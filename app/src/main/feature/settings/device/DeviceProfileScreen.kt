package com.winlator.cmod.feature.settings.device

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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.app.config.DeviceProfile
import com.winlator.cmod.app.config.DeviceProfileFeature
import com.winlator.cmod.app.config.DeviceProfileSettings
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.runtime.input.controls.InputControlsManager
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.paneNavItem

private val PageBg = Color(0xFF101018)
private val PageText = Color(0xFFF0F4FF)
private val PageSub = Color(0xFF93A6BC)
private val PageCard = Color(0xFF181822)
private val PageAccent = Color(0xFF4FC3F7)
private val PageMuted = Color(0xFF5A6B80)

@Composable
private fun SectionHeading(
    textRes: Int,
    descRes: Int,
    topPadding: Int,
) {
    Text(
        stringResource(textRes),
        color = PageSub,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = topPadding.dp),
    )
    Text(
        stringResource(descRes),
        color = PageSub,
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(Modifier.size(2.dp))
}

@Composable
private fun DeviceHeaderCard(
    profile: DeviceProfile,
    deviceLabel: String,
    overridden: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(PageAccent.copy(alpha = 0.08f))
                .border(1.dp, PageAccent.copy(alpha = 0.35f), shape)
                .clickable { onToggle() }
                .paneNavItem(
                    cornerRadius = 14.dp,
                    onActivate = onToggle,
                    highlightColor = PageAccent,
                    tapToSelect = true,
                ).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(PageAccent.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.TabletAndroid,
                contentDescription = null,
                tint = PageAccent,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                stringResource(profile.titleRes),
                color = PageText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                deviceLabel,
                color = PageSub,
                fontSize = 11.sp,
            )
            Text(
                stringResource(
                    if (overridden) {
                        R.string.device_profile_source_manual
                    } else {
                        R.string.device_profile_source_detected
                    },
                ),
                color = PageMuted,
                fontSize = 11.sp,
            )
        }

        Text(
            stringResource(R.string.device_profile_change),
            color = PageAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = PageAccent,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ProfileChoiceRow(
    profile: DeviceProfile,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(PageCard)
                .clickable { onSelect() }
                .paneNavItem(
                    cornerRadius = 12.dp,
                    onActivate = onSelect,
                    highlightColor = PageAccent,
                    tapToSelect = true,
                ).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(profile.titleRes),
                color = if (selected) PageAccent else PageText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(profile.summaryRes),
                color = PageSub,
                fontSize = 11.sp,
            )
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = PageAccent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FeatureRow(
    feature: DeviceProfileFeature,
    statusText: String?,
    portrait: Boolean,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(PageCard)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(feature.titleRes),
                color = if (feature.integrated) PageText else PageText.copy(alpha = 0.45f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(feature.summaryRes),
                color = if (feature.integrated) PageSub else PageSub.copy(alpha = 0.45f),
                fontSize = 11.sp,
            )
            if (portrait) {
                Spacer(Modifier.size(3.dp))
                FeatureStatus(feature, statusText)
            }
        }
        if (!portrait) {
            Spacer(Modifier.width(10.dp))
            FeatureStatus(feature, statusText)
        }
    }
}

@Composable
private fun FeatureStatus(
    feature: DeviceProfileFeature,
    statusText: String?,
) {
    if (feature.integrated) {
        Text(
            statusText.orEmpty(),
            color = PageAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    } else {
        Text(
            stringResource(R.string.common_ui_coming_soon),
            color = PageMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
    }
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
                .clip(RoundedCornerShape(12.dp))
                .background(PageCard)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(labelRes),
            color = PageSub,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = PageText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ActionRow(
    titleRes: Int,
    descRes: Int,
    onActivate: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(PageCard)
                .clickable { onActivate() }
                .paneNavItem(
                    cornerRadius = 12.dp,
                    onActivate = onActivate,
                    highlightColor = PageAccent,
                    tapToSelect = true,
                ).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(titleRes),
                color = PageText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(descRes),
                color = PageSub,
                fontSize = 11.sp,
            )
        }
        Icon(
            Icons.Outlined.Refresh,
            contentDescription = null,
            tint = PageAccent,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun DeviceProfileScreen(bridge: SettingsNavBridge? = null) {
    val context = LocalContext.current
    val contentNav = rememberSettingsContentNav(bridge)
    val portrait = isPortraitLayout()

    var selected by remember { mutableStateOf(DeviceProfileSettings.current(context)) }
    var expanded by remember { mutableStateOf(false) }
    var resyncSignal by remember { mutableStateOf(0) }

    val deviceLabel = remember { DeviceProfile.deviceLabel() }
    val overridden = selected != DeviceProfileSettings.detected(context)

    fun resyncLayouts() {
        runCatching { InputControlsManager(context).resyncAssetProfiles() }
        resyncSignal++
    }

    fun choose(profile: DeviceProfile) {
        expanded = false
        if (profile == selected) return
        DeviceProfileSettings.setCurrent(context, profile)
        selected = profile
        resyncLayouts()
    }

    val adaptiveStatus =
        stringResource(
            if (DeviceProfileSettings.adaptiveJoysticksDefault(context)) {
                R.string.device_profile_recommended_on
            } else {
                R.string.device_profile_recommended_off
            },
        )
    val layoutStatus =
        stringResource(
            if (selected == DeviceProfile.DEFAULT) {
                R.string.device_profile_layouts_stock
            } else {
                R.string.device_profile_layouts_tuned
            },
        )
    val libraryStatus =
        stringResource(
            if (DeviceProfileSettings.preferWideArtwork(context)) {
                R.string.device_profile_library_wide
            } else {
                R.string.device_profile_library_stock
            },
        )
    val sessionStatus =
        stringResource(
            if (DeviceProfileSettings.sessionActionCardMaxAspect(context) == Float.MAX_VALUE) {
                R.string.device_profile_session_stock
            } else {
                R.string.device_profile_session_square
            },
        )

    CompositionLocalProvider(LocalPaneNav provides contentNav) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(PageBg)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeading(
                textRes = R.string.device_profile_heading,
                descRes = R.string.device_profile_desc,
                topPadding = 4,
            )

            DeviceHeaderCard(
                profile = selected,
                deviceLabel = deviceLabel,
                overridden = overridden,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )

            if (expanded) {
                DeviceProfile.entries.forEach { profile ->
                    ProfileChoiceRow(
                        profile = profile,
                        selected = profile == selected,
                        onSelect = { choose(profile) },
                    )
                }
            }

            SectionHeading(
                textRes = R.string.device_profile_controls_heading,
                descRes = R.string.device_profile_controls_desc,
                topPadding = 10,
            )

            DeviceProfileFeature.entries.forEach { feature ->
                FeatureRow(
                    feature = feature,
                    statusText =
                        when (feature) {
                            DeviceProfileFeature.INPUT_CONTROL_LAYOUTS -> layoutStatus
                            DeviceProfileFeature.ADAPTIVE_JOYSTICKS -> adaptiveStatus
                            DeviceProfileFeature.LIBRARY_ICONS -> libraryStatus
                            DeviceProfileFeature.SESSION_MENU_SIZES -> sessionStatus
                            else -> null
                        },
                    portrait = portrait,
                )
            }

            ActionRow(
                titleRes = R.string.device_profile_reapply,
                descRes = R.string.device_profile_reapply_desc,
                onActivate = ::resyncLayouts,
            )

            SectionHeading(
                textRes = R.string.device_profile_device_heading,
                descRes = R.string.device_profile_device_desc,
                topPadding = 10,
            )

            InfoRow(R.string.device_profile_info_model, android.os.Build.MODEL.orEmpty())
            InfoRow(R.string.device_profile_info_board, android.os.Build.DEVICE.orEmpty())
            InfoRow(R.string.device_profile_info_manufacturer, android.os.Build.MANUFACTURER.orEmpty())
            InfoRow(
                R.string.device_profile_info_screen,
                screenSummary(context),
            )
        }
    }
}

private fun screenSummary(context: android.content.Context): String {
    val metrics = context.resources.displayMetrics
    val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
    val shortEdge = minOf(metrics.widthPixels, metrics.heightPixels)
    return "$longEdge x $shortEdge"
}
