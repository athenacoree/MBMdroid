package com.locol.mbmdroid.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.locol.mbmdroid.ui.MainActivity

/**
 * Se dispara vía AlarmManager (nivel de sistema operativo), NO depende de que
 * MbMdroid ni la mini-app estén corriendo. Esto es lo que garantiza que una
 * alarma programada por un "Reloj" hecho en HTML siga sonando aunque el
 * usuario haya cerrado la app por completo (o hasta reiniciado el teléfono,
 * si se reprograma con RECEIVE_BOOT_COMPLETED — ver README).
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mbmdroid:alarm")
        wakeLock.acquire(60_000)

        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Alarma"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""

        showNotification(context, alarmId, title, message)
        playAlarmSound(context)

        wakeLock.release()
    }

    private fun showNotification(context: Context, alarmId: Int, title: String, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Alarmas de MbMdroid", NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true) }
            manager.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            context, alarmId, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(alarmId, notification)
    }

    private fun playAlarmSound(context: Context) {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone: Ringtone = RingtoneManager.getRingtone(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
        } catch (_: Exception) {
            // Si el dispositivo no tiene tono de alarma configurado, la notificación
            // ya vibra/suena con su propio canal — no es un fallo crítico.
        }
    }

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        private const val CHANNEL_ID = "mbmdroid_alarms"
    }
}
