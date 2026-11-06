package com.dailytodo.mydeepseek

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class PetSecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("pet-api-secret", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "pet-chat")
    private val todoFile = AtomicFile(File(context.filesDir, "pet-todos.bin"))
    private fun privateFile(name: String) = AtomicFile(File(directory.parentFile, "pet-$name.bin"))
    @Synchronized fun privateRecord(name: String): JSONObject? {
        require(name == "persona" || name == "memories" || name == "emotion")
        val file = privateFile(name)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        require(file.baseFile.length() <= 4 * 1024 * 1024)
        return JSONObject(String(SecretEnvelope.decrypt(key(), file.readFully()), Charsets.UTF_8))
    }
    @Synchronized fun savePrivateRecord(name: String, value: JSONObject) {
        require(name == "persona" || name == "memories" || name == "emotion")
        val file = privateFile(name)
        val data = SecretEnvelope.encrypt(key(), value.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    @Synchronized fun hasKey() = preferences.contains("account")
    @Synchronized fun account(): JSONObject? {
        val encoded = preferences.getString("account", null) ?: return null
        return JSONObject(String(SecretEnvelope.decrypt(key(), Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8))
    }
    @Synchronized fun saveKey(value: String, accepted: Boolean) {
        restoreAccount(encodeKey(value, accepted))
    }
    @Synchronized internal fun encodeKey(value: String, accepted: Boolean): String {
        require(accepted && DeepSeekProtocol.validKey(value))
        val record = JSONObject().put("key", value).put("billingAccepted", true).put("consentVersion", 1)
        val data = SecretEnvelope.encrypt(key(), record.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
    @Synchronized internal fun encodedAccount(): String? = preferences.getString("account", null)
    @Synchronized internal fun restoreAccount(encoded: String?) {
        if (encoded == null) { removeKey(); return }
        check(preferences.edit().putString("account", encoded).commit())
    }
    @Synchronized fun removeKey() { check(preferences.edit().remove("account").commit()) }
    @Synchronized fun history(id: String): List<JSONObject> {
        require(DeepSeekProtocol.validPet(id))
        val file = AtomicFile(File(directory, "$id.bin"))
        if (!file.baseFile.exists()) return emptyList()
        require(file.baseFile.length() <= 4 * 1024 * 1024) { "本地记录过大" }
        val data = JSONArray(String(SecretEnvelope.decrypt(key(), file.readFully()), Charsets.UTF_8))
        return (0 until data.length()).map { data.getJSONObject(it) }.takeLast(200)
    }
    @Synchronized fun saveHistory(id: String, records: List<JSONObject>) {
        require(DeepSeekProtocol.validPet(id))
        check(directory.exists() || directory.mkdirs())
        val value = JSONArray()
        records.takeLast(200).forEach { value.put(it) }
        val data = SecretEnvelope.encrypt(key(), value.toString().toByteArray(Charsets.UTF_8))
        val file = AtomicFile(File(directory, "$id.bin"))
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized fun todos(): List<JSONObject> {
        if (!todoFile.baseFile.exists() && !File(todoFile.baseFile.path + ".bak").exists()) return emptyList()
        require(todoFile.baseFile.length() <= 4 * 1024 * 1024)
        val rows = JSONArray(String(SecretEnvelope.decrypt(key(), todoFile.readFully()), Charsets.UTF_8))
        require(rows.length() <= TodoRules.MAX_ITEMS)
        return (0 until rows.length()).map { rows.getJSONObject(it).also { row ->
            require(row.getString("id").matches(Regex("[A-Za-z0-9_-]{1,64}")))
            TodoRules.text(row.getString("text")); TodoRules.due(row); row.getBoolean("done")
        } }
    }
    @Synchronized fun saveTodos(records: List<JSONObject>) {
        require(records.size <= TodoRules.MAX_ITEMS)
        val rows = JSONArray(); records.forEach { rows.put(it) }
        val data = SecretEnvelope.encrypt(key(), rows.toString().toByteArray(Charsets.UTF_8))
        val stream = todoFile.startWrite()
        try { stream.write(data); todoFile.finishWrite(stream) }
        catch (failure: Exception) { todoFile.failWrite(stream); throw failure }
    }
    companion object { private const val ALIAS = "dsh_pet_api_aes_v1" }
}
