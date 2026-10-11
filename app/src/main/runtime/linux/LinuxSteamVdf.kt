package com.winlator.cmod.runtime.linux

import com.winlator.cmod.feature.stores.steam.utils.KeyValue
import java.io.File
import java.io.IOException

/** The Steam client's text VDF files, read and replaced whole. Worker thread, no client running. */
internal object LinuxSteamVdf {
    val STEAM_KEY = listOf("Software", "Valve", "Steam")

    fun steamRoot(rootfs: File) = File(rootfs, "root/.local/share/Steam")

    fun load(file: File, rootName: String): KeyValue {
        val parsed = if (file.isFile) KeyValue.loadFromString(file.readText()) else null
        // A file that is there but does not parse is the client's to repair, not ours to replace.
        if (parsed == null && file.isFile && file.length() > 0) throw IOException("unreadable ${file.name}")
        return parsed ?: KeyValue(rootName).apply { isSection = true }
    }

    fun section(root: KeyValue, path: List<String>): KeyValue {
        var node = root
        for (name in path) {
            var next = node[name]
            if (next === KeyValue.INVALID) {
                next = KeyValue(name).apply { isSection = true }
                node.children += next
            }
            node = next
        }
        return node
    }

    fun save(file: File, tree: KeyValue) = replace(file) { it.writeText(tree.serialize()) }

    fun replace(file: File, write: (File) -> Unit) {
        file.parentFile?.mkdirs()
        val staged = File(file.path + ".staged")
        write(staged)
        if (!staged.renameTo(file)) {
            staged.delete()
            throw IOException("could not replace ${file.name}")
        }
    }
}
