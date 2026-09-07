package com.winlator.cmod.feature.stores.steam.wnsteam

import android.content.Context
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.io.TarCompressorUtils
import timber.log.Timber
import java.io.File

// Installs bundled Steam IPC assets into the imagefs and Wine prefix.
object WnSteamAssetsInstaller {

    private const val TAG = "WnSteamAssets"

    private const val ASSET_DIR     = "wnsteam"
    private const val STEAM_TZST    = "steam-androidarm64.tzst"
    private const val LSC_ARM64EC   = "lsteamclient-arm64ec.tzst"
    private const val LSC_X86_64    = "lsteamclient-x86_64.tzst"
    private const val STEAMPIPE_API64 = "wnsteam/steampipe/steam_api64.dll"
    private const val STEAMPIPE_API32 = "wnsteam/steampipe/steam_api.dll"
    private const val VALVE_STEAM_X64 = "valve-steam-x86_64.tzst"
    private const val STEAMPIPE_ORIGINAL_API64 = "wnsteam/steampipe/original_steam_api64.dll"

    fun isSupportedFor(container: Container): Boolean =
        lsteamclientArchive(container) != null

    fun installBionicRuntime(context: Context): Boolean {
        val imageFs = ImageFs.find(context)
        val apkId = apkStamp(context)
        val steamStamp = File(imageFs.libDir, ".wnsteam-androidarm64.stamp")
        if (stampStale(steamStamp, apkId)) {
            Timber.tag(TAG).i("Installing $STEAM_TZST → ${imageFs.rootDir} (apk=$apkId)")
            val ok = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "$ASSET_DIR/$STEAM_TZST",
                imageFs.rootDir,
            )
            if (!ok) {
                Timber.tag(TAG).e("Failed to extract $STEAM_TZST")
                return false
            }
            steamStamp.writeText(apkId)
        }
        stageBridgeLibsteamclient(context, imageFs)
        return true
    }

    @Suppress("DEPRECATION")
    private fun apkStamp(context: Context): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "apk-u${pi.lastUpdateTime}"
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "apkStamp: package info unavailable; forcing re-stage")
            "apk-unknown-${System.currentTimeMillis()}"
        }
    }

    private fun stampStale(stamp: File, apkId: String): Boolean {
        return try {
            !stamp.exists() || stamp.readText().trim() != apkId
        } catch (_: Exception) {
            true
        }
    }

    fun install(context: Context, container: Container): Boolean {
        if (!installBionicRuntime(context)) return false
        val imageFs = ImageFs.find(context)

        // Wine-side bridge: arm64ec or x86_64 lsteamclient.dll.
        val lscArchive = lsteamclientArchive(container)
        if (lscArchive == null) {
            Timber.tag(TAG).w(
                "No lsteamclient archive for wineVersion=%s; skipping Wine bridge install",
                container.wineVersion,
            )
            return true
        }
        val apkId = apkStamp(context)
        val wineStamp = File(imageFs.libDir, ".wnsteam-${lscArchive}.stamp")
        if (!stampStale(wineStamp, apkId)) return true

        // Stage per-arch files, then copy DLLs into the prefix.
        val stagingRoot = File(imageFs.tmpDir, "wnsteam-stage").apply {
            deleteRecursively(); mkdirs()
        }
        Timber.tag(TAG).i("Installing $lscArchive → $stagingRoot")
        val staged = TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD,
            context,
            "$ASSET_DIR/$lscArchive",
            stagingRoot,
        )
        if (!staged) {
            Timber.tag(TAG).e("Failed to extract $lscArchive")
            return false
        }

        val isArm64ec = lscArchive == LSC_ARM64EC
        val winNative = if (isArm64ec) "aarch64-windows" else "x86_64-windows"
        val unixSide  = if (isArm64ec) "aarch64-unix"    else "x86_64-unix"

        val system32 = File(imageFs.wineprefix, "drive_c/windows/system32").apply { mkdirs() }
        val syswow64 = File(imageFs.wineprefix, "drive_c/windows/syswow64").apply { mkdirs() }

        val systemSrc = File(stagingRoot, "$winNative/lsteamclient.dll")
        val syswowSrc = File(stagingRoot, "i386-windows/lsteamclient.dll")
        if (!systemSrc.exists() || !syswowSrc.exists()) {
            Timber.tag(TAG).e("Staged lsteamclient.dlls missing in $stagingRoot")
            return false
        }
        systemSrc.copyTo(File(system32, "lsteamclient.dll"), overwrite = true)
        syswowSrc.copyTo(File(syswow64, "lsteamclient.dll"), overwrite = true)

        // Drop the Unix .so where Wine's loader already expects it.
        val unixSoSrc = File(stagingRoot, "$unixSide/lsteamclient.so")
        if (unixSoSrc.exists()) {
            val unixSoDest = File(imageFs.libDir, "wine/$unixSide/lsteamclient.so").apply {
                parentFile?.mkdirs()
            }
            unixSoSrc.copyTo(unixSoDest, overwrite = true)
            patchLsteamclientLibPath(unixSoDest, context)
        }

        stagingRoot.deleteRecursively()
        wineStamp.writeText(apkId)
        Timber.tag(TAG).i("Wine bridge installed (variant=$lscArchive, apk=$apkId)")
        return true
    }

    fun bridgeLibPath(context: Context): File =
        File(context.filesDir, "libsteamclient.so")

    fun installPlanWSteamService(context: Context, container: Container): Boolean {
        val exeAsset    = "$ASSET_DIR/bionic/steamservice.exe"
        val dllAsset    = "$ASSET_DIR/bionic/steamservice.dll"
        val curVdfAsset = "$ASSET_DIR/bionic/service_current_versions.vdf"
        val minVdfAsset = "$ASSET_DIR/bionic/service_minimum_versions.vdf"
        val exePresent = try { context.assets.open(exeAsset).close(); true } catch (_: Exception) { false }
        if (!exePresent) {
            Timber.tag(TAG).i("planW: steamservice.exe asset not bundled — "
                + "LaunchApp will fall back to CreateProcess inside the launcher")
            return false
        }
        val steamDir = File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam")
        val binDir = File(steamDir, "bin").apply { mkdirs() }

        val apkId = apkStamp(context)
        val stageStamp = File(binDir, ".wn-planw-service.stamp")
        val exeFile    = File(binDir, "steamservice.exe")
        val dllFile    = File(binDir, "steamservice.dll")
        val curVdfFile = File(binDir, "service_current_versions.vdf")
        val minVdfFile = File(binDir, "service_minimum_versions.vdf")
        val commonFilesExe = File(container.rootDir,
            ".wine/drive_c/Program Files (x86)/Common Files/Steam/steamservice.exe")
        if (!stampStale(stageStamp, apkId)
            && exeFile.isFile && exeFile.length() > 0
            && dllFile.isFile && dllFile.length() > 0
            && curVdfFile.isFile && curVdfFile.length() > 0
            && minVdfFile.isFile && minVdfFile.length() > 0
            && commonFilesExe.isFile && commonFilesExe.length() > 0) {
            Timber.tag(TAG).d("planW: steamservice bundle already staged (apk=%s) — skipping per-launch copy", apkId)
            return true
        }

        fun stage(asset: String, dstFile: File): Boolean {
            val present = try { context.assets.open(asset).close(); true } catch (_: Exception) { false }
            if (!present) return false
            return try {
                context.assets.open(asset).use { input ->
                    dstFile.outputStream().use { output -> input.copyTo(output) }
                }
                Timber.tag(TAG).i("planW: staged %s (%d bytes) at %s",
                    dstFile.name, dstFile.length(), dstFile.absolutePath)
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "planW: failed to stage %s", dstFile.name)
                false
            }
        }

        val commonFilesDir = File(container.rootDir,
            ".wine/drive_c/Program Files (x86)/Common Files/Steam").apply { mkdirs() }

        return try {
            val exeOk    = stage(exeAsset,    exeFile)
            val dllOk    = stage(dllAsset,    dllFile)
            val curVdfOk = stage(curVdfAsset, curVdfFile)
            val minVdfOk = stage(minVdfAsset, minVdfFile)
            val commonOk = stage(exeAsset,    File(commonFilesDir, "steamservice.exe")) &&
                stage(dllAsset,    File(commonFilesDir, "steamservice.dll")) &&
                stage(curVdfAsset, File(commonFilesDir, "service_current_versions.vdf")) &&
                stage(minVdfAsset, File(commonFilesDir, "service_minimum_versions.vdf"))
            stage(dllAsset, File(steamDir, "steamservice.dll"))
            Timber.tag(TAG).i("planW: steamservice bundle staged exe=%b dll=%b curVdf=%b minVdf=%b commonFiles=%b",
                exeOk, dllOk, curVdfOk, minVdfOk, commonOk)
            // Stamp only when the full bundle is on disk — a partial copy must
            // re-run next launch, never get locked in by a fresh stamp.
            if (exeOk && dllOk && curVdfOk && minVdfOk) {
                try { stageStamp.writeText(apkId) } catch (_: Exception) {}
            }
            exeOk
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "planW: failed to stage steamservice bundle")
            false
        }
    }

    fun ensureRealSteamDir(context: Context, container: Container) {
        val imageFs = ImageFs.find(context)
        val steamDir = File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam")
        val steamPath = steamDir.toPath()
        val sharedSteam = File(imageFs.rootDir, ".shared/steam-client-store")

        val isLink = try {
            java.nio.file.Files.isSymbolicLink(steamPath)
        } catch (_: Exception) { false }
        if (isLink) {
            try {
                java.nio.file.Files.delete(steamPath)
                Timber.tag(TAG).i("ensureRealSteamDir: removed Steam-dir symlink at %s",
                    steamDir.absolutePath)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "ensureRealSteamDir: failed to delete Steam-dir symlink")
                return
            }
        }
        steamDir.mkdirs()

        val sharedUserdata = File(sharedSteam, "userdata")
        if (!sharedUserdata.isDirectory) return
        val realUserdata = File(steamDir, "userdata").apply { mkdirs() }
        var rescuedFiles = 0
        var rescuedApps = 0
        sharedUserdata.listFiles()?.forEach { accountDir ->
            if (!accountDir.isDirectory) return@forEach
            accountDir.listFiles()?.forEach { appDir ->
                if (!appDir.isDirectory) return@forEach
                val appIdName = appDir.name
                if (appIdName == "7" || appIdName == "config") return@forEach
                if (!appIdName.all { it.isDigit() }) return@forEach
                val destAccountDir = File(realUserdata, accountDir.name)
                val destAppDir = File(destAccountDir, appIdName)
                val destAlreadyPopulated = destAppDir.isDirectory &&
                    destAppDir.walkTopDown().filter { it.isFile }.any()
                if (destAlreadyPopulated) return@forEach
                try {
                    appDir.copyRecursively(destAppDir, overwrite = true)
                    val n = destAppDir.walkTopDown().filter { it.isFile }.count()
                    rescuedFiles += n
                    rescuedApps += 1
                    Timber.tag(TAG).i(
                        "ensureRealSteamDir: rescued %d file(s) for app %s from shared store",
                        n, appIdName)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e,
                        "ensureRealSteamDir: failed to rescue userdata for app %s", appIdName)
                }
            }
        }
        if (rescuedApps > 0) {
            Timber.tag(TAG).i(
                "ensureRealSteamDir: rescued userdata for %d app(s), %d total file(s)",
                rescuedApps, rescuedFiles)
        }
    }

    fun installSteamclientBridgeIntoContainer(context: Context, container: Container): Boolean {
        val archive = lsteamclientArchive(container)
        if (archive == null) {
            Timber.tag(TAG).w("bridge: no lsteamclient archive for wineVersion=%s",
                container.wineVersion)
            return false
        }
        val imageFs = ImageFs.find(context)
        val staging = File(imageFs.tmpDir, "wnsteam-bridge-stage").apply {
            deleteRecursively(); mkdirs()
        }
        val ok = TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, context, "$ASSET_DIR/$archive", staging)
        if (!ok) {
            Timber.tag(TAG).e("bridge: failed to extract $archive")
            staging.deleteRecursively()
            return false
        }
        val isArm64ec = archive == LSC_ARM64EC
        val winNative = if (isArm64ec) "aarch64-windows" else "x86_64-windows"
        val unixSide = if (isArm64ec) "aarch64-unix" else "x86_64-unix"
        val system32 = File(container.rootDir, ".wine/drive_c/windows/system32")
            .apply { mkdirs() }
        val syswow64 = File(container.rootDir, ".wine/drive_c/windows/syswow64")
            .apply { mkdirs() }
        var placed64 = false
        val src64 = File(staging, "$winNative/lsteamclient.dll")
        val src32 = File(staging, "i386-windows/lsteamclient.dll")
        if (src64.exists()) {
            src64.copyTo(File(system32, "lsteamclient.dll"), overwrite = true)
            placed64 = true
        }
        if (src32.exists()) {
            src32.copyTo(File(syswow64, "lsteamclient.dll"), overwrite = true)
        }
        val unixSo = File(staging, "$unixSide/lsteamclient.so")
        if (unixSo.exists()) {
            val dest = File(imageFs.libDir, "wine/$unixSide/lsteamclient.so")
                .apply { parentFile?.mkdirs() }
            unixSo.copyTo(dest, overwrite = true)
            patchLsteamclientLibPath(dest, context)
        }
        staging.deleteRecursively()
        Timber.tag(TAG).i("bridge: lsteamclient.dll → %s (64=%b src32=%b)",
            system32.absolutePath, placed64, src32.exists())
        return placed64
    }

    fun installPlanWValveSteam(context: Context, container: Container): Boolean {
        val steamDir = File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam")
        ensureRealSteamDir(context, container)
        val apkId = apkStamp(context)
        val cache = File(context.filesDir, "wnsteam-planw-cache")
        val cacheStamp = File(cache, ".planw-valve-steam.stamp")
        if (stampStale(cacheStamp, apkId)) {
            Timber.tag(TAG).i("planW: Valve Steam cache stale (apk=%s) — re-extracting %s",
                apkId, VALVE_STEAM_X64)
            cache.deleteRecursively(); cache.mkdirs()
            val ok = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context,
                "$ASSET_DIR/bionic/$VALVE_STEAM_X64", cache)
            if (!ok) {
                Timber.tag(TAG).e("planW: failed to extract %s into cache", VALVE_STEAM_X64)
                cache.deleteRecursively()
                return false
            }
            try { cacheStamp.writeText(apkId) } catch (_: Exception) {}
            Timber.tag(TAG).i("planW: cached Valve Steam DLLs at %s (%d entries)",
                cache.absolutePath, cache.listFiles()?.size ?: 0)
        }

        val stageStamp = File(steamDir, ".wn-planw-stage.stamp")
        val cachedSc64 = File(cache, "steamclient64.dll")
        val deployedSc64 = File(steamDir, "steamclient64.dll")
        val sys32Tier0 = File(container.rootDir,
            ".wine/drive_c/windows/system32/tier0_s64.dll")
        if (!stampStale(stageStamp, apkId)
            && cachedSc64.isFile && deployedSc64.isFile
            && cachedSc64.length() == deployedSc64.length()
            && sys32Tier0.isFile) {
            Timber.tag(TAG).d("planW: Steam dir already staged (apk=%s, sc64=%d bytes) — skipping per-launch copy",
                apkId, deployedSc64.length())
            return true
        }

        var copied = 0
        cache.listFiles()?.forEach { src ->
            if (!src.isFile || src.name.startsWith(".")) return@forEach
            val dst = File(steamDir, src.name)
            try {
                src.copyTo(dst, overwrite = true)
                copied++
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "planW: copy %s -> %s failed", src.name, dst.absolutePath)
            }
        }
        Timber.tag(TAG).i("planW: staged %d Valve Steam DLLs into %s (from cache)",
            copied, steamDir.absolutePath)

        val system32 = File(container.rootDir, ".wine/drive_c/windows/system32")
            .apply { mkdirs() }
        val syswow64 = File(container.rootDir, ".wine/drive_c/windows/syswow64")
        var depCopied = 0
        for (dep in arrayOf("tier0_s64.dll", "vstdlib_s64.dll")) {
            val src = File(steamDir, dep)
            if (src.isFile) {
                try {
                    src.copyTo(File(system32, dep), overwrite = true); depCopied++
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "planW: copy %s -> system32 failed", dep)
                }
            }
        }
        if (syswow64.isDirectory) {
            for (dep in arrayOf("tier0_s.dll", "vstdlib_s.dll")) {
                val src = File(steamDir, dep)
                if (src.isFile) {
                    try { src.copyTo(File(syswow64, dep), overwrite = true) }
                    catch (e: Exception) {
                        Timber.tag(TAG).e(e, "planW: copy %s -> syswow64 failed", dep)
                    }
                }
            }
        }
        Timber.tag(TAG).i("planW: staged %d tier0/vstdlib dep(s) into system32", depCopied)

        if (copied >= 5) {
            try { stageStamp.writeText(apkId) } catch (_: Exception) {}
        }
        return copied >= 5  // steamclient64 + Steam + Steam2 + tier0 + vstdlib (at minimum)
    }

    fun installPlanWLauncher(context: Context, container: Container): Boolean {
        // Write THROUGH the Steam dir whether it's a real dir or a symlink to
        // the shared backing store at <imageFs>/.shared/steam-client-store.
        // Deleting the symlink (an earlier attempt) stranded the Valve DLLs
        // there because updateSteamDirectoryVisibility re-creates the symlink
        // each launch — without that link, steamclient64.dll is invisible to
        // the launcher and LoadLibrary fails with GLE=126.
        val steamDir = File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam")
        steamDir.mkdirs()
        val dst = File(steamDir, "steam.exe")
        File(steamDir, "wn-steam-launcher.exe").let { if (it.exists()) it.delete() }

        val marker32 = File(context.filesDir, ".wn_steam_agent_32")
        val marker64 = File(context.filesDir, ".wn_steam_agent_64")
        val use32 = !marker64.isFile &&
            (marker32.isFile ||
                com.winlator.cmod.feature.stores.steam.utils.PrefManager.wnSteamAgent32)
        val agentAsset = if (use32) "$ASSET_DIR/bionic/steam32.exe" else "$ASSET_DIR/bionic/steam.exe"
        val archTag = if (use32) "x86" else "x86_64"
        val apkId = apkStamp(context) + ":" + archTag
        val stageStamp = File(steamDir, ".wn-planw-launcher.stamp")
        val staged = !stampStale(stageStamp, apkId) && dst.isFile && dst.length() > 0
        val ok: Boolean
        if (staged) {
            Timber.tag(TAG).d("planW: steam.exe already staged (apk=%s, %d bytes) — skipping per-launch copy",
                apkId, dst.length())
            ok = true
        } else {
            if (dst.exists()) { try { dst.delete() } catch (_: Exception) {} }
            ok = try {
                context.assets.open(agentAsset).use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                Timber.tag(TAG).i("planW: installed steam.exe from %s (%s, %d bytes) at %s",
                    agentAsset, archTag, dst.length(), dst.absolutePath)
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "planW: failed to install steam.exe")
                false
            }
            if (ok) {
                try { stageStamp.writeText(apkId) } catch (_: Exception) {}
            }
        }
        for (probeName in listOf("wn-iface-probe.exe", "wn-iface-probe32.exe")) {
            try {
                val probeDst = File(steamDir, probeName)
                context.assets.open("$ASSET_DIR/bionic/$probeName").use { input ->
                    probeDst.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {}
        }
        try {
            val caSrc = File(context.filesDir, "wnsteam_cacert.pem")
            if (caSrc.exists() && caSrc.length() > 0) {
                val caDst = File(steamDir, "wnsteam_cacert.pem")
                // Always copy: the CA bundle source can be refreshed outside
                // APK updates (e.g., a CA-refresh routine), and a same-size
                // content change would otherwise leave the launcher TLS-ing
                // against a stale trust store. The file is small (~few KB);
                // the per-launch copy cost is negligible.
                caSrc.copyTo(caDst, overwrite = true)
                Timber.tag(TAG).i("planW: staged CA bundle (%d bytes) at %s",
                    caDst.length(), caDst.absolutePath)
            } else {
                Timber.tag(TAG).w("planW: %s missing — STEAM_SSL_CERT_FILE will be unset, "
                    + "launcher CM logon may fail TLS", caSrc.absolutePath)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "planW: CA bundle stage failed")
        }
        applyControllerPassthrough(steamDir)
        return ok
    }

    private val OVERLAY_DLLS = arrayOf("GameOverlayRenderer.dll", "GameOverlayRenderer64.dll")

    private val STEAM_INPUT_KEYS = arrayOf(
        "SteamController_XBoxSupport",
        "SteamController_GenericGamepadSupport",
        "SteamController_PSSupport",
        "SteamController_SwitchSupport",
    )

    private fun applyControllerPassthrough(steamDir: File) {
        try {
            for (dll in OVERLAY_DLLS) {
                val f = File(steamDir, dll)
                if (f.isFile && f.delete()) {
                    Timber.tag(TAG).i("passthrough: removed %s", dll)
                }
            }
            val localConfig = resolveLocalConfig(steamDir)
            if (localConfig == null) {
                Timber.tag(TAG).w("passthrough: no localconfig.vdf resolvable — Steam Input keys skipped")
            } else {
                disableSteamControllerSupport(localConfig)
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w("passthrough: apply failed (non-fatal): %s", t.message ?: "")
        }
    }

    private fun resolveLocalConfig(steamDir: File): File? {
        val userdata = File(steamDir, "userdata")
        val users = userdata.listFiles() ?: return null
        for (u in users) {
            val cfg = File(u, "config/localconfig.vdf")
            if (cfg.isFile) return cfg
        }
        return null
    }

    private fun disableSteamControllerSupport(localConfig: File) {
        try {
            val original = localConfig.readText()
            if (!original.contains("UserLocalConfigStore")) {
                Timber.tag(TAG).w("passthrough: %s root is not UserLocalConfigStore — left untouched",
                    localConfig.name)
                return
            }
            var text = original
            var changed = false
            for (key in STEAM_INPUT_KEYS) {
                val rx = Regex("(\"" + Regex.escape(key) + "\"\\s*)\"[^\"]*\"")
                if (rx.containsMatchIn(text)) {
                    val replaced = rx.replace(text) { m -> m.groupValues[1] + "\"0\"" }
                    if (replaced != text) { text = replaced; changed = true }
                } else {
                    val sys = Regex("(\"system\"\\s*\\r?\\n\\s*\\{\\s*\\r?\\n)")
                    val m = sys.find(text)
                    if (m != null) {
                        text = text.substring(0, m.range.last + 1) +
                            "\t\t\t\"" + key + "\"\t\t\"0\"\n" +
                            text.substring(m.range.last + 1)
                        changed = true
                    }
                }
            }
            if (changed) {
                localConfig.writeText(text)
                Timber.tag(TAG).i("passthrough: Steam Input keys disabled in %s", localConfig.absolutePath)
            } else {
                Timber.tag(TAG).i("passthrough: Steam Input keys already disabled")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("passthrough: localconfig edit failed (non-fatal): %s", e.message ?: "")
        }
    }

    private fun stageBridgeLibsteamclient(context: Context, imageFs: ImageFs): Boolean {
        val src = File(imageFs.rootDir, "usr/lib/libsteamclient.so")
        if (!src.exists()) {
            Timber.tag(TAG).w("stageBridge: %s missing — bridge lib not staged", src.absolutePath)
            return false
        }
        val dest = bridgeLibPath(context)
        val apkId = apkStamp(context)
        val stamp = File(context.filesDir, ".wnsteam-bridge-lib.stamp")
        if (dest.exists() && dest.length() == src.length() && !stampStale(stamp, apkId)) {
            return true
        }
        return try {
            src.copyTo(dest, overwrite = true)
            stamp.writeText(apkId)
            Timber.tag(TAG).i("stageBridge: libsteamclient.so → %s (%d bytes, apk=%s)",
                dest.absolutePath, dest.length(), apkId)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "stageBridge: copy failed")
            false
        }
    }

    private fun patchLsteamclientLibPath(soFile: File, context: Context) {
        val marker = "/data/data/app.winnative/files/imagefs/usr/lib/libsteamclient.so"
        val markerBytes = marker.toByteArray(Charsets.US_ASCII)
        val targetBytes = bridgeLibPath(context).absolutePath.toByteArray(Charsets.US_ASCII)
        if (targetBytes.size > markerBytes.size) {
            Timber.tag(TAG).e("lsteamclient patch: target path %d bytes exceeds %d-byte field",
                targetBytes.size, markerBytes.size)
            return
        }
        val bytes = try {
            soFile.readBytes()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "lsteamclient patch: read failed")
            return
        }
        var patched = 0
        var i = 0
        while (i <= bytes.size - markerBytes.size) {
            var match = true
            for (j in markerBytes.indices) {
                if (bytes[i + j] != markerBytes[j]) { match = false; break }
            }
            if (match) {
                for (j in markerBytes.indices) {
                    bytes[i + j] = if (j < targetBytes.size) targetBytes[j] else 0
                }
                patched++
                i += markerBytes.size
            } else {
                i++
            }
        }
        if (patched == 0) {
            Timber.tag(TAG).w("lsteamclient patch: marker path not found in %s — "
                + "upstream asset may have changed; bridge will fail to find libsteamclient.so",
                soFile.name)
            return
        }
        try {
            soFile.writeBytes(bytes)
            Timber.tag(TAG).i("lsteamclient patch: redirected %d path occurrence(s) in %s → %s",
                patched, soFile.name, String(targetBytes, Charsets.US_ASCII))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "lsteamclient patch: write failed")
        }
    }

    fun reset(context: Context) {
        val imageFs = ImageFs.find(context)
        listOf(
            File(imageFs.libDir, ".wnsteam-androidarm64.stamp"),
            File(imageFs.libDir, ".wnsteam-$LSC_ARM64EC.stamp"),
            File(imageFs.libDir, ".wnsteam-$LSC_X86_64.stamp"),
            File(context.filesDir, ".wnsteam-bridge-lib.stamp"),
            File(context.filesDir, "wnsteam-planw-cache/.planw-valve-steam.stamp"),
        ).forEach { if (it.exists()) it.delete() }
    }

    fun installSteampipeBridgeIntoApp(context: Context, gameInstallDir: File): Int {
        if (!gameInstallDir.isDirectory) {
            Timber.tag(TAG).w("steampipe: game dir missing: ${gameInstallDir.absolutePath}")
            return 0
        }
        var swapped = 0
        val assetCache = HashMap<String, ByteArray>()
        fun bridgeBytes(asset: String): ByteArray =
            assetCache.getOrPut(asset) {
                context.assets.open(asset).use { it.readBytes() }
            }

        gameInstallDir.walkTopDown().maxDepth(10).forEach { f ->
            if (!f.isFile) return@forEach
            val n = f.name.lowercase()
            if (n != "steam_api.dll" && n != "steam_api64.dll") return@forEach
            val is64 = n == "steam_api64.dll"
            val asset = if (is64) STEAMPIPE_API64 else STEAMPIPE_API32
            val orig = File(f.parentFile, f.name + ".orig")
            try {
                if (!orig.exists()) {
                    f.copyTo(orig, overwrite = false)
                }
                val bytes = bridgeBytes(asset)
                if (!fileContentEquals(f, bytes)) {
                    java.io.FileOutputStream(f).use { out -> out.write(bytes) }
                    Timber.tag(TAG).i("steampipe: swapped ${f.path} (orig backed up as ${orig.name})")
                    swapped++
                } else {
                    Timber.tag(TAG).d("steampipe: ${f.path} already matches bridge asset")
                }

                if (is64) {
                    val backend = File(f.parentFile, "original_steam_api64.dll")
                    try {
                        val backendBytes = bridgeBytes(STEAMPIPE_ORIGINAL_API64)
                        if (!fileContentEquals(backend, backendBytes)) {
                            java.io.FileOutputStream(backend).use { out -> out.write(backendBytes) }
                            Timber.tag(TAG).i("steampipe: staged ${backend.path} as forwarder backend")
                        } else {
                            Timber.tag(TAG).d("steampipe: ${backend.path} already staged")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "steampipe: backend stage failed for ${backend.path}")
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "steampipe: swap failed for ${f.path}")
            }
        }
        Timber.tag(TAG).i("steampipe: $swapped steam_api*.dll(s) swapped under ${gameInstallDir.absolutePath}")
        return swapped
    }

    private fun fileContentEquals(file: File, expected: ByteArray): Boolean {
        if (!file.isFile || file.length() != expected.size.toLong()) return false
        return try {
            var offset = 0
            var matches = true
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (matches) {
                    val read = input.read(buf)
                    if (read < 0) return@use
                    if (offset + read > expected.size) {
                        matches = false
                        return@use
                    }
                    for (i in 0 until read) {
                        if (buf[i] != expected[offset + i]) {
                            matches = false
                            return@use
                        }
                    }
                    offset += read
                }
            }
            matches && offset == expected.size
        } catch (_: Exception) {
            false
        }
    }

    private fun lsteamclientArchive(container: Container): String? {
        val prefixArch = container.getExtra("wineprefixArch", "").lowercase()
        if (prefixArch.isNotBlank()) {
            return when {
                prefixArch.contains("arm64ec") || prefixArch.contains("aarch64") -> LSC_ARM64EC
                prefixArch.contains("x86_64") || prefixArch.contains("win64") -> LSC_X86_64
                else -> null
            }
        }
        return when {
            container.wineVersion?.contains("arm64ec", ignoreCase = true) == true -> LSC_ARM64EC
            container.wineVersion?.contains("x86_64",  ignoreCase = true) == true -> LSC_X86_64
            else -> null
        }
    }
}
