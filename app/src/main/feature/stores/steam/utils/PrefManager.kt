package com.winlator.cmod.feature.stores.steam.utils
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber

object PrefManager {
    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var libraryLayoutModeCache: String? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun init(context: Context) {
        install(context)
        ensurePrefsInitialized(context.applicationContext)
    }

    private fun requirePrefs(): SharedPreferences {
        prefs?.let { return it }
        val context = appContext ?: throw IllegalStateException("PrefManager not installed. Call install() or init() first.")
        return ensurePrefsInitialized(context)
    }

    private fun ensurePrefsInitialized(context: Context): SharedPreferences {
        prefs?.let { return it }

        synchronized(this) {
            prefs?.let { return it }

            val appContext = context.applicationContext
            this.appContext = appContext
            val legacyPrefs = appContext.getSharedPreferences("PluviaPreferences", Context.MODE_PRIVATE)

            val encryptedPrefs =
                try {
                    val masterKey =
                        MasterKey
                            .Builder(appContext)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build()
                    EncryptedSharedPreferences.create(
                        appContext,
                        "PluviaPreferences_enc",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                } catch (e: Exception) {
                    Timber.e(e, "EncryptedSharedPreferences init failed")
                    throw RuntimeException("Failed to initialize secure storage", e)
                }

            prefs = encryptedPrefs
            migrateLegacyPrefsIfNeeded(legacyPrefs, appContext, encryptedPrefs)
            return encryptedPrefs
        }
    }

    private fun migrateLegacyPrefsIfNeeded(
        legacyPrefs: SharedPreferences,
        context: Context,
        encryptedPrefs: SharedPreferences,
    ) {
        val legacyEntries = legacyPrefs.all
        if (legacyEntries.isEmpty()) return

        if (encryptedPrefs.all.isEmpty()) {
            val editor = encryptedPrefs.edit()
            for ((key, value) in legacyEntries) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.apply()
            Timber.i("Migrated legacy Steam preferences into encrypted storage")
        }

        context.deleteSharedPreferences("PluviaPreferences")
    }

    private fun getString(
        key: String,
        defaultValue: String,
    ): String = requirePrefs().getString(key, defaultValue) ?: defaultValue

    private fun setString(
        key: String,
        value: String,
    ) {
        requirePrefs().edit().putString(key, value).apply()
    }

    private fun getInt(
        key: String,
        defaultValue: Int,
    ): Int = requirePrefs().getInt(key, defaultValue)

    private fun setInt(
        key: String,
        value: Int,
    ) {
        requirePrefs().edit().putInt(key, value).apply()
    }

    private fun getLong(
        key: String,
        defaultValue: Long,
    ): Long = requirePrefs().getLong(key, defaultValue)

    private fun setLong(
        key: String,
        value: Long,
    ) {
        requirePrefs().edit().putLong(key, value).apply()
    }

    private fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean = requirePrefs().getBoolean(key, defaultValue)

    private fun setBoolean(
        key: String,
        value: Boolean,
    ) {
        requirePrefs().edit().putBoolean(key, value).apply()
    }

    var username: String
        get() = getString("user_name", "")
        set(value) {
            setString("user_name", value)
        }

    var refreshToken: String
        get() = getString("refresh_token", "")
        set(value) {
            setString("refresh_token", value)
        }

    var accessToken: String
        get() = getString("access_token", "")
        set(value) {
            setString("access_token", value)
        }

    var steamUserSteamId64: Long
        get() = getLong("steam_user_steam_id_64", 0L)
        set(value) {
            setLong("steam_user_steam_id_64", value)
        }

    var steamUserAccountId: Int
        get() = getInt("steam_user_account_id", 0)
        set(value) {
            setInt("steam_user_account_id", value)
        }

    var wnHybridMode: Boolean
        get() = getBoolean("wn_hybrid_mode", true)
        set(value) {
            setBoolean("wn_hybrid_mode", value)
        }

    var wnPlanW: Boolean
        get() = getBoolean("wn_plan_w", true)
        set(value) {
            setBoolean("wn_plan_w", value)
        }


    var wnSteamAgent32: Boolean
        get() = getBoolean("wn_steam_agent_32", true)
        set(value) {
            setBoolean("wn_steam_agent_32", value)
        }

    var cellId: Int
        get() = getInt("cell_id", 0)
        set(value) {
            setInt("cell_id", value)
        }

    var cellIdManuallySet: Boolean
        get() = getBoolean("cell_id_manually_set", false)
        set(value) {
            setBoolean("cell_id_manually_set", value)
        }

    var lastPICSChangeNumber: Int
        get() = getInt("last_pics_change_number", 0)
        set(value) {
            setInt("last_pics_change_number", value)
        }

    var steamUserName: String
        get() = getString("steam_user_name", "")
        set(value) {
            setString("steam_user_name", value)
        }

    var steamUserAvatarHash: String
        get() = getString("steam_user_avatar_hash", "")
        set(value) {
            setString("steam_user_avatar_hash", value)
        }

    var friendsSnapshotJson: String
        get() = getString("friends_snapshot_json", "")
        set(value) {
            setString("friends_snapshot_json", value)
        }

    var personaState: Int
        get() = getInt("persona_state", 0)
        set(value) {
            setInt("persona_state", value)
        }

    var externalStoragePath: String
        get() = getString("external_storage_path", "")
        set(value) {
            setString("external_storage_path", value)
        }

    var useExternalStorage: Boolean
        get() = getBoolean("use_external_storage", false)
        set(value) {
            setBoolean("use_external_storage", value)
        }

    var containerLanguage: String
        get() = getString("container_language", "english")
        set(value) {
            setString("container_language", value)
        }

    var downloadSpeed: Int
        get() = getInt("download_speed", 24)
        set(value) {
            setInt("download_speed", value)
        }

    var clientId: Long
        get() = getLong("client_id", 0L)
        set(value) {
            setLong("client_id", value)
        }

    var libraryLayoutMode: String
        get() =
            libraryLayoutModeCache
                ?: getString("library_layout_mode", "GRID_4").also {
                    libraryLayoutModeCache = it
                }
        set(value) {
            libraryLayoutModeCache = value
            setString("library_layout_mode", value)
        }

    var libraryStoreVisible: String
        get() {
            val stored = getString("library_store_visible", DEFAULT_STORE_VISIBLE)
            if (!getBoolean("library_store_visible_itch_added", false)) {
                setBoolean("library_store_visible_itch_added", true)
                if (stored.isNotBlank() && !stored.split(",").contains("itch")) {
                    val merged = "$stored,itch"
                    setString("library_store_visible", merged)
                    return merged
                }
            }
            return stored
        }
        set(value) {
            setString("library_store_visible", value)
        }

    var libraryContentFilters: String
        get() = getString("library_content_filters", "games")
        set(value) {
            setString("library_content_filters", value)
        }

    var libraryForceLandscape: Boolean
        get() = getBoolean("library_force_landscape", false)
        set(value) {
            setBoolean("library_force_landscape", value)
        }

    var libraryImmersiveMode: Boolean
        get() = getBoolean("library_immersive_mode", false)
        set(value) {
            setBoolean("library_immersive_mode", value)
        }

    var libraryImmersiveBlur: Boolean
        get() = getBoolean("library_immersive_blur", false)
        set(value) {
            setBoolean("library_immersive_blur", value)
        }

    var enableSteamLogs: Boolean
        get() = getBoolean("enable_steam_logs", false)
        set(value) {
            setBoolean("enable_steam_logs", value)
        }

    var useSingleDownloadFolder: Boolean
        get() = getBoolean("use_single_download_folder", true)
        set(value) {
            setBoolean("use_single_download_folder", value)
        }

    var defaultDownloadFolder: String
        get() = getString("default_download_folder", "")
        set(value) {
            setString("default_download_folder", value)
        }

    var steamDownloadFolder: String
        get() = getString("steam_download_folder", "")
        set(value) {
            setString("steam_download_folder", value)
        }

    var epicDownloadFolder: String
        get() = getString("epic_download_folder", "")
        set(value) {
            setString("epic_download_folder", value)
        }

    var gogDownloadFolder: String
        get() = getString("gog_download_folder", "")
        set(value) {
            setString("gog_download_folder", value)
        }

    var itchDownloadFolder: String
        get() = getString("itch_download_folder", "")
        set(value) {
            setString("itch_download_folder", value)
        }

    var chatServiceEnabled: Boolean
        get() = getBoolean("chat_service_enabled", true)
        set(value) {
            setBoolean("chat_service_enabled", value)
        }

    var chatNotificationsEnabled: Boolean
        get() = getBoolean("chat_notifications_enabled", true)
        set(value) {
            setBoolean("chat_notifications_enabled", value)
        }

    var chatHeadsEnabled: Boolean
        get() = getBoolean("chat_heads_enabled", false)
        set(value) {
            setBoolean("chat_heads_enabled", value)
        }

    var chatInGameEnabled: Boolean
        get() = getBoolean("chat_in_game_enabled", false)
        set(value) {
            setBoolean("chat_in_game_enabled", value)
        }

    var chatHeadsAutoHide: Boolean
        get() = getBoolean("chat_heads_auto_hide", false)
        set(value) {
            setBoolean("chat_heads_auto_hide", value)
        }

    var chatStayRunningOnExit: Boolean
        get() = getBoolean("chat_stay_running_on_exit", false)
        set(value) {
            setBoolean("chat_stay_running_on_exit", value)
        }

    fun getSelectedBranch(appId: Int): String = getString("steam_selected_branch_$appId", "")

    fun setSelectedBranch(
        appId: Int,
        branch: String,
    ) {
        if (branch.isBlank()) {
            requirePrefs().edit().remove("steam_selected_branch_$appId").apply()
        } else {
            setString("steam_selected_branch_$appId", branch)
        }
    }

    fun getInstalledBranch(appId: Int): String = getString("steam_installed_branch_$appId", "")

    fun setInstalledBranch(
        appId: Int,
        branch: String,
    ) {
        setString("steam_installed_branch_$appId", branch.ifBlank { "public" })
    }

    fun getInstalledBuildId(appId: Int): Long = getLong("steam_installed_build_id_$appId", 0L)

    fun setInstalledBuildId(
        appId: Int,
        buildId: Long,
    ) {
        setLong("steam_installed_build_id_$appId", buildId.coerceAtLeast(0L))
    }

    fun clearInstalledBranchState(appId: Int) {
        requirePrefs()
            .edit()
            .remove("steam_installed_branch_$appId")
            .remove("steam_installed_build_id_$appId")
            .apply()
    }

    fun clearAuthTokens() {
        requirePrefs().edit().apply {
            remove("user_name")
            remove("refresh_token")
            remove("access_token")
            remove("steam_user_steam_id_64")
            remove("steam_user_account_id")
            remove("steam_user_name")
            remove("steam_user_avatar_hash")
            remove("persona_state")
            commit()
        }
    }

    fun clearPreferences() {
        libraryLayoutModeCache = null
        requirePrefs().edit().clear().commit()
    }

    const val DEFAULT_STORE_VISIBLE = "steam,epic,gog,itch"
}
