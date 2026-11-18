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
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SmsRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New rule",
    val enabled: Boolean = false,
    val senderPatterns: List<String> = emptyList(),
    val bodyKeywords: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    val sendSms: Boolean = true,
    val smsDestinations: List<String> = emptyList(),
    val sendWebhook: Boolean = false,
    val webhookUrl: String = "",
    val webhookHeaders: Map<String, String> = emptyMap()
)

data class SmsConfig(val enabled: Boolean = false, val rules: List<SmsRule> = emptyList())
data class ForwardEvent(val ruleName: String, val sender: String, val status: String, val timestamp: Long)

object SmsConfigStore {
    private const val KEY_ALIAS = "sms_otp_forwarder_settings_v1"
    private const val PREFS = "otp_forwarder_secure"
    private const val CONFIG_KEY = "encrypted_config"
    private const val HISTORY_PREFS = "otp_forwarder_history"
    private const val HISTORY_KEY = "events"
    private const val DEDUPE_PREFS = "otp_forwarder_dedupe"

    fun load(context: Context): SmsConfig {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CONFIG_KEY, null)
            ?: return SmsConfig()
        return try {
            val json = JSONObject(decrypt(encoded))
            if (json.has("rules")) {
                SmsConfig(json.optBoolean("enabled", false), json.optJSONArray("rules").toRuleList())
            } else {
                val legacy = SmsRule(
                    name = "Migrated rule",
                    enabled = json.optBoolean("ruleEnabled", true),
                    senderPatterns = json.optJSONArray("senders").toStringList(),
                    bodyKeywords = json.optJSONArray("include").toStringList(),
                    excludeKeywords = json.optJSONArray("exclude").toStringList(),
                    sendSms = true,
                    smsDestinations = listOf(json.optString("destination", "")).filter { it.isNotBlank() }
                )
                SmsConfig(json.optBoolean("enabled", false), listOf(legacy))
            }
        } catch (_: Exception) { SmsConfig() }
    }

    fun save(context: Context, config: SmsConfig) {
        val json = JSONObject().put("enabled", config.enabled).put("rules", JSONArray().apply {
            config.rules.forEach { rule ->
                put(JSONObject()
                    .put("id", rule.id).put("name", rule.name).put("enabled", rule.enabled)
                    .put("senderPatterns", JSONArray(rule.senderPatterns))
                    .put("bodyKeywords", JSONArray(rule.bodyKeywords))
                    .put("excludeKeywords", JSONArray(rule.excludeKeywords))
                    .put("sendSms", rule.sendSms).put("smsDestinations", JSONArray(rule.smsDestinations))
                    .put("sendWebhook", rule.sendWebhook).put("webhookUrl", rule.webhookUrl)
                    .put("webhookHeaders", JSONObject(rule.webhookHeaders)))
            }
        })
        val encrypted = encrypt(json.toString())
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CONFIG_KEY, encrypted).commit()) { "Could not write settings" }
    }

    fun validate(rule: SmsRule): String? {
        if (rule.senderPatterns.isEmpty() && rule.bodyKeywords.isEmpty())
            return "Add a sender pattern or a body keyword. Either one is sufficient."
        if (!rule.sendSms && !rule.sendWebhook) return "Enable at least one action: SMS or webhook."
        if (rule.sendSms) {
            if (rule.smsDestinations.isEmpty()) return "Add at least one SMS destination."
            if (rule.smsDestinations.any { !it.matches(Regex("^\\+?[0-9]{8,15}$")) })
                return "Check SMS destination numbers. Include country code, e.g. +919876543210."
        }
        if (rule.sendWebhook) {
            if (!rule.webhookUrl.startsWith("https://", ignoreCase = true))
                return "Webhook URL must use HTTPS."
            if (rule.webhookUrl.length > 2048) return "Webhook URL is too long."
            if (rule.webhookHeaders.any { (key, value) ->
                    key.isBlank() || key.any { it == ':' || it == '\r' || it == '\n' } ||
                        value.contains('\r') || value.contains('\n')
                }) return "Check webhook header names and values."
        }
        return null
    }

    fun hasEnabledSmsRule(config: SmsConfig) =
        config.enabled && config.rules.any { it.enabled && it.sendSms }

    fun addEvent(context: Context, ruleName: String, sender: String, status: String, timestamp: Long) {
        val prefs = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(HISTORY_KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val next = JSONArray().put(JSONObject()
            .put("rule", ruleName).put("sender", sender).put("status", status).put("time", timestamp))
        for (i in 0 until minOf(old.length(), 49)) next.put(old.getJSONObject(i))
        prefs.edit().putString(HISTORY_KEY, next.toString()).apply()
    }

    fun history(context: Context): List<ForwardEvent> {
        val arr = try { JSONArray(context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE).getString(HISTORY_KEY, "[]")) }
            catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                ForwardEvent(o.optString("rule"), o.optString("sender"), o.optString("status"), o.optLong("time"))
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

    fun formatTime(time: Long) = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(time))

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private fun JSONArray?.toRuleList(): List<SmsRule> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            try {
                val o = getJSONObject(index)
                val headers = o.optJSONObject("webhookHeaders") ?: JSONObject()
                val headerMap = headers.keys().asSequence().associateWith { headers.optString(it) }
                SmsRule(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Rule"), enabled = o.optBoolean("enabled", false),
                    senderPatterns = o.optJSONArray("senderPatterns").toStringList(),
                    bodyKeywords = o.optJSONArray("bodyKeywords").toStringList(),
                    excludeKeywords = o.optJSONArray("excludeKeywords").toStringList(),
                    sendSms = o.optBoolean("sendSms", false),
                    smsDestinations = o.optJSONArray("smsDestinations").toStringList(),
                    sendWebhook = o.optBoolean("sendWebhook", false),
                    webhookUrl = o.optString("webhookUrl", ""), webhookHeaders = headerMap
                )
            } catch (_: Exception) { null }
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    fun encryptPayload(value: String): String = encrypt(value)

    fun decryptPayload(value: String): String = decrypt(value)

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > 12) { "Invalid encrypted settings" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }
}
