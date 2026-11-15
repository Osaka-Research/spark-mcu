package com.osakaresearch.spark.compile

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves the on-device xtensa-lx106-elf toolchain installed via jniLibs + assets
 * by the Gradle `extractXtensaToolchain` task. See firmware/bootstrap/ for how
 * the tarball is produced during the scaffold phase.
 *
 * Android puts files from `app/src/main/jniLibs/arm64-v8a/lib<name>.so` into
 * [applicationInfo.nativeLibraryDir] with a selinux context that permits exec.
 * We can't rename them there (read-only after install), so on first use we
 * symlink each `lib<name>.so` to its unprefixed name inside [filesDir] and tell
 * gcc to look there via `-B`. The sysroot and Arduino core sources come from
 * APK assets and get copied out once.
 */
object Toolchain {

    private const val SUBDIR = "toolchain"
    private const val STAMP = ".prepared"

    // Monotonic version. Bump if the asset/jniLibs shape changes so the
    // on-device scratch area gets rebuilt on next launch.
    private const val VERSION = 6

    fun versionTag(): String = "v$VERSION"

    private val prepareMutex = Mutex()

    fun root(ctx: Context): File = File(ctx.filesDir, SUBDIR)
    fun binDir(ctx: Context): File = File(root(ctx), "bin")
    fun sysroot(ctx: Context): File = File(root(ctx), "sysroot")
    fun coreDir(ctx: Context): File = File(root(ctx), "core")
    fun binDir(ctx: Context, target: McuTarget): File =
        if (target == McuTarget.ESP32_S3) File(root(ctx), "bin-s3") else binDir(ctx)
    fun sysroot(ctx: Context, target: McuTarget): File =
        if (target == McuTarget.ESP32_S3) File(root(ctx), "sysroot-s3") else sysroot(ctx)
    fun coreDir(ctx: Context, target: McuTarget): File =
        if (target == McuTarget.ESP32_S3) File(root(ctx), "core-s3") else coreDir(ctx)

    /** Full path to a toolchain binary once [ensurePrepared] has run. */
    fun bin(ctx: Context, name: String): File = File(binDir(ctx), name)
    fun bin(ctx: Context, name: String, target: McuTarget): File =
        File(binDir(ctx, target), name)

    /**
     * Idempotent. On first call, creates symlinks from `filesDir/toolchain/bin/<name>`
     * into the APK's native library dir, and extracts the `assets/toolchain/` tree under
     * `filesDir/toolchain/`. Subsequent calls are O(1).
     */
    suspend fun ensurePrepared(ctx: Context) = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            val root = root(ctx)
            val stamp = File(root, STAMP)
            // nativeLibraryDir gets a new random suffix on every reinstall, so
            // symlinks from a previous install become dangling. Bake the path
            // into the stamp key so a reinstall forces a full re-prepare.
            val key = "$VERSION:${ctx.applicationInfo.nativeLibraryDir}"
            if (stamp.exists() && stamp.readText().trim() == key) return@withLock

            // Fresh slate — don't risk partial state from a prior aborted prepare.
            root.deleteRecursively()
            root.mkdirs()

            linkJniBinaries(ctx)
            copyAssetTree(ctx, "toolchain/xtensa-lx106-elf", sysroot(ctx))
            copyAssetTree(ctx, "toolchain/arduino-core-esp8266", coreDir(ctx))
            if (ctx.assets.list("toolchain/xtensa-esp32s3-elf").orEmpty().isNotEmpty()) {
                copyAssetTree(ctx, "toolchain/xtensa-esp32s3-elf", sysroot(ctx, McuTarget.ESP32_S3))
                // GCC locates its target headers and libgcc relative to its
                // executable. Keep that prefix layout despite Android's
                // requirement that executable files live in nativeLibraryDir.
                val s3Sysroot = sysroot(ctx, McuTarget.ESP32_S3)
                Os.symlink(s3Sysroot.absolutePath, File(root, McuTarget.ESP32_S3.toolchainTriple).absolutePath)
                Os.symlink(File(s3Sysroot, "lib").absolutePath, File(root, "lib").absolutePath)
            }
            if (ctx.assets.list("toolchain/arduino-core-esp32").orEmpty().isNotEmpty()) {
                copyAssetTree(ctx, "toolchain/arduino-core-esp32", coreDir(ctx, McuTarget.ESP32_S3))
            }

            stamp.writeText(key)
        }
    }

    private fun linkJniBinaries(ctx: Context) {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val bin = binDir(ctx).apply { mkdirs() }
        val libs = nativeDir.listFiles { f ->
            f.name.startsWith("lib") && f.name.endsWith(".so")
        } ?: throw IllegalStateException("no native libs at ${nativeDir.absolutePath}")

        var linked = 0
        for (lib in libs) {
            val unprefixed = lib.name.removePrefix("lib").removeSuffix(".so")
            // Skip Android framework / unrelated libs that happen to live in nativeLibraryDir.
            val s3 = unprefixed.startsWith("s3_") ||
                unprefixed.startsWith("${McuTarget.ESP32_S3.toolchainTriple}-")
            val name = unprefixed.removePrefix("s3_")
            if (!looksLikeToolchainBinary(name)) continue
            val link = File(if (s3) binDir(ctx, McuTarget.ESP32_S3).apply { mkdirs() } else bin, name)
            if (link.exists()) link.delete()
            try {
                Os.symlink(lib.absolutePath, link.absolutePath)
                linked++
            } catch (e: Exception) {
                // Fall back to copying if symlink isn't permitted on this ROM.
                lib.copyTo(link, overwrite = true)
                link.setExecutable(true)
                linked++
            }
        }
        if (linked == 0) {
            throw IllegalStateException(
                "no toolchain binaries found among ${libs.size} native libs. " +
                "Did ./gradlew extractXtensaToolchain populate jniLibs?"
            )
        }
    }

    private fun looksLikeToolchainBinary(unprefixed: String): Boolean {
        // Matches target cross-tools (xtensa-*-elf-{gcc,g++,ar,objcopy,...})
        // plus gcc helpers (as, ld, cc1, cc1plus, collect2). The prefix set
        // widens as new McuTargets land — see McuTarget.toolchainTriple.
        if (unprefixed in setOf("as", "ld", "cc1", "cc1plus", "collect2", "lto1", "lto-wrapper")) return true
        return McuTarget.values().any { unprefixed.startsWith("${it.toolchainTriple}-") }
    }

    private fun copyAssetTree(ctx: Context, assetPath: String, outDir: File) {
        outDir.mkdirs()
        val am = ctx.assets
        val entries = am.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            // Treat as file (AssetManager can't disambiguate empty-dir vs file).
            am.open(assetPath).use { input ->
                outDir.outputStream().use { input.copyTo(it) }
            }
            return
        }
        for (name in entries) {
            val child = "$assetPath/$name"
            val children = am.list(child).orEmpty()
            val dest = File(outDir, name)
            if (children.isEmpty()) {
                // Leaf — file.
                am.open(child).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            } else {
                copyAssetTree(ctx, child, dest)
            }
        }
    }
}
