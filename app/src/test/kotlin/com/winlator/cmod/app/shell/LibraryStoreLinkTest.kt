package com.winlator.cmod.app.shell

import com.winlator.cmod.feature.library.LibraryStoreLinks
import com.winlator.cmod.feature.library.LibraryStoreOption
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryStoreLinkTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun dir(name: String): File = temp.newFolder(name)

    private fun steam(
        appId: Int,
        title: String,
        path: String = "",
    ) = LibraryStoreOption(InstallStore.STEAM, appId, appId.toString(), title, path, true)

    private fun epic(
        id: Int,
        title: String,
        path: String = "",
    ) = LibraryStoreOption(InstallStore.EPIC, 2000000000 + id, id.toString(), title, path, true)

    private fun gog(
        id: String,
        libraryId: Int,
        title: String,
        path: String = "",
    ) = LibraryStoreOption(InstallStore.GOG, libraryId, id, title, path, true)

    private fun itch(
        id: Int,
        title: String,
        path: String,
    ) = LibraryStoreOption(InstallStore.ITCH, 0, id.toString(), title, path, true)

    private fun custom(
        libraryId: Int,
        title: String,
        path: String,
    ) = LibraryStoreOption(InstallStore.ITCH, libraryId, libraryId.toString(), title, path, true)

    @Test
    fun sharedInstallFolderCollapsesToTheOwningStore() {
        val shared = dir("Game").absolutePath
        InstallOwnership.claim(shared, InstallStore.EPIC)

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(steam(10, "Game", shared), epic(4, "Game", shared)),
                customRows = emptyList(),
                ownedEntries = listOf(steam(10, "Game"), epic(4, "Game")),
                itchInstalls = emptyList(),
            )

        assertEquals(setOf(10), result.hiddenLibraryIds)
        assertEquals(InstallStore.EPIC, result.activeStoreByLibraryId[2000000004])
    }

    @Test
    fun separateInstallFoldersKeepBothRows() {
        val steamDir = dir("SteamGame").absolutePath
        val epicDir = dir("EpicGame").absolutePath

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(steam(10, "Game", steamDir), epic(4, "Game", epicDir)),
                customRows = emptyList(),
                ownedEntries = listOf(steam(10, "Game"), epic(4, "Game")),
                itchInstalls = emptyList(),
            )

        assertTrue(result.hiddenLibraryIds.isEmpty())
    }

    @Test
    fun savedPreferenceWinsOverTheOwnerFile() {
        val shared = dir("Game").absolutePath
        InstallOwnership.claim(shared, InstallStore.EPIC)

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(steam(10, "Game", shared), epic(4, "Game", shared)),
                customRows = emptyList(),
                ownedEntries = listOf(steam(10, "Game"), epic(4, "Game")),
                itchInstalls = emptyList(),
                preferredStoreOf = { InstallStore.STEAM },
            )

        assertEquals(setOf(2000000004), result.hiddenLibraryIds)
    }

    @Test
    fun shortcutSourceDecidesWhenNoOwnerFileExists() {
        val shared = dir("Game").absolutePath

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(steam(10, "Game", shared), epic(4, "Game", shared)),
                customRows = emptyList(),
                ownedEntries = listOf(steam(10, "Game"), epic(4, "Game")),
                itchInstalls = emptyList(),
                shortcutSourceByPath = mapOf(LibraryStoreLinks.pathKey(shared) to InstallStore.STEAM),
            )

        assertEquals(setOf(2000000004), result.hiddenLibraryIds)
        assertEquals(InstallStore.STEAM, InstallOwnership.ownerOf(shared))
    }

    @Test
    fun resolvingAConflictStampsTheWinnerOnDisk() {
        val shared = dir("Game").absolutePath

        computeLibraryStoreLinks(
            installedRows = listOf(steam(10, "Game", shared), gog("g1", 1500000001, "Game", shared)),
            customRows = emptyList(),
            ownedEntries = listOf(steam(10, "Game"), gog("g1", 1500000001, "Game")),
            itchInstalls = emptyList(),
        )

        assertEquals(InstallStore.GOG, InstallOwnership.ownerOf(shared))
    }

    @Test
    fun storeOptionsListEveryStoreThatOwnsTheTitle() {
        val shared = dir("Game").absolutePath
        InstallOwnership.claim(shared, InstallStore.EPIC)

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(epic(4, "Batman™: Arkham Knight", shared)),
                customRows = emptyList(),
                ownedEntries =
                    listOf(
                        steam(10, "Batman: Arkham Knight"),
                        epic(4, "Batman™: Arkham Knight"),
                        gog("g9", 1500000009, "Some Other Game"),
                    ),
                itchInstalls = emptyList(),
            )

        assertEquals(
            listOf(InstallStore.STEAM, InstallStore.EPIC),
            result.optionsByLibraryId[2000000004]?.map { it.store },
        )
    }

    @Test
    fun aStoreWithItsOwnSeparateInstallIsNotOfferedAsASwitch() {
        val steamDir = dir("SteamGame").absolutePath
        val epicDir = dir("EpicGame").absolutePath

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(steam(10, "Game", steamDir), epic(4, "Game", epicDir)),
                customRows = emptyList(),
                ownedEntries = listOf(steam(10, "Game"), epic(4, "Game")),
                itchInstalls = emptyList(),
            )

        assertTrue(result.hiddenLibraryIds.isEmpty())
        assertEquals(
            listOf(InstallStore.STEAM),
            result.optionsByLibraryId[10]?.map { it.store },
        )
        assertEquals(
            listOf(InstallStore.EPIC),
            result.optionsByLibraryId[2000000004]?.map { it.store },
        )
    }

    @Test
    fun aGameOwnedOnOneStoreStillListsThatStore() {
        val path = dir("Game").absolutePath

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(steam(10, "Game", path)),
                customRows = emptyList(),
                ownedEntries = listOf(steam(10, "Game")),
                itchInstalls = emptyList(),
            )

        assertEquals(
            listOf(InstallStore.STEAM),
            result.optionsByLibraryId[10]?.map { it.store },
        )
    }

    @Test
    fun anInstalledRowAlwaysOffersItsOwnStoreEvenWithoutAnOwnedEntry() {
        val path = dir("Game").absolutePath

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(gog("g1", 1500000001, "Game", path)),
                customRows = emptyList(),
                ownedEntries = emptyList(),
                itchInstalls = emptyList(),
            )

        assertEquals(
            listOf(InstallStore.GOG),
            result.optionsByLibraryId[1500000001]?.map { it.store },
        )
    }

    @Test
    fun aCustomGameOffersEveryStoreThatOwnsTheTitle() {
        val path = dir("CustomGame").absolutePath

        val result =
            computeLibraryStoreLinks(
                installedRows = emptyList(),
                customRows = listOf(custom(-3, "DOOM™", path)),
                ownedEntries = listOf(steam(10, "DOOM"), gog("g9", 1500000009, "Doom")),
                itchInstalls = emptyList(),
            )

        assertTrue(result.hiddenLibraryIds.isEmpty())
        assertEquals(
            listOf(InstallStore.STEAM, InstallStore.GOG),
            result.optionsByLibraryId[-3]?.map { it.store },
        )
    }

    @Test
    fun aCustomGameOwnedNowhereOffersNoStores() {
        val path = dir("CustomGame").absolutePath

        val result =
            computeLibraryStoreLinks(
                installedRows = emptyList(),
                customRows = listOf(custom(-3, "Homebrew Thing", path)),
                ownedEntries = listOf(steam(10, "Something Else")),
                itchInstalls = emptyList(),
            )

        assertTrue(result.optionsByLibraryId[-3].orEmpty().isEmpty())
    }

    @Test
    fun aCustomGameSwitchedToAStoreIsReplacedByThatStoresRow() {
        val path = dir("CustomGame").absolutePath
        InstallOwnership.claim(path, InstallStore.STEAM)

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(steam(10, "DOOM", path)),
                customRows = listOf(custom(-3, "DOOM", path)),
                ownedEntries = listOf(steam(10, "DOOM")),
                itchInstalls = emptyList(),
            )

        assertEquals(setOf(-3), result.hiddenLibraryIds)
        assertEquals(
            listOf(InstallStore.STEAM),
            result.optionsByLibraryId[10]?.map { it.store },
        )
    }

    @Test
    fun aCustomGameSharingAFolderWithAnUnclaimedStoreInstallIsKept() {
        val path = dir("CustomGame").absolutePath

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(steam(10, "DOOM", path)),
                customRows = listOf(custom(-3, "DOOM", path)),
                ownedEntries = listOf(steam(10, "DOOM")),
                itchInstalls = emptyList(),
            )

        assertTrue(result.hiddenLibraryIds.isEmpty())
    }

    @Test
    fun anItchInstallHidesTheStoreRowSharingItsFolder() {
        val shared = dir("Game").absolutePath

        val result =
            computeLibraryStoreLinks(
                installedRows = listOf(steam(10, "Game", shared)),
                customRows = listOf(custom(-7, "Game", shared)),
                ownedEntries = listOf(steam(10, "Game")),
                itchInstalls = listOf(itch(33, "Game", shared)),
            )

        assertEquals(setOf(10), result.hiddenLibraryIds)
        assertEquals(InstallStore.ITCH, result.activeStoreByLibraryId[-7])
        assertEquals(
            listOf(InstallStore.STEAM, InstallStore.ITCH),
            result.optionsByLibraryId[-7]?.map { it.store },
        )
        assertEquals(-7, result.optionsByLibraryId[-7]?.first { it.store == InstallStore.ITCH }?.libraryId)
    }

    @Test
    fun plainCustomShortcutsAreNeverMergedWithEachOther() {
        val shared = dir("Game").absolutePath

        val result =
            computeLibraryStoreLinks(
                installedRows = emptyList(),
                customRows = listOf(custom(-1, "Launcher", shared), custom(-2, "Game", shared)),
                ownedEntries = emptyList(),
                itchInstalls = emptyList(),
            )

        assertTrue(result.hiddenLibraryIds.isEmpty())
    }

    @Test
    fun titleKeyIgnoresTrademarksAndPunctuation() {
        assertEquals(
            LibraryStoreLinks.titleKey("Batman: Arkham Knight"),
            LibraryStoreLinks.titleKey("Batman™: Arkham  Knight"),
        )
        assertTrue(LibraryStoreLinks.titleKey("").isEmpty())
    }

    @Test
    fun ownershipRoundTripsThroughDisk() {
        val path = dir("Game").absolutePath

        assertNull(InstallOwnership.ownerOf(path))
        assertFalse(InstallOwnership.isForeign(path, InstallStore.STEAM))

        assertTrue(InstallOwnership.claim(path, InstallStore.GOG))
        assertEquals(InstallStore.GOG, InstallOwnership.ownerOf(path))
        assertTrue(InstallOwnership.isForeign(path, InstallStore.STEAM))
        assertFalse(InstallOwnership.isForeign(path, InstallStore.GOG))

        assertFalse(InstallOwnership.claimIfUnowned(path, InstallStore.STEAM))
        assertEquals(InstallStore.GOG, InstallOwnership.ownerOf(path))

        assertFalse(InstallOwnership.release(path, InstallStore.STEAM))
        assertTrue(InstallOwnership.release(path, InstallStore.GOG))
        assertNull(InstallOwnership.ownerOf(path))
    }

    @Test
    fun ownershipIgnoresMissingAndUnreadableFolders() {
        assertNull(InstallOwnership.ownerOf(""))
        assertNull(InstallOwnership.ownerOf(File(temp.root, "nope").absolutePath))
        assertFalse(InstallOwnership.claim(File(temp.root, "nope").absolutePath, InstallStore.STEAM))
    }
}
