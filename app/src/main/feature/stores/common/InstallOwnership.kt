package com.winlator.cmod.feature.stores.common

import timber.log.Timber
import java.io.File

enum class InstallStore(
    val id: String,
) {
    STEAM("STEAM"),
    EPIC("EPIC"),
    GOG("GOG"),
    ITCH("ITCH"),
    ;

    companion object {
        fun fromId(value: String?): InstallStore? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return entries.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }
        }
    }
}

object InstallOwnership {
    private const val OWNER_FILE = ".store_owner"
    private const val MAX_OWNER_FILE_BYTES = 64L

    fun ownerOf(dirPath: String?): InstallStore? {
        val path = dirPath?.trim().orEmpty()
        if (path.isEmpty()) return null
        return try {
            val file = File(path, OWNER_FILE)
            if (!file.isFile || file.length() > MAX_OWNER_FILE_BYTES) return null
            InstallStore.fromId(file.readText())
        } catch (e: Exception) {
            Timber.w(e, "Failed to read install owner at %s", path)
            null
        }
    }

    fun isForeign(
        dirPath: String?,
        store: InstallStore,
    ): Boolean {
        val owner = ownerOf(dirPath) ?: return false
        return owner != store
    }

    fun claim(
        dirPath: String?,
        store: InstallStore,
    ): Boolean {
        val path = dirPath?.trim().orEmpty()
        if (path.isEmpty()) return false
        return try {
            val dir = File(path)
            if (!dir.isDirectory) return false
            val file = File(dir, OWNER_FILE)
            if (InstallStore.fromId(file.takeIf { it.isFile }?.readText()) == store) return true
            file.writeText(store.id)
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to claim install owner %s at %s", store.id, path)
            false
        }
    }

    fun claimIfUnowned(
        dirPath: String?,
        store: InstallStore,
    ): Boolean {
        if (ownerOf(dirPath) != null) return false
        return claim(dirPath, store)
    }

    fun release(
        dirPath: String?,
        store: InstallStore,
    ): Boolean {
        val path = dirPath?.trim().orEmpty()
        if (path.isEmpty()) return false
        if (ownerOf(path) != store) return false
        return try {
            File(path, OWNER_FILE).delete()
        } catch (e: Exception) {
            Timber.w(e, "Failed to release install owner %s at %s", store.id, path)
            false
        }
    }
}
