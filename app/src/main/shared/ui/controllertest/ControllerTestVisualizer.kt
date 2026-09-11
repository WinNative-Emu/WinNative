package com.winlator.cmod.shared.ui.controllertest

import android.content.res.Configuration
import android.view.InputDevice
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.WinNativeAccent
import com.winlator.cmod.shared.theme.WinNativeOutline
import com.winlator.cmod.shared.theme.WinNativeTextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary
import java.util.Locale
import kotlin.math.roundToInt

private val TestLive = Color(0xFF57C777)
private val TestTile = Color(0xFF14141E)
private val TestMenuCard = Color(0xFF1C1C2A)
private val TestNub = Color(0xFF12161D)
internal val BIND_BLUE = Color(0xFF7AA0FF)

enum class PadArt {
    XBOX_360,
    XBOX_MODERN,
    DUALSENSE,
    DUALSHOCK4,
    SWITCH_PRO,
    EIGHTBITDO,
    GENERIC,
    DUALSHOCK3,
    STEAM,
    GAMECUBE,
    SNES,
}

fun padArtLabel(a: PadArt): String =
    when (a) {
        PadArt.XBOX_360 -> "Xbox 360"
        PadArt.XBOX_MODERN -> "Xbox"
        PadArt.DUALSENSE -> "DualSense"
        PadArt.DUALSHOCK4 -> "DualShock 4"
        PadArt.SWITCH_PRO -> "Switch Pro"
        PadArt.EIGHTBITDO -> "8BitDo"
        PadArt.GENERIC -> "Generic"
        PadArt.DUALSHOCK3 -> "DualShock 3"
        PadArt.STEAM -> "Steam"
        PadArt.GAMECUBE -> "GameCube"
        PadArt.SNES -> "SNES"
    }

private fun padArtSafe(ordinal: Int): PadArt = PadArt.entries.getOrElse(ordinal) { PadArt.GENERIC }

internal fun padArtOf(
    snapshot: ControllerTestSnapshot?,
    nameHint: String?,
): PadArt =
    when {
        snapshot != null -> padArtSafe(snapshot.padArt)
        nameHint != null -> classifyPadArtByName(nameHint)
        else -> PadArt.GENERIC
    }

fun classifyPadArt(device: InputDevice?): PadArt {
    if (device == null) return PadArt.GENERIC
    val n = (device.name ?: "").lowercase(Locale.US)
    if (n.contains("8bitdo") || n.contains("sn30")) return PadArt.EIGHTBITDO
    val p = device.productId
    return when (device.vendorId) {
        0x045E -> if (n.contains("360")) PadArt.XBOX_360 else PadArt.XBOX_MODERN
        0x054C ->
            when {
                n.contains("dualsense") || p == 0x0CE6 || p == 0x0DF2 -> PadArt.DUALSENSE
                n.contains("dualshock 4") || n.contains("wireless controller") ||
                    p == 0x05C4 || p == 0x09CC || p == 0x0BA0 -> PadArt.DUALSHOCK4
                n.contains("motion") || p == 0x0268 -> PadArt.DUALSHOCK3
                else -> PadArt.DUALSHOCK4
            }
        0x057E -> PadArt.SWITCH_PRO
        0x2DC8 -> PadArt.EIGHTBITDO
        0x28DE -> PadArt.STEAM
        else -> classifyPadArtByName(n)
    }
}

private fun classifyPadArtByName(nameRaw: String): PadArt {
    val n = nameRaw.lowercase(Locale.US)
    return when {
        n.contains("8bitdo") || n.contains("sn30") || n.contains("pro 2") -> PadArt.EIGHTBITDO
        n.contains("dualsense") -> PadArt.DUALSENSE
        n.contains("dualshock 4") || n.contains("dualshock4") -> PadArt.DUALSHOCK4
        n.contains("dualshock 3") || n.contains("dualshock3") || n.contains("motion") -> PadArt.DUALSHOCK3
        n.contains("dualshock") || n.contains("playstation") -> PadArt.DUALSHOCK4
        n.contains("xbox") -> if (n.contains("360")) PadArt.XBOX_360 else PadArt.XBOX_MODERN
        n.contains("switch") || n.contains("joy-con") || n.contains("pro controller") -> PadArt.SWITCH_PRO
        n.contains("steam") -> PadArt.STEAM
        else -> PadArt.GENERIC
    }
}

private val ALL_ELEMENTS =
    listOf(
        "a", "b", "x", "y", "lb", "rb", "lt", "rt",
        "back", "start", "l3", "r3", "dup", "ddown", "dleft", "dright", "guide",
    )

private val STEAM_ELEMENTS = ALL_ELEMENTS + "qam"

@Composable
private fun friendlyName(id: String): String =
    when (id) {
        "a" -> "A / ✕"
        "b" -> "B / ○"
        "x" -> "X / □"
        "y" -> "Y / △"
        "lb" -> "LB / L1"
        "rb" -> "RB / R1"
        "lt" -> "LT / L2"
        "rt" -> "RT / R2"
        "back" -> stringResource(R.string.controller_test_element_back)
        "start" -> stringResource(R.string.controller_test_element_start)
        "l3" -> stringResource(R.string.controller_test_element_l3)
        "r3" -> stringResource(R.string.controller_test_element_r3)
        "dup" -> stringResource(R.string.controller_test_element_dpad_up)
        "ddown" -> stringResource(R.string.controller_test_element_dpad_down)
        "dleft" -> stringResource(R.string.controller_test_element_dpad_left)
        "dright" -> stringResource(R.string.controller_test_element_dpad_right)
        "guide" -> stringResource(R.string.controller_test_element_guide)
        "qam" -> stringResource(R.string.controller_test_element_quick_access)
        else -> id
    }

internal fun pressedSet(snap: ControllerTestSnapshot?): Set<String> {
    if (snap == null) return emptySet()
    val s = HashSet<String>()
    fun bit(i: Int) = (snap.buttons and (1 shl i)) != 0
    if (bit(0)) s.add("a")
    if (bit(1)) s.add("b")
    if (bit(2)) s.add("x")
    if (bit(3)) s.add("y")
    if (bit(4)) s.add("lb")
    if (bit(5)) s.add("rb")
    if (bit(6)) s.add("back")
    if (bit(7)) s.add("start")
    if (bit(8)) s.add("l3")
    if (bit(9)) s.add("r3")
    if (bit(10) || snap.triggerL > 0.5f) s.add("lt")
    if (bit(11) || snap.triggerR > 0.5f) s.add("rt")
    if (snap.dpadUp) s.add("dup")
    if (snap.dpadDown) s.add("ddown")
    if (snap.dpadLeft) s.add("dleft")
    if (snap.dpadRight) s.add("dright")
    if (snap.guide) s.add("guide")
    if (snap.quickAccess) s.add("qam")
    return s
}

private fun fmt2(v: Float): String {
    val r = (v * 100).roundToInt() / 100f
    return String.format(Locale.US, "%.2f", r)
}

private fun pct(v: Float): String = "${(v.coerceIn(0f, 1f) * 100).roundToInt()}%"

@Composable
internal fun Modifier.testMenuCard(): Modifier {
    val shape = RoundedCornerShape(10.dp)
    return this
        .clip(shape)
        .background(TestMenuCard)
        .border(1.dp, WinNativeOutline, shape)
}

@Composable
internal fun TestMenuDivider() {
    HorizontalDivider(color = WinNativeOutline.copy(alpha = 0.5f))
}

@Composable
fun ControllerTestPanel(
    snapshot: ControllerTestSnapshot?,
    title: String,
    subtitle: String,
    resetKey: Any?,
    manualArt: PadArt?,
    onArtChange: (PadArt?) -> Unit,
    identifyEnabled: Boolean,
    onIdentify: (() -> Unit)?,
    onDone: (() -> Unit)?,
    modifier: Modifier = Modifier,
    nameHint: String? = null,
) {
    val autoArt =
        when {
            snapshot != null -> padArtSafe(snapshot.padArt)
            nameHint != null -> classifyPadArtByName(nameHint)
            else -> PadArt.GENERIC
        }
    val art = manualArt ?: autoArt

    val pressed = pressedSet(snapshot)
    val elements = if (autoArt == PadArt.STEAM) STEAM_ELEMENTS else ALL_ELEMENTS
    val seen = remember(resetKey) { mutableStateListOf<String>() }
    var lastInputId by remember(resetKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(pressed) {
        pressed.forEach { if (it !in seen && it in elements) seen.add(it) }
        pressed.firstOrNull { it in elements }?.let { lastInputId = it }
    }

    Column(modifier) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WinNativeTextPrimary)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = WinNativeTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ArtSelector(art, manualArt, onArtChange)
            }
            Spacer(Modifier.height(6.dp))

            val live = snapshot != null
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (live) TestLive else WinNativeTextSecondary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (live) {
                        stringResource(R.string.controller_test_live_input)
                    } else {
                        stringResource(R.string.controller_test_waiting)
                    },
                    fontSize = 12.sp,
                    color = if (live) TestLive else WinNativeTextSecondary,
                    modifier = Modifier.weight(1f),
                )
                val batt = snapshot?.batteryPct ?: -1
                if (batt in 0..100) {
                    Text(
                        stringResource(R.string.controller_test_battery, batt),
                        fontSize = 11.sp,
                        color = WinNativeTextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            val ls = snapshot?.let { fmt2(it.thumbLX) + ", " + fmt2(it.thumbLY) } ?: "0.00, 0.00"
            val rs = snapshot?.let { fmt2(it.thumbRX) + ", " + fmt2(it.thumbRY) } ?: "0.00, 0.00"
            val tr = snapshot?.let { pct(it.triggerL) + " · " + pct(it.triggerR) } ?: "0% · 0%"
            val lastInput = lastInputId?.let { friendlyName(it) } ?: "—"
            val readouts =
                listOf(
                    stringResource(R.string.controller_test_last_input) to lastInput,
                    stringResource(R.string.controller_test_left_stick) to ls,
                    stringResource(R.string.controller_test_right_stick) to rs,
                    stringResource(R.string.controller_test_triggers) to tr,
                )
            if (landscape) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.weight(0.52f), contentAlignment = Alignment.Center) {
                        PadArtView(art = art, snapshot = snapshot)
                    }
                    Column(Modifier.weight(0.48f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (pair in readouts.chunked(2)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                for ((k, v) in pair) StatTile(k, v, Modifier.weight(1f))
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PadArtView(art = art, snapshot = snapshot)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for ((k, v) in readouts) StatTile(k, v, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            val done = seen.count { it in elements }
            Text(
                if (done >= elements.size) {
                    stringResource(R.string.controller_test_all_verified, elements.size)
                } else {
                    stringResource(R.string.controller_test_verified_count, done, elements.size)
                },
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (done >= elements.size) TestLive else WinNativeTextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (onIdentify != null) {
                Button(
                    onClick = { onIdentify() },
                    enabled = identifyEnabled,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WinNativeAccent),
                ) { Text(stringResource(R.string.controller_test_identify), fontSize = 12.sp) }
            }
            if (onDone != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onDone() }) {
                    Text(stringResource(R.string.common_ui_done))
                }
            }
        }
    }
}

@Composable
private fun ArtSelector(
    effective: PadArt,
    manual: PadArt?,
    onChange: (PadArt?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val autoPrefix = stringResource(R.string.controller_test_art_auto_prefix)
    Box {
        OutlinedButton(
            onClick = { open = true },
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                (if (manual == null) "$autoPrefix · " else "") + padArtLabel(effective),
                fontSize = 12.sp,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.testMenuCard(),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.controller_test_art_auto_detect)) },
                onClick = {
                    open = false
                    onChange(null)
                },
            )
            for (a in PadArt.entries) {
                TestMenuDivider()
                DropdownMenuItem(
                    text = { Text(padArtLabel(a)) },
                    onClick = {
                        open = false
                        onChange(a)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    key: String,
    value: String,
    modifier: Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TestTile)
            .border(1.dp, WinNativeOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Text(key.uppercase(), fontSize = 9.sp, color = WinNativeTextSecondary)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = WinNativeTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun highlightManifest(
    ref: PadArtRef,
    logical: String,
): String? {
    val cands =
        when (logical) {
            "a", "b", "x", "y", "lt", "rt", "l3", "r3" -> listOf(logical)
            "lb" -> listOf("lb", "l")
            "rb" -> listOf("rb", "r")
            "dup" -> listOf("dpad_up")
            "ddown" -> listOf("dpad_down")
            "dleft" -> listOf("dpad_left")
            "dright" -> listOf("dpad_right")
            "back" -> listOf("back", "select", "share", "minus", "view")
            "start" -> listOf("start", "options", "plus", "menu")
            "guide" -> listOf("guide", "home")
            "qam" -> listOf("qam")
            else -> emptyList()
        }
    return cands.firstOrNull { ref.el.containsKey(it) }
}

internal fun manifestToBindId(manifestId: String): String? =
    when (manifestId) {
        "a", "b", "x", "y", "lt", "rt", "l3", "r3", "lb", "rb", "start", "back", "guide" -> manifestId
        "dpad_up" -> "dup"
        "dpad_down" -> "ddown"
        "dpad_left" -> "dleft"
        "dpad_right" -> "dright"
        "l" -> "lb"
        "r" -> "rb"
        "lstick" -> "l3"
        "rstick" -> "r3"
        "cstick" -> "r3"
        "home" -> "guide"
        "options" -> "start"
        "plus" -> "start"
        "menu" -> "start"
        "select" -> "back"
        "share" -> "back"
        "minus" -> "back"
        "view" -> "back"
        else -> null
    }

private fun bindToManifest(
    ref: PadArtRef,
    bindId: String,
): String? {
    val cands =
        when (bindId) {
            "a", "b", "x", "y", "lt", "rt", "l3", "r3" -> listOf(bindId)
            "lb" -> listOf("lb", "l")
            "rb" -> listOf("rb", "r")
            "dup" -> listOf("dpad_up")
            "ddown" -> listOf("dpad_down")
            "dleft" -> listOf("dpad_left")
            "dright" -> listOf("dpad_right")
            "back" -> listOf("back", "select", "share", "minus", "view")
            "start" -> listOf("start", "options", "plus", "menu")
            "guide" -> listOf("guide", "home")
            "lsu", "lsd", "lsl", "lsr" -> listOf("lstick")
            "rsu", "rsd", "rsl", "rsr" -> listOf("rstick", "cstick")
            else -> emptyList()
        }
    return cands.firstOrNull { ref.el.containsKey(it) }
}

@Composable
internal fun PadArtView(
    art: PadArt,
    snapshot: ControllerTestSnapshot?,
    modifier: Modifier = Modifier,
    boundBindIds: Set<String> = emptySet(),
    selectedBindId: String? = null,
    onTapBind: ((String) -> Unit)? = null,
) {
    val ref = PAD_REFS[art] ?: PAD_REFS[PadArt.GENERIC]!!
    val accent = WinNativeAccent
    val pressedManifest =
        remember(snapshot, art) {
            val out = HashSet<String>()
            for (p in pressedSet(snapshot)) highlightManifest(ref, p)?.let { out.add(it) }
            out
        }
    val boundManifest =
        remember(boundBindIds, art) {
            val out = HashSet<String>()
            for (bid in boundBindIds) bindToManifest(ref, bid)?.let { out.add(it) }
            out
        }
    val selectedManifest = selectedBindId?.let { bindToManifest(ref, it) }

    Box(
        modifier
            .widthIn(max = 248.dp)
            .heightIn(max = 136.dp)
            .aspectRatio(ref.vbW / ref.vbH),
    ) {
        Image(
            painter = painterResource(ref.drawableRes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(
            Modifier.matchParentSize().then(
                if (onTapBind != null) {
                    Modifier.pointerInput(art) {
                        detectTapGestures { pos ->
                            val sx = size.width / ref.vbW
                            val sy = size.height / ref.vbH
                            val sr = maxOf(sx, sy)
                            var best: String? = null
                            var bestD = Float.MAX_VALUE
                            for ((id, t) in ref.el) {
                                val cx = t.first * sx
                                val cy = t.second * sy
                                val rr = t.third * sr
                                val dx = pos.x - cx
                                val dy = pos.y - cy
                                val d = dx * dx + dy * dy
                                if (d <= rr * rr && d < bestD) {
                                    bestD = d
                                    best = id
                                }
                            }
                            best?.let { manifestToBindId(it) }?.let { onTapBind(it) }
                        }
                    }
                } else {
                    Modifier
                },
            ),
        ) {
            val sx = size.width / ref.vbW
            val sy = size.height / ref.vbH
            val sr = maxOf(sx, sy)
            for (mid in boundManifest) {
                val t = ref.el[mid] ?: continue
                drawCircle(
                    BIND_BLUE,
                    4f,
                    Offset(t.first * sx + t.third * sr * 0.6f, t.second * sy - t.third * sr * 0.6f),
                )
            }
            for (mid in pressedManifest) {
                val t = ref.el[mid] ?: continue
                val rr = t.third * sr
                drawCircle(accent.copy(alpha = 0.30f), rr * 1.5f, Offset(t.first * sx, t.second * sy))
                drawCircle(accent, rr, Offset(t.first * sx, t.second * sy), style = Stroke(width = 2f))
            }
            selectedManifest?.let { mid ->
                val t = ref.el[mid]
                if (t != null) {
                    drawCircle(
                        accent,
                        t.third * sr * 1.25f,
                        Offset(t.first * sx, t.second * sy),
                        style = Stroke(width = 2.5f),
                    )
                }
            }
            snapshot?.let { s ->
                drawStickNub(ref, "lstick", s.thumbLX, s.thumbLY, sx, sy, sr, accent, "l3" in pressedManifest)
                val rNub = if (ref.el.containsKey("rstick")) "rstick" else "cstick"
                drawStickNub(ref, rNub, s.thumbRX, s.thumbRY, sx, sy, sr, accent, "r3" in pressedManifest)
            }
        }
    }
}

private fun DrawScope.drawStickNub(
    ref: PadArtRef,
    id: String,
    tx: Float,
    ty: Float,
    sx: Float,
    sy: Float,
    sr: Float,
    accent: Color,
    clicked: Boolean,
) {
    val t = ref.el[id] ?: return
    val socketR = t.third * sr
    val nubR = socketR * 0.55f
    val travel = socketR * 0.45f
    var ox = tx * travel
    var oy = ty * travel
    val maxOff = socketR - nubR
    val mag = kotlin.math.sqrt((ox * ox + oy * oy).toDouble()).toFloat()
    if (mag > maxOff && mag > 0f) {
        val k = maxOff / mag
        ox *= k
        oy *= k
    }
    val cx = t.first * sx + ox
    val cy = t.second * sy + oy

    val defl = kotlin.math.min(1f, kotlin.math.sqrt((tx * tx + ty * ty).toDouble()).toFloat())
    val glow = if (clicked) 1f else if (defl > 0.08f) defl else 0f
    if (glow > 0f) {
        drawCircle(accent.copy(alpha = 0.30f * glow), nubR * 1.5f, Offset(cx, cy))
    }
    val fill = if (glow > 0f) lerp(TestNub, accent, 0.65f * glow) else TestNub
    drawCircle(fill, nubR, Offset(cx, cy))
    if (glow > 0f) {
        drawCircle(accent.copy(alpha = glow), nubR, Offset(cx, cy), style = Stroke(width = 2f))
    }
    drawCircle(Color(0x22FFFFFF), nubR, Offset(cx, cy - nubR * 0.12f), style = Stroke(width = 1.2f))
}
