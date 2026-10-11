package com.winlator.cmod.app.shell

import com.winlator.cmod.feature.library.LibraryStoreLinks
import com.winlator.cmod.feature.library.LibraryStoreOption
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore

internal data class LibraryStoreLinkResult(
    val hiddenLibraryIds: Set<Int> = emptySet(),
    val optionsByLibraryId: Map<Int, List<LibraryStoreOption>> = emptyMap(),
    val activeStoreByLibraryId: Map<Int, InstallStore> = emptyMap(),
    val installPathByLibraryId: Map<Int, String> = emptyMap(),
)

private val STORE_MENU_ORDER =
    listOf(InstallStore.STEAM, InstallStore.EPIC, InstallStore.GOG, InstallStore.ITCH)

internal fun computeLibraryStoreLinks(
    installedRows: List<LibraryStoreOption>,
    customRows: List<LibraryStoreOption>,
    ownedEntries: List<LibraryStoreOption>,
    itchInstalls: List<LibraryStoreOption>,
    shortcutSourceByPath: Map<String, InstallStore> = emptyMap(),
    preferredStoreOf: (String) -> InstallStore? = { null },
): LibraryStoreLinkResult {
    val itchByPath =
        itchInstalls
            .filter { LibraryStoreLinks.pathKey(it.installPath).isNotEmpty() }
            .associateBy { LibraryStoreLinks.pathKey(it.installPath) }
    val ownedByTitle = ownedEntries.groupBy { LibraryStoreLinks.titleKey(it.title) }

    val hidden = mutableSetOf<Int>()
    val activeStore = mutableMapOf<Int, InstallStore>()
    val installPaths = mutableMapOf<Int, String>()

    installedRows.forEach { row ->
        activeStore[row.libraryId] = row.store
        installPaths[row.libraryId] = row.installPath
    }
    customRows.forEach { row ->
        installPaths[row.libraryId] = row.installPath
        itchByPath[LibraryStoreLinks.pathKey(row.installPath)]?.let {
            activeStore[row.libraryId] = InstallStore.ITCH
        }
    }

    val customByPath =
        customRows
            .filter { LibraryStoreLinks.pathKey(it.installPath).isNotEmpty() }
            .groupBy { LibraryStoreLinks.pathKey(it.installPath) }

    installedRows
        .filter { LibraryStoreLinks.pathKey(it.installPath).isNotEmpty() }
        .groupBy { LibraryStoreLinks.pathKey(it.installPath) }
        .forEach { (pathKey, storeRows) ->
            val itchRow = itchByPath[pathKey]
            val candidates = (storeRows.map { it.store } + listOfNotNull(itchRow?.store)).distinct()
            if (candidates.size < 2 && storeRows.size < 2) return@forEach

            val winner =
                preferredStoreOf(pathKey)?.takeIf { it in candidates }
                    ?: LibraryStoreLinks.resolveOwner(
                        installPath = storeRows.first().installPath,
                        claimants = candidates,
                        shortcutSource = shortcutSourceByPath[pathKey],
                    )
                    ?: return@forEach

            InstallOwnership.claimIfUnowned(storeRows.first().installPath, winner)

            val keptRow = storeRows.firstOrNull { it.store == winner }
            storeRows.filter { it.libraryId != keptRow?.libraryId }.forEach { hidden += it.libraryId }
            if (winner != InstallStore.ITCH) {
                customByPath[pathKey]?.forEach { hidden += it.libraryId }
            }
        }

    customByPath.forEach { (pathKey, rows) ->
        if (pathKey.isEmpty() || itchByPath.containsKey(pathKey)) return@forEach
        val owner = InstallOwnership.ownerOf(rows.first().installPath) ?: return@forEach
        if (owner == InstallStore.ITCH) return@forEach
        val ownerHasInstall =
            installedRows.any {
                it.store == owner && LibraryStoreLinks.pathKey(it.installPath) == pathKey
            }
        if (ownerHasInstall) rows.forEach { hidden += it.libraryId }
    }

    val installedRowIds = installedRows.map { it.libraryId }.toSet()
    val installedPathByRow =
        installedRows.associate { it.libraryId to LibraryStoreLinks.pathKey(it.installPath) }

    val visibleRows = (installedRows + customRows).filter { it.libraryId !in hidden }
    val optionsById =
        visibleRows.associate { row ->
            val pathKey = LibraryStoreLinks.pathKey(row.installPath)
            val customLibraryId = customByPath[pathKey]?.firstOrNull()?.libraryId
            val itchRow =
                itchByPath[pathKey]?.let { itch ->
                    if (customLibraryId != null) itch.copy(libraryId = customLibraryId) else null
                }?.takeIf { pathKey.isNotEmpty() }
            val owned = ownedByTitle[LibraryStoreLinks.titleKey(itchRow?.title ?: row.title)].orEmpty()
            val self = if (row.libraryId in installedRowIds) listOf(row) else emptyList()
            val options =
                (listOfNotNull(itchRow) + owned + self)
                    .distinctBy { it.store }
                    .filter { option ->
                        val installedAt = installedPathByRow[option.libraryId]
                        installedAt.isNullOrEmpty() || installedAt == pathKey
                    }.sortedBy { option -> STORE_MENU_ORDER.indexOf(option.store) }
            row.libraryId to options
        }

    return LibraryStoreLinkResult(
        hiddenLibraryIds = hidden,
        optionsByLibraryId = optionsById,
        activeStoreByLibraryId = activeStore,
        installPathByLibraryId = installPaths,
    )
}

internal fun libraryStoreDisplayName(
    store: InstallStore,
    itchLabel: String,
): String =
    when (store) {
        InstallStore.STEAM -> "Steam"
        InstallStore.EPIC -> "Epic Games"
        InstallStore.GOG -> "GOG"
        InstallStore.ITCH -> itchLabel
    }
