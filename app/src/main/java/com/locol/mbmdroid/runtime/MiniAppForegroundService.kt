package com.locol.mbmdroid.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.locol.mbmdroid.ui.MainActivity

/**
 * Servicio en primer plano (con notificación obligatoria a partir de Android 8+)
 * que mantiene vivo el proceso mientras alguna mini-app pidió seguir corriendo
 * en segundo plano (ej. reproductor de música). Sin esto, Android mata el
 * proceso a los pocos segundos/minutos de que la app deja de estar visible.
 *
 * No dibuja nada: el WebView real sigue vivo en RuntimeRegistry. Este servicio
 * solo existe para "sostener" el proceso ante el sistema operativo.
 */
class MiniAppForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MbMdroid — Apps en segundo plano",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val count = RuntimeRegistry.backgroundAppIds().size
        val text = if (count == 1) "1 app corriendo en segundo plano"
                   else "$count apps corriendo en segundo plano"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MbMdroid")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    /** Llamar cada vez que cambia el set de apps en segundo plano, para refrescar el texto. */
    fun refresh() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val CHANNEL_ID = "mbmdroid_background"
        private const val NOTIFICATION_ID = 1001
    }
}
