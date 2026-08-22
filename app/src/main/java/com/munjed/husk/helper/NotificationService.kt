package com.munjed.husk.helper

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.MutableLiveData

data class NotifItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val time: Long,
    val intent: PendingIntent?,
)

/** True once the user has granted "notification access" in system settings. */
fun isNotificationAccessGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * Feeds the home notification line and the panel. Holds nothing but the current filtered list in a
 * static LiveData, rebuilt whenever notifications change — so both the line (count) and the panel
 * (list) observe the same source. Runs in the default process on purpose: the separate process the
 * accessibility service uses would break that shared-memory LiveData.
 */
class NotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        refresh()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            return
        }
        items.postValue(active.filter(::keep).map(::toItem).sortedByDescending { it.time })
    }

    private fun toItem(sbn: StatusBarNotification): NotifItem {
        val extras = sbn.notification.extras
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        return NotifItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = label,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            time = sbn.postTime,
            intent = sbn.notification.contentIntent,
        )
    }

    companion object {
        val items = MutableLiveData<List<NotifItem>>(emptyList())
        private var instance: NotificationService? = null

        fun dismiss(key: String) = runCatching { instance?.cancelNotification(key) }
        fun clearAll() = runCatching { instance?.cancelAllNotifications() }

        // media transport, background services and download progress are the periodic/ongoing kind
        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
        )

        /**
         * Keep only one-time events: a message, a missed call, a real email. Drop ongoing and
         * foreground-service notifications (music player, USB charging, "app running"), progress
         * and media rows, and group summaries — whose children already carry the content.
         */
        private fun keep(sbn: StatusBarNotification): Boolean {
            if (!sbn.isClearable) return false
            val n = sbn.notification
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
            if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
            if (n.category in SKIP_CATEGORIES) return false
            val e = n.extras
            return !e.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
                !e.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
        }
    }
}
