package com.windowsandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the process at foreground priority while Windows runs, so switching to another app (or
 * turning the screen off) does not get the VM killed. The VM itself runs on native threads; this
 * service only holds the notification, a partial wake lock, and the Shut down / Force stop actions.
 */
class VmService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHUTDOWN -> { VmController.shutDown(); return START_NOT_STICKY }
            ACTION_FORCE_STOP -> { VmController.forceStop(); return START_NOT_STICKY }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_vm), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String) = PendingIntent.getService(
            this, a.hashCode(), Intent(this, VmService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        )
        // While Windows installs, say why the app should stay open: Android gives a background app
        // only a slice of the CPU (its "background" cpu group), which slows Setup several times over.
        val installing = VmController.status.value.installing || VmController.store.installing
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (installing) R.string.notif_install_title else R.string.notif_title))
            .setContentText(getString(if (installing) R.string.notif_install_text else R.string.notif_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(if (installing) R.string.notif_install_text else R.string.notif_text)))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.shut_down), action(ACTION_SHUTDOWN)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.force_stop), action(ACTION_FORCE_STOP)).build())
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wakeLock == null) {
            // Partial: the CPU keeps running the guest with the screen off (downloads, updates).
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WindowsAndroid:vm").apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "vm"
        private const val LINK_CHANNEL = "links"
        private const val NOTIF_ID = 1
        private const val LINK_NOTIF_ID = 2

        /** A link Windows wants opened (usually a sign-in) while the app is in the background. */
        fun notifyLink(context: Context, uri: Uri) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(LINK_CHANNEL, context.getString(R.string.channel_links), NotificationManager.IMPORTANCE_HIGH)
            )
            val open = PendingIntent.getActivity(
                context, uri.hashCode(), Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            nm.notify(
                LINK_NOTIF_ID,
                Notification.Builder(context, LINK_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.link_title))
                    .setContentText(uri.host ?: uri.toString())
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        const val ACTION_SHUTDOWN = "com.windowsandroid.SHUTDOWN"
        const val ACTION_FORCE_STOP = "com.windowsandroid.FORCE_STOP"
    }
}
