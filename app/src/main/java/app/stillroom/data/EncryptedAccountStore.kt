package app.stillroom.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.stillroom.domain.Account
import app.stillroom.domain.AccountId
import app.stillroom.domain.ServerAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.EOFException
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Never allow a credential record's generated toString to include its API key. */
class SavedAccount(val account: Account, internal val apiKey: String) {
    override fun toString() = "SavedAccount(${account.id.value}, key=[redacted])"
}

class EncryptedAccountStore(context: Context, private val namespace: String = "accounts") {
    private val directory = File(context.noBackupFilesDir, namespace).apply { mkdirs() }
    private val index = context.getSharedPreferences("${namespace}_index", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun alias(id: AccountId) = "stillroom.$namespace.${id.value}"
    fun encryptedFile(id: AccountId) = File(directory, "${id.value}.enc")
    fun preferred(): AccountId? = index.getString("active", null)?.let { runCatching { AccountId(it) }.getOrNull() }
    fun setPreferred(id: AccountId?) {
        check(index.edit().putString("active", id?.value).commit()) { "Could not save active account." }
    }

    @Synchronized fun list(): List<SavedAccount> = index.getStringSet("ids", emptySet()).orEmpty().toList().mapNotNull { text ->
        val id = runCatching { AccountId(text) }.getOrNull() ?: return@mapNotNull null
        try { read(id) }
        // Provably unreadable by anything, now or later: drop the record and its index entry.
        catch (_: AccountRecordCorrupt) { delete(id); null }
        // Anything else (missing/unusable Keystore key, Keystore daemon or provider failure,
        // storage error) may be transient. Keep the record: deleting it would destroy an API key
        // that exists nowhere else, and allowBackup is off, so there is no second copy.
        catch (_: Exception) { null }
    }

    @Synchronized fun read(id: AccountId): SavedAccount {
        val bytes = try { AtomicFile(encryptedFile(id)).readFully() }
            // No file means there is nothing left to protect; any other IO error is retried later.
            catch (error: FileNotFoundException) { throw AccountRecordCorrupt(id, error) }
        val key = try { keyStore.getKey(alias(id), null) as? SecretKey }
            catch (error: Exception) { throw AccountKeyUnavailable(id, error) }
            ?: throw AccountKeyUnavailable(id)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return try {
            val ivLength = input.readInt()
            require(ivLength == 12) { "Invalid encrypted record." }
            val iv = ByteArray(ivLength).also(input::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(id.value.toByteArray())
            val json = JSONObject(String(cipher.doFinal(input.readBytes()), Charsets.UTF_8))
            val address = ServerAddress.parse(json.getString("base_url"), json.getBoolean("insecure"))
            val userId = json.getLong("user_id")
            require(AccountId.of(address, userId) == id) { "Account identity mismatch." }
            val permissions = if (json.isNull("permissions")) null else json.getJSONArray("permissions").let { array ->
                (0 until array.length()).map { array.getString(it) }.toSet()
            }
            val verifier = if (json.isNull("verifier")) null else AccountId(json.getString("verifier"))
            SavedAccount(Account(id, address, userId, json.getString("username"), json.getString("version"), permissions, verifier), json.getString("api_key"))
        } catch (error: Exception) {
            throw if (provesRecordCorruption(error)) AccountRecordCorrupt(id, error) else error
        }
    }

    @Synchronized fun save(saved: SavedAccount) {
        val account = saved.account
        val key = (keyStore.getKey(alias(account.id), null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias(account.id), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
        val json = JSONObject().put("base_url", account.address.apiBase).put("insecure", account.address.allowInsecure)
            .put("user_id", account.userId).put("username", account.username).put("version", account.version)
            .put("permissions", account.permissions?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
            .put("verifier", account.permissionVerifier?.value ?: JSONObject.NULL).put("api_key", saved.apiKey)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(account.id.value.toByteArray())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { it.writeInt(cipher.iv.size); it.write(cipher.iv); it.write(encrypted) }
        }.toByteArray()
        val file = AtomicFile(encryptedFile(account.id))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        val ids = index.getStringSet("ids", emptySet()).orEmpty() + account.id.value
        check(index.edit().putStringSet("ids", ids).commit()) { "Could not save account index." }
    }

    @Synchronized fun delete(id: AccountId) {
        AtomicFile(encryptedFile(id)).delete()
        keyStore.deleteEntry(alias(id))
        val editor = index.edit().putStringSet("ids", index.getStringSet("ids", emptySet()).orEmpty() - id.value)
        if (preferred() == id) editor.remove("active")
        check(editor.commit()) { "Could not clear account index." }
    }
}

/**
 * The Keystore key that protects this record is missing, or the Keystore refused to return it.
 * The encrypted record is left in place; it is never treated as damage.
 */
class AccountKeyUnavailable(id: AccountId, cause: Throwable? = null) :
    Exception("The encryption key for account ${id.value.take(8)} is unavailable.", cause)

/** The stored record is provably unreadable. Only this condition allows the store to delete it. */
class AccountRecordCorrupt(id: AccountId, cause: Throwable) :
    Exception("Saved account ${id.value.take(8)} is damaged.", cause)

/**
 * True only for failures that prove the record bytes themselves are bad. The record is read
 * fully into memory first, so an EOF here means truncation, not a storage hiccup. GCM
 * authenticates the ciphertext and the account id, so a tag failure means the bytes can
 * never decrypt with this key. After a successful decrypt, unparseable JSON, bad framing or
 * an identity mismatch are permanent too.
 *
 * Everything else, notably `ProviderException`, `KeyStoreException`, `InvalidKeyException`
 * and `UnrecoverableKeyException` from a Keystore daemon or hardware-backed provider that is
 * temporarily failing, says nothing about the record and must not cause a delete.
 */
internal fun provesRecordCorruption(error: Throwable): Boolean = when (error) {
    is AEADBadTagException, is JSONException, is EOFException -> true
    is IllegalArgumentException -> true // framing, address parse, account-id format, identity mismatch
    else -> false
}
