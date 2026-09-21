package com.winlator.cmod.runtime.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.stores.steam.enums.AppType
import com.winlator.cmod.feature.stores.steam.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * The GOG games that are installed, as the store recorded them, and the program each one starts.
 *
 * The library writes a shortcut for a game only once it has been played, so an installed game the
 * user has not started yet is known by the store's records alone. Those records say where a game
 * was installed but not what to run: that is in the `goggame-<id>.info` the installer leaves
 * beside the game, whose primary play task is the one GOG's own client would start.
 */
object LinuxGogGames {
    private const val TAG = "LinuxGogGames"

    /** How far below the install directory a game's info file is looked for. */
    private const val INFO_DEPTH = 3

    class Installed(val id: String, val title: String, val exe: File)

    /** Worker thread. Every installed game whose program could be resolved. */
    @JvmStatic
    fun installed(context: Context): List<Installed> {
        val games =
            try {
                runBlocking(Dispatchers.IO) { PluviaDatabase.getInstance(context).gogGameDao().getAllAsList() }
            } catch (e: RuntimeException) {
                Log.w(TAG, "the GOG library is unavailable: ${e.javaClass.simpleName}")
                return emptyList()
            }
        return games
            .filter { it.isInstalled && it.installPath.isNotEmpty() && it.type == AppType.game }
            .mapNotNull { game ->
                exeOf(File(game.installPath), game.id)?.let { Installed(game.id, game.title, it) }
            }
    }

    /** The program the game in [install] starts, or null when its install says nothing usable. */
    private fun exeOf(install: File, id: String): File? {
        val info = infoFile(install, id) ?: return null
        return try {
            val tasks = JSONObject(info.readText()).optJSONArray("playTasks") ?: return null
            for (i in 0 until tasks.length()) {
                val task = tasks.optJSONObject(i) ?: continue
                if (!task.optBoolean("isPrimary")) continue
                val path = task.optString("path")
                if (path.isEmpty()) return null
                return FileUtils.findFileCaseInsensitive(info.parentFile ?: install, path)?.takeIf { it.isFile }
            }
            null
        } catch (e: JSONException) {
            Log.w(TAG, "could not read ${info.name}: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * The game's own info file, which sits beside its program - in the install directory for an
     * older game, and a level or two down for one the installer laid out in parts.
     */
    private fun infoFile(install: File, id: String): File? {
        var level = listOf(install)
        repeat(INFO_DEPTH + 1) {
            val files = level.flatMap { it.listFiles()?.asList().orEmpty() }
            files.firstOrNull { it.isFile && it.name.equals("goggame-$id.info", ignoreCase = true) }?.let { return it }
            files.firstOrNull { it.isFile && it.name.startsWith("goggame-") && it.extension == "info" }?.let { return it }
            level = files.filter { it.isDirectory }
            if (level.isEmpty()) return null
        }
        return null
    }
}
