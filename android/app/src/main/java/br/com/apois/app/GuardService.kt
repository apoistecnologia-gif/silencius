package br.com.apois.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Serviço em primeiro plano: mantém o processo do Silêncius vivo para que a
 * notificação de chamada do WhatsApp chegue na hora.
 *
 * Sem isso, aparelhos com hibernação agressiva (Infinix/Tecno — `com.transsion.usf`)
 * congelam o processo em segundo plano e a captura não acontece. Um serviço em
 * primeiro plano com notificação permanente não é congelado.
 */
class GuardService : Service() {

    companion object {
        private const val TAG = "SilenciusGuard"
        private const val CHANNEL = "silencius_guard"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, GuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { Log.w(TAG, "não foi possível iniciar o serviço", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, GuardService::class.java)) }
        }

        fun isRunning(ctx: Context): Boolean =
            android.app.ActivityManager.RunningServiceInfo::class.java.let { _ ->
                runCatching {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    @Suppress("DEPRECATION")
                    am.getRunningServices(200).any {
                        it.service.className == GuardService::class.java.name
                    }
                }.getOrDefault(false)
            }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        if (Prefs.isEnabled(this)) WatchdogReceiver.schedule(this)
        Log.i(TAG, "serviço de proteção iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Se o usuário desligou a proteção, o serviço se encerra.
        if (!Prefs.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL,
                getString(R.string.guard_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.guard_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL)
        else
            @Suppress("DEPRECATION")
            Notification.Builder(this)

        return builder
            .setSmallIcon(R.drawable.ic_stat_block)
            .setContentTitle(getString(R.string.guard_title))
            .setContentText(getString(R.string.guard_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(Notification.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
