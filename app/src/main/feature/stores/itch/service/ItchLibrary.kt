package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import com.winlator.cmod.feature.stores.itch.data.ItchGame
import com.winlator.cmod.feature.stores.itch.data.ItchUpload
import org.json.JSONObject
import timber.log.Timber
import java.io.File

data class ItchInstalledGame(
    val id: Int,
    val title: String,
    val url: String,
    val coverUrl: String,
    val installPath: String,
    val executablePath: String,
    val uploadId: Long = 0L,
    val uploadVersion: String = "",
    val uploadedAt: String = "",
    val uploadSize: Long = 0L,
) {
    val buildLabel: String
        get() = listOf(uploadVersion, uploadedAt).firstOrNull { it.isNotBlank() }.orEmpty()
}

object ItchLibrary {
    private const val PREFS = "itch_store"
    private const val KEY_INSTALLED = "installed_games"

    fun record(
        context: Context,
        game: ItchGame,
        installPath: String,
        executablePath: String,
        upload: ItchUpload? = null,
    ) {
        val entries = all(context).associateBy { it.id }.toMutableMap()
        entries[game.id] =
            ItchInstalledGame(
                id = game.id,
                title = game.title,
                url = game.url,
                coverUrl = game.coverUrl,
                installPath = installPath,
                executablePath = executablePath,
                uploadId = upload?.id ?: 0L,
                uploadVersion = upload?.version.orEmpty(),
                uploadedAt = upload?.uploadedAt.orEmpty(),
                uploadSize = upload?.sizeBytes ?: 0L,
            )
        persist(context, entries.values.toList())
    }

    fun remove(
        context: Context,
        gameId: Int,
    ) {
        persist(context, all(context).filterNot { it.id == gameId })
    }

    fun find(
        context: Context,
        gameId: Int,
    ): ItchInstalledGame? = all(context).firstOrNull { it.id == gameId }

    fun all(context: Context): List<ItchInstalledGame> {
        val raw = prefs(context).getString(KEY_INSTALLED, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val root = org.json.JSONArray(raw)
            (0 until root.length()).mapNotNull { index ->
                val obj = root.optJSONObject(index) ?: return@mapNotNull null
                ItchInstalledGame(
                    id = obj.optInt("id"),
                    title = obj.optString("title"),
                    url = obj.optString("url"),
                    coverUrl = obj.optString("cover"),
                    installPath = obj.optString("path"),
                    executablePath = obj.optString("exe"),
                    uploadId = obj.optLong("upload"),
                    uploadVersion = obj.optString("version"),
                    uploadedAt = obj.optString("uploaded"),
                    uploadSize = obj.optLong("size"),
                )
            }
        }.getOrElse {
            Timber.w(it, "[Itch] installed-games registry unreadable")
            emptyList()
        }
    }

    fun installedIds(context: Context): Set<Int> =
        all(context)
            .filter { ItchInstaller.isInstalled(File(it.installPath)) }
            .map { it.id }
            .toSet()

    fun prune(context: Context) {
        val kept = all(context).filter { File(it.installPath).isDirectory }
        if (kept.size != all(context).size) persist(context, kept)
    }

    private fun persist(
        context: Context,
        games: List<ItchInstalledGame>,
    ) {
        val array = org.json.JSONArray()
        games.forEach { game ->
            array.put(
                JSONObject()
                    .put("id", game.id)
                    .put("title", game.title)
                    .put("url", game.url)
                    .put("cover", game.coverUrl)
                    .put("path", game.installPath)
                    .put("exe", game.executablePath)
                    .put("upload", game.uploadId)
                    .put("version", game.uploadVersion)
                    .put("uploaded", game.uploadedAt)
                    .put("size", game.uploadSize),
            )
        }
        prefs(context).edit().putString(KEY_INSTALLED, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
