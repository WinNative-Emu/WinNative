/* Components screen — Jetpack Compose host.
 * Hosts ComponentsScreen; orchestrates install / download / remove flows. */
package com.winlator.cmod.feature.settings
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.app.shell.UnifiedActivity
import com.winlator.cmod.runtime.container.ContainerCreation
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.content.Downloader
import com.winlator.cmod.runtime.linux.LinuxProtons
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.StorageUtils
import com.winlator.cmod.shared.ui.dialog.ContentDialog
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ContentsFragment : Fragment() {
    private lateinit var manager: ContentsManager

    private var componentsState by mutableStateOf(ComponentsState())
    private var currentPlatform = ComponentsPlatform.ANDROID
    private var currentContentType = ContentProfile.ContentType.CONTENT_TYPE_WINE

    private var profilesByKey = emptyMap<String, ContentProfile>()

    private val remoteSizeCache = mutableMapOf<String, Long>()
    private val remoteSizeFetchesInFlight = mutableSetOf<String>()
    private val installedSizeCache = mutableMapOf<String, Long>()
    private val installedSizeFetchesInFlight = mutableSetOf<String>()

    private var downloadProgress: ComponentsDownloadProgress? = null
    private var conflictingContentPath: String? = null
    private var isRefreshing = false
    private var loadFailed = false

    private var autoCreateContainer = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = ContentsManager(requireContext())

        savedInstanceState
            ?.getString(STATE_COMPONENTS_PLATFORM)
            ?.let { name -> runCatching { ComponentsPlatform.valueOf(name) }.getOrNull() }
            ?.let { currentPlatform = it }

        savedInstanceState
            ?.getString(STATE_CONTENT_TYPE)
            ?.let(ContentProfile.ContentType::getTypeByName)
            ?.let { currentContentType = it }

        autoCreateContainer =
            PreferenceManager
                .getDefaultSharedPreferences(requireContext())
                .getBoolean(PREF_AUTO_CREATE_CONTAINER, true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
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
                    val linuxProtons by LinuxProtons.state.collectAsState()
                    val linuxBuildsById = linuxProtons.builds.associateBy { it.id }
                    val linuxInstalled =
                        linuxProtons
                            .builds
                            .filter { it.id in linuxProtons.installed }
                            .map { build -> build.toLinuxComponentItem(linuxProtons) }
                    val linuxAvailable =
                        linuxProtons
                            .builds
                            .filterNot { it.id in linuxProtons.installed }
                            .map { build -> build.toLinuxComponentItem(linuxProtons) }
                    LaunchedEffect(currentPlatform) {
                        if (currentPlatform == ComponentsPlatform.LINUX) {
                            LinuxProtons.refresh(ctx)
                        }
                    }
                    ComponentsScreen(
                        bridge = (requireActivity() as? UnifiedActivity)?.settingsNavBridge,
                        state =
                            componentsState.copy(
                                linuxInstalled = linuxInstalled,
                                linuxAvailable = linuxAvailable,
                            ),
                        onPlatformSelected = { platform -> selectPlatform(platform) },
                        onTypeSelected = { type -> selectContentType(type) },
                        onInstallFromFile = { promptInstallFromFile() },
                        onDownloadItem = { item ->
                            profilesByKey[item.key]?.let { downloadRemoteContent(it) }
                        },
                        onRemoveItem = { item ->
                            profilesByKey[item.key]?.let { onRemoveRequested(it) }
                        },
                        onDownloadLinuxItem = { item ->
                            linuxBuildsById[item.key]?.let { LinuxProtons.install(ctx, it) }
                        },
                        onRemoveLinuxItem = { item ->
                            linuxBuildsById[item.key]?.let { LinuxProtons.remove(ctx, it) }
                        },
                        onDismissConflict = {
                            conflictingContentPath = null
                            publishState()
                        },
                        onToggleAutoCreateContainer = { enabled ->
                            autoCreateContainer = enabled
                            PreferenceManager
                                .getDefaultSharedPreferences(requireContext())
                                .edit()
                                .putBoolean(PREF_AUTO_CREATE_CONTAINER, enabled)
                                .apply()
                            publishState()
                        },
                        onRefresh = {
                            if (currentPlatform == ComponentsPlatform.LINUX) {
                                LinuxProtons.refresh(ctx)
                            } else {
                                refreshRemoteProfiles()
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.settings_content_components)
        syncAndPublish()
    }

    private fun syncAndPublish() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { manager.syncContents() }
            if (!isAdded || view == null) return@launch
            publishState()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRemoteProfiles()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_COMPONENTS_PLATFORM, currentPlatform.name)
        outState.putString(STATE_CONTENT_TYPE, currentContentType.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        context?.cacheDir?.let(FileUtils::clear)
        super.onDestroy()
    }

    // State management

    private fun selectContentType(type: ContentProfile.ContentType) {
        if (type == currentContentType) return
        currentContentType = type
        publishState()
    }

    private fun selectPlatform(platform: ComponentsPlatform) {
        if (platform == currentPlatform) return
        currentPlatform = platform
        if (platform == ComponentsPlatform.LINUX) {
            LinuxProtons.refresh(requireContext())
        }
        publishState()
    }

    private fun publishState() {
        val profiles = manager.getProfiles(currentContentType).orEmpty()

        val installed = profiles.filter { it.isInstalled }.sortedWith(CONTENT_ORDER)
        val available = profiles.filterNot { it.isInstalled }.sortedWith(CONTENT_ORDER)

        val keyedProfiles = linkedMapOf<String, ContentProfile>()
        val installedItems =
            installed.map { profile ->
                val item = profile.toItem()
                keyedProfiles[item.key] = profile
                item
            }
        val availableItems =
            available.map { profile ->
                val item = profile.toItem()
                keyedProfiles[item.key] = profile
                item
            }

        profilesByKey = keyedProfiles
        componentsState =
            ComponentsState(
                platform = currentPlatform,
                currentType = currentContentType,
                installed = installedItems,
                available = availableItems,
                downloadProgress = downloadProgress,
                conflict = conflictingContentPath?.let(::ComponentsConflict),
                autoCreateContainer = autoCreateContainer,
                isRefreshing = isRefreshing,
                loadFailed = loadFailed,
            )

        scheduleRemoteSizeFetches(availableItems)
        scheduleInstalledSizeFetches(installedItems)
    }

    private fun updateDownloadProgress(
        title: String,
        message: String,
        progress: Float? = null,
        indeterminate: Boolean = false,
    ) {
        val next =
            ComponentsDownloadProgress(
                title = title,
                message = message,
                progress = progress ?: 0f,
                indeterminate = indeterminate || progress == null,
            )
        runOnMain {
            downloadProgress = next
            publishState()
        }
    }

    private fun clearDownloadProgress() {
        runOnMain {
            downloadProgress = null
            publishState()
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        val act = activity
        if (act != null && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            act?.runOnUiThread { block() }
        }
    }

    private fun ContentProfile.toItem(): ComponentItem {
        val installedSuffix = if (isInstalled) "1" else "0"
        val cachedSize =
            if (isInstalled) {
                installedSizeCache[ContentsManager.getInstallDir(requireContext(), this).absolutePath]
            } else {
                remoteUrl?.let { remoteSizeCache[it] }
            }
        return ComponentItem(
            key = "$type:$verName:$verCode:$installedSuffix:${remoteUrl ?: ""}",
            type = type,
            verName = verName,
            isInstalled = isInstalled,
            hasRemote = remoteUrl != null,
            sizeBytes = cachedSize,
            isOfficial = isOfficial,
        )
    }

    private fun LinuxProtons.Build.toLinuxComponentItem(state: LinuxProtons.State): LinuxComponentItem =
        LinuxComponentItem(
            key = id,
            type = ContentProfile.ContentType.CONTENT_TYPE_PROTON,
            verName = displayName,
            isInstalled = id in state.installed,
            hasRemote = true,
            sizeBytes = size,
            isOfficial = false,
            isWorking = state.working == id,
            progress = state.progress,
        )

    private fun scheduleRemoteSizeFetches(items: List<ComponentItem>) {
        val urlsToFetch =
            items
                .mapNotNull { item -> profilesByKey[item.key]?.remoteUrl }
                .filter { url -> url !in remoteSizeCache && url !in remoteSizeFetchesInFlight }
                .distinct()

        if (urlsToFetch.isEmpty()) return

        remoteSizeFetchesInFlight.addAll(urlsToFetch)

        viewLifecycleOwner.lifecycleScope.launch {
            val sizes =
                urlsToFetch
                    .map { url -> async(Dispatchers.IO) { url to Downloader.fetchContentLength(url) } }
                    .awaitAll()
            if (!isAdded || view == null) return@launch
            sizes.forEach { (url, size) ->
                remoteSizeCache[url] = size
                remoteSizeFetchesInFlight.remove(url)
            }
            publishState()
        }
    }

    private fun scheduleInstalledSizeFetches(items: List<ComponentItem>) {
        val installDirsToFetch =
            items
                .mapNotNull { item -> profilesByKey[item.key] }
                .map { profile -> ContentsManager.getInstallDir(requireContext(), profile).absolutePath }
                .filter { path -> path !in installedSizeCache && path !in installedSizeFetchesInFlight }
                .distinct()

        if (installDirsToFetch.isEmpty()) return

        installedSizeFetchesInFlight.addAll(installDirsToFetch)

        viewLifecycleOwner.lifecycleScope.launch {
            val sizes =
                installDirsToFetch
                    .map { installDir -> async(Dispatchers.IO) { installDir to StorageUtils.getFolderSize(installDir) } }
                    .awaitAll()
            if (!isAdded || view == null) return@launch
            sizes.forEach { (installDir, size) ->
                installedSizeCache[installDir] = size
                installedSizeFetchesInFlight.remove(installDir)
            }
            publishState()
        }
    }

    // Actions

    private fun promptInstallFromFile() {
        val activity = activity ?: return
        DirectoryPickerDialog.showFile(
            activity = activity,
            title = getString(R.string.settings_content_install),
            allowedExtensions = setOf("wcp", "xz", "txz", "tzst"),
        ) { path ->
            updateDownloadProgress(
                title = getString(R.string.settings_content_installing_title),
                message = getString(R.string.settings_content_preparing_package),
                indeterminate = true,
            )
            installSelectedContent(
                Uri.fromFile(File(path)),
                getString(R.string.settings_content_installed_success),
            )
        }
    }

    private fun onRemoveRequested(profile: ContentProfile) {
        var containerInUse: String? = null
        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE ||
            profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON
        ) {
            val containerManager = ContainerManager(requireContext())
            containerManager.containers.forEach { container ->
                if (container.wineVersion == ContentsManager.getEntryName(profile)) {
                    containerInUse = container.name
                    return@forEach
                }
            }
        }

        if (containerInUse != null) {
            ContentDialog.alert(
                requireContext(),
                getString(
                    R.string.settings_content_unable_to_remove_in_use,
                    containerInUse,
                ),
                null,
            )
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { manager.removeContent(profile) }
            if (!isAdded || view == null) return@launch
            publishState()
        }
    }

    private fun refreshRemoteProfiles() {
        if (isRefreshing) return
        isRefreshing = true
        loadFailed = false
        remoteSizeCache.entries.removeAll { it.value <= 0L }
        installedSizeCache.entries.removeAll { it.value <= 0L }
        publishState()

        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            try {
                val context = context
                val contentsUrl =
                    if (context != null) {
                        PreferenceManager
                            .getDefaultSharedPreferences(context)
                            .getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES)
                            ?: ContentsManager.REMOTE_PROFILES
                    } else {
                        ContentsManager.REMOTE_PROFILES
                    }

                val json =
                    withContext(Dispatchers.IO) {
                        Downloader.downloadString(contentsUrl)
                    }

                if (json != null) {
                    withContext(Dispatchers.IO) { manager.setRemoteProfiles(json) }
                } else {
                    failed = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh remote profiles.", e)
                failed = true
            } finally {
                isRefreshing = false
            }

            loadFailed = failed
            if (isAdded && view != null) publishState()
        }
    }

    private fun installSelectedContent(
        uri: Uri,
        completionMessage: String,
        sourceRemoteUrl: String? = null,
    ) {
        val callback =
            object : ContentsManager.OnInstallFinishedCallback {
                private var isExtracting = true
                private var extractedProfile: ContentProfile? = null

                override fun onFailed(
                    reason: ContentsManager.InstallFailedReason,
                    e: Exception?,
                ) {
                    val conflictingProfile = extractedProfile
                    if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) {
                        conflictingProfile?.let { profile ->
                            if (sourceRemoteUrl != null) {
                                manager.registerRemoteProfileAlias(sourceRemoteUrl, profile)
                            }
                            manager.syncContents()
                        }
                    }

                    val msgId =
                        when (reason) {
                            ContentsManager.InstallFailedReason.ERROR_BADTAR -> R.string.settings_content_file_cannot_be_recognized
                            ContentsManager.InstallFailedReason.ERROR_NOPROFILE -> R.string.settings_content_profile_not_found
                            ContentsManager.InstallFailedReason.ERROR_BADPROFILE -> R.string.settings_content_profile_cannot_be_recognized
                            ContentsManager.InstallFailedReason.ERROR_MISSINGFILES -> R.string.settings_content_is_incomplete
                            ContentsManager.InstallFailedReason.ERROR_UNTRUSTPROFILE -> R.string.settings_content_cannot_be_trusted
                            else -> R.string.settings_content_unable_to_install
                        }

                    runOnMain {
                        clearDownloadProgress()
                        if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST && conflictingProfile != null) {
                            showConflictingContentDialog(conflictingProfile)
                        } else {
                            ContentDialog.alert(
                                requireContext(),
                                getString(R.string.settings_content_install_failed) + ": " + getString(msgId),
                                null,
                            )
                        }
                    }
                }

                override fun onSucceed(profile: ContentProfile) {
                    if (isExtracting) {
                        isExtracting = false
                        extractedProfile = profile
                        updateDownloadProgress(
                            title = getString(R.string.settings_content_installing_title),
                            message = profile.verName,
                            indeterminate = true,
                        )
                        manager.finishInstallContent(profile, this)
                        return
                    }

                    if (sourceRemoteUrl != null) {
                        manager.registerRemoteProfileAlias(sourceRemoteUrl, profile)
                    }
                    manager.syncContents()

                    runOnMain {
                        WinToast.show(requireContext(), completionMessage)
                        currentContentType = profile.type
                        publishState()

                        val willAutoCreate = autoCreateContainer && (
                            profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE ||
                                profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON
                        )

                        if (willAutoCreate) {
                            // Keep the download/install popup open and swap
                            // its title — avoids a flash between dialogs.
                            updateDownloadProgress(
                                title = getString(R.string.containers_list_creating),
                                message = profile.verName,
                                indeterminate = true,
                            )
                            val containerManager = ContainerManager(requireContext())
                            ContainerCreation.createContainerForProfileAsync(
                                requireContext(),
                                containerManager,
                                manager,
                                profile,
                            ) { newContainer ->
                                clearDownloadProgress()
                                if (newContainer != null) {
                                    WinToast.show(
                                        requireContext(),
                                        getString(R.string.settings_content_container_created, newContainer.name),
                                    )
                                }
                            }
                        } else {
                            clearDownloadProgress()
                        }
                    }
                }
            }

        val extractionProgress =
            object : ContentsManager.OnExtractionProgressListener {
                override fun onProgress(
                    filesExtracted: Int,
                    currentFileName: String,
                ) {
                    updateDownloadProgress(
                        title = getString(R.string.settings_content_extracting_title),
                        message = getString(R.string.settings_content_extracting_detail, filesExtracted),
                        indeterminate = true,
                    )
                }

                override fun prefersByteProgress(): Boolean = true

                override fun onByteProgress(bytesExtracted: Long) {
                    val ctx = context ?: return
                    updateDownloadProgress(
                        title = getString(R.string.settings_content_extracting_title),
                        message = android.text.format.Formatter.formatFileSize(ctx, bytesExtracted),
                        indeterminate = true,
                    )
                }
            }

        // Hold the keep-alive across the extraction/install so screen lock
        // doesn't kill the process mid-extract. The callback above can chain
        // into finishInstallContent() asynchronously; we release after the
        // install pipeline finishes (success, failure, or terminal callback).
        val installKeepAliveTag = "components_install_${uri}_${System.currentTimeMillis()}"
        val appCtx = requireContext().applicationContext
        com.winlator.cmod.runtime.system.SessionKeepAliveService.startDownload(appCtx, installKeepAliveTag)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                runCatching { manager.extraContentFile(uri, callback, extractionProgress) }
                    .onFailure {
                        runOnMain {
                            clearDownloadProgress()
                            WinToast.show(requireContext(), R.string.input_controls_editor_unable_to_import)
                        }
                    }
            } finally {
                com.winlator.cmod.runtime.system.SessionKeepAliveService.stopDownload(appCtx, installKeepAliveTag)
            }
        }
    }

    private fun showConflictingContentDialog(profile: ContentProfile) {
        conflictingContentPath = ContentsManager.getInstallDir(requireContext(), profile).absolutePath
        publishState()
    }

    private fun findInstalledProfileFor(profile: ContentProfile): ContentProfile? {
        manager.syncContents()

        val context = requireContext()
        val remoteUrl = profile.remoteUrl
        val installedProfile =
            manager
                .getProfiles(profile.type)
                .orEmpty()
                .firstOrNull { candidate ->
                    val sameVersion =
                        candidate.verName == profile.verName &&
                            candidate.verCode == profile.verCode
                    val sameRemote = remoteUrl != null && candidate.remoteUrl == remoteUrl

                    candidate.isInstalled &&
                        ContentsManager.isInstalled(context, candidate) &&
                        (sameVersion || sameRemote)
                }

        return installedProfile
            ?: profile.takeIf { ContentsManager.isInstalled(context, it) }
    }

    private fun downloadRemoteContent(profile: ContentProfile) {
        val remoteUrl = profile.remoteUrl ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val installedProfile = withContext(Dispatchers.IO) { findInstalledProfileFor(profile) }
            if (!isAdded || view == null) return@launch
            if (installedProfile != null) {
                publishState()
                showConflictingContentDialog(installedProfile)
                return@launch
            }

            updateDownloadProgress(
                title = getString(R.string.settings_content_downloading_title),
                message = profile.verName,
                indeterminate = true,
            )

            // Keep the app process alive while the download/install runs so screen
            // lock doesn't tear it down. installSelectedContent() owns its own
            // keep-alive scope; this one covers the download step alone.
            val keepAliveTag = "components_download_${remoteUrl}"
            val appCtx = requireContext().applicationContext
            com.winlator.cmod.runtime.system.SessionKeepAliveService.startDownload(appCtx, keepAliveTag)
            val output = File(requireContext().cacheDir, "temp_${System.currentTimeMillis()}")
            val success =
                withContext(Dispatchers.IO) {
                    Downloader.downloadFileWinNativeFirst(remoteUrl, output) { downloadedBytes, totalBytes ->
                        if (totalBytes <= 0L) {
                            updateDownloadProgress(
                                title = getString(R.string.settings_content_downloading_title),
                                message = profile.verName,
                                indeterminate = true,
                            )
                            return@downloadFileWinNativeFirst
                        }
                        val fraction =
                            (downloadedBytes.toFloat() / totalBytes.toFloat())
                                .coerceIn(0f, 1f)
                        updateDownloadProgress(
                            title = getString(R.string.settings_content_downloading_title),
                            message = profile.verName,
                            progress = fraction,
                        )
                    }
                }

            try {
                if (!isAdded || view == null) {
                    output.delete()
                    clearDownloadProgress()
                    return@launch
                }

                if (success) {
                    updateDownloadProgress(
                        title = getString(R.string.settings_content_extracting_title),
                        message = profile.verName,
                        indeterminate = true,
                    )
                    installSelectedContent(
                        Uri.parse(output.absolutePath),
                        getString(R.string.settings_content_download_complete),
                        remoteUrl,
                    )
                } else if (isAdded) {
                    clearDownloadProgress()
                    WinToast.show(requireContext(), R.string.settings_content_download_failed)
                }
            } finally {
                com.winlator.cmod.runtime.system.SessionKeepAliveService.stopDownload(appCtx, keepAliveTag)
            }
        }
    }

    companion object {
        private const val STATE_COMPONENTS_PLATFORM = "state_components_platform"
        private const val STATE_CONTENT_TYPE = "state_content_type"
        private const val TAG = "ContentsFragment"
        private const val PREF_AUTO_CREATE_CONTAINER = "components_auto_create_container"
    }
}

// Online-capable layers first, then everything else; within each group newest version first.
// The version is the first numeric run in the version name, which for remote entries is
// prefixed by the type ("Proton-11.0-2-arm64ec" -> 11.0.2). Compared component by component,
// so 11.0-2.1 outranks 11.0-2 and 11.10 outranks 11.9.
private val VERSION_RUN = Regex("""\d+(?:[.\-]\d+)*""")

private fun versionParts(verName: String?): List<Int> =
    VERSION_RUN.find(verName.orEmpty())
        ?.value
        ?.split('.', '-')
        ?.mapNotNull(String::toIntOrNull)
        .orEmpty()

private fun compareVersionDescending(a: ContentProfile, b: ContentProfile): Int {
    val left = versionParts(a.verName)
    val right = versionParts(b.verName)
    for (i in 0 until maxOf(left.size, right.size)) {
        // A missing component ranks below a present one, so 11.0-2 follows 11.0-2.1.
        val l = left.getOrElse(i) { -1 }
        val r = right.getOrElse(i) { -1 }
        if (l != r) return r.compareTo(l)
    }
    return 0
}

private val CONTENT_ORDER: Comparator<ContentProfile> =
    compareByDescending<ContentProfile> { isOnlineCapableName(it.verName, it.remoteUrl) }
        .thenComparator(::compareVersionDescending)
        .thenByDescending { it.verCode }
        .thenByDescending { it.isOfficial }
        .thenBy { it.verName.orEmpty().lowercase() }
