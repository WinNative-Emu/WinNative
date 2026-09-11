package com.winlator.cmod.app.db.download

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single source of truth for one download across Steam / Epic / GOG. The DownloadCoordinator
 * owns this table and uses it to enforce a global concurrency limit and to restore in-progress
 * downloads after the app is restarted.
 */
@Entity(
    tableName = "download_records",
    indices = [Index(value = ["store", "store_game_id"], unique = true)],
)
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo("store")
    val store: String,
    @ColumnInfo("store_game_id")
    val storeGameId: String,
    @ColumnInfo("title")
    val title: String = "",
    @ColumnInfo("art_url")
    val artUrl: String = "",
    @ColumnInfo("install_path")
    val installPath: String = "",
    /** JSON-encoded list of DLC IDs. Empty string when not applicable. */
    @ColumnInfo("selected_dlcs")
    val selectedDlcs: String = "",
    @ColumnInfo("language")
    val language: String = "",
    /** One of: INSTALL, UPDATE, VERIFY. */
    @ColumnInfo("task_type")
    val taskType: String = TASK_INSTALL,
    /** One of: QUEUED, DOWNLOADING, PAUSED, COMPLETE, CANCELLED, FAILED. */
    @ColumnInfo("status")
    val status: String = STATUS_QUEUED,
    @ColumnInfo("bytes_downloaded")
    val bytesDownloaded: Long = 0L,
    @ColumnInfo("bytes_total")
    val bytesTotal: Long = 0L,
    @ColumnInfo("error_message")
    val errorMessage: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo("updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STORE_STEAM = "STEAM"
        const val STORE_EPIC = "EPIC"
        const val STORE_GOG = "GOG"
        const val STORE_ITCH = "ITCH"

        const val TASK_INSTALL = "INSTALL"
        const val TASK_UPDATE = "UPDATE"
        const val TASK_VERIFY = "VERIFY"

        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_COMPLETE = "COMPLETE"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_FAILED = "FAILED"
    }
}
