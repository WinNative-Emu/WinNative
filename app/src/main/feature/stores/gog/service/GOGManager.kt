package com.winlator.cmod.feature.stores.gog.service
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.feature.shortcuts.LibraryShortcutUtils
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.common.StoreArtworkCache
import com.winlator.cmod.feature.stores.common.StoreInstallPathSafety
import com.winlator.cmod.feature.stores.gog.data.GOGCloudSavesLocation
import com.winlator.cmod.feature.stores.gog.data.GOGCloudSavesLocationTemplate
import com.winlator.cmod.feature.stores.gog.data.GOGDlcInfo
import com.winlator.cmod.feature.stores.gog.data.GOGGame
import com.winlator.cmod.feature.stores.gog.data.LibraryItem
import com.winlator.cmod.feature.stores.gog.db.dao.GOGGameDao
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.data.LaunchInfo
import com.winlator.cmod.feature.stores.steam.data.PostSyncInfo
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.enums.AppType
import com.winlator.cmod.feature.stores.steam.enums.ControllerSupport
import com.winlator.cmod.feature.stores.steam.enums.GameSource
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.enums.OS
import com.winlator.cmod.feature.stores.steam.enums.PathType
import com.winlator.cmod.feature.stores.steam.enums.ReleaseState
import com.winlator.cmod.feature.stores.steam.enums.SyncResult
import com.winlator.cmod.feature.stores.steam.utils.ContainerUtils
import com.winlator.cmod.feature.stores.steam.utils.FileUtils
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import com.winlator.cmod.feature.stores.steam.utils.Net
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent
import com.winlator.cmod.runtime.wine.EnvVars
import com.winlator.cmod.runtime.wine.WineUtils
import com.winlator.cmod.shared.io.StorageUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.winlator.cmod.feature.stores.gog.api.GOGApiClient as GOGContentApiClient
import com.winlator.cmod.feature.stores.gog.api.GOGManifestParser as GOGContentManifestParser
import com.winlator.cmod.shared.io.FileUtils as WinlatorFileUtils

data class GOGManifestSizes(
    val installSize: Long = 0L,
    val downloadSize: Long = 0L,
)

data class GOGSaveSyncConfig(
    val clientId: String,
    val clientSecret: String,
    val locations: List<GOGCloudSavesLocationTemplate>,
)

// Coordinates GOG library, install, verification, launch, and sync operations.
@Singleton
class GOGManager
    @Inject
    constructor(
        private val gogGameDao: GOGGameDao,
        @ApplicationContext private val context: Context,
        private val gogContentApiClient: GOGContentApiClient,
        private val gogContentManifestParser: GOGContentManifestParser,
    ) {
        private val downloadSizeCache = ConcurrentHashMap<String, String>()
        private val REFRESH_BATCH_SIZE = 10
        private val GOG_PLACEHOLDER_PRODUCT_ID = "2147483047"

        private val remoteConfigCache = ConcurrentHashMap<String, List<GOGCloudSavesLocationTemplate>>()

        // Persisted cloud-save sync state.
        private val syncTimestamps = ConcurrentHashMap<String, String>()
        private val timestampFile = File(context.filesDir, "gog_sync_timestamps.json")

        private val activeSyncs = ConcurrentHashMap.newKeySet<String>()

        init {
            loadCloudSaveTimestampsFromDisk()
        }

        suspend fun getGameFromDbById(gameId: String): GOGGame? =
            withContext(Dispatchers.IO) {
                try {
                    gogGameDao.getById(gameId)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get GOG game by ID: $gameId")
                    null
                }
            }

        suspend fun insertGame(game: GOGGame) {
            withContext(Dispatchers.IO) {
                gogGameDao.insert(game)
            }
        }

        suspend fun updateGame(game: GOGGame) {
            withContext(Dispatchers.IO) {
                gogGameDao.update(game)
            }
        }

        suspend fun deleteAllNonInstalledGames() {
            withContext(Dispatchers.IO) {
                gogGameDao.deleteAllNonInstalledGames()
            }
        }

        suspend fun getAllGameIds(): Set<String> =
            withContext(Dispatchers.IO) {
                try {
                    gogGameDao.getAllGameIdsIncludingExcluded().toSet()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get all game IDs")
                    emptySet()
                }
            }

        suspend fun getOwnedDlcsForGame(
            gameId: String,
            language: String,
        ): List<GOGDlcInfo> =
            withContext(Dispatchers.IO) {
                try {
                    val selectedBuild =
                        selectPreferredBuild(gameId)
                            ?: return@withContext emptyList()
                    val manifestResult = gogContentApiClient.fetchManifest(selectedBuild.link)
                    if (manifestResult.isFailure) {
                        Timber.tag("GOG").w(manifestResult.exceptionOrNull(), "Failed to fetch manifest for DLC list: $gameId")
                        return@withContext emptyList()
                    }

                    val manifest = manifestResult.getOrThrow()
                    val ownedProductIds = getAllGameIds()
                    val installedDlcIds = getInstalledDlcIds(gameId)

                    coroutineScope {
                        val dlcProductIds =
                            gogContentManifestParser
                                .findDLCProducts(manifest)
                                .filter { it.productId in ownedProductIds }
                                .map { it.productId }
                                .toSet()
                        val manifestSizesByProduct =
                            calculateManifestSizesByProduct(
                                selectedBuild = selectedBuild,
                                manifest = manifest,
                                language = language,
                                productIds = dlcProductIds,
                                ownedProductIds = ownedProductIds,
                            )

                        gogContentManifestParser
                            .findDLCProducts(manifest)
                            .filter { it.productId in ownedProductIds }
                            .map { product ->
                                async {
                                    val sizeInfo = manifestSizesByProduct[product.productId] ?: GOGManifestSizes()
                                    val productDetailsSize =
                                        GOGApiClient
                                            .getGameById(context, product.productId, expanded = listOf("downloads"))
                                            .getOrNull()
                                            ?.downloadSize
                                            ?: 0L
                                    val resolvedSizeInfo = sizeInfo.withProductDetailsFallback(productDetailsSize)
                                    GOGDlcInfo(
                                        id = product.productId,
                                        title = product.name,
                                        downloadSize = maxOf(resolvedSizeInfo.downloadSize, productDetailsSize),
                                        installSize = maxOf(resolvedSizeInfo.installSize, productDetailsSize),
                                        isInstalled = product.productId in installedDlcIds,
                                    )
                                }
                            }.map { it.await() }
                            .sortedBy { it.title.lowercase(Locale.ROOT) }
                    }
                } catch (e: Exception) {
                    Timber.tag("GOG").w(e, "Failed to get owned DLC for game $gameId")
                    emptyList()
                }
            }

        suspend fun getInstallableSelectedManifestSizes(
            gameId: String,
            language: String,
            selectedDlcIds: Collection<Int> = emptyList(),
        ): GOGManifestSizes =
            withContext(Dispatchers.IO) {
                try {
                    val selectedBuild = selectPreferredBuild(gameId) ?: return@withContext GOGManifestSizes()
                    val manifest =
                        gogContentApiClient
                            .fetchManifest(selectedBuild.link)
                            .getOrNull()
                            ?: return@withContext fallbackGameManifestSizes(gameId)
                    val baseProductId = manifest.baseProductId.ifBlank { gameId }
                    val requestedProductIds =
                        buildSet {
                            add(baseProductId)
                            selectedDlcIds.mapTo(this) { it.toString() }
                        }
                    val selectedManifestSizes =
                        calculateSelectedManifestSizes(
                            selectedBuild = selectedBuild,
                            manifest = manifest,
                            language = language,
                            productIds = requestedProductIds,
                            ownedProductIds = getAllGameIds(),
                        )
                    val productDetailsSizes =
                        getProductDetailsDownloadSizes(
                            gameId = gameId,
                            productIds = requestedProductIds,
                        )
                    selectedManifestSizes
                        .withProductDetailsFallback(productDetailsSizes.values.sum())
                        .takeIf { it.installSize > 0L || it.downloadSize > 0L }
                        ?: fallbackGameManifestSizes(gameId)
                } catch (e: Exception) {
                    Timber.tag("GOG").w(e, "Failed to calculate selected manifest sizes for game $gameId")
                    fallbackGameManifestSizes(gameId)
                }
            }

        suspend fun getDlcOnlyManifestSizes(
            gameId: String,
            dlcId: Int,
            language: String,
        ): GOGManifestSizes =
            withContext(Dispatchers.IO) {
                try {
                    val selectedBuild = selectPreferredBuild(gameId) ?: return@withContext GOGManifestSizes()
                    val manifest =
                        gogContentApiClient
                            .fetchManifest(selectedBuild.link)
                            .getOrNull()
                            ?: return@withContext GOGManifestSizes()
                    val manifestSize =
                        calculateManifestSizesByProduct(
                            selectedBuild = selectedBuild,
                            manifest = manifest,
                            language = language,
                            productIds = setOf(dlcId.toString()),
                            ownedProductIds = getAllGameIds(),
                        )[dlcId.toString()] ?: GOGManifestSizes()
                    val productDetailsSize =
                        GOGApiClient
                            .getGameById(context, dlcId.toString(), expanded = listOf("downloads"))
                            .getOrNull()
                            ?.downloadSize
                            ?: 0L
                    GOGManifestSizes(
                        downloadSize = maxOf(manifestSize.withProductDetailsFallback(productDetailsSize).downloadSize, productDetailsSize),
                        installSize = maxOf(manifestSize.withProductDetailsFallback(productDetailsSize).installSize, productDetailsSize),
                    )
                } catch (e: Exception) {
                    Timber.tag("GOG").w(e, "Failed to calculate DLC manifest size for game $gameId DLC $dlcId")
                    GOGManifestSizes()
                }
            }

        private suspend fun getProductDetailsDownloadSizes(
            gameId: String,
            productIds: Set<String>,
        ): Map<String, Long> =
            coroutineScope {
                productIds
                    .map { productId ->
                        async {
                            val size =
                                if (productId == gameId) {
                                    getGameFromDbById(gameId)?.downloadSize ?: 0L
                                } else {
                                    GOGApiClient
                                        .getGameById(context, productId, expanded = listOf("downloads"))
                                        .getOrNull()
                                        ?.downloadSize
                                        ?: 0L
                                }
                            productId to size
                        }
                    }.map { it.await() }
                    .filter { it.second > 0L }
                    .toMap()
            }

        private suspend fun fallbackGameManifestSizes(gameId: String): GOGManifestSizes {
            val game = getGameFromDbById(gameId)
            return GOGManifestSizes(
                downloadSize = game?.downloadSize ?: 0L,
                installSize = game?.installSize ?: 0L,
            )
        }

        private suspend fun calculateManifestSizesByProduct(
            selectedBuild: com.winlator.cmod.feature.stores.gog.api.GOGBuild,
            manifest: com.winlator.cmod.feature.stores.gog.api.GOGManifestMeta,
            language: String,
            productIds: Set<String>,
            ownedProductIds: Set<String>,
        ): Map<String, GOGManifestSizes> {
            if (productIds.isEmpty()) return emptyMap()

            val (languageDepots, effectiveLanguage) = gogContentManifestParser.filterDepotsByLanguage(manifest, language)
            val candidateDepots =
                gogContentManifestParser
                    .filterDepotsByOwnership(languageDepots, ownedProductIds)

            if (candidateDepots.isEmpty()) return emptyMap()

            val sizes = productIds.associateWith { GOGManifestSizes() }.toMutableMap()
            if (selectedBuild.generation == 1 && manifest.productTimestamp != null) {
                for (depot in candidateDepots) {
                    val productId = depot.productId
                    if (productId !in productIds) continue
                    val depotJson =
                        gogContentApiClient
                            .fetchDepotManifestV1(
                                productId = depot.productId,
                                platform = selectedBuild.platform,
                                timestamp = manifest.productTimestamp,
                                manifestHash = depot.manifest,
                            ).getOrNull()
                            ?: continue
                    val size =
                        gogContentManifestParser
                            .parseV1DepotManifest(depotJson)
                            .filterNot { it.isSupport }
                            .sumOf { it.size.coerceAtLeast(0L) }
                    sizes[productId] =
                        sizes.getValue(productId).let {
                            GOGManifestSizes(
                                downloadSize = it.downloadSize + size,
                                installSize = it.installSize + size,
                            )
                        }
                }
                return sizes
            }

            val seenDownloadChunksByProduct = productIds.associateWith { mutableSetOf<String>() }
            for (depot in candidateDepots) {
                val depotManifest =
                    gogContentApiClient
                        .fetchDepotManifest(depot.manifest)
                        .getOrNull()
                        ?: continue
                depotManifest.files.forEach { file ->
                    if (file.isSupportFile()) return@forEach
                    val productId = effectiveProductId(file.productId, depot.productId)
                    if (productId !in productIds) return@forEach

                    val seenDownloadChunks = seenDownloadChunksByProduct.getValue(productId)
                    val downloadSize =
                        file.chunks.sumOf {
                            if (seenDownloadChunks.add(it.compressedMd5)) {
                                (it.compressedSize ?: it.size).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                        }
                    val installSize = file.chunks.sumOf { it.size }
                    sizes[productId] =
                        sizes.getValue(productId).let {
                            GOGManifestSizes(
                                downloadSize = it.downloadSize + downloadSize,
                                installSize = it.installSize + installSize,
                            )
                        }
                }
            }

            Timber.tag("GOG").d("Calculated manifest sizes for ${sizes.size} product(s) using $effectiveLanguage")
            return sizes
        }

        private suspend fun calculateSelectedManifestSizes(
            selectedBuild: com.winlator.cmod.feature.stores.gog.api.GOGBuild,
            manifest: com.winlator.cmod.feature.stores.gog.api.GOGManifestMeta,
            language: String,
            productIds: Set<String>,
            ownedProductIds: Set<String>,
        ): GOGManifestSizes {
            if (productIds.isEmpty()) return GOGManifestSizes()

            val (languageDepots, effectiveLanguage) = gogContentManifestParser.filterDepotsByLanguage(manifest, language)
            val candidateDepots =
                gogContentManifestParser
                    .filterDepotsByOwnership(languageDepots, ownedProductIds)
                    .filter { it.productId in productIds }

            if (candidateDepots.isEmpty()) return GOGManifestSizes()

            var downloadSize = 0L
            var installSize = 0L

            if (selectedBuild.generation == 1 && manifest.productTimestamp != null) {
                for (depot in candidateDepots) {
                    val depotJson =
                        gogContentApiClient
                            .fetchDepotManifestV1(
                                productId = depot.productId,
                                platform = selectedBuild.platform,
                                timestamp = manifest.productTimestamp,
                                manifestHash = depot.manifest,
                            ).getOrNull()
                            ?: continue
                    val size =
                        gogContentManifestParser
                            .parseV1DepotManifest(depotJson)
                            .filterNot { it.isSupport }
                            .sumOf { it.size.coerceAtLeast(0L) }
                    downloadSize += size
                    installSize += size
                }
                return GOGManifestSizes(downloadSize = downloadSize, installSize = installSize)
            }

            val seenDownloadChunks = mutableSetOf<String>()
            for (depot in candidateDepots) {
                val depotManifest =
                    gogContentApiClient
                        .fetchDepotManifest(depot.manifest)
                        .getOrNull()
                        ?: continue
                depotManifest.files.forEach { file ->
                    if (file.isSupportFile()) return@forEach
                    val productId = effectiveProductId(file.productId, depot.productId)
                    if (productId !in productIds) return@forEach

                    file.chunks.forEach { chunk ->
                        if (seenDownloadChunks.add(chunk.compressedMd5)) {
                            downloadSize += (chunk.compressedSize ?: chunk.size).coerceAtLeast(0L)
                        }
                        installSize += chunk.size.coerceAtLeast(0L)
                    }
                }
            }

            Timber.tag("GOG").d("Calculated selected manifest size using $effectiveLanguage")
            return GOGManifestSizes(downloadSize = downloadSize, installSize = installSize)
        }

        private fun effectiveProductId(
            fileProductId: String?,
            depotProductId: String,
        ): String =
            when (fileProductId) {
                null, "", GOG_PLACEHOLDER_PRODUCT_ID -> depotProductId
                else -> fileProductId
            }

        private fun GOGManifestSizes.withProductDetailsFallback(productDetailsDownloadSize: Long): GOGManifestSizes {
            val fallbackSize = productDetailsDownloadSize.coerceAtLeast(0L)
            val resolvedDownloadSize = downloadSize.takeIf { it > 0L } ?: fallbackSize
            val resolvedInstallSize = installSize.takeIf { it > 0L } ?: resolvedDownloadSize
            return GOGManifestSizes(
                downloadSize = resolvedDownloadSize,
                installSize = resolvedInstallSize,
            )
        }

        suspend fun getInstalledDlcIds(gameId: String): Set<String> =
            withContext(Dispatchers.IO) {
                val game = getGameFromDbById(gameId) ?: return@withContext emptySet()
                val installPath =
                    when {
                        game.installPath.isNotBlank() -> game.installPath
                        game.title.isNotBlank() -> getGameInstallPath(gameId, game.title)
                        else -> ""
                    }
                if (installPath.isBlank()) emptySet() else GOGManifestUtils.getInstalledDlcIds(File(installPath))
            }

        private suspend fun selectPreferredBuild(gameId: String): com.winlator.cmod.feature.stores.gog.api.GOGBuild? {
            val platform = "windows"
            val gen2Result = gogContentApiClient.getBuildsForGame(gameId, platform, generation = 2)
            if (gen2Result.isSuccess) {
                gogContentManifestParser
                    .selectBuild(gen2Result.getOrThrow().items, preferredGeneration = 2, platform = platform)
                    ?.let { return it }
            }

            val gen1Result = gogContentApiClient.getBuildsForGame(gameId, platform, generation = 1)
            if (gen1Result.isSuccess) {
                return gogContentManifestParser.selectBuild(
                    gen1Result.getOrThrow().items,
                    preferredGeneration = 1,
                    platform = platform,
                )
            }

            return null
        }

        suspend fun startBackgroundSync(context: Context): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    if (!GOGAuthManager.hasStoredCredentials(context)) {
                        Timber.w("Cannot start background sync: no stored credentials")
                        return@withContext Result.failure(Exception("No stored credentials found"))
                    }

                    Timber.tag("GOG").i("Starting GOG library background sync...")

                    val result = refreshLibrary(context)

                    if (result.isSuccess) {
                        val count = result.getOrNull() ?: 0
                        Timber.tag("GOG").i("Background sync completed: $count games synced")
                        return@withContext Result.success(Unit)
                    } else {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "Background sync failed: ${error?.message}")
                        return@withContext Result.failure(error ?: Exception("Background sync failed"))
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to sync GOG library in background")
                    Result.failure(e)
                }
            }

        /**
         * Refresh the entire library (called manually by user)
         * Fetches all games from GOG API and updates the database
         * ! Note: If someone wants to improve this logic, I'd recommend seeing
         * ! if coroutine parallel downloading would work without being rate-limited
         */
        suspend fun refreshLibrary(context: Context): Result<Int> =
            withContext(Dispatchers.IO) {
                try {
                    if (!GOGAuthManager.hasStoredCredentials(context)) {
                        Timber.w("Cannot refresh library: not authenticated with GOG")
                        return@withContext Result.failure(Exception("Not authenticated with GOG"))
                    }

                    Timber.tag("GOG").i("Refreshing GOG library from GOG API...")

                    // Fetch games from GOG via GOGDL Python backend

                    var gameIdList = GOGApiClient.getGameIds(context)

                    if (!gameIdList.isSuccess) {
                        val error = gameIdList.exceptionOrNull()
                        Timber.e(error, "Failed to fetch GOG game IDs: ${error?.message}")
                        return@withContext Result.failure(error ?: Exception("Failed to fetch GOG game IDs"))
                    }

                    val gameIds = gameIdList.getOrNull() ?: emptyList()
                    Timber.tag("GOG").i("Successfully fetched ${gameIds.size} game IDs from GOG")

                    if (gameIds.isEmpty()) {
                        Timber.w("No games found in GOG library")
                        return@withContext Result.success(0)
                    }

                    val ignoredGameId = "1801418160" // Hidden ID for GOG Galaxy that we should ignore.

                    // Get existing game IDs from database to avoid re-fetching, except when
                    // older rows are missing the dedicated hero artwork added in DB v7.
                    val existingGamesMissingHero =
                        gogGameDao
                            .getAllAsList()
                            .filter { it.heroImageUrl.isBlank() }
                            .map { it.id }
                            .toSet()
                    val existingGameIds = gogGameDao.getAllGameIdsIncludingExcluded().toMutableSet()
                    existingGameIds.add(ignoredGameId)

                    Timber.tag("GOG").d("Found ${existingGameIds.size} games already in database")

                    val newGameIds =
                        (
                            gameIds.filter { it !in existingGameIds } +
                                gameIds.filter { it in existingGamesMissingHero }
                        ).distinct()
                    Timber.tag("GOG").d("${newGameIds.size} games need details fetched")

                    if (newGameIds.isEmpty()) {
                        val detectedCount = detectAndUpdateExistingInstallations()
                        Timber.tag("GOG").d("No new games to fetch, library is up to date. Detected $detectedCount existing installations")
                        return@withContext Result.success(detectedCount)
                    }

                    var totalProcessed = 0

                    Timber.tag("GOG").d("Getting Game Details for ${newGameIds.size} new GOG Games...")

                    val games = mutableListOf<GOGGame>()

                    // Use direct HTTP calls via GOGApiClient
                    for ((index, id) in newGameIds.withIndex()) {
                        try {
                            // Fetch game details using direct HTTP call
                            val result = GOGApiClient.getGameById(context, id)

                            if (result.isSuccess) {
                                val gameDetails = result.getOrNull()
                                if (gameDetails != null) {
                                    Timber.tag("GOG").d("Got Game Details for ID: $id")
                                    val game = parseGameObject(gameDetails)
                                    if (game != null) {
                                        games.add(game)
                                        Timber.tag("GOG").d("Refreshed Game: ${game.title}")
                                        totalProcessed++
                                    }
                                }
                            } else {
                                Timber.w("GOG game ID $id not found in library after refresh")
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to parse game details for ID: $id")
                        }

                        if ((index + 1) % REFRESH_BATCH_SIZE == 0 || index == newGameIds.size - 1) {
                            if (games.isNotEmpty()) {
                                gogGameDao.upsertPreservingInstallStatus(games)
                                Timber.tag("GOG").d("Batch inserted ${games.size} games (processed ${index + 1}/${newGameIds.size})")
                                games.clear()
                            }
                        }
                    }
                    val detectedCount = detectAndUpdateExistingInstallations()
                    if (detectedCount > 0) {
                        Timber.d("Detected and updated $detectedCount existing installations")
                    }
                    Timber.tag("GOG").i("Successfully refreshed GOG library with $totalProcessed games")
                    return@withContext Result.success(totalProcessed)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to refresh GOG library")
                    return@withContext Result.failure(e)
                }
            }

        private fun parseGameObject(parsedGame: ParsedGogGame): GOGGame? {
            val title = parsedGame.title
            val id = parsedGame.id
            val downloadSize = parsedGame.downloadSize
            val isSecret = parsedGame.isSecret
            val isDlc = parsedGame.isDlc
            // Added Exclude so that we still store a record in the DB but we don't expose it.
            // This reduces the amount of fetching we do from the APIs and we also reduce chances of Amazon Prime duplicates etc.
            // Had to put in an extra case for some games not using isSecret but still are amazon prime duplicates...
            val exclude =
                title == "Unknown Game" || title.startsWith("product_title_") || title == "Unknown" || downloadSize == 0L || isSecret ||
                    title.endsWith("Amazon Prime") || isDlc

            return GOGGame(
                id = id,
                title = title,
                exclude = exclude,
                slug = parsedGame.slug,
                imageUrl = parsedGame.imageUrl,
                heroImageUrl = parsedGame.heroImageUrl,
                iconUrl = parsedGame.iconUrl,
                description = parsedGame.description,
                releaseDate = parsedGame.releaseDate,
                developer = parsedGame.developer,
                publisher = parsedGame.publisher,
                genres = parsedGame.genres,
                languages = parsedGame.languages,
                downloadSize = parsedGame.downloadSize,
                installSize = 0L,
                isInstalled = false,
                installPath = "",
                lastPlayed = 0L,
                playTime = 0L,
            )
        }

        /**
         * Scan the GOG games directories for existing installations
         * and update the database with installation info
         *
         * @return Number of installations detected and updated
         */
        private suspend fun detectAndUpdateExistingInstallations(): Int =
            withContext(Dispatchers.IO) {
                var detectedCount = 0

                try {
                    val pathsToCheck =
                        listOf(
                            GOGConstants.internalGOGGamesPath,
                            GOGConstants.externalGOGGamesPath,
                        )

                    for (basePath in pathsToCheck) {
                        val baseDir = File(basePath)
                        if (!baseDir.exists() || !baseDir.isDirectory) {
                            Timber.d("Skipping non-existent path: $basePath")
                            continue
                        }

                        Timber.d("Scanning for installations in: $basePath")
                        val installDirs = baseDir.listFiles { file -> file.isDirectory } ?: emptyArray()

                        for (installDir in installDirs) {
                            try {
                                val detectedGame = detectGameFromDirectory(installDir)
                                if (detectedGame != null) {
                                    val existingGame = getGameFromDbById(detectedGame.id)
                                    if (existingGame != null && !existingGame.isInstalled) {
                                        val updatedGame =
                                            existingGame.copy(
                                                isInstalled = true,
                                                installPath = detectedGame.installPath,
                                                installSize = detectedGame.installSize,
                                            )
                                        updateGame(updatedGame)
                                        detectedCount++
                                        Timber.i("Detected existing installation: ${existingGame.title} at ${installDir.absolutePath}")
                                    } else if (existingGame != null) {
                                        Timber.d("Game ${existingGame.title} already marked as installed")
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Error detecting game in ${installDir.name}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error during installation detection")
                }

                detectedCount
            }

        /**
         * Try to detect which game is installed in the given directory
         *
         * @param installDir The directory to check
         * @return GOGGame with installation info, or null if no game detected
         */
        private suspend fun detectGameFromDirectory(installDir: File): GOGGame? {
            if (!installDir.exists() || !installDir.isDirectory) {
                return null
            }

            val dirName = installDir.name
            Timber.d("Checking directory: $dirName")

            // Look for .info files which contain game metadata
            val infoFiles =
                installDir.listFiles { file ->
                    file.isFile && file.extension == "info"
                } ?: emptyArray()

            if (infoFiles.isNotEmpty()) {
                // Try to parse game ID from .info file
                val infoFile = infoFiles.first()
                try {
                    val infoContent = infoFile.readText()
                    val infoJson = JSONObject(infoContent)
                    val gameId = infoJson.optString("gameId", "")
                    if (gameId.isNotEmpty()) {
                        val game = getGameFromDbById(gameId)
                        if (game != null) {
                            val installSize = FileUtils.calculateDirectorySize(installDir)
                            return game.copy(
                                isInstalled = true,
                                installPath = installDir.absolutePath,
                                installSize = installSize,
                            )
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Error parsing .info file: ${infoFile.name}")
                }
            }

            // Fallback: Try to match by directory name with game titles in database
            val allGames = gogGameDao.getAllAsList()
            for (game in allGames) {
                // Sanitize game title to match directory naming convention
                val sanitizedTitle = game.title.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()

                if (dirName.equals(sanitizedTitle, ignoreCase = true)) {
                    // Verify it's actually a game directory (has executables or subdirectories)
                    val hasContent =
                        installDir.listFiles()?.any {
                            it.isDirectory || it.extension in listOf("exe", "dll", "bat")
                        } == true

                    if (hasContent) {
                        val installSize = FileUtils.calculateDirectorySize(installDir)
                        Timber.d("Matched directory '$dirName' to game '${game.title}'")
                        return game.copy(
                            isInstalled = true,
                            installPath = installDir.absolutePath,
                            installSize = installSize,
                        )
                    }
                }
            }

            return null
        }

        suspend fun refreshSingleGame(
            gameId: String,
            context: Context,
        ): Result<GOGGame?> {
            return try {
                Timber.d("Fetching single game data for gameId: $gameId via direct HTTP...")

                if (!GOGAuthManager.hasStoredCredentials(context)) {
                    return Result.failure(Exception("Not authenticated"))
                }

                val result = GOGApiClient.getGameById(context, gameId)

                if (result.isFailure) {
                    return Result.failure(result.exceptionOrNull() ?: Exception("Failed to fetch game data"))
                }

                val gameDetails = result.getOrNull()
                if (gameDetails == null) {
                    Timber.w("Game $gameId not found in GOG library")
                    return Result.success(null)
                }

                val game = parseGameObject(gameDetails)
                if (game == null) {
                    Timber.tag("GOG").w("Skipping Invalid GOG App with id: $gameId")
                    return Result.success(null)
                }
                insertGame(game)
                return Result.success(game)
            } catch (e: Exception) {
                Timber.e(e, "Error fetching single game data for $gameId")
                Result.failure(e)
            }
        }

        suspend fun deleteGame(
            context: Context,
            libraryItem: LibraryItem,
        ): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    val gameId = libraryItem.gameId.toString()
                    val game = getGameFromDbById(gameId)
                    val installPath =
                        when {
                            game?.installPath?.isNotEmpty() == true -> game.installPath
                            else -> getGameInstallPath(gameId, libraryItem.name)
                        }
                    val installDir = File(installPath)
                    val wasInstalled = game?.isInstalled == true || MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    val deleteCheck =
                        StoreInstallPathSafety.checkInstallDirDelete(
                            context,
                            installPath,
                            protectedRoots = listOf(GOGConstants.defaultGOGGamesPath),
                            owner = InstallStore.GOG,
                        )
                    if (!deleteCheck.allowed) {
                        Timber.e("Refusing to delete GOG install path '$installPath': ${deleteCheck.reason}")
                        return@withContext Result.failure(Exception("Refusing to delete unsafe install path: $installPath"))
                    }

                    // Delete the manifest file
                    val manifestPath = File(context.filesDir, "manifests/$gameId")
                    if (manifestPath.exists()) {
                        manifestPath.delete()
                        Timber.i("Deleted manifest file for game $gameId")
                    }

                    // Remove any generated per-game shortcuts for this GOG title across containers.
                    val deletedShortcuts =
                        LibraryShortcutUtils.deleteGogShortcuts(
                            context = context,
                            gogId = gameId,
                            appId = libraryItem.appId.substringAfterLast("_", ""),
                        )
                    Timber.i("Deleted $deletedShortcuts GOG shortcuts for game $gameId")

                    if (installDir.exists()) {
                        val success = installDir.deleteRecursively()
                        if (success) {
                            Timber.i("Successfully deleted game directory: $installPath")
                        } else {
                            Timber.w("Failed to delete some game files")
                            return@withContext Result.failure(Exception("Failed to fully delete at $installPath"))
                        }
                    } else {
                        Timber.w("GOG game directory doesn't exist: $installPath")
                    }

                    MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                    if (wasInstalled) {
                        if (game != null) {
                            val updatedGame = game.copy(isInstalled = false, installPath = "")
                            gogGameDao.update(updatedGame)
                            Timber.d("Updated database: game marked as not installed")
                        }
                    }
                    StoreArtworkCache.deleteGame(context, "gog", gameId)

                    com.winlator.cmod.app.PluviaApp.events.emitJava(
                        com.winlator.cmod.feature.stores.steam.events.AndroidEvent
                            .LibraryInstallStatusChanged(libraryItem.gameId),
                    )

                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to delete GOG game ${libraryItem.gameId}")
                    Result.failure(e)
                }
            }
        }

        fun isGameInstalled(
            context: Context,
            libraryItem: LibraryItem,
        ): Boolean {
            try {
                val gameId = libraryItem.gameId.toString()
                val game = runBlocking { getGameFromDbById(gameId) }
                val appDirPath =
                    game?.installPath?.takeIf { it.isNotBlank() }
                        ?: getGameInstallPath(gameId, libraryItem.name)

                // Trust DB.isInstalled when set, only verify the install directory still exists.
                // Avoids flipping isInstalled=false during verify/update when DOWNLOAD_IN_PROGRESS
                // is temporarily set on an already-installed game.
                if (game != null && game.isInstalled && game.installPath.isNotBlank()) {
                    if (InstallOwnership.isForeign(game.installPath, InstallStore.GOG)) {
                        runBlocking { gogGameDao.update(game.copy(isInstalled = false, installPath = "")) }
                        return false
                    }
                    return File(game.installPath).isDirectory
                }

                // Use marker-based approach
                val isDownloadComplete = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                val isDownloadInProgress = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                val isInstalled =
                    isDownloadComplete &&
                        !isDownloadInProgress &&
                        !InstallOwnership.isForeign(appDirPath, InstallStore.GOG)

                if (game != null && (isInstalled != game.isInstalled || (isInstalled && game.installPath != appDirPath))) {
                    val installPath = if (isInstalled) appDirPath else ""
                    val updatedGame = game.copy(isInstalled = isInstalled, installPath = installPath)
                    runBlocking { gogGameDao.update(updatedGame) }
                }

                return isInstalled
            } catch (e: Exception) {
                Timber.e(e, "Error checking if GOG game is installed")
                return false
            }
        }

        fun verifyInstallation(gameId: String): Pair<Boolean, String?> {
            val game = runBlocking { getGameFromDbById(gameId) }
            val installPath = game?.installPath

            if (game == null || installPath == null || !game.isInstalled) {
                return Pair(false, "Game not marked as installed in database")
            }

            val installDir = File(installPath)
            if (!installDir.exists()) {
                return Pair(false, "Install directory not found: $installPath")
            }

            if (!installDir.isDirectory) {
                return Pair(false, "Install path is not a directory")
            }

            if (!MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)) {
                return Pair(false, "Download is not marked complete")
            }

            if (MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)) {
                return Pair(false, "Download is still in progress")
            }

            val contents = installDir.listFiles()
            if (contents == null || contents.isEmpty()) {
                return Pair(false, "Install directory is empty")
            }

            Timber.i("Installation verified for game $gameId at $installPath")
            return Pair(true, null)
        }

        suspend fun getInstalledExe(libraryItem: LibraryItem): String =
            withContext(Dispatchers.IO) {
                val gameId = libraryItem.gameId.toString()
                try {
                    val game = getGameFromDbById(gameId) ?: return@withContext ""
                    val installPath = getGameInstallPath(game.id, game.title)

                    // Try V2 structure first (game_$gameId subdirectory)
                    val v2GameDir = File(installPath, "game_$gameId")
                    if (v2GameDir.exists()) {
                        return@withContext getGameExecutable(installPath, v2GameDir)
                    }

                    // Try V1 structure: goggame-*.info and exe can be in install root or in a subdir
                    val installDirFile = File(installPath)
                    val exe = getGameExecutable(installPath, installDirFile)
                    if (exe.isNotEmpty()) return@withContext exe
                    val subdirs =
                        installDirFile.listFiles()?.filter {
                            it.isDirectory && it.name != "saves" && it.name != "_CommonRedist"
                        } ?: emptyList()

                    for (subdir in subdirs) {
                        val subdirExe = getGameExecutable(installPath, subdir)
                        if (subdirExe.isNotEmpty()) return@withContext subdirExe
                    }

                    ""
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get executable for GOG game $gameId")
                    ""
                }
            }

        /**
         * Resolves the effective launch executable for a GOG game (container config or auto-detected).
         * Returns empty string if no executable can be found.
         */
        suspend fun getLaunchExecutable(
            appId: String,
            container: Container,
        ): String =
            withContext(Dispatchers.IO) {
                container.executablePath.ifEmpty {
                    getInstalledExe(LibraryItem(appId = appId, name = "", gameSource = GameSource.GOG))
                }
            }

        private fun getGameExecutable(
            installPath: String,
            gameDir: File,
        ): String {
            val result = getMainExecutableFromGOGInfo(gameDir, installPath)
            if (result.isSuccess) {
                val exe = result.getOrNull() ?: ""
                Timber.d("Found GOG game executable from info file: $exe")
                return exe
            }
            Timber.e(result.exceptionOrNull(), "Failed to find executable from GOG info file in: ${gameDir.absolutePath}")
            return ""
        }

        private fun findGOGInfoFile(
            directory: File,
            gameId: String? = null,
            maxDepth: Int = 3,
            currentDepth: Int = 0,
        ): File? {
            if (!directory.exists() || !directory.isDirectory) {
                return null
            }

            val infoFile =
                directory.listFiles()?.find {
                    it.isFile &&
                        if (gameId != null) {
                            it.name == "goggame-$gameId.info"
                        } else {
                            it.name.startsWith("goggame-") && it.name.endsWith(".info")
                        }
                }

            if (infoFile != null) {
                return infoFile
            }

            // If max depth reached, stop searching
            if (currentDepth >= maxDepth) {
                return null
            }

            // Search subdirectories recursively
            val subdirs = directory.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (subdir in subdirs) {
                val found = findGOGInfoFile(subdir, gameId, maxDepth, currentDepth + 1)
                if (found != null) {
                    return found
                }
            }

            return null
        }

        private fun getMainExecutableFromGOGInfo(
            gameDir: File,
            installPath: String,
        ): Result<String> {
            return try {
                val infoFile =
                    findGOGInfoFile(gameDir)
                        ?: return Result.failure(Exception("GOG info file not found in ${gameDir.absolutePath}"))

                val content = infoFile.readText()
                val jsonObject = JSONObject(content)

                if (!jsonObject.has("playTasks")) {
                    return Result.failure(Exception("playTasks array not found in ${infoFile.name}"))
                }

                val playTasks = jsonObject.getJSONArray("playTasks")
                val installDir = File(installPath)

                for (i in 0 until playTasks.length()) {
                    val task = playTasks.getJSONObject(i)
                    if (task.has("isPrimary") && task.getBoolean("isPrimary")) {
                        val executablePath = task.getString("path")
                        Timber.e("executable_path: $executablePath, gameDir: ${gameDir.absolutePath}")
                        val exeFile = FileUtils.findFileCaseInsensitive(gameDir, executablePath)
                        if (exeFile != null) {
                            val relativePath = exeFile.relativeTo(installDir).path
                            return Result.success(relativePath)
                        }
                        return Result.failure(Exception("Primary executable '$executablePath' not found in ${gameDir.absolutePath}"))
                    }
                }

                Result.failure(Exception("No primary executable found in playTasks"))
            } catch (e: Exception) {
                Result.failure(Exception("Error parsing GOG info file in ${gameDir.absolutePath}: ${e.message}", e))
            }
        }

        fun getGogWineStartCommand(
            libraryItem: LibraryItem,
            container: Container,
            bootToContainer: Boolean,
            appLaunchInfo: LaunchInfo?,
            envVars: EnvVars,
            guestProgramLauncherComponent: GuestProgramLauncherComponent,
            gameId: Int,
        ): String {
            // Verify installation
            val (isValid, errorMessage) = verifyInstallation(gameId.toString())
            if (!isValid) {
                Timber.e("Installation verification failed: $errorMessage")
                return "\"explorer.exe\""
            }

            val game = runBlocking { getGameFromDbById(gameId.toString()) }
            if (game == null) {
                Timber.e("Game not found for ID: $gameId")
                return "\"explorer.exe\""
            }

            val gameInstallPath = getGameInstallPath(gameId.toString(), game.title)
            val gameDir = File(gameInstallPath)

            if (!gameDir.exists()) {
                Timber.e("Game directory does not exist: $gameInstallPath")
                return "\"explorer.exe\""
            }

            // Use container's configured executable path if available, otherwise auto-detect
            val configuredExecutablePath = container.executablePath
            val executablePath =
                if (configuredExecutablePath.isNotEmpty()) {
                    Timber.d("Using configured executable path from container: $configuredExecutablePath")
                    configuredExecutablePath
                } else {
                    val detectedPath = runBlocking { getInstalledExe(libraryItem) }
                    Timber.d("Auto-detected executable path: $detectedPath")
                    if (detectedPath.isNotEmpty()) {
                        container.executablePath = detectedPath
                        container.saveData()
                    }
                    detectedPath
                }

            if (executablePath.isEmpty()) {
                Timber.w("No executable found, opening file manager")
                return "\"explorer.exe\""
            }

            val gameInstallDir = File(gameInstallPath)
            val execFile = File(gameInstallPath, executablePath)
            val windowsPath =
                WineUtils.getDriveCGameWindowsPath(
                    container,
                    "GOG",
                    gameInstallPath,
                    execFile.absolutePath,
                ) ?: WineUtils.getWindowsPath(container, execFile.absolutePath)

            val execWorkingDir = execFile.parentFile
            if (execWorkingDir != null) {
                guestProgramLauncherComponent.setWorkingDir(execWorkingDir)
                val mappedWorkingDir =
                    WineUtils.getDriveCGameWindowsPath(
                        container,
                        "GOG",
                        gameInstallPath,
                        execWorkingDir.absolutePath,
                    ) ?: WineUtils.getWindowsPath(container, execWorkingDir.absolutePath)
                envVars.put("WINEPATH", mappedWorkingDir)
            } else {
                guestProgramLauncherComponent.setWorkingDir(gameDir)
            }

            Timber.d("GOG Wine command: \"$windowsPath\"")
            return "\"$windowsPath\""
        }

        /**
         * Creates the GOG scriptinterpreter rootdir symlink when present. /DIR and /supportDir use
         * A:\_CommonRedist\ISI\rootdir; rootdir must be a symlink to the actual game install root so
         * it resolves correctly when the drive is mounted.
         */
        private fun ensureScriptInterpreterRootDirSymlink(gameInstallDir: File) {
            val commonRedistDir = File(gameInstallDir, "_CommonRedist")
            val isiDir = File(commonRedistDir, "ISI")
            if (isiDir.isDirectory) {
                val rootDirLink = File(isiDir, "rootdir")
                if (!rootDirLink.exists() || !WinlatorFileUtils.isSymlink(rootDirLink)) {
                    try {
                        WinlatorFileUtils.symlink(gameInstallDir, rootDirLink)
                        Timber.tag("GOG").d(
                            "Created scriptinterpreter rootdir symlink: ${rootDirLink.absolutePath} -> ${gameInstallDir.absolutePath}",
                        )
                    } catch (e: Exception) {
                        Timber.tag("GOG").e(
                            e,
                            "Failed to create scriptinterpreter rootdir symlink: ${rootDirLink.absolutePath} -> ${gameInstallDir.absolutePath}",
                        )
                    }
                }
            }
        }

        /**
         * Returns command parts to run GOG scriptinterpreter.exe for each product (when required by
         * _gog_manifest.json). Used by LaunchSteps to prepend to the game launch command so it runs
         * in the same Wine session. Returns empty list if not needed or not available.
         */
        fun getScriptInterpreterPartsForLaunch(appId: String): List<String> {
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            val game = runBlocking { getGameFromDbById(gameId.toString()) } ?: return emptyList()
            val computedPath = getGameInstallPath(gameId.toString(), game.title)
            val gameInstallPath =
                when {
                    game.installPath.isNotEmpty() && File(game.installPath).exists() -> game.installPath
                    else -> computedPath
                }
            val gameInstallDir = File(gameInstallPath)
            if (!GOGManifestUtils.needsScriptInterpreter(gameInstallDir)) return emptyList()
            val root = GOGManifestUtils.readLocalManifest(gameInstallDir) ?: return emptyList()
            val isiRelativePath = "_CommonRedist/ISI/scriptinterpreter.exe"
            if (!File(gameInstallPath, isiRelativePath).exists()) return emptyList()

            ensureScriptInterpreterRootDirSymlink(gameInstallDir)

            val isiRelativePathWin = isiRelativePath.replace('/', '\\')
            val gameDriveLetter = "F"
            val buildId = root.optString("buildId", "")
            val versionName = root.optString("versionName", "")
            val langCode = root.optString("language", "en").let { if (it.length <= 2) "$it-US" else it }
            val language = "English"
            val productsArray = root.optJSONArray("products") ?: return emptyList()

            val parts = mutableListOf<String>()
            for (i in 0 until productsArray.length()) {
                val product = productsArray.getJSONObject(i)
                val productId = product.optString("productId", "")
                if (productId.isEmpty()) continue

                val exePathWin = "$gameDriveLetter:\\$isiRelativePathWin"
                // scriptinterpreter needs a drive-qualified folder; rootdir resolves to the game root.
                val dirAndSupport = "$gameDriveLetter:\\_CommonRedist\\ISI\\rootdir"
                val args =
                    listOf(
                        "/VERYSILENT",
                        "/DIR=$dirAndSupport",
                        "/Language=$language",
                        "/LANG=$language",
                        "/ProductId=$productId",
                        "/galaxyclient",
                        "/buildId=$buildId",
                        "/versionName=$versionName",
                        "/lang-code=$langCode",
                        "/supportDir=$dirAndSupport",
                        "/nodesktopshorctut",
                        "/nodesktopshortcut",
                    ).joinToString(" ")

                parts.add("$exePathWin $args")
            }

            return parts
        }


        /**
         * Read GOG game info file and extract clientId
         * @param appId Game ID
         * @param installPath Optional install path, if null will try to get from game database
         * @return JSONObject with game info, or null if not found
         */
        suspend fun readInfoFile(
            appId: String,
            installPath: String?,
        ): JSONObject? =
            withContext(Dispatchers.IO) {
                try {
                    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                    var path = installPath

                    // If no install path provided, try to get from database
                    if (path == null) {
                        val game = getGameFromDbById(gameId.toString())
                        path = game?.installPath
                    }

                    if (path == null || path.isEmpty()) {
                        Timber.w("No install path found for game $gameId")
                        return@withContext null
                    }

                    val installDir = File(path)
                    if (!installDir.exists()) {
                        Timber.w("Install directory does not exist: $path")
                        return@withContext null
                    }

                    // Look for goggame-{gameId}.info file - check root first, then common subdirectories
                    val infoFile = findGOGInfoFile(installDir, gameId.toString())

                    if (infoFile == null || !infoFile.exists()) {
                        Timber.w("Info file not found for game $gameId in ${installDir.absolutePath}")
                        return@withContext null
                    }

                    val infoContent = infoFile.readText()
                    val infoJson = JSONObject(infoContent)
                    Timber.d("Successfully read info file for game $gameId")
                    return@withContext infoJson
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read info file for appId $appId")
                    return@withContext null
                }
            }

        /**
         * Fetch save locations from GOG Remote Config API
         * @param context Android context
         * @param appId Game app ID
         * @param installPath Game install path
         * @return Cloud save configuration, or null if the game cannot be mapped to a Galaxy client
         */
        suspend fun getSaveSyncLocation(
            context: Context,
            appId: String,
            installPath: String,
        ): GOGSaveSyncConfig? =
            withContext(Dispatchers.IO) {
                try {
                    Timber.tag("GOG").d("[Cloud Saves] Getting save sync location for $appId")
                    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                    val infoJson = readInfoFile(appId, installPath)

                    if (infoJson == null) {
                        Timber.tag("GOG").w("[Cloud Saves] Info file not found for game $gameId; trying build metadata")
                    }

                    val cloudCredentials = GOGApiClient.getCloudCredentials(context, gameId.toString(), installPath)
                    val clientId = infoJson?.optString("clientId", "")?.ifEmpty { cloudCredentials?.clientId.orEmpty() }
                        ?: cloudCredentials?.clientId.orEmpty()
                    if (clientId.isEmpty()) {
                        Timber.tag("GOG").w("[Cloud Saves] No clientId found for game $gameId")
                        return@withContext null
                    }
                    Timber.tag("GOG").d("[Cloud Saves] Client ID: $clientId")

                    val clientSecret = cloudCredentials?.clientSecret.orEmpty()
                    if (clientSecret.isEmpty()) {
                        Timber.tag("GOG").w("[Cloud Saves] No clientSecret available for game $gameId")
                    } else {
                        Timber.tag("GOG").d("[Cloud Saves] Got client secret for game")
                    }

                    // Check cache first
                    remoteConfigCache[clientId]?.let { cachedLocations ->
                        Timber
                            .tag(
                                "GOG",
                            ).d("[Cloud Saves] Using cached save locations for clientId $clientId (${cachedLocations.size} locations)")
                        // Cache only contains locations, we still need to fetch clientSecret fresh
                        return@withContext GOGSaveSyncConfig(clientId, clientSecret, cachedLocations)
                    }

                    // Android runs games through Wine, so always use Windows platform
                    val syncPlatform = "Windows"

                    // Fetch remote config
                    val url = "https://remote-config.gog.com/components/galaxy_client/clients/$clientId?component_version=2.0.45"
                    Timber.tag("GOG").d("[Cloud Saves] Fetching remote config from: $url")

                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .build()

                    val response = Net.http.newCall(request).execute()
                    response.use {
                        if (!response.isSuccessful) {
                            Timber.tag("GOG").w("[Cloud Saves] Failed to fetch remote config: HTTP ${response.code}")
                            return@withContext GOGSaveSyncConfig(clientId, clientSecret, emptyList())
                        }
                        Timber.tag("GOG").d("[Cloud Saves] Successfully fetched remote config")

                        val responseBody = response.body?.string()
                        if (responseBody == null) {
                            Timber.tag("GOG").w("[Cloud Saves] Empty response body from remote config")
                            return@withContext GOGSaveSyncConfig(clientId, clientSecret, emptyList())
                        }
                        val configJson = JSONObject(responseBody)

                        val content = configJson.optJSONObject("content")
                        if (content == null) {
                            Timber.tag("GOG").w("[Cloud Saves] No 'content' field in remote config response")
                            return@withContext GOGSaveSyncConfig(clientId, clientSecret, emptyList())
                        }

                        val platformContent = content.optJSONObject(syncPlatform)
                        if (platformContent == null) {
                            Timber.tag("GOG").d("[Cloud Saves] No cloud storage config for platform $syncPlatform")
                            return@withContext GOGSaveSyncConfig(clientId, clientSecret, emptyList())
                        }

                        val cloudStorage = platformContent.optJSONObject("cloudStorage")
                        if (cloudStorage == null) {
                            Timber.tag("GOG").d("[Cloud Saves] No cloudStorage field for platform $syncPlatform")
                            return@withContext GOGSaveSyncConfig(clientId, clientSecret, emptyList())
                        }

                        val enabled = cloudStorage.optBoolean("enabled", false)
                        if (!enabled) {
                            Timber.tag("GOG").d("[Cloud Saves] Cloud saves not enabled for game $gameId")
                            return@withContext GOGSaveSyncConfig(clientId, clientSecret, emptyList())
                        }
                        Timber.tag("GOG").d("[Cloud Saves] Cloud saves are enabled for game $gameId")

                        val locationsArray = cloudStorage.optJSONArray("locations")
                        if (locationsArray == null || locationsArray.length() == 0) {
                            Timber.tag("GOG").d("[Cloud Saves] No save locations configured for game $gameId")
                            return@withContext GOGSaveSyncConfig(clientId, clientSecret, emptyList())
                        }
                        Timber.tag("GOG").d("[Cloud Saves] Found ${locationsArray.length()} location(s) in config")

                        val locations = mutableListOf<GOGCloudSavesLocationTemplate>()
                        for (i in 0 until locationsArray.length()) {
                            val locationObj = locationsArray.getJSONObject(i)
                            val name = locationObj.optString("name", "__default")
                            val location = locationObj.optString("location", "")
                            if (location.isNotEmpty()) {
                                Timber.tag("GOG").d("[Cloud Saves] Location ${i + 1}: '$name' = '$location'")
                                locations.add(GOGCloudSavesLocationTemplate(name, location))
                            } else {
                                Timber.tag("GOG").w("[Cloud Saves] Skipping location ${i + 1} with empty path")
                            }
                        }

                        // Cache the result
                        if (locations.isNotEmpty()) {
                            remoteConfigCache[clientId] = locations
                            Timber.tag("GOG").d("[Cloud Saves] Cached ${locations.size} save locations for clientId $clientId")
                        }

                        Timber.tag("GOG").i("[Cloud Saves] Found ${locations.size} save location(s) for game $gameId")
                        return@withContext GOGSaveSyncConfig(clientId, clientSecret, locations)
                    }
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "[Cloud Saves] Failed to get save sync location for appId $appId")
                    return@withContext null
                }
            }

        /**
         * Get resolved save directory paths for a game
         * @param context Android context
         * @param appId Game app ID
         * @param gameTitle Game title (for fallback)
         * @return List of resolved save locations, or null if cloud saves not available
         */
        suspend fun getSaveDirectoryPath(
            context: Context,
            appId: String,
            gameTitle: String,
            targetContainerId: Int? = null,
        ): List<GOGCloudSavesLocation>? =
            withContext(Dispatchers.IO) {
                try {
                    Timber.tag("GOG").d("[Cloud Saves] Getting save directory path for $appId ($gameTitle)")
                    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                    val game = getGameFromDbById(gameId.toString())

                    if (game == null) {
                        Timber.tag("GOG").w("[Cloud Saves] Game not found for appId $appId")
                        return@withContext null
                    }

                    val installPath = game.installPath
                    if (installPath.isEmpty()) {
                        Timber.tag("GOG").w("[Cloud Saves] Game not installed: $appId")
                        return@withContext null
                    }
                    Timber.tag("GOG").d("[Cloud Saves] Game install path: $installPath")

                    // Fetch save locations from API (Android runs games through Wine, so always Windows)
                    Timber.tag("GOG").d("[Cloud Saves] Fetching save locations from API")
                    val result = getSaveSyncLocation(context, appId, installPath)
                    if (result == null) {
                        Timber.tag("GOG").w("[Cloud Saves] Could not resolve cloud save config for game $gameId")
                        return@withContext null
                    }

                    val clientId = result.clientId
                    if (clientId.isEmpty()) {
                        Timber.tag("GOG").w("[Cloud Saves] No clientId found for game $gameId")
                        return@withContext null
                    }
                    Timber.tag("GOG").d("[Cloud Saves] Client ID: $clientId")

                    val clientSecret: String
                    val locations: List<GOGCloudSavesLocationTemplate>

                    // If no locations from API, use default Windows path
                    if (result.locations.isEmpty()) {
                        clientSecret = result.clientSecret
                        Timber.tag("GOG").d("[Cloud Saves] No save locations from API, using default for game $gameId")
                        val defaultLocation = "%LOCALAPPDATA%/GOG.com/Galaxy/Applications/$clientId/Storage/Shared/Files"
                        Timber.tag("GOG").d("[Cloud Saves] Using default location: $defaultLocation")
                        locations = listOf(GOGCloudSavesLocationTemplate("__default", defaultLocation))
                    } else {
                        clientSecret = result.clientSecret
                        locations = result.locations
                        Timber.tag("GOG").i("[Cloud Saves] Retrieved ${locations.size} save location(s) from API")
                    }

                    // Resolve each location
                    val resolvedLocations = mutableListOf<GOGCloudSavesLocation>()
                    for ((index, locationTemplate) in locations.withIndex()) {
                        Timber
                            .tag(
                                "GOG",
                            ).d(
                                "[Cloud Saves] Resolving location ${index + 1}/${locations.size}: '${locationTemplate.name}' = '${locationTemplate.location}'",
                            )
                        // Resolve GOG variables (<?INSTALL?>, etc.) to Windows env vars
                        var resolvedPath = PathType.resolveGOGPathVariables(locationTemplate.location, installPath)
                        Timber.tag("GOG").d("[Cloud Saves] After GOG variable resolution: $resolvedPath")

                        resolvedPath = PathType.toAbsPathForGOG(context, resolvedPath, appId, targetContainerId)
                        Timber.tag("GOG").d("[Cloud Saves] After path mapping to Wine prefix: $resolvedPath")

                        // Manual normalization — File.canonicalPath would follow symlinks
                        // and bail on missing intermediates.
                        resolvedPath = normalizeGogPathSegments(resolvedPath)
                        resolvedPath = resolveExistingPathCaseInsensitive(File(resolvedPath)).absolutePath
                        if (!resolvedPath.endsWith("/")) resolvedPath = "$resolvedPath/"
                        Timber.tag("GOG").d("[Cloud Saves] After normalization: $resolvedPath")

                        resolvedLocations.add(
                            GOGCloudSavesLocation(
                                name = locationTemplate.name,
                                location = resolvedPath,
                                clientId = clientId,
                                clientSecret = clientSecret,
                            ),
                        )
                    }

                    Timber.tag("GOG").i("[Cloud Saves] Resolved ${resolvedLocations.size} save location(s) for game $gameId")
                    for (loc in resolvedLocations) {
                        Timber.tag("GOG").d("[Cloud Saves]   - '${loc.name}': ${loc.location}")
                    }
                    return@withContext resolvedLocations
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "[Cloud Saves] Failed to get save directory path for appId $appId")
                    return@withContext null
                }
            }

        /**
         * Get stored sync timestamp for a game+location
         * @param appId Game app ID
         * @param locationName Location name
         * @return Timestamp string, or "0" if not found
         */
        fun getCloudSaveSyncTimestamp(
            appId: String,
            locationName: String,
        ): String {
            val key = "${appId}_$locationName"
            return syncTimestamps.getOrDefault(key, "0")
        }

        /**
         * Store sync timestamp for a game+location
         * @param appId Game app ID
         * @param locationName Location name
         * @param timestamp Timestamp string
         */
        fun setCloudSaveSyncTimestamp(
            appId: String,
            locationName: String,
            timestamp: String,
        ) {
            val key = "${appId}_$locationName"
            syncTimestamps[key] = timestamp
            Timber.d("Stored sync timestamp for $key: $timestamp")
            // Persist to disk
            saveCloudSaveTimestampsToDisk()
        }

        /**
         * Start a sync operation for a game (prevents concurrent syncs)
         * @param appId Game app ID
         * @return true if sync can proceed, false if one is already in progress
         */
        fun startSync(appId: String): Boolean = activeSyncs.add(appId)

        /**
         * End a sync operation for a game
         * @param appId Game app ID
         */
        fun endSync(appId: String) {
            activeSyncs.remove(appId)
        }

        /**
         * Load timestamps from disk
         */
        private fun loadCloudSaveTimestampsFromDisk() {
            try {
                if (timestampFile.exists()) {
                    val json = timestampFile.readText()
                    val map = org.json.JSONObject(json)
                    map.keys().forEach { key ->
                        syncTimestamps[key] = map.getString(key)
                    }
                    Timber.tag("GOG").i("[Cloud Saves] Loaded ${syncTimestamps.size} sync timestamps from disk")
                } else {
                    Timber.tag("GOG").d("[Cloud Saves] No persisted timestamps found (first run)")
                }
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "[Cloud Saves] Failed to load timestamps from disk")
            }
        }

        /**
         * Save timestamps to disk
         */
        private fun saveCloudSaveTimestampsToDisk() {
            try {
                val json = org.json.JSONObject()
                syncTimestamps.forEach { (key, value) ->
                    json.put(key, value)
                }
                timestampFile.writeText(json.toString())
                Timber.tag("GOG").d("[Cloud Saves] Saved ${syncTimestamps.size} timestamps to disk")
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "[Cloud Saves] Failed to save timestamps to disk")
            }
        }

        // File system and paths

        fun getAppDirPath(appId: String): String {
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            val game = runBlocking { getGameFromDbById(gameId.toString()) }

            if (game != null) {
                return GOGConstants.getGameInstallPath(game.title)
            }

            Timber.w("Could not find game for appId $appId")
            return GOGConstants.defaultGOGGamesPath
        }

        fun getGameInstallPath(
            gameId: String,
            gameTitle: String,
        ): String = GOGConstants.getGameInstallPath(gameTitle)

        private fun normalizeGogPathSegments(path: String): String {
            val unified = path.replace('\\', '/')
            val absolute = unified.startsWith('/')
            val parts = unified.split('/').filter { it.isNotEmpty() }
            val stack = ArrayDeque<String>()
            for (part in parts) {
                when (part) {
                    "." -> Unit
                    ".." ->
                        if (stack.isNotEmpty() && stack.last() != "..") {
                            stack.removeLast()
                        } else if (!absolute) {
                            stack.addLast("..")
                        }
                    else -> stack.addLast(part)
                }
            }
            val joined = stack.joinToString("/")
            return if (absolute) "/$joined" else joined
        }

        private fun resolveExistingPathCaseInsensitive(path: File): File {
            if (path.exists()) return path
            val absolute = path.absoluteFile
            val parts = absolute.path.split(File.separatorChar, '/', '\\').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return path
            var current =
                if (absolute.path.startsWith(File.separator)) {
                    File(File.separator)
                } else {
                    File(parts.first()).also { if (it.exists()) return@also }
                }
            val startIndex = if (absolute.path.startsWith(File.separator)) 0 else 1
            for (index in startIndex until parts.size) {
                val part = parts[index]
                val direct = File(current, part)
                current =
                    if (direct.exists()) {
                        direct
                    } else {
                        current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) }
                            ?: direct
                    }
            }
            return current
        }
    }
