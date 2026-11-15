import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// --- xtensa toolchain fetcher -----------------------------------------------
// Pulls a release tarball from GitHub, extracts it, and places:
//   bin/*                       → src/main/jniLibs/arm64-v8a/lib<name>.so
//   xtensa-lx106-elf/           → src/main/assets/toolchain/xtensa-lx106-elf/
//   arduino-core-esp8266/       → src/main/assets/toolchain/arduino-core-esp8266/
//
// Source-agnostic: works for bootstrap tarballs (ArduinoDroid-derived,
// dev-only) and for the final Canadian-cross build. Flip the release tag
// via spark.toolchain.releaseTag in gradle.properties.
//
// Binaries land under lib/arm64-v8a/ because Android only permits exec on
// files extracted from APK native library dirs. At runtime, a Kotlin init
// step re-exposes them with their conventional unprefixed names via
// hardlinks in filesDir — see phase 4.
val toolchainRepo = (project.findProperty("spark.toolchain.githubRepo") as String?) ?: ""
val toolchainTag = (project.findProperty("spark.toolchain.releaseTag") as String?) ?: ""
val toolchainAsset = (project.findProperty("spark.toolchain.asset") as String?) ?: ""
val skipToolchainFetch = (project.findProperty("spark.toolchain.skipFetch") as String?)?.toBoolean() ?: false

val toolchainCacheDir = layout.buildDirectory.dir("toolchain-cache").get().asFile
val toolchainJniLibsDir = file("src/main/jniLibs/arm64-v8a")
val toolchainAssetsDir = file("src/main/assets/toolchain")

tasks.register("fetchXtensaToolchain") {
    group = "spark"
    description = "Download xtensa-lx106-elf toolchain tarball from the configured GH release."

    val tarball = File(toolchainCacheDir, "$toolchainTag-$toolchainAsset")

    // Don't use outputs.file() here — Gradle's stale-output cleanup will
    // delete pre-seeded tarballs the developer dropped into the cache.
    // Instead, gate with onlyIf: run only when the tarball is missing.
    onlyIf {
        skipToolchainFetch.not() && (!tarball.exists() || tarball.length() < 1024)
    }

    doLast {
        if (toolchainRepo.isBlank() || toolchainTag.isBlank() || toolchainAsset.isBlank()) {
            throw GradleException("toolchain fetch misconfigured: set spark.toolchain.{githubRepo,releaseTag,asset} in gradle.properties")
        }
        toolchainCacheDir.mkdirs()
        val url = "https://github.com/$toolchainRepo/releases/download/$toolchainTag/$toolchainAsset"
        logger.lifecycle("Fetching $url")
        val tmp = File(tarball.parentFile, tarball.name + ".part")
        downloadFollowingRedirects(url, tmp)
        tmp.renameTo(tarball)
        logger.lifecycle("  -> ${tarball.absolutePath} (${tarball.length()} bytes, sha256=${sha256(tarball).take(16)}...)")
    }
}

tasks.register("extractXtensaToolchain") {
    group = "spark"
    description = "Extract fetched toolchain into jniLibs/arm64-v8a and assets/toolchain."
    dependsOn("fetchXtensaToolchain")

    val tarball = File(toolchainCacheDir, "$toolchainTag-$toolchainAsset")
    val stamp = File(toolchainCacheDir, "$toolchainTag.extracted")
    inputs.file(tarball)
    outputs.file(stamp)
    outputs.dir(toolchainJniLibsDir)
    outputs.dir(toolchainAssetsDir)

    doLast {
        if (skipToolchainFetch && !tarball.exists()) {
            logger.lifecycle("skipFetch + no cached tarball — assuming jniLibs is pre-populated")
            return@doLast
        }
        val stage = File(toolchainCacheDir, "stage-$toolchainTag")
        stage.deleteRecursively()
        stage.mkdirs()
        exec {
            commandLine("tar", "-xJf", tarball.absolutePath, "-C", stage.absolutePath)
        }

        toolchainJniLibsDir.deleteRecursively()
        toolchainJniLibsDir.mkdirs()
        val binDir = File(stage, "bin")
        if (!binDir.isDirectory) throw GradleException("tarball missing bin/ dir")
        var count = 0
        binDir.listFiles()?.forEach { bin ->
            if (!bin.isFile) return@forEach
            val dest = File(toolchainJniLibsDir, "lib${bin.name}.so")
            bin.copyTo(dest, overwrite = true)
            dest.setExecutable(true)
            count++
        }
        logger.lifecycle("  -> wrote $count binaries to ${toolchainJniLibsDir.relativeTo(projectDir)}")

        toolchainAssetsDir.deleteRecursively()
        toolchainAssetsDir.mkdirs()
        listOf("xtensa-lx106-elf", "arduino-core-esp8266").forEach { name ->
            val src = File(stage, name)
            if (src.isDirectory) {
                src.copyRecursively(File(toolchainAssetsDir, name), overwrite = true)
                logger.lifecycle("  -> staged assets/toolchain/$name")
            }
        }
        File(stage, "BOOTSTRAP_SOURCE.txt").takeIf { it.exists() }
            ?.copyTo(File(toolchainAssetsDir, "BOOTSTRAP_SOURCE.txt"), overwrite = true)

        stamp.writeText("tag=$toolchainTag\nsha256=${sha256(tarball)}\n")
    }
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn("extractXtensaToolchain")
    }
}

fun downloadFollowingRedirects(url: String, dest: File, maxHops: Int = 5) {
    var current = url
    repeat(maxHops) {
        val conn = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "spark-gradle-fetch")
        }
        when (conn.responseCode) {
            in 200..299 -> {
                conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                return
            }
            in 300..399 -> {
                current = conn.getHeaderField("Location")
                    ?: throw GradleException("redirect without Location from $current")
            }
            else -> throw GradleException("HTTP ${conn.responseCode} fetching $current")
        }
    }
    throw GradleException("too many redirects fetching $url")
}

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { s ->
        val buf = ByteArray(8192)
        while (true) { val n = s.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

android {
    namespace = "com.osakaresearch.spark"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.osakaresearch.spark"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Toolchain binaries are shipped as lib*.so under jniLibs so Android
        // will mark them exec, but we need them on disk at nativeLibraryDir
        // to list and symlink. Legacy packaging extracts at install time.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
