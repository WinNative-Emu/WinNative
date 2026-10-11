package com.winlator.cmod.feature.library

import com.winlator.cmod.feature.stores.steam.enums.AppType
import com.winlator.cmod.runtime.container.Shortcut

/** What a library entry is, for the Games / Applications filters. Stored as the `library_type` extra. */
enum class LibraryItemType(val key: String, val appType: AppType) {
    GAME("game", AppType.game),
    APPLICATION("application", AppType.application),
    ;

    companion object {
        const val EXTRA_KEY = "library_type"

        @JvmStatic
        fun fromKey(key: String?): LibraryItemType = entries.firstOrNull { it.key == key } ?: GAME

        @JvmStatic
        fun of(shortcut: Shortcut): LibraryItemType = fromKey(shortcut.getExtra(EXTRA_KEY))
    }
}

/** The library drawer's content filters, keyed as they are persisted: `games`, `dlc`, `applications`, `tools`. */
object LibraryContentFilters {
    fun allows(type: AppType, filters: Map<String, Boolean>): Boolean =
        when (type) {
            AppType.game, AppType.demo -> filters["games"] == true
            AppType.dlc -> filters["dlc"] == true
            AppType.application -> filters["applications"] == true
            AppType.tool, AppType.config -> filters["tools"] == true
            else -> filters["games"] == true
        }
}
