package com.winlator.cmod.runtime.container

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.winlator.cmod.feature.library.LinuxApps
import com.winlator.cmod.runtime.compat.box64.Box64Preset
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePreset
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.winhandler.WinHandler
import com.winlator.cmod.runtime.wine.WineInfo
import com.winlator.cmod.runtime.wine.WineThemeManager
import com.winlator.cmod.runtime.wine.WineUtils
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.shared.util.Callback
import org.json.JSONObject

object ContainerCreation {
    const val GAMESCOPE_CONTAINER_NAME = "GameScope"
    private const val WINE_DISPLAY_NAME = "Wine"
    private const val PROTON_DISPLAY_NAME = "Proton"
    private const val BOX64_EMULATOR = "box64"
    private const val FEXCORE_EMULATOR = "fexcore"

    private val displayNameUnsafeChars = Regex("[^a-zA-Z0-9._\\- ]")
    private val whitespace = Regex("\\s+")
    private val arm64EcPattern = Regex("arm64ec", RegexOption.IGNORE_CASE)

    private data class LaunchReadyDefaults(
        val emulator: String,
        val box64Version: String,
        val fexcoreVersion: String,
        val dxWrapperConfig: String,
    )

    @JvmStatic
    fun displayNameForProfile(profile: ContentProfile): String {
        val prefix =
            when (profile.type) {
                ContentProfile.ContentType.CONTENT_TYPE_WINE -> WINE_DISPLAY_NAME
                ContentProfile.ContentType.CONTENT_TYPE_PROTON -> PROTON_DISPLAY_NAME
                else -> profile.type.toString()
            }
        val withoutPrefix =
            removeLeadingRuntimePrefix(profile.verName)
                .trim()
        return sanitizeDisplayName("$prefix $withoutPrefix").ifBlank { prefix }
    }

    @JvmStatic
    fun displayNameForWineVersion(
        context: Context,
        contentsManager: ContentsManager,
        wineVersion: String,
    ): String {
        findRuntimeProfile(contentsManager, wineVersion)?.let {
            return displayNameForProfile(it)
        }
        displayNameFromWineVersionIdentifier(wineVersion)?.let {
            return it
        }

        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion)
        return sanitizeDisplayName(wineInfo.toString()).ifBlank { wineVersion }
    }

    private fun displayNameFromWineVersionIdentifier(wineVersion: String): String? {
        val trimmed = wineVersion.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        val runtimeName =
            when {
                lower.startsWith("proton-") -> PROTON_DISPLAY_NAME
                lower.startsWith("wine-") -> WINE_DISPLAY_NAME
                else -> return null
            }
        val contentTypePrefix =
            when (runtimeName) {
                PROTON_DISPLAY_NAME -> ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString() + "-"
                else -> ContentProfile.ContentType.CONTENT_TYPE_WINE.toString() + "-"
            }
        var versionPart =
            if (trimmed.startsWith(contentTypePrefix, ignoreCase = true)) {
                trimmed.substring(contentTypePrefix.length)
            } else {
                removeLeadingRuntimePrefix(trimmed)
            }
        versionPart = removeLeadingRuntimePrefix(versionPart)

        val lastDash = versionPart.lastIndexOf('-')
        if (lastDash > 0 && versionPart.substring(lastDash + 1).all { it.isDigit() }) {
            versionPart = versionPart.substring(0, lastDash)
        }

        return sanitizeDisplayName("$runtimeName $versionPart").ifBlank { null }
    }

    private fun findRuntimeProfile(
        contentsManager: ContentsManager,
        entryName: String,
    ): ContentProfile? =
        (
            contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE).orEmpty() +
                contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON).orEmpty()
            ).firstOrNull { ContentsManager.getEntryName(it) == entryName }

    private fun removeLeadingRuntimePrefix(versionName: String): String {
        val trimmed = versionName.trim()
        val prefixEnd =
            when {
                trimmed.regionMatches(0, "wine", 0, "wine".length, ignoreCase = true) -> "wine".length
                trimmed.regionMatches(0, "proton", 0, "proton".length, ignoreCase = true) -> "proton".length
                else -> return trimmed
            }
        val next = trimmed.getOrNull(prefixEnd)
        return if (next == null || next == '-' || next == '_' || next.isWhitespace()) {
            trimmed.drop(prefixEnd).dropWhile { it == '-' || it == '_' || it.isWhitespace() }
        } else {
            trimmed
        }
    }

    @JvmStatic
    fun uniqueName(
        containerManager: ContainerManager,
        desiredName: String,
    ): String {
        containerManager.loadContainers()
        // Strip any existing "-number" suffix (e.g., "Proton 10-1" -> "Proton 10")
        val baseName = desiredName.trim().replace(Regex("-\\d+$"), "").ifBlank { "Container" }
        
        var maxNumber = 0
        val suffixRegex = Regex("^" + Regex.escape(baseName) + "-(\\d+)$", RegexOption.IGNORE_CASE)
        
        for (container in containerManager.containers) {
            val name = container.name
            if (name.equals(baseName, ignoreCase = true) && maxNumber < 1) {
                maxNumber = 0 // Base name exists without suffix
            }
            suffixRegex.find(name)?.let { match ->
                val num = match.groupValues[1].toIntOrNull() ?: 0
                if (num > maxNumber) maxNumber = num
            }
        }
        
        return "$baseName-${maxNumber + 1}"
    }

    @JvmStatic
    fun buildLaunchReadyData(
        context: Context,
        contentsManager: ContentsManager,
        name: String,
        wineVersion: String,
    ): JSONObject {
        val defaults = resolveLaunchReadyDefaults(context, contentsManager, wineVersion)

        val preferredDriver = SetupWizardActivity.getLastInstalledDriverId(context)

        return JSONObject().apply {
            put("name", name)
            put("wineVersion", wineVersion)
            put("screenSize", Container.DEFAULT_SCREEN_SIZE)
            put("envVars", Container.DEFAULT_ENV_VARS)
            put("cpuList", Container.getFallbackCPUList())
            put("cpuListWoW64", Container.getFallbackCPUListWoW64())
            put("graphicsDriver", preferredDriver.ifBlank { Container.DEFAULT_GRAPHICS_DRIVER })
            put("graphicsDriverConfig", replaceDelimitedConfigValue(
                Container.DEFAULT_GRAPHICSDRIVERCONFIG,
                ';',
                "version",
                preferredDriver.ifBlank { "System" },
            ))
            put("dxwrapper", Container.DEFAULT_DXWRAPPER)
            put("dxwrapperConfig", defaults.dxWrapperConfig)
            put("audioDriver", Container.DEFAULT_AUDIO_DRIVER)
            put("emulator", defaults.emulator)
            put("emulator64", defaults.emulator)
            put("wincomponents", Container.DEFAULT_WINCOMPONENTS)
            put("drives", WineUtils.normalizePersistentDrives(context, Container.DEFAULT_DRIVES))
            put("fullscreenStretched", false)
            put("inputType", WinHandler.DEFAULT_INPUT_TYPE.toInt())
            put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL.toInt())
            put("box64Version", defaults.box64Version)
            put("box64Preset", Box64Preset.PERFORMANCE)
            put("fexcoreVersion", defaults.fexcoreVersion)
            put("fexcorePreset", FEXCorePreset.PERFORMANCE_TSO)
            put("desktopTheme", WineThemeManager.DEFAULT_DESKTOP_THEME)
            put("midiSoundFont", "")
            put("lc_all", "")
            put("execArgs", "")
        }
    }

    @JvmStatic
    fun createContainer(
        context: Context,
        containerManager: ContainerManager,
        contentsManager: ContentsManager,
        name: String,
        wineVersion: String,
    ): Container? {
        val data = buildLaunchReadyData(context, contentsManager, name, wineVersion)
        return containerManager.createContainer(data, contentsManager)?.also {
            applyLaunchReadyDefaults(context, contentsManager, it)
        }
    }

    @JvmStatic
    fun createContainerForProfile(
        context: Context,
        containerManager: ContainerManager,
        contentsManager: ContentsManager,
        profile: ContentProfile,
        desiredName: String = displayNameForProfile(profile),
    ): Container? =
        createContainer(
            context,
            containerManager,
            contentsManager,
            uniqueName(containerManager, desiredName),
            ContentsManager.getEntryName(profile),
        )

    @JvmStatic
    fun createContainerAsync(
        context: Context,
        containerManager: ContainerManager,
        contentsManager: ContentsManager,
        name: String,
        wineVersion: String,
        callback: Callback<Container?>,
    ) {
        val data = buildLaunchReadyData(context, contentsManager, name, wineVersion)
        containerManager.createContainerAsync(data, contentsManager) { container ->
            if (container != null) {
                applyLaunchReadyDefaults(context, contentsManager, container)
            }
            callback.call(container)
        }
    }

    @JvmStatic
    fun createContainerAsync(
        containerManager: ContainerManager,
        contentsManager: ContentsManager,
        data: JSONObject,
        callback: Callback<Container?>,
    ) {
        containerManager.createContainerAsync(data, contentsManager) { container ->
            callback.call(container)
        }
    }

    /** The newest installed Wine or Proton, which every container's prefix is built from. */
    @JvmStatic
    fun newestInstalledRuntime(contentsManager: ContentsManager): ContentProfile? =
        (
            contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE).orEmpty() +
                contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON).orEmpty()
            ).filter { it.isInstalled }
            .maxWithOrNull(compareBy<ContentProfile> { it.verCode }.thenBy { it.verName.lowercase() })

    /**
     * Creates the GameScope container: Linux programs and the native Steam client boot into it,
     * so it always draws through Wayland and carries a Steam library entry. The callback gets null
     * when creation fails.
     */
    @JvmStatic
    fun createGamescopeContainerAsync(
        context: Context,
        containerManager: ContainerManager,
        contentsManager: ContentsManager,
        runtime: ContentProfile?,
        callback: Callback<Container?>,
    ) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val container = createGamescopeContainer(context, containerManager, contentsManager, runtime)
            handler.post { callback.call(container) }
        }.start()
    }

    /**
     * Worker thread. [createGamescopeContainerAsync] without the thread; null when creation fails.
     * Without a [runtime] the container gets no Wine prefix, which its sessions never use.
     */
    @JvmStatic
    fun createGamescopeContainer(
        context: Context,
        containerManager: ContainerManager,
        contentsManager: ContentsManager,
        runtime: ContentProfile?,
    ): Container? {
        val name = uniqueName(containerManager, GAMESCOPE_CONTAINER_NAME)
        val created =
            if (runtime != null) {
                val data = buildLaunchReadyData(context, contentsManager, name, ContentsManager.getEntryName(runtime))
                containerManager.createContainer(data, contentsManager)
            } else {
                val data = buildLaunchReadyData(context, contentsManager, name, WineInfo.MAIN_WINE_VERSION.identifier())
                containerManager.createPrefixlessContainer(data)
            }
        val container = created ?: return null
        applyLaunchReadyDefaults(context, contentsManager, container)
        container.setRuntime(Container.RUNTIME_GAMESCOPE)
        container.setDisplayBackend(Container.DISPLAY_BACKEND_WAYLAND)
        container.saveData()
        LinuxApps.ensureSteamShortcut(context, container)
        return container
    }

    @JvmStatic
    fun createContainerForProfileAsync(
        context: Context,
        containerManager: ContainerManager,
        contentsManager: ContentsManager,
        profile: ContentProfile,
        callback: Callback<Container?>,
    ) {
        val uniqueName = uniqueName(containerManager, displayNameForProfile(profile))
        createContainerAsync(
            context,
            containerManager,
            contentsManager,
            uniqueName,
            ContentsManager.getEntryName(profile),
            callback,
        )
    }

    @JvmStatic
    fun getOrCreateContainerForProfile(
        context: Context,
        containerManager: ContainerManager,
        contentsManager: ContentsManager,
        profile: ContentProfile,
        desiredName: String = displayNameForProfile(profile),
    ): Container? {
        val wineVersion = ContentsManager.getEntryName(profile)
        containerManager.containers.firstOrNull { it.name.equals(desiredName, ignoreCase = true) }?.let {
            if (it.wineVersion != wineVersion) {
                it.setWineVersion(wineVersion)
                it.putExtra("wineprefixNeedsUpdate", "t")
            }
            applyLaunchReadyDefaults(context, contentsManager, it)
            return it
        }
        // If not found, create a new one using the smart unique naming logic (Name-1)
        val uniqueName = uniqueName(containerManager, desiredName)
        return createContainerForProfile(context, containerManager, contentsManager, profile, uniqueName)
    }

    @JvmStatic
    fun applyLaunchReadyDefaults(
        context: Context,
        contentsManager: ContentsManager,
        container: Container,
    ) {
        val defaults = resolveLaunchReadyDefaults(context, contentsManager, container.wineVersion)
        val preferredDriver = SetupWizardActivity.getLastInstalledDriverId(context)

        container.setGraphicsDriver(preferredDriver.ifBlank { Container.DEFAULT_GRAPHICS_DRIVER })
        container.setCPUList(Container.getFallbackCPUList())
        container.setCPUListWoW64(Container.getFallbackCPUListWoW64())
        container.setDrives(WineUtils.normalizePersistentDrives(
            context,
            container.drives ?: Container.DEFAULT_DRIVES,
        ))
        container.setGraphicsDriverConfig(replaceDelimitedConfigValue(
            Container.DEFAULT_GRAPHICSDRIVERCONFIG,
            ';',
            "version",
            preferredDriver.ifBlank { "System" },
        ))
        container.setDXWrapper(Container.DEFAULT_DXWRAPPER)
        container.setDXWrapperConfig(defaults.dxWrapperConfig)
        container.setEmulator(defaults.emulator)
        container.setEmulator64(defaults.emulator)
        container.setBox64Version(defaults.box64Version)
        container.setFEXCoreVersion(defaults.fexcoreVersion)
        container.setBox64Preset(Box64Preset.PERFORMANCE)
        container.setFEXCorePreset(FEXCorePreset.PERFORMANCE_TSO)
        container.saveData()
    }

    private fun sanitizeDisplayName(value: String): String =
        value
            .replace(displayNameUnsafeChars, " ")
            .trim()
            .replace(whitespace, " ")

    private fun resolveLaunchReadyDefaults(
        context: Context,
        contentsManager: ContentsManager,
        wineVersion: String,
    ): LaunchReadyDefaults {
        contentsManager.syncContents()

        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion)
        val isArm64Ec = wineInfo.isArm64EC
        return LaunchReadyDefaults(
            emulator = emulatorFor(isArm64Ec),
            box64Version = resolvePreferredContentVersion(
                contentsManager,
                box64ContentTypeFor(isArm64Ec),
                "",
            ),
            fexcoreVersion = resolvePreferredContentVersion(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                "",
            ),
            dxWrapperConfig = buildDefaultDxWrapperConfig(contentsManager, isArm64Ec),
        )
    }

    private fun emulatorFor(isArm64Ec: Boolean): String =
        if (isArm64Ec) FEXCORE_EMULATOR else BOX64_EMULATOR

    private fun box64ContentTypeFor(isArm64Ec: Boolean): ContentProfile.ContentType =
        if (isArm64Ec) {
            ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
        } else {
            ContentProfile.ContentType.CONTENT_TYPE_BOX64
        }

    @JvmStatic
    fun defaultDxWrapperConfig(
        contentsManager: ContentsManager,
        isArm64Ec: Boolean,
    ): String = buildDefaultDxWrapperConfig(contentsManager, isArm64Ec)

    private fun buildDefaultDxWrapperConfig(
        contentsManager: ContentsManager,
        isArm64Ec: Boolean,
    ): String {
        val dxvkVersion =
            resolvePreferredContentVersion(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                "",
                includePattern = if (isArm64Ec) arm64EcPattern else null,
                excludePattern = if (isArm64Ec) null else arm64EcPattern,
            )
        val vkd3dVersion =
            resolvePreferredContentVersion(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
                "None",
                includePattern = if (isArm64Ec) arm64EcPattern else null,
                excludePattern = if (isArm64Ec) null else arm64EcPattern,
            )

        return replaceDelimitedConfigValue(
            replaceDelimitedConfigValue(
                Container.DEFAULT_DXWRAPPERCONFIG,
                ',',
                "version",
                dxvkVersion,
            ),
            ',',
            "vkd3dVersion",
            vkd3dVersion,
        )
    }

    private fun resolvePreferredContentVersion(
        manager: ContentsManager,
        type: ContentProfile.ContentType,
        fallback: String,
        includePattern: Regex? = null,
        excludePattern: Regex? = null,
    ): String {
        val installedProfiles =
            manager.getProfiles(type)
                .orEmpty()
                .filter { it.isInstalled }
        val matchingProfiles =
            installedProfiles
                .filter { profile ->
                    val versionName = profile.verName
                    (includePattern == null || includePattern.containsMatchIn(versionName)) &&
                        (excludePattern == null || !excludePattern.containsMatchIn(versionName))
                }.ifEmpty { installedProfiles }

        val newestInstalled =
            matchingProfiles.maxWithOrNull(
                compareBy<ContentProfile> { it.verCode }.thenBy { it.verName.lowercase() },
            )
        return newestInstalled?.let(::contentVersionIdentifier) ?: fallback
    }

    private fun contentVersionIdentifier(profile: ContentProfile): String {
        val entryName = ContentsManager.getEntryName(profile)
        val firstDash = entryName.indexOf('-')
        return if (firstDash >= 0) entryName.substring(firstDash + 1) else entryName
    }

    private fun replaceDelimitedConfigValue(
        config: String,
        delimiter: Char,
        key: String,
        value: String,
    ): String {
        val parts = config.split(delimiter).toMutableList()
        var replaced = false
        for (index in parts.indices) {
            if (parts[index].startsWith("$key=")) {
                parts[index] = "$key=$value"
                replaced = true
            }
        }
        if (!replaced) {
            parts += "$key=$value"
        }
        return parts.joinToString(delimiter.toString())
    }
}
