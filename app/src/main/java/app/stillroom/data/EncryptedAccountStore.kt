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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
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
        try { read(id) } catch (_: Exception) { delete(id); null }
    }

    @Synchronized fun read(id: AccountId): SavedAccount {
        val input = DataInputStream(ByteArrayInputStream(AtomicFile(encryptedFile(id)).readFully()))
        val ivLength = input.readInt()
        require(ivLength == 12) { "Invalid encrypted record." }
        val iv = ByteArray(ivLength).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = keyStore.getKey(alias(id), null) as? SecretKey ?: error("Account encryption key is unavailable.")
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
        return SavedAccount(Account(id, address, userId, json.getString("username"), json.getString("version"), permissions, verifier), json.getString("api_key"))
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
