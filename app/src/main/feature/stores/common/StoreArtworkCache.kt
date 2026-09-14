package com.winlator.cmod.feature.stores.common

import android.content.Context
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.gog.data.GOGGame
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object StoreArtworkCache {
    private const val ROOT_DIR = "library_artwork_cache"
    private const val TAG = "StoreArtworkCache"
    private const val MAX_CONCURRENT_DOWNLOADS = 12

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(MAX_CONCURRENT_DOWNLOADS, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }

    data class ArtworkRef(
        val store: String,
        val gameId: String,
        val slot: String,
        val url: String,
    )

    fun imageModel(
        context: Context,
        ref: ArtworkRef?,
    ): Any? {
        if (ref == null || ref.url.isBlank()) return null
        val file = cacheFile(context, ref)
        return if (file.exists() && file.length() > 0L) file else ref.url
    }

    suspend fun cacheAll(
        context: Context,
        refs: Collection<ArtworkRef>,
    ): Boolean {
        val distinctRefs =
            refs
                .filter { it.url.isNotBlank() && it.url.startsWith("http", ignoreCase = true) }
                .distinctBy { "${it.store}:${it.gameId}:${it.slot}:${it.url}" }
        if (distinctRefs.isEmpty()) return false

        val semaphore = Semaphore(permits = MAX_CONCURRENT_DOWNLOADS)
        return coroutineScope {
            distinctRefs
                .map { ref ->
                    async {
                        semaphore.withPermit {
                            cache(context, ref)
                        }
                    }
                }.awaitAll()
                .any { it }
        }
    }

    fun deleteGame(
        context: Context,
        store: String,
        gameId: String,
    ): Boolean {
        val dir = gameDir(context, store, gameId)
        if (!dir.exists()) return true
        return runCatching { dir.deleteRecursively() }
            .onFailure { Timber.tag(TAG).w(it, "Failed to delete artwork cache for %s/%s", store, gameId) }
            .getOrDefault(false)
    }

    fun deleteSlots(
        context: Context,
        store: String,
        gameId: String,
        slots: Collection<String>,
    ): Boolean {
        if (slots.isEmpty()) return false
        val dir = gameDir(context, store, gameId)
        if (!dir.exists()) return false

        val prefixes = slots.map { "${safeName(it)}_" }.toSet()
        return runCatching {
            val files =
                dir.listFiles()
                ?.filter { file -> file.isFile && prefixes.any { prefix -> file.name.startsWith(prefix) } }
                .orEmpty()
            files.isNotEmpty() && files.all { it.delete() || !it.exists() }
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to delete artwork cache slots for %s/%s", store, gameId)
        }.getOrDefault(false)
    }

    fun steamRefs(app: SteamApp): List<ArtworkRef> {
        val refs =
            mutableListOf(
                ArtworkRef("steam", app.id.toString(), "small_capsule", app.getSmallCapsuleUrl()),
                ArtworkRef("steam", app.id.toString(), "header", app.getHeaderImageUrl()),
                ArtworkRef("steam", app.id.toString(), "capsule", app.getCapsuleUrl()),
                ArtworkRef("steam", app.id.toString(), "library_capsule", app.getLibraryCapsuleUrl()),
                ArtworkRef("steam", app.id.toString(), "hero", app.getHeroUrl()),
                ArtworkRef("steam", app.id.toString(), "logo", app.getLogoUrl()),
            )
        if (app.iconHash.isNotBlank()) refs += ArtworkRef("steam", app.id.toString(), "icon", app.iconUrl)
        if (app.logoHash.isNotBlank()) refs += ArtworkRef("steam", app.id.toString(), "legacy_logo", app.logoUrl)
        if (app.logoSmallHash.isNotBlank()) refs += ArtworkRef("steam", app.id.toString(), "legacy_logo_small", app.logoSmallUrl)
        return refs.distinctBy { "${it.slot}:${it.url}" }
    }

    fun steamRef(
        app: SteamApp,
        slot: String,
        url: String,
    ): ArtworkRef = ArtworkRef("steam", app.id.toString(), slot, url)

    fun libraryRefs(
        app: SteamApp,
        gogGame: GOGGame?,
        epicGame: EpicGame?,
    ): List<ArtworkRef> =
        when {
            gogGame != null -> gogRefs(gogGame)
            epicGame != null -> epicRefs(epicGame)
            app.id < 0 -> emptyList()
            else -> steamRefs(app)
        }

    fun primaryRef(
        app: SteamApp,
        gogGame: GOGGame?,
        epicGame: EpicGame?,
        useLibraryCapsule: Boolean,
        listMode: Boolean,
        preferWide: Boolean = false,
    ): ArtworkRef? =
        when {
            gogGame != null -> gogPrimaryRef(gogGame, preferWide)
            epicGame != null -> epicPrimaryRef(epicGame, preferWide)
            app.id < 0 -> null
            else -> {
                val slot: String
                val url: String
                when {
                    listMode -> {
                        slot = "small_capsule"
                        url = app.getSmallCapsuleUrl()
                    }
                    useLibraryCapsule -> {
                        slot = "library_capsule"
                        url = app.getLibraryCapsuleUrl()
                    }
                    preferWide -> {
                        slot = "header"
                        url = app.getHeaderImageUrl()
                    }
                    else -> {
                        slot = "capsule"
                        url = app.getCapsuleUrl()
                    }
                }
                steamRef(app, slot, url)
            }
        }

    fun heroRef(
        app: SteamApp,
        gogGame: GOGGame?,
        epicGame: EpicGame?,
    ): ArtworkRef? =
        when {
            gogGame != null -> gogHeroRef(gogGame)
            epicGame != null -> epicHeroRef(epicGame)
            app.id < 0 -> null
            else -> steamRef(app, "hero", app.getHeroUrl())
        }

    fun epicRefs(game: EpicGame): List<ArtworkRef> =
        listOf(
            ArtworkRef("epic", game.id.toString(), "cover", game.artCover),
            ArtworkRef("epic", game.id.toString(), "square", game.artSquare),
            ArtworkRef("epic", game.id.toString(), "logo", game.artLogo),
            ArtworkRef("epic", game.id.toString(), "hero", game.artPortrait),
        ).filter { it.url.isNotBlank() }

    fun epicPrimaryRef(
        game: EpicGame,
        preferWide: Boolean = false,
    ): ArtworkRef? =
        when {
            preferWide && game.artPortrait.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "hero", game.artPortrait)
            game.artCover.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "cover", game.artCover)
            game.artSquare.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "square", game.artSquare)
            game.artLogo.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "logo", game.artLogo)
            game.artPortrait.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "hero", game.artPortrait)
            else -> null
        }

    fun epicHeroRef(game: EpicGame): ArtworkRef? =
        when {
            game.artPortrait.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "hero", game.artPortrait)
            else -> epicPrimaryRef(game)
        }

    fun gogRefs(game: GOGGame): List<ArtworkRef> =
        listOf(
            ArtworkRef("gog", game.id, "cover", game.imageUrl),
            ArtworkRef("gog", game.id, "hero", game.heroImageUrl),
            ArtworkRef("gog", game.id, "icon", game.iconUrl),
        ).filter { it.url.isNotBlank() }

    fun gogPrimaryRef(
        game: GOGGame,
        preferWide: Boolean = false,
    ): ArtworkRef? =
        when {
            preferWide && game.heroImageUrl.isNotBlank() -> ArtworkRef("gog", game.id, "hero", game.heroImageUrl)
            game.imageUrl.isNotBlank() -> ArtworkRef("gog", game.id, "cover", game.imageUrl)
            game.iconUrl.isNotBlank() -> ArtworkRef("gog", game.id, "icon", game.iconUrl)
            else -> null
        }

    fun gogHeroRef(game: GOGGame): ArtworkRef? =
        when {
            game.heroImageUrl.isNotBlank() -> ArtworkRef("gog", game.id, "hero", game.heroImageUrl)
            else -> gogPrimaryRef(game)
        }

    private fun cache(
        context: Context,
        ref: ArtworkRef,
    ): Boolean {
        val target = cacheFile(context, ref)
        if (target.exists() && target.length() > 0L) return false

        return runCatching {
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(ref.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).d("Artwork request failed %s for %s", response.code, ref.url)
                    return false
                }
                val body = response.body ?: return false
                val temp = File(target.parentFile, "${target.name}.tmp")
                body.byteStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (temp.length() == 0L) {
                    temp.delete()
                    return false
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                true
            }
        }.onFailure {
            Timber.tag(TAG).d(it, "Failed to cache artwork %s", ref.url)
        }.getOrDefault(false)
    }

    private fun cacheFile(
        context: Context,
        ref: ArtworkRef,
    ): File {
        val ext = extensionFromUrl(ref.url)
        return File(gameDir(context, ref.store, ref.gameId), "${safeName(ref.slot)}_${sha256(ref.url)}$ext")
    }

    private fun gameDir(
        context: Context,
        store: String,
        gameId: String,
    ): File = File(File(File(context.filesDir, ROOT_DIR), safeName(store)), safeName(gameId))

    private fun extensionFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "ico" -> ".$ext"
            else -> ".img"
        }
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}
