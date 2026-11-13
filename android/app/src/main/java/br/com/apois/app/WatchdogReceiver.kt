package br.com.apois.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Rede de segurança contra a hibernação da Infinix/Tecno (`com.transsion.usf`).
 *
 * O sistema congela o processo do Silêncius ~13 s depois de cada evento e ignora
 * a lista de isenção do Android. Um alarme exato acorda o processo mesmo com o
 * aparelho parado; nele reativamos o serviço em primeiro plano e pedimos ao
 * sistema que reataque o listener de notificações, que é o que congela.
 *
 * Não faz nada se a proteção estiver desligada.
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SilenciusWatch"
        private const val INTERVAL_MS = 10_000L
        private const val REQ = 4321

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = pending(ctx)
            val at = System.currentTimeMillis() + INTERVAL_MS
            runCatching {
                val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
                if (!exactOk) {
                    // sem permissão de alarme exato: usa o alarme comum (o Android agrupa)
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
                }
            }.onFailure { Log.w(TAG, "não foi possível agendar", it) }
        }

        fun cancel(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(pending(ctx))
        }

        private fun pending(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, REQ,
                Intent(ctx, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    override fun onReceive(ctx: Context, intent: Intent?) {
        // Sempre reagenda: é uma corrente que só para quando a proteção é desligada.
        if (!Prefs.isEnabled(ctx)) {
            cancel(ctx)
            return
        }
        schedule(ctx)

        // Acorda a CPU por alguns segundos para o trabalho abaixo terminar.
        val pm = ctx.getSystemService(PowerManager::class.java)
        val lock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Silencius:watchdog")
        runCatching { lock?.acquire(15_000L) }

        try {
            GuardService.start(ctx)
            reattachListener(ctx)
            Log.i(TAG, "vigia acordou o app")
        } catch (e: Throwable) {
            Log.w(TAG, "falha no vigia", e)
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    /** Pede ao sistema que reataque o listener (é ele que a Infinix derruba). */
    private fun reattachListener(ctx: Context) {
        runCatching {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return
            if (!enabled.contains(ctx.packageName)) return

            @Suppress("DEPRECATION")
            val list = NotificationListenerService.requestRebind(
                android.content.ComponentName(ctx, CallNotificationListener::class.java)
            )
            Log.d(TAG, "rebind do listener pedido ($list)")
        }.onFailure { Log.w(TAG, "não foi possível reatar o listener", it) }
    }
}
