package com.winlator.cmod.app.update

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateDownloader {
    private const val WORK_DIR = "winnative-update"
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 60000
    private const val BUFFER = 1 shl 16
    private const val PROGRESS_STEP = 1L shl 20

    fun workDir(context: Context): File = File(context.cacheDir, WORK_DIR)

    fun clear(context: Context) {
        workDir(context).deleteRecursively()
    }

    fun fetchText(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode} for $url")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * As [fetchText], except that a release which is not there answers null instead of raising.
     * A pull request's release goes away when it is merged or closed, and that is an answer, not
     * a fault to report.
     */
    fun fetchTextOrNull(url: String): String? {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE) return null
            if (code !in 200..299) throw IllegalStateException("HTTP $code for $url")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun downloadApk(
        context: Context,
        release: UpdateRelease,
        onProgress: (Long, Long) -> Unit,
    ): Result<File> =
        runCatching {
            val work = workDir(context).apply { deleteRecursively(); mkdirs() }
            val target = File(work, "update.apk")
            download(release.apkUrl, target, onProgress)
            if (release.apkSize > 0 && target.length() != release.apkSize) {
                throw IllegalStateException(
                    "Downloaded ${target.length()} bytes, the release lists ${release.apkSize}",
                )
            }
            target
        }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WinNative-Updater")
        }

    private fun download(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode} for $url")
            }
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    var got = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        got += read
                        if (got - lastReport >= PROGRESS_STEP) {
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
}
