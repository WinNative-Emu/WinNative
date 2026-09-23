package com.winlator.cmod.runtime.linux

import android.content.Context
import android.os.Environment
import android.util.Log
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.library.LinuxApps
import com.winlator.cmod.feature.retro.RetroShortcuts
import com.winlator.cmod.feature.shortcuts.LibraryShortcutUtils
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.steam.enums.AppType
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.utils.KeyValue
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.linux.LinuxSteamVdf.STEAM_KEY
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32

/**
 * The library's Windows games that are not Steam's - custom, itch.io, GOG and Epic - kept in the
 * Linux Steam client as non-Steam games, each set to run under the ARM64 Proton tool. A game of a
 * store is taken from its shortcut, and from the store's own records when it has none: the library
 * writes a shortcut for a game only once it has been played.
 *
 * An Epic game is signed in as it starts rather than here: its entry names the game in its launch
 * options, and the runtime's `winnative-epic-launch` asks [LinuxEpicTokens] for the command line.
 *
 * The client keeps such games in `userdata/<account>/config/shortcuts.vdf`, a binary VDF. Entries
 * the user added in the client are carried over as they are; the ones written here are listed in
 * [OWNED] beside the file, which is how an entry whose game has left the library is known to be
 * ours to remove.
 */
object LinuxSteamShortcuts {
    private const val TAG = "LinuxSteamShortcuts"
    private const val OWNED = "winnative-shortcuts"
    private const val TOOL = "winnative-proton"

    /** Read by `winnative-launch` in the runtime, which hands an Epic game to its own launcher. */
    private const val EPIC_GAME = "WN_EPIC"
    private const val NON_STEAM = 0x02000000L
    private const val TYPE_SECTION = 0
    private const val TYPE_STRING = 1
    private const val TYPE_INT = 2
    private const val TYPE_END = 8

    private class Node(val name: String, var value: Any)

    private class Game(
        val appId: Int,
        val name: String,
        val exe: File,
        val icon: String,
        /** Set for an Epic game alone, and the only launch options this class writes. */
        val epicAppName: String = "",
    )

    /** The URL that has the client run [shortcut], or null when it is not a game this class adds. */
    @JvmStatic
    fun launchUrl(context: Context, shortcut: Shortcut): String? =
        game(context, shortcut)?.let { "steam://rungameid/" + java.lang.Long.toUnsignedString((unsigned(it.appId) shl 32) or NON_STEAM) }

    /** Worker thread. The id the client knows [shortcut] by as a non-Steam game, or null when it is not one. */
    @JvmStatic
    fun steamAppId(context: Context, shortcut: Shortcut): Long? = game(context, shortcut)?.let { unsigned(it.appId) }

    /** Worker thread. The id the client starts [shortcut]'s game under, or null when the client does not start it. */
    @JvmStatic
    fun clientAppId(context: Context, shortcut: Shortcut): Long? =
        if (shortcut.getExtra("game_source") == "STEAM") shortcut.getExtra("app_id").toLongOrNull()
        else steamAppId(context, shortcut)

    /**
     * Worker thread, with no client running. Brings the client's non-Steam games in line with the
     * library and returns the proot bind specs for game folders the session does not already see.
     */
    @JvmStatic
    @Synchronized
    fun sync(context: Context): List<String> {
        if (!LinuxRuntime.isInstalled(context)) return emptyList()
        val games =
            try {
                // A shortcut first: it carries the name and the artwork the user gave the game.
                (ContainerManager(context).loadShortcuts().mapNotNull { game(context, it) } +
                    LinuxEpicTokens.installedGames(context).mapNotNull(::epicGame) +
                    LinuxGogGames.installed(context).map(::gogGame))
                    .distinctBy { it.appId }
            } catch (e: RuntimeException) {
                Log.w(TAG, "library unavailable: ${e.javaClass.simpleName}")
                return emptyList()
            }
        val steamRoot = LinuxSteamVdf.steamRoot(LinuxRuntime.rootDir(context))
        val retired = HashSet<Int>()
        for (config in userConfigs(steamRoot)) {
            try {
                retired += write(config, games)
            } catch (e: Exception) {
                Log.w(TAG, "could not update ${config.parentFile?.name}: ${e.javaClass.simpleName}")
            }
        }
        try {
            val mapped = games.map { unsigned(it.appId).toString() } + ownedSteamGames(context)
            mapTool(File(steamRoot, "config/config.vdf"), mapped, retired - games.map { it.appId }.toSet())
        } catch (e: Exception) {
            Log.w(TAG, "could not set the compatibility tool: ${e.javaClass.simpleName}")
        }
        val visible = listOf(context.filesDir, context.cacheDir, Environment.getExternalStorageDirectory())
        return games
            .mapNotNull { it.exe.parentFile }
            .filter { dir -> visible.none { dir.startsWith(it) } }
            .distinct()
            .map { "${it.path}:${it.path}" }
    }

    /**
     * A game left the library. With no client running its entry goes now; a running client owns the
     * file, and the next session's [sync] takes the entry out instead.
     */
    @JvmStatic
    fun removed(context: Context, shortcut: Shortcut) {
        if (!isCandidate(shortcut) || SessionKeepAliveService.isLinuxSessionActive()) return
        val appContext = context.applicationContext
        Thread({ sync(appContext) }, "steam-shortcuts").start()
    }

    private fun isCandidate(shortcut: Shortcut): Boolean =
        !LinuxApps.isLinuxShortcut(shortcut) &&
            !RetroShortcuts.isRetroShortcut(shortcut) &&
            LibraryShortcutUtils.inferGameSource(shortcut).let { it == "CUSTOM" || it == "GOG" || it == "EPIC" }

    private fun game(context: Context, shortcut: Shortcut): Game? {
        if (!isCandidate(shortcut)) return null
        var epicAppName = ""
        val (identity, exe) =
            when (LibraryShortcutUtils.inferGameSource(shortcut)) {
                "CUSTOM" ->
                    "custom:" + shortcut.getExtra("uuid").ifEmpty { shortcut.file?.path ?: shortcut.name } to
                        File(shortcut.getExtra("custom_exe"))
                "GOG" ->
                    "gog:" + shortcut.getExtra("gog_id").ifEmpty { return null } to
                        installed(shortcut)
                "EPIC" -> {
                    // The shortcut holds the library's own id; the game is signed in by the name
                    // Epic knows it as, and started by the program the store recorded, neither of
                    // which the shortcut carries.
                    val id = shortcut.getExtra("app_id").toIntOrNull() ?: return null
                    val record = LinuxEpicTokens.gameOf(context, id) ?: return null
                    epicAppName = epicName(record) ?: return null
                    "epic:" + epicAppName to (installed(shortcut).takeIf { it.isFile } ?: epicExe(record) ?: return null)
                }
                else -> return null
            }
        if (!exe.isFile) return null
        val name = shortcut.getExtra("custom_name").ifBlank { shortcut.name }
        return Game(appIdOf(identity), name, exe, shortcut.getExtra("customCoverArtPath"), epicAppName)
    }

    /**
     * An installed game the library has written no shortcut for yet, from the store's record alone.
     * It carries no artwork of the user's, which the client fills in with its own.
     */
    private fun gogGame(record: LinuxGogGames.Installed): Game =
        Game(appIdOf("gog:${record.id}"), record.title, record.exe, "")

    /** As above, for Epic, which is also signed in by the name the store knows the game as. */
    private fun epicGame(record: EpicGame): Game? {
        val name = epicName(record) ?: return null
        val exe = epicExe(record) ?: return null
        return Game(appIdOf("epic:$name"), record.title.ifBlank { name }, exe, "", name)
    }

    /** The id the client files a non-Steam game under, which is the same for the same game. */
    private fun appIdOf(identity: String): Int {
        val crc = CRC32().apply { update(identity.toByteArray()) }.value
        return (crc or 0x80000000L).toInt()
    }

    /**
     * The name Epic knows [record] by, or null when it cannot be written: the name goes into the
     * launch options as one word of a shell-like line, and Epic gives a game a single word.
     */
    private fun epicName(record: EpicGame): String? =
        record.appName.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() || c == '"' } }

    /** The program an Epic game starts, by the store's record and then by what is on disk. */
    private fun epicExe(record: EpicGame): File? {
        val install = File(record.installPath)
        val recorded = File(install, record.executable.replace('\\', '/'))
        if (record.executable.isNotEmpty() && recorded.isFile) return recorded
        // A record written before the manifest named one. Epic's own stubs are not the game.
        val stubs = setOf("epicgameslauncher.exe", "eosbootstrapper.exe")
        return install
            .walkTopDown()
            .maxDepth(3)
            .firstOrNull {
                it.isFile && it.extension.equals("exe", ignoreCase = true) &&
                    it.name.lowercase(Locale.US) !in stubs
            }
    }

    /** Where a store's game was installed, by the path its shortcut records. */
    private fun installed(shortcut: Shortcut): File {
        val exe = shortcut.getExtra("launch_exe_path").replace('\\', '/')
        return if (exe.startsWith("/")) File(exe) else File(shortcut.getExtra("game_install_path"), exe)
    }

    private fun unsigned(appId: Int) = appId.toLong() and 0xFFFFFFFFL

    /** The config directory of every account the client knows, and of the store's account. */
    private fun userConfigs(steamRoot: File): List<File> {
        val userdata = File(steamRoot, "userdata")
        val accounts = userdata.list()?.filter { it.toLongOrNull()?.let { id -> id > 0 } == true }.orEmpty().toMutableSet()
        val steamId = PrefManager.steamUserSteamId64
        if (steamId != 0L) accounts += (steamId and 0xFFFFFFFFL).toString()
        return accounts.map { File(userdata, "$it/config") }
    }

    /** Returns the app ids that were ours in this account before, for their tool mapping to go. */
    private fun write(config: File, games: List<Game>): Set<Int> {
        val file = File(config, "shortcuts.vdf")
        val ownedFile = File(config, OWNED)
        val owned = if (ownedFile.isFile) ownedFile.readLines().mapNotNull { it.trim().toLongOrNull()?.toInt() }.toSet() else emptySet()
        if (games.isEmpty() && owned.isEmpty()) return owned
        val entries = if (file.isFile && file.length() > 0) parse(file.readBytes()) else ArrayList()
        val before = serialize(entries)
        val wanted = games.associateBy { it.appId }
        entries.removeAll { entry -> appId(entry).let { it in owned && it !in wanted } }
        for (game in games) {
            val fields =
                entries.firstOrNull { appId(it) == game.appId }?.let { fieldsOf(it) }
                    ?: newEntry(game.appId).also { entries += Node("", it) }
            set(fields, "AppName", game.name)
            set(fields, "Exe", "\"${game.exe.path}\"")
            set(fields, "StartDir", "\"${game.exe.parent}\"")
            set(fields, "icon", game.icon)
            set(fields, "LaunchOptions", launchOptions(fields, game))
        }
        val after = serialize(entries)
        if (!after.contentEquals(before)) LinuxSteamVdf.replace(file) { it.writeBytes(after) }
        if (wanted.keys != owned) {
            LinuxSteamVdf.replace(ownedFile) { it.writeText(wanted.keys.joinToString("\n") { id -> unsigned(id).toString() }) }
        }
        return owned
    }

    /**
     * The entry's launch options with the Epic game's name in them, keeping whatever else the user
     * put there. `%command%` must stay: without it the client passes the options to the game as
     * arguments instead of running the line.
     */
    private fun launchOptions(fields: MutableList<Node>, game: Game): String {
        val current = fields.firstOrNull { it.name.equals("LaunchOptions", ignoreCase = true) }?.value as? String ?: ""
        // A game of another store keeps the line it has, down to the spacing the user typed.
        if (game.epicAppName.isEmpty() && !current.contains("$EPIC_GAME=")) return current
        val kept = current.split(" ").filter { it.isNotEmpty() && !it.startsWith("$EPIC_GAME=") }
        if (game.epicAppName.isEmpty()) return kept.joinToString(" ")
        val named = listOf("$EPIC_GAME=${game.epicAppName}") + kept
        return if (named.any { it.contains("%command%") }) named.joinToString(" ") else (named + "%command%").joinToString(" ")
    }

    /**
     * The Steam games the store's account owns, which are set to the ARM64 tool before the client
     * installs any of them. Valve names a Proton of its own for thousands of titles, and that
     * beats the client's default: installing one has the client fetch an x86-64 Proton, its
     * runtime and FEX - gigabytes that cannot run here - and the game then fails to start.
     */
    private fun ownedSteamGames(context: Context): List<String> =
        try {
            runBlocking(Dispatchers.IO) { PluviaDatabase.getInstance(context).steamAppDao().getAllAsList() }
                .filter { it.packageId != SteamService.INVALID_PKG_ID && (it.type == AppType.game || it.type == AppType.demo) }
                .map { it.id.toString() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "the Steam library is unavailable: ${e.javaClass.simpleName}")
            emptyList()
        }

    private fun mapTool(config: File, apps: List<String>, retired: Set<Int>) {
        // The client writes this file on its first start; until then there is nothing to add to.
        if (!config.isFile) return
        val tree = LinuxSteamVdf.load(config, "InstallConfigStore")
        val mapping = LinuxSteamVdf.section(tree, STEAM_KEY + "CompatToolMapping")
        var changed = mapping.children.removeAll { entry -> retired.any { unsigned(it).toString() == entry.name } }
        for (key in apps) {
            if (mapping[key] !== KeyValue.INVALID) continue
            mapping.children +=
                KeyValue(key).apply {
                    isSection = true
                    children += KeyValue("name", TOOL)
                    children += KeyValue("config", "")
                    children += KeyValue("priority", "250")
                }
            changed = true
        }
        if (changed) LinuxSteamVdf.save(config, tree)
    }

    @Suppress("UNCHECKED_CAST")
    private fun fieldsOf(entry: Node) = entry.value as MutableList<Node>

    private fun appId(entry: Node): Int? =
        fieldsOf(entry).firstOrNull { it.name.equals("appid", ignoreCase = true) }?.value as? Int

    private fun set(fields: MutableList<Node>, name: String, value: String) {
        val field = fields.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (field != null) field.value = value else fields += Node(name, value)
    }

    /** The fields the client itself writes for a non-Steam game, in its order. */
    private fun newEntry(appId: Int): MutableList<Node> =
        mutableListOf(
            Node("appid", appId),
            Node("AppName", ""),
            Node("Exe", ""),
            Node("StartDir", ""),
            Node("icon", ""),
            Node("ShortcutPath", ""),
            Node("LaunchOptions", ""),
            Node("IsHidden", 0),
            Node("AllowDesktopConfig", 1),
            Node("AllowOverlay", 1),
            Node("OpenVR", 0),
            Node("Devkit", 0),
            Node("DevkitGameID", ""),
            Node("DevkitOverrideAppID", 0),
            Node("LastPlayTime", 0),
            Node("FlatpakAppID", ""),
            Node("tags", ArrayList<Node>()),
        )

    /** The entries under the file's one `shortcuts` section. Anything else is not ours to rewrite. */
    private fun parse(bytes: ByteArray): MutableList<Node> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get().toInt() != TYPE_SECTION || !readString(buffer).equals("shortcuts", ignoreCase = true)) {
            throw IOException("not a shortcuts file")
        }
        val entries = readSection(buffer)
        if (entries.any { it.value !is MutableList<*> }) throw IOException("unexpected entry")
        return entries
    }

    private fun readSection(buffer: ByteBuffer): MutableList<Node> {
        val nodes = ArrayList<Node>()
        while (true) {
            val type = buffer.get().toInt()
            if (type == TYPE_END) return nodes
            val name = readString(buffer)
            nodes +=
                when (type) {
                    TYPE_SECTION -> Node(name, readSection(buffer))
                    TYPE_STRING -> Node(name, readString(buffer))
                    TYPE_INT -> Node(name, buffer.int)
                    else -> throw IOException("unknown field type $type")
                }
        }
    }

    private fun readString(buffer: ByteBuffer): String {
        val start = buffer.position()
        while (buffer.get().toInt() != 0) Unit
        return String(buffer.array(), start, buffer.position() - 1 - start, Charsets.UTF_8)
    }

    private fun serialize(entries: List<Node>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(TYPE_SECTION)
        writeString(out, "shortcuts")
        // The client numbers the entries from zero and reads them back by that number.
        writeSection(out, entries.mapIndexed { index, entry -> Node(index.toString(), entry.value) })
        out.write(TYPE_END)
        return out.toByteArray()
    }

    private fun writeSection(out: ByteArrayOutputStream, nodes: List<Node>) {
        for (node in nodes) {
            when (val value = node.value) {
                is String -> {
                    out.write(TYPE_STRING)
                    writeString(out, node.name)
                    writeString(out, value)
                }
                is Int -> {
                    out.write(TYPE_INT)
                    writeString(out, node.name)
                    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
                }
                else -> {
                    out.write(TYPE_SECTION)
                    writeString(out, node.name)
                    @Suppress("UNCHECKED_CAST")
                    writeSection(out, value as List<Node>)
                }
            }
        }
        out.write(TYPE_END)
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write(0)
    }
}
