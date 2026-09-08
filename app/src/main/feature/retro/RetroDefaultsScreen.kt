package com.winlator.cmod.feature.retro

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.winlator.cmod.R
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.paneNavItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PageBg = Color(0xFF101018)
private val PageText = Color(0xFFF0F4FF)
private val PageSub = Color(0xFF93A6BC)

private val SHADER_KEYS = listOf("default", "crt", "lcd", "sharp")
private val UPSCALE_KEYS = listOf("2x", "4x", "native")

@Composable
fun RetroDefaultsScreen(bridge: SettingsNavBridge? = null) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var expandedConsole by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmHardcore by remember { mutableStateOf(false) }
    val contentNav = rememberSettingsContentNav(bridge)

    if (confirmHardcore) {
        RetroHardcoreConfirmDialog(
            onConfirm = {
                confirmHardcore = false
                RetroAchievementsManager.setHardcorePreferred(context, true)
                refresh++
            },
            onDismiss = { confirmHardcore = false },
        )
    }

    val biosPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        RetroBiosImport.importFromUri(context, uri)
                    }
                    result
                        .onSuccess { Toast.makeText(context, context.getString(R.string.retro_scr_bios_imported, it), Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.retro_scr_invalid_bios_file), Toast.LENGTH_LONG).show() }
                    refresh++
                }
            }
        }

    val ps2BiosPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        RetroBiosImport.importPs2FromUri(context, uri)
                    }
                    result
                        .onSuccess { Toast.makeText(context, context.getString(R.string.retro_scr_ps2_bios_imported, it), Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.retro_scr_invalid_ps2_bios_file), Toast.LENGTH_LONG).show() }
                    refresh++
                }
            }
        }

    @Suppress("UNUSED_EXPRESSION") refresh

    CompositionLocalProvider(LocalPaneNav provides contentNav) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(PageBg)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.retro_scr_retro_defaults),
            color = PageSub,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Text(
            stringResource(R.string.retro_scr_retro_defaults_desc),
            color = PageSub,
            style = MaterialTheme.typography.bodySmall,
        )

        RetroConsoleBundleGroup(refreshKey = refresh, onChanged = { refresh++ })

        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_scr_retroachievements))
            RetroInfoRow(
                stringResource(R.string.retro_scr_account),
                if (RetroAchievementsManager.isLoggedIn(context)) {
                    RetroAchievementsManager.displayName(context) ?: stringResource(R.string.retro_scr_signed_in)
                } else {
                    stringResource(R.string.retro_scr_not_signed_in_hint)
                },
            )
            RetroSettingSwitch(
                stringResource(R.string.retro_scr_achievements_enabled),
                RetroAchievementsManager.isEnabled(context),
            ) {
                RetroAchievementsManager.setEnabled(context, it)
                refresh++
            }
            if (RetroAchievementsManager.HARDCORE_SUPPORTED) {
                RetroSettingSwitch(
                    stringResource(R.string.retro_scr_hardcore_default),
                    RetroAchievementsManager.isHardcorePreferred(context),
                ) {
                    if (it) {
                        confirmHardcore = true
                    } else {
                        RetroAchievementsManager.setHardcorePreferred(context, false)
                        refresh++
                    }
                }
            }
            if (RetroAchievementsManager.isLoggedIn(context)) {
                OutlinedButton(
                    onClick = {
                        RetroAchievementsManager.logout(context)
                        refresh++
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .paneNavItem(
                                cornerRadius = 8.dp,
                                onActivate = {
                                    RetroAchievementsManager.logout(context)
                                    refresh++
                                },
                                highlightColor = Color(0xFF4FC3F7),
                                tapToSelect = true,
                            ),
                ) {
                    Text(stringResource(R.string.retro_scr_sign_out))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.weight(1f)) {
                RetroSettingGroup {
                    RetroGroupTitle(stringResource(R.string.retro_scr_playstation_bios))
                    val dir = RetroCoreManager.systemDir(context)
                    val installed = RetroSystems.PSX.biosFiles.filter { File(dir, it).isFile }
                    RetroInfoRow(
                        stringResource(R.string.retro_scr_installed),
                        if (installed.isEmpty()) {
                            stringResource(R.string.retro_scr_none_ps1_bios)
                        } else {
                            installed.joinToString(", ")
                        },
                    )
                    Button(
                        onClick = { runCatching { biosPicker.launch(arrayOf("*/*")) } },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .paneNavItem(
                                    cornerRadius = 8.dp,
                                    onActivate = { runCatching { biosPicker.launch(arrayOf("*/*")) } },
                                    highlightColor = Color(0xFF4FC3F7),
                                    tapToSelect = true,
                                ),
                    ) {
                        Text(stringResource(R.string.retro_scr_import_ps1_bios))
                    }
                    if (installed.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                val n = RetroBiosImport.deletePs1Bios(context)
                                Toast.makeText(
                                    context,
                                    if (n > 0) {
                                        context.getString(R.string.retro_scr_ps1_bios_removed)
                                    } else {
                                        context.getString(R.string.retro_scr_no_bios_to_remove)
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                                refresh++
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .paneNavItem(
                                        cornerRadius = 8.dp,
                                        onActivate = {
                                            val n = RetroBiosImport.deletePs1Bios(context)
                                            Toast.makeText(
                                                context,
                                                if (n > 0) {
                                                    context.getString(R.string.retro_scr_ps1_bios_removed)
                                                } else {
                                                    context.getString(R.string.retro_scr_no_bios_to_remove)
                                                },
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            refresh++
                                        },
                                        highlightColor = Color(0xFF4FC3F7),
                                        tapToSelect = true,
                                    ),
                        ) {
                            Text(stringResource(R.string.retro_scr_remove_ps1_bios))
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                RetroSettingGroup {
                    RetroGroupTitle(stringResource(R.string.retro_scr_playstation_2_bios))
                    val ps2Installed = RetroBiosImport.installedPs2Bios(context)
                    RetroInfoRow(
                        stringResource(R.string.retro_scr_installed),
                        if (ps2Installed.isEmpty()) {
                            stringResource(R.string.retro_scr_none_ps2_bios)
                        } else {
                            ps2Installed.joinToString(", ")
                        },
                    )
                    Button(
                        onClick = { runCatching { ps2BiosPicker.launch(arrayOf("*/*")) } },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .paneNavItem(
                                    cornerRadius = 8.dp,
                                    onActivate = { runCatching { ps2BiosPicker.launch(arrayOf("*/*")) } },
                                    highlightColor = Color(0xFF4FC3F7),
                                    tapToSelect = true,
                                ),
                    ) {
                        Text(stringResource(R.string.retro_scr_import_ps2_bios))
                    }
                    if (ps2Installed.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                val n = RetroBiosImport.deletePs2Bios(context)
                                Toast.makeText(
                                    context,
                                    if (n > 0) {
                                        context.getString(R.string.retro_scr_ps2_bios_removed)
                                    } else {
                                        context.getString(R.string.retro_scr_no_bios_to_remove)
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                                refresh++
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .paneNavItem(
                                        cornerRadius = 8.dp,
                                        onActivate = {
                                            val n = RetroBiosImport.deletePs2Bios(context)
                                            Toast.makeText(
                                                context,
                                                if (n > 0) {
                                                    context.getString(R.string.retro_scr_ps2_bios_removed)
                                                } else {
                                                    context.getString(R.string.retro_scr_no_bios_to_remove)
                                                },
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            refresh++
                                        },
                                        highlightColor = Color(0xFF4FC3F7),
                                        tapToSelect = true,
                                    ),
                        ) {
                            Text(stringResource(R.string.retro_scr_remove_ps2_bios))
                        }
                    }
                }
            }
        }

        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_scr_library_artwork))
            RetroSettingSwitch(
                stringResource(R.string.retro_scr_retro_case_art),
                RetroBoxart.caseArtEnabled(context),
            ) { RetroBoxart.setCaseArtEnabled(context, it); refresh++ }
            RetroInfoRow(
                stringResource(R.string.retro_scr_box_art),
                stringResource(R.string.retro_scr_box_art_desc),
            )
        }

        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_scr_roms_folder))
            val romsDir = RetroDefaults.romsDir(context)
            RetroInfoRow(
                stringResource(R.string.retro_scr_folder),
                romsDir ?: stringResource(R.string.retro_scr_roms_not_set),
            )
            RetroInfoRow(
                stringResource(R.string.retro_scr_auto_import),
                stringResource(R.string.retro_scr_auto_import_desc),
            )
            val pickRomsFolder = {
                val activity = context as? android.app.Activity
                if (activity != null) {
                    com.winlator.cmod.shared.android.DirectoryPickerDialog.show(
                        activity = activity,
                        initialPath = romsDir
                            ?: android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS,
                            ).absolutePath,
                        title = context.getString(R.string.retro_scr_select_roms_folder_title),
                    ) { path ->
                        RetroDefaults.setRomsDir(context, path)
                        Thread {
                            val result = RetroRomScanner.scan(context, File(path))
                            activity.runOnUiThread {
                                Toast.makeText(context, scanMessage(context, result), Toast.LENGTH_SHORT).show()
                                refresh++
                            }
                        }.start()
                    }
                }
            }
            Button(
                onClick = { pickRomsFolder() },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .paneNavItem(
                            cornerRadius = 8.dp,
                            onActivate = { pickRomsFolder() },
                            highlightColor = Color(0xFF4FC3F7),
                            tapToSelect = true,
                        ),
            ) {
                Text(if (romsDir == null) stringResource(R.string.retro_scr_select_roms_folder_button) else stringResource(R.string.retro_scr_change_roms_folder_button))
            }
            if (romsDir != null) {
                val scanNow = {
                    val activity = context as? android.app.Activity
                    Thread {
                        val result = RetroRomScanner.scanConfiguredFolder(context)
                        activity?.runOnUiThread {
                            Toast.makeText(context, scanMessage(context, result), Toast.LENGTH_SHORT).show()
                            refresh++
                        }
                    }.start()
                }
                OutlinedButton(
                    onClick = { scanNow() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .paneNavItem(
                                cornerRadius = 8.dp,
                                onActivate = { scanNow() },
                                highlightColor = Color(0xFF4FC3F7),
                                tapToSelect = true,
                            ),
                ) {
                    Text(stringResource(R.string.retro_scr_scan_now))
                }
            }
        }

        RetroLibretroHudSection(RetroDefaults.hud(context, "")) {
            RetroDefaults.setHud(context, "", it)
            refresh++
        }

        Text(
            stringResource(R.string.retro_scr_console_defaults),
            color = PageSub,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.retro_scr_console_defaults_desc),
            color = PageSub,
            style = MaterialTheme.typography.labelMedium,
        )

        RetroSystems.ALL.sortedBy { it.displayName }.forEach { console ->
            val sys = console.id
            val expanded = expandedConsole == sys
            RetroSettingGroup {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { expandedConsole = if (expanded) null else sys }
                            .paneNavItem(
                                cornerRadius = 8.dp,
                                onActivate = { expandedConsole = if (expanded) null else sys },
                                highlightColor = Color(0xFF4FC3F7),
                                tapToSelect = true,
                            )
                            .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        console.displayName,
                        color = PageText,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (expanded) "▲" else "▼",
                        color = PageSub,
                    )
                }
                if (expanded) {
                    RetroSettingSwitch(
                        stringResource(R.string.settings_frame_generation_title),
                        RetroDefaults.frameGen(context, sys),
                        subtitle = stringResource(R.string.retro_gs_frame_generation_subtitle),
                    ) { RetroDefaults.setFrameGen(context, sys, it); refresh++ }
                }
                if (expanded && console.isExternal) {
                    val ps2Prefs = context.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE)
                    val rendererKeys = listOf("vulkan", "opengl", "software")
                    val rendererLabels = listOf(
                        stringResource(R.string.retro_ps2_renderer_vulkan),
                        stringResource(R.string.retro_ps2_renderer_opengl),
                        stringResource(R.string.retro_ps2_renderer_software),
                    )
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_scr_renderer),
                        entries = rendererLabels,
                        selectedIndex = rendererKeys.indexOf(ps2Prefs.getString("wn.ps2.renderer", "vulkan")).coerceAtLeast(0),
                        onSelected = { ps2Prefs.edit().putString("wn.ps2.renderer", rendererKeys[it]).apply(); refresh++ },
                    )
                    val ps2Drivers = remember { com.armsx2.CustomDriver.listInstalled(context) }
                    val driverIds = listOf("") + ps2Drivers.map { it.id }
                    val driverLabels = listOf(stringResource(R.string.retro_gpu_driver_system)) + ps2Drivers.map { it.name }
                    val curDriver = (ps2Prefs.getString("wn.ps2.driver", "") ?: "").let { if (it.equals("system", true)) "" else it }
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_gpu_driver),
                        entries = driverLabels,
                        selectedIndex = driverIds.indexOf(curDriver).coerceAtLeast(0),
                        onSelected = { ps2Prefs.edit().putString("wn.ps2.driver", driverIds[it]).apply(); refresh++ },
                    )
                    if (ps2Drivers.isEmpty()) {
                        Text(
                            stringResource(R.string.retro_gpu_driver_hint),
                            color = PageSub,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    com.winlator.cmod.feature.retro.Ps2TurnipFlags(ps2Prefs, refresh) { refresh++ }
                    val ps2Scales = listOf(1f, 1.5f, 2f, 3f, 4f)
                    val ps2ScaleLabels = listOf(
                        stringResource(R.string.retro_gs_scale_1x_native),
                        stringResource(R.string.retro_gs_scale_1_5x),
                        stringResource(R.string.retro_gs_scale_2x),
                        stringResource(R.string.retro_gs_scale_3x),
                        stringResource(R.string.retro_gs_scale_4x),
                    )
                    val curScale = ps2Prefs.getFloat("wn.ps2.upscale", 1f)
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_scr_upscale_resolution),
                        entries = ps2ScaleLabels,
                        selectedIndex = ps2Scales.indexOfFirst { kotlin.math.abs(it - curScale) < 0.01f }.coerceAtLeast(0),
                        onSelected = { ps2Prefs.edit().putFloat("wn.ps2.upscale", ps2Scales[it]).apply(); refresh++ },
                    )
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_scr_aspect_ratio),
                        entries = listOf(
                            stringResource(R.string.retro_scr_aspect_stretch),
                            stringResource(R.string.retro_scr_aspect_auto_standard),
                            stringResource(R.string.retro_scr_aspect_4_3),
                            stringResource(R.string.retro_scr_aspect_16_9),
                        ),
                        selectedIndex = ps2Prefs.getInt("wn.ps2.aspect", 1).coerceIn(0, 3),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.aspect", it).apply(); refresh++ },
                    )
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_gs_fmv_aspect_ratio),
                        entries = listOf(
                            stringResource(R.string.retro_gs_off),
                            stringResource(R.string.retro_gs_aspect_auto_standard),
                            stringResource(R.string.retro_gs_aspect_4_3),
                            stringResource(R.string.retro_gs_aspect_16_9),
                        ),
                        selectedIndex = ps2Prefs.getInt("wn.ps2.fmvaspect", 0).coerceIn(0, 3),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.fmvaspect", it).apply(); refresh++ },
                    )
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_gs_deinterlace_mode),
                        entries = listOf(
                            stringResource(R.string.retro_gs_deint_auto),
                            stringResource(R.string.retro_gs_deint_off),
                            stringResource(R.string.retro_gs_deint_weave_tff),
                            stringResource(R.string.retro_gs_deint_weave_bff),
                            stringResource(R.string.retro_gs_deint_bob_tff),
                            stringResource(R.string.retro_gs_deint_bob_bff),
                            stringResource(R.string.retro_gs_deint_blend_tff),
                            stringResource(R.string.retro_gs_deint_blend_bff),
                            stringResource(R.string.retro_gs_deint_adaptive_tff),
                            stringResource(R.string.retro_gs_deint_adaptive_bff),
                        ),
                        selectedIndex = ps2Prefs.getInt("wn.ps2.deinterlace", 0).coerceIn(0, 9),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.deinterlace", it).apply(); refresh++ },
                    )
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_scr_display_filter),
                        entries = listOf(
                            stringResource(R.string.retro_scr_filter_nearest),
                            stringResource(R.string.retro_scr_filter_bilinear_smooth),
                            stringResource(R.string.retro_scr_filter_bilinear_sharp),
                        ),
                        selectedIndex = ps2Prefs.getInt("wn.ps2.displayfilter", 1).coerceIn(0, 2),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.displayfilter", it).apply(); refresh++ },
                    )
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_scr_texture_filter),
                        entries = listOf(
                            stringResource(R.string.retro_scr_filter_nearest),
                            stringResource(R.string.retro_scr_filter_bilinear_forced),
                            stringResource(R.string.retro_scr_filter_bilinear_ps2),
                            stringResource(R.string.retro_scr_filter_bilinear_sprites),
                        ),
                        selectedIndex = ps2Prefs.getInt("wn.ps2.filter", 2).coerceIn(0, 3),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.filter", it).apply(); refresh++ },
                    )
                    RetroSettingSwitch(stringResource(R.string.retro_scr_mipmapping), ps2Prefs.getBoolean("wn.ps2.mipmap", true)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.mipmap", it).apply(); refresh++
                    }
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_scr_blending_accuracy),
                        entries = listOf(
                            stringResource(R.string.retro_scr_blend_minimum),
                            stringResource(R.string.retro_scr_blend_basic),
                            stringResource(R.string.retro_scr_blend_medium),
                            stringResource(R.string.retro_scr_blend_high),
                            stringResource(R.string.retro_scr_blend_full),
                            stringResource(R.string.retro_scr_blend_maximum),
                        ),
                        selectedIndex = ps2Prefs.getInt("wn.ps2.blend", 1).coerceIn(0, 5),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.blend", it).apply(); refresh++ },
                    )
                    RetroSettingSwitch(stringResource(R.string.retro_gs_anti_blur), ps2Prefs.getBoolean("wn.ps2.antiblur", true)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.antiblur", it).apply(); refresh++
                    }
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_scr_crt_tv_shader),
                        entries = listOf(
                            stringResource(R.string.retro_scr_shader_off),
                            stringResource(R.string.retro_scr_shader_scanline),
                            stringResource(R.string.retro_scr_shader_diagonal),
                            stringResource(R.string.retro_scr_shader_triangular),
                            stringResource(R.string.retro_scr_shader_wave),
                            stringResource(R.string.retro_scr_shader_lottes),
                            stringResource(R.string.retro_scr_shader_4xrgss),
                            stringResource(R.string.retro_scr_shader_nxagss),
                        ),
                        selectedIndex = ps2Prefs.getInt("wn.ps2.tvshader", 0).coerceIn(0, 7),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.tvshader", it).apply(); refresh++ },
                    )
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_scr_frame_skip),
                        entries = listOf(
                            stringResource(R.string.retro_scr_skip_off),
                            stringResource(R.string.retro_scr_skip_1),
                            stringResource(R.string.retro_scr_skip_2),
                            stringResource(R.string.retro_scr_skip_3),
                        ),
                        selectedIndex = ps2Prefs.getInt("wn.ps2.frameskip", 0).coerceIn(0, 3),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.frameskip", it).apply(); refresh++ },
                    )
                    RetroSettingSwitch(stringResource(R.string.retro_gs_widescreen_patches), ps2Prefs.getBoolean("wn.ps2.widescreen", false)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.widescreen", it).apply(); refresh++
                    }
                    RetroSettingSwitch(stringResource(R.string.retro_gs_no_interlace_patches), ps2Prefs.getBoolean("wn.ps2.nointerlace", false)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.nointerlace", it).apply(); refresh++
                    }
                    val eeRates = listOf(-3, -2, -1, 0, 1, 2, 3)
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_scr_ee_cycle_rate),
                        entries = listOf(
                            stringResource(R.string.retro_scr_ee_rate_50),
                            stringResource(R.string.retro_scr_ee_rate_60),
                            stringResource(R.string.retro_scr_ee_rate_75),
                            stringResource(R.string.retro_scr_ee_rate_100_default),
                            stringResource(R.string.retro_scr_ee_rate_130),
                            stringResource(R.string.retro_scr_ee_rate_180),
                            stringResource(R.string.retro_scr_ee_rate_300),
                        ),
                        selectedIndex = eeRates.indexOf(ps2Prefs.getInt("wn.ps2.eeRate", 0).coerceIn(-3, 3)).coerceAtLeast(0),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.eeRate", eeRates[it]).apply(); refresh++ },
                    )
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_scr_ee_cycle_skip),
                        entries = listOf(
                            stringResource(R.string.retro_scr_skip_off),
                            stringResource(R.string.retro_scr_skip_num_1),
                            stringResource(R.string.retro_scr_skip_num_2),
                            stringResource(R.string.retro_scr_skip_num_3),
                        ),
                        selectedIndex = ps2Prefs.getInt("wn.ps2.eeSkip", 0).coerceIn(0, 3),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.eeSkip", it).apply(); refresh++ },
                    )
                    RetroSettingSwitch(stringResource(R.string.retro_scr_mtvu), ps2Prefs.getBoolean("wn.ps2.mtvu", true)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.mtvu", it).apply(); refresh++
                    }
                    RetroSettingSwitch(stringResource(R.string.retro_scr_instant_vu1), ps2Prefs.getBoolean("wn.ps2.instantVu1", true)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.instantVu1", it).apply(); refresh++
                    }
                    RetroSettingSwitch(stringResource(R.string.retro_gs_vu_flag_hack), ps2Prefs.getBoolean("wn.ps2.vuFlagHack", true)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.vuFlagHack", it).apply(); refresh++
                    }
                    RetroSettingSwitch(stringResource(R.string.retro_gs_intc_spin), ps2Prefs.getBoolean("wn.ps2.intc", true)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.intc", it).apply(); refresh++
                    }
                    RetroSettingSwitch(stringResource(R.string.retro_gs_wait_loop), ps2Prefs.getBoolean("wn.ps2.waitloop", true)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.waitloop", it).apply(); refresh++
                    }
                    RetroSettingSwitch(stringResource(R.string.retro_scr_fast_cdvd), ps2Prefs.getBoolean("wn.ps2.fastCdvd", false)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.fastCdvd", it).apply(); refresh++
                    }
                    RetroSettingSwitch(stringResource(R.string.retro_gs_fast_boot), ps2Prefs.getBoolean("wn.ps2.fastboot", true)) {
                        ps2Prefs.edit().putBoolean("wn.ps2.fastboot", it).apply(); refresh++
                    }
                    RetroSettingSwitch(
                        stringResource(R.string.retro_scr_touch_controls),
                        RetroDefaults.touchControls(context, sys),
                    ) { RetroDefaults.setTouchControls(context, sys, it); refresh++ }
                        RetroSettingSwitch(
                            stringResource(R.string.retro_gs_adaptive_sticks),
                            RetroDefaults.adaptiveSticks(context, sys),
                            subtitle = stringResource(R.string.retro_gs_adaptive_sticks_subtitle),
                        ) { RetroDefaults.setAdaptiveSticks(context, sys, it); refresh++ }
                        RetroSettingSwitch(
                            stringResource(R.string.retro_ps2_show_l3r3),
                            ps2Prefs.getBoolean("wn.ps2.showl3r3", true),
                            subtitle = stringResource(R.string.retro_ps2_show_l3r3_subtitle),
                        ) { ps2Prefs.edit().putBoolean("wn.ps2.showl3r3", it).apply(); refresh++ }
                    RetroSettingSwitch(
                        stringResource(R.string.retro_scr_sound),
                        !ps2Prefs.getBoolean("wn.ps2.muted", false),
                    ) { ps2Prefs.edit().putBoolean("wn.ps2.muted", !it).apply(); refresh++ }
                    RetroSettingSwitch(
                        stringResource(R.string.retro_scr_swap_stereo),
                        ps2Prefs.getBoolean("wn.ps2.swap", false),
                    ) { ps2Prefs.edit().putBoolean("wn.ps2.swap", it).apply(); refresh++ }
                    RetroSettingSwitch(
                        stringResource(R.string.retro_gs_time_stretch),
                        ps2Prefs.getBoolean("wn.ps2.timestretch", true),
                    ) { ps2Prefs.edit().putBoolean("wn.ps2.timestretch", it).apply(); refresh++ }
                    val bufferValues = listOf(40, 50, 60, 80, 100, 120, 160, 200)
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_gs_audio_buffer),
                        entries = bufferValues.map { context.getString(R.string.retro_gs_ms, it) },
                        selectedIndex = bufferValues.indexOf(ps2Prefs.getInt("wn.ps2.audiobuffer", 50)).coerceAtLeast(0),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.audiobuffer", bufferValues[it]).apply(); refresh++ },
                    )
                    val latencyValues = listOf(10, 15, 20, 30, 40, 60, 80, 100)
                    RetroSettingDropdown(
                        label = stringResource(R.string.retro_gs_audio_latency),
                        entries = latencyValues.map { context.getString(R.string.retro_gs_ms, it) },
                        selectedIndex = latencyValues.indexOf(ps2Prefs.getInt("wn.ps2.audiolatency", 20)).coerceAtLeast(0),
                        onSelected = { ps2Prefs.edit().putInt("wn.ps2.audiolatency", latencyValues[it]).apply(); refresh++ },
                    )
                    RetroSettingSwitch(
                        stringResource(R.string.retro_scr_performance_hud),
                        RetroHudSupport.resolvePs2HudEnabled(context),
                    ) {
                        RetroHudSupport.setPs2HudEnabled(context, it)
                        RetroDefaults.setHud(context, sys, it)
                        refresh++
                    }
                    RetroSettingSwitch(
                        stringResource(R.string.retro_scr_enable_online_dev9),
                        ps2Prefs.getBoolean("wn.ps2.net.enable", true),
                    ) { ps2Prefs.edit().putBoolean("wn.ps2.net.enable", it).apply(); refresh++ }
                    if (ps2Prefs.getBoolean("wn.ps2.net.enable", true)) {
                        val devices = listOf("Auto", "Wi-Fi")
                        RetroSettingDropdown(
                            label = stringResource(R.string.retro_scr_ethernet_device),
                            entries = listOf(
                                stringResource(R.string.retro_scr_net_auto),
                                stringResource(R.string.retro_scr_net_wifi),
                            ),
                            selectedIndex = devices.indexOf(ps2Prefs.getString("wn.ps2.net.ethdevice", "Auto")).coerceAtLeast(0),
                            onSelected = { ps2Prefs.edit().putString("wn.ps2.net.ethdevice", devices[it]).apply(); refresh++ },
                        )
                        val dnsModes = listOf("Manual", "Auto", "Internal")
                        RetroSettingDropdown(
                            label = stringResource(R.string.retro_scr_dns_mode),
                            entries = listOf(
                                stringResource(R.string.retro_scr_dns_manual),
                                stringResource(R.string.retro_scr_net_auto),
                                stringResource(R.string.retro_scr_dns_internal),
                            ),
                            selectedIndex = dnsModes.indexOf(ps2Prefs.getString("wn.ps2.net.dnsmode", "Manual")).coerceAtLeast(0),
                            onSelected = { ps2Prefs.edit().putString("wn.ps2.net.dnsmode", dnsModes[it]).apply(); refresh++ },
                        )
                        RetroSettingTextField(stringResource(R.string.retro_scr_primary_dns), ps2Prefs.getString("wn.ps2.net.dns1", PS2_DEFAULT_DNS).orEmpty(), PS2_DEFAULT_DNS) { ps2Prefs.edit().putString("wn.ps2.net.dns1", it).apply(); refresh++ }
                        RetroSettingTextField(stringResource(R.string.retro_scr_secondary_dns), ps2Prefs.getString("wn.ps2.net.dns2", "").orEmpty(), stringResource(R.string.retro_scr_optional)) { ps2Prefs.edit().putString("wn.ps2.net.dns2", it).apply(); refresh++ }
                        RetroSettingSwitch(stringResource(R.string.retro_gs_dnas_bypass), ps2Prefs.getBoolean(com.winlator.cmod.feature.retro.Ps2DnasBypass.PREF, true), subtitle = stringResource(R.string.retro_gs_dnas_bypass_subtitle)) { ps2Prefs.edit().putBoolean(com.winlator.cmod.feature.retro.Ps2DnasBypass.PREF, it).apply(); refresh++ }
                        RetroSettingSwitch(stringResource(R.string.retro_scr_auto_ip_dhcp), ps2Prefs.getBoolean("wn.ps2.net.dhcp", true)) { ps2Prefs.edit().putBoolean("wn.ps2.net.dhcp", it).apply(); refresh++ }
                    }
                }
                if (expanded && !console.isExternal) {
                    if (RetroCoreManager.usesDolphinCore(console)) {
                        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        val gcDrivers = remember { com.armsx2.CustomDriver.listInstalled(context) }
                        val gcDriverIds = remember(gcDrivers) { listOf("") + gcDrivers.map { it.id } }
                        val gcDriverLabels = listOf(stringResource(R.string.retro_gpu_driver_system)) + gcDrivers.map { it.name }
                        val curGcDriver = (prefs.getString(DolphinEmbedLaunch.DRIVER_PREF, "") ?: "").let { if (it.equals("system", true)) "" else it }
                        RetroSettingDropdown(
                            label = stringResource(R.string.retro_gpu_driver),
                            entries = gcDriverLabels,
                            selectedIndex = gcDriverIds.indexOf(curGcDriver).coerceAtLeast(0),
                            onSelected = { prefs.edit().putString(DolphinEmbedLaunch.DRIVER_PREF, gcDriverIds[it]).apply(); refresh++ },
                        )
                    }
                    val embeddedDolphin =
                        RetroCoreManager.usesDolphinCore(console) &&
                            RetroShortcuts.embeddedDolphinEnabled(context)
                    if (!embeddedDolphin) {
                        RetroSettingDropdown(
                            label = stringResource(R.string.retro_scr_shader),
                            entries = listOf(
                                stringResource(R.string.retro_scr_shader_default),
                                stringResource(R.string.retro_scr_shader_crt),
                                stringResource(R.string.retro_scr_shader_lcd),
                                stringResource(R.string.retro_scr_shader_sharp),
                            ),
                            selectedIndex = SHADER_KEYS.indexOf(RetroDefaults.shader(context, sys)).coerceAtLeast(0),
                            onSelected = { RetroDefaults.setShader(context, sys, SHADER_KEYS[it]); refresh++ },
                        )
                        RetroSettingSwitch(
                            stringResource(R.string.retro_scr_sgsr_upscaling),
                            RetroDefaults.sgsr(context, sys),
                        ) { RetroDefaults.setSgsr(context, sys, it); refresh++ }
                        RetroSettingDropdown(
                            label = stringResource(R.string.retro_scr_upscale_resolution),
                            entries = listOf("2x", "4x", stringResource(R.string.retro_scr_upscale_native)),
                            selectedIndex = UPSCALE_KEYS.indexOf(RetroDefaults.upscale(context, sys)).coerceAtLeast(0),
                            onSelected = { RetroDefaults.setUpscale(context, sys, UPSCALE_KEYS[it]); refresh++ },
                        )
                    }
                    val consoleOptions = RetroCoreOptions.forSystem(console)
                    (consoleOptions.filterNot { it.advanced } + consoleOptions.filter { it.advanced })
                        .forEach { option ->
                            val current = RetroDefaults.coreOption(context, sys, option.key, option.defaultValue)
                            RetroSettingDropdown(
                                label = option.labelText(context),
                                entries = option.valueLabels,
                                selectedIndex = option.values.indexOf(current).coerceAtLeast(0),
                                onSelected = { RetroDefaults.setCoreOption(context, sys, option.key, option.values[it]); refresh++ },
                            )
                        }

                    RetroSettingSwitch(
                        stringResource(R.string.retro_scr_touch_controls),
                        RetroDefaults.touchControls(context, sys),
                    ) { RetroDefaults.setTouchControls(context, sys, it); refresh++ }
                        RetroSettingSwitch(
                            stringResource(R.string.retro_gs_adaptive_sticks),
                            RetroDefaults.adaptiveSticks(context, sys),
                            subtitle = stringResource(R.string.retro_gs_adaptive_sticks_subtitle),
                        ) { RetroDefaults.setAdaptiveSticks(context, sys, it); refresh++ }
                    RetroSettingSwitch(
                        stringResource(R.string.retro_scr_sound),
                        RetroDefaults.audio(context, sys),
                    ) { RetroDefaults.setAudio(context, sys, it); refresh++ }
                    if (RetroOnlineSupport.supportsMultiplayerUi(sys)) {
                        RetroNetplaySettingsSection(
                            systemId = sys,
                            version = refresh,
                            onChanged = { refresh++ },
                        )
                    } else if (RetroOnlineSupport.supportsDolphinNetplay(sys)) {
                        DolphinNetplaySettingsSection(
                            systemId = sys,
                            version = refresh,
                            onChanged = { refresh++ },
                        )
                    }
                }
            }
        }
    }
    }
}

private fun scanMessage(context: android.content.Context, result: RetroRomScanner.Result): String {
    val parts = buildList {
        if (result.added > 0) add(context.getString(R.string.retro_scan_added, result.added))
        if (result.removed > 0) add(context.getString(R.string.retro_scan_removed, result.removed))
    }
    return if (parts.isEmpty()) {
        context.getString(R.string.retro_scan_up_to_date)
    } else {
        context.getString(R.string.retro_scan_roms, parts.joinToString(", "))
    }
}

@Composable
fun RetroHardcoreConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = Color(0xFF1A9FFF)
    com.winlator.cmod.shared.ui.dialog.WinNativeDialogShell(
        onDismiss = onDismiss,
        title = stringResource(R.string.retro_scr_enable_hardcore_title),
    ) {
        Text(
            stringResource(R.string.retro_scr_enable_hardcore_body),
            color = com.winlator.cmod.shared.theme.WinNativeTextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(com.winlator.cmod.shared.theme.WinNativeOutline))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            com.winlator.cmod.shared.ui.dialog.WinNativeDialogButton(
                label = stringResource(R.string.retro_scr_cancel),
                textColor = com.winlator.cmod.shared.theme.WinNativeTextPrimary,
                onClick = onDismiss,
            )
            com.winlator.cmod.shared.ui.dialog.WinNativeDialogButton(
                label = stringResource(R.string.retro_scr_enable),
                textColor = accent,
                backgroundColor = accent.copy(alpha = 0.12f),
                borderColor = accent.copy(alpha = 0.3f),
                onClick = onConfirm,
            )
        }
    }
}

@Composable
private fun RetroConsoleBundleGroup(
    refreshKey: Int,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableStateOf(0L to 0L) }
    var checkFailed by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var published by remember { mutableStateOf<RetroBundle.Version?>(null) }
    var releases by remember { mutableStateOf<List<RetroBundle.Release>>(emptyList()) }
    var releasesFailed by remember { mutableStateOf(false) }
    var selectedTag by remember { mutableStateOf(RetroBundle.selectedTag(context)) }
    val installed = remember(refreshKey, busy) { RetroBundle.installed(context) }
    val pinned = selectedTag != RetroBundle.LATEST
    val differs = published != null && published!!.sha256 != installed?.sha256

    val latestLabel = stringResource(R.string.retro_scr_consoles_release_latest)
    val verifyingText = stringResource(R.string.retro_scr_consoles_verifying)
    val installingText = stringResource(R.string.retro_scr_consoles_installing)

    val entries = remember(releases, latestLabel) { listOf(latestLabel) + releases.map { it.label } }
    val selectedIndex =
        remember(selectedTag, releases) {
            if (selectedTag == RetroBundle.LATEST) {
                0
            } else {
                releases.indexOfFirst { it.tag == selectedTag }.let { if (it < 0) 0 else it + 1 }
            }
        }

    suspend fun check(tag: String): RetroBundle.Version? {
        checking = true
        installError = null
        val result = withContext(Dispatchers.IO) { RetroBundle.published(tag) }
        checking = false
        published = result.getOrNull()
        checkFailed = result.isFailure
        return result.getOrNull()
    }

    suspend fun install(version: RetroBundle.Version) {
        busy = true
        progress = 0f
        step = null
        downloaded = 0L to 0L
        installError = null
        val result =
            withContext(Dispatchers.IO) {
                RetroBundle.install(context, version, selectedTag) { p ->
                    when (p) {
                        is RetroBundle.Progress.Downloading -> {
                            downloaded = p.bytes to p.total
                            if (p.total > 0) progress = p.bytes.toFloat() / p.total
                        }
                        RetroBundle.Progress.Verifying -> step = verifyingText
                        RetroBundle.Progress.Extracting -> step = installingText
                    }
                }
            }
        busy = false
        step = null
        installError = result.exceptionOrNull()?.let { it.message ?: it.toString() }
        onChanged()
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        check(selectedTag)
        val list = withContext(Dispatchers.IO) { RetroBundle.releases() }
        releases = list.getOrDefault(emptyList())
        releasesFailed = list.isFailure
    }

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_scr_consoles_group))
        RetroInfoRow(
            stringResource(R.string.retro_scr_consoles_state),
            when {
                busy && step != null -> step!!
                busy && downloaded.second > 0 ->
                    stringResource(
                        R.string.retro_scr_consoles_downloading,
                        downloaded.first / (1024 * 1024),
                        downloaded.second / (1024 * 1024),
                    )
                busy -> stringResource(R.string.retro_scr_consoles_working)
                installError != null -> installError!!
                checking -> stringResource(R.string.retro_scr_consoles_checking)
                installed == null -> stringResource(R.string.retro_scr_consoles_missing)
                differs && pinned ->
                    stringResource(
                        R.string.retro_scr_consoles_selected_available,
                        published!!.day,
                        published!!.size / (1024 * 1024),
                    )
                differs ->
                    stringResource(
                        R.string.retro_scr_consoles_update_available,
                        published!!.day,
                        published!!.size / (1024 * 1024),
                    )
                checkFailed -> stringResource(R.string.retro_scr_consoles_installed_no_check, installed.day)
                pinned -> stringResource(R.string.retro_scr_consoles_installed_pinned, installed.day)
                else -> stringResource(R.string.retro_scr_consoles_installed, installed.day)
            },
        )
        RetroSettingDropdown(
            label =
                stringResource(
                    when {
                        releases.isEmpty() && releasesFailed -> R.string.retro_scr_consoles_releases_failed
                        releases.isEmpty() -> R.string.retro_scr_consoles_releases_loading
                        else -> R.string.retro_scr_consoles_release
                    },
                ),
            entries = entries,
            selectedIndex = selectedIndex,
            onSelected = { index ->
                val tag = if (index == 0) RetroBundle.LATEST else releases[index - 1].tag
                if (tag != selectedTag) {
                    selectedTag = tag
                    RetroBundle.selectTag(context, tag)
                    published = null
                    scope.launch { check(tag) }
                }
            },
        )
        if (busy && progress > 0f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        OutlinedButton(
            enabled = !busy && !checking,
            onClick = {
                scope.launch {
                    if (installed == null || differs || pinned) {
                        (published ?: check(selectedTag))?.let { install(it) }
                    } else {
                        check(selectedTag)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(
                stringResource(
                    when {
                        installed == null -> R.string.retro_scr_consoles_download
                        differs && pinned -> R.string.retro_scr_consoles_install_selected
                        differs -> R.string.retro_scr_consoles_update
                        pinned -> R.string.retro_scr_consoles_reinstall
                        else -> R.string.retro_scr_consoles_check
                    },
                ),
            )
        }
    }
}
