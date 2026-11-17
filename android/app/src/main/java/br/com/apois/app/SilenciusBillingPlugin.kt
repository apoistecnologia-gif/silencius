package br.com.apois.app

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.Purchase
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import org.json.JSONObject
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Integração Google Play Billing. Não concede acesso apenas porque há uma compra no aparelho:
 * o token deverá ser validado no Google Play Developer API por um servidor seguro.
 * Por segurança, o fluxo de compra fica desligado até esse servidor estar configurado e testado.
 */
@CapacitorPlugin(name = "SilenciusBilling")
class SilenciusBillingPlugin : Plugin() {
    companion object {
        private const val PRODUCT_ID = "silencius_premium"
        private const val BASE_PLAN_ID = "mensal"
        private const val TRIAL_OFFER_ID = "teste-3-dias"

        // Só liberar cobrança em builds explicitamente habilitados e com backend HTTPS.
        private fun purchasesEnabled() =
            BuildConfig.BILLING_ENFORCEMENT_ENABLED &&
                BuildConfig.BILLING_VERIFY_URL.startsWith("https://")
    }

    private var client: BillingClient? = null
    private var connecting = false
    private val io = Executors.newSingleThreadExecutor()
    private val pendingActions = mutableListOf<Pair<PluginCall, () -> Unit>>()

    override fun load() {
        client = BillingClient.newBuilder(context)
            .setListener { result, purchases ->
                val update = JSObject()
                update.put("responseCode", result.responseCode)
                update.put("message", result.debugMessage)
                update.put("hasPurchased", purchases?.any {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        it.products.contains(PRODUCT_ID)
                } == true)
                notifyListeners("purchaseUpdated", update)
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases?.firstOrNull {
                        it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                            it.products.contains(PRODUCT_ID)
                    }?.let { verifyOnBackend(it, null) }
                }
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .enableAutoServiceReconnection()
            .build()
    }

    private fun whenConnected(call: PluginCall, action: () -> Unit) {
        val c = client ?: run {
            call.reject("Google Play Billing indisponível")
            return
        }
        if (c.isReady) {
            action()
            return
        }
        pendingActions.add(call to action)
        if (connecting) return
        connecting = true
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                val queued = pendingActions.toList()
                pendingActions.clear()
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    queued.forEach { (pendingCall, _) ->
                        pendingCall.reject("Falha de conexão com o Google Play: ${result.debugMessage}")
                    }
                    return
                }
                queued.forEach { (_, queuedAction) -> queuedAction.invoke() }
            }
            override fun onBillingServiceDisconnected() {
                connecting = false
                val queued = pendingActions.toList()
                pendingActions.clear()
                queued.forEach { (pendingCall, _) ->
                    pendingCall.reject("Conexão com o Google Play interrompida")
                }
            }
        })
    }

    @PluginMethod
    fun getConfig(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("productId", PRODUCT_ID)
            put("basePlanId", BASE_PLAN_ID)
            put("trialOfferId", TRIAL_OFFER_ID)
            put("purchaseEnabled", purchasesEnabled())
            put("priceExpected", "R$ 14,90/mês")
        })
    }

    @PluginMethod
    fun getOffers(call: PluginCall) {
        whenConnected(call) {
            val c = client ?: return@whenConnected
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                ))
                .build()
            c.queryProductDetailsAsync(params) { result, products ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    call.reject("Não foi possível consultar os planos: ${result.debugMessage}")
                    return@queryProductDetailsAsync
                }
                val offers = JSArray()
                products.productDetailsList.filter { it.productId == PRODUCT_ID }.forEach { product ->
                    product.subscriptionOfferDetails?.forEach { offer ->
                        val phases = offer.pricingPhases.pricingPhaseList
                        val hasTrial = phases.any { it.priceAmountMicros == 0L && it.billingPeriod == "P3D" }
                        val paid = phases.lastOrNull { it.priceAmountMicros > 0L }
                        offers.put(JSObject().apply {
                            put("basePlanId", offer.basePlanId)
                            put("offerId", offer.offerId)
                            put("hasThreeDayTrial", hasTrial)
                            put("period", paid?.billingPeriod ?: "")
                            put("formattedPrice", paid?.formattedPrice ?: "")
                            put("currencyCode", paid?.priceCurrencyCode ?: "")
                            put("eligible", offer.basePlanId == BASE_PLAN_ID)
                        })
                    }
                }
                call.resolve(JSObject().apply {
                    put("offers", offers)
                    put("purchaseEnabled", purchasesEnabled())
                    put("productAvailable", products.productDetailsList.any { it.productId == PRODUCT_ID })
                })
            }
        }
    }

    /** Apenas consulta compras da conta Google Play; NÃO concede assinatura sem verificação no servidor. */
    @PluginMethod
    fun restorePurchases(call: PluginCall) {
        whenConnected(call) {
            val c = client ?: return@whenConnected
            c.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            ) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    call.reject("Não foi possível consultar compras: ${result.debugMessage}")
                    return@queryPurchasesAsync
                }
                val owned = purchases.filter {
                    it.products.contains(PRODUCT_ID) &&
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                val purchased = owned.firstOrNull()
                if (purchased == null) {
                    SubscriptionAccess.updateVerified(context, false, 0L)
                    if (BuildConfig.BILLING_ENFORCEMENT_ENABLED) {
                        GuardService.stop(context)
                        WatchdogReceiver.cancel(context)
                    }
                    call.resolve(JSObject().apply {
                        put("found", false)
                        put("verified", true)
                        put("active", false)
                        put("message", "Nenhuma assinatura ativa nesta conta Google Play")
                    })
                } else {
                    verifyOnBackend(purchased, call)
                }
            }
        }
    }

    @PluginMethod
    fun purchase(call: PluginCall) {
        if (!purchasesEnabled()) {
            call.reject("Assinaturas ainda não habilitadas neste build.")
            return
        }
        whenConnected(call) {
            val c = client ?: return@whenConnected
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.SUBS).build()))
                .build()
            // Consultar oferta no momento de comprar, sem reutilizar dados antigos.
            c.queryProductDetailsAsync(params) { result, products ->
                val details = products.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
                if (result.responseCode != BillingClient.BillingResponseCode.OK || details == null) {
                    call.reject("Plano mensal indisponível na Google Play")
                    return@queryProductDetailsAsync
                }
                val eligible = details.subscriptionOfferDetails.orEmpty()
                    .filter { it.basePlanId == BASE_PLAN_ID }
                val offer = eligible.firstOrNull { it.offerId == TRIAL_OFFER_ID &&
                    it.pricingPhases.pricingPhaseList.any { p ->
                        p.billingPeriod == "P3D" && p.priceAmountMicros == 0L
                    }
                } ?: eligible.firstOrNull { it.offerId == null }
                if (offer == null) {
                    call.reject("Nenhuma oferta mensal disponível para esta conta")
                    return@queryProductDetailsAsync
                }
                val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(
                    listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details).setOfferToken(offer.offerToken).build())
                ).build()
                activity.runOnUiThread {
                    val launch = c.launchBillingFlow(activity, flow)
                    if (launch.responseCode == BillingClient.BillingResponseCode.OK) {
                        call.resolve(JSObject().apply { put("launched", true) })
                    } else {
                        call.reject("Google Play: ${launch.debugMessage}")
                    }
                }
            }
        }
    }

    /** Consulta a API da APOIS que valida e reconhece a compra junto ao Google Play. */
    private fun verifyOnBackend(purchase: Purchase, call: PluginCall?) {
        if (!BuildConfig.BILLING_VERIFY_URL.startsWith("https://")) {
            call?.reject("Servidor de validação não configurado")
            return
        }
        io.execute {
            try {
                val conn = (URL(BuildConfig.BILLING_VERIFY_URL).openConnection() as HttpURLConnection)
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                try {
                    val body = JSONObject().put("purchaseToken", purchase.purchaseToken).toString()
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code != 200) throw IllegalStateException("Falha HTTP ${code}")
                    val result = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                    val expires = result.optString("expiryTime", "")
                    val expiryMs = runCatching { Instant.parse(expires).toEpochMilli() }.getOrDefault(0L)
                    val active = result.optBoolean("active", false) && expiryMs > System.currentTimeMillis()
                    SubscriptionAccess.updateVerified(context, active, expiryMs)
                    if (!active && BuildConfig.BILLING_ENFORCEMENT_ENABLED) {
                        GuardService.stop(context)
                        WatchdogReceiver.cancel(context)
                    } else if (active && Prefs.isEnabled(context)) {
                        GuardService.start(context)
                        WatchdogReceiver.schedule(context)
                    }
                    val data = JSObject().apply {
                        put("verified", true)
                        put("active", active)
                        put("expiryTime", expires)
                        put("subscriptionState", result.optString("subscriptionState"))
                    }
                    activity.runOnUiThread {
                        notifyListeners("subscriptionVerified", data)
                        call?.resolve(data)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    notifyListeners("subscriptionVerificationFailed", JSObject().apply {
                        put("message", "Não foi possível verificar a assinatura agora")
                    })
                    call?.reject("Não foi possível verificar a assinatura agora")
                }
            }
        }
    }

    override fun handleOnDestroy() {
        pendingActions.clear()
        io.shutdown()
        client?.endConnection()
        client = null
        super.handleOnDestroy()
    }
}
