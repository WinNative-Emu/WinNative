package com.winlator.cmod.runtime.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.epic.db.dao.EpicGameDao
import com.winlator.cmod.feature.stores.epic.service.EpicAuthManager
import com.winlator.cmod.feature.stores.epic.service.EpicGameLauncher
import com.winlator.cmod.feature.stores.steam.enums.AppType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Locale

/**
 * Signs an Epic game in as the Linux Steam client starts it.
 *
 * Epic authenticates a game with an exchange code that lasts five minutes and is spent the first
 * time it is used, so there is nothing to write into a shortcut ahead of time: the client may sit
 * in the library for an hour before the user presses Play, and pressing it twice would replay a
 * code that is already gone. The shortcut carries the game's Epic name instead, and
 * `winnative-epic-launch` in the runtime asks this server for the command line as the game starts.
 *
 * The server listens on the loopback address alone and answers only a caller that quotes the
 * secret written beside its port. That file is in the app's own runtime directory: the session can
 * read it, and another app on the device - which shares the loopback address - cannot.
 */
object LinuxEpicTokens {
    private const val TAG = "LinuxEpicTokens"

    /** Under the runtime's root, and `/run/winnative-epic` inside the session. */
    private const val DIR = "run/winnative-epic"
    private const val ENDPOINT = "endpoint"
    private const val TOKENS = "tokens"

    /** Long enough for the two Epic calls a DRM title needs, short enough to not hold a launch. */
    private const val REQUEST_TIMEOUT_MS = 45000

    private var server: ServerSocket? = null
    private var endpointFile: File? = null

    /**
     * Starts the server for this session, unless the user has no Epic account signed in. Does
     * nothing when one is already running, which is a session being rejoined.
     */
    @JvmStatic
    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val appContext = context.applicationContext
        val dir = File(LinuxRuntime.rootDir(appContext), DIR)
        val tokens = File(dir, TOKENS)
        // A session of an app run that is gone left its port behind; nothing answers there now,
        // and an account signed out since is not to be asked for again.
        File(dir, ENDPOINT).delete()
        if (!EpicAuthManager.isLoggedIn(appContext)) return
        try {
            // Tokens of an earlier session are spent; the game reads the one written for this run.
            tokens.listFiles()?.forEach { it.delete() }
            if (!tokens.isDirectory && !tokens.mkdirs()) throw IOException("could not create ${tokens.path}")
            val socket = ServerSocket()
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
            val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val endpoint = File(dir, ENDPOINT)
            LinuxSteamVdf.replace(endpoint) { it.writeText("${socket.localPort} $secret\n") }
            server = socket
            endpointFile = endpoint
            Thread({ serve(appContext, socket, secret) }, "epic-tokens").start()
        } catch (e: IOException) {
            Log.w(TAG, "could not start the Epic sign-in server: ${e.javaClass.simpleName}")
        }
    }

    /** Ends the session's server. The endpoint goes with it, so a stale port is never answered. */
    @JvmStatic
    @Synchronized
    fun stop() {
        endpointFile?.delete()
        endpointFile = null
        try {
            server?.close()
        } catch (e: IOException) {
            Log.w(TAG, "could not close the Epic sign-in server: ${e.javaClass.simpleName}")
        }
        server = null
    }

    /**
     * One request at a time: a launch is a rare event, and a game that is starting has the whole
     * answer before the next one asks.
     */
    private fun serve(context: Context, socket: ServerSocket, secret: String) {
        while (true) {
            try {
                socket.accept().use { client ->
                    client.soTimeout = REQUEST_TIMEOUT_MS
                    val request = client.getInputStream().bufferedReader().readLine().orEmpty()
                    val reply =
                        if (!request.startsWith("$secret ")) {
                            Log.w(TAG, "a caller without the session's secret was refused")
                            "error unauthorized"
                        } else {
                            answer(context, request.substring(secret.length + 1).trim())
                        }
                    client.getOutputStream().apply {
                        write(reply.toByteArray())
                        flush()
                    }
                }
            } catch (e: SocketException) {
                return // The session ended and stop() closed the socket.
            } catch (e: IOException) {
                if (socket.isClosed) return
                Log.w(TAG, "Epic sign-in request failed: ${e.javaClass.simpleName}")
            } catch (e: RuntimeException) {
                // One request that could not be answered is not the session's other games.
                Log.w(TAG, "Epic sign-in request failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** `ok` and one command-line argument per line, or `error <what went wrong>`. */
    private fun answer(context: Context, appName: String): String {
        if (appName.isEmpty()) return "error no game named"
        val game = gameOf(context, appName) ?: return "error $appName is not in the Epic library"
        val built =
            runBlocking(Dispatchers.IO) {
                EpicGameLauncher.buildLaunchParameters(
                    context = context,
                    game = game,
                    stageOwnershipToken = { hex -> stageOwnershipToken(context, game, hex) },
                )
            }
        val params =
            built.getOrElse { e ->
                Log.w(TAG, "could not sign $appName in: ${e.javaClass.simpleName}")
                return "error ${e.message ?: e.javaClass.simpleName}"
            }
        Log.i(TAG, "signed $appName in for the Linux client")
        return (listOf("ok") + params).joinToString("\n", postfix = "\n")
    }

    /**
     * The library's game [id] as the store recorded it, read from the store's own records: they are
     * there whether or not the Epic service happens to be running.
     */
    @JvmStatic
    fun gameOf(context: Context, id: Int): EpicGame? = read(context) { it.getById(id) }

    private fun gameOf(context: Context, appName: String): EpicGame? = read(context) { it.getByAppName(appName) }

    /**
     * The Epic games that are installed, as the store recorded them. The library writes a shortcut
     * for one only once it has been played, so this is what the client is given to show.
     */
    @JvmStatic
    fun installedGames(context: Context): List<EpicGame> =
        try {
            runBlocking(Dispatchers.IO) { PluviaDatabase.getInstance(context).epicGameDao().getAllAsList() }
                .filter { it.isInstalled && it.installPath.isNotEmpty() && !it.isDLC && it.type == AppType.game }
        } catch (e: RuntimeException) {
            Log.w(TAG, "the Epic library is unavailable: ${e.javaClass.simpleName}")
            emptyList()
        }

    private fun read(context: Context, query: suspend (EpicGameDao) -> EpicGame?): EpicGame? =
        try {
            runBlocking(Dispatchers.IO) { query(PluviaDatabase.getInstance(context).epicGameDao()) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "the Epic library is unavailable: ${e.javaClass.simpleName}")
            null
        }

    /**
     * Writes the ownership token a DRM title is started with and returns the path the game reads it
     * back by. Proton maps `Z:` to the session's root, so the runtime's own directory is reachable
     * without touching the prefix, which Proton has not built yet when the game is started.
     */
    private fun stageOwnershipToken(context: Context, game: EpicGame, hex: String): String {
        val name = (game.namespace + game.catalogId).lowercase(Locale.US).filter { it.isLetterOrDigit() } + ".ovt"
        val file = File(File(LinuxRuntime.rootDir(context), DIR), "$TOKENS/$name")
        LinuxSteamVdf.replace(file) { it.writeBytes(hex.chunked(2).map { byte -> byte.toInt(16).toByte() }.toByteArray()) }
        return "Z:\\${DIR.replace('/', '\\')}\\$TOKENS\\$name"
    }
}
