package com.winlator.cmod.app.config

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileTest {
    private val astra2Build = "nubia redmagic NP06J PQ85P01_A PQ85P01-UN qcom"
    private val astra2Marketing = "REDMAGIC Astra 2 Gaming Tablet"

    @Test
    fun marketingNameIdentifiesAstra2() {
        assertEquals(DeviceProfile.ASTRA_2, DeviceProfile.classify("", astra2Marketing))
    }

    @Test
    fun buildTokensIdentifyAstra2WithoutMarketingName() {
        assertEquals(DeviceProfile.ASTRA_2, DeviceProfile.classify(astra2Build, ""))
    }

    @Test
    fun regionalModelNamesStillMatch() {
        val regional =
            listOf(
                "nubia redmagic NP06J PQ85P01_A PQ85P01-UN qcom",
                "nubia redmagic NP06J PQ85P01_B PQ85P01-CN qcom",
                "nubia redmagic NP06P PQ85P01_A PQ85P01-EEA qcom",
            )
        regional.forEach { assertEquals(DeviceProfile.ASTRA_2, DeviceProfile.classify(it, "")) }
    }

    @Test
    fun otherRedMagicDevicesAreNotAstra2() {
        val others =
            listOf(
                "nubia redmagic NX769J aurora aurora-global qcom" to "REDMAGIC 9 Pro",
                "nubia redmagic NX729J peak peak-global qcom" to "REDMAGIC 8 Pro",
                "nubia nubia NX721J muse muse-global qcom" to "REDMAGIC Astra Gaming Tablet",
            )
        others.forEach { (build, marketing) ->
            assertEquals(DeviceProfile.DEFAULT, DeviceProfile.classify(build, marketing))
        }
    }

    @Test
    fun unrelatedDevicesFallBackToDefault() {
        val others =
            listOf(
                "oneplus oneplus CPH2649 OP5959L1 CPH2649 qcom" to "OnePlus 13",
                "google google Pixel 9 Pro caiman caiman zuma" to "Pixel 9 Pro",
                "samsung samsung SM-X910 gts9ultra gts9ultrxx qcom" to "Galaxy Tab S9 Ultra",
                "" to "",
            )
        others.forEach { (build, marketing) ->
            assertEquals(DeviceProfile.DEFAULT, DeviceProfile.classify(build, marketing))
        }
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(DeviceProfile.ASTRA_2, DeviceProfile.classify("NUBIA REDMAGIC PQ85P01_A", ""))
        assertEquals(DeviceProfile.ASTRA_2, DeviceProfile.classify("", "redmagic ASTRA 2 gaming tablet"))
    }

    @Test
    fun prefValuesRoundTrip() {
        DeviceProfile.entries.forEach {
            assertEquals(it, DeviceProfile.fromPrefValue(it.prefValue))
        }
    }

    @Test
    fun unknownPrefValueFallsBackToDefault() {
        assertEquals(DeviceProfile.DEFAULT, DeviceProfile.fromPrefValue(null))
        assertEquals(DeviceProfile.DEFAULT, DeviceProfile.fromPrefValue(""))
        assertEquals(DeviceProfile.DEFAULT, DeviceProfile.fromPrefValue("some_retired_profile"))
    }

    @Test
    fun defaultProfileUsesTheLegacyAssetDirectory() {
        assertEquals("", DeviceProfile.DEFAULT.assetToken)
        assertEquals("astra2", DeviceProfile.ASTRA_2.assetToken)
    }

    @Test
    fun astra2PrefersWideArtworkAndDefaultDoesNot() {
        assertEquals(true, DeviceProfileSettings.preferWideArtwork(DeviceProfile.ASTRA_2))
        assertEquals(false, DeviceProfileSettings.preferWideArtwork(DeviceProfile.DEFAULT))
    }

    @Test
    fun astra2LibraryImagesMatchTheSteamHeaderAndDefaultIsStock() {
        assertEquals(460f / 215f, DeviceProfileSettings.libraryImageAspect(DeviceProfile.ASTRA_2)!!, 0.001f)
        assertEquals(null, DeviceProfileSettings.libraryImageAspect(DeviceProfile.DEFAULT))
    }

    @Test
    fun astra2LibraryColumnsFollowOrientation() {
        assertEquals(4, DeviceProfileSettings.libraryColumns(DeviceProfile.ASTRA_2, 1067, portrait = false))
        assertEquals(2, DeviceProfileSettings.libraryColumns(DeviceProfile.ASTRA_2, 668, portrait = true))
    }

    @Test
    fun defaultLibraryColumnsKeepTheStockLadder() {
        assertEquals(2, DeviceProfileSettings.libraryColumns(DeviceProfile.DEFAULT, 400, portrait = true))
        assertEquals(3, DeviceProfileSettings.libraryColumns(DeviceProfile.DEFAULT, 600, portrait = false))
        assertEquals(4, DeviceProfileSettings.libraryColumns(DeviceProfile.DEFAULT, 914, portrait = false))
    }

    @Test
    fun astra2ActionCardsAreSquareOnTheTallDrawer() {
        val cardWidth = (300f - 20f - 16f) / 3f
        val tall = DeviceProfileSettings.actionCardRowHeight(
            availableHeight = 450f, cardWidth = cardWidth, rows = 2, spacing = 8f, minHeight = 72f,
            maxAspect = DeviceProfileSettings.sessionActionCardMaxAspect(DeviceProfile.ASTRA_2),
        )
        assertEquals(cardWidth, tall, 0.01f)
    }

    @Test
    fun defaultActionCardsStillFillTheDrawer() {
        val cardWidth = (300f - 20f - 16f) / 3f
        val fill = DeviceProfileSettings.actionCardRowHeight(
            availableHeight = 450f, cardWidth = cardWidth, rows = 2, spacing = 8f, minHeight = 72f,
            maxAspect = DeviceProfileSettings.sessionActionCardMaxAspect(DeviceProfile.DEFAULT),
        )
        assertEquals((450f - 8f) / 2f, fill, 0.01f)
    }

    @Test
    fun actionCardsNeverDropBelowTheMinimumHeight() {
        val h = DeviceProfileSettings.actionCardRowHeight(
            availableHeight = 100f, cardWidth = 40f, rows = 3, spacing = 8f, minHeight = 72f, maxAspect = 1.0f,
        )
        assertEquals(72f, h, 0.01f)
    }
}
