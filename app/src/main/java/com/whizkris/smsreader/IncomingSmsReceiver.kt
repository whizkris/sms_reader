package com.whizkris.smsreader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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

        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("$sender|$body|$timestamp".toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (SmsConfigStore.isDuplicate(context, fingerprint)) return

        matchingRules.forEach { rule ->
            if (rule.sendSms) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    SmsConfigStore.addEvent(context, rule.name, sender, "SMS permission missing", timestamp)
                } else {
                    rule.smsDestinations.forEach { number ->
                        try {
                            sendSms(context, number, "Message from $sender:\n$body")
                            SmsConfigStore.addEvent(context, rule.name, sender,
                                "SMS submitted to ${maskNumber(number)}", timestamp)
                        } catch (e: Exception) {
                            SmsConfigStore.addEvent(context, rule.name, sender,
                                "SMS failed (${e.javaClass.simpleName})", timestamp)
                        }
                    }
                }
            }
            if (rule.sendWebhook) {
                try {
                    enqueueWebhook(context, rule, sender, body, timestamp, fingerprint)
                } catch (e: Exception) {
                    SmsConfigStore.addEvent(context, rule.name, sender,
                        "Webhook queue failed (${e.javaClass.simpleName})", timestamp)
                }
            }
        }
    }

    private fun sendSms(context: Context, number: String, text: String) {
        val manager = context.getSystemService(SmsManager::class.java)
        val chunks = manager.divideMessage(text)
        if (chunks.size > 1) manager.sendMultipartTextMessage(number, null, chunks, null, null)
        else manager.sendTextMessage(number, null, text, null, null)
    }

    private fun enqueueWebhook(
        context: Context, rule: SmsRule, sender: String, body: String, timestamp: Long, fingerprint: String
    ) {
        val payload = JSONObject()
            .put("ruleId", rule.id)
            .put("ruleName", rule.name)
            .put("sender", sender)
            .put("message", body)
            .put("receivedAt", timestamp)
        val encrypted = SmsConfigStore.encryptPayload(payload.toString())
        val data = Data.Builder()
            .putString(WebhookWorker.KEY_RULE_ID, rule.id)
            .putString(WebhookWorker.KEY_PAYLOAD, encrypted)
            .build()
        val request = OneTimeWorkRequestBuilder<WebhookWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sms-webhook-${rule.id}-$fingerprint", ExistingWorkPolicy.KEEP, request
        )
    }

    private fun maskNumber(number: String): String =
        if (number.length <= 4) "****" else "******" + number.takeLast(4)
}
