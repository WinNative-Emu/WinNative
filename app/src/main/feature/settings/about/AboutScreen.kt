package com.winlator.cmod.feature.settings.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.paneNavItem

private val AboutBg = Color(0xFF101018)
private val AboutText = Color(0xFFF0F4FF)
private val AboutSub = Color(0xFF93A6BC)
private val AboutCard = Color(0xFF181822)
private val AboutAccent = Color(0xFF4FC3F7)
private val AboutWarn = Color(0xFFFFB74D)

fun isBrandedBuild(): Boolean = BuildConfig.VERSION_CODE != BuildConfig.TRUE_VERSION_CODE

fun buildInfoLines(): List<Pair<Int, String>> {
    val lines =
        mutableListOf(
            R.string.about_version to BuildConfig.VERSION_NAME,
            R.string.about_build_id to BuildConfig.BUILD_ID,
            R.string.about_version_code to BuildConfig.TRUE_VERSION_CODE.toString(),
            R.string.about_variant to "${BuildConfig.FLAVOR} · ${BuildConfig.BUILD_TYPE}",
            R.string.about_package to BuildConfig.APPLICATION_ID,
        )
    if (isBrandedBuild()) {
        lines += R.string.about_reported_version_code to BuildConfig.VERSION_CODE.toString()
    }
    return lines
}

private fun buildInfoClipText(context: Context): String =
    buildInfoLines().joinToString("\n") { (labelRes, value) ->
        "${context.getString(labelRes)}: $value"
    }

@Composable
private fun SectionHeading(
    textRes: Int,
    descRes: Int,
    topPadding: Int,
) {
    Text(
        stringResource(textRes),
        color = AboutSub,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = topPadding.dp),
    )
    Text(
        stringResource(descRes),
        color = AboutSub,
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(Modifier.size(2.dp))
}

@Composable
private fun InfoRow(
    labelRes: Int,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AboutCard)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(labelRes),
            color = AboutSub,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = AboutText,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BrandingCard() {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AboutWarn.copy(alpha = 0.08f))
                .border(1.dp, AboutWarn.copy(alpha = 0.35f), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(AboutWarn.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = AboutWarn,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                BuildConfig.APPLICATION_ID,
                color = AboutText,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            stringResource(R.string.about_branding_explain, BuildConfig.APPLICATION_ID),
            color = AboutSub,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun CopyRow(onCopy: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AboutAccent.copy(alpha = 0.08f))
                .border(1.dp, AboutAccent.copy(alpha = 0.35f), shape)
                .clickable { onCopy() }
                .paneNavItem(
                    cornerRadius = 14.dp,
                    onActivate = onCopy,
                    highlightColor = AboutAccent,
                    tapToSelect = true,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(AboutAccent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = null,
                tint = AboutAccent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.about_copy),
            color = AboutText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun AboutScreen(bridge: SettingsNavBridge? = null) {
    val context = LocalContext.current
    val contentNav = rememberSettingsContentNav(bridge)

    fun copyBuildInfo() {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("WinNative", buildInfoClipText(context)))
            Toast.makeText(context, R.string.about_copied, Toast.LENGTH_SHORT).show()
        }
    }

    CompositionLocalProvider(LocalPaneNav provides contentNav) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(AboutBg)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeading(
                textRes = R.string.about_app_heading,
                descRes = R.string.about_app_desc,
                topPadding = 4,
            )

            buildInfoLines().forEach { (labelRes, value) ->
                InfoRow(labelRes = labelRes, value = value)
            }

            if (isBrandedBuild()) {
                SectionHeading(
                    textRes = R.string.about_branding_heading,
                    descRes = R.string.about_branding_desc,
                    topPadding = 10,
                )
                BrandingCard()
            }

            Spacer(Modifier.size(2.dp))

            CopyRow(onCopy = ::copyBuildInfo)
        }
    }
}
