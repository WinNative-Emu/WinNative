package com.winlator.cmod.feature.retro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.bindPaneNav
import com.winlator.cmod.shared.ui.nav.paneNavItem

private val BgDark = Color(0xFF12121B)
private val SurfaceDark = Color(0xFF171722)
private val CardBorder = Color(0xFF2A2A3A)
private val Accent = Color(0xFF1A9FFF)
private val TextPrimary = Color(0xFFF0F4FF)
private val TextSecondary = Color(0xFF93A6BC)
private val Danger = Color(0xFFE07B6B)
private val ScrimColor = Color(0xFF000000)

class RetroCheatsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SYSTEM_ID = "system_id"
        const val EXTRA_GAME_NAME = "game_name"
    }

    private val navRegistry = PaneNavRegistry()
    private var restoreNav: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val systemId = intent.getStringExtra(EXTRA_SYSTEM_ID)
        val gameName = intent.getStringExtra(EXTRA_GAME_NAME) ?: "Retro Game"
        setContent {
            com.winlator.cmod.shared.theme.WinNativeTheme {
                CompositionLocalProvider(LocalPaneNav provides navRegistry) {
                    RetroCheatsScreen(systemId, gameName) { finish() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        restoreNav = window.bindPaneNav(navRegistry, onDismiss = { finish() })
    }

    override fun onPause() {
        restoreNav?.invoke()
        restoreNav = null
        super.onPause()
    }
}

@Composable
private fun RetroCheatsScreen(
    systemId: String?,
    gameName: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var cheats by remember { mutableStateOf(RetroCheats.load(context, gameName)) }
    var editing by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf(-1) }
    var draftName by remember { mutableStateOf("") }
    var draftCode by remember { mutableStateOf("") }

    fun persist(next: List<RetroCheat>) {
        cheats = next
        RetroCheats.save(context, gameName, next)
    }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ScrimColor.copy(alpha = 0.62f))
                .windowInsetsPadding(WindowInsets.systemBars)
                // The dialog is a fixed height taken from maxHeight, so without this the
                // soft keyboard covers the name/code fields and the Save row below them.
                .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        val dialogWidth = (maxWidth - 32.dp).coerceAtMost(560.dp)
        val dialogHeight = (maxHeight - 40.dp).coerceIn(340.dp, 680.dp)
        Surface(
            modifier =
                Modifier
                    .widthIn(min = minOf(320.dp, dialogWidth), max = dialogWidth)
                    .fillMaxWidth()
                    .height(dialogHeight),
            shape = RoundedCornerShape(16.dp),
            color = BgDark,
            border = BorderStroke(1.dp, CardBorder),
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(38.dp).background(Accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.VideogameAsset, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.retro_che_brand), color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(
                            gameName,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(40.dp).paneNavItem(onActivate = onClose)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(22.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))

                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (editing) {
                        val fieldColors =
                            OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = Accent,
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = CardBorder,
                                focusedLabelColor = Accent,
                                unfocusedLabelColor = TextSecondary,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark,
                            )
                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { draftName = it },
                            label = { Text(stringResource(R.string.retro_che_name)) },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draftCode,
                            onValueChange = { draftCode = it },
                            label = { Text(stringResource(R.string.retro_che_code)) },
                            colors = fieldColors,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            RetroCheats.formatHint(systemId),
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { editing = false },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextPrimary),
                                modifier = Modifier.weight(1f).paneNavItem(onActivate = { editing = false }),
                            ) { Text(stringResource(R.string.retro_che_cancel)) }
                            Button(
                                onClick = {
                                    val name = draftName.ifBlank { context.getString(R.string.retro_che_default_name, cheats.size + 1) }
                                    val code = draftCode.trim()
                                    if (code.isNotEmpty()) {
                                        val entry = RetroCheat(name, code, true)
                                        val next =
                                            if (editIndex >= 0 && editIndex < cheats.size) {
                                                cheats.toMutableList().also { it[editIndex] = entry }
                                            } else {
                                                cheats + entry
                                            }
                                        persist(next)
                                    }
                                    editing = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                                modifier =
                                    Modifier.weight(1f).paneNavItem(onActivate = {
                                        val code = draftCode.trim()
                                        if (code.isNotEmpty()) {
                                            val entry = RetroCheat(draftName.ifBlank { context.getString(R.string.retro_che_default_name, cheats.size + 1) }, code, true)
                                            val next =
                                                if (editIndex >= 0 && editIndex < cheats.size) {
                                                    cheats.toMutableList().also { it[editIndex] = entry }
                                                } else {
                                                    cheats + entry
                                                }
                                            persist(next)
                                        }
                                        editing = false
                                    }),
                            ) { Text(stringResource(R.string.retro_che_save)) }
                        }
                    } else {
                        if (cheats.isEmpty()) {
                            Text(
                                stringResource(R.string.retro_che_empty),
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        cheats.forEachIndexed { index, cheat ->
                            CheatRow(
                                cheat = cheat,
                                onToggle = {
                                    persist(cheats.toMutableList().also { it[index] = cheat.copy(enabled = !cheat.enabled) })
                                },
                                onEdit = {
                                    editIndex = index
                                    draftName = cheat.name
                                    draftCode = cheat.code
                                    editing = true
                                },
                                onDelete = {
                                    persist(cheats.toMutableList().also { it.removeAt(index) })
                                },
                            )
                        }
                        Button(
                            onClick = {
                                editIndex = -1
                                draftName = ""
                                draftCode = ""
                                editing = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                            modifier =
                                Modifier.fillMaxWidth().padding(top = 4.dp).paneNavItem(isEntry = true, onActivate = {
                                    editIndex = -1
                                    draftName = ""
                                    draftCode = ""
                                    editing = true
                                }),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.retro_che_add))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheatRow(
    cheat: RetroCheat,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDark.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth().paneNavItem(cornerRadius = 12.dp, onActivate = onToggle, onSecondary = onDelete),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    cheat.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    cheat.code,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.VideogameAsset, contentDescription = "Edit", tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp).paneNavItem(onActivate = onDelete)) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Danger, modifier = Modifier.size(18.dp))
            }
            Switch(
                checked = cheat.enabled,
                onCheckedChange = { onToggle() },
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Accent,
                        uncheckedTrackColor = SurfaceDark,
                        uncheckedBorderColor = CardBorder,
                    ),
            )
        }
    }
}
