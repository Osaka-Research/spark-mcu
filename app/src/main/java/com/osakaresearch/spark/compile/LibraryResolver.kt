package com.osakaresearch.spark.compile

import java.io.File

/**
 * Walks `#include` directives from a preprocessed sketch (and, transitively,
 * from any library source it pulls in) to collect the arduino-core-esp8266
 * libraries the build needs. Produces `-I` include dirs and the list of
 * library source files to compile and link.
 *
 * Deliberately non-strict — unresolved headers are ignored (they may be core
 * headers, SDK headers, or vendor libs we haven't shipped yet). The real
 * compile will fail on anything that actually matters, with a readable error.
 *
 * Header-to-library resolution is by basename: `#include <ESP8266WiFi.h>`
 * matches any header file named `ESP8266WiFi.h` under `libraries/<Lib>/src/`.
 * This mirrors how Arduino's `arduino-builder` resolves libraries in practice
 * (modulo architecture filtering, which we don't need — only esp8266 libs
 * ship in our assets).
 */
object LibraryResolver {

    data class Resolved(val includeDirs: List<File>, val sources: List<File>)

    private val INCLUDE_RE =
        Regex("""^\s*#\s*include\s*[<"]([^>"]+)[>"]""", RegexOption.MULTILINE)

    fun resolve(
        sketchSrcs: List<File>,
        coreDir: File,
        onLog: (String) -> Unit = {},
    ): Resolved {
        val libRoot = File(coreDir, "libraries")
        if (!libRoot.isDirectory) return Resolved(emptyList(), emptyList())

        val index = indexHeaders(libRoot)

        val visitedLibs = linkedSetOf<File>()   // library src dirs already pulled in
        val visitedFiles = linkedSetOf<File>()  // source/header files already scanned
        val worklist: ArrayDeque<File> = ArrayDeque()
        val librarySources = linkedSetOf<File>()

        sketchSrcs.forEach { worklist.add(it) }
        while (worklist.isNotEmpty()) {
            val file = worklist.removeFirst()
            if (!visitedFiles.add(file)) continue
            val content = try {
                file.readText()
            } catch (_: Throwable) {
                continue
            }
            for (m in INCLUDE_RE.findAll(content)) {
                val header = m.groupValues[1]
                val libSrcDir = index[headerKey(header)] ?: continue
                if (visitedLibs.add(libSrcDir)) {
                    onLog("library: ${libSrcDir.parentFile?.name ?: libSrcDir.name}")
                    libSrcDir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { f ->
                            when (f.extension.lowercase()) {
                                "cpp", "cc", "cxx", "c" -> {
                                    librarySources.add(f)
                                    worklist.add(f)
                                }
                                "h", "hpp" -> worklist.add(f)
                            }
                        }
                }
            }
        }

        return Resolved(
            includeDirs = visitedLibs.toList(),
            sources = librarySources.toList(),
        )
    }

    /**
     * `headerName` from the `#include` directive may be bare (`Servo.h`) or
     * dotted-pathed (`utility/foo.h`). Strip the path so an index keyed by
     * basename is the single source of truth.
     */
    private fun headerKey(headerName: String): String =
        headerName.substringAfterLast('/').lowercase()

    /**
     * Build a `headerBasename.lowercase() → library src dir` index. First
     * match wins (library dirs are iterated in lexicographic order, which
     * gives a stable resolution across runs).
     */
    private fun indexHeaders(libRoot: File): Map<String, File> {
        val idx = mutableMapOf<String, File>()
        libRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { lib ->
                val srcDir = File(lib, "src").takeIf { it.isDirectory } ?: lib
                srcDir.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in setOf("h", "hpp") }
                    .forEach { h ->
                        idx.putIfAbsent(h.name.lowercase(), srcDir)
                    }
            }
        return idx
    }
}
