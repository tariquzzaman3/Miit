package com.miit.app.band

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Keeps the authenticated Xiaomi SPP connection alive when the UI Activity is gone. */
class MiitConnectionService : Service() {
    companion object {
        private const val CHANNEL_ID = "miit_band_connection"
        private const val NOTIFICATION_ID = 9001
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Miit")
            .setContentText("Xiaomi Band connection is active")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        // Creating/reusing the process singleton here means the service and Activity
        // observe the exact same SPP connection object.
        BandScanner.getInstance(this)
        MiitTestLog.add("Miit connection service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not stop the service when the task is swiped away. Android may still
        // terminate the process under memory pressure; START_STICKY then allows the
        // service to be recreated and BandScanner restores the saved Band connection.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        MiitTestLog.add("Miit connection service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Band connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Xiaomi Band connection available outside the Miit screen"
            }
        )
    }
}
