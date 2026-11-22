package com.iamfaizankhalid.eyecare

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches for ongoing navigation notifications (Google Maps, Waze, ...) so EyeCare can
 * hold off on breaks while the user is driving.
 */
class NavigationMonitor : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        active.clear()
        runCatching { activeNotifications }.getOrNull()?.forEach {
            if (isNavigation(it)) active.add(it.key)
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        active.clear()
    }

    override fun onDestroy() {
        connected = false
        active.clear()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isNavigation(sbn)) active.add(sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        active.remove(sbn.key)
    }

    private fun isNavigation(sbn: StatusBarNotification): Boolean =
        sbn.notification?.category == Notification.CATEGORY_NAVIGATION

    companion object {
        private val active = ConcurrentHashMap.newKeySet<String>()

        @Volatile
        private var connected = false

        fun isNavigating(): Boolean = connected && active.isNotEmpty()

        fun isAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return flat.split(':').any {
                ComponentName.unflattenFromString(it)?.packageName == context.packageName
            }
        }
    }
}
