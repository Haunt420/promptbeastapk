package app.promptbeast

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One saved sign-in for the Mack pane (the WebView can't use the system password manager inside Perchance's frames).
 * AES-GCM with a key that lives in the Android Keystore and never leaves it; the file holds only IV + ciphertext and sits
 * outside backups. If the Keystore refuses, nothing is saved: there is no plaintext fallback.
 */
class SignInStore(private val file: File) {
    class Cred(val user: String, val pass: String)

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }

    val saved: Boolean get() = file.exists()

    fun save(user: String, pass: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(true))
        val body = c.doFinal(JSONObject().put("u", user).put("p", pass).toString().toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(c.iv + body)
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        if (!tmp.renameTo(file)) { file.writeBytes(c.iv + body); tmp.delete() }
        true
    } catch (e: Exception) { false }

    fun load(): Cred? = try {
        if (!file.exists()) null else {
            val raw = file.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(false) ?: return null, GCMParameterSpec(128, raw, 0, IV_LEN))
            val j = JSONObject(String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8))
            Cred(j.getString("u"), j.getString("p"))
        }
    } catch (e: Exception) { null }

    fun forget() {
        file.delete()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (e: Exception) { /* nothing to drop */ }
    }

    private companion object {
        const val ALIAS = "promptbeast_signin"
        const val IV_LEN = 12
    }
}
