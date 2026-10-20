package com.align.tuition

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play Billing manager for the lifetime, non-consumable Premium product.
 *
 * Premium is granted only for a PURCHASED Play purchase. This local-only
 * implementation does not provide server-side purchase-token verification.
 */
class LifetimePremiumBilling(
    context: Context,
    private val onPremiumChanged: (Boolean) -> Unit,
    private val onMessage: (String) -> Unit
) {
    companion object {
        const val PRODUCT_ID = "align_tuition_premium_lifetime"
        private const val PREFS = "tuition_lifetime_premium"
        private const val KEY_PREMIUM = "premium_cached"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    var isPremium: Boolean = prefs.getBoolean(KEY_PREMIUM, false)
        private set

    private var productDetails: ProductDetails? = null
    private var connecting = false

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when {
            result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null -> {
                processPurchases(purchases)
            }
            result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> {
                onMessage("Purchase cancelled.")
            }
            result.responseCode != BillingClient.BillingResponseCode.OK -> {
                onMessage("Google Play Billing: ${result.debugMessage}")
            }
        }
    }

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    fun connect() {
        if (billingClient.isReady || connecting) {
            if (billingClient.isReady) {
                queryProduct()
                restorePurchases()
            }
            return
        }

        connecting = true
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                Log.e(
                    "AlignBillingDiag",
                    "setup responseCode=${result.responseCode}, debugMessage=${result.debugMessage}"
                )
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    restorePurchases()
                } else {
                    onMessage("Google Play is unavailable: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                // Call connect() again when the screen/app is resumed.
            }
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { result, detailsResult ->
            val fetched = detailsResult.productDetailsList
            val unfetched = detailsResult.unfetchedProductList

            Log.e(
                "AlignBillingDiag",
                "query responseCode=${result.responseCode}, " +
                    "debugMessage=${result.debugMessage}, " +
                    "fetched=${fetched.map { it.productId }}, " +
                    "unfetched=${unfetched.map { "${it.productId}:${it.statusCode}" }}"
            )

            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = fetched.firstOrNull { it.productId == PRODUCT_ID }

                if (productDetails == null) {
                    val fetchedIds = fetched.joinToString { it.productId }
                        .ifBlank { "(none)" }
                    val unfetchedInfo = unfetched.joinToString {
                        "${it.productId}: statusCode=${it.statusCode}"
                    }.ifBlank { "(none)" }

                    onMessage(
                        "Premium product not returned. " +
                        "Fetched: $fetchedIds. Unfetched: $unfetchedInfo"
                    )
                }
            } else {
                onMessage(
                    "Could not load Premium product. " +
                    "ResponseCode=${result.responseCode}; " +
                    "Message=${result.debugMessage}"
                )
            }
        }
    }

    fun launchPurchase(activity: Activity) {
        if (!billingClient.isReady) {
            onMessage("Connecting to Google Play. Please try again.")
            connect()
            return
        }

        val details = productDetails
        if (details == null) {
            onMessage("Premium product is not available yet. Please try again.")
            queryProduct()
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            onMessage("Could not start purchase: ${result.debugMessage}")
        }
    }

    fun restorePurchases() {
        if (!billingClient.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases)
                val ownsLifetime = purchases.any {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        PRODUCT_ID in it.products
                }
                if (!ownsLifetime) setPremium(false)
            } else {
                // Do not revoke a cached entitlement when Play cannot be reached.
                onMessage("Could not check previous purchases: ${result.debugMessage}")
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        purchases.forEach { purchase ->
            if (PRODUCT_ID !in purchase.products) return@forEach
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
                if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                    onMessage("Purchase is pending. Premium activates after payment completes.")
                }
                return@forEach
            }

            if (purchase.isAcknowledged) {
                setPremium(true)
            } else {
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                billingClient.acknowledgePurchase(params) { result ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        setPremium(true)
                    } else {
                        onMessage("Purchase acknowledgement failed: ${result.debugMessage}")
                    }
                }
            }
        }
    }

    private fun setPremium(enabled: Boolean) {
        isPremium = enabled
        prefs.edit().putBoolean(KEY_PREMIUM, enabled).apply()
        onPremiumChanged(enabled)
    }

    fun close() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}
