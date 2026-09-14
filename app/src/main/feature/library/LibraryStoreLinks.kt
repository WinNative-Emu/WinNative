package com.winlator.cmod.feature.library

import android.content.Context
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import java.io.File
import java.util.Locale

data class LibraryStoreOption(
    val store: InstallStore,
    val libraryId: Int,
    val storeGameId: String,
    val title: String,
    val installPath: String,
    val isInstalled: Boolean,
)

object LibraryStoreLinks {
    private const val PREFS = "library_store_links"
    private const val PREF_PREFIX = "store_"

    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

    private val RESOLUTION_ORDER =
        listOf(InstallStore.ITCH, InstallStore.GOG, InstallStore.EPIC, InstallStore.STEAM)

    fun titleKey(title: String?): String =
        title
            .orEmpty()
            .lowercase(Locale.ROOT)
            .replace('™', ' ')
            .replace('®', ' ')
            .replace('©', ' ')
            .replace(NON_ALPHANUMERIC, "")

    fun pathKey(path: String?): String {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return runCatching { File(raw).canonicalPath }
            .getOrElse { File(raw).absolutePath }
            .trimEnd('/')
            .lowercase(Locale.ROOT)
    }

    fun resolveOwner(
        installPath: String,
        claimants: List<InstallStore>,
        shortcutSource: InstallStore?,
    ): InstallStore? {
        if (claimants.isEmpty()) return null
        if (claimants.size == 1) return claimants.first()

        InstallOwnership.ownerOf(installPath)?.let { owner ->
            if (owner in claimants) return owner
        }
        if (shortcutSource != null && shortcutSource in claimants) return shortcutSource
        return RESOLUTION_ORDER.firstOrNull { it in claimants } ?: claimants.first()
    }

    fun preferredStore(
        context: Context,
        titleKey: String,
    ): InstallStore? {
        if (titleKey.isEmpty()) return null
        return InstallStore.fromId(
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_PREFIX + titleKey, null),
        )
    }

    fun setPreferredStore(
        context: Context,
        titleKey: String,
        store: InstallStore,
    ) {
        if (titleKey.isEmpty()) return
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_PREFIX + titleKey, store.id)
            .apply()
    }

    fun clearPreferredStore(
        context: Context,
        titleKey: String,
    ) {
        if (titleKey.isEmpty()) return
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_PREFIX + titleKey)
            .apply()
    }
}
