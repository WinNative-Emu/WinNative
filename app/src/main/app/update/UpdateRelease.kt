package com.winlator.cmod.app.update

import com.winlator.cmod.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale

/**
 * Where a build takes its updates from. It is not a preference: an official install can only be
 * replaced by an official release and a pull request build only by a later build of that same
 * pull request, because anything else is signed with another key and would be refused on install.
 */
enum class UpdateChannel {
    OFFICIAL,
    PULL_REQUEST,
}

data class UpdateNoteEntry(
    val text: String,
    val pullRequest: Int,
    val author: String,
)

data class UpdateNoteSection(
    val title: String,
    val entries: List<UpdateNoteEntry>,
)

data class UpdateRelease(
    val tag: String,
    val name: String,
    val version: AppVersion,
    val preRelease: Boolean,
    val publishedAt: String,
    val htmlUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sections: List<UpdateNoteSection>,
    val channel: UpdateChannel = UpdateChannel.OFFICIAL,
    /**
     * Which build of this tag it is. A pull request keeps one tag for its whole life and replaces
     * the assets under it, so the commit is the only thing that tells two builds apart. Empty for
     * an official release, where the tag already is the identity.
     */
    val buildRef: String = "",
    val assetUpdatedAt: String = "",
) {
    val key: String get() = if (buildRef.isEmpty()) tag else "$tag@$buildRef"

    val displayDate: String get() = (assetUpdatedAt.takeIf { it.isNotBlank() } ?: publishedAt).substringBefore('T')

    /** What the build is called where a version number would go. */
    val displayVersion: String get() = if (buildRef.isEmpty()) tag else "$tag · ${shortRef(buildRef)}"

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/WinNative-Emu/WinNative/releases?per_page=30"
        const val RELEASES_PAGE = "https://github.com/WinNative-Emu/WinNative/releases"

        fun ciReleaseUrl(pullRequest: Int): String =
            "https://api.github.com/repos/WinNative-Emu/WinNative-CI/releases/tags/${ciTag(pullRequest)}"

        fun ciTag(pullRequest: Int): String = "pr-$pullRequest"

        fun shortRef(ref: String): String = if (ref.length > 8) ref.substring(0, 8) else ref

        /** The pull request this build was made for, or 0 when it was not made for one. */
        fun installedPullRequest(): Int = BuildConfig.PR_NUMBER.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0

        fun isPullRequestBuild(): Boolean = installedPullRequest() > 0

        fun installedBuildRef(): String = BuildConfig.BUILD_ID.trim()

        /**
         * Whether two commits name the same build. What the app carries is shortened and what the
         * release notes carry is not, so they are compared over the length they share, and a ref
         * too short to be meaningful only matches itself.
         */
        fun isSameBuild(
            installed: String,
            remote: String,
        ): Boolean {
            val mine = installed.trim().lowercase(Locale.US)
            val theirs = remote.trim().lowercase(Locale.US)
            if (mine.isEmpty() || theirs.isEmpty()) return false
            val shared = minOf(mine.length, theirs.length)
            if (shared < 7) return mine == theirs
            return mine.regionMatches(0, theirs, 0, shared)
        }

        val KNOWN_VARIANTS: List<String> =
            BuildConfig.KNOWN_FLAVORS
                .split(',')
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotEmpty() }

        private val TOKEN_SEPARATORS = charArrayOf('-', '_', '.', ' ', '+')

        fun matchApkAsset(
            names: List<String>,
            flavor: String,
        ): String? {
            val wanted = flavor.lowercase(Locale.US)
            if (wanted.isEmpty()) return null
            var preferred: String? = null
            var fallback: String? = null
            for (name in names) {
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val tokens =
                    name
                        .lowercase(Locale.US)
                        .removeSuffix(".apk")
                        .split(*TOKEN_SEPARATORS)
                        .filter { it.isNotEmpty() }
                val variants = KNOWN_VARIANTS.filter { tokens.contains(it) }
                if (variants.size != 1 || variants.first() != wanted) continue
                if (tokens.contains("signed")) {
                    if (preferred == null) preferred = name
                } else if (fallback == null) {
                    fallback = name
                }
            }
            return preferred ?: fallback
        }

        fun installedVersion(): AppVersion? = AppVersion.parse(BuildConfig.VERSION_NAME)

        fun isReleaseBuild(): Boolean = installedVersion() != null

        /** The official releases, newest version wins. Pre-releases are never offered here. */
        fun pick(json: String): UpdateRelease? {
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
            var best: UpdateRelease? = null
            for (i in 0 until array.length()) {
                val candidate = parse(array.optJSONObject(i) ?: continue) ?: continue
                if (candidate.preRelease) continue
                if (best == null || candidate.version > best.version) best = candidate
            }
            return best
        }

        /**
         * The one CI release for a pull request. Its tag is fixed, so the answer is whichever
         * build is under it now; whether that is newer than the installed one is decided by the
         * caller against the commit.
         */
        fun pickPullRequest(
            json: String,
            pullRequest: Int,
        ): UpdateRelease? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (o.optBoolean("draft", false)) return null
            val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            if (!tag.equals(ciTag(pullRequest), ignoreCase = true)) {
                Timber.w("CI release %s is not the pull request that was asked for (%d)", tag, pullRequest)
                return null
            }
            val assets = o.optJSONArray("assets") ?: return null
            val apk = pickAsset(assets, tag) ?: return null
            val url = apk.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return null
            val commit = sourceCommit(o.optString("body"))
            if (commit.isEmpty()) {
                Timber.w("CI release %s does not name a source commit; not offering it", tag)
                return null
            }
            return UpdateRelease(
                tag = tag,
                name = o.optString("name").takeIf { it.isNotBlank() } ?: tag,
                version = AppVersion(listOf(0), ""),
                preRelease = true,
                publishedAt = o.optString("published_at"),
                htmlUrl = o.optString("html_url"),
                apkName = apk.optString("name"),
                apkUrl = url,
                apkSize = apk.optLong("size", 0L),
                sections = parseNotes(o.optString("body")),
                channel = UpdateChannel.PULL_REQUEST,
                buildRef = commit,
                assetUpdatedAt = apk.optString("updated_at"),
            )
        }

        /** The commit the CI notes name, which is the head of the pull request, not the merge. */
        private val SOURCE_COMMIT = Regex("(?im)^[ \\t]*Source commit:[ \\t]*`?([0-9a-fA-F]{7,40})`?[ \\t\\r]*$")

        fun sourceCommit(body: String?): String {
            if (body.isNullOrBlank()) return ""
            return SOURCE_COMMIT.find(body)?.groupValues?.get(1)?.lowercase(Locale.US).orEmpty()
        }

        private fun pickAsset(
            assets: JSONArray,
            tag: String,
        ): JSONObject? {
            val byName = LinkedHashMap<String, JSONObject>()
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (name.isNotBlank()) byName[name] = asset
            }
            val chosen = matchApkAsset(byName.keys.toList(), BuildConfig.FLAVOR)
            if (chosen == null) {
                Timber.w(
                    "Release %s has no %s APK; assets=%s",
                    tag,
                    BuildConfig.FLAVOR,
                    byName.keys.joinToString(),
                )
                return null
            }
            return byName.getValue(chosen)
        }

        private fun parse(o: JSONObject): UpdateRelease? {
            if (o.optBoolean("draft", false)) return null
            val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val version = AppVersion.parse(tag) ?: return null

            val assets = o.optJSONArray("assets") ?: return null
            val apk = pickAsset(assets, tag) ?: return null

            val url = apk.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return null

            return UpdateRelease(
                tag = tag,
                name = o.optString("name").takeIf { it.isNotBlank() } ?: tag,
                version = version,
                preRelease = o.optBoolean("prerelease", false),
                publishedAt = o.optString("published_at"),
                htmlUrl = o.optString("html_url"),
                apkName = apk.optString("name"),
                apkUrl = url,
                apkSize = apk.optLong("size", 0L),
                sections = parseNotes(o.optString("body")),
            )
        }

        private val HEADING = Regex("^#{1,4}\\s+(.*)$")
        private val BULLET = Regex("^\\s*[-*]\\s+(.*)$")
        private val PR_REF = Regex("(?:https://github\\.com/[^\\s)]+/pull/(\\d+)|#(\\d+))")
        private val AUTHOR_REF = Regex("@([A-Za-z0-9][A-Za-z0-9-]*)")

        fun parseNotes(body: String?): List<UpdateNoteSection> {
            if (body.isNullOrBlank()) return emptyList()
            val sections = mutableListOf<UpdateNoteSection>()
            var title = "Changes"
            var entries = mutableListOf<UpdateNoteEntry>()

            fun flush() {
                if (entries.isNotEmpty()) {
                    sections += UpdateNoteSection(title, entries.toList())
                    entries = mutableListOf()
                }
            }

            for (raw in body.replace("\r\n", "\n").split('\n')) {
                val line = raw.trimEnd()
                val heading = HEADING.matchEntire(line.trim())
                if (heading != null) {
                    flush()
                    title = cleanHeading(heading.groupValues[1])
                    continue
                }
                val bullet = BULLET.matchEntire(line) ?: continue
                val entry = toEntry(bullet.groupValues[1]) ?: continue
                entries += entry
            }
            flush()
            return sections
        }

        private fun cleanHeading(raw: String): String {
            val trimmed = raw.trim().trim('*', '_', '#', ' ')
            return when {
                trimmed.equals("What's Changed", ignoreCase = true) -> "Changes"
                trimmed.isEmpty() -> "Changes"
                else -> trimmed
            }
        }

        private val CREDIT_LINE =
            Regex("^(.*?)\\s+by\\s+@([A-Za-z0-9][A-Za-z0-9-]*)\\s+in\\s+https://\\S+(.*)$")

        private fun toEntry(raw: String): UpdateNoteEntry? {
            val line = raw.trim()
            if (line.isEmpty()) return null
            if (line.startsWith("Full Changelog", ignoreCase = true)) return null

            val pull =
                PR_REF.find(line)?.let {
                    (it.groupValues[1].takeIf { g -> g.isNotEmpty() } ?: it.groupValues[2]).toIntOrNull()
                } ?: 0

            val credit = CREDIT_LINE.matchEntire(line)
            var text: String
            val author: String
            if (credit != null) {
                text = credit.groupValues[1].trim()
                author = credit.groupValues[2]
                val trailing = credit.groupValues[3].trim()
                if (trailing.isNotEmpty()) text = "$text — $trailing"
            } else {
                author = AUTHOR_REF.find(line)?.groupValues?.get(1).orEmpty()
                text = line.replace(Regex("\\s*in\\s+https://\\S+"), "")
            }

            text = text.replace(Regex("\\s*\\(#\\d+\\)"), "")
            text = text.replace(Regex("\\*\\*|__|`"), "")
            text = text.replace(Regex("\\s{2,}"), " ").trim().trimEnd(',')

            if (text.isEmpty()) return null
            return UpdateNoteEntry(text = text, pullRequest = pull, author = author)
        }
    }
}
