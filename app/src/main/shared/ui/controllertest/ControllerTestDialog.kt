package com.winlator.cmod.shared.ui.controllertest

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.cmod.R
import com.winlator.cmod.runtime.input.controls.ControlsProfile
import com.winlator.cmod.runtime.input.controls.ExternalController
import com.winlator.cmod.shared.theme.WinNativeAccent
import com.winlator.cmod.shared.theme.WinNativeOutline
import com.winlator.cmod.shared.theme.WinNativeSurface
import com.winlator.cmod.shared.theme.WinNativeTextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary
import kotlin.math.min

@Composable
fun ControllerTestDialog(
    onDismiss: () -> Unit,
    profile: ControlsProfile?,
    allProfiles: List<ControlsProfile>,
    onSelectProfile: (ControlsProfile) -> Unit,
    onCreateProfile: (String) -> Unit,
    onRenameProfile: (() -> Unit)?,
    onBindingsChanged: () -> Unit,
    onOpenAllBindings: (() -> Unit)? = null,
    onDeleteProfile: (() -> Unit)? = null,
    startInBind: Boolean = false,
) {
    val snap by ControllerTestBus.snapshot.collectAsState()
    var manualArt by remember { mutableStateOf<PadArt?>(null) }
    var bindMode by remember { mutableStateOf(startInBind) }
    val padName = remember { ExternalController.getControllers().firstOrNull()?.name }
    val padDescriptor = remember { ExternalController.getControllers().firstOrNull()?.id }

    DisposableEffect(Unit) {
        ControllerTestBus.setDialogOpen(true)
        onDispose {
            ControllerTestBus.setDialogOpen(false)
        }
    }

    BackHandler { onDismiss() }

    key(bindMode) {
        Dialog(
            onDismissRequest = onDismiss,
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                ),
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            val testMode = !bindMode
            SideEffect {
                ControllerTestBus.setDialogOpen(true)
                ControllerTestBus.setActive(testMode)
                window?.apply {
                    if (testMode) {
                        addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    } else {
                        clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    }
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setDimAmount(0f)
                }
            }

            val cfg = LocalConfiguration.current
            val w = min(760, cfg.screenWidthDp - 24).coerceAtLeast(300).dp
            val maxH = (cfg.screenHeightDp - 24).coerceAtLeast(220).dp
            val panelMax = (maxH.value - 76f).coerceAtLeast(180f).dp

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.padding(12.dp).width(w).heightIn(max = maxH),
                    shape = RoundedCornerShape(18.dp),
                    color = WinNativeSurface,
                    contentColor = WinNativeTextPrimary,
                    border = BorderStroke(1.dp, WinNativeOutline),
                    shadowElevation = 24.dp,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            TestBindToggle(bindMode) { bindMode = it }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.common_ui_close))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (bindMode) {
                            VisualControllerBinder(
                                profile = profile,
                                art = padArtOf(snap, padName),
                                snapshot = snap,
                                controllerId = snap?.deviceDescriptor?.takeIf { it.isNotEmpty() } ?: padDescriptor,
                            controllerName = snap?.deviceName?.takeIf { it.isNotEmpty() } ?: padName,
                                allProfiles = allProfiles,
                                onSelectProfile = onSelectProfile,
                                onCreateProfile = onCreateProfile,
                                onRenameProfile = onRenameProfile,
                                onSaved = onBindingsChanged,
                                onOpenAllBindings = onOpenAllBindings,
                                onDeleteProfile = onDeleteProfile,
                                modifier = Modifier.fillMaxWidth().heightIn(max = panelMax),
                            )
                        } else {
                            ControllerTestPanel(
                                snapshot = snap,
                                title = stringResource(R.string.controller_test_title),
                                subtitle = snap?.deviceName?.takeIf { it.isNotEmpty() } ?: padName ?: "",
                                resetKey = Unit,
                                manualArt = manualArt,
                                onArtChange = { manualArt = it },
                                identifyEnabled = snap?.hasVibrator == true,
                                onIdentify = { ControllerTestBus.onIdentify?.run() },
                                onDone = null,
                                modifier = Modifier.fillMaxWidth().heightIn(max = panelMax),
                                nameHint = padName,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestBindToggle(
    bind: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, WinNativeOutline, RoundedCornerShape(10.dp)),
    ) {
        SegItem(stringResource(R.string.controller_test_mode_test), !bind) { onChange(false) }
        SegItem(stringResource(R.string.controller_test_mode_bind), bind) { onChange(true) }
    }
}

@Composable
private fun SegItem(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(if (on) WinNativeAccent else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (on) Color.White else WinNativeTextSecondary,
        )
    }
}
