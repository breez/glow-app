package technology.breez.glow.nativevault

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle

/**
 * `BiometricAuthProviding` implementation backed by `androidx.biometric.BiometricPrompt`.
 *
 * Prompt only: proves a human is present, does not authorize a specific
 * Keystore operation.
 */
class BiometricPromptAuth(private val context: Context) : BiometricAuthProviding {

    override fun checkCapability(): BiometryCapability {
        val manager = BiometricManager.from(context)
        val canAuth = manager.canAuthenticate(BIOMETRIC_AUTHENTICATORS)

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Keep the raw status visible: lockout, none-enrolled, and
            // hw-unavailable all collapse to `available: false`
            // otherwise, and the Security page hides the biometric row
            // with no way to tell why.
            val statusName = when (canAuth) {
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "NO_HARDWARE"
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "HW_UNAVAILABLE"
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "NONE_ENROLLED"
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "SECURITY_UPDATE_REQUIRED"
                BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "UNSUPPORTED"
                BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "STATUS_UNKNOWN"
                else -> "UNRECOGNIZED"
            }
            val code = when (canAuth) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> NativeVaultErrorCode.BIOMETRIC_NOT_ENROLLED
                else -> NativeVaultErrorCode.BIOMETRIC_UNAVAILABLE
            }
            return BiometryCapability(
                available = false,
                type = NativeVaultBiometryType.NONE,
                unavailabilityReason = "canAuthenticate=$canAuth ($statusName)",
                unavailabilityCode = code,
            )
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
        allowDeviceCredential: Boolean,
        onSuccess: () -> Unit,
        onFailure: (NativeVaultErrorCode, String) -> Unit,
    ) {
        // Pre-flight: surface BIOMETRIC_NOT_ENROLLED / BIOMETRIC_UNAVAILABLE
        // before showing a prompt that would just fail anyway.
        //
        // Skipped when a device credential is acceptable: `canAuthenticate`
        // only accepts DEVICE_CREDENTIAL from API 30 up, so below that the
        // biometric-only status would reject a user who has a PIN but no
        // fingerprint. Let BiometricPrompt report its own errors instead;
        // `mapBiometricError` covers them.
        if (!allowDeviceCredential) {
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
            // Bail out if the activity isn't at least STARTED:
            // BiometricPrompt.authenticate calls FragmentManager.commit
            // which throws IllegalStateException once the host has
            // passed onSaveInstanceState. The exception would surface
            // as an uncaught throwable on the main looper and the
            // authentication callback would never fire, leaving the
            // JS caller stuck on a Promise that never resolves.
            // STARTED (not RESUMED) is the right threshold: the
            // AndroidX BiometricPrompt fragment uses
            // commitAllowingStateLoss internally, so it's safe at
            // STARTED and we avoid rejecting requests that arrive
            // mid-resume transition.
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                onFailure(
                    NativeVaultErrorCode.USER_CANCELLED,
                    "Activity not at least STARTED; biometric prompt deferred",
                )
                return@runOnUiThread
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
                .setConfirmationRequired(false)
                .apply {
                    if (allowDeviceCredential) {
                        // setDeviceCredentialAllowed is deprecated in favour
                        // of setAllowedAuthenticators(... or DEVICE_CREDENTIAL),
                        // but that combination is unsupported on API 28-29 —
                        // the only versions that reach this branch. A negative
                        // button is illegal alongside a credential fallback,
                        // so it is deliberately not set here.
                        @Suppress("DEPRECATION")
                        setDeviceCredentialAllowed(true)
                    } else {
                        setNegativeButtonText(cancelLabel)
                        setAllowedAuthenticators(BIOMETRIC_AUTHENTICATORS)
                    }
                }
                .build()

            // Belt-and-braces: the activity could still transition to
            // STOPPED between the lifecycle check above and the
            // authenticate call below. Catch the resulting
            // IllegalStateException so the JS Promise always resolves.
            try {
                prompt.authenticate(info)
            } catch (e: IllegalStateException) {
                onFailure(
                    NativeVaultErrorCode.USER_CANCELLED,
                    "BiometricPrompt.authenticate failed: ${e.message ?: "IllegalStateException"}",
                )
            } catch (e: Throwable) {
                onFailure(
                    NativeVaultErrorCode.UNKNOWN,
                    "BiometricPrompt.authenticate failed: ${e.message ?: e::class.simpleName}",
                )
            }
        }
    }

    companion object {
        // Strongest biometric class only. DEVICE_CREDENTIAL is excluded
        // because falling back to a PIN on biometric failure would defeat
        // the "biometric unlock" promise; the `allowDeviceCredential`
        // branch above bypasses this constant when a credential is
        // acceptable.
        //
        // BIOMETRIC_STRONG specifically, because a legacy bound key on
        // API 30+ was generated with `AUTH_BIOMETRIC_STRONG`: a weaker
        // authenticator would satisfy the prompt but not the key, and
        // the decrypt right after would throw.
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
