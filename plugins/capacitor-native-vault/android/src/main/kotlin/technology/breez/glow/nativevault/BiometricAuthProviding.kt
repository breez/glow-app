package technology.breez.glow.nativevault

import androidx.fragment.app.FragmentActivity

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
    /**
     * The Keystore key's auth window has lapsed. Reaching JS means a
     * decrypt failed even after a successful prompt; the JS side folds
     * this into UNKNOWN, which is recoverable and retryable.
     */
    USER_NOT_AUTHENTICATED("USER_NOT_AUTHENTICATED"),
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
    /** Why [available] is false (raw `canAuthenticate` status), null when
     *  available. Diagnostic only — surfaced to the JS logger so "the
     *  biometric row vanished" is explainable from Share Logs. */
    val unavailabilityReason: String? = null,
    /** Structured counterpart of [unavailabilityReason]: the mapped
     *  [NativeVaultErrorCode], so JS can branch without string parsing. */
    val unavailabilityCode: NativeVaultErrorCode? = null,
)

/**
 * Internal trait/interface for biometric authentication. The plugin class
 * delegates to a concrete provider (currently `BiometricPromptAuth`, which
 * uses `androidx.biometric.BiometricPrompt`). Splitting the interface keeps
 * the plugin class small and lets future providers slot in without touching
 * the JS-facing layer.
 *
 * Prompt only. Binding a prompt to a specific Keystore operation would
 * need an auth-per-use key, and no shipped tier has one.
 */
interface BiometricAuthProviding {
    /** Inspect the device's biometric capability without prompting. */
    fun checkCapability(): BiometryCapability

    /**
     * Prompt the user for authentication. Calls [onSuccess] on pass,
     * [onFailure] on cancel/error.
     *
     * @param activity the host FragmentActivity for `BiometricPrompt`.
     *   Capacitor's bridge activity extends AppCompatActivity which itself
     *   extends FragmentActivity, so the plugin can pass `activity` directly.
     * @param allowDeviceCredential accept the device PIN / pattern /
     *   password as well as a biometric. Suppresses the negative
     *   button, which the platform forbids alongside a credential
     *   fallback.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cancelLabel: String,
        allowDeviceCredential: Boolean = false,
        onSuccess: () -> Unit,
        onFailure: (NativeVaultErrorCode, String) -> Unit,
    )
}
