package technology.breez.glow.passkeyprf

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.BlockstoreClient
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import kotlinx.coroutines.tasks.await
import org.json.JSONArray

/**
 * Plugin-side store of known passkey credential IDs, providing
 * cross-device sync via Google Block Store with a local
 * `SharedPreferences` fallback.
 *
 * Block Store is the closest Android equivalent to iCloud Keychain:
 * Google-account synced, restored automatically on reinstall and across
 * devices. The SharedPreferences fallback covers offline / not-signed-in
 * cases. Reads union both sources, writes go to both.
 *
 * Storage keys are kept stable (`breez.glow.knownCredentials.*`) so any
 * already-persisted entries remain readable.
 */
public class BlockStoreCredentialRegistry(
    private val context: Context,
) {
    private companion object {
        private const val TAG = "BlockStoreCredentialRegistry"
        private const val BLOCKSTORE_KEY_PREFIX = "breez.glow.knownCredentials."
        private const val PREFS_FILE = "breez.glow.knownCredentials"
        private const val PREFS_KEY_PREFIX = "credentials."

        private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    }

    private fun encode(b: ByteArray): String = Base64.encodeToString(b, B64_FLAGS)
    private fun decode(s: String): ByteArray = Base64.decode(s, B64_FLAGS)

    suspend fun read(rpId: String): List<ByteArray> {
        val seen = LinkedHashSet<String>()
        readFromBlockStore(rpId)?.forEach { seen.add(it) }
        readFromPrefs(rpId).forEach { seen.add(it) }
        return seen.mapNotNull { encoded ->
            try {
                decode(encoded)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Skipping malformed credential ID for rpId=$rpId: ${e.message}")
                null
            }
        }
    }

    suspend fun add(rpId: String, credentialId: ByteArray) {
        val encoded = encode(credentialId)
        val current = LinkedHashSet(readEncodedUnion(rpId))
        if (!current.add(encoded)) return
        val payload = jsonEncode(current.toList())
        writeToBlockStore(rpId, payload)
        writeToPrefs(rpId, payload)
    }

    suspend fun remove(rpId: String, credentialId: ByteArray) {
        val encoded = encode(credentialId)
        val current = readEncodedUnion(rpId).toMutableList()
        if (!current.remove(encoded)) return
        if (current.isEmpty()) {
            // Avoid persisting an empty array; clear() drops both
            // backing entries so subsequent add() takes the insert
            // path rather than overwriting an empty blob.
            clear(rpId)
        } else {
            val payload = jsonEncode(current)
            writeToBlockStore(rpId, payload)
            writeToPrefs(rpId, payload)
        }
    }

    suspend fun clear(rpId: String) {
        clearBlockStore(rpId)
        clearPrefs(rpId)
    }

    private suspend fun readEncodedUnion(rpId: String): List<String> {
        val seen = LinkedHashSet<String>()
        readFromBlockStore(rpId)?.forEach { seen.add(it) }
        readFromPrefs(rpId).forEach { seen.add(it) }
        return seen.toList()
    }

    // ------------------------------------------------------------------
    // Block Store
    // ------------------------------------------------------------------

    private fun blockStoreKey(rpId: String): String = "$BLOCKSTORE_KEY_PREFIX$rpId"

    private suspend fun readFromBlockStore(rpId: String): List<String>? {
        val client = blockStoreClient() ?: return null
        return try {
            val request = RetrieveBytesRequest.Builder()
                .setKeys(listOf(blockStoreKey(rpId)))
                .build()
            val response = client.retrieveBytes(request).await()
            val bytes = response.blockstoreDataMap[blockStoreKey(rpId)]?.bytes
                ?: return emptyList()
            jsonDecode(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Block Store retrieve failed for rpId=$rpId: ${e.message}")
            null
        }
    }

    private suspend fun writeToBlockStore(rpId: String, encoded: String) {
        val client = blockStoreClient() ?: return
        try {
            val data = StoreBytesData.Builder()
                .setKey(blockStoreKey(rpId))
                .setBytes(encoded.toByteArray(Charsets.UTF_8))
                // Opt into cross-device + cross-reinstall sync via the
                // user's Google account. Without this, Block Store keeps
                // the value only locally.
                .setShouldBackupToCloud(true)
                .build()
            client.storeBytes(data).await()
        } catch (e: Exception) {
            // Best-effort: a Block Store write failure leaves us in
            // local-only mode. Prefs write below still succeeds and the
            // next add() retries the cloud path.
            Log.w(TAG, "Block Store write failed for rpId=$rpId: ${e.message}")
        }
    }

    private suspend fun clearBlockStore(rpId: String) {
        val client = blockStoreClient() ?: return
        try {
            val request = DeleteBytesRequest.Builder()
                .setKeys(listOf(blockStoreKey(rpId)))
                .build()
            client.deleteBytes(request).await()
        } catch (e: Exception) {
            Log.w(TAG, "Block Store delete failed for rpId=$rpId: ${e.message}")
        }
    }

    private fun blockStoreClient(): BlockstoreClient? = try {
        Blockstore.getClient(context.applicationContext)
    } catch (e: Exception) {
        // No Play Services / unsupported device. Fall back to prefs only.
        Log.w(TAG, "Block Store unavailable: ${e.message}")
        null
    }

    // ------------------------------------------------------------------
    // SharedPreferences fallback
    // ------------------------------------------------------------------

    private fun prefsKey(rpId: String): String = "$PREFS_KEY_PREFIX$rpId"

    private fun readFromPrefs(rpId: String): List<String> = try {
        val prefs = context.applicationContext.getSharedPreferences(
            PREFS_FILE, Context.MODE_PRIVATE
        )
        val raw = prefs.getString(prefsKey(rpId), null) ?: return emptyList()
        jsonDecode(raw)
    } catch (e: Exception) {
        Log.w(TAG, "Prefs read failed for rpId=$rpId: ${e.message}")
        emptyList()
    }

    private fun writeToPrefs(rpId: String, encoded: String) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(
                PREFS_FILE, Context.MODE_PRIVATE
            )
            prefs.edit().putString(prefsKey(rpId), encoded).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Prefs write failed for rpId=$rpId: ${e.message}")
        }
    }

    private fun clearPrefs(rpId: String) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(
                PREFS_FILE, Context.MODE_PRIVATE
            )
            prefs.edit().remove(prefsKey(rpId)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Prefs delete failed for rpId=$rpId: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // JSON encoding (storage format unchanged across the rename)
    // ------------------------------------------------------------------

    private fun jsonEncode(ids: List<String>): String {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        return arr.toString()
    }

    private fun jsonDecode(raw: String): List<String> = try {
        val arr = JSONArray(raw)
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotEmpty()) out.add(v)
        }
        out
    } catch (e: Exception) {
        // Corrupt or stale shape. Surface as empty rather than crash —
        // the next successful create / sign-in will overwrite it.
        emptyList()
    }
}
