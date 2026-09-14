package com.winlator.cmod.shared.android
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.app.shell.UnifiedActivity
import javax.inject.Inject


class NotificationHelper
    @Inject
    constructor(
        private val context: Context,
    ) {
        companion object {
            private const val CHANNEL_ID = "pluvia_foreground_service"
            private const val CHANNEL_NAME = "WinNative Foreground Service"

            // This constant is passed directly from the class that starts the notification.
            //private const val NOTIFICATION_ID = 1

            private const val CHAT_CHANNEL_ID = "winnative_steam_chat"
            private const val CHAT_CHANNEL_NAME = "Steam Chat"

            const val EXTRA_OPEN_CHAT_FRIEND_ID = BuildConfig.APPLICATION_ID + ".OPEN_CHAT_FRIEND_ID"

            const val ACTION_EXIT = BuildConfig.APPLICATION_ID + ".EXIT"
        }

        private val notificationManager: NotificationManager =
            context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        init {
            // Default channel
            createNotificationChannel()

            // Steam chat channel
            createNotificationChannel(
                context.applicationContext,
                CHAT_CHANNEL_ID,
                CHAT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
                "Incoming Steam friend messages",
                true
            )
        }

    // Sends or updates a notification.
    fun notify(
        id: Int,
        content: String,
        title: String = context.getString(R.string.common_ui_app_name),
        serviceClass: Class<*>? = null,
        exitAction: String? = null
    ) {
        val notification = createForegroundNotification(content, title, serviceClass, exitAction)
        notificationManager.notify(id, notification)
    }

    // Overload to notify using a pre-built Notification object.
    fun notify(id: Int, notification: Notification) {
        notificationManager.notify(id, notification)
    }

    fun cancel(id: Int) {
        notificationManager.cancel(id)
    }

    fun createForegroundNotification(
        content: String,
        title: String = context.getString(R.string.common_ui_app_name),
        serviceClass: Class<*>? = null,
        exitAction: String? = null,
        targetActivity: Class<*> = UnifiedActivity::class.java
    ): Notification {
        val intent = Intent(context, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        // Add "Exit" button only if service and action are provided
        if (serviceClass != null && exitAction != null) {
            val stopIntent = Intent(context, serviceClass).apply { action = exitAction }
            val stopPendingIntent = PendingIntent.getForegroundService(
                context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Exit", stopPendingIntent)
        }

        return builder.build()
    }

    /**
     * Create a notification channel.
     * @param context The context of the app.
     * @param channelId Unique channel identifier.
     * @param name Visible channel name for the user.
     * @param importance Importance level (e.g., NotificationManager.IMPORTANCE_LOW).
     * @param desc Channel description (optional).
     * @param showBadge Whether to show a badge for this channel (optional).
     */
    fun createNotificationChannel(
        context: Context,
        channelId: String?,
        name: String?,
        importance: Int,
        desc: String,
        showBadge: Boolean
    ) {
        val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
        if (nm != null && nm.getNotificationChannel(channelId) == null) {
            val channel =
                NotificationChannel(
                    channelId,
                    name,
                    importance
                ).apply {
                    if (desc.isNotEmpty()) description = desc
                    setShowBadge(showBadge)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }

            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Overload of createNotificationChannel()
     * without description.
     */
    fun createNotificationChannel(
        context: Context,
        channelId: String?,
        name: String?,
        importance: Int
    ) {
        createNotificationChannel(context, channelId, name, importance, "", false)
    }

    /**
     * Overload of createNotificationChannel()
     * using default values.
     */
    fun createNotificationChannel() {
        createNotificationChannel(
            context,
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
            "Allows to display WinNative foreground notifications",
            false
        )
    }

        /**
         * Generate a unique ID based on the package name and the given string
         * to avoid conflicts with other forks/flavors.
         * @param context The context of the app for get the package name.
         * @param notificationIDName A string that identifies the notification and is used
         *                           to generate a unique ID.
         * @return A unique integer identifier.
         */
        fun generateNotificationId(context: Context, notificationIDName: String): Int {
            val contextKey = context.packageName + notificationIDName
            return contextKey.hashCode() and 0x7FFFFFFF // Avoid negative IDs
        }

    private fun chatNotificationId(friendId: Long): Int = 2_000_000 + ((friendId.hashCode() and 0x7FFFFFFF) % 1_000_000)

    fun notifyChatMessage(friendId: Long, sender: String, message: String) {
        val intent =
            Intent(context, UnifiedActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_CHAT_FRIEND_ID, friendId)
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                friendId.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHAT_CHANNEL_ID)
                .setContentTitle(sender)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .build()
        notificationManager.notify(chatNotificationId(friendId), notification)
    }

    fun cancelChatNotification(friendId: Long) {
        notificationManager.cancel(chatNotificationId(friendId))
    }
}
