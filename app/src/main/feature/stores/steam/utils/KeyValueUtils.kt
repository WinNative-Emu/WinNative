package com.winlator.cmod.feature.stores.steam.utils
import com.winlator.cmod.feature.stores.steam.data.BranchInfo
import com.winlator.cmod.feature.stores.steam.data.ConfigInfo
import com.winlator.cmod.feature.stores.steam.data.DepotInfo
import com.winlator.cmod.feature.stores.steam.data.LaunchInfo
import com.winlator.cmod.feature.stores.steam.data.LibraryAssetsInfo
import com.winlator.cmod.feature.stores.steam.data.LibraryCapsuleInfo
import com.winlator.cmod.feature.stores.steam.data.LibraryHeroInfo
import com.winlator.cmod.feature.stores.steam.data.LibraryLogoInfo
import com.winlator.cmod.feature.stores.steam.data.ManifestInfo
import com.winlator.cmod.feature.stores.steam.data.SaveFilePattern
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.data.SteamControllerConfigDetail
import com.winlator.cmod.feature.stores.steam.data.UFS
import com.winlator.cmod.feature.stores.steam.enums.AppType
import com.winlator.cmod.feature.stores.steam.enums.ControllerSupport
import com.winlator.cmod.feature.stores.steam.enums.Language
import com.winlator.cmod.feature.stores.steam.enums.OS
import com.winlator.cmod.feature.stores.steam.enums.OSArch
import com.winlator.cmod.feature.stores.steam.enums.PathType
import com.winlator.cmod.feature.stores.steam.enums.ReleaseState
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.INVALID_APP_ID
import timber.log.Timber
import java.util.Date

/**
 * Extension functions relating to [WnKeyValue] as the receiver type.
 */

private data class WindowsRootRedirect(
    val source: PathType,
    val target: PathType,
    val prependPath: String,
    val replacements: List<Pair<String, String>>,
)

private fun WnKeyValue.parseWindowsRootRedirects(): List<WindowsRootRedirect> =
    this["ufs"]["rootoverrides"].children.mapNotNull { entry ->
        val os = entry["os"].value.orEmpty()
        val osList = entry["oslist"].value.orEmpty()
        val appliesToWindows =
            os.equals("Windows", ignoreCase = true) ||
                osList
                    .split(",")
                    .any { it.trim().equals("windows", ignoreCase = true) }
        if (!appliesToWindows) return@mapNotNull null

        WindowsRootRedirect(
            source = PathType.from(entry["root"].value),
            target = PathType.from(entry["useinstead"].value),
            prependPath = entry["addpath"].value.orEmpty(),
            replacements =
                entry["pathtransforms"].children.map { transform ->
                    transform["find"].value.orEmpty() to transform["replace"].value.orEmpty()
                },
        )
    }

private fun normalizeSteamUfsPath(value: String): String = if (value == "." || value == "/") "" else value

private fun remapWindowsUfsPath(
    originalPath: String,
    redirect: WindowsRootRedirect?,
): String {
    if (redirect == null) return originalPath

    var localPath =
        if (redirect.prependPath.isNotEmpty()) {
            val prefix = redirect.prependPath.replace('\\', '/').trimEnd('/')
            if (originalPath.isNotEmpty()) "$prefix/${originalPath.trimStart('/')}" else prefix
        } else {
            originalPath
        }

    redirect.replacements.forEach { (find, replace) ->
        localPath = localPath.replace(find, replace)
    }

    return localPath
}

fun WnKeyValue.generateSteamApp(): SteamApp =
    SteamApp(
        id = this["appid"].asInteger(INVALID_APP_ID),
        depots =
            this["depots"]
                .children
                .filter { currentDepot ->
                    currentDepot.name?.toIntOrNull() != null
                }.associate { currentDepot ->
                    val depotId = currentDepot.name!!.toInt()

                    val manifests = currentDepot["manifests"].children.generateManifest()

                    val encryptedManifests = currentDepot["encryptedmanifests"].children.generateManifest()

                    depotId to
                        DepotInfo(
                            depotId = depotId,
                            dlcAppId = currentDepot["dlcappid"].asInteger(INVALID_APP_ID),
                            depotFromApp =
                                currentDepot["depotfromapp"].asInteger(
                                    INVALID_APP_ID,
                                ),
                            sharedInstall = currentDepot["sharedinstall"].asBoolean(),
                            osList = OS.from(currentDepot["config"]["oslist"].value),
                            osArch = OSArch.from(currentDepot["config"]["osarch"].value),
                            manifests = manifests,
                            encryptedManifests = encryptedManifests,
                            language = currentDepot["config"]["language"].value.orEmpty(),
                            realm = currentDepot["config"]["realm"].value.orEmpty(),
                            lowViolence = currentDepot["config"]["lowviolence"].asBoolean(),
                            optionalDlcId = currentDepot["config"]["optionaldlc"].asInteger(INVALID_APP_ID),
                        )
                },
        branches =
            this["depots"]["branches"].children.associate {
                it.name!! to
                    BranchInfo(
                        name = it.name!!,
                        buildId = it["buildid"].asLong(),
                        pwdRequired = it["pwdrequired"].asBoolean(),
                        timeUpdated = Date(it["timeupdated"].asLong() * 1000L),
                    )
            },
        name = this["common"]["name"].value.orEmpty(),
        type = AppType.from(this["common"]["type"].value),
        osList = OS.from(this["common"]["oslist"].value),
        releaseState = ReleaseState.from(this["common"]["releasestate"].value),
        releaseDate = this["common"]["steam_release_date"].asLong(),
        metacriticScore = this["common"]["metacritic_score"].asByte(),
        metacriticFullUrl = this["common"]["metacritic_fullurl"].value.orEmpty(),
        logoHash = this["common"]["logo"].value.orEmpty(),
        logoSmallHash = this["common"]["logo_small"].value.orEmpty(),
        iconHash = this["common"]["icon"].value.orEmpty(),
        clientIconHash = this["common"]["clienticon"].value.orEmpty(),
        clientTgaHash = this["common"]["clienttga"].value.orEmpty(),
        smallCapsule = this["common"]["small_capsule"].children.toLangImgMap(),
        headerImage = this["common"]["header_image"].children.toLangImgMap(),
        libraryAssets =
            LibraryAssetsInfo(
                libraryCapsule =
                    LibraryCapsuleInfo(
                        image = this["common"]["library_assets_full"]["library_capsule"]["image"].children.toLangImgMap(),
                        image2x = this["common"]["library_assets_full"]["library_capsule"]["image2x"].children.toLangImgMap(),
                    ),
                libraryHero =
                    LibraryHeroInfo(
                        image = this["common"]["library_assets_full"]["library_hero"]["image"].children.toLangImgMap(),
                        image2x = this["common"]["library_assets_full"]["library_hero"]["image2x"].children.toLangImgMap(),
                    ),
                libraryLogo =
                    LibraryLogoInfo(
                        image = this["common"]["library_assets_full"]["library_logo"]["image"].children.toLangImgMap(),
                        image2x = this["common"]["library_assets_full"]["library_logo"]["image2x"].children.toLangImgMap(),
                    ),
            ),
        primaryGenre = this["common"]["primary_genre"].asBoolean(),
        reviewScore = this["common"]["review_score"].asByte(),
        reviewPercentage = this["common"]["review_percentage"].asByte(),
        controllerSupport = ControllerSupport.from(this["common"]["controller_support"].value),
        demoOfAppId = this["common"]["extended"]["demoofappid"].asInteger(),
        developer = this["extended"]["developer"].value.orEmpty(),
        publisher = this["extended"]["publisher"].value.orEmpty(),
        homepageUrl = this["extended"]["homepage"].value.orEmpty(),
        gameManualUrl = this["common"]["extended"]["gamemanualurl"].value.orEmpty(),
        loadAllBeforeLaunch = this["common"]["extended"]["loadallbeforelaunch"].asBoolean(),
        dlcAppIds = this["common"]["extended"]["listofdlc"].value.parseDlcAppIds(),
        isFreeApp = this["common"]["extended"]["isfreeapp"].asBoolean(),
        dlcForAppId = this["extended"]["dlcforappid"].asInteger(this["common"]["extended"]["dlcforappid"].asInteger()),
        mustOwnAppToPurchase = this["common"]["extended"]["mustownapptopurchase"].asInteger(),
        dlcAvailableOnStore = this["common"]["extended"]["dlcavailableonstore"].asBoolean(),
        optionalDlc = this["common"]["extended"]["optionaldlc"].asBoolean(),
        gameDir = this["common"]["extended"]["gamedir"].value.orEmpty(),
        installScript = this["common"]["extended"]["installscript"].value.orEmpty(),
        noServers = this["common"]["extended"]["noservers"].asBoolean(),
        order = this["common"]["extended"]["order"].asBoolean(),
        primaryCache = this["common"]["extended"]["primarycache"].asInteger(),
        validOSList = OS.from(this["common"]["extended"]["validoslist"].value),
        thirdPartyCdKey = this["common"]["extended"]["thirdpartycdkey"].asBoolean(),
        visibleOnlyWhenInstalled = this["common"]["extended"]["visibleonlywheninstalled"].asBoolean(),
        visibleOnlyWhenSubscribed = this["common"]["extended"]["visibleonlywhensubscribed"].asBoolean(),
        launchEulaUrl = this["common"]["extended"]["launcheula"].value.orEmpty(),
        requireDefaultInstallFolder = this["common"]["config"]["requiredefaultinstallfolder"].asBoolean(),
        contentType = this["common"]["config"]["contentType"].asInteger(),
        installDir = this["common"]["config"]["installdir"].value.orEmpty(),
        useLaunchCmdLine = this["common"]["config"]["uselaunchcommandline"].asBoolean(),
        launchWithoutWorkshopUpdates = this["common"]["config"]["launchwithoutworkshopupdates"].asBoolean(),
        useMms = this["common"]["config"]["usemms"].asBoolean(),
        installScriptSignature = this["common"]["config"]["installscriptsignature"].value.orEmpty(),
        installScriptOverride = this["common"]["config"]["installscriptoverride"].asBoolean(),
        config =
            ConfigInfo(
                installDir = this["config"]["installdir"].value.orEmpty(),
                launch =
                    this["config"]["launch"].children.map {
                        LaunchInfo(
                            executable = it["executable"].value?.replace('\\', '/').orEmpty(),
                            workingDir = it["workingdir"].value?.replace('\\', '/').orEmpty(),
                            description = it["description"].value.orEmpty(),
                            type = it["type"].value.orEmpty(),
                            configOS = OS.from(it["config"]["oslist"].value),
                            configArch = OSArch.from(it["config"]["osarch"].value),
                            launchId = it.name?.toIntOrNull() ?: -1,
                        )
                    },
                steamControllerTemplateIndex = this["config"]["steamcontrollertemplateindex"].asInteger(),
                steamControllerTouchTemplateIndex = this["config"]["steamcontrollertouchtemplateindex"].asInteger(),
                steamInputManifestPath = this["config"]["steaminputmanifestpath"].value.orEmpty(),
                steamControllerConfigDetails = parseSteamControllerConfigDetails(),
            ),
        ufs = run {
            val windowsRootRedirects = parseWindowsRootRedirects()

            UFS(
                quota = this["ufs"]["quota"].asInteger(),
                maxNumFiles = this["ufs"]["maxnumfiles"].asInteger(),
                saveFilePatterns =
                    this["ufs"]["savefiles"].children.mapNotNull {
                        val platforms = it["platforms"].children.map { platform -> platform.value?.lowercase() }
                        if (platforms.isNotEmpty() && "windows" !in platforms) return@mapNotNull null

                        val originalRoot = PathType.from(it["root"].value)
                        val originalPath = normalizeSteamUfsPath(it["path"].value.orEmpty())
                        val redirect = windowsRootRedirects.find { override -> override.source == originalRoot }

                        SaveFilePattern(
                            root = redirect?.target ?: originalRoot,
                            path = remapWindowsUfsPath(originalPath, redirect),
                            pattern = it["pattern"].value.orEmpty(),
                            recursive = it["recursive"].asInteger(0),
                            uploadRoot = originalRoot,
                            uploadPath = originalPath,
                        )
                    },
            )
        },
    )

private fun WnKeyValue.parseSteamControllerConfigDetails(): List<SteamControllerConfigDetail> {
    val details = this["config"]["steamcontrollerconfigdetails"]
    if (details.children.isEmpty()) return emptyList()

    return details.children.mapNotNull { detail ->
        val publishedFileId = detail.name?.toLongOrNull() ?: return@mapNotNull null
        val controllerType = detail["controller_type"].value.orEmpty()
        val enabledBranches =
            detail["enabled_branches"]
                .value
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        SteamControllerConfigDetail(
            publishedFileId = publishedFileId,
            controllerType = controllerType,
            enabledBranches = enabledBranches,
        )
    }
}

fun List<WnKeyValue>.generateManifest(): Map<String, ManifestInfo> =
    associate { manifest ->
        manifest.name!! to
            ManifestInfo(
                name = manifest.name!!,
                gid = manifest["gid"].asLong(),
                size = manifest["size"].asLong(),
                download = manifest["download"].asLong(),
            )
    }

fun List<WnKeyValue>.toLangImgMap(): Map<Language, String> =
    mapNotNull { kv ->
        Language
            .from(kv.name!!)
            .takeIf { it != Language.unknown }
            ?.to(kv.value!!)
    }.toMap()

private fun String?.parseDlcAppIds(): List<Int> =
    this
        .orEmpty()
        .split(',', ';')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it != INVALID_APP_ID }
        .distinct()

@Suppress("unused")
fun WnKeyValue.printAllKeyValues(depth: Int = 0) {
    val parent = this
    var tabString = ""

    for (i in 0..depth) {
        tabString += "\t"
    }

    if (parent.children.isNotEmpty()) {
        Timber.i("$tabString${parent.name}")

        for (child in parent.children) {
            child.printAllKeyValues(depth + 1)
        }
    } else {
        Timber.i("$tabString${parent.name}: ${parent.value}")
    }
}
