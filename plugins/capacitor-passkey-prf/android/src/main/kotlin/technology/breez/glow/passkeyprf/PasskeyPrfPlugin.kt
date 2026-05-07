package technology.breez.glow.passkeyprf

import android.app.Activity
import android.os.Build
import android.util.Base64
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import technology.breez.spark.passkey.PasskeyProvider
import breez_sdk_spark.DomainAssociation
import breez_sdk_spark.PasskeyPrfException

@CapacitorPlugin(name = "PasskeyPrf")
class PasskeyPrfPlugin : Plugin() {

    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Deadline for the GPM indexing grace period that follows
     * registration on Android <14. The next assertion sleeps until
     * this passes, then clears it.
     */
    @Volatile
    private var postCreateGraceDeadlineMs: Long = 0L

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

        // Merge caller-supplied IDs with the synced registry so the
        // platform's duplicate refusal triggers on either source.
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

                val provider = makeProvider(call, rpId, allowCredentialIds = emptyList())
                val credentialId = provider.createPasskey(excludeBytes)
                val credentialIdBase64 = Base64.encodeToString(credentialId, Base64.NO_WRAP)

                // Sync local write so the next assertion sees the new ID.
                try {
                    KnownCredentialsStore.addLocal(context, credentialIdBase64, rpId)
                } catch (_: Exception) {
                }

                scope.launch {
                    try {
                        KnownCredentialsStore.syncBlockStore(context, rpId)
                    } catch (_: Exception) {
                    }
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    postCreateGraceDeadlineMs =
                        System.currentTimeMillis() + POST_CREATE_GRACE_TOTAL_MS
                }

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
                awaitPostCreateGracePeriod()

                val allowedBytes = KnownCredentialsStore.read(context, rpId).mapNotNull { id ->
                    try {
                        Base64.decode(id, Base64.NO_WRAP)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }

                val provider = makeProvider(call, rpId, allowCredentialIds = allowedBytes)

                // Capture asserted IDs so installs that predate the
                // registry auto-migrate on first sign-in.
                provider.onAssertionCredentialId = { credentialId ->
                    val base64 = Base64.encodeToString(credentialId, Base64.NO_WRAP)
                    scope.launch {
                        try {
                            KnownCredentialsStore.add(context, base64, rpId)
                        } catch (_: Exception) {
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
    fun derivePrfSeeds(call: PluginCall) {
        val saltsArr = call.getArray("salts")
        if (saltsArr == null || saltsArr.length() == 0) {
            call.reject("Missing or empty required parameter: salts", "INVALID_ARGUMENT")
            return
        }
        val salts: List<String> = saltsArr.toList<String>()
        val rpId = call.getString("rpId") ?: DEFAULT_RP_ID

        scope.launch {
            try {
                awaitPostCreateGracePeriod()

                val allowedBytes = KnownCredentialsStore.read(context, rpId).mapNotNull { id ->
                    try {
                        Base64.decode(id, Base64.NO_WRAP)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }

                val provider = makeProvider(call, rpId, allowCredentialIds = allowedBytes)
                provider.onAssertionCredentialId = { credentialId ->
                    val base64 = Base64.encodeToString(credentialId, Base64.NO_WRAP)
                    scope.launch {
                        try {
                            KnownCredentialsStore.add(context, base64, rpId)
                        } catch (_: Exception) {
                        }
                    }
                }

                val seedBytesList = provider.derivePrfSeeds(salts)
                val seedsBase64 = JSArray()
                for (bytes in seedBytesList) {
                    seedsBase64.put(Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
                val ret = JSObject()
                ret.put("seeds", seedsBase64)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject(e.message ?: "Bulk PRF seed derivation failed", errorCode(e))
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
                // SDK contract says it never throws (failures are .Skipped),
                // but catch defensively.
                call.reject(e.message ?: "Domain association check failed", errorCode(e))
            }
        }
    }

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

    private suspend fun awaitPostCreateGracePeriod() {
        val deadline = postCreateGraceDeadlineMs
        if (deadline == 0L) return
        val remaining = deadline - System.currentTimeMillis()
        postCreateGraceDeadlineMs = 0L
        if (remaining > 0) delay(remaining)
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

        /** GPM indexing window after registration on Android <14. */
        private const val POST_CREATE_GRACE_TOTAL_MS: Long = 600L
    }
}
