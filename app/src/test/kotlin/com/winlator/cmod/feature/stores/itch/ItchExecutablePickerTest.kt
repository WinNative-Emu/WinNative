package com.winlator.cmod.feature.stores.itch

import com.winlator.cmod.feature.stores.itch.service.ItchExecutablePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ItchExecutablePickerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(
        root: File,
        path: String,
        sizeBytes: Int = 1024,
    ): File {
        val target = File(root, path)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(sizeBytes))
        return target
    }

    private fun dir(
        root: File,
        path: String,
    ): File = File(root, path).apply { mkdirs() }

    @Test
    fun picksUnityGameOverLargerCrashHandler() {
        val root = temp.newFolder("slide")
        file(root, "UnityCrashHandler32.exe", sizeBytes = 923_136)
        file(root, "Slide in the woods.exe", sizeBytes = 640_000)
        dir(root, "Slide in the woods_Data")
        dir(root, "MonoBleedingEdge")

        assertEquals("Slide in the woods.exe", ItchExecutablePicker.pick(root, "Slide in the Woods")?.name)
    }

    @Test
    fun picksSoleRootExecutable() {
        val root = temp.newFolder("cobb")
        file(root, "COBB CAN MOVE.exe", sizeBytes = 506_880)
        file(root, "package.json", sizeBytes = 64)
        dir(root, "www")

        assertEquals("COBB CAN MOVE.exe", ItchExecutablePicker.pick(root, "COBB CAN MOVE")?.name)
    }

    @Test
    fun ignoresRedistributablesAndInstallers() {
        val root = temp.newFolder("redist")
        file(root, "_CommonRedist/vcredist/vcredist_x64.exe", sizeBytes = 20_000_000)
        file(root, "DXSETUP.exe", sizeBytes = 9_000_000)
        file(root, "unins000.exe", sizeBytes = 3_000_000)
        file(root, "Pocket Quest.exe", sizeBytes = 400_000)

        assertEquals("Pocket Quest.exe", ItchExecutablePicker.pick(root, "Pocket Quest")?.name)
    }

    @Test
    fun prefersSixtyFourBitRenpyTwin() {
        val root = temp.newFolder("renpy")
        file(root, "NightMarket.exe", sizeBytes = 1_800_000)
        file(root, "NightMarket-32.exe", sizeBytes = 1_700_000)
        dir(root, "renpy")
        dir(root, "game")
        dir(root, "lib")

        assertEquals("NightMarket.exe", ItchExecutablePicker.pick(root, "Night Market")?.name)
    }

    @Test
    fun prefersTitleMatchOverGenericLauncherDeeperDown() {
        val root = temp.newFolder("nested")
        file(root, "Gamma Emerald.exe", sizeBytes = 800_000)
        dir(root, "Gamma Emerald_Data")
        file(root, "extras/Level Editor.exe", sizeBytes = 4_000_000)

        assertEquals("Gamma Emerald.exe", ItchExecutablePicker.pick(root, "Pokemon Gamma Emerald")?.name)
    }

    @Test
    fun fallsBackToExcludedCandidateWhenNothingElseExists() {
        val root = temp.newFolder("installer-only")
        file(root, "setup_game.exe", sizeBytes = 50_000_000)

        assertEquals("setup_game.exe", ItchExecutablePicker.pick(root, "Some Game")?.name)
    }

    @Test
    fun returnsNullWhenNoExecutablesExist() {
        val root = temp.newFolder("empty")
        file(root, "readme.txt", sizeBytes = 12)

        assertNull(ItchExecutablePicker.pick(root, "Some Game"))
    }
}
