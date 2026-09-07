package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import com.winlator.cmod.feature.stores.itch.data.ItchGame
import com.winlator.cmod.feature.stores.itch.data.ItchUpload
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object ItchDownloadRequestStore {
    private const val PREFS = "itch_store"
    private const val KEY_PENDING = "pending_downloads"

    fun put(
        context: Context,
        game: ItchGame,
        upload: ItchUpload,
        installPath: String,
    ) {
        val entries = load(context).filterNot { it.first == game.id }.toMutableList()
        entries.add(game.id to encode(game, upload, installPath))
        persist(context, entries)
    }

    fun take(
        context: Context,
        gameId: Int,
    ): Triple<ItchGame, ItchUpload, String>? {
        val json = load(context).firstOrNull { it.first == gameId }?.second ?: return null
        return decode(json)
    }

    fun remove(
        context: Context,
        gameId: Int,
    ) {
        persist(context, load(context).filterNot { it.first == gameId })
    }

    private fun encode(
        game: ItchGame,
        upload: ItchUpload,
        installPath: String,
    ): JSONObject =
        JSONObject()
            .put("id", game.id)
            .put("title", game.title)
            .put("url", game.url)
            .put("cover", game.coverUrl)
            .put("author", game.author)
            .put("install", installPath)
            .put("upload_id", upload.id)
            .put("upload_name", upload.fileName)
            .put("upload_size_label", upload.sizeLabel)
            .put("upload_size", upload.sizeBytes)
            .put("upload_version", upload.version)

    private fun decode(json: JSONObject): Triple<ItchGame, ItchUpload, String> {
        val game =
            ItchGame(
                id = json.optInt("id"),
                title = json.optString("title"),
                url = json.optString("url"),
                coverUrl = json.optString("cover"),
                author = json.optString("author"),
            )
        val upload =
            ItchUpload(
                id = json.optLong("upload_id"),
                fileName = json.optString("upload_name"),
                sizeLabel = json.optString("upload_size_label"),
                sizeBytes = json.optLong("upload_size"),
                version = json.optString("upload_version"),
                platforms = emptySet(),
            )
        return Triple(game, upload, json.optString("install"))
    }

    private fun load(context: Context): List<Pair<Int, JSONObject>> {
        val raw = prefs(context).getString(KEY_PENDING, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                obj.optInt("id") to obj
            }
        }.getOrElse {
            Timber.w(it, "[Itch] pending download registry unreadable")
            emptyList()
        }
    }

    private fun persist(
        context: Context,
        entries: List<Pair<Int, JSONObject>>,
    ) {
        val array = JSONArray()
        entries.forEach { array.put(it.second) }
        prefs(context).edit().putString(KEY_PENDING, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
