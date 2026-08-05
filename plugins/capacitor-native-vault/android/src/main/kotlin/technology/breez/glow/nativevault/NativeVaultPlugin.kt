package technology.breez.glow.nativevault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.fragment.app.FragmentActivity
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import javax.crypto.Cipher

/**
 * Capacitor plugin entry point for `NativeVault`. Exposes five methods to
 * JS (`checkBiometry`, `hasStoredSeed`, `storeSeed`, `retrieveSeed`,
 * `clearSeed`) and delegates each one to a dedicated provider:
 *
 *   - `BiometricAuthProviding` for the biometric prompt
 *   - `SeedVaultProviding` for the actual Keystore + ciphertext store
 *
 * Splitting the work across interface-conforming providers (instead of
 * stuffing everything into the plugin class) keeps each concern testable
 * in isolation.
 *
 * F3 flow shape: `storeSeed` and `retrieveSeed` each orchestrate a
 * three-step dance because the Keystore key is marked
 * `setUserAuthenticationRequired(true)` and cannot be used until a
 * biometric has authorized a specific `Cipher` via
 * `BiometricPrompt.CryptoObject`:
 *
 *   1. `vault.prepareEncryptCipher()` / `vault.prepareDecryptCipher()`
 *      returns an initialized-but-unauthenticated Cipher.
 *   2. `biometric.authenticateWithCrypto(cipher)` shows the biometric
 *      prompt with that Cipher wrapped in a CryptoObject.
 *   3. On success, `vault.finishEncryptAndStore` / `vault.finishDecrypt`
 *      runs the single `doFinal` call against the now-authorized
 *      Cipher and persists / returns the result.
 */
@CapacitorPlugin(name = "NativeVault")
class NativeVaultPlugin : Plugin() {

    // Trait-style providers. Lazy-initialized because they need the host
    // context, which `Plugin.getContext()` only returns after `load()`.
    private val biometric: BiometricAuthProviding by lazy { BiometricPromptAuth(context) }
    private val vault: SeedVaultProviding by lazy { KeystoreSeedVault(context) }

    // MARK: - Biometric capability

    @PluginMethod
    fun checkBiometry(call: PluginCall) {
        val capability = biometric.checkCapability()
        val result = JSObject().apply {
            put("available", capability.available)
            put("biometryType", capability.type.value)
            capability.unavailabilityReason?.let { put("unavailabilityReason", it) }
            capability.unavailabilityCode?.let { put("unavailabilityCode", it.code) }
        }
        call.resolve(result)
    }

    /**
     * iOS-only lockout recovery (`deviceOwnerAuthentication`). Android's
     * `canAuthenticate` never reports lockout (it surfaces at prompt time
     * and BiometricPrompt handles its own device-credential fallback), so
     * the JS layer never calls this here; reject for bridge symmetry.
     */
    @PluginMethod
    fun authenticateDeviceOwner(call: PluginCall) {
        call.reject("Not supported on Android", NativeVaultErrorCode.BIOMETRIC_UNAVAILABLE.code)
    }

    /**
     * Standalone biometric gate: prompts and resolves/rejects without
     * touching the vault. Used by the JS app-lock layer (Security page
     * gating + lock screen), where the seed stays in the device-only
     * tier so a PIN fallback can still start the app.
     */
    @PluginMethod
    fun authenticate(call: PluginCall) {
        val hostActivity = activity as? FragmentActivity
        if (hostActivity == null) {
            call.reject(
                "Plugin host activity is not a FragmentActivity",
                NativeVaultErrorCode.UNKNOWN.code,
            )
            return
        }
        biometric.authenticate(
            activity = hostActivity,
            title = call.getString("reason") ?: "Unlock Glow",
            subtitle = "",
            cancelLabel = "Cancel",
            onSuccess = { call.resolve() },
            onFailure = { code, message -> call.reject(message, code.code) },
        )
    }

    // MARK: - Storage

    @PluginMethod
    fun hasStoredSeed(call: PluginCall) {
        val result = JSObject().apply { put("stored", vault.hasStoredSeed()) }
        call.resolve(result)
    }

    @PluginMethod
    fun storeSeed(call: PluginCall) {
        val seed = call.getString("seed")
        if (seed == null) {
            call.reject("Missing required parameter: seed", NativeVaultErrorCode.UNKNOWN.code)
            return
        }

        val hostActivity = activity as? FragmentActivity
        if (hostActivity == null) {
            call.reject(
                "Plugin host activity is not a FragmentActivity",
                NativeVaultErrorCode.UNKNOWN.code,
            )
            return
        }

        // Fast path: try cipher.init + doFinal. Succeeds when a recent
        // BIOMETRIC_STRONG auth (e.g. the WebAuthn ceremony that
        // chained into onboarding) covers the key's grace window.
        // For time-based-auth keys, init itself can throw
        // UserNotAuthenticatedException; either prepare or finish may
        // surface it.
        when (val prepared = vault.prepareEncryptCipher()) {
            is SeedVaultResult.Ok -> {
                when (val fast = vault.finishEncryptAndStore(seed, prepared.value)) {
                    is SeedVaultResult.Ok -> {
                        call.resolve()
                        return
                    }
                    is SeedVaultResult.Error -> {
                        if (fast.code != NativeVaultErrorCode.USER_NOT_AUTHENTICATED) {
                            call.reject(fast.message, fast.code.code)
                            return
                        }
                        // Fall through to the prompt path below.
                    }
                    is SeedVaultResult.NotFound -> {
                        call.reject(
                            "Seed vault returned NotFound on finishEncryptAndStore",
                            NativeVaultErrorCode.UNKNOWN.code,
                        )
                        return
                    }
                }
            }
            is SeedVaultResult.NotFound -> {
                call.reject(
                    "Seed vault returned NotFound on prepareEncryptCipher",
                    NativeVaultErrorCode.UNKNOWN.code,
                )
                return
            }
            is SeedVaultResult.Error -> {
                if (prepared.code != NativeVaultErrorCode.USER_NOT_AUTHENTICATED) {
                    call.reject(prepared.message, prepared.code.code)
                    return
                }
                // Fall through to the prompt path below.
            }
        }

        // Slow path: prompt without CryptoObject (the canonical pattern
        // for time-based-auth keys), then retry the fast path with a
        // fresh cipher. The successful prompt seeds the Keystore's
        // auth state for the next [KEY_AUTH_GRACE_SECONDS] seconds.
        biometric.authenticate(
            activity = hostActivity,
            title = "Protect Glow",
            subtitle = "Use your biometric credential to enable quick wallet unlock",
            cancelLabel = "Cancel",
            onSuccess = {
                when (val rePrepared = vault.prepareEncryptCipher()) {
                    is SeedVaultResult.Ok -> completeStoreAfterAuth(call, seed, rePrepared.value)
                    is SeedVaultResult.NotFound -> call.reject(
                        "Seed vault returned NotFound on prepareEncryptCipher",
                        NativeVaultErrorCode.UNKNOWN.code,
                    )
                    is SeedVaultResult.Error -> call.reject(rePrepared.message, rePrepared.code.code)
                }
            },
            onFailure = { code, message -> call.reject(message, code.code) },
        )
    }

    @PluginMethod
    fun retrieveSeed(call: PluginCall) {
        // 1. Pre-flight: if no entry exists, fail fast with NO_STORED_SEED
        //    *before* showing a biometric prompt. Otherwise the user would
        //    authenticate just to be told "nothing stored".
        if (!vault.hasStoredSeed()) {
            call.reject(
                "No seed is currently persisted in secure storage.",
                NativeVaultErrorCode.NO_STORED_SEED.code,
            )
            return
        }

        // 2. Resolve the host activity for BiometricPrompt. Capacitor's
        //    bridge activity extends AppCompatActivity, which itself
        //    extends FragmentActivity, so this cast always succeeds in
        //    practice. Defensively bail with UNKNOWN if it doesn't.
        val hostActivity = activity as? FragmentActivity
        if (hostActivity == null) {
            call.reject(
                "Plugin host activity is not a FragmentActivity",
                NativeVaultErrorCode.UNKNOWN.code,
            )
            return
        }

        // Fast path: try cipher.init + doFinal. Either prepare or
        // finish may surface UserNotAuthenticatedException with a
        // time-based-auth key; both routes fall through to the prompt.
        when (val prepared = vault.prepareDecryptCipher()) {
            is SeedVaultResult.Ok -> {
                when (val fast = vault.finishDecrypt(prepared.value)) {
                    is SeedVaultResult.Ok -> {
                        val ret = JSObject().apply { put("seed", fast.value) }
                        call.resolve(ret)
                        return
                    }
                    is SeedVaultResult.Error -> {
                        if (fast.code != NativeVaultErrorCode.USER_NOT_AUTHENTICATED) {
                            call.reject(fast.message, fast.code.code)
                            return
                        }
                        // Fall through to the prompt path below.
                    }
                    is SeedVaultResult.NotFound -> {
                        call.reject(
                            "No seed is currently persisted in secure storage.",
                            NativeVaultErrorCode.NO_STORED_SEED.code,
                        )
                        return
                    }
                }
            }
            is SeedVaultResult.NotFound -> {
                call.reject(
                    "No seed is currently persisted in secure storage.",
                    NativeVaultErrorCode.NO_STORED_SEED.code,
                )
                return
            }
            is SeedVaultResult.Error -> {
                if (prepared.code != NativeVaultErrorCode.USER_NOT_AUTHENTICATED) {
                    call.reject(prepared.message, prepared.code.code)
                    return
                }
                // Fall through to the prompt path below.
            }
        }

        // Slow path: prompt without CryptoObject (the canonical pattern
        // for time-based-auth keys), then retry the fast path with a
        // fresh cipher.
        biometric.authenticate(
            activity = hostActivity,
            title = "Unlock Glow",
            subtitle = "Use your biometric credential to access your wallet",
            cancelLabel = "Cancel",
            onSuccess = {
                when (val rePrepared = vault.prepareDecryptCipher()) {
                    is SeedVaultResult.Ok -> completeRetrieveAfterAuth(call, rePrepared.value)
                    is SeedVaultResult.NotFound -> call.reject(
                        "No seed is currently persisted in secure storage.",
                        NativeVaultErrorCode.NO_STORED_SEED.code,
                    )
                    is SeedVaultResult.Error -> call.reject(rePrepared.message, rePrepared.code.code)
                }
            },
            onFailure = { code, message -> call.reject(message, code.code) },
        )
    }

    @PluginMethod
    fun clearSeed(call: PluginCall) {
        when (val result = vault.clearSeed()) {
            is SeedVaultResult.Ok, SeedVaultResult.NotFound -> call.resolve()
            is SeedVaultResult.Error -> call.reject(result.message, result.code.code)
        }
    }

    // MARK: - Device-only tier (encrypted at rest, no biometric gate)

    @PluginMethod
    fun hasStoredSeedDeviceOnly(call: PluginCall) {
        val result = JSObject().apply { put("stored", vault.hasStoredSeedDeviceOnly()) }
        call.resolve(result)
    }

    @PluginMethod
    fun storeSeedDeviceOnly(call: PluginCall) {
        val seed = call.getString("seed")
        if (seed == null) {
            call.reject("Missing required parameter: seed", NativeVaultErrorCode.UNKNOWN.code)
            return
        }
        when (val result = vault.storeSeedDeviceOnly(seed)) {
            is SeedVaultResult.Ok -> call.resolve()
            is SeedVaultResult.NotFound -> call.reject(
                "Seed vault returned NotFound on storeSeedDeviceOnly",
                NativeVaultErrorCode.UNKNOWN.code,
            )
            is SeedVaultResult.Error -> call.reject(result.message, result.code.code)
        }
    }

    @PluginMethod
    fun retrieveSeedDeviceOnly(call: PluginCall) {
        when (val result = vault.retrieveSeedDeviceOnly()) {
            is SeedVaultResult.Ok -> {
                val ret = JSObject().apply { put("seed", result.value) }
                call.resolve(ret)
            }
            is SeedVaultResult.NotFound -> call.reject(
                "No seed is currently persisted in device-only secure storage.",
                NativeVaultErrorCode.NO_STORED_SEED.code,
            )
            is SeedVaultResult.Error -> call.reject(result.message, result.code.code)
        }
    }

    @PluginMethod
    fun clearSeedDeviceOnly(call: PluginCall) {
        when (val result = vault.clearSeedDeviceOnly()) {
            is SeedVaultResult.Ok, SeedVaultResult.NotFound -> call.resolve()
            is SeedVaultResult.Error -> call.reject(result.message, result.code.code)
        }
    }

    // MARK: - Sensitive clipboard

    /**
     * Copy text the OS should treat as a secret. Android 13+ reads
     * EXTRA_IS_SENSITIVE to keep the value out of the clipboard preview
     * toast, which otherwise renders a copied recovery phrase in the clear
     * on screen. Expiry is the caller's timer: Android has no clipboard TTL.
     */
    @PluginMethod
    fun copySensitive(call: PluginCall) {
        val value = call.getString("value")
        if (value == null) {
            call.reject("Missing required parameter: value", NativeVaultErrorCode.UNKNOWN.code)
            return
        }
        try {
            val clip = ClipData.newPlainText(null, value).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
            }
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(clip)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "copySensitive failed", NativeVaultErrorCode.UNKNOWN.code)
        }
    }

    // MARK: - Internal

    /**
     * F3 step 3 for the store flow: runs after biometric auth has
     * authorized the Cipher. Encrypts the seed into that Cipher and
     * persists the ciphertext + IV.
     */
    private fun completeStoreAfterAuth(call: PluginCall, seed: String, cipher: Cipher) {
        when (val result = vault.finishEncryptAndStore(seed, cipher)) {
            is SeedVaultResult.Ok -> call.resolve()
            is SeedVaultResult.NotFound -> {
                // Non-sensical for a write; treat as unknown.
                call.reject(
                    "Seed vault returned NotFound on finishEncryptAndStore",
                    NativeVaultErrorCode.UNKNOWN.code,
                )
            }
            is SeedVaultResult.Error -> call.reject(result.message, result.code.code)
        }
    }

    /**
     * F3 step 3 for the retrieve flow: runs after biometric auth has
     * authorized the Cipher. Decrypts the stored ciphertext with the
     * now-authenticated Cipher and resolves the outstanding PluginCall.
     */
    private fun completeRetrieveAfterAuth(call: PluginCall, cipher: Cipher) {
        when (val result = vault.finishDecrypt(cipher)) {
            is SeedVaultResult.Ok -> {
                val ret = JSObject().apply { put("seed", result.value) }
                call.resolve(ret)
            }
            is SeedVaultResult.NotFound -> {
                // Race: the entry was cleared between `prepareDecryptCipher`
                // and the biometric callback. Surface as NO_STORED_SEED so
                // the caller falls through to onboarding.
                call.reject(
                    "No seed is currently persisted in secure storage.",
                    NativeVaultErrorCode.NO_STORED_SEED.code,
                )
            }
            is SeedVaultResult.Error -> call.reject(result.message, result.code.code)
        }
    }
}
