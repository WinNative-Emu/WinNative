package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import android.webkit.CookieManager
import timber.log.Timber

object ItchAuthManager {
    private const val PREFS = "itch_store"
    private const val KEY_USER = "signed_in_user"
    private const val SESSION_COOKIE = "itchio"

    fun isLoggedIn(context: Context): Boolean = ItchWebClient.cookieJar(context).hasCookie(SESSION_COOKIE)

    fun userName(context: Context): String = prefs(context).getString(KEY_USER, "").orEmpty()

    fun captureWebViewSession(context: Context): Boolean {
        val header = CookieManager.getInstance().getCookie(ItchConstants.SITE_URL) ?: return false
        if (!header.contains("$SESSION_COOKIE=")) return false
        ItchWebClient.cookieJar(context).acceptHeader(ItchConstants.SITE_URL, header)
        return isLoggedIn(context)
    }

    fun refreshProfile(context: Context) {
        val html = runCatching { ItchWebClient.getHtml(context, ItchConstants.SITE_URL) }.getOrNull() ?: return
        if (!ItchWebClient.isSignedIn(html)) {
            signOut(context)
            return
        }
        val name = ItchWebClient.signedInUserName(html)
        if (!name.isNullOrBlank()) {
            prefs(context).edit().putString(KEY_USER, name).apply()
        }
    }

    fun signOut(context: Context) {
        ItchWebClient.cookieJar(context).clear()
        ItchWebClient.reset()
        prefs(context).edit().remove(KEY_USER).apply()
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.getCookie(ItchConstants.SITE_URL)?.split(';')?.forEach { part ->
                val name = part.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    cookieManager.setCookie(ItchConstants.SITE_URL, "$name=; Domain=.${ItchConstants.COOKIE_DOMAIN}; Path=/; Max-Age=0")
                }
            }
            cookieManager.flush()
        }.onFailure { Timber.w(it, "[Itch] failed to clear WebView cookies") }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
