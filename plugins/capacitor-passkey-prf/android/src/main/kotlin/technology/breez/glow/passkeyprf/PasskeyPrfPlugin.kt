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
                val registered = provider.createPasskey(excludeBytes)
                val credentialIdBase64 = Base64.encodeToString(registered.credentialId, Base64.NO_WRAP)

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
                ret.put(
                    "aaguid",
                    registered.aaguid?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                )
                ret.put("backupEligible", registered.backupEligible)
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

                // Caller-supplied allowCredentialIds pin the assertion
                // to the active cred (post-sign-in derives like
                // listLabels / saveLabel / label switch). Empty /
                // absent → fully discoverable (initial sign-in).
                val callerAllowIds = call.getArray("allowCredentialIds")
                    ?.toList<String>()
                    ?: emptyList()
                val allowBytes = callerAllowIds.mapNotNull { id ->
                    try { Base64.decode(id, Base64.NO_WRAP) }
                    catch (_: IllegalArgumentException) { null }
                }
                val provider = makeProvider(call, rpId, allowCredentialIds = allowBytes)

                // Capture asserted IDs so KnownCredentialsStore stays
                // populated for excludeCredentialIds use on subsequent
                // creates, AND so we can return the credential ID in
                // the response. glow-web reads the returned credId to
                // update per-cred metadata (last-sign-in timestamp,
                // active cred pin) on every successful sign-in.
                val credIdRef = java.util.concurrent.atomic.AtomicReference<String?>()
                provider.onAssertionCredentialId = { credentialId ->
                    val base64 = Base64.encodeToString(credentialId, Base64.NO_WRAP)
                    credIdRef.set(base64)
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
                ret.put("credentialId", credIdRef.get())
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

                // See derivePrfSeed — caller-supplied allowCredentialIds pin
                // active cred; empty / absent is fully discoverable.
                val callerAllowIds = call.getArray("allowCredentialIds")
                    ?.toList<String>()
                    ?: emptyList()
                val allowBytes = callerAllowIds.mapNotNull { id ->
                    try { Base64.decode(id, Base64.NO_WRAP) }
                    catch (_: IllegalArgumentException) { null }
                }
                val provider = makeProvider(call, rpId, allowCredentialIds = allowBytes)
                // See derivePrfSeed: capture for both KnownCredentialsStore
                // population AND the response credId field. Bulk PRF runs
                // a single assertion per pair, but every salt resolves
                // against the same credential, so one credId covers the
                // whole batch.
                val credIdRef = java.util.concurrent.atomic.AtomicReference<String?>()
                provider.onAssertionCredentialId = { credentialId ->
                    val base64 = Base64.encodeToString(credentialId, Base64.NO_WRAP)
                    credIdRef.set(base64)
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
                ret.put("credentialId", credIdRef.get())
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
    fun removeKnownCredentialId(call: PluginCall) {
        val credentialId = call.getString("credentialId")
        if (credentialId.isNullOrEmpty()) {
            call.reject("Missing required parameter: credentialId", "INVALID_ARGUMENT")
            return
        }
        val rpId = call.getString("rpId") ?: DEFAULT_RP_ID
        scope.launch {
            try {
                KnownCredentialsStore.remove(context, credentialId, rpId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "Failed to remove known credential", errorCode(e))
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
        private const val POST_CREATE_GRACE_TOTAL_MS: Long = 800L
    }
}
