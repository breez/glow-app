package technology.breez.glow.passkeyprf

import android.app.Activity
import android.util.Base64
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import technology.breez.spark.passkey.CredentialManagerPrfProvider
import breez_sdk_spark.PasskeyPrfException

@CapacitorPlugin(name = "PasskeyPrf")
class PasskeyPrfPlugin : Plugin() {

    private val scope = CoroutineScope(Dispatchers.Main)

    @PluginMethod
    fun isPrfAvailable(call: PluginCall) {
        val provider = makeProvider(call)
        scope.launch {
            try {
                val available = provider.isPrfAvailable()
                val ret = com.getcapacitor.JSObject()
                ret.put("available", available)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject(e.message ?: "Unknown error", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun createPasskey(call: PluginCall) {
        val provider = makeProvider(call)
        scope.launch {
            try {
                provider.createPasskey()
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "Passkey creation failed", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun derivePrfSeed(call: PluginCall) {
        val salt = call.getString("salt")
        if (salt == null) {
            call.reject("Missing required parameter: salt", "INVALID_ARGUMENT")
            return
        }

        val provider = makeProvider(call)
        scope.launch {
            try {
                val seedBytes = provider.derivePrfSeed(salt)
                val ret = com.getcapacitor.JSObject()
                ret.put("seed", Base64.encodeToString(seedBytes, Base64.NO_WRAP))
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject(e.message ?: "PRF seed derivation failed", errorCode(e))
            }
        }
    }

    private fun makeProvider(call: PluginCall): CredentialManagerPrfProvider {
        return CredentialManagerPrfProvider(
            activityProvider = { activity as Activity },
            rpId = call.getString("rpId") ?: "keys.breez.technology",
            rpName = call.getString("rpName") ?: "Glow",
            userName = call.getString("userName"),
            userDisplayName = call.getString("userDisplayName"),
        )
    }

    private fun errorCode(e: Exception): String = when (e) {
        is PasskeyPrfException.PrfNotSupported -> "PRF_NOT_SUPPORTED"
        is PasskeyPrfException.UserCancelled -> "USER_CANCELLED"
        is PasskeyPrfException.CredentialNotFound -> "CREDENTIAL_NOT_FOUND"
        is PasskeyPrfException.AuthenticationFailed -> "AUTHENTICATION_FAILED"
        is PasskeyPrfException.PrfEvaluationFailed -> "PRF_EVALUATION_FAILED"
        is PasskeyPrfException.Generic -> "GENERIC_ERROR"
        else -> "UNKNOWN_ERROR"
    }
}
