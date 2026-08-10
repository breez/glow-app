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
 * Capacitor plugin entry point for `NativeVault`. Delegates to a
 * dedicated provider per concern:
 *
 *   - `BiometricAuthProviding` for the biometric prompt
 *   - `SeedVaultProviding` for the actual Keystore + ciphertext store
 *
 * `*SeedDeviceOnly` is the shipped tier. `hasStoredSeed` /
 * `retrieveSeed` / `clearSeed` are the retired biometric-bound tier,
 * read-only; see `SeedVaultProviding`.
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
    fun retrieveSeed(call: PluginCall) {
        // Fail fast before prompting: authenticating only to be told
        // "nothing stored" is worse than no prompt at all.
        if (!vault.hasStoredSeed()) {
            call.reject(
                "No seed is currently persisted in secure storage.",
                NativeVaultErrorCode.NO_STORED_SEED.code,
            )
            return
        }

        // Capacitor's bridge activity extends AppCompatActivity, which
        // extends FragmentActivity, so this cast succeeds in practice.
        val hostActivity = activity as? FragmentActivity
        if (hostActivity == null) {
            call.reject(
                "Plugin host activity is not a FragmentActivity",
                NativeVaultErrorCode.UNKNOWN.code,
            )
            return
        }

        // Prompt before every decrypt, with no attempt-first fast path:
        // this tier's key authorizes on a time basis, so it will not
        // require a prompt itself.
        //
        // Below API 30 that key accepts any device unlock, biometric or
        // not, so accept a device credential too. A biometric-only
        // prompt would leave an Android 9/10 user who has a PIN but no
        // fingerprint unable to read their seed.
        biometric.authenticate(
            activity = hostActivity,
            title = "Unlock Glow",
            subtitle = "Confirm it's you to continue",
            cancelLabel = "Cancel",
            allowDeviceCredential = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
            onSuccess = {
                when (val prepared = vault.prepareDecryptCipher()) {
                    is SeedVaultResult.Ok -> completeRetrieveAfterAuth(call, prepared.value)
                    is SeedVaultResult.NotFound -> call.reject(
                        "No seed is currently persisted in secure storage.",
                        NativeVaultErrorCode.NO_STORED_SEED.code,
                    )
                    is SeedVaultResult.Error -> call.reject(prepared.message, prepared.code.code)
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
     * EXTRA_IS_SENSITIVE to keep the value out of the clipboard preview.
     * Expiry is the caller's timer: Android has no clipboard TTL.
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
     * Runs after the user has just authenticated. Decrypts the stored
     * ciphertext and resolves the outstanding PluginCall.
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
