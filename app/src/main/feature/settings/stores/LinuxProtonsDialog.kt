package com.winlator.cmod.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.cmod.R
import com.winlator.cmod.runtime.linux.LinuxProtons
import com.winlator.cmod.runtime.linux.LinuxRuntime
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.dialog.PopupProgressBar
import com.winlator.cmod.shared.ui.dialog.PopupTextAction
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry

@Composable
internal fun LinuxProtonsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by LinuxProtons.state.collectAsState()
    val nav = remember { PaneNavRegistry() }
    val blue = Color(0xFF1A9FFF)
    val text = Color(0xFF93A6BC)
    val ready = LinuxRuntime.isInstalled(context)
    LaunchedEffect(Unit) { LinuxProtons.refresh(context) }
    Dialog(onDismissRequest = onDismiss) {
        DialogPaneNav(nav, onDismiss = onDismiss)
        CompositionLocalProvider(LocalPaneNav provides nav) {
            PopupDialog(
                title = stringResource(R.string.linux_protons_title),
                message = stringResource(if (ready) R.string.linux_protons_hint else R.string.linux_protons_requires_runtime),
                icon = Icons.Outlined.ArrowDownward,
                accentColor = blue,
                modifier = Modifier.widthIn(min = 300.dp, max = 480.dp),
                content = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.loading) PopupProgressBar(Float.NaN, stringResource(R.string.linux_client_stage_connect), blue, text)
                        if (state.failed) Text(stringResource(R.string.linux_protons_failed), color = text)
                        state.builds.forEach { build ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(build.displayName, color = Color.White, fontSize = 14.sp)
                                when {
                                    state.working == build.id -> {
                                        PopupProgressBar(state.progress, stringResource(state.stage), blue, text)
                                        if (!state.removing) PopupTextAction(stringResource(R.string.common_ui_cancel), text, { LinuxProtons.cancel() })
                                    }
                                    build.id in state.installed -> {
                                        Text(stringResource(R.string.common_ui_installed), color = text, fontSize = 12.sp)
                                        if (state.working == null && !state.loading) PopupTextAction(stringResource(R.string.common_ui_remove), text, { LinuxProtons.remove(context, build) })
                                    }
                                    ready && state.working == null && !state.loading -> PopupTextAction(stringResource(R.string.common_ui_download), blue, { LinuxProtons.install(context, build) })
                                }
                            }
                        }
                    }
                },
                footer = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (state.working == null && !state.loading) PopupTextAction(stringResource(R.string.linux_client_retry), blue, { LinuxProtons.refresh(context) })
                        PopupTextAction(stringResource(R.string.common_ui_ok), blue, onDismiss, isEntry = true)
                    }
                },
            )
        }
    }
}
