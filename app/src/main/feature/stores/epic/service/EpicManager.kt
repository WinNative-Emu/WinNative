package com.winlator.cmod.feature.stores.epic.service
import android.content.Context
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.epic.db.dao.EpicGameDao
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import com.winlator.cmod.feature.stores.steam.utils.Net
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EpicManager handles Epic Games library management
 */
@Singleton
class EpicManager
    @Inject
    constructor(
        private val epicGameDao: EpicGameDao,
        @ApplicationContext private val context: Context,
    ) {
        private val REFRESH_BATCH_SIZE = 10

        private val httpClient = Net.http

        // Separate client for CDN downloads - no connection pooling, follows redirects
        private val cdnClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        data class EpicAssetList(
            val appName: String,
            val labelName: String,
            val buildVersion: String,
            val catalogItemId: String,
            val namespace: String,
            val assetId: String,
            val metadata: AssetMetadata?,
        )

        data class AssetMetadata(
            val installationPoolId: String,
            val update_type: String,
        )

        data class EpicLibraryItem(
            val namespace: String,
            val catalogItemId: String?,
            val appName: String,
            val country: String?,
            val platform: List<String>?,
            val productId: String,
            val sandboxName: String,
            val sandboxType: String,
            val recordType: String?,
            val acquisitionDate: String?,
            val dependencies: List<String>?,
        )

        data class ParsedLibraryItem(
            val appName: String,
            val namespace: String,
            val catalogItemId: String,
            val sandboxType: String?,
            val country: String?,
        )

        data class LibraryItemsResponse(
            val responseMetadata: ResponseMetadata,
            val records: List<EpicLibraryItem>?,
        )

        data class ResponseMetadata(
            val nextCursor: String?,
            val stateToken: String?,
        )

        data class ManifestSizes(
            val installSize: Long,
            val downloadSize: Long,
        )

        // Usually consists of DieselGameBox and DieselGameBoxTall that we can use.
        data class EpicKeyImage(
            val type: String,
            val url: String, // Full URL of the game art.
            val md5: String?,
            val width: Int?,
            val height: Int?,
            val size: Int?,
            val uploadedDate: String?, // "2019-12-19T21:54:10.003Z"
        )

        data class EpicCategory(
            val path: String,
        )

        data class EpicCustomAttributeValue(
            val type: String,
            val value: String,
        )

        // Custom Attributes from the payload.
        data class EpicCustomAttributes(
            val canRunOffline: Boolean = false,
            // True when Epic ships the title with DRM (Denuvo / per-launch ownership-token
            // verification). Source: customAttributes.OwnershipToken on the catalog API.
            val ownershipToken: Boolean = false,
            val cloudSaveFolder: String? = null,
            val cloudIncludeList: String? = null,
            val cloudExcludeList: String? = null,
            val neverUpdate: Boolean = false,
            val folderName: String? = null,
            val presenceId: String? = null,
            val monitorPresence: Boolean = false,
            val useAccessControl: Boolean = false,
            val canSkipKoreanIdVerification: Boolean = true,
            val partnerLinkType: String? = null, // Ubisoft
            val thirdPartyManagedProvider: String? = null, // UbisoftConnect
            val thirdPartyManagedApp: String? = null, // The EA App | Origin
            val partnerLinkId: String? = null,
            val backgroundProcessName: String? = null,
            val registryPath: String? = null,
            val registryLocation: String? = null,
            val registryKey: String? = null,
            val additionalCommandline: String? = null,
            val processNames: String? = null,
            val gameId: String? = null,
            val executableName: String? = null,
        )

        data class EpicReleaseInfo(
            val id: String,
            val appId: String,
            val platform: List<String>?,
            val dateAdded: String?,
            val releaseNote: String?,
            val versionTitle: String?,
        )

        data class EpicMainGameItem(
            val id: String,
            val namespace: String,
        )

        data class GameInfoResponse(
            val id: String,
            val title: String,
            val description: String,
            val keyImages: List<EpicKeyImage>,
            val categories: List<EpicCategory>,
            val namespace: String,
            val status: String?,
            val creationDate: String?, // "2025-03-04T08:39:07.841Z",
            val lastModifiedDate: String?, // "2025-03-06T07:37:16.597Z",
            val customAttributes: EpicCustomAttributes?,
            val entitlementName: String?,
            val entitlementType: String?,
            val itemType: String?,
            val releaseInfo: EpicReleaseInfo,
            val developer: String,
            val developerId: String?,
            val eulaIds: List<String>?,
            val endOfSupport: Boolean?,
            val mainGameItemList: List<String>?,
            val ageGatings: Map<String, Int>?,
            val applicationId: String?,
            val baseAppName: String?,
            val baseProductId: String?,
            val mainGameItem: EpicMainGameItem?,
        )

        /**
         * Refresh the entire library (called manually by user or after login)
         * Fetches all games from Epic via Legendary and updates the database
         */
        suspend fun refreshLibrary(context: Context): Result<Int> =
            withContext(Dispatchers.IO) {
                try {
                    if (!EpicAuthManager.hasStoredCredentials(context)) {
                        Timber.w("Cannot refresh library: not authenticated with Epic")
                        return@withContext Result.failure(Exception("Not authenticated with Epic"))
                    }

                    Timber.tag("Epic").i("Refreshing Epic library from Epic API...")

                    val listResult = fetchLibrary(context)

                    if (listResult.isFailure) {
                        val error = listResult.exceptionOrNull()
                        Timber.tag("Epic").e(error, "Failed to fetch games from Epic: ${error?.message}")
                        return@withContext Result.failure(error ?: Exception("Failed to fetch Epic library"))
                    }

                    val gamesList = listResult.getOrNull() ?: emptyList()
                    Timber.tag("Epic").i("Successfully fetched ${gamesList.size} games from Epic")

                    if (gamesList.isEmpty()) {
                        Timber.tag("Epic").w("No games found in Epic library")
                        return@withContext Result.success(0)
                    }

                    val epicGames = mutableListOf<EpicGame>()
                    var processedCount = 0
                    for ((index, game) in gamesList.withIndex()) {
                        val result = fetchGameInfo(context, game)

                        if (result.isSuccess) {
                            val epicGame = result.getOrNull()
                            if (epicGame != null) {
                                epicGames.add(epicGame)
                                processedCount++
                                Timber.tag("Epic").d("Refreshed Game: ${epicGame.title}")
                            }
                        } else {
                            Timber.tag("Epic").w("Epic game ${game.appName} could not be fetched")
                        }

                        if ((index + 1) % REFRESH_BATCH_SIZE == 0 || index == gamesList.lastIndex) {
                            if (epicGames.isNotEmpty()) {
                                epicGameDao.upsertPreservingInstallStatus(epicGames)
                                Timber.tag("Epic").d("Batch inserted ${epicGames.size} games (processed ${index + 1}/${gamesList.size})")
                                epicGames.clear()
                            }
                        }
                    }

                    val detectedCount = detectAndUpdateExistingInstallations()
                    if (detectedCount > 0) {
                        Timber.tag("Epic").i("Detected and updated $detectedCount existing Epic installations")
                    }

                    Timber.tag("Epic").i("Successfully refreshed Epic library")
                    Result.success(processedCount + detectedCount)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to refresh Epic library")
                    Result.failure(e)
                }
            }

        private suspend fun detectAndUpdateExistingInstallations(): Int =
            withContext(Dispatchers.IO) {
                var detectedCount = 0
                val allGames = epicGameDao.getAllAsList()

                for (game in allGames) {
                    if (game.isInstalled && InstallOwnership.isForeign(game.installPath, InstallStore.EPIC)) {
                        epicGameDao.update(game.copy(isInstalled = false, installPath = ""))
                        continue
                    }

                    if (
                        game.isInstalled &&
                        game.installPath.isNotEmpty() &&
                        File(game.installPath).exists() &&
                        MarkerUtils.hasMarker(game.installPath, Marker.DOWNLOAD_COMPLETE_MARKER) &&
                        !MarkerUtils.hasMarker(game.installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    ) {
                        continue
                    }

                    val candidatePaths = linkedSetOf<String>()
                    if (game.installPath.isNotBlank()) {
                        candidatePaths.add(game.installPath)
                    }
                    if (game.title.isNotBlank()) {
                        candidatePaths.add(EpicConstants.getGameInstallPath(context, game.title))
                    }
                    if (game.appName.isNotBlank()) {
                        candidatePaths.add(EpicConstants.getGameInstallPath(context, game.appName))
                    }

                    val detectedPath =
                        candidatePaths.firstOrNull { installPath ->
                            installPath.isNotBlank() &&
                                File(installPath).isDirectory &&
                                !InstallOwnership.isForeign(installPath, InstallStore.EPIC) &&
                                MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER) &&
                                !MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                        } ?: continue

                    val updatedGame =
                        game.copy(
                            isInstalled = true,
                            installPath = detectedPath,
                            executable = game.executable.ifBlank { getInstalledExeCandidate(detectedPath) },
                        )
                    epicGameDao.update(updatedGame)
                    detectedCount++
                    Timber.tag("Epic").i("Recovered installed Epic game ${game.title} from $detectedPath")
                }

                detectedCount
            }

        private fun getInstalledExeCandidate(installPath: String): String {
            val rootDir = File(installPath)
            if (!rootDir.isDirectory) return ""

            return rootDir
                .walkTopDown()
                .maxDepth(3)
                .firstOrNull { file ->
                    file.isFile &&
                        file.extension.equals("exe", ignoreCase = true) &&
                        !file.name.equals("EpicGamesLauncher.exe", ignoreCase = true)
                }?.relativeTo(rootDir)
                ?.path
                ?.replace(File.separatorChar, '/')
                .orEmpty()
        }

        /**
         *
         * Returns list of library items with app names, namespaces, and catalog IDs
         */
        suspend fun fetchLibrary(context: Context): Result<List<ParsedLibraryItem>> =
            withContext(Dispatchers.IO) {
                try {
                    val credentials = EpicAuthManager.getStoredCredentials(context)
                    if (credentials.isFailure) {
                        return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
                    }

                    val accessToken = credentials.getOrNull()?.accessToken
                    if (accessToken.isNullOrEmpty()) {
                        return@withContext Result.failure(Exception("No access token"))
                    }

                    val gameList = mutableListOf<ParsedLibraryItem>()
                    var cursor: String? = null

                    // Fetch all pages of library items
                    do {
                        val url =
                            buildString {
                                append("${EpicConstants.EPIC_LIBRARY_API_URL}?includeMetadata=true")
                                if (cursor != null) {
                                    append("&cursor=$cursor")
                                }
                            }

                        val request =
                            Request
                                .Builder()
                                .url(url)
                                .header("Authorization", "Bearer $accessToken")
                                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                                .get()
                                .build()

                        Timber.tag("Epic").d("Fetching Epic library page: cursor=$cursor")

                        val response = httpClient.newCall(request).execute()

                        if (!response.isSuccessful) {
                            val error = response.body?.string() ?: "Unknown error"
                            Timber.tag("Epic").e("Library fetch failed: ${response.code} - $error")
                            return@withContext Result.failure(Exception("HTTP ${response.code}: $error"))
                        }

                        val body = response.body?.string()
                        if (body.isNullOrEmpty()) {
                            Timber.tag("Epic").e("Empty response body from library API")
                            return@withContext Result.failure(Exception("Empty response"))
                        }

                        val json = JSONObject(body)
                        val records = json.optJSONArray("records") ?: JSONArray()

                        Timber.tag("Epic").d("Received ${records.length()} library items in this page")

                        for (i in 0 until records.length()) {
                            val record = records.getJSONObject(i)

                            // Skip items without app name
                            if (!record.has("appName")) {
                                continue
                            }

                            val appName = record.getString("appName")
                            val namespace = record.getString("namespace")
                            val catalogItemId = record.getString("catalogItemId")
                            val sandboxType = record.optString("sandboxType", "")
                            val country = record.optString("country", "")
                            val platformsArray = record.optJSONArray("platform")
                            val platforms =
                                buildList {
                                    if (platformsArray != null) {
                                        for (j in 0 until platformsArray.length()) {
                                            add(platformsArray.getString(j))
                                        }
                                    }
                                }

                            // Skip UE assets, private sandboxes, and broken entries
                            if (namespace == "ue" || sandboxType == "PRIVATE" || appName == "1") {
                                Timber.tag("Epic").d("Skipping due to invalid app: $appName (namespace=$namespace, sandbox=$sandboxType)")
                                continue
                            }

                            // Skip invalid platform (such as Android versions)
                            if (platforms.isNotEmpty() && !platforms.contains("Win32") && !platforms.contains("Windows")) {
                                Timber
                                    .tag(
                                        "Epic",
                                    ).d("Skipping due to invalid platform: $appName (namespace=$namespace, sandbox=$sandboxType)")
                                continue
                            }

                            val gameInfo = ParsedLibraryItem(appName, namespace, catalogItemId, sandboxType, country)
                            gameList.add(gameInfo)
                        }
                        // Get cursor for next page - stop if cursor is null or same as previous
                        val metadata = json.optJSONObject("responseMetadata")
                        val oldCursor = cursor
                        cursor = metadata?.optString("nextCursor")?.takeIf { it.isNotEmpty() }
                    } while (cursor != null && cursor != oldCursor)

                    Timber.tag("Epic").i("Successfully fetched ${gameList.size} games from Epic library")
                    Result.success(gameList)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Failed to fetch Epic library")
                    Result.failure(e)
                }
            }

        /**
         * Resolves the effective launch executable for an Epic game.
         * Returns empty string if game is not installed or no executable can be found.
         */
        suspend fun getLaunchExecutable(appId: Int): String = getInstalledExe(appId)

        suspend fun getInstalledExe(appId: Int): String {
            // Strip EPIC_ prefix to get the raw Epic app name
            val game = getGameById(appId)
            if (game == null || !game.isInstalled || game.installPath.isEmpty()) {
                Timber.tag("Epic").e("Game not installed: $appId")
                return ""
            }

            val installDir = File(game.installPath)
            if (!installDir.exists()) {
                Timber.tag("Epic").e("Install directory does not exist: ${game.installPath}")
                return ""
            }

            // 1. Prioritize manifest's launch executable if stored in database
            if (game.executable.isNotEmpty()) {
                val storedExeFile = File(installDir, game.executable)
                if (storedExeFile.exists()) {
                    Timber.tag("Epic").i("Found stored executable from manifest: ${storedExeFile.absolutePath}")
                    return game.executable
                } else {
                    Timber.tag("Epic").w("Stored executable not found on disk: ${game.executable}, falling back to heuristic")
                }
            }

            // 2. Fallback heuristic for finding the main executable
            // Common patterns: Game.exe, GameName.exe, or in Binaries/Win64/
            val exeFiles =
                installDir
                    .walk()
                    .filter { it.extension.equals("exe", ignoreCase = true) }
                    .filter { !it.name.contains("UnityCrashHandler", ignoreCase = true) }
                    .filter { !it.name.contains("UnrealCEFSubProcess", ignoreCase = true) }
                    .filter { !it.name.contains("launcher", ignoreCase = true) }
                    .toList()

            // Prioritize 64-bit executables (Win64, x64, or filename containing 64)
            val mainExe =
                exeFiles.find {
                    it.absolutePath.lowercase().contains("win64") ||
                        it.absolutePath.lowercase().contains("x64") ||
                        it.name.lowercase().contains("64")
                } ?: exeFiles.sortedBy { it.absolutePath.length }.firstOrNull()

            if (mainExe != null) {
                Timber.tag("Epic").i("Found executable via heuristic: ${mainExe.absolutePath}")
                return mainExe.relativeTo(installDir).path
            }

            Timber.tag("Epic").w("No executable found in ${game.installPath}")
            return ""
        }

        private suspend fun fetchGameInfo(
            context: Context,
            game: ParsedLibraryItem,
        ): Result<EpicGame> =
            withContext(Dispatchers.IO) {
                try {
                    val credentials = EpicAuthManager.getStoredCredentials(context)
                    if (credentials.isFailure) {
                        return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
                    }

                    val accessToken = credentials.getOrNull()?.accessToken
                    if (accessToken.isNullOrEmpty()) {
                        return@withContext Result.failure(Exception("No access token"))
                    }

                    val country = game.country ?: "US" // Do we really need this?

                    // ! We should expertiment with the country to see what affects language downloads
                    val url =
                        "${EpicConstants.EPIC_CATALOG_API_URL}/shared/namespace/${game.namespace}/bulk/items" +
                            "?id=${game.catalogItemId}&includeDLCDetails=true&includeMainGameDetails=true" +
                            "&country=$country"

                    Timber.tag("Epic").d("fetching game info for ${game.appName} - url: $url")

                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .header("Authorization", "Bearer $accessToken")
                            .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                            .get()
                            .build()

                    val response = httpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        Timber.w("Failed to fetch game info for ${game.catalogItemId}: ${response.code}")
                        return@withContext Result.failure(Exception("Could not fetch game info: ${response.code}"))
                    }

                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) {
                        return@withContext Result.failure(Exception("Could not fetch game info for ${game.catalogItemId}"))
                    }

                    val json = JSONObject(body)
                    val gameData: JSONObject? = json.optJSONObject(game.catalogItemId)

                    if (gameData != null) {
                        val epicGame = parseGameFromCatalog(gameData, game.appName)
                        return@withContext Result.success(epicGame)
                    } else {
                        return@withContext Result.failure(Exception("Game data not found in response"))
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Error fetching game info for ${game.catalogItemId}")
                    return@withContext Result.failure(e)
                }
            }

        /**
         * Parse customAttributes object from Epic catalog API
         */
        private fun parseCustomAttributes(customAttributesJson: JSONObject?): EpicCustomAttributes {
            if (customAttributesJson == null) {
                return EpicCustomAttributes()
            }

            // Helper function to extract value from attribute object
            fun getAttribute(name: String): String? {
                val attrObj = customAttributesJson.optJSONObject(name)
                return attrObj?.optString("value")?.takeIf { it.isNotEmpty() }
            }

            // Helper function to parse boolean attributes
            fun getBooleanAttribute(
                name: String,
                default: Boolean = false,
            ): Boolean {
                val value = getAttribute(name)
                return when (value?.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> default
                }
            }

            return EpicCustomAttributes(
                canRunOffline = getBooleanAttribute("CanRunOffline", false),
                ownershipToken = getBooleanAttribute("OwnershipToken", false),
                cloudSaveFolder = getAttribute("CloudSaveFolder"),
                cloudIncludeList = getAttribute("CloudIncludeList"),
                cloudExcludeList = getAttribute("CloudExcludeList"),
                folderName = getAttribute("FolderName"),
                presenceId = getAttribute("PresenceId"),
                monitorPresence = getBooleanAttribute("MonitorPresence", false),
                useAccessControl = getBooleanAttribute("UseAccessControl", false),
                canSkipKoreanIdVerification = getBooleanAttribute("CanSkipKoreanIdVerification", true),
                thirdPartyManagedApp = getAttribute("ThirdPartyManagedApp"),
                thirdPartyManagedProvider = getAttribute("ThirdPartyManagedProvider"),
                partnerLinkType = getAttribute("partnerLinkType"),
                executableName = getAttribute("MainWindowProcessName"),
                // Per-game extra args from `customAttributes.AdditionalCommandLine`. Mirrors
                // legendary `Game.additional_command_line` and is consumed by
                // [fetchAdditionalCommandLine] when building launch params.
                additionalCommandline = getAttribute("AdditionalCommandLine"),
            )
        }

        /**
         * Parse Epic catalog JSON into EpicGame object
         */
        internal fun parseGameFromCatalog(
            data: JSONObject,
            libraryAppName: String,
        ): EpicGame {
            val catalogItemId = data.getString("id")
            val namespace = data.getString("namespace")
            val title = data.getString("title")
            val description = data.optString("description", "")

            val appName = libraryAppName

            val keyImages = data.optJSONArray("keyImages")
            var artCover = "" // DieselGameBoxTall - Tall cover art
            var artSquare = "" // DieselGameBox - Square box art
            var artLogo = "" // DieselGameBoxLogo - Logo image
            var artPortrait = "" // DieselStoreFrontWide - Wide banner

            if (keyImages != null) {
                for (i in 0 until keyImages.length()) {
                    val img = keyImages.getJSONObject(i)
                    val imgType = img.optString("type")
                    val imgUrl = img.optString("url", "")

                    when (imgType) {
                        "DieselGameBoxTall" -> artCover = imgUrl
                        "DieselGameBox" -> artSquare = imgUrl
                        "DieselGameBoxLogo" -> artLogo = imgUrl
                        "DieselStoreFrontWide" -> artPortrait = imgUrl
                        "Thumbnail" -> if (artSquare.isEmpty()) artSquare = imgUrl
                    }
                }
            }

            val isDLC = data.has("mainGameItem")
            val baseGameAppName =
                if (isDLC) {
                    data.optJSONObject("mainGameItem")?.optString("id", "") ?: ""
                } else {
                    ""
                }

            val developer = data.optString("developer", "")

            val categories = data.optJSONArray("categories")
            var isMod = false
            if (categories != null) {
                for (i in 0 until categories.length()) {
                    val cat = categories.getJSONObject(i)
                    if (cat.optString("path") == "mods") {
                        isMod = true
                        break
                    }
                }
            }

            // Release date - convert to string format
            val releaseInfo = data.optJSONArray("releaseInfo")
            var releaseDate = ""
            if (releaseInfo != null && releaseInfo.length() > 0) {
                val release = releaseInfo.getJSONObject(0)
                releaseDate = release.optString("dateAdded", "")
            }
            val genresList = mutableListOf<String>()
            val tagsList = mutableListOf<String>()
            if (categories != null) {
                for (i in 0 until categories.length()) {
                    val cat = categories.getJSONObject(i)
                    val path = cat.optString("path", "")
                    if (path.startsWith("games/")) {
                        genresList.add(path.removePrefix("games/"))
                    } else if (path.isNotEmpty() && path != "mods") {
                        tagsList.add(path)
                    }
                }
            }

            // Parse custom attributes for cloud saves and offline support
            val parsedAttributes = parseCustomAttributes(data.optJSONObject("customAttributes"))
            val canRunOffline = parsedAttributes.canRunOffline
            // Whether Epic ships this title with DRM (Denuvo / per-launch ownership-token
            // verification). When true, `EpicGameLauncher` materialises a `.ovt` file inside
            // the Wine prefix and passes its path via `-epicovt=...` so the game can
            // verify ownership at startup.
            val requiresOwnershipToken = parsedAttributes.ownershipToken
            val cloudSaveEnabled = !parsedAttributes.cloudSaveFolder.isNullOrEmpty()
            val saveFolder = parsedAttributes.cloudSaveFolder ?: ""
            val cloudIncludeList = parsedAttributes.cloudIncludeList ?: ""
            val cloudExcludeList = parsedAttributes.cloudExcludeList ?: ""
            val executable = parsedAttributes.executableName ?: ""
            val thirdPartyApp =
                listOfNotNull(
                    parsedAttributes.thirdPartyManagedApp,
                    parsedAttributes.thirdPartyManagedProvider,
                    parsedAttributes.partnerLinkType,
                ).firstOrNull() ?: ""

            val isEaManaged =
                if (parsedAttributes.thirdPartyManagedApp != null &&
                    parsedAttributes.thirdPartyManagedApp.lowercase() in listOf("origin", "the ea app")
                ) {
                    true
                } else {
                    false
                }

            Timber.d(
                "Game $appName - CloudSaveFolder: $saveFolder, CloudIncludeList: ${parsedAttributes.cloudIncludeList}, CloudExcludeList: ${parsedAttributes.cloudExcludeList}, CanRunOffline: $canRunOffline",
            )

            return EpicGame(
                id = 0, // Auto-generated by Room
                catalogId = catalogItemId,
                appName = appName,
                title = title,
                namespace = namespace,
                developer = developer,
                publisher = "",
                description = description,
                artCover = artCover,
                artSquare = artSquare,
                artLogo = artLogo,
                artPortrait = artPortrait,
                isDLC = isDLC,
                baseGameAppName = baseGameAppName,
                releaseDate = releaseDate,
                genres = genresList,
                tags = tagsList,
                isInstalled = false, // Will be updated from local database
                installPath = "",
                platform = "Windows",
                version = "",
                executable = executable,
                installSize = 0,
                downloadSize = 0,
                canRunOffline = canRunOffline, // Unknown from catalog API, will need manifest
                requiresOT = requiresOwnershipToken,
                cloudSaveEnabled = cloudSaveEnabled,
                saveFolder = saveFolder,
                cloudIncludeList = cloudIncludeList,
                cloudExcludeList = cloudExcludeList,
                thirdPartyManagedApp = thirdPartyApp,
                isEAManaged = isEaManaged,
                lastPlayed = 0,
                playTime = 0,
            )
        }

        suspend fun deleteAllNonInstalledGames() {
            withContext(Dispatchers.IO) {
                epicGameDao.deleteAllNonInstalledGames()
            }
        }

        /**
         * Get a single game by ID
         */
        suspend fun getGamesById(gameIds: List<Int>): List<EpicGame> =
            withContext(Dispatchers.IO) {
                try {
                    epicGameDao.getGamesById(gameIds)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get Epic games by IDs: ${gameIds.size}")
                    emptyList()
                }
            }

        /**
         * Get a single game by ID
         */
        suspend fun getGameById(appId: Int): EpicGame? =
            withContext(Dispatchers.IO) {
                try {
                    epicGameDao.getById(appId)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get Epic game by ID: $appId")
                    null
                }
            }

        suspend fun getGameByCatalogId(catalogId: String): EpicGame? =
            withContext(Dispatchers.IO) {
                try {
                    epicGameDao.getByCatalogId(catalogId)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get Epic game by catalogId: $catalogId")
                    null
                }
            }

        suspend fun getDLCForTitle(appId: Int): List<EpicGame> =
            withContext(Dispatchers.IO) {
                try {
                    Timber.tag("Epic").i("Getting DLC for appId: $appId")
                    epicGameDao.getDLCForTitle(appId).firstOrNull() ?: emptyList()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get DLC for app name: $appId")
                    emptyList()
                }
            }

        /**
         * Get a single game by app name (Legendary identifier)
         */
        suspend fun getGameByAppName(appName: String): EpicGame? =
            withContext(Dispatchers.IO) {
                try {
                    epicGameDao.getByAppName(appName)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get Epic game by app name: $appName")
                    null
                }
            }

        suspend fun insertGame(game: EpicGame) {
            withContext(Dispatchers.IO) {
                epicGameDao.insert(game)
            }
        }

        suspend fun updateGame(game: EpicGame) {
            withContext(Dispatchers.IO) {
                epicGameDao.update(game)
            }
        }

        suspend fun getAllGames(): List<EpicGame> =
            withContext(Dispatchers.IO) {
                epicGameDao.getAllAsList()
            }

        suspend fun uninstall(appId: Int) {
            withContext(Dispatchers.IO) {
                epicGameDao.uninstall(appId)
            }
        }

        /**
         * Start background sync (called after login)
         */
        suspend fun startBackgroundSync(context: Context): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    if (!EpicAuthManager.hasStoredCredentials(context)) {
                        Timber.w("Cannot start background sync: no stored credentials")
                        return@withContext Result.failure(Exception("No stored credentials found"))
                    }

                    Timber.tag("Epic").i("Starting Epic library background sync...")

                    val result = refreshLibrary(context)

                    if (result.isSuccess) {
                        val count = result.getOrNull() ?: 0
                        Timber.tag("Epic").i("Background sync completed: $count games synced")
                        Result.success(Unit)
                    } else {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "Background sync failed: ${error?.message}")
                        Result.failure(error ?: Exception("Background sync failed"))
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to sync Epic library in background")
                    Result.failure(e)
                }
            }

        data class ManifestResult(
            val manifestBytes: ByteArray,
            val cdnUrls: List<CdnUrl>,
        )

        data class CdnUrl(
            val baseUrl: String,
            val authQueryParams: String,
            val cloudDir: String = "", // Full build path for chunk downloads
        )

        /**
         * Fetch manifest binary data from Epic API and CDN
         *
         * Returns the raw manifest bytes and CDN base URLs from the API response
         */
        suspend fun fetchManifestFromEpic(
            context: Context,
            namespace: String,
            catalogItemId: String,
            appName: String,
        ): Result<ManifestResult> =
            withContext(Dispatchers.IO) {
                try {
                    val credentials = EpicAuthManager.getStoredCredentials(context)
                    if (credentials.isFailure) {
                        return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
                    }

                    val accessToken = credentials.getOrNull()?.accessToken
                    if (accessToken.isNullOrEmpty()) {
                        return@withContext Result.failure(Exception("No access token"))
                    }

                    // Fetch manifest URL from Epic API
                    val manifestUrl =
                        "${EpicConstants.EPIC_LAUNCHER_API_URL}/launcher/api/public/assets/v2/platform" +
                            "/Windows/namespace/$namespace/catalogItem/$catalogItemId/app" +
                            "/$appName/label/Live"

                    Timber.tag("Epic").d("Fetching manifest metadata from: $manifestUrl")

                    val request =
                        Request
                            .Builder()
                            .url(manifestUrl)
                            .header("Authorization", "Bearer $accessToken")
                            .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                            .get()
                            .build()

                    val manifestJson =
                        httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                return@withContext Result.failure(Exception("Manifest API request failed: ${response.code}"))
                            }

                            val body = response.body?.string()
                            if (body.isNullOrEmpty()) {
                                return@withContext Result.failure(Exception("Empty manifest API response"))
                            }

                            JSONObject(body)
                        }
                    val elements = manifestJson.optJSONArray("elements")

                    if (elements == null || elements.length() == 0) {
                        return@withContext Result.failure(Exception("No elements in manifest API response"))
                    }

                    val element = elements.getJSONObject(0)
                    val manifests = element.optJSONArray("manifests")

                    if (manifests == null || manifests.length() == 0) {
                        return@withContext Result.failure(Exception("No manifests in API response"))
                    }

                    // Extract CDN base URLs from manifest URIs with their auth tokens
                    // Each manifest entry represents the same content on a different CDN
                    val cdnUrls = mutableListOf<CdnUrl>()
                    for (i in 0 until manifests.length()) {
                        val manifest = manifests.getJSONObject(i)
                        val uri = manifest.getString("uri")

                        val baseUrl = uri.substringBefore("/Builds")
                        if (baseUrl.isEmpty() || !baseUrl.startsWith("http")) {
                            continue
                        }

                        // Example: https://fastly-download.epicgames.com/Builds/Org/{org}/{build}/default/...
                        // CloudDir: /Builds/Org/{org}/{build}/default
                        val cloudDir =
                            if (uri.contains("/Builds")) {
                                val afterBase = uri.substringAfter(baseUrl)
                                val manifestFilename = afterBase.substringAfterLast("/")
                                afterBase.substringBefore("/" + manifestFilename)
                            } else {
                                ""
                            }

                        // Extract authentication query parameters for this CDN
                        val queryParams = manifest.optJSONArray("queryParams")
                        val authParams =
                            if (queryParams != null && queryParams.length() > 0) {
                                val params = StringBuilder("?")
                                for (j in 0 until queryParams.length()) {
                                    val param = queryParams.getJSONObject(j)
                                    val name = param.getString("name")
                                    val value = param.getString("value")
                                    if (j > 0) params.append("&")
                                    params.append("$name=$value")
                                }
                                params.toString()
                            } else {
                                ""
                            }

                        cdnUrls.add(CdnUrl(baseUrl, authParams, cloudDir))
                    }

                    // Error if no CDN URLs could be extracted
                    if (cdnUrls.isEmpty()) {
                        return@withContext Result.failure(Exception("No CDN URLs found in manifest API response"))
                    }

                    Timber.tag("Epic").d("Found ${cdnUrls.size} CDN mirrors")

                    val expectedManifestHash = element.optString("hash", "")
                    var manifestBytes: ByteArray? = null
                    var lastManifestError: Exception? = null

                    for (i in 0 until manifests.length()) {
                        val manifestObj = manifests.getJSONObject(i)
                        var manifestUri = manifestObj.getString("uri")

                        // Append query parameters (CDN authentication tokens) for manifest download.
                        val manifestQueryParams = manifestObj.optJSONArray("queryParams")
                        if (manifestQueryParams != null && manifestQueryParams.length() > 0) {
                            val params = StringBuilder()
                            for (j in 0 until manifestQueryParams.length()) {
                                val param = manifestQueryParams.getJSONObject(j)
                                val name = param.getString("name")
                                val value = param.getString("value")
                                if (j == 0) {
                                    params.append("?")
                                } else {
                                    params.append("&")
                                }
                                params.append("$name=$value")
                            }
                            manifestUri += params.toString()
                        }

                        Timber.tag("Epic").d("Downloading manifest binary from: $manifestUri")

                        // Manifest downloads from CDN don't need/accept Epic auth tokens.
                        val manifestRequest =
                            Request
                                .Builder()
                                .url(manifestUri)
                                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                                .get()
                                .build()

                        try {
                            val bytes =
                                cdnClient.newCall(manifestRequest).execute().use { manifestResponse ->
                                    if (!manifestResponse.isSuccessful) {
                                        throw Exception("Failed to download manifest binary: ${manifestResponse.code}")
                                    }

                                    manifestResponse.body?.bytes()
                                        ?: throw Exception("Empty manifest bytes from CDN")
                                }

                            if (expectedManifestHash.isNotEmpty() && !verifyManifestHash(bytes, expectedManifestHash)) {
                                throw Exception("Manifest SHA-1 mismatch")
                            }

                            manifestBytes = bytes
                            break
                        } catch (e: Exception) {
                            Timber.tag("Epic").w(e, "Unable to download manifest from ${manifestObj.getString("uri")}, trying next URL")
                            lastManifestError = e
                        }
                    }

                    val finalManifestBytes =
                        manifestBytes
                            ?: return@withContext Result.failure(
                                lastManifestError ?: Exception("Unable to download manifest from any CDN URL"),
                            )

                    Timber.tag("Epic").d("Manifest fetched with ${cdnUrls.size} CDN URLs")
                    Result.success(ManifestResult(finalManifestBytes, cdnUrls))
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Exception fetching manifest")
                    Result.failure(e)
                }
            }

        private fun verifyManifestHash(
            manifestBytes: ByteArray,
            expectedHash: String,
        ): Boolean {
            val actualHash =
                MessageDigest
                    .getInstance("SHA-1")
                    .digest(manifestBytes)
                    .joinToString("") { "%02x".format(it) }
            return actualHash.equals(expectedHash, ignoreCase = true)
        }

        /**
         * Fetch install size for a game by downloading its manifest
         * Manifest is small (~500KB-1MB) and contains all file metadata
         * Returns size in bytes, or 0 if failed
         */
        suspend fun fetchManifestSizes(
            context: Context,
            appId: Int,
        ): ManifestSizes =
            withContext(Dispatchers.IO) {
                try {
                    val game = getGameById(appId)

                    if (game == null) {
                        Timber.tag("Epic").w("Game not found in database: $game.appName")
                        return@withContext ManifestSizes(installSize = 0L, downloadSize = 0L)
                    }

                    val appName = game.appName

                    // Fetch manifest using shared function
                    val manifestResult = fetchManifestFromEpic(context, game.namespace, game.catalogId, game.appName)
                    if (manifestResult.isFailure) {
                        Timber.tag("Epic").w("Failed to fetch manifest: ${manifestResult.exceptionOrNull()?.message}")
                        return@withContext ManifestSizes(installSize = 0L, downloadSize = 0L)
                    }

                    val manifestData = manifestResult.getOrNull()!!

                    val manifest =
                        com.winlator.cmod.feature.stores.epic.service.manifest.EpicManifest
                            .readAll(manifestData.manifestBytes)

                    // Required-only sizes for detail page display (download uses container language via getSizesForSelectedInstallTags elsewhere).
                    val (downloadSize, installSize) =
                        com.winlator.cmod.feature.stores.epic.service.manifest.ManifestUtils
                            .getSizesForSelectedInstallTags(
                                manifest,
                                emptyList(),
                            )
                    Timber.tag("Epic").d(
                        "Manifest stats for $appName: version=${manifest.version}, featureLevel=${manifest.meta?.featureLevel}, " +
                            "buildVersion=${manifest.meta?.buildVersion}, buildId=${manifest.meta?.buildId}",
                    )
                    Timber.tag("Epic").d(
                        "Manifest stats for $appName: files=${manifest.fileManifestList?.count}, " +
                            "chunks=${manifest.chunkDataList?.count}",
                    )
                    Timber.tag("Epic").d("Install size for $appName: $installSize bytes")
                    Timber.tag("Epic").d("Download size for $appName: $downloadSize bytes")

                    if (installSize > 0L || downloadSize > 0L) {
                        try {
                            epicGameDao.update(
                                game.copy(
                                    installSize = installSize.takeIf { it > 0L } ?: game.installSize,
                                    downloadSize = downloadSize.takeIf { it > 0L } ?: game.downloadSize,
                                ),
                            )
                        } catch (e: Exception) {
                            Timber.tag("Epic").w(e, "Failed to cache manifest sizes for $appName")
                        }
                    }

                    return@withContext ManifestSizes(installSize = installSize, downloadSize = downloadSize)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Exception fetching install size for appId: $appId")
                    ManifestSizes(installSize = 0L, downloadSize = 0L)
                }
            }

        // Fetch the EOS deployment id for a game from the launcher manifest API.
        suspend fun fetchDeploymentId(
            context: Context,
            namespace: String,
            catalogItemId: String,
            appName: String,
            forceRefresh: Boolean = false,
        ): String? =
            withContext(Dispatchers.IO) {
                val cacheDir = File(context.filesDir, "epic/deployment_ids").also { it.mkdirs() }
                val cacheFile = File(cacheDir, "${appName.sanitizeForFilename()}.txt")

                if (!forceRefresh && cacheFile.exists()) {
                    val cacheAgeMs = System.currentTimeMillis() - cacheFile.lastModified()
                    if (cacheAgeMs < EPIC_LAUNCH_METADATA_CACHE_TTL_MS) {
                        return@withContext cacheFile.readText().trim().takeIf { it.isNotEmpty() }
                    }
                    Timber.tag("Epic").d(
                        "fetchDeploymentId cache for $appName is stale (age=${cacheAgeMs}ms), refetching",
                    )
                }

                try {
                    val accessToken = EpicAuthManager.getStoredCredentials(context).getOrNull()?.accessToken
                    if (accessToken.isNullOrEmpty()) {
                        Timber.tag("Epic").w("fetchDeploymentId: no access token")
                        return@withContext null
                    }

                    val manifestUrl =
                        "${EpicConstants.EPIC_LAUNCHER_API_URL}/launcher/api/public/assets/v2/platform" +
                            "/Windows/namespace/$namespace/catalogItem/$catalogItemId/app" +
                            "/$appName/label/Live"

                    val request =
                        Request
                            .Builder()
                            .url(manifestUrl)
                            .header("Authorization", "Bearer $accessToken")
                            .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                            .get()
                            .build()

                    val manifestJson =
                        httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                Timber.tag("Epic").w("fetchDeploymentId: manifest API ${response.code} for $appName")
                                return@withContext null
                            }
                            val body = response.body?.string()
                            if (body.isNullOrEmpty()) return@withContext null
                            JSONObject(body)
                        }

                    val elements = manifestJson.optJSONArray("elements")
                    if (elements == null || elements.length() == 0) return@withContext null

                    val sidecar = elements.getJSONObject(0).optJSONObject("sidecar")
                    val configStr = sidecar?.optString("config", "") ?: ""
                    if (configStr.isEmpty()) {
                        // Cache negative result so we don't refetch every launch.
                        cacheFile.writeText("")
                        return@withContext null
                    }

                    val deploymentId =
                        try {
                            JSONObject(configStr).optString("deploymentId", "").takeIf { it.isNotEmpty() }
                        } catch (e: Exception) {
                            // Malformed sidecar (transient hiccup or schema change). Don't persist a
                            // negative cache here — the next launch retries the parse rather than
                            // permanently treating this game as having no deployment id.
                            Timber.tag("Epic").w(e, "fetchDeploymentId: failed to parse sidecar.config for $appName")
                            return@withContext null
                        }

                    cacheFile.writeText(deploymentId ?: "")
                    Timber.tag("Epic").d("fetchDeploymentId($appName) = ${deploymentId ?: "<none>"}")
                    deploymentId
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Exception fetching deployment id for $appName")
                    null
                }
            }

        /**
         * Fetch `customAttributes.AdditionalCommandLine` from the Epic catalog API.
         * Mirrors legendary `Game.additional_command_line`. Cached per app-name with the same
         * TTL as deployment ids.
         */
        suspend fun fetchAdditionalCommandLine(
            context: Context,
            namespace: String,
            catalogItemId: String,
            appName: String,
            forceRefresh: Boolean = false,
        ): String? =
            withContext(Dispatchers.IO) {
                val cacheDir = File(context.filesDir, "epic/additional_cmdline").also { it.mkdirs() }
                val cacheFile = File(cacheDir, "${appName.sanitizeForFilename()}.txt")

                if (!forceRefresh && cacheFile.exists()) {
                    val cacheAgeMs = System.currentTimeMillis() - cacheFile.lastModified()
                    if (cacheAgeMs < EPIC_LAUNCH_METADATA_CACHE_TTL_MS) {
                        return@withContext cacheFile.readText().trim().takeIf { it.isNotEmpty() }
                    }
                }

                try {
                    val accessToken = EpicAuthManager.getStoredCredentials(context).getOrNull()?.accessToken
                    if (accessToken.isNullOrEmpty()) return@withContext null

                    val country = "US"
                    val url =
                        "${EpicConstants.EPIC_CATALOG_API_URL}/shared/namespace/$namespace/bulk/items" +
                            "?id=$catalogItemId&includeDLCDetails=false&includeMainGameDetails=false" +
                            "&country=$country"

                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .header("Authorization", "Bearer $accessToken")
                            .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                            .get()
                            .build()

                    val gameData =
                        httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                Timber.tag("Epic").w(
                                    "fetchAdditionalCommandLine: catalog API ${response.code} for $appName",
                                )
                                return@withContext null
                            }
                            val body = response.body?.string()
                            if (body.isNullOrEmpty()) return@withContext null
                            JSONObject(body).optJSONObject(catalogItemId)
                        } ?: return@withContext null

                    val additionalCommandLine =
                        parseCustomAttributes(gameData.optJSONObject("customAttributes")).additionalCommandline

                    cacheFile.writeText(additionalCommandLine ?: "")
                    additionalCommandLine
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Exception fetching additional command line for $appName")
                    null
                }
            }

        companion object {
            // 30 days. Deployment IDs and additional command line attributes change extremely
            // rarely; the Epic catalog API is rate-limited so a long TTL is safe.
            private const val EPIC_LAUNCH_METADATA_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        }
    }
