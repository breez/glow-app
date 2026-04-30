package technology.breez.glow.passkeyprf

import android.app.Activity
import android.util.Base64
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import technology.breez.spark.passkey.PasskeyProvider
import breez_sdk_spark.DomainAssociation
import breez_sdk_spark.PasskeyPrfException

@CapacitorPlugin(name = "PasskeyPrf")
class PasskeyPrfPlugin : Plugin() {

    private val scope = CoroutineScope(Dispatchers.Main)

    @PluginMethod
    fun isPrfAvailable(call: PluginCall) {
        val rpId = call.getString("rpId") ?: DEFAULT_RP_ID
        scope.launch {
            try {
                val provider = makeProvider(call, rpId, allowCredentialIds = emptyList())
                val available = provider.isPrfAvailable()
                val ret = JSObject()
                ret.put("available", available)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject(e.message ?: "Unknown error", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun createPasskey(call: PluginCall) {
        val rpId = call.getString("rpId") ?: DEFAULT_RP_ID

        // Build excludeCredentials by merging two sources, mirroring iOS:
        //   1. Caller-provided base64 IDs (legacy localStorage list).
        //   2. The plugin-owned, Block-Store-synced registry. Survives
        //      app uninstall + device transfer via the Google account.
        // The platform refuses registration if any listed credential is
        // currently registered on the device, regardless of source.
        val callerExcludeIds = call.getArray("excludeCredentialIds")
            ?.toList<String>()
            ?: emptyList()

        scope.launch {
            try {
                val storeIds = KnownCredentialsStore.read(context, rpId)
                val seen = LinkedHashSet<String>()
                val merged = ArrayList<String>()
                for (id in callerExcludeIds) if (seen.add(id)) merged.add(id)
                for (id in storeIds) if (seen.add(id)) merged.add(id)
                val excludeBytes = merged.mapNotNull { id ->
                    try {
                        Base64.decode(id, Base64.NO_WRAP)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }

                // Use a provider with empty allowCredentialIds for create —
                // we only constrain on the assertion side.
                val provider = makeProvider(call, rpId, allowCredentialIds = emptyList())
                val credentialId = provider.createPasskey(excludeBytes)
                val credentialIdBase64 = Base64.encodeToString(credentialId, Base64.NO_WRAP)

                // Persist into the registry so future createPasskey calls
                // (including post-uninstall, post-device-transfer) see this
                // ID in their excludeCredentials list.
                KnownCredentialsStore.add(context, credentialIdBase64, rpId)

                val ret = JSObject()
                ret.put("credentialId", credentialIdBase64)
                call.resolve(ret)
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
        val rpId = call.getString("rpId") ?: DEFAULT_RP_ID

        scope.launch {
            try {
                // Constrain assertion to credential IDs we've registered
                // for this RP (read from the synced registry). Guarantees
                // deterministic seed derivation: the platform only signs
                // with one of our tracked credentials, never picking a
                // sibling that happens to share the RP and produce a
                // different PRF output.
                val allowedBytes = KnownCredentialsStore.read(context, rpId).mapNotNull { id ->
                    try {
                        Base64.decode(id, Base64.NO_WRAP)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }

                val provider = makeProvider(call, rpId, allowCredentialIds = allowedBytes)

                // Capture-on-sign-in: every successful assertion writes
                // the asserted credential ID back into the registry, so
                // pre-tracking installs (e.g. users from older Glow
                // versions that didn't write the registry) auto-migrate
                // after their first sign-in here. After that, future
                // create attempts hit the platform-level "already exists"
                // refusal correctly.
                provider.onAssertionCredentialId = { credentialId ->
                    val base64 = Base64.encodeToString(credentialId, Base64.NO_WRAP)
                    // Fire-and-forget: best-effort. Failures here must
                    // not block the seed return because the assertion
                    // already succeeded.
                    scope.launch {
                        try {
                            KnownCredentialsStore.add(context, base64, rpId)
                        } catch (e: Exception) {
                            // swallow
                        }
                    }
                }

                val seedBytes = provider.derivePrfSeed(salt)
                val ret = JSObject()
                ret.put("seed", Base64.encodeToString(seedBytes, Base64.NO_WRAP))
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject(e.message ?: "PRF seed derivation failed", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun getKnownCredentialIds(call: PluginCall) {
        val rpId = call.getString("rpId") ?: DEFAULT_RP_ID
        scope.launch {
            try {
                val ids = KnownCredentialsStore.read(context, rpId)
                val ret = JSObject()
                val arr = JSArray()
                for (id in ids) arr.put(id)
                ret.put("credentialIds", arr)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject(e.message ?: "Failed to read known credentials", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun clearKnownCredentialIds(call: PluginCall) {
        val rpId = call.getString("rpId") ?: DEFAULT_RP_ID
        scope.launch {
            try {
                KnownCredentialsStore.clear(context, rpId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "Failed to clear known credentials", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun checkDomainAssociation(call: PluginCall) {
        val rpId = call.getString("rpId") ?: DEFAULT_RP_ID
        scope.launch {
            try {
                val provider = makeProvider(call, rpId, allowCredentialIds = emptyList())
                val result = provider.checkDomainAssociation()
                call.resolve(domainAssociationToJson(result))
            } catch (e: Exception) {
                // The SDK's checkDomainAssociation is documented to never
                // throw — verification-level failures surface as Skipped.
                // Catch defensively in case the SDK contract changes.
                call.reject(e.message ?: "Domain association check failed", errorCode(e))
            }
        }
    }

    /**
     * Serialize [DomainAssociation] into the JSObject tagged-union shape
     * the Capacitor bridge expects. Mirrors the TypeScript
     * `DomainAssociation` type one-to-one so callers on both platforms
     * see the same payload.
     */
    private fun domainAssociationToJson(result: DomainAssociation): JSObject {
        val ret = JSObject()
        when (result) {
            is DomainAssociation.Associated -> {
                ret.put("kind", "Associated")
            }
            is DomainAssociation.NotAssociated -> {
                ret.put("kind", "NotAssociated")
                ret.put("source", result.source)
                ret.put("reason", result.reason)
            }
            is DomainAssociation.Skipped -> {
                ret.put("kind", "Skipped")
                ret.put("reason", result.reason)
            }
        }
        return ret
    }

    private fun makeProvider(
        call: PluginCall,
        rpId: String,
        allowCredentialIds: List<ByteArray>,
    ): PasskeyProvider {
        return PasskeyProvider(
            activityProvider = { activity as Activity },
            rpId = rpId,
            rpName = call.getString("rpName") ?: "Glow",
            userName = call.getString("userName"),
            userDisplayName = call.getString("userDisplayName"),
            autoRegister = call.getBoolean("autoRegister") ?: true,
            allowCredentialIds = allowCredentialIds,
        )
    }

    private fun errorCode(e: Exception): String = when (e) {
        is PasskeyPrfException.PrfNotSupported -> "PRF_NOT_SUPPORTED"
        is PasskeyPrfException.UserCancelled -> "USER_CANCELLED"
        is PasskeyPrfException.CredentialNotFound -> "CREDENTIAL_NOT_FOUND"
        is PasskeyPrfException.AuthenticationFailed -> "AUTHENTICATION_FAILED"
        is PasskeyPrfException.PrfEvaluationFailed -> "PRF_EVALUATION_FAILED"
        is PasskeyPrfException.Configuration -> "CONFIGURATION_ERROR"
        is PasskeyPrfException.CredentialAlreadyExists -> "CREDENTIAL_ALREADY_EXISTS"
        is PasskeyPrfException.Generic -> "GENERIC_ERROR"
        else -> "UNKNOWN_ERROR"
    }

    companion object {
        private const val DEFAULT_RP_ID = "keys.breez.technology"
    }
}
