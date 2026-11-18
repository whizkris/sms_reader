package com.whizkris.smsreader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val sender = parts.first().originatingAddress?.trim().orEmpty()
        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
        val timestamp = parts.first().timestampMillis
        if (sender.isBlank() || body.isBlank()) return

        val config = SmsConfigStore.load(context)
        if (!config.enabled) return
        val matchingRules = config.rules.filter { SmsRuleMatcher.matches(it, sender, body) }
        if (matchingRules.isEmpty()) return

        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sender|$body|$timestamp".toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (SmsConfigStore.isDuplicate(context, digest)) return

        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                matchingRules.forEach { rule ->
                    val outcomes = mutableListOf<String>()
                    if (rule.sendSms) {
                        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                            outcomes += "SMS permission missing"
                        } else {
                            rule.smsDestinations.forEach { number ->
                                try {
                                    sendSms(appContext, number, "Message from $sender:\n$body")
                                    outcomes += "SMS submitted to \${maskNumber(number)}"
                                } catch (e: Exception) {
                                    outcomes += "SMS failed (\${e.javaClass.simpleName})"
                                }
                            }
                        }
                    }
                    if (rule.sendWebhook) {
                        try {
                            val code = postWebhook(rule, sender, body, timestamp)
                            outcomes += "Webhook HTTP $code"
                        } catch (e: Exception) {
                            outcomes += "Webhook failed (\${e.javaClass.simpleName})"
                        }
                    }
                    SmsConfigStore.addEvent(appContext, rule.name, sender, outcomes.joinToString("; "), timestamp)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun sendSms(context: Context, number: String, text: String) {
        val manager = context.getSystemService(SmsManager::class.java)
        val chunks = manager.divideMessage(text)
        if (chunks.size > 1) manager.sendMultipartTextMessage(number, null, chunks, null, null)
        else manager.sendTextMessage(number, null, text, null, null)
    }

    private fun postWebhook(rule: SmsRule, sender: String, body: String, timestamp: Long): Int {
        val connection = (URL(rule.webhookUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            rule.webhookHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            val payload = JSONObject()
                .put("ruleId", rule.id)
                .put("ruleName", rule.name)
                .put("sender", sender)
                .put("message", body)
                .put("receivedAt", timestamp)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun maskNumber(number: String): String =
        if (number.length <= 4) "****" else "******" + number.takeLast(4)
}
