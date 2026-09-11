package com.winlator.cmod.feature.stores.itch.data

import androidx.annotation.StringRes
import com.winlator.cmod.R

enum class ItchPlatform {
    WINDOWS,
    LINUX,
    MACOS,
    ANDROID,
    WEB,
}

data class ItchGame(
    val id: Int,
    val title: String,
    val url: String,
    val coverUrl: String = "",
    val author: String = "",
    val shortText: String = "",
    val genre: String = "",
    val priceLabel: String = "",
    val onSale: Boolean = false,
    val platforms: Set<ItchPlatform> = emptySet(),
) {
    val isFree: Boolean get() = priceLabel.isBlank()

    val hasWindowsBuild: Boolean get() = ItchPlatform.WINDOWS in platforms

    val slug: String get() = url.trimEnd('/').substringAfterLast('/')

    val baseUrl: String get() = url.trimEnd('/').substringBeforeLast('/')
}

data class ItchUpload(
    val id: Long,
    val fileName: String,
    val sizeLabel: String,
    val sizeBytes: Long,
    val version: String,
    val uploadedAt: String = "",
    val platforms: Set<ItchPlatform>,
) {
    val buildLabel: String
        get() = listOf(version, uploadedAt).firstOrNull { it.isNotBlank() } ?: fileName
}

data class ItchUpdateInfo(
    val available: Boolean,
    val upload: ItchUpload?,
    val installedLabel: String,
    val latestLabel: String,
)

data class ItchInput(
    val slug: String,
    val label: String,
) {
    val isController: Boolean get() = slug in CONTROLLER_SLUGS

    private companion object {
        val CONTROLLER_SLUGS =
            setOf(
                "input-gamepad",
                "input-x360",
                "input-playstation",
                "input-joy-con",
                "input-joystick",
                "input-flight-stick",
                "input-racing-wheel",
                "input-wiimote",
                "input-dance-pad",
                "input-light-gun",
                "input-motion-controller",
            )
    }
}

data class ItchGameDetails(
    val game: ItchGame,
    val heroImageUrl: String = "",
    val description: String = "",
    val screenshots: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val inputs: List<ItchInput> = emptyList(),
    val infoRows: List<Pair<String, String>> = emptyList(),
    val minPriceCents: Int? = null,
) {
    val controllerInputs: List<ItchInput> get() = inputs.filter { it.isController }

    val hasControllerSupport: Boolean get() = controllerInputs.isNotEmpty()

    val inputsKnown: Boolean get() = inputs.isNotEmpty()
}

data class ItchFacet(
    val segment: String,
    @StringRes val labelRes: Int,
    val kind: Kind = Kind.SORT,
) {
    enum class Kind { ALL, SORT, GENRE, TAG, INPUT, OWNED }

    companion object {
        val ALL = ItchFacet("", R.string.itch_facet_all, Kind.ALL)
        val OWNED = ItchFacet("owned", R.string.itch_facet_owned, Kind.OWNED)
        val POPULAR = ItchFacet("", R.string.itch_facet_popular)

        private val BROWSABLE =
            listOf(
                POPULAR,
                ItchFacet("new-and-popular", R.string.itch_facet_new_and_popular),
                ItchFacet("newest", R.string.itch_facet_newest),
                ItchFacet("top-rated", R.string.itch_facet_top_rated),
                ItchFacet("top-sellers", R.string.itch_facet_top_sellers),
                ItchFacet("input-gamepad", R.string.itch_facet_gamepad, Kind.INPUT),
                ItchFacet("genre-action", R.string.itch_facet_action, Kind.GENRE),
                ItchFacet("genre-adventure", R.string.itch_facet_adventure, Kind.GENRE),
                ItchFacet("genre-rpg", R.string.itch_facet_rpg, Kind.GENRE),
                ItchFacet("genre-platformer", R.string.itch_facet_platformer, Kind.GENRE),
                ItchFacet("genre-shooter", R.string.itch_facet_shooter, Kind.GENRE),
                ItchFacet("genre-puzzle", R.string.itch_facet_puzzle, Kind.GENRE),
                ItchFacet("genre-simulation", R.string.itch_facet_simulation, Kind.GENRE),
                ItchFacet("genre-strategy", R.string.itch_facet_strategy, Kind.GENRE),
                ItchFacet("genre-sports", R.string.itch_facet_sports, Kind.GENRE),
                ItchFacet("genre-visual-novel", R.string.itch_facet_visual_novel, Kind.GENRE),
                ItchFacet("tag-horror", R.string.itch_facet_horror, Kind.TAG),
                ItchFacet("tag-pixel-art", R.string.itch_facet_pixel_art, Kind.TAG),
                ItchFacet("tag-2d", R.string.itch_facet_2d, Kind.TAG),
                ItchFacet("tag-3d", R.string.itch_facet_3d, Kind.TAG),
                ItchFacet("tag-roguelike", R.string.itch_facet_roguelike, Kind.TAG),
                ItchFacet("tag-multiplayer", R.string.itch_facet_multiplayer, Kind.TAG),
                ItchFacet("tag-anime", R.string.itch_facet_anime, Kind.TAG),
                ItchFacet("tag-retro", R.string.itch_facet_retro, Kind.TAG),
                ItchFacet("tag-story-rich", R.string.itch_facet_story_rich, Kind.TAG),
                ItchFacet("tag-sandbox", R.string.itch_facet_sandbox, Kind.TAG),
                ItchFacet("tag-fangame", R.string.itch_facet_fangame, Kind.TAG),
            )

        fun visible(signedIn: Boolean): List<ItchFacet> =
            if (signedIn) listOf(ALL, OWNED) + BROWSABLE else listOf(ALL) + BROWSABLE
    }
}

data class ItchBrowseFilter(
    val facet: ItchFacet = ItchFacet.ALL,
    val windowsOnly: Boolean = true,
) {
    val isOwned: Boolean get() = facet.kind == ItchFacet.Kind.OWNED

    val isAll: Boolean get() = facet.kind == ItchFacet.Kind.ALL

    val filtersWindowsServerSide: Boolean
        get() = windowsOnly && facet.segment.isEmpty() && facet.kind != ItchFacet.Kind.OWNED

    fun toPath(): String {
        val segments =
            when {
                facet.kind == ItchFacet.Kind.OWNED -> listOf(FREE_SEGMENT)
                facet.segment.isEmpty() -> listOf(FREE_SEGMENT, if (windowsOnly) WINDOWS_SEGMENT else "")
                facet.kind == ItchFacet.Kind.SORT -> listOf(facet.segment, FREE_SEGMENT)
                else -> listOf(FREE_SEGMENT, facet.segment)
            }.filter { it.isNotEmpty() }
        return "games/" + segments.joinToString("/")
    }

    private companion object {
        const val FREE_SEGMENT = "free"
        const val WINDOWS_SEGMENT = "platform-windows"
    }
}
