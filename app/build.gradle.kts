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
val s3ToolchainTag = (project.findProperty("spark.s3.toolchain.releaseTag") as String?).orEmpty()
val s3ToolchainAsset = "xtensa-esp32s3-elf-android-arm64.tar.xz"

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
    if (!skipToolchainFetch || tarball.exists()) inputs.file(tarball)
    outputs.file(stamp)
    outputs.dir(File(toolchainAssetsDir, "xtensa-lx106-elf"))
    outputs.dir(File(toolchainAssetsDir, "arduino-core-esp8266"))

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

        toolchainAssetsDir.mkdirs()
        listOf("xtensa-lx106-elf", "arduino-core-esp8266").forEach { name ->
            val src = File(stage, name)
            if (src.isDirectory) {
                val dest = File(toolchainAssetsDir, name)
                dest.deleteRecursively()
                src.copyRecursively(dest, overwrite = true)
                logger.lifecycle("  -> staged assets/toolchain/$name")
            }
        }
        File(stage, "BOOTSTRAP_SOURCE.txt").takeIf { it.exists() }
            ?.copyTo(File(toolchainAssetsDir, "BOOTSTRAP_SOURCE.txt"), overwrite = true)

        stamp.writeText("tag=$toolchainTag\nsha256=${sha256(tarball)}\n")
    }
}

tasks.register("fetchS3Toolchain") {
    group = "spark"
    val tarball = File(toolchainCacheDir, s3ToolchainAsset)
    onlyIf { s3ToolchainTag.isNotBlank() && (!tarball.exists() || tarball.length() < 1024) }
    doLast {
        toolchainCacheDir.mkdirs()
        val url = "https://github.com/$toolchainRepo/releases/download/$s3ToolchainTag/$s3ToolchainAsset"
        val tmp = File(tarball.parentFile, "$s3ToolchainAsset.part")
        downloadFollowingRedirects(url, tmp)
        if (!tmp.renameTo(tarball)) throw GradleException("cannot install $s3ToolchainAsset")
    }
}

tasks.register("extractS3Toolchain") {
    group = "spark"
    dependsOn("extractXtensaToolchain", "fetchS3Toolchain")
    val tarball = File(toolchainCacheDir, s3ToolchainAsset)
    val stamp = File(toolchainCacheDir, "$s3ToolchainAsset.extracted")
    onlyIf { tarball.exists() && tarball.length() >= 1024 }
    inputs.file(tarball).optional()
    outputs.file(stamp)
    outputs.dir(File(toolchainAssetsDir, "xtensa-esp32s3-elf"))
    outputs.dir(File(toolchainAssetsDir, "arduino-core-esp32"))
    doLast {
        val stage = File(toolchainCacheDir, "stage-s3").apply {
            deleteRecursively(); mkdirs()
        }
        // Android app-private storage rejects hard links. The cached GCC
        // archive has a few alias entries, so extract their targets and copy
        // the aliases as ordinary files after tar finishes.
        val hardLinkAliases = mapOf(
            "bin/xtensa-esp32s3-elf-gcc-12.2.0" to "bin/xtensa-esp32s3-elf-gcc",
            "bin/xtensa-esp32s3-elf-ld" to "bin/xtensa-esp32s3-elf-ld.bfd",
            "bin/xtensa-esp32s3-elf-c++" to "bin/xtensa-esp32s3-elf-g++",
            "xtensa-esp32s3-elf/lib/libg.a" to "xtensa-esp32s3-elf/lib/libc.a",
            "xtensa-esp32s3-elf/bin/ld" to "xtensa-esp32s3-elf/bin/ld.bfd",
        )
        exec {
            commandLine(
                listOf("tar") + hardLinkAliases.keys.map { "--exclude=$it" } +
                    listOf("-xJf", tarball.absolutePath, "-C", stage.absolutePath)
            )
        }
        hardLinkAliases.forEach { (alias, original) ->
            val source = File(stage, original)
            if (source.isFile) {
                val destination = File(stage, alias)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
                destination.setExecutable(source.canExecute())
            }
        }
        val target = "xtensa-esp32s3-elf"
        val binDir = File(stage, "bin")
        val targetDir = File(stage, target)
        val coreDir = File(stage, "arduino-core-esp32")
        if (!binDir.isDirectory || !targetDir.isDirectory || !coreDir.isDirectory) {
            throw GradleException("S3 tarball needs bin/, $target/, and arduino-core-esp32/ at root")
        }
        verifyS3AndroidExecutable(File(binDir, "$target-gcc"))
        verifyLittleEndianArchive(File(stage, "lib/gcc/$target/12.2.0/libgcc.a"))
        verifyLittleEndianArchive(File(targetDir, "lib/libc.a"))
        verifyLittleEndianArchive(File(targetDir, "lib/libstdc++.a"))
        val cxxRuntime = File(stage, "libc++_shared.so")
        val cxxDestination = File(toolchainJniLibsDir, "libc++_shared.so")
        if (!cxxRuntime.isFile) {
            throw GradleException("S3 toolchain archive is missing libc++_shared.so")
        }
        // Remove helpers and versioned aliases from the previous S3 archive.
        toolchainJniLibsDir.listFiles()?.filter {
            it.name.startsWith("lib$target-") || it.name.startsWith("libs3_")
        }?.forEach { it.delete() }
        cxxRuntime.copyTo(cxxDestination, overwrite = true)
        binDir.listFiles()?.filter { it.isFile }?.forEach { bin ->
            verifyS3AndroidExecutable(bin)
            val helper = bin.name in setOf("as", "ld", "cc1", "cc1plus", "collect2")
            val dest = File(toolchainJniLibsDir, "lib${if (helper) "s3_" else ""}${bin.name}.so")
            bin.copyTo(dest, overwrite = true)
            dest.setExecutable(true)
        }
        val helperSources = listOf(
            // The target/bin helpers in the staged GCC archive are the
            // x86_64-hosted originals. Use the Android-hosted cross tools
            // from bin/ for the helpers that run on the phone.
            File(binDir, "$target-as") to "as",
            File(binDir, "$target-ld") to "ld",
            File(stage, "libexec/gcc/$target/12.2.0/cc1") to "cc1",
            File(stage, "libexec/gcc/$target/12.2.0/cc1plus") to "cc1plus",
            File(stage, "libexec/gcc/$target/12.2.0/collect2") to "collect2",
            File(stage, "libexec/gcc/$target/12.2.0/lto1") to "lto1",
            File(stage, "libexec/gcc/$target/12.2.0/lto-wrapper") to "lto-wrapper",
        )
        helperSources.forEach { (src, name) ->
            if (!src.isFile) {
                if (name in setOf("lto1", "lto-wrapper")) return@forEach
                throw GradleException("S3 toolchain missing helper: ${src.absolutePath}")
            }
            verifyS3AndroidExecutable(src)
            src.copyTo(File(toolchainJniLibsDir, "libs3_$name.so"), overwrite = true)
                .setExecutable(true)
        }
        val assets = File(toolchainAssetsDir, target)
        assets.deleteRecursively()
        targetDir.copyRecursively(assets, overwrite = true)
        val gccLibs = File(stage, "lib/gcc/$target")
        if (gccLibs.isDirectory) {
            gccLibs.copyRecursively(File(assets, "lib/gcc/$target"), overwrite = true)
        }
        val coreAssets = File(toolchainAssetsDir, "arduino-core-esp32")
        coreAssets.deleteRecursively()
        coreDir.copyRecursively(coreAssets, overwrite = true)
        stamp.writeText("sha256=${sha256(tarball)}\n")
        stage.deleteRecursively()
    }
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn("extractS3Toolchain")
    }
}

fun verifyS3AndroidExecutable(binary: File) {
    val header = binary.inputStream().use { it.readNBytes(20) }
    val arm64Pie = header.size == 20 &&
        header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
        header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
        header[4] == 2.toByte() && header[5] == 1.toByte() &&
        header[16] == 3.toByte() && header[17] == 0.toByte() &&
        header[18] == 183.toByte() && header[19] == 0.toByte()
    if (!arm64Pie) {
        throw GradleException("S3 tool ${binary.name} must be an AArch64 PIE executable for Android")
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

fun verifyLittleEndianArchive(archive: File) {
    if (!archive.isFile) throw GradleException("S3 toolchain missing ${archive.name}")
    val bytes = archive.readBytes()
    val magic = "!<arch>\n".toByteArray(Charsets.US_ASCII)
    if (bytes.size < magic.size || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
        throw GradleException("${archive.name} is not an ar archive")
    }
    var pos = magic.size
    var elfMembers = 0
    while (pos + 60 <= bytes.size) {
        val size = bytes.copyOfRange(pos + 48, pos + 58)
            .toString(Charsets.US_ASCII).trim().toIntOrNull()
            ?: throw GradleException("invalid member size in ${archive.name}")
        val body = pos + 60
        if (size < 0 || body + size > bytes.size) {
            throw GradleException("truncated member in ${archive.name}")
        }
        if (size >= 6 && bytes[body] == 0x7f.toByte() &&
            bytes[body + 1] == 'E'.code.toByte() &&
            bytes[body + 2] == 'L'.code.toByte() &&
            bytes[body + 3] == 'F'.code.toByte()
        ) {
            elfMembers++
            if (bytes[body + 5] != 1.toByte()) {
                throw GradleException("${archive.name} contains a non-little-endian object; rebuild the S3 toolchain")
            }
        }
        pos = body + size + (size and 1)
    }
    if (elfMembers == 0) throw GradleException("${archive.name} has no ELF objects to verify")
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

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.nanohttpd:nanohttpd:2.3.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
