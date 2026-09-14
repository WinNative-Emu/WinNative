package com.winlator.cmod.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledControlLayoutTest {
    private data class Box(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private val assetsRoot: File by lazy {
        var dir: File? = File(".").canonicalFile
        val suffixes = listOf("app/src/main/assets/inputcontrols", "src/main/assets/inputcontrols")
        repeat(6) {
            val here = dir ?: return@repeat
            suffixes.forEach { suffix ->
                val candidate = File(here, suffix)
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = here.parentFile
        }
        throw AssertionError("bundled input control assets not found from ${File(".").canonicalPath}")
    }

    private fun layouts(dir: File): Map<Int, Map<String, Any?>> {
        val files = dir.listFiles { f -> f.name.endsWith(".icp") }.orEmpty()
        return files.associate { f ->
            val json = Json.parse(f.readText()).asMap()
            json.num("id").toInt() to json
        }
    }

    private fun snapping(width: Int) = width / 100

    private fun boxOf(
        element: Map<String, Any?>,
        width: Int,
        height: Int,
    ): Box {
        val snap = snapping(width)
        val maxWidth = width / snap * snap
        val maxHeight = height / snap * snap
        val cx = Math.round(element.num("x") * maxWidth).toInt()
        val cy = Math.round(element.num("y") * maxHeight).toInt()
        val type = element.text("type")
        val shape = element.text("shape")
        val bindings = element.strings("bindings")
        var halfWidth: Int
        var halfHeight: Int
        when (type) {
            "BUTTON" ->
                when (shape) {
                    "RECT", "ROUND_RECT" -> {
                        halfWidth = snap * 4
                        halfHeight = snap * 2
                    }
                    "SQUARE" -> {
                        halfWidth = (snap * 2.5f).toInt()
                        halfHeight = (snap * 2.5f).toInt()
                    }
                    else -> {
                        halfWidth = snap * 3
                        halfHeight = snap * 3
                    }
                }
            "D_PAD" -> {
                halfWidth = snap * 7
                halfHeight = snap * 7
            }
            "STICK", "TRACKPAD" -> {
                halfWidth = snap * 6
                halfHeight = snap * 6
            }
            "RANGE_BUTTON" -> {
                halfWidth = snap * (bindings.size * 4 / 2)
                halfHeight = snap * 2
                if ((element["orientation"] as? Number)?.toInt() == 1) {
                    val swap = halfWidth
                    halfWidth = halfHeight
                    halfHeight = swap
                }
            }
            else -> {
                halfWidth = snap * 3
                halfHeight = snap * 3
            }
        }
        val scale = ((element["scale"] as? Number)?.toDouble() ?: 1.0).toFloat()
        halfWidth = (halfWidth * scale).toInt()
        halfHeight = (halfHeight * scale).toInt()
        return Box(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
    }

    private fun label(element: Map<String, Any?>): String =
        element.strings("bindings").firstOrNull { it != "NONE" } ?: element.text("type")

    @Test
    fun everyDeviceVariantCoversTheSameProfileIds() {
        val stock = layouts(File(assetsRoot, "profiles"))
        assertTrue("stock layouts missing", stock.isNotEmpty())
        DeviceProfile.entries.filter { it.assetToken.isNotEmpty() }.forEach { profile ->
            val dir = File(assetsRoot, "profiles-${profile.assetToken}")
            assertTrue("missing asset dir for ${profile.name}: $dir", dir.isDirectory)
            val variant = layouts(dir)
            assertEquals(
                "${profile.name} must ship the same profile ids as the stock layouts",
                stock.keys.sorted(),
                variant.keys.sorted(),
            )
            variant.forEach { (id, json) ->
                assertEquals(
                    "${profile.name} profile $id must keep the stock name so pickers stay stable",
                    stock.getValue(id).text("name"),
                    json.text("name"),
                )
            }
        }
    }

    @Test
    fun everyBundledElementStaysOnScreen() {
        allLayoutDirs().forEach { dir ->
            layouts(dir).forEach { (id, json) ->
                val elements = json["elements"].asList().map { it.asMap() }
                for (element in elements) {
                    val x = element.num("x")
                    val y = element.num("y")
                    assertTrue("${dir.name}/$id ${label(element)} x=$x out of range", x in 0.0..1.0)
                    assertTrue("${dir.name}/$id ${label(element)} y=$y out of range", y in 0.0..1.0)
                }
            }
        }
    }

    @Test
    fun deviceVariantElementsDoNotOverlapOnTheTargetScreen() {
        val width = 2400
        val height = 1504
        DeviceProfile.entries.filter { it.assetToken.isNotEmpty() }.forEach { profile ->
            val dir = File(assetsRoot, "profiles-${profile.assetToken}")
            layouts(dir).forEach { (id, json) ->
                val elements = json["elements"].asList().map { it.asMap() }
                val boxes = elements.map { boxOf(it, width, height) }
                for (i in boxes.indices) {
                    val a = boxes[i]
                    assertTrue(
                        "${profile.name} profile $id ${label(elements[i])} runs off screen",
                        a.left >= 0 && a.top >= 0 && a.right <= width && a.bottom <= height,
                    )
                    for (j in i + 1 until boxes.size) {
                        val b = boxes[j]
                        val overlaps = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                        assertTrue(
                            "${profile.name} profile $id: ${label(elements[i])} overlaps " + label(elements[j]),
                            !overlaps,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun everyTappableElementMeetsTheMinimumTouchTarget() {
        val width = 2400
        val height = 1504
        val gameplayFloorPx = 108
        val menuFloorPx = 84
        DeviceProfile.entries.filter { it.assetToken.isNotEmpty() }.forEach { profile ->
            val dir = File(assetsRoot, "profiles-${profile.assetToken}")
            layouts(dir).forEach { (id, json) ->
                val elements = json["elements"].asList().map { it.asMap() }
                for (element in elements) {
                    val box = boxOf(element, width, height)
                    val shorter = minOf(box.right - box.left, box.bottom - box.top)
                    val floor = if (isDeliberateTarget(element, box, width)) menuFloorPx else gameplayFloorPx
                    assertTrue(
                        "${profile.name} profile $id ${label(element)} is only ${shorter}px across (floor $floor)",
                        shorter >= floor,
                    )
                }
            }
        }
    }

    private fun isDeliberateTarget(
        element: Map<String, Any?>,
        box: Box,
        width: Int,
    ): Boolean {
        if (element.text("type") == "RANGE_BUTTON") return true
        if (element.text("shape") != "ROUND_RECT") return false
        val binding = element.strings("bindings").firstOrNull { it != "NONE" }
        if (binding == "GAMEPAD_BUTTON_START" || binding == "GAMEPAD_BUTTON_SELECT") return true
        if ((element["iconId"] as? Number)?.toInt() in listOf(15, 16)) return true
        val centre = (box.left + box.right) / 2
        return Math.abs(centre - width / 2) <= 400
    }

    private fun allLayoutDirs(): List<File> =
        assetsRoot.listFiles { f -> f.isDirectory && f.name.startsWith("profiles") }.orEmpty().toList()
}

private object Json {
    fun parse(text: String): Any? = Reader(text).let { r -> r.value().also { r.skipWs() } }

    private class Reader(private val src: String) {
        private var pos = 0

        fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            return when (src[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> num()
            }
        }

        private fun expect(word: String) {
            require(src.startsWith(word, pos)) { "expected $word at $pos" }
            pos += word.length
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (src[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                val key = str()
                skipWs()
                require(src[pos] == ':') { "expected : at $pos" }
                pos++
                out[key] = value()
                skipWs()
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw IllegalArgumentException("bad object at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++
            skipWs()
            if (src[pos] == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipWs()
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw IllegalArgumentException("bad array at $pos")
                }
            }
        }

        private fun str(): String {
            require(src[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (src[pos] != '"') {
                if (src[pos] == '\\') {
                    pos++
                    when (val c = src[pos]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'u' -> {
                            sb.append(src.substring(pos + 1, pos + 5).toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(c)
                    }
                } else {
                    sb.append(src[pos])
                }
                pos++
            }
            pos++
            return sb.toString()
        }

        private fun num(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] in "-+.eE")) pos++
            return src.substring(start, pos).toDouble()
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Any?.asList(): List<Any?> = this as List<Any?>

private fun Map<String, Any?>.num(key: String): Double = (this[key] as Number).toDouble()

private fun Map<String, Any?>.text(key: String): String = this[key] as String

private fun Map<String, Any?>.strings(key: String): List<String> = this[key].asList().map { it as String }
