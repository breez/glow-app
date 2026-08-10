package technology.breez.glow.nativevault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/**
 * Installs that predate app lock still hold an entry in the
 * biometric-bound tier, and moving it into the device-only tier is the
 * only way they reach it. Plants such an entry (same alias, prefs keys
 * and key parameters a pre-app-lock build used) and checks
 * `KeystoreSeedVault` can still read and clear it.
 *
 * Runs on API < 30 only: there the key accepts any device unlock, so the
 * read is reachable without driving a fingerprint sensor. The code path
 * is the same on API 30+, which needs an enrolled biometric.
 *
 * Requires a secure lock screen and a recent credential confirmation,
 * since the key authorizes on a time basis and the read below does not
 * prompt on its own:
 *
 *     adb shell locksettings set-pin 1234
 *     adb shell locksettings verify --old 1234   # immediately before
 */
@RunWith(AndroidJUnit4::class)
class LegacyBoundTierDrainTest {

    private lateinit var context: Context
    private lateinit var vault: KeystoreSeedVault

    @Before
    fun setUp() {
        assumeTrue(
            "Legacy bound keys below API 30 are the automatable case",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
        )
        context = InstrumentationRegistry.getInstrumentation().targetContext
        vault = KeystoreSeedVault(context)
        wipe()
    }

    @After
    fun tearDown() {
        if (this::context.isInitialized) wipe()
    }

    @Test
    fun legacyEntryStillDrains() {
        plantLegacyEntry(SEED)

        assertTrue("a planted legacy entry should be visible", vault.hasStoredSeed())

        val cipher = vault.prepareDecryptCipher()
        assertTrue(
            "prepareDecryptCipher failed on a legacy key: $cipher",
            cipher is SeedVaultResult.Ok,
        )

        val drained = vault.finishDecrypt((cipher as SeedVaultResult.Ok).value)
        assertTrue("finishDecrypt failed on a legacy key: $drained", drained is SeedVaultResult.Ok)
        assertEquals(SEED, (drained as SeedVaultResult.Ok).value)
    }

    @Test
    fun clearSeedRetiresTheTierForGood() {
        plantLegacyEntry(SEED)

        assertTrue(vault.clearSeed() is SeedVaultResult.Ok)

        assertFalse("the drained entry should be gone", vault.hasStoredSeed())
        assertFalse(
            "the legacy key should be deleted, not left behind for a later decrypt",
            androidKeyStore().containsAlias(KEY_ALIAS),
        )
        // Nothing in the plugin can mint a replacement: there is no
        // store path for this tier. A drained install is done with it.
        assertTrue(vault.prepareDecryptCipher() is SeedVaultResult.NotFound)
    }

    /**
     * Recreate what a pre-app-lock build left on disk: an AES-GCM key
     * with the old tier's time-based authorization, plus the ciphertext
     * and IV under the prefs keys `KeystoreSeedVault` reads.
     */
    private fun plantLegacyEntry(seed: String) {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        @Suppress("DEPRECATION")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .setUserAuthenticationValidityDurationSeconds(30)
                .build(),
        )
        val key = keyGenerator.generateKey()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(seed.toByteArray(Charsets.UTF_8))

        val committed = context
            .getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(PREFS_KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        assertTrue("failed to plant the legacy prefs entry", committed)
    }

    private fun wipe() {
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        androidKeyStore().takeIf { it.containsAlias(KEY_ALIAS) }?.deleteEntry(KEY_ALIAS)
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val SEED = """{"type":"mnemonic","mnemonic":"abandon abandon about"}"""
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "technology.breez.glow.native-vault.seed-key"
        const val SHARED_PREFERENCES_NAME = "technology.breez.glow.NativeVault"
        const val PREFS_KEY_IV = "wallet_seed_iv"
        const val PREFS_KEY_CIPHERTEXT = "wallet_seed_ciphertext"
    }
}
