package com.winlator.cmod.feature.stores.gog.service
import android.content.Context
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.gog.api.DepotFile
import com.winlator.cmod.feature.stores.gog.api.FileChunk
import com.winlator.cmod.feature.stores.gog.api.GOGApiClient
import com.winlator.cmod.feature.stores.gog.api.GOGManifestMeta
import com.winlator.cmod.feature.stores.gog.api.GOGManifestParser
import com.winlator.cmod.feature.stores.gog.api.V1DepotFile
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import com.winlator.cmod.feature.stores.steam.utils.Net
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton

// HTTP status failure with its typed status code.
class HttpStatusException(
    val statusCode: Int,
    message: String,
) : Exception(message)

// Downloads GOG games from Gen 1/Gen 2 CDN manifests.
@Singleton
class GOGDownloadManager
    @Inject
    constructor(
        private val apiClient: GOGApiClient,
        private val parser: GOGManifestParser,
        private val gogManager: GOGManager,
        @ApplicationContext private val context: Context,
    ) {
        private val WINDOWS_OS_VERSION = "windows"
        private val httpClient = Net.http

        // Context needed to refresh secure CDN links when they expire.
        private data class SecureLinkContext(
            val gameId: String,
            val generation: Int,
            val productIds: Set<String>,
            val chunkToProductMap: Map<String, String>,
        )

        companion object {
            private const val MAX_PARALLEL_DOWNLOADS = 10
            private const val MAX_PARALLEL_MANIFEST_FETCHES = 8
            private val EXPIRED_LINK_STATUS_CODES = setOf(401, 403, 404)
            private const val CHUNK_BUFFER_SIZE = 1024 * 1024
            private const val STREAM_BUFFER_SIZE = 64 * 1024
            private const val PROGRESS_EMIT_INTERVAL_MS = 250L
            private const val MAX_CHUNK_RETRIES = 3
            private const val RETRY_DELAY_MS = 1000L
            private const val DEPENDENCY_URL = "https://content-system.gog.com/dependencies/repository?generation=2"
        }

        // Downloads, verifies, or updates a GOG game.
        suspend fun downloadGame(
            gameId: String,
            installPath: File,
            downloadInfo: DownloadInfo,
            language: String = GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE,
            withDlcs: Boolean = false,
            supportDir: File? = null,
            selectedDlcIds: Set<String> = emptySet(),
            buildIdOverride: String? = null,
            verifyMode: Boolean = false,
            verifyProgressSink: ((bytesDone: Long, total: Long) -> Unit)? = null,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    Timber.tag("GOG").i("Starting download for game $gameId to ${installPath.absolutePath}")

                    if (supportDir != null) {
                        Timber.tag("GOG").i("Will also put dependencies into ${supportDir.absolutePath}")
                    }

                    val dbGame = gogManager.getGameFromDbById(gameId)

                    if (dbGame == null) {
                        return@withContext Result.failure(
                            Exception("Failed to fetch game from DB"),
                        )
                    }

                    Timber.tag("GOG").d("Database game ID: ${dbGame.id}, title: ${dbGame.title}")

                    com.winlator.cmod.app.PluviaApp.events.emitJava(
                        com.winlator.cmod.feature.stores.steam.events.AndroidEvent
                            .DownloadStatusChanged(gameId.toIntOrNull() ?: 0, true),
                    )

                    downloadInfo.updateStatusMessage("Fetching builds")

                    // Prefer Gen 2 builds, falling back to Gen 1 legacy builds.
                    val selectedBuild =
                        run {
                            val gen2Result = apiClient.getBuildsForGame(gameId, WINDOWS_OS_VERSION, generation = 2)
                            if (gen2Result.isFailure) {
                                return@withContext Result.failure(
                                    gen2Result.exceptionOrNull() ?: Exception("Failed to fetch Gen 2 builds"),
                                )
                            }
                            val gen2Items = gen2Result.getOrThrow().items
                            if (buildIdOverride != null) {
                                gen2Items.firstOrNull { it.buildId == buildIdOverride && it.platform.equals(WINDOWS_OS_VERSION, ignoreCase = true) }
                                    ?.let { return@run it }
                            }
                            parser
                                .selectBuild(gen2Items, preferredGeneration = 2, platform = WINDOWS_OS_VERSION)
                                ?.let { return@run it }
                            val gen1Result = apiClient.getBuildsForGame(gameId, WINDOWS_OS_VERSION, generation = 1)
                            if (gen1Result.isFailure) {
                                return@withContext Result.failure(
                                    gen1Result.exceptionOrNull() ?: Exception("Failed to fetch builds"),
                                )
                            }
                            val builds = gen1Result.getOrThrow()
                            if (buildIdOverride != null) {
                                builds.items.firstOrNull { it.buildId == buildIdOverride && it.platform.equals(WINDOWS_OS_VERSION, ignoreCase = true) }
                                    ?.let { return@run it }
                            }
                            parser.selectBuild(builds.items, preferredGeneration = 1, platform = WINDOWS_OS_VERSION)
                                ?: run {
                                    val hint =
                                        when {
                                            builds.items.isEmpty() -> "No builds returned for Windows (game may be Linux/Mac only)."

                                            else -> "No Windows build. Available: ${builds.items.joinToString {
                                                "Gen ${it.generation}/${it.platform}"
                                            }}."
                                        }
                                    return@withContext Result.failure(Exception("No suitable build found for Windows. $hint"))
                                }
                        }

                    Timber
                        .tag(
                            "GOG",
                        ).i(
                            "Selected build: ${selectedBuild.buildId} (Gen ${selectedBuild.generation}, Platform: ${selectedBuild.platform})",
                        )
                    Timber.tag("GOG").d("Build productId: ${selectedBuild.productId}, input gameId: $gameId")
                    Timber
                        .tag(
                            "GOG",
                        ).d(
                            "Full build details: buildId=${selectedBuild.buildId}, productId=${selectedBuild.productId}, platform=${selectedBuild.platform}, gen=${selectedBuild.generation}, version=${selectedBuild.versionName}, branch=${selectedBuild.branch}, legacyBuildId=${selectedBuild.legacyBuildId}",
                        )

                    val realGameId = gameId

                    downloadInfo.updateStatusMessage("Fetching manifest")

                    val gameManifestResult = apiClient.fetchManifest(selectedBuild.link)
                    if (gameManifestResult.isFailure) {
                        return@withContext Result.failure(
                            gameManifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest"),
                        )
                    }

                    val gameManifest = gameManifestResult.getOrThrow()
                    Timber.tag("GOG").d("Game Manifest: ${gameManifest.installDirectory}, ${gameManifest.depots.size} depot(s)")
                    Timber.tag("GOG").d("Game Manifest baseProductId: ${gameManifest.baseProductId}")

                    gameManifest.products?.let { products ->
                        Timber.tag("GOG").d("Manifest products: ${products.joinToString { "name=${it.name}, id=${it.productId}" }}")
                    }

                    if (selectedBuild.generation == 1 && gameManifest.productTimestamp != null) {
                        Timber.tag("GOG").i("Using Gen 1 (legacy) downloader for game $gameId")
                        return@withContext downloadGameGen1(
                            gameId = gameId,
                            installPath = installPath,
                            downloadInfo = downloadInfo,
                            gameManifest = gameManifest,
                            selectedBuild = selectedBuild,
                            language = language,
                            withDlcs = withDlcs,
                            supportDir = supportDir,
                            selectedDlcIds = selectedDlcIds,
                            verifyMode = verifyMode,
                        )
                    }

                    Timber.tag("GOG").i("Using Gen 2 downloader for game $gameId")

                    val dependencies = gameManifest.dependencies

                    downloadInfo.updateStatusMessage("Filtering depots")

                    val (languageDepots, effectiveLang) = parser.filterDepotsByLanguage(gameManifest, language)
                    if (languageDepots.isEmpty()) {
                        return@withContext Result.failure(
                            Exception("No depots found for any requested or fallback (English) languages"),
                        )
                    }

                    // Filter by ownership to exclude unowned DLC depots
                    val ownedGameIds = gogManager.getAllGameIds()
                    val requestedProductIds =
                        buildSet {
                            add(gameManifest.baseProductId)
                            if (withDlcs) addAll(selectedDlcIds)
                        }
                    val depots =
                        parser
                            .filterDepotsByOwnership(languageDepots, ownedGameIds)
                            .filter { it.productId in requestedProductIds }
                    if (depots.isEmpty()) {
                        return@withContext Result.failure(Exception("No owned depots found for language: $effectiveLang"))
                    }

                    Timber.tag("GOG").d("Found ${depots.size} owned depot(s) for $effectiveLang")
                    depots.forEachIndexed { index, depot ->
                        Timber
                            .tag(
                                "GOG",
                            ).d(
                                "  Depot $index: productId=${depot.productId}, manifest=${depot.manifest}, size=${depot.size}, compressedSize=${depot.compressedSize}",
                            )
                    }

                    // Retain source depot IDs so shared files inherit the correct product ownership.
                    data class FileWithDepot(
                        val file: DepotFile,
                        val depotProductId: String,
                    )
                    fun effectiveProductId(
                        fileProductId: String?,
                        depotProductId: String,
                    ): String =
                        when (fileProductId) {
                            null, "", "2147483047" -> depotProductId
                            else -> fileProductId
                        }

                    downloadInfo.updateStatusMessage("Fetching depot manifests (${depots.size})")

                    val depotCompleted = AtomicInteger(0)
                    val depotManifestResults =
                        coroutineScope {
                            val limit = minOf(MAX_PARALLEL_MANIFEST_FETCHES, depots.size).coerceAtLeast(1)
                            val nextIndex = AtomicInteger(0)
                            val perDepot = arrayOfNulls<Result<com.winlator.cmod.feature.stores.gog.api.DepotManifest>>(depots.size)
                            (0 until limit)
                                .map {
                                    async {
                                        while (true) {
                                            val i = nextIndex.getAndIncrement()
                                            if (i >= depots.size) break
                                            perDepot[i] = apiClient.fetchDepotManifest(depots[i].manifest)
                                            val done = depotCompleted.incrementAndGet()
                                            downloadInfo.updateStatusMessage("Fetching depot $done/${depots.size}")
                                        }
                                    }
                                }.awaitAll()
                            perDepot.toList()
                        }

                    val allFilesWithDepots = mutableListOf<FileWithDepot>()
                    for ((index, depot) in depots.withIndex()) {
                        val depotResult = depotManifestResults[index]
                            ?: return@withContext Result.failure(Exception("Missing depot manifest result for ${depot.manifest}"))
                        if (depotResult.isFailure) {
                            return@withContext Result.failure(
                                depotResult.exceptionOrNull() ?: Exception("Failed to fetch depot manifest"),
                            )
                        }
                        depotResult.getOrThrow().files.forEach { file ->
                            allFilesWithDepots.add(FileWithDepot(file, depot.productId))
                        }
                    }

                    val requestedFilesWithDepots =
                        allFilesWithDepots.filter { (file, depotProductId) ->
                            effectiveProductId(file.productId, depotProductId) in requestedProductIds
                        }
                    Timber.tag("GOG").d("Total files from all depots: ${allFilesWithDepots.size}")

                    val baseFiles =
                        requestedFilesWithDepots
                            .filter { (file, depotProductId) ->
                                effectiveProductId(file.productId, depotProductId) == gameManifest.baseProductId
                            }.map { it.file }
                    val dlcFiles =
                        requestedFilesWithDepots
                            .filter { (file, depotProductId) ->
                                effectiveProductId(file.productId, depotProductId) != gameManifest.baseProductId
                            }.map { it.file }
                    var (gameFiles, supportFiles) = parser.separateSupportFiles(requestedFilesWithDepots.map { it.file })

                    val gameInstallDir = installPath
                    val beforeCount = gameFiles.size
                    val originalGameFiles = gameFiles
                    var verifyScannedBytes = 0L
                    var verifyScanTotalBytes = 0L
                    if (verifyMode) {
                        verifyScanTotalBytes = gameFiles.sumOf { f -> f.chunks.sumOf { it.size } }
                        downloadInfo.updateStatus(
                            com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.VERIFYING,
                            "Verifying installed files (0/$beforeCount)",
                        )
                        downloadInfo.setTotalExpectedBytes(verifyScanTotalBytes)
                        downloadInfo.setDisplayTotalExpectedBytes(verifyScanTotalBytes)
                        downloadInfo.initializeBytesDownloaded(0L)
                        downloadInfo.emitProgressChange()
                        verifyProgressSink?.invoke(0L, verifyScanTotalBytes)

                        val verified = mutableListOf<DepotFile>()
                        var lastEmitMs = 0L
                        gameFiles.forEachIndexed { idx, file ->
                            if (!downloadInfo.isActive()) {
                                return@withContext Result.failure(
                                    kotlinx.coroutines.CancellationException("Verify cancelled"),
                                )
                            }
                            val outputFile = File(gameInstallDir, file.path)
                            if (!isInstalledFileValidByChunkMd5(outputFile, file)) {
                                verified.add(file)
                            }
                            verifyScannedBytes += file.chunks.sumOf { it.size }
                            downloadInfo.setBytesDownloaded(verifyScannedBytes)

                            val now = System.currentTimeMillis()
                            val isLastFile = idx == beforeCount - 1
                            if (isLastFile || now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                                lastEmitMs = now
                                downloadInfo.updateStatusMessage("Verifying installed files (${idx + 1}/$beforeCount)")
                                downloadInfo.emitProgressChange()
                                verifyProgressSink?.invoke(verifyScannedBytes, verifyScanTotalBytes)
                            }
                        }
                        gameFiles = verified
                    } else {
                        gameFiles =
                            gameFiles.filter { file ->
                                val outputFile = File(gameInstallDir, file.path)
                                val expectedSize = file.chunks.sumOf { it.size }
                                !fileExistsWithCorrectSize(outputFile, expectedSize)
                            }
                    }
                    Timber.tag("GOG").d(
                        "${if (verifyMode) "Verify" else "Size"} check kept ${gameFiles.size}/$beforeCount file(s) for (re)download",
                    )

                    val (baseGameFiles, _) = parser.separateSupportFiles(baseFiles)
                    val baseGameSize = parser.calculateTotalSize(baseGameFiles)
                    val dlcSize =
                        if (withDlcs && dlcFiles.isNotEmpty()) {
                            val (dlcGameFiles, _) = parser.separateSupportFiles(dlcFiles)
                            parser.calculateTotalSize(dlcGameFiles)
                        } else {
                            0L
                        }

                    Timber.tag("GOG").d(
                        """
                |Download plan:
                |  Base game files: ${baseFiles.size}
                |  DLC files: ${dlcFiles.size}
                |  Game files to download: ${gameFiles.size}
                |  Support files: ${supportFiles.size}
                |  Base game size: ${baseGameSize / 1_000_000.0} MB
                |  DLC size: ${dlcSize / 1_000_000.0} MB
                |  Including DLCs: $withDlcs
                        """.trimMargin(),
                    )

                    val totalSize = parser.calculateTotalSize(gameFiles)
                    val chunkHashes = parser.extractChunkHashes(gameFiles)

                    Timber.tag("GOG").d(
                        """
                |Download stats:
                |  Total compressed size: ${totalSize / 1_000_000.0} MB (${if (withDlcs) "including DLC" else "base game only"})
                |  Unique chunks: ${chunkHashes.size}
                |  Files: ${gameFiles.size}
                        """.trimMargin(),
                    )

                    val fullCompressedSize = parser.calculateTotalSize(originalGameFiles)

                    val combinedWorkTotal =
                        if (verifyMode) verifyScanTotalBytes + totalSize else fullCompressedSize
                    downloadInfo.setTotalExpectedBytes(combinedWorkTotal)
                    if (verifyMode) {
                        downloadInfo.setDisplayTotalExpectedBytes(combinedWorkTotal)
                        downloadInfo.setBytesDownloaded(verifyScannedBytes)
                        downloadInfo.updateStatus(
                            com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.DOWNLOADING,
                            if (gameFiles.isEmpty()) "All files verified" else "Re-downloading ${gameFiles.size} changed file(s)",
                        )
                        downloadInfo.emitProgressChange()
                        verifyProgressSink?.invoke(verifyScannedBytes, combinedWorkTotal)
                    } else {
                        val skippedCompressedSize = (fullCompressedSize - totalSize).coerceAtLeast(0L)
                        val cacheDirForBookkeeping = File(installPath, ".gog_chunks")
                        val cachedCompressedSize =
                            if (cacheDirForBookkeeping.exists()) {
                                val sizeByHash = HashMap<String, Long>(chunkHashes.size)
                                for (file in gameFiles) {
                                    for (chunk in file.chunks) {
                                        sizeByHash.putIfAbsent(
                                            chunk.compressedMd5,
                                            (chunk.compressedSize ?: chunk.size).coerceAtLeast(0L),
                                        )
                                    }
                                }
                                sizeByHash.entries.sumOf { (hash, size) ->
                                    if (File(cacheDirForBookkeeping, "$hash.chunk").exists()) size else 0L
                                }
                            } else {
                                0L
                            }
                        val alreadyDoneBytes = skippedCompressedSize + cachedCompressedSize
                        downloadInfo.setDisplayTotalExpectedBytes(fullCompressedSize)
                        downloadInfo.initializeBytesDownloaded(alreadyDoneBytes)
                        // No statusMessage tweak here on purpose — the chip text comes from the
                        // DownloadPhase enum (existing downloads_queue_phase_* strings), and the
                        // bar already reflects alreadyDoneBytes/fullCompressedSize.
                        downloadInfo.emitProgressChange()
                        verifyProgressSink?.invoke(alreadyDoneBytes, fullCompressedSize)
                    }

                    downloadInfo.updateStatusMessage("Getting secure download links")

                    val productUrlMap = mutableMapOf<String, List<String>>()
                    val chunkToProductMap = mutableMapOf<String, String>()

                    Timber
                        .tag(
                            "GOG",
                        ).d(
                            "Mapping chunks to products. gameId parameter: $gameId, realGameId: $realGameId, manifest baseProductId: ${gameManifest.baseProductId}",
                        )

                    val filesToDownloadPaths = gameFiles.map { it.path }.toSet()
                    allFilesWithDepots.forEach { (file, depotProductId) ->
                        if (file.path !in filesToDownloadPaths) return@forEach

                        val productId = effectiveProductId(file.productId, depotProductId)

                        if (productId in ownedGameIds && productId in requestedProductIds) {
                            file.chunks.forEach { chunk ->
                                chunkToProductMap[chunk.compressedMd5] = productId
                            }
                        } else {
                            Timber.tag("GOG").d("Skipping file ${file.path} from unowned product $productId")
                        }
                    }

                    val productIds = chunkToProductMap.values.toSet()
                    Timber.tag("GOG").d("Need secure links for ${productIds.size} owned product(s): ${productIds.joinToString()}")
                    Timber.tag("GOG").d("Mapped ${chunkToProductMap.size} chunks to products")

                    // Fetch secure links for each product in parallel.
                    val linkResults =
                        coroutineScope {
                            productIds
                                .map { productId ->
                                    async {
                                        productId to
                                            apiClient.getSecureLink(
                                                productId = productId,
                                                path = "/",
                                                generation = selectedBuild.generation,
                                            )
                                    }
                                }.awaitAll()
                        }
                    for ((productId, linksResult) in linkResults) {
                        if (linksResult.isSuccess) {
                            productUrlMap[productId] = linksResult.getOrThrow().urls
                        } else {
                            return@withContext Result.failure(
                                linksResult.exceptionOrNull() ?: Exception("Failed to get secure links for product $productId"),
                            )
                        }
                    }

                    val chunkUrlMap = parser.buildChunkUrlMapWithProducts(chunkHashes, chunkToProductMap, productUrlMap)

                    val secureLinkContext =
                        SecureLinkContext(
                            gameId = realGameId,
                            generation = selectedBuild.generation,
                            productIds = productIds,
                            chunkToProductMap = chunkToProductMap,
                        )

                    Timber.tag("GOG").i("Downloading chunks for game $gameId")

                    installPath.mkdirs()
                    MarkerUtils.addMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                    downloadInfo.updateStatusMessage("Downloading chunks")

                    val chunkCacheDir = File(installPath, ".gog_chunks")
                    chunkCacheDir.mkdirs()

                    val downloadResult =
                        downloadChunks(
                            chunkUrlMap = chunkUrlMap,
                            chunkCacheDir = chunkCacheDir,
                            downloadInfo = downloadInfo,
                            chunkHashes = chunkHashes,
                            secureLinkContext = secureLinkContext,
                            chunkToProductMap = chunkToProductMap,
                            progressSink = verifyProgressSink,
                        )

                    if (downloadResult.isFailure) {
                        MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                        return@withContext downloadResult
                    }

                    downloadInfo.updateStatus(
                        com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.UNPACKING,
                        context.getString(R.string.downloads_queue_phase_unpacking),
                    )
                    downloadInfo.emitProgressChange()

                    gameInstallDir.mkdirs()

                    val assembleResult = assembleFiles(gameFiles, chunkCacheDir, gameInstallDir, downloadInfo)
                    if (assembleResult.isFailure) {
                        MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                        return@withContext assembleResult
                    }

                    if (supportDir != null && dependencies.isNotEmpty()) {
                        downloadInfo.updateStatus(
                            com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.DOWNLOADING,
                            context.getString(R.string.downloads_queue_phase_downloading),
                        )
                        supportDir.mkdirs()

                        val dependencyResult = downloadDependencies(gameId, dependencies, installPath, supportDir, downloadInfo)
                        if (dependencyResult.isFailure) {
                            Timber.tag("GOG").w("Failed to install Dependencies: ${dependencyResult.exceptionOrNull()?.message}")
                        }
                    }

                    downloadInfo.updateStatus(
                        com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.FINALIZING,
                        context.getString(R.string.downloads_queue_phase_finalizing),
                    )
                    downloadInfo.emitProgressChange()
                    chunkCacheDir.deleteRecursively()

                    saveManifestToGameDir(
                        installPath,
                        gameManifest,
                        selectedBuild.buildId,
                        selectedBuild.versionName,
                        effectiveLang,
                        selectedDlcIds,
                    )

                    finalizeInstallSuccess(gameId, installPath, downloadInfo)
                    Timber.tag("GOG").i("Download completed successfully for game $gameId")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "Download failed: ${e.message}")
                    downloadInfo.updateStatusMessage("Failed: ${e.message}")
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)
                    downloadInfo.emitProgressChange()

                    // Emit download stopped event on failure
                    com.winlator.cmod.app.PluviaApp.events.emitJava(
                        com.winlator.cmod.feature.stores.steam.events.AndroidEvent
                            .DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false),
                    )

                    // Ensure in-progress marker is cleared on failure
                    MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                    Result.failure(e)
                }
            }

        private fun saveManifestToGameDir(
            installPath: File,
            gameManifest: GOGManifestMeta,
            buildId: String,
            versionName: String,
            language: String,
            selectedDlcIds: Set<String> = emptySet(),
        ) {
            try {
                val installedDlcIds = GOGManifestUtils.getInstalledDlcIds(installPath) + selectedDlcIds
                val installedProductIds = installedDlcIds + gameManifest.baseProductId
                val productsArray = JSONArray()
                gameManifest.products.filter { it.productId in installedProductIds }.forEach { p ->
                    productsArray.put(
                        JSONObject().apply {
                            put("productId", p.productId)
                            put("name", p.name)
                            put("temp_executable", p.temp_executable ?: "")
                            put("temp_arguments", p.temp_arguments ?: "")
                        },
                    )
                }
                val root =
                    JSONObject().apply {
                        put("version", 2)
                        put("baseProductId", gameManifest.baseProductId)
                        put("scriptInterpreter", gameManifest.scriptInterpreter)
                        put("products", productsArray)
                        put(
                            "installedDlcIds",
                            JSONArray().apply {
                                installedDlcIds.sorted().forEach { put(it) }
                            },
                        )
                        put("buildId", buildId)
                        put("versionName", versionName)
                        put("language", language)
                    }
                val file = File(installPath, GOGManifestUtils.MANIFEST_FILE_NAME)
                file.writeText(root.toString())
                Timber.tag("GOG").d("Saved setup manifest to ${file.absolutePath} (scriptInterpreter=${gameManifest.scriptInterpreter})")
            } catch (e: Exception) {
                Timber.tag("GOG").w(e, "Failed to save GOG setup manifest")
            }
        }

        private suspend fun finalizeInstallSuccess(
            gameId: String,
            installPath: File,
            downloadInfo: DownloadInfo,
        ) {
            MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(installPath.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)
            InstallOwnership.claim(installPath.absolutePath, InstallStore.GOG)

            downloadInfo.updateStatusMessage("Updating database")
            try {
                val game = gogManager.getGameFromDbById(gameId)
                if (game != null) {
                    val installSize = calculateDirectorySize(installPath)
                    gogManager.updateGame(game.copy(isInstalled = true, installPath = installPath.absolutePath, installSize = installSize))
                    Timber.tag("GOG").i("Updated database: game marked as installed, size: ${installSize / 1_000_000} MB")
                } else {
                    Timber.tag("GOG").w("Game $gameId not found in database, skipping DB update")
                }
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Failed to update database for game $gameId")
            }
            downloadInfo.updateStatusMessage("Complete")
            downloadInfo.setProgress(1.0f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()
            com.winlator.cmod.app.PluviaApp.events.emitJava(
                com.winlator.cmod.feature.stores.steam.events.AndroidEvent
                    .DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false),
            )
            com.winlator.cmod.app.PluviaApp.events.emitJava(
                com.winlator.cmod.feature.stores.steam.events.AndroidEvent
                    .LibraryInstallStatusChanged(gameId.toIntOrNull() ?: 0),
            )
        }

        /**
         * Gen 1 (legacy) download: one main.bin per depot; files are read via Range requests (offset/size).
         * See heroic-gogdl: task_executor.py v1() uses endpoint["parameters"]["path"] += "/main.bin" and Range: bytes=offset-(offset+size-1).
         */
        private suspend fun downloadGameGen1(
            gameId: String,
            installPath: File,
            downloadInfo: DownloadInfo,
            gameManifest: com.winlator.cmod.feature.stores.gog.api.GOGManifestMeta,
            selectedBuild: com.winlator.cmod.feature.stores.gog.api.GOGBuild,
            language: String,
            withDlcs: Boolean,
            supportDir: File?,
            selectedDlcIds: Set<String>,
            verifyMode: Boolean = false,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    Timber.tag("GOG").i("Starting Gen 1 (legacy) download for game $gameId")

                    val timestamp =
                        gameManifest.productTimestamp
                            ?: return@withContext Result.failure(Exception("Gen 1 manifest missing productTimestamp"))
                    val platform = selectedBuild.platform
                    val ownedGameIds = gogManager.getAllGameIds()

                    // Filter depots by selected language (same logic as Gen 2), then by ownership
                    downloadInfo.updateStatusMessage("Filtering depots")
                    val (languageDepots, effectiveLang) = parser.filterDepotsByLanguage(gameManifest, language)
                    if (languageDepots.isEmpty()) {
                        return@withContext Result.failure(
                            Exception("No depots found for requested or fallback (English) languages"),
                        )
                    }
                    val depots = parser.filterDepotsByOwnership(languageDepots, ownedGameIds)
                    if (depots.isEmpty()) {
                        return@withContext Result.failure(Exception("No owned depots found"))
                    }

                    val baseProductId = gameManifest.baseProductId
                    val requestedProductIds =
                        buildSet {
                            add(baseProductId)
                            if (withDlcs) addAll(selectedDlcIds)
                        }
                    val filesToDownload = depots.filter { it.productId in requestedProductIds }
                    if (filesToDownload.isEmpty()) {
                        return@withContext Result.failure(Exception("No depots to download"))
                    }

                    val productIds = filesToDownload.map { it.productId }.toSet()
                    val securePath = "/$platform/$timestamp/"
                    val productUrlMap = mutableMapOf<String, List<String>>()
                    val gen1LinkResults =
                        coroutineScope {
                            productIds
                                .map { productId ->
                                    async {
                                        productId to apiClient.getSecureLink(productId = productId, path = securePath, generation = 1)
                                    }
                                }.awaitAll()
                        }
                    for ((productId, linksResult) in gen1LinkResults) {
                        if (linksResult.isFailure) {
                            return@withContext Result.failure(
                                linksResult.exceptionOrNull() ?: Exception("Failed to get secure link for product $productId"),
                            )
                        }
                        productUrlMap[productId] = linksResult.getOrThrow().urls
                    }

                    data class FileWithProduct(
                        val file: V1DepotFile,
                        val productId: String,
                    )
                    val allV1Files = mutableListOf<FileWithProduct>()

                    downloadInfo.updateStatusMessage("Fetching depot manifests (${filesToDownload.size})")
                    val gen1DepotJsonResults =
                        coroutineScope {
                            val limit = minOf(MAX_PARALLEL_MANIFEST_FETCHES, filesToDownload.size).coerceAtLeast(1)
                            val nextIndex = AtomicInteger(0)
                            val perDepot = arrayOfNulls<Result<String>>(filesToDownload.size)
                            val completed = AtomicInteger(0)
                            (0 until limit)
                                .map {
                                    async {
                                        while (true) {
                                            val i = nextIndex.getAndIncrement()
                                            if (i >= filesToDownload.size) break
                                            val d = filesToDownload[i]
                                            perDepot[i] =
                                                apiClient.fetchDepotManifestV1(d.productId, platform, timestamp, d.manifest)
                                            val done = completed.incrementAndGet()
                                            downloadInfo.updateStatusMessage("Fetching depot $done/${filesToDownload.size}")
                                        }
                                    }
                                }.awaitAll()
                            perDepot.toList()
                        }
                    for ((idx, depot) in filesToDownload.withIndex()) {
                        val depotJsonResult = gen1DepotJsonResults[idx]
                            ?: return@withContext Result.failure(Exception("Missing Gen 1 depot result for ${depot.manifest}"))
                        if (depotJsonResult.isFailure) {
                            return@withContext Result.failure(
                                depotJsonResult.exceptionOrNull() ?: Exception("Failed to fetch depot manifest"),
                            )
                        }
                        val v1Files = parser.parseV1DepotManifest(depotJsonResult.getOrThrow())
                        v1Files.forEach { allV1Files.add(FileWithProduct(it, depot.productId)) }
                    }

                    var gameFiles = allV1Files.filter { !it.file.isSupport }
                    var supportFiles = allV1Files.filter { it.file.isSupport }
                    // Resume check — size-only by default. In verify mode, use the file's full MD5
                    // (Gen 1 manifests store a per-file hash, not per-chunk hashes like Gen 2).
                    fun v1IsValid(f: FileWithProduct, baseDir: File): Boolean {
                        val outFile = File(baseDir, f.file.path)
                        return if (verifyMode) {
                            val expectedHash = f.file.hash.takeIf { it.isNotBlank() }
                            fileExistsWithCorrectSize(outFile, f.file.size, expectedHash)
                        } else {
                            fileExistsWithCorrectSize(outFile, f.file.size)
                        }
                    }
                    if (verifyMode) downloadInfo.updateStatusMessage("Verifying installed files")
                    gameFiles = gameFiles.filterNot { v1IsValid(it, installPath) }
                    if (supportDir != null) {
                        supportFiles = supportFiles.filterNot { v1IsValid(it, supportDir) }
                    }
                    val totalSize =
                        gameFiles.sumOf { it.file.size } +
                            if (supportDir != null) supportFiles.sumOf { it.file.size } else 0L
                    downloadInfo.setTotalExpectedBytes(totalSize)
                    downloadInfo.updateStatusMessage("Downloading files")
                    downloadInfo.setProgress(0f)
                    downloadInfo.setActive(true)
                    downloadInfo.emitProgressChange()
                    installPath.mkdirs()
                    MarkerUtils.addMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                    val totalFiles = gameFiles.size + if (supportDir != null) supportFiles.size else 0
                    var doneFiles = 0

                    // Gen 1: one main.bin URL per product; each file is fetched with Range: bytes=offset-(offset+size-1)
                    val mainBinUrlByProduct =
                        productUrlMap.mapValues { (_, urls) ->
                            (urls.firstOrNull()?.trimEnd('/') ?: "") + "/main.bin"
                        }

                    fun downloadOneFile(
                        f: FileWithProduct,
                        baseDir: File,
                    ): Result<Unit> {
                        val file = f.file
                        val outFile = File(baseDir, file.path)
                        outFile.parentFile?.mkdirs()

                        if (file.size == 0L) {
                            outFile.createNewFile()
                            return Result.success(Unit)
                        }

                        val mainBinUrl =
                            mainBinUrlByProduct[f.productId]
                                ?: return Result.failure(Exception("No main.bin URL for product ${f.productId}"))

                        val offset = file.offset
                        if (offset == null) {
                            return Result.failure(Exception("Gen 1 file ${file.path} has no offset (main.bin range request required)"))
                        }

                        val rangeHeader = "bytes=$offset-${offset + file.size - 1}"
                        val request =
                            Request
                                .Builder()
                                .url(mainBinUrl)
                                .header("User-Agent", "GOG Galaxy")
                                .header("Range", rangeHeader)
                                .build()

                        return try {
                            httpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) return Result.failure(Exception("HTTP ${response.code} for ${file.path}"))
                                val body = response.body ?: return Result.failure(Exception("Empty response"))
                                val md = MessageDigest.getInstance("MD5")
                                val buffer = ByteArray(1024 * 1024) // 1MB
                                var lastEmitMs = 0L
                                DigestOutputStream(
                                    BufferedOutputStream(FileOutputStream(outFile)),
                                    md,
                                ).use { out ->
                                    body.byteStream().use { input ->
                                        var n: Int
                                        while (input.read(buffer).also { n = it } != -1) {
                                            if (!downloadInfo.isActive()) {
                                                outFile.delete()
                                                return Result.failure(Exception("Download cancelled"))
                                            }
                                            out.write(buffer, 0, n)
                                            downloadInfo.updateBytesDownloaded(n.toLong())
                                            val now = System.currentTimeMillis()
                                            if (now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                                                lastEmitMs = now
                                                downloadInfo.setProgress(
                                                    (downloadInfo.getBytesDownloaded().toFloat() / totalSize).coerceIn(0f, 1f),
                                                )
                                                downloadInfo.emitProgressChange()
                                            }
                                        }
                                    }
                                }
                                val bytesWritten = outFile.length()
                                if (bytesWritten != file.size) return Result.failure(Exception("Size mismatch ${file.path}"))
                                val md5 = md.digest().joinToString("") { "%02x".format(it) }
                                if (file.hash.isNotEmpty() &&
                                    md5 != file.hash
                                ) {
                                    return Result.failure(Exception("MD5 mismatch ${file.path}"))
                                }
                                // bytes already reported during copy; ensure final progress is exact
                                downloadInfo.setProgress(
                                    (downloadInfo.getBytesDownloaded().toFloat() / totalSize).coerceIn(0f, 1f),
                                )
                                downloadInfo.emitProgressChange()
                                Result.success(Unit)
                            }
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }

                    for (f in gameFiles) {
                        if (!downloadInfo.isActive()) {
                            MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }
                        downloadInfo.updateStatusMessage("Downloading ${f.file.path}")
                        val res = downloadOneFile(f, installPath)
                        if (res.isFailure) {
                            MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                            return@withContext res
                        }
                        doneFiles++
                    }
                    if (supportDir != null) {
                        supportDir.mkdirs()
                        for (f in supportFiles) {
                            if (!downloadInfo.isActive()) {
                                MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                                return@withContext Result.failure(Exception("Download cancelled"))
                            }
                            downloadInfo.updateStatusMessage("Downloading support ${f.file.path}")
                            val res = downloadOneFile(f, supportDir)
                            if (res.isFailure) {
                                MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                                return@withContext res
                            }
                            doneFiles++
                        }
                    }

                    downloadInfo.updateStatus(
                        com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.FINALIZING,
                        context.getString(R.string.downloads_queue_phase_finalizing),
                    )
                    downloadInfo.emitProgressChange()
                    saveManifestToGameDir(
                        installPath,
                        gameManifest,
                        selectedBuild.buildId,
                        selectedBuild.versionName,
                        effectiveLang,
                        selectedDlcIds,
                    )

                    finalizeInstallSuccess(gameId, installPath, downloadInfo)
                    Timber.tag("GOG").i("Gen 1 download completed for game $gameId")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "Gen 1 download failed: ${e.message}")
                    downloadInfo.updateStatusMessage("Failed: ${e.message}")
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)
                    downloadInfo.emitProgressChange()
                    MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    com.winlator.cmod.app.PluviaApp.events.emitJava(
                        com.winlator.cmod.feature.stores.steam.events.AndroidEvent
                            .DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false),
                    )
                    Result.failure(e)
                }
            }

        /**
         * Download all chunks from CDN with parallel execution
         *
         * @param chunkUrlMap Map of chunk MD5 hash to secure CDN URL
         * @param chunkCacheDir Directory to cache downloaded chunks
         * @param downloadInfo Progress tracker
         * @param chunkHashes List of all chunk hashes needed
         * @param secureLinkContext Context for refreshing secure links if they expire
         * @param chunkToProductMap Map of chunk MD5 hash to product ID for debugging
         */
        private suspend fun downloadChunks(
            chunkUrlMap: Map<String, String>,
            chunkCacheDir: File,
            downloadInfo: DownloadInfo,
            chunkHashes: List<String>,
            secureLinkContext: SecureLinkContext,
            chunkToProductMap: Map<String, String>,
            progressSink: ((bytesDone: Long, total: Long) -> Unit)? = null,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val currentChunkUrlMap = AtomicReference(chunkUrlMap)
                    val chunks = chunkHashes.distinct()
                    val totalChunks = chunks.size
                    val nextChunkIndex = AtomicInteger(0)
                    val downloadedChunks = AtomicInteger(0)
                    val refreshMutex = Mutex()

                    Timber.tag("GOG").d("Downloading $totalChunks chunks")

                    if (totalChunks == 0) {
                        return@withContext Result.success(Unit)
                    }

                    downloadInfo.setProgress(0.0f)
                    downloadInfo.setActive(true)
                    downloadInfo.emitProgressChange()

                    suspend fun downloadOneChunk(chunkMd5: String): File {
                        var refreshAttempts = 0
                        while (true) {
                            val url =
                                currentChunkUrlMap.get()[chunkMd5]
                                    ?: throw Exception("No URL found for chunk $chunkMd5")
                            val result = downloadChunkWithRetry(chunkMd5, url, chunkCacheDir, downloadInfo)
                            if (result.isSuccess) return result.getOrThrow()

                            val exception = result.exceptionOrNull()
                            if (exception is HttpStatusException &&
                                exception.statusCode in EXPIRED_LINK_STATUS_CODES &&
                                refreshAttempts < MAX_CHUNK_RETRIES
                            ) {
                                refreshMutex.withLock {
                                    if (currentChunkUrlMap.get()[chunkMd5] == url) {
                                        val productId = chunkToProductMap[chunkMd5]
                                        Timber
                                            .tag("GOG")
                                            .w("Chunk $chunkMd5 belongs to product $productId: ${exception.message}; refreshing secure links")
                                        val refreshResult = refreshSecureLinks(secureLinkContext, chunkHashes)
                                        if (refreshResult.isFailure) {
                                            throw refreshResult.exceptionOrNull()
                                                ?: Exception("Failed to refresh secure links")
                                        }
                                        currentChunkUrlMap.set(refreshResult.getOrThrow())
                                        Timber.tag("GOG").i("Secure links refreshed successfully")
                                    }
                                }
                                refreshAttempts++
                                continue
                            }

                            throw exception ?: Exception("Failed to download chunk $chunkMd5")
                        }
                    }

                    val workers = minOf(MAX_PARALLEL_DOWNLOADS, totalChunks).coerceAtLeast(1)
                    coroutineScope {
                        (0 until workers)
                            .map {
                                async {
                                    while (true) {
                                        if (!downloadInfo.isActive()) {
                                            throw kotlinx.coroutines.CancellationException("Download cancelled")
                                        }

                                        val index = nextChunkIndex.getAndIncrement()
                                        if (index >= totalChunks) break

                                        val chunkMd5 = chunks[index]
                                        downloadOneChunk(chunkMd5)

                                        val completed = downloadedChunks.incrementAndGet()
                                        val progress = completed.toFloat() / totalChunks
                                        downloadInfo.setProgress(progress)
                                        downloadInfo.updateStatusMessage("Downloading chunks ($completed/$totalChunks)")
                                        downloadInfo.emitProgressChange()
                                        progressSink?.invoke(
                                            downloadInfo.getBytesDownloaded(),
                                            downloadInfo.getTotalExpectedBytes(),
                                        )

                                        Timber.tag("GOG").d("Progress: ${(progress * 100).toInt()}% ($completed/$totalChunks chunks)")
                                    }
                                }
                            }.awaitAll()
                    }

                    Timber.tag("GOG").i("All $totalChunks chunks downloaded successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "Failed to download chunks")
                    Result.failure(e)
                }
            }

        /**
         * Downloads the dependencies for a given game by using the dependency array from the game's depo-list
         * It will download the dependencies using the dependency URL
         *
         */
        private suspend fun downloadDependencies(
            gameId: String,
            dependencies: List<String>,
            gameDir: File,
            supportDir: File,
            downloadInfo: DownloadInfo,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    if (dependencies.isEmpty()) {
                        Timber.tag("GOG").d("No dependencies to download")
                        return@withContext Result.success(Unit)
                    }

                    Timber.tag("GOG").i("Downloading ${dependencies.size} dependencies: ${dependencies.joinToString()}")

                    val dependencyRepositoryResult = apiClient.fetchDependencyRepository(DEPENDENCY_URL)
                    if (dependencyRepositoryResult.isFailure) {
                        return@withContext Result.failure(
                            dependencyRepositoryResult.exceptionOrNull() ?: Exception("Failed to fetch Dependency repository"),
                        )
                    }

                    val repositoryManifestUrl = dependencyRepositoryResult.getOrThrow().repositoryManifest
                    if (repositoryManifestUrl.isBlank()) {
                        return@withContext Result.failure(Exception("Empty repository manifest URL"))
                    }

                    // Get the decompressed manifest
                    val dependencyManifestResult = apiClient.fetchDependencyManifest(repositoryManifestUrl)
                    if (dependencyManifestResult.isFailure) {
                        return@withContext Result.failure(
                            dependencyManifestResult.exceptionOrNull() ?: Exception("Failed to fetch Dependency manifest"),
                        )
                    }

                    val dependencyManifest = dependencyManifestResult.getOrThrow()

                    // Filter depots by the dependencyId so we only install what we need (e.g., MSVC2013)
                    val filteredDepots =
                        dependencyManifest.depots.filter { depot ->
                            dependencies.contains(depot.dependencyId)
                        }

                    if (filteredDepots.isEmpty()) {
                        Timber.tag("GOG").w("No matching dependency depots found for: ${dependencies.joinToString()}")
                        return@withContext Result.success(Unit)
                    }

                    Timber.tag("GOG").d("Found ${filteredDepots.size} dependency depot(s) to download")

                    val openLinkResult = apiClient.getDependencyOpenLink()
                    if (openLinkResult.isFailure) {
                        return@withContext Result.failure(
                            openLinkResult.exceptionOrNull() ?: Exception("Failed to get dependency open link"),
                        )
                    }

                    val dependencyBaseUrls = openLinkResult.getOrThrow()
                    if (dependencyBaseUrls.isEmpty()) {
                        return@withContext Result.failure(Exception("No dependency URLs returned"))
                    }

                    Timber.tag("GOG").d("Got ${dependencyBaseUrls.size} dependency base URL(s)")

                    // Download each dependency
                    for ((index, depot) in filteredDepots.withIndex()) {
                        downloadInfo.updateStatusMessage(
                            "Downloading dependency ${index + 1}/${filteredDepots.size}: ${depot.readableName}",
                        )

                        Timber.tag("GOG").i("Downloading dependency: ${depot.readableName} (${depot.dependencyId})")

                        // If path starts with __redist, install to supportDir, otherwise install to gameDir
                        val installBaseDir =
                            if (depot.executable?.path?.startsWith("__redist") == true) {
                                Timber.tag("GOG").d("Dependency ${depot.dependencyId} has __redist path, installing to supportDir")
                                supportDir
                            } else {
                                Timber.tag("GOG").d("Dependency ${depot.dependencyId} has no __redist path, installing to gameDir")
                                gameDir
                            }

                        // Fetch depot manifest to get file list using open link URLs
                        val depotManifestResult = apiClient.fetchDependencyDepotManifest(depot.manifest, dependencyBaseUrls)
                        if (depotManifestResult.isFailure) {
                            Timber
                                .tag(
                                    "GOG",
                                ).w(
                                    "Failed to fetch depot manifest for ${depot.readableName}: ${depotManifestResult.exceptionOrNull()?.message}",
                                )
                            continue
                        }

                        val depotManifest = depotManifestResult.getOrThrow()
                        val depotFiles = depotManifest.files

                        if (depotFiles.isEmpty()) {
                            Timber.tag("GOG").w("No files in dependency depot: ${depot.readableName}")
                            continue
                        }

                        val chunkHashes = parser.extractChunkHashes(depotFiles)

                        val chunkUrlMap = buildChunkUrlMap(chunkHashes, dependencyBaseUrls)

                        // Create cache directory for this dependency
                        val depotCacheDir = File(installBaseDir, ".gog_dep_${depot.dependencyId}")
                        depotCacheDir.mkdirs()

                        // Download chunks
                        val downloadResult = downloadChunksSimple(chunkUrlMap, depotCacheDir, downloadInfo)
                        if (downloadResult.isFailure) {
                            Timber
                                .tag(
                                    "GOG",
                                ).w("Failed to download chunks for ${depot.readableName}: ${downloadResult.exceptionOrNull()?.message}")
                            continue
                        }

                        // Assemble files - the file paths in the manifest already contain the full directory structure
                        // so we use installBaseDir directly without adding depot.dependencyId
                        val depotInstallDir = installBaseDir
                        depotInstallDir.mkdirs()

                        // Strip __redist/ prefix from file paths if they're being installed to supportDir
                        // This prevents paths like supportDir/__redist/DirectX and gives us supportDir/DirectX instead
                        val filesToAssemble =
                            if (installBaseDir == supportDir) {
                                depotFiles.map { file ->
                                    if (file.path.startsWith("__redist/")) {
                                        file.copy(path = file.path.removePrefix("__redist/"))
                                    } else {
                                        file
                                    }
                                }
                            } else {
                                depotFiles
                            }

                        val assembleResult = assembleFiles(filesToAssemble, depotCacheDir, depotInstallDir, downloadInfo)
                        if (assembleResult.isFailure) {
                            Timber
                                .tag(
                                    "GOG",
                                ).w("Failed to assemble files for ${depot.readableName}: ${assembleResult.exceptionOrNull()?.message}")
                            continue
                        }

                        // Cleanup cache
                        depotCacheDir.deleteRecursively()

                        Timber.tag("GOG").i("Successfully downloaded dependency: ${depot.readableName} to ${depotInstallDir.absolutePath}")
                    }

                    Timber.tag("GOG").i("Completed downloading ${filteredDepots.size} dependencies")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "Failed to download dependencies")
                    Result.failure(e)
                }
            }

        suspend fun downloadDependenciesWithProgress(
            gameId: String,
            dependencies: List<String>,
            gameDir: File,
            supportDir: File,
            onProgress: ((Float) -> Unit)? = null,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                if (dependencies.isEmpty()) return@withContext Result.success(Unit)
                val downloadInfo =
                    DownloadInfo(
                        jobCount = 1,
                        gameId = 0,
                        downloadingAppIds = CopyOnWriteArrayList(),
                    )
                onProgress?.let { downloadInfo.addProgressListener(it) }
                val result =
                    downloadDependencies(
                        gameId = gameId,
                        dependencies = dependencies,
                        gameDir = gameDir,
                        supportDir = supportDir,
                        downloadInfo = downloadInfo,
                    )
                if (result.isSuccess) {
                    onProgress?.invoke(1f)
                }
                result
            }

        /**
         * Build chunk URL map using base URLs
         */
        private fun buildChunkUrlMap(
            chunkHashes: List<String>,
            baseUrls: List<String>,
        ): Map<String, String> {
            val chunkUrlMap = mutableMapOf<String, String>()
            val baseUrl = baseUrls.firstOrNull() ?: return emptyMap()
            // Ensure base URL ends with / for proper concatenation
            val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            chunkHashes.forEach { hash ->
                // Build GOG Galaxy path format: AA/BB/CCDD...
                val galaxyPath =
                    if (hash.length >= 4) {
                        "${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash"
                    } else {
                        hash
                    }
                chunkUrlMap[hash] = "$normalizedBaseUrl$galaxyPath"
            }

            return chunkUrlMap
        }

        /**
         * Simplified chunk download without retry and secure link refresh
         * Used for dependencies which use open links
         */
        private suspend fun downloadChunksSimple(
            chunkUrlMap: Map<String, String>,
            chunkCacheDir: File,
            downloadInfo: DownloadInfo,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val chunks = chunkUrlMap.entries.toList()
                    val totalChunks = chunks.size
                    val nextChunkIndex = AtomicInteger(0)
                    val downloadedChunks = AtomicInteger(0)

                    downloadInfo.setProgress(0f)
                    downloadInfo.setActive(true)
                    downloadInfo.emitProgressChange()

                    if (totalChunks == 0) {
                        return@withContext Result.success(Unit)
                    }

                    val workers = minOf(MAX_PARALLEL_DOWNLOADS, totalChunks).coerceAtLeast(1)
                    coroutineScope {
                        (0 until workers)
                            .map {
                                async {
                                    while (true) {
                                        if (!downloadInfo.isActive()) {
                                            throw kotlinx.coroutines.CancellationException("Download cancelled")
                                        }

                                        val index = nextChunkIndex.getAndIncrement()
                                        if (index >= totalChunks) break

                                        val (chunkMd5, url) = chunks[index]
                                        val result = downloadChunk(chunkMd5, url, chunkCacheDir, downloadInfo)
                                        if (result.isFailure) {
                                            throw result.exceptionOrNull() ?: Exception("Failed to download chunk $chunkMd5")
                                        }

                                        val completed = downloadedChunks.incrementAndGet()
                                        downloadInfo.setProgress(completed.toFloat() / totalChunks)
                                        downloadInfo.emitProgressChange()
                                    }
                                }
                            }.awaitAll()
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "Failed to download dependency chunks")
                    Result.failure(e)
                }
            }

        /**
         * Refresh secure CDN links when they expire
         *
         * @param context Context containing info needed to fetch new links
         * @param chunkHashes List of chunk hashes needed
         * @return New chunk URL map with fresh secure links
         */
        private suspend fun refreshSecureLinks(
            context: SecureLinkContext,
            chunkHashes: List<String>,
        ): Result<Map<String, String>> =
            withContext(Dispatchers.IO) {
                try {
                    val productUrlMap = mutableMapOf<String, List<String>>()

                    // Get secure links for each product in parallel.
                    val refreshed =
                        coroutineScope {
                            context.productIds
                                .map { productId ->
                                    async {
                                        productId to
                                            apiClient.getSecureLink(
                                                productId = productId,
                                                path = "/",
                                                generation = context.generation,
                                            )
                                    }
                                }.awaitAll()
                        }
                    for ((productId, linksResult) in refreshed) {
                        if (linksResult.isSuccess) {
                            productUrlMap[productId] = linksResult.getOrThrow().urls
                        } else {
                            return@withContext Result.failure(
                                linksResult.exceptionOrNull() ?: Exception("Failed to refresh secure links for product $productId"),
                            )
                        }
                    }

                    Timber.tag("GOG").d("Refreshed secure links for ${productUrlMap.size} product(s)")

                    // Rebuild chunk URL map with new secure links
                    val newChunkUrlMap = parser.buildChunkUrlMapWithProducts(chunkHashes, context.chunkToProductMap, productUrlMap)
                    Result.success(newChunkUrlMap)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "Failed to refresh secure links")
                    Result.failure(e)
                }
            }

        /**
         * Download a single chunk with retry logic
         *
         * @param chunkMd5 Compressed MD5 hash (chunk identifier)
         * @param url Secure CDN URL (time-limited)
         * @param chunkCacheDir Cache directory
         * @param downloadInfo Progress tracker
         */
        private suspend fun downloadChunkWithRetry(
            chunkMd5: String,
            url: String,
            chunkCacheDir: File,
            downloadInfo: DownloadInfo,
        ): Result<File> =
            withContext(Dispatchers.IO) {
                var lastException: Exception? = null

                repeat(MAX_CHUNK_RETRIES) { attempt ->
                    val result = downloadChunk(chunkMd5, url, chunkCacheDir, downloadInfo)

                    if (result.isSuccess) {
                        if (attempt > 0) {
                            Timber.tag("GOG").i("Chunk $chunkMd5 downloaded successfully after ${attempt + 1} attempts")
                        }
                        return@withContext result
                    }

                    lastException = result.exceptionOrNull() as? Exception

                    if (attempt < MAX_CHUNK_RETRIES - 1) {
                        val delay = RETRY_DELAY_MS * (1 shl attempt) // Exponential backoff: 1s, 2s, 4s
                        Timber
                            .tag(
                                "GOG",
                            ).w(
                                "Chunk $chunkMd5 download failed (attempt ${attempt + 1}/$MAX_CHUNK_RETRIES): ${lastException?.message}. Retrying in ${delay}ms",
                            )
                        kotlinx.coroutines.delay(delay)
                    }
                }

                Timber.tag("GOG").e(lastException, "Failed to download chunk $chunkMd5 after $MAX_CHUNK_RETRIES attempts")
                Result.failure(lastException ?: Exception("Failed to download chunk $chunkMd5"))
            }

        /**
         * Download a single chunk from GOG CDN
         *
         * @param chunkMd5 Compressed MD5 hash (chunk identifier)
         * @param url Secure CDN URL (time-limited)
         * @param chunkCacheDir Cache directory
         * @param downloadInfo Progress tracker
         */
        private suspend fun downloadChunk(
            chunkMd5: String,
            url: String,
            chunkCacheDir: File,
            downloadInfo: DownloadInfo,
        ): Result<File> =
            withContext(Dispatchers.IO) {
                val chunkFile = File(chunkCacheDir, "$chunkMd5.chunk")

                // Cache hit: chunk files are only renamed to the final name after the streamed MD5
                // matched, so existence implies verification — no need to re-hash.
                if (chunkFile.exists()) {
                    return@withContext Result.success(chunkFile)
                }

                val tempFile = File(chunkCacheDir, "$chunkMd5.chunk.tmp")
                tempFile.delete()

                try {
                    Timber.tag("GOG").d("Downloading chunk $chunkMd5 from: $url")

                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .header("User-Agent", "GOG Galaxy")
                            .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.tag("GOG").e("HTTP ${response.code} for chunk $chunkMd5 from URL: $url")
                            return@withContext Result.failure(
                                HttpStatusException(response.code, "HTTP ${response.code} downloading chunk $chunkMd5"),
                            )
                        }

                        val body = response.body
                            ?: return@withContext Result.failure(Exception("Empty response for chunk $chunkMd5"))

                        val digest = MessageDigest.getInstance("MD5")
                        var cancelled = false

                        DigestOutputStream(BufferedOutputStream(FileOutputStream(tempFile)), digest).use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    if (!downloadInfo.isActive() || downloadInfo.isCancelling) {
                                        cancelled = true
                                        break
                                    }
                                    out.write(buffer, 0, bytesRead)
                                    downloadInfo.updateBytesDownloaded(bytesRead.toLong())
                                }
                            }
                        }

                        if (cancelled) {
                            tempFile.delete()
                            return@withContext Result.failure(
                                kotlinx.coroutines.CancellationException("Download cancelled mid-chunk"),
                            )
                        }

                        val actualMd5 = digest.digest().joinToString("") { "%02x".format(it) }
                        if (actualMd5 != chunkMd5) {
                            tempFile.delete()
                            return@withContext Result.failure(
                                Exception("Compressed MD5 mismatch for chunk: expected $chunkMd5, got $actualMd5"),
                            )
                        }

                        if (!tempFile.renameTo(chunkFile)) {
                            // Fall back: copy then delete. Rare; usually only happens across filesystems.
                            try {
                                tempFile.inputStream().use { input ->
                                    FileOutputStream(chunkFile).use { output -> input.copyTo(output) }
                                }
                                tempFile.delete()
                            } catch (e: Exception) {
                                tempFile.delete()
                                chunkFile.delete()
                                return@withContext Result.failure(Exception("Failed to finalize chunk $chunkMd5", e))
                            }
                        }

                        Result.success(chunkFile)
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    Timber.tag("GOG").e(e, "Failed to download chunk $chunkMd5")
                    Result.failure(e)
                }
            }

        /**
         * Assemble files from downloaded chunks
         *
         * @param files List of files to assemble
         * @param chunkCacheDir Directory containing downloaded chunks
         * @param installDir Target installation directory
         * @param downloadInfo Progress tracker
         */
        private suspend fun assembleFiles(
            files: List<DepotFile>,
            chunkCacheDir: File,
            installDir: File,
            downloadInfo: DownloadInfo,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val totalFiles = files.size

                    for ((index, file) in files.withIndex()) {
                        if (!downloadInfo.isActive()) {
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }
                      
                        downloadInfo.updateStatusMessage(
                            context.getString(R.string.downloads_queue_phase_unpacking) +
                                " ${index + 1}/$totalFiles: ${file.path}",
                        )

                        val assembleResult = assembleFile(file, chunkCacheDir, installDir)
                        if (assembleResult.isFailure) {
                            return@withContext Result.failure(
                                assembleResult.exceptionOrNull() ?: Exception("Failed to assemble ${file.path}"),
                            )
                        }
                    }

                    Timber.tag("GOG").i("Assembled $totalFiles file(s) successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "Failed to assemble files")
                    Result.failure(e)
                }
            }

        /**
         * Assemble a single file from its chunks
         *
         * @param file File metadata with chunks
         * @param chunkCacheDir Directory containing downloaded chunks
         * @param installDir Target installation directory
         */
        private suspend fun assembleFile(
            file: DepotFile,
            chunkCacheDir: File,
            installDir: File,
        ): Result<File> =
            withContext(Dispatchers.IO) {
                try {
                    val outputFile = File(installDir, file.path)
                    outputFile.parentFile?.mkdirs()

                    BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        for (chunk in file.chunks) {
                            val chunkFile = File(chunkCacheDir, "${chunk.compressedMd5}.chunk")
                            if (!chunkFile.exists()) {
                                return@withContext Result.failure(
                                    Exception("Chunk file missing: ${chunk.compressedMd5}"),
                                )
                            }

                            val digest = MessageDigest.getInstance("MD5")
                            var bytesEmitted = 0L

                            try {
                                BufferedInputStream(chunkFile.inputStream()).use { rawIn ->
                                    val decompressedStream: InputStream =
                                        if (chunk.compressedSize == null) rawIn else InflaterInputStream(rawIn)
                                    DigestInputStream(decompressedStream, digest).use { input ->
                                        var bytesRead: Int
                                        while (input.read(buffer).also { bytesRead = it } != -1) {
                                            output.write(buffer, 0, bytesRead)
                                            bytesEmitted += bytesRead
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Treat decode failures as a poisoned cache entry and let the next
                                // install attempt re-download the chunk fresh.
                                chunkFile.delete()
                                return@withContext Result.failure(
                                    Exception("Failed to decompress chunk ${chunk.compressedMd5}", e),
                                )
                            }

                            if (bytesEmitted != chunk.size) {
                                chunkFile.delete()
                                return@withContext Result.failure(
                                    Exception("Decompressed size mismatch for chunk ${chunk.compressedMd5}: expected ${chunk.size}, got $bytesEmitted"),
                                )
                            }

                            val actualMd5 = digest.digest().joinToString("") { "%02x".format(it) }
                            if (actualMd5 != chunk.md5) {
                                chunkFile.delete()
                                return@withContext Result.failure(
                                    Exception("Decompressed MD5 mismatch for chunk: expected ${chunk.md5}, got $actualMd5"),
                                )
                            }
                        }
                    }

                    // Verify final file hash if provided
                    if (file.md5 != null) {
                        val fileMd5 = calculateMd5File(outputFile)
                        if (fileMd5 != file.md5) {
                            Timber.tag("GOG").w("File MD5 mismatch: ${file.path}, expected ${file.md5}, got $fileMd5")
                            // Don't fail - some games have incorrect MD5 in manifest
                        }
                    }

                    Timber.tag("GOG").d("Assembled: ${file.path} (${outputFile.length()} bytes)")
                    Result.success(outputFile)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "Failed to assemble file ${file.path}")
                    Result.failure(e)
                }
            }

        /**
         * Check if file exists and has the expected size. When [expectedMd5] is non-null/non-blank,
         * also verifies content MD5 to reject corrupted files; short-circuits on size mismatch before hashing.
         */
        private fun fileExistsWithCorrectSize(
            outputFile: File,
            expectedSize: Long,
            expectedMd5: String? = null,
        ): Boolean {
            if (!outputFile.exists()) return false
            if (outputFile.length() != expectedSize) return false
            return expectedMd5.isNullOrBlank() || calculateMd5File(outputFile).equals(expectedMd5, ignoreCase = true)
        }

        internal fun isInstalledFileValidByChunkMd5(
            outputFile: File,
            file: DepotFile,
        ): Boolean {
            if (!outputFile.exists() || !outputFile.isFile) return false
            val expectedSize = file.chunks.sumOf { it.size }
            if (outputFile.length() != expectedSize) return false
            if (file.chunks.isEmpty()) return true

            return try {
                BufferedInputStream(outputFile.inputStream()).use { input ->
                    for (chunk in file.chunks) {
                        val expectedMd5 = chunk.md5
                        if (expectedMd5.isBlank()) {
                            // No chunk hash to compare — skip past this chunk's bytes and trust size.
                            var remaining = chunk.size
                            val skipBuffer = ByteArray(STREAM_BUFFER_SIZE)
                            while (remaining > 0) {
                                val toRead = minOf(skipBuffer.size.toLong(), remaining).toInt()
                                val read = input.read(skipBuffer, 0, toRead)
                                if (read < 0) return false
                                remaining -= read
                            }
                            continue
                        }
                        val digest = MessageDigest.getInstance("MD5")
                        var remaining = chunk.size
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        while (remaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read < 0) return false
                            digest.update(buffer, 0, read)
                            remaining -= read
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(expectedMd5, ignoreCase = true)) {
                            Timber.tag("GOG").d("Chunk MD5 mismatch for ${file.path}: expected $expectedMd5, got $actual")
                            return false
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Timber.tag("GOG").w(e, "Error verifying chunks for ${outputFile.absolutePath}")
                false
            }
        }

        /**
         * Calculate MD5 hash of file
         */
        private fun calculateMd5File(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun calculateDirectorySize(directory: File): Long {
            var size = 0L
            try {
                if (!directory.exists() || !directory.isDirectory) {
                    return 0L
                }

                val files = directory.listFiles() ?: return 0L
                for (file in files) {
                    size +=
                        if (file.isDirectory) {
                            calculateDirectorySize(file)
                        } else {
                            file.length()
                        }
                }
            } catch (e: Exception) {
                Timber.tag("GOG").w(e, "Error calculating directory size for ${directory.name}")
            }
            return size
        }
    }
