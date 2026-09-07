package com.winlator.cmod.shared.io

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

object ArchiveExtractor {
    private const val BUFFER_SIZE = 1 shl 16
    private const val TAR_HEADER_SIZE = 512

    private val COMPOUND_SUFFIXES =
        listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.zstd")

    private val SIMPLE_SUFFIXES =
        listOf(
            ".zip", ".7z", ".tar",
            ".tgz", ".tbz2", ".tbz", ".txz", ".tzst",
            ".gz", ".bz2", ".xz", ".zst", ".zstd",
            ".wcp",
        )

    private enum class Format { ZIP, SEVENZ, XZ, ZSTD, GZIP, BZIP2, NONE }

    fun isSupported(file: File): Boolean {
        if (!file.isFile) return false
        val name = file.name.lowercase(Locale.ROOT)
        if (COMPOUND_SUFFIXES.any { name.endsWith(it) } || SIMPLE_SUFFIXES.any { name.endsWith(it) }) return true
        val head = readHead(file)
        return magicFormat(head) != Format.NONE || hasTarMagic(head)
    }

    fun baseName(file: File): String {
        val name = file.name
        val lower = name.lowercase(Locale.ROOT)
        val suffix =
            COMPOUND_SUFFIXES.firstOrNull { lower.endsWith(it) }
                ?: SIMPLE_SUFFIXES.firstOrNull { lower.endsWith(it) }
        val stripped = if (suffix != null) name.dropLast(suffix.length) else name
        return stripped.ifBlank { name }
    }

    @Throws(IOException::class)
    fun extract(
        source: File,
        destDir: File,
        onProgress: (Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        if (!destDir.exists() && !destDir.mkdirs()) throw IOException("Cannot create ${destDir.name}")
        when (val format = detectFormat(source)) {
            Format.ZIP -> extractZip(source, destDir, onProgress, isActive)
            Format.SEVENZ -> extractSevenZ(source, destDir, onProgress, isActive)
            else -> extractStream(source, destDir, format, onProgress, isActive)
        }
    }

    private fun detectFormat(source: File): Format {
        val magic = magicFormat(readHead(source))
        return if (magic != Format.NONE) magic else suffixFormat(source)
    }

    private fun magicFormat(head: ByteArray): Format =
        when {
            matches(head, 0x50, 0x4B, 0x03, 0x04) -> Format.ZIP
            matches(head, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Format.SEVENZ
            matches(head, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> Format.XZ
            matches(head, 0x28, 0xB5, 0x2F, 0xFD) -> Format.ZSTD
            matches(head, 0x1F, 0x8B) -> Format.GZIP
            matches(head, 0x42, 0x5A, 0x68) -> Format.BZIP2
            else -> Format.NONE
        }

    private fun suffixFormat(source: File): Format {
        val name = source.name.lowercase(Locale.ROOT)
        return when {
            name.endsWith(".zip") -> Format.ZIP
            name.endsWith(".7z") -> Format.SEVENZ
            name.endsWith(".gz") || name.endsWith(".tgz") -> Format.GZIP
            name.endsWith(".bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") -> Format.BZIP2
            name.endsWith(".xz") || name.endsWith(".txz") -> Format.XZ
            name.endsWith(".zst") || name.endsWith(".zstd") || name.endsWith(".tzst") -> Format.ZSTD
            else -> Format.NONE
        }
    }

    private fun matches(
        head: ByteArray,
        vararg signature: Int,
    ): Boolean {
        if (head.size < signature.size) return false
        return signature.indices.all { (head[it].toInt() and 0xFF) == signature[it] }
    }

    private fun hasTarMagic(head: ByteArray): Boolean =
        head.size >= TAR_HEADER_SIZE && String(head, 257, 5, Charsets.US_ASCII) == "ustar"

    private fun readHead(file: File): ByteArray =
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(TAR_HEADER_SIZE)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read == buffer.size) buffer else buffer.copyOf(read)
            }
        } catch (e: IOException) {
            ByteArray(0)
        }

    private fun extractZip(
        source: File,
        destDir: File,
        onProgress: (Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        ZipFile.builder().setFile(source).get().use { zip ->
            val entries = zip.entries.toList()
            val total = entries.sumOf { it.size.coerceAtLeast(0L) }.coerceAtLeast(1L)
            var done = 0L
            val reporter = ProgressReporter(onProgress)
            entries.forEach { entry ->
                if (!isActive()) throw CancellationException()
                val target = safeChild(destDir, entry.name) ?: return@forEach
                if (entry.isDirectory) {
                    target.mkdirs()
                    return@forEach
                }
                if (!zip.canReadEntryData(entry)) throw IOException("Cannot read ${entry.name}")
                zip.getInputStream(entry).use { stream ->
                    writeEntry(stream, target, isActive) { written ->
                        done += written
                        reporter.report(done.toFloat() / total)
                    }
                }
            }
        }
        onProgress(1f)
    }

    private fun extractSevenZ(
        source: File,
        destDir: File,
        onProgress: (Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        SevenZFile.builder().setFile(source).get().use { archive ->
            val total = archive.entries.sumOf { it.size.coerceAtLeast(0L) }.coerceAtLeast(1L)
            var done = 0L
            val reporter = ProgressReporter(onProgress)
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (!isActive()) throw CancellationException()
                val entry = archive.nextEntry ?: break
                val target = safeChild(destDir, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    while (true) {
                        if (!isActive()) throw CancellationException()
                        val read = archive.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        reporter.report(done.toFloat() / total)
                    }
                }
            }
        }
        onProgress(1f)
    }

    private fun extractStream(
        source: File,
        destDir: File,
        format: Format,
        onProgress: (Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        val total = source.length().coerceAtLeast(1L)
        val counting = CountingInputStream(FileInputStream(source))
        val decompressed = wrapCompressor(format, BufferedInputStream(counting, BUFFER_SIZE))
        BufferedInputStream(decompressed, BUFFER_SIZE).use { stream ->
            val reporter = ProgressReporter(onProgress)
            val progress = { reporter.report(counting.count.toFloat() / total) }
            if (looksLikeTar(stream)) {
                extractTar(stream, destDir, isActive, progress)
            } else {
                val target = File(destDir, baseName(source))
                writeEntry(stream, target, isActive) { progress() }
            }
        }
        onProgress(1f)
    }

    private fun wrapCompressor(
        format: Format,
        stream: InputStream,
    ): InputStream =
        when (format) {
            Format.GZIP -> GzipCompressorInputStream(stream, true)
            Format.BZIP2 -> BZip2CompressorInputStream(stream, true)
            Format.XZ -> XZCompressorInputStream(stream, true)
            Format.ZSTD -> ZstdCompressorInputStream(stream)
            else -> stream
        }

    private fun looksLikeTar(stream: BufferedInputStream): Boolean {
        stream.mark(TAR_HEADER_SIZE + 1)
        val header = ByteArray(TAR_HEADER_SIZE)
        var read = 0
        while (read < TAR_HEADER_SIZE) {
            val n = stream.read(header, read, TAR_HEADER_SIZE - read)
            if (n < 0) break
            read += n
        }
        stream.reset()
        if (read < TAR_HEADER_SIZE) return false
        val magic = String(header, 257, 5, Charsets.US_ASCII)
        return magic == "ustar"
    }

    private fun extractTar(
        stream: InputStream,
        destDir: File,
        isActive: () -> Boolean,
        onProgress: () -> Unit,
    ) {
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                if (!isActive()) throw CancellationException()
                val entry = tar.nextEntry ?: break
                if (entry.isSymbolicLink || entry.isLink) continue
                val target = safeChild(destDir, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                if (!entry.isFile) continue
                writeEntry(tar, target, isActive) { onProgress() }
            }
        }
    }

    private fun writeEntry(
        stream: InputStream,
        target: File,
        isActive: () -> Boolean,
        onProgress: (Int) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val buffer = ByteArray(BUFFER_SIZE)
        target.outputStream().use { out ->
            while (true) {
                if (!isActive()) throw CancellationException()
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                onProgress(read)
            }
        }
    }

    private fun safeChild(
        destDir: File,
        entryName: String,
    ): File? {
        val normalized = entryName.replace('\\', '/').trim()
        if (normalized.isEmpty() || normalized.startsWith("/")) return null
        if (normalized.split('/').any { it == ".." }) return null
        val target = File(destDir, normalized)
        val root = destDir.canonicalPath
        val path = target.canonicalPath
        return if (path == root || path.startsWith(root + File.separator)) target else null
    }

    private class ProgressReporter(private val onProgress: (Float) -> Unit) {
        private var lastPercent = -1

        fun report(fraction: Float) {
            val clamped = fraction.coerceIn(0f, 1f)
            val percent = (clamped * 100).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(clamped)
            }
        }
    }

    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) count++
            return b
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun available(): Int = delegate.available()

        override fun close() = delegate.close()
    }
}
