package com.winlator.cmod.app.update

import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.BuildConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Against what GitHub actually answers: the payloads under androidTest/assets/update are the
 * bodies of the real CI and official releases, saved as the API returned them.
 */
class UpdateSourceTest {
    private fun payload(name: String): String =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("update/$name")
            .bufferedReader()
            .use { it.readText() }

    private val ci769 get() = payload("ci-pr-769.json")
    private val ci770 get() = payload("ci-pr-770.json")

    @Test
    fun aPullRequestOffersOnlyItsOwnBuild() {
        assertNotNull(UpdateRelease.pickPullRequest(ci769, 769))
        assertNotNull(UpdateRelease.pickPullRequest(ci770, 770))
        // The release for one pull request can never be served to another, however it is reached.
        assertNull(UpdateRelease.pickPullRequest(ci770, 769))
        assertNull(UpdateRelease.pickPullRequest(ci769, 770))
    }

    @Test
    fun theTagAsksForThePullRequestThatWasInstalled() {
        assertEquals("pr-769", UpdateRelease.ciTag(769))
        assertTrue(UpdateRelease.ciReleaseUrl(770).endsWith("/WinNative-CI/releases/tags/pr-770"))
    }

    @Test
    fun theBuildIsTheCommitTheNotesName() {
        val release = UpdateRelease.pickPullRequest(ci769, 769)!!
        assertEquals("594231dd8e5eebfd83a20634852584c696eba924", release.buildRef)
        assertEquals(UpdateChannel.PULL_REQUEST, release.channel)
        // The tag never moves, so what is ignored has to be this build of it and not the tag.
        assertEquals("pr-769@594231dd8e5eebfd83a20634852584c696eba924", release.key)
    }

    /** The app carries a shortened commit; the notes carry the whole one. */
    @Test
    fun aShortenedCommitStillNamesTheSameBuild() {
        assertTrue(UpdateRelease.isSameBuild("594231dd", "594231dd8e5eebfd83a20634852584c696eba924"))
        assertTrue(UpdateRelease.isSameBuild("594231DD", "594231dd8e5eebfd83a20634852584c696eba924"))
        assertFalse(UpdateRelease.isSameBuild("091b4e71", "594231dd8e5eebfd83a20634852584c696eba924"))
        assertFalse(UpdateRelease.isSameBuild("", "594231dd8e5eebfd83a20634852584c696eba924"))
        assertFalse(UpdateRelease.isSameBuild("594231dd", ""))
        // Too short to mean anything on its own, so it only answers to itself.
        assertFalse(UpdateRelease.isSameBuild("5942", "594231dd8e5eebfd83a20634852584c696eba924"))
    }

    @Test
    fun theApkChosenIsThisBuildsOwnFlavour() {
        val release = UpdateRelease.pickPullRequest(ci769, 769)!!
        val flavour = BuildConfig.FLAVOR.lowercase()
        assertTrue(release.apkName, release.apkName.lowercase().contains(flavour))
        assertTrue(release.apkName, release.apkName.endsWith(".apk"))
        assertTrue(release.apkUrl, release.apkUrl.startsWith("https://"))
        assertTrue(release.apkSize > 0)
        // Every flavour that CI publishes is reachable, each getting its own file.
        val names = listOf(
            "WinNative-Debug-Standard-PR.769-59423.apk",
            "WinNative-Debug-Ludashi-PR.769-59423.apk",
            "WinNative-Debug-Pubg-PR.769-59423.apk",
            "WinNative-Debug-Antutu-PR.769-59423.apk",
        )
        for (wanted in UpdateRelease.KNOWN_VARIANTS) {
            val picked = UpdateRelease.matchApkAsset(names, wanted)
            assertNotNull(wanted, picked)
            assertTrue(wanted, picked!!.lowercase().contains(wanted))
        }
    }

    /** The official path has to keep working exactly as it did. */
    @Test
    fun officialTakesTheNewestReleaseAndKeepsTheTagAsItsKey() {
        val release = UpdateRelease.pick(payload("official.json"))!!
        assertEquals("v0.6.2-beta", release.tag)
        assertEquals(UpdateChannel.OFFICIAL, release.channel)
        assertEquals("", release.buildRef)
        assertEquals("v0.6.2-beta", release.key)
        assertTrue(release.apkName, release.apkName.lowercase().contains(BuildConfig.FLAVOR.lowercase()))
        assertTrue(release.apkName, release.apkName.contains("signed"))
    }

    @Test
    fun aReleaseWithoutACommitIsNotOffered() {
        val body = ci769.replace("Source commit:", "Built from:")
        assertNull(UpdateRelease.pickPullRequest(body, 769))
        assertEquals("", UpdateRelease.sourceCommit("no commit here"))
        assertEquals("", UpdateRelease.sourceCommit(null))
        assertEquals("abc1234", UpdateRelease.sourceCommit("Source commit: `abc1234`\r\n"))
        assertEquals("abc1234", UpdateRelease.sourceCommit("Source commit: abc1234"))
    }

    /**
     * The stamp the build carries is what decides, so this holds for a CI build and for one made
     * anywhere else. A build made outside CI follows the official releases, as it always did.
     */
    @Test
    fun theSourceFollowsTheStampTheBuildCarries() {
        val pullRequest = UpdateRelease.installedPullRequest()
        assertEquals(BuildConfig.PR_NUMBER.trim().toIntOrNull() ?: 0, pullRequest)
        if (pullRequest > 0) {
            assertTrue(UpdateRelease.isPullRequestBuild())
            assertEquals(UpdateChannel.PULL_REQUEST, UpdateService.channel())
            assertEquals("PR #$pullRequest", UpdateService.updateSourceName())
            assertTrue(UpdateService.installedVersionName().startsWith("PR #$pullRequest"))
            assertTrue(UpdateRelease.installedBuildRef().isNotEmpty())
        } else {
            assertFalse(UpdateRelease.isPullRequestBuild())
            assertEquals(UpdateChannel.OFFICIAL, UpdateService.channel())
            assertEquals("", UpdateService.updateSourceName())
            assertEquals(BuildConfig.VERSION_NAME, UpdateService.installedVersionName())
        }
    }
}
