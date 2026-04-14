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
 */
interface BiometricAuthProviding {
    /** Inspect the device's biometric capability without prompting. */
    fun checkCapability(): BiometryCapability

    /**
     * Prompt the user for biometric authentication. Calls [onSuccess] on
     * pass, [onFailure] on cancel/error.
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
}
