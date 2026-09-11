package com.winlator.cmod.runtime.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamLaunchExeFamilyTest {
    @Test
    fun stemCollapsesArchAndHandoffSuffixes() {
        assertEquals("left4dead2", XServerDisplayUtils.exeStem("left4dead2_win64.exe"))
        assertEquals("cstrike", XServerDisplayUtils.exeStem("cstrike_win64.exe"))
        assertEquals("brawlhalla", XServerDisplayUtils.exeStem("BrawlhallaGame64.exe"))
        assertEquals("foobar", XServerDisplayUtils.exeStem("FooBar-Win64-Shipping.exe"))
        assertEquals("foobar", XServerDisplayUtils.exeStem("FooBar.original.exe"))
    }

    @Test
    fun stemKeepsShortNamesIntact() {
        assertEquals("hl2", XServerDisplayUtils.exeStem("hl2.exe"))
        assertEquals("cs2", XServerDisplayUtils.exeStem("cs2.exe"))
        assertEquals("portal2", XServerDisplayUtils.exeStem("Portal2.exe"))
    }

    @Test
    fun stemCollapsesArchSuffixOnTwoLetterNames() {
        assertEquals("tf", XServerDisplayUtils.exeStem("tf_win64.exe"))
        assertEquals("tf", XServerDisplayUtils.exeStem("tf.exe"))
        assertTrue(XServerDisplayUtils.sameExeFamily("tf_win64.exe", "tf.exe"))
    }

    @Test
    fun stemDoesNotEatNamesThatAreOnlyAnArchTag() {
        assertEquals("x64", XServerDisplayUtils.exeStem("x64.exe"))
        assertEquals("32", XServerDisplayUtils.exeStem("32.exe"))
        assertEquals("game", XServerDisplayUtils.exeStem("game.exe"))
        assertEquals("launcher", XServerDisplayUtils.exeStem("launcher.exe"))
    }

    @Test
    fun stemHandlesPathsAndSeparators() {
        assertEquals(
            "foobar",
            XServerDisplayUtils.exeStem("Binaries\\Win64\\FooBar-Win64-Shipping.exe"),
        )
        assertEquals("left4dead2", XServerDisplayUtils.exeStem("bin/left4dead2_win64.exe"))
    }

    @Test
    fun archSiblingsAreTheSameFamily() {
        assertTrue(XServerDisplayUtils.sameExeFamily("left4dead2_win64.exe", "left4dead2.exe"))
        assertTrue(XServerDisplayUtils.sameExeFamily("cstrike_win64.exe", "cstrike.exe"))
        assertTrue(XServerDisplayUtils.sameExeFamily("BrawlhallaGame64.exe", "Brawlhalla.exe"))
        assertTrue(XServerDisplayUtils.sameExeFamily("brawlhalla.exe", "Brawlhalla.exe"))
        assertTrue(
            XServerDisplayUtils.sameExeFamily(
                "Binaries\\Win64\\FooBar-Win64-Shipping.exe",
                "FooBar.exe",
            ),
        )
    }

    @Test
    fun unrelatedProgramsAreNotTheSameFamily() {
        assertFalse(XServerDisplayUtils.sameExeFamily("hammer.exe", "hl2.exe"))
        assertFalse(XServerDisplayUtils.sameExeFamily("Portal2.exe", "Portal.exe"))
        assertFalse(XServerDisplayUtils.sameExeFamily("hl.exe", "hl2.exe"))
        assertFalse(XServerDisplayUtils.sameExeFamily("steamwebhelper.exe", "steam.exe"))
    }

    @Test
    fun blankNamesAreNeverTheSameFamily() {
        assertFalse(XServerDisplayUtils.sameExeFamily("", "left4dead2.exe"))
        assertFalse(XServerDisplayUtils.sameExeFamily("left4dead2.exe", ""))
        assertFalse(XServerDisplayUtils.sameExeFamily(null, "left4dead2.exe"))
    }
}
