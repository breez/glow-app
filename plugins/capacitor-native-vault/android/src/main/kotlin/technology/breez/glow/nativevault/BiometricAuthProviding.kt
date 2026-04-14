package technology.breez.glow.nativevault

import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Coarse error codes returned to the JS layer via `PluginCall.reject(code, ...)`.
 * Mirrors the `NativeVaultErrorCode` union in `src/definitions.ts`.
 */
enum class NativeVaultErrorCode(val code: String) {
    USER_CANCELLED("USER_CANCELLED"),
    BIOMETRIC_LOCKOUT("BIOMETRIC_LOCKOUT"),
    BIOMETRIC_NOT_ENROLLED("BIOMETRIC_NOT_ENROLLED"),
    BIOMETRIC_UNAVAILABLE("BIOMETRIC_UNAVAILABLE"),
    KEY_INVALIDATED("KEY_INVALIDATED"),
    NO_STORED_SEED("NO_STORED_SEED"),
    UNKNOWN("UNKNOWN"),
}

/** Detected biometry type, mirrors `NativeVaultBiometryType` in TS. */
enum class NativeVaultBiometryType(val value: String) {
    NONE("none"),
    FINGERPRINT("fingerprint"),
    FACE("face"),
    IRIS("iris"),
}

data class BiometryCapability(
    val available: Boolean,
    val type: NativeVaultBiometryType,
)

/**
 * Internal trait/interface for biometric authentication. The plugin class
 * delegates to a concrete provider (currently `BiometricPromptAuth`, which
 * uses `androidx.biometric.BiometricPrompt`). Splitting the interface keeps
 * the plugin class small and lets future providers slot in without touching
 * the JS-facing layer.
 *
 * F3 exposes [authenticateWithCrypto], the idiomatic Android pattern for
 * binding a biometric auth to a specific Keystore key operation. The
 * legacy [authenticate] method is kept for protocol conformance but is
 * not called by `NativeVaultPlugin` under F3 — every store/retrieve
 * goes through [authenticateWithCrypto] instead so the authenticated
 * `Cipher` is scoped to exactly one cryptographic operation.
 */
interface BiometricAuthProviding {
    /** Inspect the device's biometric capability without prompting. */
    fun checkCapability(): BiometryCapability

    /**
     * Prompt the user for biometric authentication WITHOUT binding to
     * a specific cryptographic operation. Calls [onSuccess] on pass,
     * [onFailure] on cancel/error.
     *
     * Not used by `NativeVaultPlugin` under F3 — kept as an escape
     * hatch for future callers that need a standalone biometric gate.
     *
     * @param activity the host FragmentActivity for `BiometricPrompt`.
     *   Capacitor's bridge activity extends AppCompatActivity which itself
     *   extends FragmentActivity, so the plugin can pass `activity` directly.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cancelLabel: String,
        onSuccess: () -> Unit,
        onFailure: (NativeVaultErrorCode, String) -> Unit,
    )

    /**
     * Prompt the user for biometric authentication AND authorize the
     * given `Cipher` for a single cryptographic operation. The Cipher
     * must already be initialized (`Cipher.init(mode, key)`). On
     * success, [onSuccess] receives a Cipher that is guaranteed to be
     * authorized for exactly one `doFinal` call against the key it
     * was init'd with.
     *
     * This is the only authentication API that works against Keystore
     * keys generated with `setUserAuthenticationRequired(true)` in
     * per-operation mode.
     */
    fun authenticateWithCrypto(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        cancelLabel: String,
        onSuccess: (Cipher) -> Unit,
        onFailure: (NativeVaultErrorCode, String) -> Unit,
    )
}
