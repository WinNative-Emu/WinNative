package com.winlator.cmod.feature.retro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.paneNavItem

private val CreditsBg = Color(0xFF101018)
private val CreditsText = Color(0xFFF0F4FF)
private val CreditsSub = Color(0xFF93A6BC)

@Composable
fun RetroCreditsScreen(bridge: SettingsNavBridge? = null) {
    val context = LocalContext.current
    val contentNav = rememberSettingsContentNav(bridge)

    fun open(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ),
            )
        }
    }

    CompositionLocalProvider(LocalPaneNav provides contentNav) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(CreditsBg)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.retro_scr_credits_licenses),
                color = CreditsSub,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.retro_scr_credits_desc),
                color = CreditsSub,
                style = MaterialTheme.typography.labelMedium,
            )
            RetroSettingGroup {
                RETRO_CREDITS.forEach { credit ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { open(credit.url) }
                                .paneNavItem(
                                    cornerRadius = 8.dp,
                                    onActivate = { open(credit.url) },
                                    highlightColor = Color(0xFF4FC3F7),
                                    tapToSelect = true,
                                )
                                .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(credit.name, color = CreditsText, style = MaterialTheme.typography.bodyMedium)
                            Text(credit.detail, color = CreditsSub, fontSize = 11.sp)
                        }
                        Text(credit.license, color = CreditsSub, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

internal data class RetroCredit(
    val name: String,
    val detail: String,
    val license: String,
    val url: String,
)

internal val RETRO_CREDITS =
    listOf(
        RetroCredit("ARMSX2", "PlayStation 2", "GPL-3.0", "https://github.com/ARMSX2/ARMSX2"),
        RetroCredit("Beetle PSX", "PlayStation", "GPL-2.0", "https://github.com/libretro/beetle-psx-libretro"),
        RetroCredit("Camille LaVey", "Frame generation Vulkan chain — Eden Emulator Project", "GPL-3.0", "https://git.eden-emu.dev/eden-emu/eden"),
        RetroCredit("Dolphin", "GameCube / Wii", "GPL-2.0", "https://github.com/dolphin-emu/dolphin"),
        RetroCredit("FCEUmm", "NES", "GPL-2.0", "https://github.com/libretro/libretro-fceumm"),
        RetroCredit("Gambatte", "Game Boy / Color", "GPL-2.0", "https://github.com/libretro/gambatte-libretro"),
        RetroCredit("Genesis Plus GX", "Genesis / SMS / GG", "GPX", "https://github.com/libretro/Genesis-Plus-GX"),
        RetroCredit("LibretroDroid", "libretro frontend", "GPL-3.0", "https://github.com/Swordfish90/LibretroDroid"),
        RetroCredit("lsfg-vk", "Frame generation, upstream of the Eden port", "GPL-3.0", "https://github.com/PancakeTAS/lsfg-vk"),
        RetroCredit("mGBA", "Game Boy Advance", "MPL-2.0", "https://github.com/libretro/mgba"),
        RetroCredit("ParaLLEl N64", "Nintendo 64", "GPL-2.0", "https://github.com/libretro/parallel-n64"),
        RetroCredit("PCSX2", "PS2 upstream of ARMSX2", "GPL-3.0", "https://github.com/pcsx2/pcsx2"),
        RetroCredit("rcheevos", "RetroAchievements", "MIT", "https://github.com/RetroAchievements/rcheevos"),
        RetroCredit("Snapdragon GSR", "Upscaling", "BSD-3", "https://github.com/quic/snapdragon-gsr"),
        RetroCredit("Snes9x", "SNES", "Snes9x", "https://github.com/libretro/snes9x"),
        RetroCredit("SteamLite", "VAC secure-launch recipe for the Steam Launcher", "GPL-3.0", "https://github.com/The412Banner/winlator-contents"),
        RetroCredit("SwanStation", "PlayStation", "GPL-3.0", "https://github.com/libretro/swanstation"),
        RetroCredit("Winlator", "Windows-on-Android base", "GPL-3.0", "https://github.com/brunodev85/winlator"),
    )
