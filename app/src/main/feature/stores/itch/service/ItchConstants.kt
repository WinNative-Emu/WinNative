package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import android.net.Uri
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import timber.log.Timber
import java.io.File
import java.nio.file.Paths

object ItchConstants {
    const val SITE_URL = "https://itch.io"
    const val LOGIN_URL = "https://itch.io/login"
    const val LOGOUT_URL = "https://itch.io/logout"
    const val COOKIE_DOMAIN = "itch.io"
    const val SEARCH_URL = "https://itch.io/search"
    const val AUTOCOMPLETE_URL = "https://itch.io/autocomplete"

    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/128.0.0.0 Safari/537.36"

    const val PAGE_SIZE = 36

    fun browseUrl(
        path: String,
        page: Int,
    ): String = "$SITE_URL/$path?format=json&page=$page"

    fun searchUrl(query: String): String = "$SEARCH_URL?q=${Uri.encode(query)}"

    fun internalGamesPath(context: Context): String {
        val path = Paths.get(context.dataDir.path, "Itch", "games").toString()
        File(path).mkdirs()
        return path
    }

    fun externalGamesPath(): String {
        val path = Paths.get(PrefManager.externalStoragePath, "Itch", "games").toString()
        File(path).mkdirs()
        return path
    }

    fun defaultGamesPath(context: Context): String {
        val storeDefaultUri =
            if (PrefManager.useSingleDownloadFolder) PrefManager.defaultDownloadFolder else PrefManager.itchDownloadFolder
        if (storeDefaultUri.isNotEmpty()) {
            val baseDir =
                com.winlator.cmod.shared.io.FileUtils
                    .getFilePathFromUri(context, Uri.parse(storeDefaultUri))
            if (baseDir != null) {
                Timber.i("[Itch] using user-defined default storage: $baseDir")
                File(baseDir).mkdirs()
                return baseDir
            }
        }
        return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
            externalGamesPath()
        } else {
            internalGamesPath(context)
        }
    }

    fun sanitizeFolderName(title: String): String {
        val sanitized = title.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").replace(Regex("\\s+"), " ").trim()
        return sanitized.ifEmpty { "Game" }
    }

    fun gameInstallPath(
        context: Context,
        title: String,
    ): String = Paths.get(defaultGamesPath(context), sanitizeFolderName(title)).toString()
}
