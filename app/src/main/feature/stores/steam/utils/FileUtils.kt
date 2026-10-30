package com.winlator.cmod.feature.stores.steam.utils

import android.content.res.AssetManager
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object FileUtils {
    fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        try {
            if (!directory.exists() || !directory.isDirectory) return 0L
            val files = directory.listFiles() ?: return 0L
            for (file in files) {
                size += if (file.isDirectory) calculateDirectorySize(file) else file.length()
            }
        } catch (e: Exception) {
            Timber.w(e, "Error calculating directory size for ${directory.name}")
        }
        return size
    }

    fun makeDir(dirName: String) {
        File(dirName).mkdirs()
    }

    fun makeFile(
        fileName: String,
        errorTag: String? = "FileUtils",
        errorMsg: ((Exception) -> String)? = null,
    ) {
        try {
            val file = File(fileName)
            if (!file.exists()) file.createNewFile()
        } catch (e: Exception) {
            Timber.e("%s encountered an issue in makeFile()", errorTag)
            Timber.e(errorMsg?.invoke(e) ?: "Error creating file: $e")
        }
    }

    fun createPathIfNotExist(filepath: String) {
        val file = File(filepath)
        var dirs = filepath
        if (!filepath.endsWith('/') && filepath.lastIndexOf('/') > 0) {
            file.parent?.let { dirs = it }
        }
        makeDir(dirs)
    }

    fun readFileAsString(
        path: String,
        errorTag: String = "FileUtils",
        errorMsg: ((Exception) -> String)? = null,
    ): String? {
        var fileData: String? = null
        try {
            val reader = BufferedReader(FileReader(path))
            val total = StringBuilder()
            var line: String?
            while ((reader.readLine().also { line = it }) != null) {
                total.append(line).append('\n')
            }
            fileData = total.toString()
            reader.close()
        } catch (e: Exception) {
            Timber.e("%s encountered an issue in readFileAsString()", errorTag)
            Timber.e(errorMsg?.invoke(e) ?: "Error reading file: $e")
        }
        return fileData
    }

    fun writeStringToFile(
        data: String,
        path: String,
        errorTag: String? = "FileUtils",
        errorMsg: ((Exception) -> String)? = null,
    ) {
        createPathIfNotExist(path)
        try {
            val output = FileOutputStream(path)
            val writer = OutputStreamWriter(output)
            writer.append(data)
            writer.close()
            output.flush()
            output.close()
        } catch (e: Exception) {
            Timber.e("%s encountered an issue in writeStringToFile()", errorTag)
            Timber.e(errorMsg?.invoke(e) ?: "Error writing to file: $e")
        }
    }

    fun walkThroughPath(
        rootPath: Path,
        maxDepth: Int = 0,
        action: (Path) -> Unit,
    ) {
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) return
        Files.list(rootPath).use { fileList ->
            fileList.forEach {
                action(it)
                if (maxDepth != 0 && it.exists() && it.isDirectory()) {
                    walkThroughPath(
                        rootPath = it,
                        maxDepth = if (maxDepth > 0) maxDepth - 1 else maxDepth,
                        action = action,
                    )
                }
            }
        }
    }

    fun findFiles(
        rootPath: Path,
        pattern: String,
        includeDirectories: Boolean = false,
    ): Stream<Path> {
        val patternParts = pattern.split("*").filter { it.isNotEmpty() }
        if (!Files.exists(rootPath)) return emptyList<Path>().stream()
        return Files.list(rootPath).filter { path ->
            if (path.isDirectory() && !includeDirectories) {
                false
            } else {
                val fileName = path.name
                var startIndex = 0
                !patternParts
                    .map {
                        val index = fileName.indexOf(it, startIndex)
                        if (index >= 0) startIndex = index + it.length
                        index
                    }.any { it < 0 }
            }
        }
    }

    fun findFilesRecursive(
        rootPath: Path,
        pattern: String,
        maxDepth: Int = -1,
        includeDirectories: Boolean = false,
    ): Stream<Path> {
        if (!Files.exists(rootPath)) return emptyList<Path>().stream()

        val results = mutableListOf<Path>()
        val matcher = compilePatternMatcher(pattern)

        walkThroughPath(rootPath, maxDepth) { path ->
            if (path.isDirectory()) {
                if (includeDirectories && matcher(path.name)) results.add(path)
            } else if (matcher(path.name)) {
                results.add(path)
            }
        }

        return results.stream()
    }

    /**
     * Short-circuiting variant of [findFilesRecursive] for "does anything match?"
     * checks. Stops walking as soon as the first match is seen — wrapping the
     * full-collect helper with `findAny()` would still scan the entire tree
     * because [findFilesRecursive] returns an already-materialized list.
     */
    fun anyFileMatches(
        rootPath: Path,
        pattern: String,
        maxDepth: Int = -1,
        includeDirectories: Boolean = false,
    ): Boolean {
        if (!Files.exists(rootPath)) return false
        val matcher = compilePatternMatcher(pattern)
        return try {
            walkThroughPath(rootPath, maxDepth) { path ->
                val matched =
                    if (path.isDirectory()) includeDirectories && matcher(path.name)
                    else matcher(path.name)
                if (matched) throw FoundMatch
            }
            false
        } catch (_: FoundMatch) {
            true
        }
    }

    private object FoundMatch : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun compilePatternMatcher(pattern: String): (String) -> Boolean {
        val parts = pattern.split("*").filter { it.isNotEmpty() }
        return { fileName ->
            var startIndex = 0
            var ok = true
            for (part in parts) {
                val index = fileName.indexOf(part, startIndex)
                if (index < 0) {
                    ok = false
                    break
                }
                startIndex = index + part.length
            }
            ok
        }
    }

    fun assetExists(
        assetManager: AssetManager,
        assetPath: String,
    ): Boolean =
        try {
            assetManager.open(assetPath).use { true }
        } catch (_: IOException) {
            false
        }

    fun findFileCaseInsensitive(
        baseDir: File,
        relativePath: String,
    ): File? {
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        var current = baseDir
        for (segment in segments) {
            val match = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
            current = match
        }
        return current.takeIf { it.exists() }
    }
}
