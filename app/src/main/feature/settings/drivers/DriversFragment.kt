package com.winlator.cmod.feature.settings
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.app.shell.UnifiedActivity
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import com.winlator.cmod.runtime.content.Downloader
import com.winlator.cmod.runtime.content.DriverPackages
import com.winlator.cmod.runtime.content.DriverPackages.Platform
import com.winlator.cmod.runtime.linux.LinuxRuntime
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.toast.WinToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class DriversFragment : Fragment() {
    private lateinit var adrenotoolsManager: AdrenotoolsManager
    private lateinit var preferences: SharedPreferences

    private var platform = Platform.ANDROID
    private var driversState by mutableStateOf(DriversState())

    private val releasesBySource = linkedMapOf<String, List<DriverReleaseItem>>()
    private var sources = mutableListOf<DriverRepo>()
    private var installedDrivers: List<InstalledDriverItem> = emptyList()
    private var installedAssetNames: Set<String> = emptySet()
    private var expandedSourceApiUrl: String? = null
    private var expandedReleaseId: Long? = null
    private var loadingSourceApiUrl: String? = null
    private var downloadProgress: DownloadProgress? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform =
            savedInstanceState?.getString("driverPlatform")?.let { runCatching { Platform.valueOf(it) }.getOrNull() } ?: Platform.ANDROID
        adrenotoolsManager = AdrenotoolsManager(requireContext())
        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.settings_drivers_manager)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        loadRepos()
        refreshInstalledDrivers()
        publishState()

        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinNativeTheme(
                    colorScheme =
                        darkColorScheme(
                            primary = Color(0xFF1A9FFF),
                            background = Color(0xFF141B24),
                            surface = Color(0xFF1E252E),
                        ),
                ) {
                    DriversScreen(
                        state = driversState,
                        onInstallFromFile = { selected ->
                            selectPlatform(selected)
                            promptInstallDriverFromFile()
                        },
                        onPlatformSelected = { selectPlatform(it) },
                        onSelectDriver = { driver ->
                            DriverPackages.selectLinux(requireContext(), driver.id)
                            refreshInstalledDrivers()
                            publishState()
                        },
                        onSourceTapped = { source -> onSourceSelected(source) },
                        onReleaseTapped = { release ->
                            expandedReleaseId = if (expandedReleaseId == release.id) null else release.id
                            publishState()
                        },
                        onDownloadAsset = { asset -> downloadReleaseAsset(asset) },
                        onRemoveDriver = { driver ->
                            if (platform == Platform.LINUX) {
                                DriverPackages.removeLinux(requireContext(), driver.id)
                            } else {
                                adrenotoolsManager.removeDriver(driver.id)
                            }
                            refreshInstalledDrivers()
                            publishState()
                        },
                        onRepoAdded = { name, apiUrl ->
                            val normalized = normalizeRepoInput(name, apiUrl)
                            sources.add(normalized)
                            saveRepos()
                            publishState()
                        },
                        onRepoUpdated = { index, name, apiUrl ->
                            if (index in sources.indices) {
                                val normalized = normalizeRepoInput(name, apiUrl)
                                sources[index] = normalized
                                releasesBySource.remove(sources[index].apiUrl)
                                saveRepos()
                                publishState()
                            }
                        },
                        onRepoDeleted = { index ->
                            if (index in sources.indices) {
                                val removed = sources.removeAt(index)
                                releasesBySource.remove(removed.apiUrl)
                                if (expandedSourceApiUrl == removed.apiUrl) expandedSourceApiUrl = null
                                if (loadingSourceApiUrl == removed.apiUrl) loadingSourceApiUrl = null
                                saveRepos()
                                publishState()
                            }
                        },
                        onRestoreDefaultRepos = { restoreDefaultRepos() },
                        bridge = (requireActivity() as? UnifiedActivity)?.settingsNavBridge,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("driverPlatform", platform.name)
    }

    override fun onResume() {
        super.onResume()
        refreshInstalledDrivers()
        publishState()
    }

    private fun selectPlatform(selected: Platform) {
        if (platform == selected) return
        platform = selected
        expandedSourceApiUrl = null
        expandedReleaseId = null
        loadRepos()
        refreshInstalledDrivers()
        publishState()
    }

    private fun promptInstallDriverFromFile() {
        val activity = activity ?: return
        DirectoryPickerDialog.showFile(
            activity = activity,
            title = getString(R.string.settings_drivers_install),
            allowedExtensions = setOf("zip"),
        ) { path ->
            installDriverPackage(Uri.fromFile(File(path)))
        }
    }

    private fun publishState() {
        val existingApiUrls = sources.map { it.apiUrl }.toHashSet()
        val hasMissingDefaults = defaultRepoList().any { it.apiUrl !in existingApiUrls }
        driversState =
            DriversState(
                platform = platform,
                installedDrivers = installedDrivers,
                sources = sources.toList(),
                releasesBySource =
                    releasesBySource.mapValues { (_, releases) ->
                        releases.mapNotNull { release ->
                            val assets = release.assets.filter { (it.name.startsWith("WN-Linux-")) == (platform == Platform.LINUX) }
                            if (assets.isEmpty()) null else release.copy(assets = assets)
                        }
                    },
                expandedSourceApiUrl = expandedSourceApiUrl,
                expandedReleaseId = expandedReleaseId,
                loadingSourceApiUrl = loadingSourceApiUrl,
                hasMissingDefaults = hasMissingDefaults,
                installedAssetNames = installedAssetNames,
                downloadProgress = downloadProgress,
            )
    }

    private fun refreshInstalledDrivers() {
        if (platform == Platform.LINUX) {
            val context = requireContext()
            val selected = DriverPackages.selectedLinux(context)
            val custom =
                DriverPackages.linuxDrivers(context).mapNotNull { directory ->
                    runCatching {
                        val meta = JSONObject(File(directory, "meta.json").readText())
                        InstalledDriverItem(
                            id = directory.name,
                            name = meta.getString("name"),
                            version = meta.optString("driverVersion"),
                            selected =
                                selected == directory.name,
                        ) to
                            meta.optString("sourceAsset")
                    }.getOrNull()
                }
            installedDrivers =
                buildList {
                    add(
                        InstalledDriverItem(
                            id = DriverPackages.BUNDLED,
                            name = getString(R.string.settings_drivers_bundled),
                            version = LinuxRuntime.TURNIP_VERSION,
                            selected =
                                selected == DriverPackages.BUNDLED,
                            removable = false,
                        ),
                    )
                    if (LinuxRuntime.downloadedDriverVersion(context) > 0) {
                        add(
                            InstalledDriverItem(
                                id = DriverPackages.LEGACY,
                                name = getString(R.string.settings_drivers_legacy),
                                version =
                                    File(LinuxRuntime.driverDir(context), LinuxRuntime.DRIVER_NAME_FILE)
                                        .takeIf {
                                            it.isFile
                                        }?.readText()
                                        ?.trim()
                                        .orEmpty(),
                                selected =
                                    selected == DriverPackages.LEGACY,
                            ),
                        )
                    }
                    addAll(custom.map { it.first }.sortedBy { it.name.lowercase(Locale.ROOT) })
                }
            installedAssetNames = custom.map { it.second }.filter { it.isNotBlank() }.toSet()
            return
        }
        val driverIds = adrenotoolsManager.enumarateInstalledDrivers()
        installedDrivers =
            driverIds
                .map { driverId ->
                    InstalledDriverItem(
                        id = driverId,
                        name = adrenotoolsManager.getDriverName(driverId).ifBlank { driverId },
                        version = adrenotoolsManager.getDriverVersion(driverId),
                    )
                }.sortedBy { it.name.lowercase(Locale.getDefault()) }
        installedAssetNames =
            driverIds
                .mapNotNull { id -> adrenotoolsManager.getSourceAsset(id).takeIf { it.isNotBlank() } }
                .toSet()
    }

    private fun defaultRepoList(): List<DriverRepo> =
        listOf(
            DriverRepo(
                name = "WinNative Drivers",
                repoUrl = "https://github.com/WinNative-Emu/Drivers/releases",
                apiUrl = "https://api.github.com/repos/WinNative-Emu/Drivers/releases",
            ),
        ) +
            if (platform == Platform.LINUX) {
                emptyList()
            } else {
                listOf(
                    DriverRepo(
                        name = WINNATIVE_COMPONENTS_REPO_NAME,
                        repoUrl = WINNATIVE_COMPONENTS_REPO_URL,
                        apiUrl = WINNATIVE_COMPONENTS_API_URL,
                    ),
                )
            }

    private fun repoPreferenceKey() = if (platform == Platform.LINUX) "custom_linux_driver_repos" else "custom_driver_repos"

    private fun loadRepos() {
        val jsonStr = preferences.getString(repoPreferenceKey(), null)
        val newSources = mutableListOf<DriverRepo>()

        if (jsonStr == null) {
            newSources.addAll(defaultRepoList())
        } else {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    newSources.add(
                        DriverRepo(
                            name = obj.optString("name", "Unknown Repo"),
                            repoUrl = obj.optString("repoUrl", ""),
                            apiUrl = obj.optString("apiUrl", ""),
                        ),
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        sources = newSources
    }

    private fun restoreDefaultRepos() {
        val existingApiUrls = sources.map { it.apiUrl }.toHashSet()
        var added = 0
        defaultRepoList().forEach { default ->
            if (default.apiUrl !in existingApiUrls) {
                sources.add(default)
                added += 1
            }
        }
        if (added > 0) {
            saveRepos()
            publishState()
        } else {
            WinToast.show(requireContext(), R.string.settings_drivers_defaults_present)
        }
    }

    private fun saveRepos() {
        try {
            val array = JSONArray()
            sources.forEach { source ->
                val obj = JSONObject()
                obj.put("name", source.name)
                obj.put("repoUrl", source.repoUrl)
                obj.put("apiUrl", source.apiUrl)
                array.put(obj)
            }
            preferences
                .edit()
                .putString(repoPreferenceKey(), array.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun normalizeRepoInput(
        name: String,
        rawUrl: String,
    ): DriverRepo {
        var url = rawUrl
        if (url.startsWith("https://github.com/") && !url.contains("api.github.com")) {
            url = url.replace("https://github.com/", "https://api.github.com/repos/")
            if (!url.endsWith("/releases")) {
                url = "$url/releases"
            }
        }
        val repoUrl = url.replace("api.github.com/repos", "github.com")
        return DriverRepo(name = name, repoUrl = repoUrl, apiUrl = url)
    }

    private fun onSourceSelected(source: DriverRepo) {
        if (loadingSourceApiUrl == source.apiUrl) return

        if (expandedSourceApiUrl == source.apiUrl) {
            expandedSourceApiUrl = null
            expandedReleaseId = null
            publishState()
            return
        }

        expandedSourceApiUrl = source.apiUrl
        expandedReleaseId = null

        if (releasesBySource.containsKey(source.apiUrl)) {
            publishState()
            return
        }

        loadingSourceApiUrl = source.apiUrl
        publishState()

        viewLifecycleOwner.lifecycleScope.launch {
            val releases =
                withContext(Dispatchers.IO) {
                    runCatching { fetchGithubReleases(source) }.getOrElse { emptyList() }
                }

            if (!isAdded || view == null) return@launch

            if (loadingSourceApiUrl == source.apiUrl) {
                loadingSourceApiUrl = null
            }
            if (releases.isNotEmpty()) releasesBySource[source.apiUrl] = releases
            publishState()

            if (releases.isEmpty()) {
                WinToast.show(requireContext(), R.string.settings_drivers_repo_fetch_failed)
            }
        }
    }

    private fun fetchGithubReleases(source: DriverRepo): List<DriverReleaseItem> {
        if (!source.apiUrl.contains("api.github.com")) {
            return fetchReleasePage(source.apiUrl).second
        }

        val perPage = 100
        val maxPages = 2
        val items = mutableListOf<DriverReleaseItem>()
        var page = 1
        while (page <= maxPages) {
            val separator = if (source.apiUrl.contains("?")) "&" else "?"
            val pageUrl = "${source.apiUrl}${separator}per_page=$perPage&page=$page"
            val (rawCount, pageItems) = fetchReleasePage(pageUrl)
            items += pageItems
            if (rawCount < perPage) break
            page++
        }
        return items
    }

    private fun fetchReleasePage(apiUrl: String): Pair<Int, List<DriverReleaseItem>> {
        val connection =
            (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WinNative")
            }

        return connection.useResponse { responseText ->
            val json = JSONArray(responseText)
            val parsed =
                buildList {
                    for (index in 0 until json.length()) {
                        val releaseObject = json.optJSONObject(index) ?: continue
                        val assets = releaseObject.optJSONArray("assets").toZipAssets()
                        if (assets.isEmpty()) continue

                        val tagName = releaseObject.optString("tag_name")
                        val releaseName = releaseObject.optString("name").ifBlank { tagName }
                        val publishedAt = releaseObject.optString("published_at")
                        val releaseNotes = releaseObject.optString("body").toReleaseNotes()

                        add(
                            DriverReleaseItem(
                                id = releaseObject.optLong("id"),
                                title = releaseName.ifBlank { getString(R.string.common_ui_unnamed) },
                                subtitle = buildReleaseSubtitle(tagName, publishedAt, assets.size),
                                notes = releaseNotes,
                                assets = assets,
                            ),
                        )
                    }
                }
            json.length() to parsed
        }
    }

    private fun JSONArray?.toZipAssets(): List<DriverAssetItem> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val assetObject = optJSONObject(index) ?: continue
                val assetName = assetObject.optString("name").trim()
                val downloadUrl = assetObject.optString("browser_download_url")
                if (!assetName.lowercase(Locale.ROOT).endsWith(".zip") || downloadUrl.isBlank()) continue

                add(
                    DriverAssetItem(
                        id = assetObject.optLong("id"),
                        name = assetName,
                        downloadUrl = downloadUrl,
                        sizeLabel = formatBytes(assetObject.optLong("size")),
                    ),
                )
            }
        }
    }

    private fun buildReleaseSubtitle(
        tagName: String,
        publishedAt: String,
        assetCount: Int,
    ): String {
        val parts = mutableListOf<String>()
        if (tagName.isNotBlank()) parts += tagName
        if (publishedAt.isNotBlank()) {
            runCatching {
                val formattedDate =
                    OffsetDateTime.parse(publishedAt).format(
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM),
                    )
                parts += formattedDate
            }
        }
        parts += resources.getQuantityString(R.plurals.github_repo_asset_count, assetCount, assetCount)
        return parts.joinToString("  •  ")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return getString(R.string.settings_drivers_repo_unknown_size)
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.toInt()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    private fun String.toReleaseNotes(): String =
        lineSequence()
            .map { line -> line.trim() }
            .dropWhile { it.isBlank() }
            .map { line ->
                line
                    .removePrefix("#")
                    .removePrefix("##")
                    .removePrefix("###")
                    .removePrefix("- [ ] ")
                    .removePrefix("- [x] ")
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .trim()
            }.fold(mutableListOf<String>()) { acc, line ->
                if (line.isBlank()) {
                    if (acc.isNotEmpty() && acc.last().isNotBlank()) acc += ""
                } else {
                    acc += line
                }
                acc
            }.joinToString("\n")
            .trim()

    private fun downloadReleaseAsset(asset: DriverAssetItem) {
        if (asset.name in installedAssetNames) return
        if (downloadProgress != null) return

        val downloadTitle = getString(R.string.settings_content_downloading_title)
        downloadProgress =
            DownloadProgress(
                title = downloadTitle,
                assetName = asset.name,
                progress = 0f,
                indeterminate = true,
            )
        publishState()

        // Hold the app process alive across this download so screen lock can't
        // interrupt it. installDriverPackage owns its own keep-alive scope.
        val keepAliveTag = "drivers_download_${asset.downloadUrl}"
        val appCtx = requireContext().applicationContext
        com.winlator.cmod.runtime.system.SessionKeepAliveService
            .startDownload(appCtx, keepAliveTag)
        viewLifecycleOwner.lifecycleScope.launch {
            val output = File(requireContext().cacheDir, "driver_${System.currentTimeMillis()}.zip")
            val success =
                withContext(Dispatchers.IO) {
                    Downloader.downloadFile(asset.downloadUrl, output) { downloadedBytes, totalBytes ->
                        if (totalBytes <= 0L) {
                            updateDownloadProgress(
                                DownloadProgress(
                                    title = downloadTitle,
                                    assetName = asset.name,
                                    indeterminate = true,
                                ),
                            )
                            return@downloadFile
                        }
                        val fraction =
                            (downloadedBytes.toFloat() / totalBytes.toFloat())
                                .coerceIn(0f, 1f)
                        updateDownloadProgress(
                            DownloadProgress(
                                title = downloadTitle,
                                assetName = asset.name,
                                progress = fraction,
                                indeterminate = false,
                            ),
                        )
                    }
                }

            try {
                if (!isAdded || view == null) {
                    output.delete()
                    clearDownloadProgress()
                    return@launch
                }

                if (!success) {
                    output.delete()
                    clearDownloadProgress()
                    WinToast.show(requireContext(), R.string.settings_drivers_repo_download_failed)
                    return@launch
                }

                installDriverPackage(
                    uri = Uri.fromFile(output),
                    sourceAssetName = asset.name,
                    onComplete = { output.delete() },
                )
            } finally {
                com.winlator.cmod.runtime.system.SessionKeepAliveService
                    .stopDownload(appCtx, keepAliveTag)
            }
        }
    }

    private fun installDriverPackage(
        uri: Uri,
        sourceAssetName: String? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        val requestedPlatform = platform
        val installTitle = getString(R.string.settings_drivers_install)
        val installMessage = getString(R.string.settings_content_preparing_package)
        updateDownloadProgress(
            DownloadProgress(
                title = installTitle,
                assetName = sourceAssetName ?: installMessage,
                indeterminate = true,
            ),
        )

        // Keep the process alive across the install (driver extraction +
        // move). Released after the lifecycleScope coroutine finishes.
        val installKeepAliveTag = "drivers_install_${sourceAssetName ?: uri}"
        val appCtx = requireContext().applicationContext
        com.winlator.cmod.runtime.system.SessionKeepAliveService
            .startDownload(appCtx, installKeepAliveTag)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val installed =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            DriverPackages.install(appCtx, uri, sourceAssetName, null)
                        }.getOrNull()
                    }

                if (!isAdded || view == null) {
                    clearDownloadProgress()
                    onComplete?.invoke()
                    return@launch
                }

                clearDownloadProgress()
                onComplete?.invoke()

                if (installed == null) {
                    WinToast.show(requireContext(), R.string.settings_drivers_install_failed)
                    return@launch
                }

                if (installed.platform == Platform.ANDROID) SetupWizardActivity.recordInstalledDriver(requireContext(), installed.id)
                selectPlatform(installed.platform)
                if (installed.platform != requestedPlatform) {
                    val destination =
                        getString(
                            if (installed.platform ==
                                Platform.LINUX
                            ) {
                                R.string.settings_drivers_linux
                            } else {
                                R.string.settings_drivers_android
                            },
                        )
                    WinToast.show(requireContext(), getString(R.string.settings_drivers_routed, destination))
                }
                refreshInstalledDrivers()
                publishState()
            } finally {
                com.winlator.cmod.runtime.system.SessionKeepAliveService
                    .stopDownload(appCtx, installKeepAliveTag)
            }
        }
    }

    private fun updateDownloadProgress(progress: DownloadProgress) {
        downloadProgress = progress
        publishState()
    }

    private fun clearDownloadProgress() {
        downloadProgress = null
        publishState()
    }

    companion object {
        private const val WINNATIVE_COMPONENTS_REPO_NAME = "WinNative Components"
        private const val WINNATIVE_COMPONENTS_REPO_URL = "https://github.com/nicholasx417/WinNative-Components/releases"
        private const val WINNATIVE_COMPONENTS_API_URL = "https://api.github.com/repos/nicholasx417/WinNative-Components/releases"
    }
}

private inline fun <T> HttpURLConnection.useResponse(block: (String) -> T): T =
    try {
        val inputStream = if (responseCode in 200..299) inputStream else errorStream
        val body = inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (responseCode !in 200..299) {
            throw IllegalStateException(body.ifBlank { "GitHub request failed with HTTP $responseCode" })
        }
        block(body)
    } finally {
        disconnect()
    }
