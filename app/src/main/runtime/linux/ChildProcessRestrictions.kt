package com.winlator.cmod.runtime.linux

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Android's phantom process monitor, as it applies to a Linux session.
 *
 * A session is one proot holding gamescope, the Steam client, its web helper and a game, which
 * passes the thirty-two child processes Android 12 and later allow an app; over that the system
 * kills them, and killing proot ends the session. The monitor is off while
 * `settings_enable_monitor_phantom_procs` is false. Developer options carries a switch for it from
 * Android 14 ("Disable child process restrictions"); before that only adb can set it, and the app
 * can do neither itself - writing it needs a permission only the system grants.
 */
object ChildProcessRestrictions {
    private const val SETTING = "settings_enable_monitor_phantom_procs"

    /** Whether this device kills a session's processes, so the user still has something to do. */
    @JvmStatic
    fun active(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val value =
            try {
                Settings.Global.getString(context.contentResolver, SETTING)
            } catch (e: SecurityException) {
                return true
            }
        return !"false".equals(value, ignoreCase = true) && value != "0"
    }

    /** Whether Developer options has the switch, or the user needs adb. */
    @JvmStatic
    fun hasSwitch(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Opens Developer options, and reports whether the device let it. */
    @JvmStatic
    fun openDeveloperOptions(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
