package com.winlator.cmod.feature.library

import android.content.Context
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.gog.data.GOGGame
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.runtime.container.Shortcut
import org.json.JSONArray
import org.json.JSONObject

/**
 * The library the app drew last time, so the grid is on screen the moment it opens rather than
 * after Room, the stores and the shortcut scan have all reported in.
 *
 * It is a picture of a finished scan, never a source of truth: the scan that runs on every launch
 * replaces it, and until that scan finishes the app is offline or simply still starting, which is
 * exactly when the picture is worth having. Entries carry only what a card draws - artwork is
 * already cached on disk under the app id.
 */
object LibraryCache {
    private const val PREFS = "library_cache"
    private const val KEY_ENTRIES = "installed"

    /** The custom artwork a card draws, by library id. */
    data class Artwork(
        val cover: Map<Int, String> = emptyMap(),
        val iconArtwork: Map<Int, String> = emptyMap(),
        val icon: Map<Int, String> = emptyMap(),
        val hero: Map<Int, String> = emptyMap(),
        val carousel: Map<Int, String> = emptyMap(),
        val list: Map<Int, String> = emptyMap(),
    )

    /** Everything the library screen had worked out when it was last on screen in this process. */
    data class Session(
        val apps: List<SteamApp>,
        val merged: List<SteamApp>,
        val gogByPseudoId: Map<Int, GOGGame>,
        val epicByPseudoId: Map<Int, EpicGame>,
        val shortcuts: List<Shortcut>,
        val customApps: List<SteamApp>,
        val customStoragePaths: Map<Int, String>,
        val artwork: Artwork = Artwork(),
    )

    /**
     * The library screen leaves the composition whenever another tab or a game's page covers it.
     * Coming back starts from this rather than from nothing, so the grid never empties while the
     * scan that follows confirms it. Main thread only.
     */
    var session: Session? = null

    /** The saved picture, read ahead of the first composition by [preload]. */
    @Volatile
    var preloaded: List<SteamApp>? = null
        private set

    /** Worker thread. */
    fun preload(context: Context) {
        if (preloaded == null) preloaded = load(context)
    }

    /** Worker thread. */
    fun load(context: Context): List<SteamApp> =
        runCatching {
            val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.optInt("id", 0)
                if (id == 0) return@mapNotNull null
                SteamApp(
                    id = id,
                    name = entry.optString("name"),
                    developer = entry.optString("developer"),
                    gameDir = entry.optString("gameDir"),
                )
            }
        }.getOrDefault(emptyList())

    /** Worker thread. */
    fun save(
        context: Context,
        apps: List<SteamApp>,
    ) {
        val array = JSONArray()
        for (app in apps) {
            array.put(
                JSONObject()
                    .put("id", app.id)
                    .put("name", app.name)
                    .put("developer", app.developer)
                    .put("gameDir", app.gameDir),
            )
        }
        runCatching { prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply() }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
