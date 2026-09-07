package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

class ItchCookieJar(
    context: Context,
) : CookieJar {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cookies = ConcurrentHashMap<String, Cookie>()

    init {
        restore()
    }

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        var changed = false
        cookies.forEach { cookie ->
            val key = keyOf(cookie)
            if (cookie.expiresAt < System.currentTimeMillis()) {
                changed = this.cookies.remove(key) != null || changed
            } else {
                val previous = this.cookies.put(key, cookie)
                changed = previous?.value != cookie.value || changed
            }
        }
        if (changed) persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val expired = cookies.filterValues { it.expiresAt < now }.keys
        if (expired.isNotEmpty()) {
            expired.forEach { cookies.remove(it) }
            persist()
        }
        return cookies.values.filter { it.matches(url) }
    }

    fun hasCookie(name: String): Boolean = cookies.values.any { it.name == name && it.value.isNotBlank() }

    fun acceptHeader(
        url: String,
        header: String,
    ) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        var changed = false
        header.split(';').forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@forEach
            val cookie = Cookie.parse(httpUrl, "$trimmed; Domain=${ItchConstants.COOKIE_DOMAIN}; Path=/; Max-Age=31536000")
            if (cookie != null) {
                cookies[keyOf(cookie)] = cookie
                changed = true
            }
        }
        if (changed) persist()
    }

    fun clear() {
        cookies.clear()
        prefs.edit().remove(KEY_COOKIES).apply()
    }

    private fun keyOf(cookie: Cookie): String = "${cookie.domain}|${cookie.path}|${cookie.name}"

    private fun persist() {
        runCatching {
            prefs.edit().putStringSet(KEY_COOKIES, cookies.values.map { it.toString() }.toSet()).apply()
        }.onFailure { Timber.w(it, "[Itch] failed to persist cookies") }
    }

    private fun restore() {
        val stored = runCatching { prefs.getStringSet(KEY_COOKIES, emptySet()) }.getOrNull() ?: return
        val base = ItchConstants.SITE_URL.toHttpUrlOrNull() ?: return
        stored.forEach { raw ->
            val cookie = Cookie.parse(base, raw)
            if (cookie != null && cookie.expiresAt > System.currentTimeMillis()) {
                cookies[keyOf(cookie)] = cookie
            }
        }
    }

    companion object {
        private const val PREFS = "itch_store"
        private const val KEY_COOKIES = "cookies"
    }
}
