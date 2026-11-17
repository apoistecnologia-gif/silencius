package br.com.apois.app

import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Atualização da validade em segundo plano, sem Activity e sem polling remoto
 * a cada alarme do vigia. O token vem exclusivamente do BillingClient do Google Play.
 *
 * O servidor continua sendo a fonte de verdade; nunca conceder direito de uso
 * por um cronômetro local ou pela simples existência de uma compra.
 *
 * Um limite em SharedPreferences impede consultas excessivas durante reinícios
 * de processo, e o AtomicBoolean impede execuções simultâneas na mesma instância.
 */
object BillingRenewalSync {
    private const val TAG = "SilenciusBillingRefresh"
    private const val PRODUCT_ID = "silencius_premium"
    private const val LAST_ATTEMPT_KEY = "billing_background_last_attempt_ms"
    private const val MINUTE = 60_000L
    private const val HOUR = 60L * MINUTE
    private const val DAY = 24L * HOUR

    private val busy = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor()

    fun mayRecover(context: Context): Boolean {
        if (!BuildConfig.BILLING_ENFORCEMENT_ENABLED || !Prefs.isEnabled(context)) return false
        val expiry = SubscriptionAccess.expiresAt(context)
        val now = System.currentTimeMillis()
        return expiry > 0L && now >= expiry && now - expiry <= DAY
    }

    fun maybeSync(context: Context, force: Boolean = false) {
        if (!BuildConfig.BILLING_ENFORCEMENT_ENABLED ||
            !BuildConfig.BILLING_VERIFY_URL.startsWith("https://") ||
            !Prefs.isEnabled(context)) return

        val ctx = context.applicationContext
        val expiry = SubscriptionAccess.expiresAt(ctx)
        val now = System.currentTimeMillis()
        if (expiry <= 0L || now - expiry > DAY) return

        val interval = when {
            now >= expiry && now - expiry <= 30L * MINUTE -> MINUTE
            now >= expiry -> 15L * MINUTE
            expiry - now <= 2L * HOUR -> MINUTE
            expiry - now <= DAY -> 10L * MINUTE
            else -> 4L * HOUR
        }
        val prefs = Prefs.get(ctx)
        val previous = prefs.getLong(LAST_ATTEMPT_KEY, 0L)
        if (!force && previous > 0L && previous <= now && now - previous < interval) return
        if (!busy.compareAndSet(false, true)) return
        prefs.edit().putLong(LAST_ATTEMPT_KEY, now).apply()

        val billing = try {
            BillingClient.newBuilder(ctx)
                .setListener { _, _ -> }
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
                )
                .enableAutoServiceReconnection()
                .build()
        } catch (_: Exception) {
            busy.set(false)
            return
        }

        fun finish() {
            runCatching { billing.endConnection() }
            busy.set(false)
        }

        try {
            billing.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        finish()
                        return
                    }
                    try {
                        billing.queryPurchasesAsync(
                            QueryPurchasesParams.newBuilder()
                                .setProductType(BillingClient.ProductType.SUBS)
                                .build()
                        ) { queryResult, purchases ->
                            if (queryResult.responseCode != BillingClient.BillingResponseCode.OK) {
                                finish()
                                return@queryPurchasesAsync
                            }
                            val purchase = purchases.firstOrNull {
                                it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                                    it.products.contains(PRODUCT_ID)
                            }
                            if (purchase == null) {
                                // Google Play respondeu com sucesso mas não encontrou
                                // assinatura pertencente à conta atual.
                                SubscriptionAccess.updateVerified(ctx, false, 0L)
                                GuardService.stop(ctx)
                                WatchdogReceiver.cancel(ctx)
                                finish()
                                return@queryPurchasesAsync
                            }
                            io.execute {
                                try {
                                    validateWithBackend(ctx, purchase.purchaseToken)
                                } catch (_: Exception) {
                                    // Falha temporária: preservar direito já confirmado
                                    // até seu vencimento. A próxima tentativa tem backoff.
                                    Log.w(TAG, "Verificação em segundo plano indisponível")
                                } finally {
                                    finish()
                                }
                            }
                        }
                    } catch (_: Exception) {
                        finish()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Nunca deixar a trava em execução permanentemente se o
                    // Google Play desconectar antes de concluir a consulta.
                    finish()
                }
            })
        } catch (_: Exception) {
            finish()
        }
    }

    private fun validateWithBackend(context: Context, purchaseToken: String) {
        val conn = URL(BuildConfig.BILLING_VERIFY_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().put("purchaseToken", purchaseToken).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return

            val result = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            if (!result.has("active") || !result.has("subscriptionState")) return
            val expiry = runCatching {
                Instant.parse(result.optString("expiryTime", "")).toEpochMilli()
            }.getOrDefault(0L)
            val isActive = result.optBoolean("active", false) &&
                expiry > System.currentTimeMillis()
            SubscriptionAccess.updateVerified(context, isActive, expiry)
            if (isActive && Prefs.isEnabled(context)) {
                GuardService.start(context)
                WatchdogReceiver.schedule(context)
            } else {
                GuardService.stop(context)
                WatchdogReceiver.cancel(context)
            }
        } finally {
            conn.disconnect()
        }
    }
}
