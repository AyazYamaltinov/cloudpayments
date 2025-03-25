package com.shushper.cloudpayments

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.util.Log
import androidx.annotation.NonNull
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wallet.*
import com.shushper.cloudpayments.googlepay.GooglePayUtil
import com.shushper.cloudpayments.sdk.cp_card.CPCard
import com.shushper.cloudpayments.sdk.three_ds.ThreeDSDialogListener
import com.shushper.cloudpayments.sdk.three_ds.ThreeDsDialogFragment
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

const val LOAD_PAYMENT_DATA_REQUEST_CODE = 991

/** CloudpaymentsPlugin */
class CloudpaymentsPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {
    private lateinit var channel: MethodChannel
    private var activity: FlutterFragmentActivity? = null
    private var binding: ActivityPluginBinding? = null
    private var paymentsClient: PaymentsClient? = null
    private var lastPaymentResult: Result? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "cloudpayments")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activity = binding.activity as? FlutterFragmentActivity
        this.binding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        this.activity = null
        this.paymentsClient = null
        binding?.removeActivityResultListener(this)
        binding = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "isValidNumber" -> result.success(isValidNumber(call))
            "isValidExpiryDate" -> result.success(isValidExpiryDate(call))
            "cardCryptogram" -> result.success(cardCryptogram(call))
            "show3ds" -> show3ds(call, result)
            "createPaymentsClient" -> createPaymentsClient(call, result)
            "isGooglePayAvailable" -> checkIsGooglePayAvailable(result)
            "requestGooglePayPayment" -> requestGooglePayPayment(call, result)
            else -> result.notImplemented()
        }
    }

    private fun isValidNumber(call: MethodCall): Boolean {
        val cardNumber = call.argument<String>("cardNumber") ?: return false
        return CPCard.isValidNumber(cardNumber)
    }

    private fun isValidExpiryDate(call: MethodCall): Boolean {
        val expiryDate = call.argument<String>("expiryDate") ?: return false
        return CPCard.isValidExpDate(expiryDate)
    }

    private fun cardCryptogram(call: MethodCall): Map<String, Any?> {
        val cardNumber = call.argument<String>("cardNumber") ?: return mapOf("error" to "Missing cardNumber")
        val cardDate = call.argument<String>("cardDate") ?: return mapOf("error" to "Missing cardDate")
        val cardCVC = call.argument<String>("cardCVC") ?: return mapOf("error" to "Missing cardCVC")
        val publicId = call.argument<String>("publicId") ?: return mapOf("error" to "Missing publicId")

        val card = CPCard(cardNumber, cardDate, cardCVC)
        return try {
            mapOf("cryptogram" to card.cardCryptogram(publicId), "error" to null)
        } catch (e: Exception) {
            mapOf("cryptogram" to null, "error" to e.javaClass.simpleName)
        }
    }

    private fun show3ds(call: MethodCall, result: Result) {
        val acsUrl = call.argument<String>("acsUrl") ?: return result.error("MissingParam", "acsUrl is required", null)
        val transactionId = call.argument<String>("transactionId") ?: return result.error("MissingParam", "transactionId is required", null)
        val paReq = call.argument<String>("paReq") ?: return result.error("MissingParam", "paReq is required", null)

        activity?.let {
            val dialog = ThreeDsDialogFragment.newInstance(acsUrl, transactionId, paReq)
            dialog.show(it.supportFragmentManager, "3DS")
            dialog.setListener(object : ThreeDSDialogListener {
                override fun onAuthorizationCompleted(md: String, paRes: String) {
                    result.success(mapOf("md" to md, "paRes" to paRes))
                }
                override fun onAuthorizationFailed(html: String?) {
                    result.error("AuthorizationFailed", "authorizationFailed", null)
                }
                override fun onCancel() {
                    result.success(null)
                }
            })
        }
    }

    private fun createPaymentsClient(call: MethodCall, result: Result) {
        val environment = when (call.argument<String>("environment")) {
            "test" -> WalletConstants.ENVIRONMENT_TEST
            "production" -> WalletConstants.ENVIRONMENT_PRODUCTION
            else -> WalletConstants.ENVIRONMENT_TEST
        }
        activity?.let {
            paymentsClient = GooglePayUtil.createPaymentsClient(it, environment)
            result.success(null)
        } ?: result.error("GooglePayError", "Couldn't create Payments Client", null)
    }

    private fun checkIsGooglePayAvailable(result: Result) {
        val request = GooglePayUtil.isReadyToPayRequest()?.let { IsReadyToPayRequest.fromJson(it.toString()) }
        if (request == null) {
            result.error("GooglePayError", "Google Pay is not available", null)
            return
        }
        paymentsClient?.isReadyToPay(request)?.addOnCompleteListener { task ->
            try {
                result.success(task.getResult(ApiException::class.java))
            } catch (e: ApiException) {
                result.error("GooglePayError", e.message, null)
            }
        }
    }

    private fun requestGooglePayPayment(call: MethodCall, result: Result) {
        val price = call.argument<String>("price") ?: return result.error("MissingParam", "price is required", null)
        val currencyCode = call.argument<String>("currencyCode") ?: return result.error("MissingParam", "currencyCode is required", null)
        val countryCode = call.argument<String>("countryCode") ?: return result.error("MissingParam", "countryCode is required", null)
        val merchantName = call.argument<String>("merchantName") ?: return result.error("MissingParam", "merchantName is required", null)
        val publicId = call.argument<String>("publicId") ?: return result.error("MissingParam", "publicId is required", null)

        val paymentDataRequestJson = GooglePayUtil.getPaymentDataRequest(price, currencyCode, countryCode, merchantName, publicId)
        val request = paymentDataRequestJson?.let { PaymentDataRequest.fromJson(it.toString()) }
        if (request == null) {
            result.error("RequestPayment", "Can't fetch payment data request", null)
            return
        }

        lastPaymentResult = result
        activity?.let {
            paymentsClient?.loadPaymentData(request)?.let { task ->
                AutoResolveHelper.resolveTask(task, it, LOAD_PAYMENT_DATA_REQUEST_CODE)
            }
        }
    }
}
