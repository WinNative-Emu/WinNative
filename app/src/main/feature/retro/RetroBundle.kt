package com.winlator.cmod.feature.retro

import android.content.Context
import android.widget.Toast
import com.winlator.cmod.R
import com.winlator.cmod.shared.io.TarCompressorUtils
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

object RetroBundle {
    private const val REPO = "WinNative-Emu/Retro-Consoles"
    private const val DOWNLOADS = "https://github.com/$REPO/releases/download"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=100"
    private const val ARCHIVE = "retro-consoles.tzst"
    private const val INFO = "bundle-info.json"
    private const val PREFS = "retro_bundle"
    private const val KEY_CHANNEL = "channel"

    const val LATEST = "latest"

    fun root(context: Context): File = File(context.filesDir, "retro/bundle")

    fun coresDir(context: Context): File = File(root(context), "cores")

    fun dataDir(context: Context): File = File(root(context), "data")

    data class Version(
        val tag: String,
        val buildDate: String,
        val sha256: String,
        val size: Long,
    ) {
        val day: String get() = buildDate.substringBefore('T')

        fun toJson(): String =
            JSONObject()
                .put("tag", tag)
                .put("buildDate", buildDate)
                .put("sha256", sha256)
                .put("size", size)
                .toString()

        companion object {
            fun parse(json: String): Version {
                val o = JSONObject(json)
                return Version(
                    tag = o.getString("tag"),
                    buildDate = o.getString("buildDate"),
                    sha256 = o.getString("sha256").lowercase(),
                    size = o.getLong("size"),
                )
            }
        }
    }

    private fun marker(context: Context): File = File(root(context), ".installed")

    fun isInstalled(context: Context): Boolean = installed(context) != null

    fun requireInstalled(context: Context): Boolean {
        if (isInstalled(context)) return true
        Toast.makeText(context, context.getString(R.string.retro_bundle_required), Toast.LENGTH_LONG).show()
        return false
    }

    fun installed(context: Context): Version? =
        runCatching { Version.parse(marker(context).readText()) }.getOrNull()

    fun published(tag: String = LATEST): Result<Version> =
        runCatching { Version.parse(fetchText("$DOWNLOADS/$tag/$INFO")) }

    data class Release(
        val tag: String,
        val day: String,
        val build: String,
    ) {
        val label: String get() = if (build.isEmpty()) day else "$day ($build)"
    }

    fun releases(): Result<List<Release>> =
        runCatching {
            val array = JSONArray(fetchText(RELEASES_API))
            (0 until array.length())
                .asSequence()
                .map { array.getJSONObject(it) }
                .filterNot { it.optBoolean("draft") }
                .map { it.optString("tag_name") }
                .filter { it.startsWith("bundle-") }
                .distinct()
                .sortedDescending()
                .map { tag ->
                    val parts = tag.removePrefix("bundle-").split('-')
                    val stamp = parts.getOrElse(0) { "" }
                    Release(
                        tag = tag,
                        day =
                            if (stamp.length == 8) {
                                "${stamp.take(4)}-${stamp.substring(4, 6)}-${stamp.substring(6)}"
                            } else {
                                stamp
                            },
                        build = parts.getOrElse(1) { "" },
                    )
                }
                .toList()
        }

    fun selectedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHANNEL, LATEST) ?: LATEST

    fun selectTag(
        context: Context,
        tag: String,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHANNEL, tag).apply()
    }

    sealed class Progress {
        data class Downloading(val bytes: Long, val total: Long) : Progress()

        data object Verifying : Progress()

        data object Extracting : Progress()
    }

    fun install(
        context: Context,
        version: Version,
        tag: String = LATEST,
        onProgress: (Progress) -> Unit = {},
    ): Result<Version> =
        runCatching {
            val work = File(context.cacheDir, "retro-bundle").apply { deleteRecursively(); mkdirs() }
            val archive = File(work, ARCHIVE)

            download("$DOWNLOADS/$tag/$ARCHIVE", archive) { got, total ->
                onProgress(Progress.Downloading(got, if (total > 0) total else version.size))
            }

            onProgress(Progress.Verifying)
            val actual = sha256(archive)
            if (!actual.equals(version.sha256, ignoreCase = true)) {
                throw IllegalStateException("Bundle checksum mismatch: expected ${version.sha256}, got $actual")
            }

            onProgress(Progress.Extracting)
            val staging = File(work, "stage").apply { mkdirs() }
            val ok =
                TarCompressorUtils
                    .extractAsync(TarCompressorUtils.Type.ZSTD, archive, staging)
                    .get()
            if (ok != true) throw IllegalStateException("Bundle extraction failed")

            val destination = root(context)
            destination.deleteRecursively()
            destination.parentFile?.mkdirs()
            if (!staging.renameTo(destination)) {
                staging.copyRecursively(destination, overwrite = true)
                staging.deleteRecursively()
            }
            marker(context).writeText(version.toJson())
            work.deleteRecursively()
            version
        }

    fun uninstall(context: Context) {
        root(context).deleteRecursively()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WinNative")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun fetchText(url: String): String =
        open(url).let { c ->
            try {
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }

    private fun download(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val connection = open(url)
        try {
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var got = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        got += read
                        if (got - lastReport >= 1L shl 20) {
                            lastReport = got
                            onProgress(got, total)
                        }
                    }
                    onProgress(got, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
