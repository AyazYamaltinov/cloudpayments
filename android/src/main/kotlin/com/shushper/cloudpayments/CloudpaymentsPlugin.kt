package com.shushper.cloudpayments

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.Intent
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
import java.io.UnsupportedEncodingException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import javax.crypto.NoSuchPaddingException

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
        this.activity = null
        binding?.removeActivityResultListener(this)
        binding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.activity = binding.activity as? FlutterFragmentActivity
        this.binding = binding
        binding.addActivityResultListener(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "isValidNumber" -> result.success(isValidNumber(call))
            "isValidExpiryDate" -> result.success(isValidExpiryDate(call))
            "cardCryptogram" -> result.success(cardCryptogram(call))
            "show3ds" -> show3ds(call, result)
            "createPaymentsClient" -> createPaymentsClient(call, result)
            "isGooglePayAvailable" -> checkIsGooglePayAvailable(call, result)
            "requestGooglePayPayment" -> requestGooglePayPayment(call, result)
            else -> result.notImplemented()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return if (requestCode == LOAD_PAYMENT_DATA_REQUEST_CODE) {
            when (resultCode) {
                RESULT_OK -> onPaymentOk(data)
                RESULT_CANCELED -> onPaymentCanceled()
                AutoResolveHelper.RESULT_ERROR -> onPaymentError(data)
            }
            true
        } else {
            false
        }
    }

    private fun isValidNumber(call: MethodCall): Boolean {
        val params = call.arguments as Map<String, Any>
        return CPCard.isValidNumber(params["cardNumber"] as String)
    }

    private fun isValidExpiryDate(call: MethodCall): Boolean {
        val params = call.arguments as Map<String, Any>
        return CPCard.isValidExpDate(params["expiryDate"] as String)
    }

    private fun cardCryptogram(call: MethodCall): Map<String, Any?> {
        val params = call.arguments as Map<String, Any>
        val card = CPCard(params["cardNumber"] as String, params["cardDate"] as String, params["cardCVC"] as String)
        return try {
            mapOf("cryptogram" to card.cardCryptogram(params["publicId"] as String), "error" to null)
        } catch (e: Exception) {
            e.printStackTrace()
            mapOf("cryptogram" to null, "error" to e::class.java.simpleName)
        }
    }

    private fun show3ds(call: MethodCall, result: Result) {
        val params = call.arguments as Map<String, Any>
        activity?.let {
            val dialog = ThreeDsDialogFragment.newInstance(params["acsUrl"] as String, params["transactionId"] as String, params["paReq"] as String)
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
        val environment = when ((call.arguments as Map<String, Any>)["environment"] as String) {
            "test" -> WalletConstants.ENVIRONMENT_TEST
            "production" -> WalletConstants.ENVIRONMENT_PRODUCTION
            else -> WalletConstants.ENVIRONMENT_TEST
        }

        activity?.let {
            paymentsClient = GooglePayUtil.createPaymentsClient(it, environment)
            result.success(null)
        } ?: result.error("GooglePayError", "Couldn't create Payments Client", null)
    }

    private fun checkIsGooglePayAvailable(call: MethodCall, result: Result) {
        val request = IsReadyToPayRequest.fromJson(GooglePayUtil.isReadyToPayRequest()?.toString())
        paymentsClient?.isReadyToPay(request)?.addOnCompleteListener { task ->
            try {
                result.success(task.getResult(ApiException::class.java))
            } catch (e: ApiException) {
                result.error("GooglePayError", e.message, null)
            }
        } ?: result.error("GooglePayError", "Google Pay is not available", null)
    }

    private fun requestGooglePayPayment(call: MethodCall, result: Result) {
        val params = call.arguments as Map<String, Any>
        val request = PaymentDataRequest.fromJson(
            GooglePayUtil.getPaymentDataRequest(
                params["price"] as String,
                params["currencyCode"] as String,
                params["countryCode"] as String,
                params["merchantName"] as String,
                params["publicId"] as String
            )?.toString()
        )
        lastPaymentResult = result
        activity?.let { act ->
            paymentsClient?.let {
                AutoResolveHelper.resolveTask(it.loadPaymentData(request), act, LOAD_PAYMENT_DATA_REQUEST_CODE)
            }
        } ?: result.error("RequestPayment", "Cannot start Google Pay flow", null)
    }

    private fun onPaymentOk(data: Intent?) {
        if (data == null) {
            lastPaymentResult?.error("RequestPayment", "Intent is null", null)
        } else {
            val paymentData = PaymentData.getFromIntent(data)

            if (paymentData == null) {
                lastPaymentResult?.error("RequestPayment", "Payment data is null", null)
            } else {
                val paymentInfo: String = paymentData.toJson()

                lastPaymentResult?.success(mapOf(
                    "status" to "SUCCESS",
                    "result" to paymentInfo
                ))
            }
        }
        lastPaymentResult = null
    }
    private fun onPaymentCanceled() {
        lastPaymentResult?.success(mapOf(
            "status" to "CANCELED"
        ))

        lastPaymentResult = null
    }

    private fun onPaymentError(data: Intent?) {
        if (data == null) {
            lastPaymentResult?.error("RequestPayment", "Intent is null", null)
        } else {
            val status = AutoResolveHelper.getStatusFromIntent(data)
            if (status == null) {
                lastPaymentResult?.error("RequestPayment", "Status is null", null)
            } else {
                lastPaymentResult?.success(mapOf(
                    "status" to "ERROR",
                    "error_code" to status.statusCode,
                    "error_message" to status.statusMessage,
                    "error_description" to status.toString()
                ))
            }
        }
        lastPaymentResult = null
    }

}
