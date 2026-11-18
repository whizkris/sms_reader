package com.whizkris.smsreader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SmsConfig(
    val enabled: Boolean = false,
    val destination: String = "",
    val allowedSenders: List<String> = emptyList(),
    val includeKeywords: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList()
)

data class ForwardEvent(val sender: String, val status: String, val timestamp: Long)

object SmsConfigStore {
    private const val KEY_ALIAS = "sms_otp_forwarder_settings_v1"
    private const val PREFS = "otp_forwarder_secure"
    private const val CONFIG_KEY = "encrypted_config"
    private const val HISTORY_PREFS = "otp_forwarder_history"
    private const val HISTORY_KEY = "events"
    private const val DEDUPE_PREFS = "otp_forwarder_dedupe"

    fun load(context: Context): SmsConfig {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CONFIG_KEY, null) ?: return SmsConfig()
        return try {
            val json = JSONObject(decrypt(encoded))
            SmsConfig(
                enabled = json.optBoolean("enabled", false),
                destination = json.optString("destination", ""),
                allowedSenders = json.optJSONArray("senders").toStringList(),
                includeKeywords = json.optJSONArray("include").toStringList(),
                excludeKeywords = json.optJSONArray("exclude").toStringList()
            )
        } catch (_: Exception) {
            SmsConfig()
        }
    }

    fun save(context: Context, config: SmsConfig) {
        val json = JSONObject()
            .put("enabled", config.enabled)
            .put("destination", config.destination)
            .put("senders", JSONArray(config.allowedSenders))
            .put("include", JSONArray(config.includeKeywords))
            .put("exclude", JSONArray(config.excludeKeywords))
        val encrypted = encrypt(json.toString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CONFIG_KEY, encrypted).commit()
    }

    fun validate(config: SmsConfig): String? {
        if (!config.destination.matches(Regex("^\\+?[0-9]{8,15}$"))) return "Enter a valid destination number, including country code."
        if (config.allowedSenders.isEmpty()) return "Add at least one allowed sender. Unknown senders are never forwarded."
        if (config.includeKeywords.isEmpty()) return "Add at least one include keyword."
        return null
    }

    fun addEvent(context: Context, sender: String, status: String, timestamp: Long) {
        val prefs = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(HISTORY_KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val next = JSONArray()
        next.put(JSONObject().put("sender", sender).put("status", status).put("time", timestamp))
        for (i in 0 until minOf(old.length(), 29)) next.put(old.getJSONObject(i))
        prefs.edit().putString(HISTORY_KEY, next.toString()).apply()
    }

    fun history(context: Context): List<ForwardEvent> {
        val arr = try {
            JSONArray(context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE).getString(HISTORY_KEY, "[]"))
        } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                ForwardEvent(o.optString("sender"), o.optString("status"), o.optLong("time"))
            } catch (_: Exception) { null }
        }
    }

    fun isDuplicate(context: Context, fingerprint: String): Boolean {
        val prefs = context.getSharedPreferences(DEDUPE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val oldTime = prefs.getLong(fingerprint, 0L)
        if (oldTime > 0 && now - oldTime < 10 * 60 * 1000L) return true
        prefs.edit().putLong(fingerprint, now).apply()
        return false
    }

    fun formatTime(time: Long): String =
        SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(time))

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > 12) { "Invalid encrypted settings" }
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
