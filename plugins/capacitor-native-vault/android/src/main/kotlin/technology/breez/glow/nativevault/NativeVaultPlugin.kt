package technology.breez.glow.nativevault

import androidx.fragment.app.FragmentActivity
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

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
 * in isolation and makes the F3 hardening work surgical: F3 swaps the
 * providers' internal implementation (binding biometric to the Keystore
 * key via `setUserAuthenticationRequired(true)` + CryptoObject) without
 * touching the plugin class or the JS API.
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
        when (val result = vault.storeSeed(seed)) {
            is SeedVaultResult.Ok -> call.resolve()
            is SeedVaultResult.NotFound -> {
                // Non-sensical for a write; treat as unknown.
                call.reject("Seed vault returned NotFound on storeSeed", NativeVaultErrorCode.UNKNOWN.code)
            }
            is SeedVaultResult.Error -> call.reject(result.message, result.code.code)
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

        // 3. Prompt for biometric authentication, then complete the read.
        biometric.authenticate(
            activity = hostActivity,
            title = "Unlock Glow wallet",
            subtitle = "Use your biometric credential to access your wallet",
            cancelLabel = "Cancel",
            onSuccess = { completeRetrieveAfterAuth(call) },
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

    // MARK: - Internal

    /**
     * Second half of `retrieveSeed`: runs after biometric auth has
     * succeeded. Reads the actual Keystore-encrypted blob and
     * resolves / rejects the outstanding `PluginCall`.
     */
    private fun completeRetrieveAfterAuth(call: PluginCall) {
        when (val result = vault.retrieveSeed()) {
            is SeedVaultResult.Ok -> {
                val ret = JSObject().apply { put("seed", result.value) }
                call.resolve(ret)
            }
            is SeedVaultResult.NotFound -> {
                // Race: the entry was cleared between the pre-flight check
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
