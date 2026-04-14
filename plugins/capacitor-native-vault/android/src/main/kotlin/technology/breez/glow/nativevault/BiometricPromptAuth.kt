package technology.breez.glow.nativevault

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * `BiometricAuthProviding` implementation backed by `androidx.biometric.BiometricPrompt`.
 *
 * F3 introduces [authenticateWithCrypto], which wraps a pre-initialized
 * `Cipher` in a `BiometricPrompt.CryptoObject` and passes it to
 * `prompt.authenticate`. On successful biometric auth, the callback
 * receives a CryptoObject whose underlying Cipher is authorized for
 * exactly one cryptographic operation (one `doFinal` call). This is
 * how Android binds a biometric auth to a specific Keystore key
 * operation — without it, any cryptographic call against a
 * `setUserAuthenticationRequired(true)` key throws
 * `UserNotAuthenticatedException`.
 *
 * The legacy [authenticate] method (no crypto object) is kept for
 * protocol conformance but is not called by `NativeVaultPlugin` in
 * the F3 world — every store/retrieve goes through
 * [authenticateWithCrypto] instead.
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
        runAuthenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            cancelLabel = cancelLabel,
            cryptoObject = null,
            onSuccess = { _ -> onSuccess() },
            onFailure = onFailure,
        )
    }

    override fun authenticateWithCrypto(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        cancelLabel: String,
        onSuccess: (Cipher) -> Unit,
        onFailure: (NativeVaultErrorCode, String) -> Unit,
    ) {
        runAuthenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            cancelLabel = cancelLabel,
            cryptoObject = BiometricPrompt.CryptoObject(cipher),
            onSuccess = { result ->
                // F3: the callback's CryptoObject wraps the same Cipher
                // we passed in, but it's now authorized for a single
                // crypto operation. We prefer the post-auth Cipher the
                // callback hands us (it's guaranteed to be the
                // authenticated one); fall back to the input Cipher if
                // the callback doesn't populate it (shouldn't happen in
                // practice with a crypto-bound prompt).
                val authenticatedCipher = result?.cryptoObject?.cipher ?: cipher
                onSuccess(authenticatedCipher)
            },
            onFailure = onFailure,
        )
    }

    private fun runAuthenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cancelLabel: String,
        cryptoObject: BiometricPrompt.CryptoObject?,
        onSuccess: (BiometricPrompt.AuthenticationResult?) -> Unit,
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

        // BiometricPrompt internally uses FragmentManager transactions, so
        // both `new BiometricPrompt(...)` and `prompt.authenticate(...)`
        // must be called on the main thread. Capacitor 8 dispatches plugin
        // method calls on the "CapacitorPlugins" worker thread by default,
        // so without this hop we crash with:
        //   FATAL EXCEPTION: CapacitorPlugins
        //     at BiometricPrompt.findOrAddBiometricFragment
        //     at BiometricPrompt.authenticate
        // Hop to the main looper before constructing the prompt.
        activity.runOnUiThread {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess(result)
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

            if (cryptoObject != null) {
                prompt.authenticate(info, cryptoObject)
            } else {
                prompt.authenticate(info)
            }
        }
    }

    companion object {
        // Allow the strongest biometric class available on the device. We
        // do NOT include DEVICE_CREDENTIAL here because falling back to a
        // PIN/pattern on biometric failure would defeat the "biometric
        // unlock" UX promise — the caller should fall through to the
        // legacy passkey or mnemonic restore flow instead.
        //
        // F3 also needs BIOMETRIC_STRONG specifically because the
        // Keystore key is generated with `AUTH_BIOMETRIC_STRONG`; if
        // the allowed authenticators here didn't match, the prompt
        // would still show but the authenticated Cipher wouldn't be
        // usable and `doFinal` would throw.
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
