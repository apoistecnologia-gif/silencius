package br.com.apois.app

import android.content.Context

/**
 * Autorização local de uso derivada EXCLUSIVAMENTE de uma confirmação do servidor
 * Google Play Developer API. Nunca conceder acesso por temporizador local de 3 dias.
 * O teste gratuito é uma assinatura real gerida pela Google Play.
 */
object SubscriptionAccess {
    private const val KEY_EXPIRY = "billing_verified_expiry_ms"
    private const val KEY_VERIFIED_AT = "billing_verified_at_ms"

    fun isAllowed(context: Context): Boolean {
        // Enquanto o novo Billing não for homologado, preservar o comportamento atual.
        if (!BuildConfig.BILLING_ENFORCEMENT_ENABLED) return true
        val p = Prefs.get(context)
        val expiry = p.getLong(KEY_EXPIRY, 0L)
        val checkedAt = p.getLong(KEY_VERIFIED_AT, 0L)
        val now = System.currentTimeMillis()
        // Protege contra relógio local retrocedido antes da última verificação.
        return checkedAt > 0 && now >= checkedAt - 60_000L && now < expiry
    }

    /** Somente o verificador nativo escreve aqui após retorno autenticado do backend. */
    fun updateVerified(context: Context, active: Boolean, expiryMs: Long) {
        val now = System.currentTimeMillis()
        Prefs.get(context).edit()
            .putLong(KEY_EXPIRY, if (active && expiryMs > now) expiryMs else 0L)
            .putLong(KEY_VERIFIED_AT, now)
            .apply()
    }

    fun expiresAt(context: Context): Long = Prefs.get(context).getLong(KEY_EXPIRY, 0L)
}
