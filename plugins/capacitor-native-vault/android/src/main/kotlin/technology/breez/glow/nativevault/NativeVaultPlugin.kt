package technology.breez.glow.nativevault

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
        }
        call.resolve(result)
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

        // F3 step 1: init a Cipher in ENCRYPT_MODE. This may generate
        // the Keystore key on first use.
        val prepared = vault.prepareEncryptCipher()
        when (prepared) {
            is SeedVaultResult.Ok -> {
                // F3 step 2: show biometric prompt with CryptoObject(cipher).
                biometric.authenticateWithCrypto(
                    activity = hostActivity,
                    cipher = prepared.value,
                    title = "Protect your Glow wallet",
                    subtitle = "Use your biometric credential to enable quick wallet unlock",
                    cancelLabel = "Cancel",
                    onSuccess = { authenticatedCipher ->
                        // F3 step 3: run doFinal against the now-authorized Cipher.
                        completeStoreAfterAuth(call, seed, authenticatedCipher)
                    },
                    onFailure = { code, message -> call.reject(message, code.code) },
                )
            }
            is SeedVaultResult.NotFound -> {
                // NotFound has no meaning for a write path; surface as UNKNOWN.
                call.reject(
                    "Seed vault returned NotFound on prepareEncryptCipher",
                    NativeVaultErrorCode.UNKNOWN.code,
                )
            }
            is SeedVaultResult.Error -> call.reject(prepared.message, prepared.code.code)
        }
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

        // 3. F3 step 1: init a Cipher in DECRYPT_MODE against the stored IV.
        val prepared = vault.prepareDecryptCipher()
        when (prepared) {
            is SeedVaultResult.Ok -> {
                // F3 step 2: show biometric prompt with CryptoObject(cipher).
                biometric.authenticateWithCrypto(
                    activity = hostActivity,
                    cipher = prepared.value,
                    title = "Unlock Glow wallet",
                    subtitle = "Use your biometric credential to access your wallet",
                    cancelLabel = "Cancel",
                    onSuccess = { authenticatedCipher ->
                        // F3 step 3: run doFinal against the now-authorized Cipher.
                        completeRetrieveAfterAuth(call, authenticatedCipher)
                    },
                    onFailure = { code, message -> call.reject(message, code.code) },
                )
            }
            is SeedVaultResult.NotFound -> {
                // Race: the entry was cleared between the pre-flight check
                // and `prepareDecryptCipher`. Surface as NO_STORED_SEED.
                call.reject(
                    "No seed is currently persisted in secure storage.",
                    NativeVaultErrorCode.NO_STORED_SEED.code,
                )
            }
            is SeedVaultResult.Error -> call.reject(prepared.message, prepared.code.code)
        }
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
