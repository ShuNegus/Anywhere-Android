package com.argsment.anywhere.turn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.argsment.anywhere.MainActivity
import com.argsment.anywhere.R

/**
 * The "captcha required" notification (iOS: `TurnCaptchaNotifier` posting through
 * `UNUserNotificationCenter`, plus `TurnNotifications` for the permission). Spec §9.4.
 *
 * Posted with tag [TurnCaptcha.NOTIFICATION_ID], so a repost replaces it like the iOS
 * request identifier does. A tap opens [MainActivity] with [EXTRA_OPEN_CAPTCHA], which
 * re-arms the captcha sheet.
 */
object TurnCaptchaNotifications {

    const val CHANNEL_ID = "turn_captcha"
    const val EXTRA_OPEN_CAPTCHA = "open_turn_captcha"

    /** Paired with the tag; distinct from the VPN's foreground notification id (1). */
    private const val NOTIFICATION_INT_ID = 8765

    /** Set once the runtime permission has been requested: a refusal is not re-asked. */
    private const val PREF_PERMISSION_ASKED = "turnNotificationPermissionAsked"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.turn_captcha_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context) {
        if (!canPost(context)) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_CAPTCHA, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context, NOTIFICATION_INT_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.turn_captcha_notification_title))
            .setContentText(context.getString(R.string.turn_captcha_notification_body))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.turn_captcha_notification_body)))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(TurnCaptcha.NOTIFICATION_ID, NOTIFICATION_INT_ID, notification)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(TurnCaptcha.NOTIFICATION_ID, NOTIFICATION_INT_ID)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * iOS asks only while the status is `notDetermined`. Android has no such state, so
     * "never asked by us and not granted" stands in for it (API 33+ only).
     */
    fun shouldRequestPermission(context: Context): Boolean {
        if (canPost(context)) return false
        return !prefs(context).getBoolean(PREF_PERMISSION_ASKED, false)
    }

    fun markPermissionRequested(context: Context) {
        prefs(context).edit().putBoolean(PREF_PERMISSION_ASKED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(TurnSettings.PREFS_NAME, Context.MODE_PRIVATE)
}
