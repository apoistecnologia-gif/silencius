package br.com.apois.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Religa o serviço de proteção depois de reiniciar o celular ou atualizar o app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (!Prefs.isEnabled(ctx)) return
        // Após reiniciar o aparelho, uma renovação pode ter sido processada
        // enquanto o Silêncius estava desligado. Consultar sem liberar acesso
        // até que o servidor valide o direito de uso.
        BillingRenewalSync.maybeSync(ctx, force = true)
        if (SubscriptionAccess.isAllowed(ctx)) {
            GuardService.start(ctx)
            WatchdogReceiver.schedule(ctx)
        } else if (BillingRenewalSync.mayRecover(ctx)) {
            WatchdogReceiver.scheduleRecovery(ctx)
        }
    }
}
