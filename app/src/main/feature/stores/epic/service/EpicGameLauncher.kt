package com.winlator.cmod.feature.stores.epic.service

import android.content.Context
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.epic.data.EpicGameToken
import com.winlator.cmod.runtime.container.Container
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Helper functionality for launching Epic Games with the correct execution params for online
 * verification.
 *
 * Handles:
 *  - Acquiring authentication tokens before launch (exchange code, ownership token).
 *  - Building Epic Games Services command-line parameters.
 *  - Managing ownership-token files for DRM-protected (Denuvo) titles inside the Wine prefix.
 *
 * Implementation notes (DRM):
 *  - For DRM titles the ownership token is delivered as a binary `.ovt` file referenced by
 *    the `-epicovt=<windows-path>` parameter. The token MUST live inside the Wine prefix
 *    because the dosdevices map exposes only the Z: → / passthrough plus the container's
 *    drives — the Android cache dir is not reachable on any drive letter.
 *  - For modern EOS-integrated titles the `-epicdeploymentid=<id>` parameter is required;
 *    without it games such as "Deliver At All Costs" refuse to start with
 *    "Failed to connect to the Epic Launcher".
 */
object EpicGameLauncher {
    /**
     * Build launch parameters for an Epic game.
     *
     * Returns a list of command-line arguments to pass to the game executable for Epic Games
     * Services authentication. When [container] is non-null and the game requires an ownership
     * token, the token is materialised inside the container's Wine prefix and referenced as a
     * Windows-style path; without a container the token cannot be delivered to DRM titles, unless
     * [stageOwnershipToken] writes it where the game can reach it and returns that path.
     */
    suspend fun buildLaunchParameters(
        context: Context,
        game: EpicGame,
        offline: Boolean = false,
        languageCode: String = "en-US",
        container: Container? = null,
        stageOwnershipToken: ((hex: String) -> String)? = null,
    ): Result<List<String>> =
        try {
            val params = mutableListOf<String>()

            if (offline) {
                if (game.canRunOffline) {
                    Timber.tag("EPIC").i("Launching ${game.appName} in offline mode (no authentication)")
                    Result.success(params)
                } else {
                    Timber.tag("EPIC").w("${game.appName} cannot run offline, will attempt online launch")
                    buildOnlineLaunch(context, game, languageCode, container, stageOwnershipToken, params)
                }
            } else {
                buildOnlineLaunch(context, game, languageCode, container, stageOwnershipToken, params)
            }
        } catch (e: Exception) {
            Timber.tag("EPIC").e(e, "Failed to build launch parameters")
            Result.failure(e)
        }

    private suspend fun buildOnlineLaunch(
        context: Context,
        game: EpicGame,
        languageCode: String,
        container: Container?,
        stageOwnershipToken: ((hex: String) -> String)?,
        params: MutableList<String>,
    ): Result<List<String>> {
        Timber.tag("EPIC").d("Launching ${game.appName} online, getting game launch token...")

        val tokenResult =
            EpicAuthManager.getGameLaunchToken(
                context = context,
                namespace = game.namespace,
                catalogItemId = game.catalogId,
                requiresOwnershipToken = game.requiresOT,
            )

        if (tokenResult.isFailure) {
            return Result.failure(tokenResult.exceptionOrNull() ?: Exception("Failed to get launch token"))
        }

        val gameToken: EpicGameToken =
            tokenResult.getOrNull()
                ?: run {
                    Timber.tag("EPIC").w("Game Token is null for ${game.appName}")
                    return Result.failure(Exception("Game token is null for ${game.appName}"))
                }

        Timber.tag("EPIC").d("Got Game Token for ${game.appName}")

        // Save ownership token into the Wine prefix so the game (running under Wine) can read
        // it via the C:\... path passed to -epicovt. Without [container] we cannot stage the
        // token in a Wine-reachable location and must skip — DRM titles will then fail to
        // verify ownership at startup.
        val ownershipTokenPath: String? =
            gameToken.ownershipToken?.let { hex ->
                when {
                    // A caller that runs the game outside a container of ours - the Linux Steam
                    // client does - says where the token goes and what path reads it back.
                    stageOwnershipToken != null -> stageOwnershipToken(hex)
                    container == null -> {
                        Timber.tag("EPIC").w(
                            "Ownership token requested for ${game.appName} but no container was supplied; " +
                                "DRM verification will be skipped",
                        )
                        null
                    }
                    else -> saveOwnershipTokenToPrefix(container, game.namespace, game.catalogId, hex)
                }
            }

        Timber.tag("EPIC").i("Game launch token obtained for ${game.appName}")

        params.add("-AUTH_LOGIN=unused")
        params.add("-AUTH_PASSWORD=${gameToken.authCode}")
        params.add("-AUTH_TYPE=exchangecode")
        params.add("-epicapp=${game.appName}")
        params.add("-epicenv=Prod")
        params.add("-EpicPortal")

        val displayName = gameToken.displayName.takeIf { it.isNotBlank() } ?: "EpicUser"
        val accountId = gameToken.accountId.ifBlank { "0" }
        params.add("-epicusername=$displayName")
        params.add("-epicuserid=$accountId")
        params.add("-epiclocale=$languageCode")
        params.add("-epicsandboxid=${game.namespace}")

        // EOS deployment id (sourced from the manifest API sidecar config). Required by modern
        // EOS-integrated titles — without it they fail with "Failed to connect to the Epic
        // Launcher, ensure it is running...". Null/empty for games that don't ship a sidecar.
        val deploymentId =
            EpicService.getInstance()?.epicManager?.fetchDeploymentId(
                context = context,
                namespace = game.namespace,
                catalogItemId = game.catalogId,
                appName = game.appName,
            )
        if (!deploymentId.isNullOrEmpty()) {
            params.add("-epicdeploymentid=$deploymentId")
            Timber.tag("EPIC").d("Added deployment id for ${game.appName}")
        }

        if (ownershipTokenPath != null) {
            params.add("-epicovt=$ownershipTokenPath")
            Timber.tag("EPIC").d("Added ownership token path: $ownershipTokenPath")
        }

        // Per-game extra command line from catalog customAttributes.AdditionalCommandLine.
        val additionalCommandLine =
            EpicService.getInstance()?.epicManager?.fetchAdditionalCommandLine(
                context = context,
                namespace = game.namespace,
                catalogItemId = game.catalogId,
                appName = game.appName,
            )
        if (!additionalCommandLine.isNullOrBlank()) {
            val extraArgs = tokenizeArgs(additionalCommandLine)
            params.addAll(extraArgs)
            Timber.tag("EPIC").d("Added ${extraArgs.size} additional command-line args for ${game.appName}")
        }

        Timber.tag("EPIC").d("Built ${params.size} launch parameters for ${game.appName}")
        return Result.success(params)
    }

    /**
     * Tokenise a Windows-style command-line string, preserving double-quoted segments. Adjacent
     * quoted/unquoted runs collapse into one token (so `-arg="value with spaces"` yields
     * `-arg=value with spaces`). Single quotes are treated as literal characters to match
     * `CommandLineToArgvW` semantics — args like `-name=Don't` must not be split or merged.
     * Unbalanced double quotes consume to end-of-string.
     */
    private fun tokenizeArgs(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inDouble = false
        var hasToken = false
        for (c in input) {
            when {
                inDouble -> if (c == '"') inDouble = false else current.append(c)
                c == '"' -> {
                    inDouble = true
                    hasToken = true
                }
                c.isWhitespace() -> {
                    if (hasToken) {
                        tokens.add(current.toString())
                        current.setLength(0)
                        hasToken = false
                    }
                }
                else -> {
                    current.append(c)
                    hasToken = true
                }
            }
        }
        if (hasToken) tokens.add(current.toString())
        return tokens
    }

    /**
     * Save ownership-token bytes inside [container]'s Wine prefix and return the Windows-style
     * path. The path lives in the Public Documents tree (C:\users\Public\Documents\EpicTokens)
     * which is always reachable inside the prefix.
     */
    private fun saveOwnershipTokenToPrefix(
        container: Container,
        namespace: String,
        catalogItemId: String,
        ownershipTokenHex: String,
    ): String {
        if (ownershipTokenHex.isEmpty()) {
            throw IllegalArgumentException("Ownership token hex string is empty")
        }
        if (ownershipTokenHex.length % 2 != 0) {
            throw IllegalArgumentException("Ownership token hex string has odd length: ${ownershipTokenHex.length}")
        }
        if (!ownershipTokenHex.matches(Regex("^[0-9A-Fa-f]+$"))) {
            throw IllegalArgumentException("Ownership token hex string contains invalid characters")
        }

        // Sanitise namespace and catalogItemId to prevent path traversal — values are reused
        // verbatim in the resulting filename.
        val fileName = "${namespace.sanitizeForFilename()}${catalogItemId.sanitizeForFilename()}.ovt"

        val tokenDirInPrefix = File(container.rootDir, ".wine/drive_c/users/Public/Documents/EpicTokens")
        if (!tokenDirInPrefix.exists() && !tokenDirInPrefix.mkdirs()) {
            throw IOException("Failed to create OT directory: ${tokenDirInPrefix.absolutePath}")
        }

        val tokenFile = File(tokenDirInPrefix, fileName)

        try {
            val tokenBytes =
                ownershipTokenHex
                    .chunked(2)
                    .map { hexByte ->
                        try {
                            hexByte.toInt(16).toByte()
                        } catch (e: NumberFormatException) {
                            throw IllegalArgumentException("Invalid hex byte: $hexByte", e)
                        }
                    }.toByteArray()

            tokenFile.writeBytes(tokenBytes)
            val winPath = "C:\\users\\Public\\Documents\\EpicTokens\\$fileName"
            Timber.tag("EPIC").d("Ownership token saved to: ${tokenFile.absolutePath} (wine path: $winPath)")
            return winPath
        } catch (e: IllegalArgumentException) {
            Timber.tag("EPIC").e(e, "Failed to parse ownership token hex string")
            throw e
        } catch (e: IOException) {
            Timber.tag("EPIC").e(e, "Failed to write ownership token file: ${tokenFile.absolutePath}")
            throw e
        }
    }

    /**
     * Clean up temporary ownership-token files after a game exits. Removes both the in-prefix
     * tokens (when [container] is provided) and any tokens left in the legacy app-cache
     * location from older builds.
     */
    fun cleanupOwnershipTokens(context: Context, container: Container? = null) {
        if (container != null) {
            try {
                val tokenDirInPrefix = File(container.rootDir, ".wine/drive_c/users/Public/Documents/EpicTokens")
                if (tokenDirInPrefix.exists() && tokenDirInPrefix.isDirectory) {
                    tokenDirInPrefix.listFiles()?.forEach { file ->
                        if (file.extension == "ovt") {
                            file.delete()
                            Timber.tag("EPIC").d("Deleted ownership token file: ${file.name}")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("EPIC").e(e, "Failed to cleanup ownership token files in prefix")
            }
        }

        // Legacy on-disk tokens from older builds that wrote into the app cache directory.
        try {
            val legacyDir = File(context.cacheDir, "epic_tokens")
            if (legacyDir.exists() && legacyDir.isDirectory) {
                legacyDir.listFiles()?.forEach { file ->
                    if (file.extension == "ovt") {
                        file.delete()
                        Timber.tag("EPIC").d("Deleted legacy ownership token file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("EPIC").e(e, "Failed to cleanup legacy ownership token files")
        }
    }
}
