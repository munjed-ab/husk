package com.munjed.husk.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.munjed.husk.R
import com.munjed.husk.data.Prefs

/**
 * Cuts internet for the apps in [Prefs.blockedApps] with a local VPN.
 *
 * Only blocked apps are put on the tunnel and nothing ever reads or writes it, so their packets
 * are dropped while every other app keeps the normal network. Both IP families are routed, else
 * a blocked app would still reach IPv6 hosts.
 */
class BlockerVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        closeTunnel()
        // without this the system stops the service as soon as the launcher leaves the foreground,
        // which silently unblocks everything
        goForeground()
        val blocked = Prefs(this).blockedApps
        if (blocked.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        tunnel = try {
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress("10.111.222.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:111:222::1", 128)
                .addRoute("::", 0)
            // an uninstalled package throws, and one bad entry must not take the whole block list down
            blocked.forEach { runCatching { builder.addAllowedApplication(it) } }
            builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (tunnel == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.block_internet), NotificationManager.IMPORTANCE_LOW)
            )
        val count = Prefs(this).blockedApps.size
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.block_internet))
            .setContentText(getString(R.string.blocked_apps_count, count))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else
            startForeground(NOTIFICATION_ID, notification)
    }

    override fun onRevoke() {
        closeTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "blocker"
        const val NOTIFICATION_ID = 1
    }

    private fun closeTunnel() {
        runCatching { tunnel?.close() }
        tunnel = null
    }
}

/** Starts, restarts or stops the blocker so it matches [Prefs.blockedApps]. */
fun Context.syncAppBlocker() {
    val intent = Intent(this, BlockerVpnService::class.java)
    if (Prefs(this).blockedApps.isEmpty()) stopService(intent)
    else if (VpnService.prepare(this) == null) startService(intent)
}
