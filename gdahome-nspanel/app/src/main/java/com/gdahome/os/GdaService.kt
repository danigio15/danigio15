package com.gdahome.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.gdahome.os.ui.AvvioActivity

/** Servizio in primo piano: mantiene attivi relè, sensori, MQTT e Home Assistant anche a schermo spento. */
class GdaService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_MIN)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, AvvioActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.service_running))
            .setSmallIcon(R.drawable.ic_stato)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, n)
        PanelHub.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) PanelHub.restart(this)
        return START_STICKY
    }

    override fun onDestroy() {
        PanelHub.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "gdahome"
        const val ACTION_RESTART = "com.gdahome.os.RESTART"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GdaService::class.java))
        }

        fun restart(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GdaService::class.java).setAction(ACTION_RESTART))
        }
    }
}
