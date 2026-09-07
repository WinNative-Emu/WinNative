package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ItchAuthRequiredException(
    message: String,
) : IOException(message)

object ItchWebClient {
    private val csrfMetaRegex =
        Regex("<meta[^>]*name=\"csrf_token\"[^>]*value=\"([^\"]+)\"[^>]*>|<meta[^>]*value=\"([^\"]+)\"[^>]*name=\"csrf_token\"[^>]*>")
    private val currentUserRegex = Regex("I\\.current_user\\s*=\\s*(null|\\{)")
    private val profileAnchorRegex =
        Regex("<a[^>]*href=\"https://([a-z0-9][a-z0-9_-]*)\\.itch\\.io/?\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var jar: ItchCookieJar? = null

    fun cookieJar(context: Context): ItchCookieJar = ensure(context).second

    fun client(context: Context): OkHttpClient = ensure(context).first

    @Synchronized
    private fun ensure(context: Context): Pair<OkHttpClient, ItchCookieJar> {
        val existingClient = client
        val existingJar = jar
        if (existingClient != null && existingJar != null) return existingClient to existingJar
        val newJar = ItchCookieJar(context)
        val newClient =
            OkHttpClient
                .Builder()
                .cookieJar(newJar)
                .followRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        client = newClient
        jar = newJar
        return newClient to newJar
    }

    fun reset() {
        client = null
        jar = null
    }

    private fun requestBuilder(url: String): Request.Builder =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", ItchConstants.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")

    fun getHtml(
        context: Context,
        url: String,
    ): String {
        val request = requestBuilder(url).header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9").build()
        client(context).newCall(request).execute().use { response ->
            return readBody(response, url)
        }
    }

    fun postForm(
        context: Context,
        url: String,
        fields: Map<String, String>,
    ): String {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request =
            requestBuilder(url)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", url)
                .post(body)
                .build()
        client(context).newCall(request).execute().use { response ->
            return readBody(response, url)
        }
    }

    private fun readBody(
        response: Response,
        url: String,
    ): String {
        if (!response.isSuccessful) {
            throw IOException("itch.io returned HTTP ${response.code} for $url")
        }
        return response.body?.string() ?: throw IOException("Empty response from $url")
    }

    fun csrfToken(html: String): String? {
        val match = csrfMetaRegex.find(html) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }.ifBlank { null }
    }

    fun isSignedIn(html: String): Boolean = currentUserRegex.find(html)?.groupValues?.get(1) == "{"

    fun signedInUserName(html: String): String? {
        val panel = html.indexOf("user_panel_widget")
        val scope = if (panel >= 0) html.substring(panel, (panel + 4000).coerceAtMost(html.length)) else html
        val anchor = profileAnchorRegex.find(scope) ?: return null
        val label = anchor.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
        return label.ifBlank { anchor.groupValues[1] }.takeUnless { it.isBlank() }
    }

    fun extractJsonObject(
        source: String,
        start: Int,
    ): String? {
        if (start >= source.length || source[start] != '{') return null
        var depth = 0
        var index = start
        var inString = false
        var escaped = false
        while (index < source.length) {
            val ch = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(start, index + 1)
                    }
                }
            }
            index++
        }
        return null
    }

    fun resolveDownloadPage(
        context: Context,
        gameUrl: String,
    ): String {
        val normalized = gameUrl.trimEnd('/')
        val page = getHtml(context, normalized)
        val token = csrfToken(page) ?: throw IOException("itch.io page did not expose a CSRF token")
        val response = postForm(context, "$normalized/download_url", mapOf("csrf_token" to token))
        val url =
            runCatching { JSONObject(response).optString("url") }.getOrNull()
                ?: throw ItchAuthRequiredException("This game is not available for free download")
        if (url.isBlank()) throw ItchAuthRequiredException("This game is not available for free download")
        return url
    }

    fun mintFileUrl(
        context: Context,
        gameUrl: String,
        uploadId: Long,
        csrfToken: String,
    ): String {
        val normalized = gameUrl.trimEnd('/')
        val endpoint = "$normalized/file/$uploadId?source=game_download&after_download_lightbox=1&as_props=1"
        val response = postForm(context, endpoint, mapOf("csrf_token" to csrfToken))
        val json = runCatching { JSONObject(response) }.getOrNull() ?: throw IOException("Unexpected itch.io file response")
        val url = json.optString("url")
        if (url.isBlank()) {
            val errors = json.optJSONArray("errors")
            val reason = if (errors != null && errors.length() > 0) errors.optString(0) else "no download URL returned"
            throw IOException("itch.io refused the download: $reason")
        }
        return url
    }

    fun openStream(
        context: Context,
        url: String,
        rangeStart: Long,
    ): Response {
        val builder = requestBuilder(url).header("Accept", "*/*")
        if (rangeStart > 0L) builder.header("Range", "bytes=$rangeStart-")
        val response = client(context).newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("Download failed with HTTP $code")
        }
        return response
    }
}
