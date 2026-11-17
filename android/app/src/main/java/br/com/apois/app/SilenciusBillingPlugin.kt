package br.com.apois.app

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.Purchase
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

        // NÃO habilitar cobrança em produção sem verificação de token + reconhecimento da compra.
        private const val PURCHASE_FLOW_ENABLED = false
    }

    private var client: BillingClient? = null
    private var connecting = false
    private val pendingActions = mutableListOf<() -> Unit>()

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
                // Aviso apenas: NÃO é confirmação de direito de uso.
                notifyListeners("purchaseUpdated", update)
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
        pendingActions.add(action)
        if (connecting) return
        connecting = true
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                val queued = pendingActions.toList()
                pendingActions.clear()
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    // Cada ação usa o mesmo call que foi enfileirado; falha é comunicada pelo plugin.
                    notifyListeners("billingConnectionFailed", JSObject().apply {
                        put("responseCode", result.responseCode)
                        put("message", result.debugMessage)
                    })
                    return
                }
                queued.forEach { it.invoke() }
            }
            override fun onBillingServiceDisconnected() {
                connecting = false
            }
        })
    }

    @PluginMethod
    fun getConfig(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("productId", PRODUCT_ID)
            put("basePlanId", BASE_PLAN_ID)
            put("trialOfferId", TRIAL_OFFER_ID)
            put("purchaseEnabled", PURCHASE_FLOW_ENABLED)
            put("priceExpected", "R$ 9,90/mês")
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
                    put("purchaseEnabled", PURCHASE_FLOW_ENABLED)
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
                call.resolve(JSObject().apply {
                    put("found", owned.isNotEmpty())
                    put("verified", false)
                    put("message", if (owned.isEmpty()) "Nenhuma assinatura encontrada nesta conta Google Play"
                        else "Compra encontrada. Falta confirmar sua validade no servidor.")
                })
            }
        }
    }

    @PluginMethod
    fun purchase(call: PluginCall) {
        if (!PURCHASE_FLOW_ENABLED) {
            call.reject("Cobranças ainda não habilitadas: primeiro é necessário validar compras em um servidor seguro.")
            return
        }
        // A implementação de lançamento deve ser ativada somente após a verificação
        // segura e o reconhecimento da compra terem sido integrados e homologados.
        call.reject("Fluxo de pagamento aguardando homologação.")
    }

    override fun handleOnDestroy() {
        pendingActions.clear()
        client?.endConnection()
        client = null
        super.handleOnDestroy()
    }
}
