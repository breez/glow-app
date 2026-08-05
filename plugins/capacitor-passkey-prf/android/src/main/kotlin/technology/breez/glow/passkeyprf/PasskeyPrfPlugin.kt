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
import breez_sdk_spark.ConnectWithPasskeyRequest
import breez_sdk_spark.PasskeyAvailability
import breez_sdk_spark.PasskeyClient
import breez_sdk_spark.PasskeyConfig
import breez_sdk_spark.PasskeyCredential
import breez_sdk_spark.PasskeyException
import breez_sdk_spark.PasskeyProviderOptions
import breez_sdk_spark.PrfProviderException
import breez_sdk_spark.RegisterRequest
import breez_sdk_spark.Seed
import breez_sdk_spark.SignInRequest
import breez_sdk_spark.Wallet

/**
 * Capacitor bridge over `breez_sdk_spark.PasskeyClient`.
 *
 * Owns a single `PasskeyClient` per session (built in `initialize`)
 * plus a process-lifetime `BlockStoreCredentialRegistry`.
 */
@CapacitorPlugin(name = "Passkey")
class PasskeyPlugin : Plugin() {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val registry: BlockStoreCredentialRegistry by lazy {
        BlockStoreCredentialRegistry(context.applicationContext)
    }

    @Volatile
    private var client: PasskeyClient? = null

    // RP the client was initialized with; needed to key the registry.
    @Volatile
    private var rpId: String? = null

    private companion object {
        /**
         * RP IDs this app may run a ceremony against. Must stay in step with
         * the domains in assetlinks.json (and iOS's associated-domains
         * entitlement): a domain here that the app is not associated with
         * dead-ends the OS sheet.
         */
        val ALLOWED_RP_IDS = setOf("keys.breez.technology")
    }

    @PluginMethod
    fun initialize(call: PluginCall) {
        val rpId = call.getString("rpId")
        val rpName = call.getString("rpName")
        if (rpId == null || rpName == null) {
            call.reject("rpId and rpName are required", "INVALID_ARGUMENT")
            return
        }
        // The RP is ours to decide, not the WebView's. Credential Manager
        // would reject a domain this app is not associated with anyway, but
        // only after showing the user a sheet naming that domain, so a
        // compromised WebView could still run a convincing phishing prompt.
        if (rpId !in ALLOWED_RP_IDS) {
            call.reject("rpId '$rpId' is not an associated domain of this app", "INVALID_ARGUMENT")
            return
        }
        this.rpId = rpId
        val provider = PasskeyProvider(
            activityProvider = { activity as Activity },
            options = PasskeyProviderOptions(
                rpId = rpId,
                rpName = rpName,
                userName = call.getString("userName"),
                userDisplayName = call.getString("userDisplayName"),
            ),
        )
        val config = call.getString("defaultLabel")?.let { PasskeyConfig(defaultLabel = it) }
        client = PasskeyClient(provider, call.getString("breezApiKey"), config)
        call.resolve()
    }

    @PluginMethod
    fun checkAvailability(call: PluginCall) {
        val c = client ?: run {
            call.resolve(JSObject().put("type", "prfUnsupported"))
            return
        }
        scope.launch {
            try {
                call.resolve(encodeAvailability(c.checkAvailability()))
            } catch (e: Exception) {
                call.reject(e.message ?: "checkAvailability failed", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun register(call: PluginCall) {
        val c = requireClient(call) ?: return
        val request = RegisterRequest(
            label = call.getString("label"),
            excludeCredentials = parseB64Array(call, "excludeCredentials"),
        )
        scope.launch {
            try {
                val response = c.register(request)
                recordCredential(response.credential)
                call.resolve(JSObject().apply {
                    put("wallet", encodeWallet(response.wallet))
                    put("credential", response.credential?.let { encodeCredential(it) })
                })
            } catch (e: Exception) {
                call.reject(e.message ?: "register failed", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun signIn(call: PluginCall) {
        val c = requireClient(call) ?: return
        val request = SignInRequest(
            label = call.getString("label"),
            allowCredentials = parseB64Array(call, "allowCredentials"),
            preferImmediatelyAvailableCredentials = call.getBoolean("preferImmediatelyAvailableCredentials"),
        )
        scope.launch {
            try {
                val response = c.signIn(request)
                recordCredential(response.credential)
                call.resolve(JSObject().apply {
                    put("wallet", encodeWallet(response.wallet))
                    put("labels", JSArray(response.labels))
                    put("credentialId", response.credential?.credentialId?.let { encodeB64(it) })
                })
            } catch (e: Exception) {
                call.reject(e.message ?: "signIn failed", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun connectWithPasskey(call: PluginCall) {
        val c = requireClient(call) ?: return
        val request = ConnectWithPasskeyRequest(
            label = call.getString("label"),
            allowCredentials = parseB64Array(call, "allowCredentials"),
            excludeCredentials = parseB64Array(call, "excludeCredentials"),
        )
        scope.launch {
            try {
                val response = c.connectWithPasskey(request)
                recordCredential(response.credential)
                call.resolve(JSObject().apply {
                    put("wallet", encodeWallet(response.wallet))
                    // Discovery labels for the returning multi-wallet case;
                    // empty on the register path (glow-web#309).
                    put("labels", JSArray(response.labels))
                    put("registeredCredential", response.credential?.let { encodeCredential(it) })
                })
            } catch (e: Exception) {
                call.reject(e.message ?: "connectWithPasskey failed", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun listLabels(call: PluginCall) {
        val c = requireClient(call) ?: return
        scope.launch {
            try {
                call.resolve(JSObject().put("labels", JSArray(c.labels().list())))
            } catch (e: Exception) {
                call.reject(e.message ?: "listLabels failed", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun storeLabel(call: PluginCall) {
        val label = call.getString("label") ?: run {
            call.reject("Missing required parameter: label", "INVALID_ARGUMENT")
            return
        }
        val c = requireClient(call) ?: return
        scope.launch {
            try {
                c.labels().store(label)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "storeLabel failed", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun getKnownCredentialIds(call: PluginCall) {
        val rp = rpId ?: run {
            call.resolve(JSObject().put("credentialIds", JSArray()))
            return
        }
        scope.launch {
            try {
                val ids = registry.read(rp)
                val arr = JSArray().apply { ids.forEach { put(encodeB64(it)) } }
                call.resolve(JSObject().put("credentialIds", arr))
            } catch (e: Exception) {
                call.reject(e.message ?: "getKnownCredentialIds failed", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun removeKnownCredentialId(call: PluginCall) {
        val raw = call.getString("credentialId")
        if (raw.isNullOrEmpty()) {
            call.reject("Missing required parameter: credentialId", "INVALID_ARGUMENT")
            return
        }
        val decoded = decodeB64Either(raw)
        if (decoded == null) {
            call.reject("credentialId is not valid base64", "INVALID_ARGUMENT")
            return
        }
        val rp = rpId ?: run {
            call.resolve()
            return
        }
        scope.launch {
            try {
                registry.remove(rp, decoded)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "removeKnownCredentialId failed", errorCode(e))
            }
        }
    }

    @PluginMethod
    fun clearKnownCredentialIds(call: PluginCall) {
        val rp = rpId ?: run {
            call.resolve()
            return
        }
        scope.launch {
            try {
                registry.clear(rp)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "clearKnownCredentialIds failed", errorCode(e))
            }
        }
    }

    /**
     * Best-effort: record a credential ID in the synced store.
     */
    private suspend fun recordCredential(cred: PasskeyCredential?) {
        val rp = rpId ?: return
        if (cred == null) return
        try {
            registry.add(rp, cred.credentialId)
        } catch (e: Exception) {
            // Best-effort; a registry write failure must not fail the ceremony.
        }
    }

    private fun requireClient(call: PluginCall): PasskeyClient? {
        val c = client
        if (c == null) call.reject("Plugin not initialized", "NOT_INITIALIZED")
        return c
    }

    private fun parseB64Array(call: PluginCall, key: String): List<ByteArray> {
        val raw = call.getArray(key)?.toList<String>() ?: return emptyList()
        return raw.mapNotNull { decodeB64Either(it) }
    }

    private fun encodeB64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeB64Either(input: String): ByteArray? {
        listOf(
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.NO_WRAP,
            Base64.NO_WRAP or Base64.NO_PADDING,
        ).forEach { flags ->
            try { return Base64.decode(input, flags) }
            catch (_: IllegalArgumentException) { /* try next */ }
        }
        return null
    }

    private fun encodeWallet(wallet: Wallet): JSObject =
        JSObject().apply {
            put("seed", encodeSeed(wallet.seed))
            put("label", wallet.label)
        }

    private fun encodeSeed(seed: Seed): JSObject = when (seed) {
        is Seed.Mnemonic -> JSObject().apply {
            put("type", "mnemonic")
            put("mnemonic", seed.mnemonic)
            put("passphrase", seed.passphrase)
        }
        is Seed.Entropy -> JSObject().apply {
            put("type", "entropy")
            put("entropy", encodeB64(seed.v1))
        }
    }

    private fun encodeCredential(cred: PasskeyCredential): JSObject =
        JSObject().apply {
            put("credentialId", encodeB64(cred.credentialId))
            put("userId", cred.userId?.let { encodeB64(it) })
            put("aaguid", cred.aaguid?.let { encodeB64(it) })
            put("backupEligible", cred.backupEligible)
        }

    private fun encodeAvailability(a: PasskeyAvailability): JSObject = when (a) {
        is PasskeyAvailability.Available ->
            JSObject().put("type", "available")
        is PasskeyAvailability.PrfUnsupported ->
            JSObject().put("type", "prfUnsupported")
        is PasskeyAvailability.NotAssociated ->
            JSObject().put("type", "notAssociated").put("source", a.source).put("reason", a.reason)
        is PasskeyAvailability.Skipped ->
            JSObject().put("type", "skipped").put("reason", a.reason)
    }

    private fun errorCode(e: Exception): String = when (e) {
        is PasskeyException -> passkeyErrorCode(e)
        is PrfProviderException -> prfErrorCode(e)
        else -> "UNKNOWN_ERROR"
    }

    private fun passkeyErrorCode(e: PasskeyException): String = when (e) {
        // The SDK wraps every PrfProviderException in here, and this arm is
        // matched before the bare `is PrfProviderException` one in errorCode,
        // so unwrap: collapsing to GENERIC_ERROR loses the no-credential,
        // cancelled, and already-exists cases the JS routing depends on.
        is PasskeyException.Prf -> prfErrorCode(e.v1)
        is PasskeyException.RelayConnectionFailed -> "RELAY_CONNECTION_FAILED"
        is PasskeyException.NostrWriteFailed -> "NOSTR_WRITE_FAILED"
        is PasskeyException.NostrReadFailed -> "NOSTR_READ_FAILED"
        is PasskeyException.KeyDerivationException -> "KEY_DERIVATION_ERROR"
        is PasskeyException.InvalidPrfOutput -> "INVALID_PRF_OUTPUT"
        is PasskeyException.MnemonicException -> "MNEMONIC_ERROR"
        is PasskeyException.InvalidSalt -> "INVALID_SALT"
        // A passkey exists on the device even though the ceremony failed.
        // Its own code so the web layer signs in with it rather than
        // offering a create that would strand it.
        is PasskeyException.CreatedButNotDerived -> "CREATED_BUT_NOT_DERIVED"
        is PasskeyException.Generic -> "GENERIC_ERROR"
    }

    private fun prfErrorCode(e: PrfProviderException): String = when (e) {
        is PrfProviderException.PrfNotSupported -> "PRF_NOT_SUPPORTED"
        is PrfProviderException.UserCancelled -> "USER_CANCELLED"
        is PrfProviderException.UserTimedOut -> "USER_TIMED_OUT"
        is PrfProviderException.CredentialNotFound -> "CREDENTIAL_NOT_FOUND"
        is PrfProviderException.AuthenticationFailed -> "AUTHENTICATION_FAILED"
        is PrfProviderException.PrfEvaluationFailed -> "PRF_EVALUATION_FAILED"
        is PrfProviderException.Configuration -> "CONFIGURATION_ERROR"
        is PrfProviderException.CredentialAlreadyExists -> "CREDENTIAL_ALREADY_EXISTS"
        is PrfProviderException.Generic -> "GENERIC_ERROR"
    }
}
