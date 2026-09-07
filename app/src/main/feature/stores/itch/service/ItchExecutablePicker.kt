package com.winlator.cmod.feature.stores.itch.service

import java.io.File
import java.util.Locale

object ItchExecutablePicker {
    private const val MAX_DEPTH = 6

    private val EXCLUDED_NAME_PATTERNS =
        listOf(
            Regex("^unins.*"),
            Regex("^uninstall.*"),
            Regex("^setup.*"),
            Regex("^install.*"),
            Regex(".*_installer.*"),
            Regex("^vcredist.*"),
            Regex("^vc_redist.*"),
            Regex("^dxsetup.*"),
            Regex("^dxwebsetup.*"),
            Regex("^directx.*"),
            Regex("^oalinst.*"),
            Regex("^openal.*"),
            Regex("^physx.*"),
            Regex("^xnafx.*"),
            Regex("^dotnetfx.*"),
            Regex("^ndp[0-9].*"),
            Regex("^windowsdesktop-runtime.*"),
            Regex(".*redist.*"),
            Regex(".*crashhandler.*"),
            Regex(".*crashreport.*"),
            Regex(".*crashsender.*"),
            Regex("^crashpad_handler$"),
            Regex("^bssndrpt$"),
            Regex(".*bugreport.*"),
            Regex("^notification_helper$"),
            Regex("^python[0-9w]*$"),
            Regex("^javaw?$"),
            Regex("^node$"),
            Regex("^nwjc$"),
            Regex("^ffmpeg$"),
            Regex("^7z.*"),
            Regex("^chromedriver$"),
            Regex("^update$"),
            Regex("^squirrel$"),
            Regex("^easyanticheat.*"),
            Regex("^battleye.*"),
            Regex("^beservice.*"),
            Regex(".*dedicated.*server.*"),
            Regex(".*[_ -]server$"),
            Regex(".*[_ -]editor$"),
            Regex(".*benchmark.*"),
        )

    private val EXCLUDED_DIR_SEGMENTS =
        setOf(
            "_commonredist",
            "redist",
            "redistributables",
            "directx",
            "vcredist",
            "dotnet",
            "dependencies",
            "__installer",
            "easyanticheat",
            "battleye",
            "monobleedingedge",
            "engine",
            "editor",
            "tools",
        )

    private val NOISE_TOKENS =
        setOf(
            "win", "win32", "win64", "windows", "x86", "x64", "32bit", "64bit", "bit32", "bit64",
            "pc", "demo", "build", "release", "final", "full", "game", "the", "a", "an", "of", "and",
        )

    private val VERSION_TOKEN = Regex("^v?[0-9]+([._][0-9]+)*$")

    data class Candidate(
        val file: File,
        val relativePath: String,
        val depth: Int,
        val score: Int,
    )

    fun pick(
        installDir: File,
        gameTitle: String,
    ): File? = rank(installDir, gameTitle).firstOrNull()?.file

    fun rank(
        installDir: File,
        gameTitle: String,
    ): List<Candidate> {
        if (!installDir.isDirectory) return emptyList()
        val found = mutableListOf<File>()
        collect(installDir, installDir, 0, found)
        if (found.isEmpty()) return emptyList()

        val allowed = found.filter { !isExcluded(installDir, it) }
        val usingFallback = allowed.isEmpty()
        val pool = if (usingFallback) found else allowed
        val basePenalty = if (usingFallback) -500 else 0
        val normalizedTitle = normalize(gameTitle)
        val presentBaseNames = pool.map { it.nameWithoutExtension.lowercase(Locale.ROOT) }.toSet()

        return pool
            .map { file ->
                val relative = file.relativeTo(installDir).path.replace('\\', '/')
                val depth = relative.count { it == '/' }
                Candidate(
                    file = file,
                    relativePath = relative,
                    depth = depth,
                    score = basePenalty + score(file, depth, normalizedTitle, presentBaseNames),
                )
            }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.depth }.thenBy { it.relativePath.lowercase(Locale.ROOT) })
    }

    private fun collect(
        root: File,
        dir: File,
        depth: Int,
        out: MutableList<File>,
    ) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        children.forEach { child ->
            if (child.isDirectory) {
                collect(root, child, depth + 1, out)
            } else if (child.name.endsWith(".exe", ignoreCase = true)) {
                out.add(child)
            }
        }
    }

    private fun isExcluded(
        root: File,
        file: File,
    ): Boolean {
        val relative = file.relativeTo(root).path.replace('\\', '/')
        val segments = relative.split('/')
        if (segments.dropLast(1).any { it.lowercase(Locale.ROOT) in EXCLUDED_DIR_SEGMENTS }) return true
        val base = file.nameWithoutExtension.lowercase(Locale.ROOT)
        return EXCLUDED_NAME_PATTERNS.any { it.matches(base) }
    }

    private fun score(
        file: File,
        depth: Int,
        normalizedTitle: String,
        presentBaseNames: Set<String>,
    ): Int {
        var score = 0
        val parent = file.parentFile
        val base = file.nameWithoutExtension
        val lowerBase = base.lowercase(Locale.ROOT)

        score += engineScore(parent, base)

        val normalizedExe = normalize(base)
        val normalizedFolder = normalize(parent?.name.orEmpty())
        when {
            normalizedTitle.isNotEmpty() && normalizedExe == normalizedTitle -> score += 600
            normalizedFolder.isNotEmpty() && normalizedExe == normalizedFolder -> score += 350
            normalizedTitle.length >= 4 && normalizedExe.length >= 4 &&
                (normalizedExe.contains(normalizedTitle) || normalizedTitle.contains(normalizedExe)) -> score += 300
            tokenOverlap(normalizedTitleTokens(normalizedTitle), base) -> score += 200
        }

        score +=
            when (depth) {
                0 -> 200
                1 -> 100
                2 -> 40
                else -> 0
            }

        if (Regex("(game|start|play|launch|run)").containsMatchIn(lowerBase)) score += 60

        val sizeMb = (file.length() / (1024L * 1024L)).coerceAtMost(50L).toInt()
        score += sizeMb

        val strippedArch = lowerBase.replace(Regex("([-_ ]?(32|x86|32bit))$"), "")
        if (strippedArch != lowerBase && strippedArch in presentBaseNames) score -= 250
        if (lowerBase in setOf("nw", "electron") && presentBaseNames.size > 1) score -= 150

        return score
    }

    private fun engineScore(
        parent: File?,
        base: String,
    ): Int {
        if (parent == null) return 0
        if (File(parent, "${base}_Data").isDirectory) return 1000
        if (File(parent, "$base.pck").isFile) return 900
        if (File(parent, "data.win").isFile) return 850
        if (File(parent, "renpy").isDirectory && File(parent, "game").isDirectory) return 800
        if (File(parent, "Engine").isDirectory) return 800
        if (File(parent, "resources/app.asar").isFile) return 750
        if (File(parent, "package.json").isFile || File(parent, "www").isDirectory) return 750
        if (File(parent, "Content").isDirectory) return 500
        if (File(parent, "love.dll").isFile) return 500
        return 0
    }

    private fun normalizedTitleTokens(normalizedTitle: String): Set<String> =
        if (normalizedTitle.isEmpty()) emptySet() else setOf(normalizedTitle)

    private fun tokenOverlap(
        titleTokens: Set<String>,
        base: String,
    ): Boolean {
        if (titleTokens.isEmpty()) return false
        val exeTokens = tokenize(base)
        if (exeTokens.isEmpty()) return false
        val title = titleTokens.first()
        return exeTokens.any { it.length >= 4 && title.contains(it) }
    }

    fun normalize(value: String): String = tokenize(value).joinToString("")

    private fun tokenize(value: String): List<String> =
        value
            .split(Regex("[^A-Za-z0-9]+"), limit = 0)
            .flatMap { chunk -> Regex("[A-Z]?[a-z0-9]+|[A-Z]+(?![a-z])").findAll(chunk).map { it.value } }
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it !in NOISE_TOKENS && !VERSION_TOKEN.matches(it) }
}
