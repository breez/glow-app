package technology.breez.glow.nativevault

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * `BiometricAuthProviding` implementation backed by `androidx.biometric.BiometricPrompt`.
 * Used in F2; F3 will tighten this by passing a `BiometricPrompt.CryptoObject`
 * built from a Keystore key marked `setUserAuthenticationRequired(true)`,
 * which binds the cryptographic operation to a successful biometric.
 */
class BiometricPromptAuth(private val context: Context) : BiometricAuthProviding {

    override fun checkCapability(): BiometryCapability {
        val manager = BiometricManager.from(context)
        val canAuth = manager.canAuthenticate(BIOMETRIC_AUTHENTICATORS)

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            return BiometryCapability(available = false, type = NativeVaultBiometryType.NONE)
        }

        // androidx.biometric does not surface which underlying biometric
        // (fingerprint vs face vs iris) is in use — `BiometricPrompt` picks
        // automatically. The closest signal is `PackageManager` features.
        val pm = context.packageManager
        val type = when {
            pm.hasSystemFeature("android.hardware.fingerprint") -> NativeVaultBiometryType.FINGERPRINT
            pm.hasSystemFeature("android.hardware.biometrics.face") -> NativeVaultBiometryType.FACE
            pm.hasSystemFeature("android.hardware.biometrics.iris") -> NativeVaultBiometryType.IRIS
            else -> NativeVaultBiometryType.FINGERPRINT // sensible default for unknown hardware
        }
        return BiometryCapability(available = true, type = type)
    }

    override fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cancelLabel: String,
        onSuccess: () -> Unit,
        onFailure: (NativeVaultErrorCode, String) -> Unit,
    ) {
        // Pre-flight: surface BIOMETRIC_NOT_ENROLLED / BIOMETRIC_UNAVAILABLE
        // before showing a prompt that would just fail anyway.
        val manager = BiometricManager.from(context)
        when (manager.canAuthenticate(BIOMETRIC_AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                // ok, continue
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
                onFailure(NativeVaultErrorCode.BIOMETRIC_UNAVAILABLE, "Biometric hardware unavailable")
                return
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                onFailure(NativeVaultErrorCode.BIOMETRIC_NOT_ENROLLED, "No biometric credential enrolled")
                return
            }
            else -> {
                onFailure(NativeVaultErrorCode.UNKNOWN, "Unknown biometric capability state")
                return
            }
        }

        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val mapped = mapBiometricError(errorCode)
                    onFailure(mapped, errString.toString())
                }

                // Note: onAuthenticationFailed (recoverable failure — user's
                // fingerprint didn't match) is intentionally NOT bridged.
                // BiometricPrompt continues prompting until either onError
                // or onSuccess fires.
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(cancelLabel)
            .setAllowedAuthenticators(BIOMETRIC_AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info)
    }

    companion object {
        // Allow the strongest biometric class available on the device. We
        // do NOT include DEVICE_CREDENTIAL here because falling back to a
        // PIN/pattern on biometric failure would defeat the "biometric
        // unlock" UX promise — the caller should fall through to the
        // legacy passkey or mnemonic restore flow instead.
        private const val BIOMETRIC_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
}

/**
 * Map a `BiometricPrompt.ERROR_*` code to a `NativeVaultErrorCode`.
 * Centralized so the same mapping covers both the pre-flight
 * `canAuthenticate` path and the async authentication callback.
 */
private fun mapBiometricError(errorCode: Int): NativeVaultErrorCode = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED -> NativeVaultErrorCode.USER_CANCELLED

    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> NativeVaultErrorCode.BIOMETRIC_LOCKOUT

    BiometricPrompt.ERROR_NO_BIOMETRICS -> NativeVaultErrorCode.BIOMETRIC_NOT_ENROLLED

    BiometricPrompt.ERROR_HW_NOT_PRESENT,
    BiometricPrompt.ERROR_HW_UNAVAILABLE,
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
    BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED -> NativeVaultErrorCode.BIOMETRIC_UNAVAILABLE

    else -> NativeVaultErrorCode.UNKNOWN
}
