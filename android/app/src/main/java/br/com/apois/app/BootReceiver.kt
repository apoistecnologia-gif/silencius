package br.com.apois.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Religa o serviço de proteção depois de reiniciar o celular ou atualizar o app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (Prefs.isEnabled(ctx)) {
            GuardService.start(ctx)
            WatchdogReceiver.schedule(ctx)
        }
    }
}
