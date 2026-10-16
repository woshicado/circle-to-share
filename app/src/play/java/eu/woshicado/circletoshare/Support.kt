package eu.woshicado.circletoshare

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TableLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Play build: the support button offers tips through Google Play Billing.
 * Play doesn't allow linking to an outside donation page, so this replaces
 * the Ko-fi link of the F-Droid / GitHub build.
 */
internal fun AppCompatActivity.setupSupportButton(button: Button) {
    val tipJar = ViewModelProvider(this)[TipJar::class.java]
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = tipJar.consumeLeftovers()
    })
    button.setOnClickListener {
        tipJar.queryTips { tips ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) pickTip(button, tipJar, tips)
        }
    }
}

/** The tips in a card on the support button, like the setup helpers' cards. */
private fun AppCompatActivity.pickTip(button: View, tipJar: TipJar, tips: List<Tip>) {
    val card = AnchoredCard(this, button)
    val content = card.inflate(R.layout.popup_tips)
    val table = content.findViewById<TableLayout>(R.id.tip_options)
    lateinit var popup: PopupWindow
    for (tip in tips) {
        val kind = TIPS.getValue(tip.product.productId)
        val row = layoutInflater.inflate(R.layout.item_tip, table, false)
        row.findViewById<ImageView>(R.id.tip_icon).setImageResource(kind.icon)
        row.findViewById<TextView>(R.id.tip_price).text = tip.offer.formattedPrice
        row.findViewById<TextView>(R.id.tip_name).setText(kind.name)
        row.setOnClickListener {
            popup.dismiss()
            tipJar.buy(this, tip)
        }
        table.addView(row)
    }
    popup = card.show(content)
}

private class TipKind(@StringRes val name: Int, @DrawableRes val icon: Int)

/**
 * Three consumable in-app products, created in Play Console under these ids;
 * the prices live there. A tip unlocks nothing, so it is consumed right away
 * and can be given again.
 */
private val TIPS = mapOf(
    "tip_small" to TipKind(R.string.tip_small, R.drawable.ic_coffee),
    "tip_medium" to TipKind(R.string.tip_medium, R.drawable.ic_local_cafe),
    "tip_large" to TipKind(R.string.tip_large, R.drawable.ic_cake),
)

internal class Tip(val product: ProductDetails, val offer: ProductDetails.OneTimePurchaseOfferDetails)

/**
 * The billing connection of the setup screen; as a ViewModel it outlives a
 * rotation, so a purchase finishing meanwhile still arrives. Each time the
 * screen is shown, completed tips still on the account are consumed (which
 * acknowledges them; Google refunds a purchase left unacknowledged for three
 * days): one whose result never reached the app, or a pending payment that
 * cleared later.
 */
internal class TipJar(app: Application) : AndroidViewModel(app), PurchasesUpdatedListener {

    private val main = Handler(Looper.getMainLooper())
    private var client = newClient()
    private var ready = false
    private var connecting = false
    private var querying = false

    // What a tap asked for while not connected; done once setup succeeds.
    private var afterSetup: (() -> Unit)? = null

    init {
        connect()
    }

    private fun newClient() = BillingClient.newBuilder(getApplication())
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private fun connect() {
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) = onMain {
                connecting = false
                ready = result.responseCode == BillingResponseCode.OK
                val next = afterSetup
                afterSetup = null
                if (ready) {
                    consumeLeftovers()
                    next?.invoke()
                } else if (next != null) {
                    toast(R.string.tip_unavailable)
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    /**
     * Run [then] once connected. A client whose setup failed (no Play Store,
     * not signed in) doesn't try again by itself, so a fresh one is set up;
     * a setup still under way is waited for.
     */
    private fun whenReady(then: (() -> Unit)?) {
        if (ready) return then?.invoke() ?: Unit
        if (then != null) afterSetup = then
        if (connecting) return
        client.endConnection()
        client = newClient()
        connect()
    }

    override fun onCleared() {
        main.removeCallbacksAndMessages(null)
        client.endConnection()
    }

    /** Look up the tips, cheapest first, and hand them to [show]. */
    fun queryTips(show: (List<Tip>) -> Unit) {
        if (!ready) return whenReady { queryTips(show) }
        if (querying) return
        querying = true
        val products = TIPS.keys.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        client.queryProductDetailsAsync(params) { result, details ->
            val tips = details.productDetailsList.mapNotNull { product ->
                // Several offers are possible; the list holds those this user
                // can buy (older Play Stores report only the single one).
                val offer = product.oneTimePurchaseOfferDetailsList?.minByOrNull { it.priceAmountMicros }
                    ?: product.oneTimePurchaseOfferDetails
                offer?.takeIf { product.productId in TIPS }?.let { Tip(product, it) }
            }.sortedBy { it.offer.priceAmountMicros }
            onMain {
                querying = false
                if (result.responseCode == BillingResponseCode.OK && tips.isNotEmpty()) {
                    show(tips)
                } else {
                    toast(R.string.tip_unavailable)
                }
            }
        }
    }

    fun buy(screen: AppCompatActivity, tip: Tip) {
        val product = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(tip.product)
            .apply { tip.offer.offerToken?.let(::setOfferToken) }
            .build()
        // A launch that fails is reported to onPurchasesUpdated as well.
        client.launchBillingFlow(
            screen,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product)).build(),
        )
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) = onMain {
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases.orEmpty().filter(::isTip).forEach {
                when (it.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> {
                        toast(R.string.tip_thanks, Toast.LENGTH_LONG)
                        consume(it)
                    }
                    Purchase.PurchaseState.PENDING -> toast(R.string.tip_pending, Toast.LENGTH_LONG)
                }
            }
            BillingResponseCode.USER_CANCELED -> Unit
            // An earlier tip of this size is still open: consumed now if it
            // completed, or still waiting for its payment (which can take days).
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                consumeLeftovers()
                toast(R.string.tip_still_open, Toast.LENGTH_LONG)
            }
            else -> toast(R.string.tip_failed)
        }
    }

    /** Consume the completed tips still on the account. */
    fun consumeLeftovers() {
        if (!ready) return whenReady(null) // setup runs this check when done
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingResponseCode.OK) return@queryPurchasesAsync
            purchases.filter { isTip(it) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .forEach { onMain { consume(it) } }
        }
    }

    // A consume that fails is retried by the next check.
    private fun consume(purchase: Purchase) {
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        client.consumeAsync(params) { _, _ -> }
    }

    private fun isTip(purchase: Purchase) = purchase.products.any { it in TIPS }

    private fun onMain(block: () -> Unit) {
        main.post(block)
    }

    private fun toast(@StringRes message: Int, length: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(getApplication(), message, length).show()
    }
}
