package com.whizkris.smsreader

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WebhookWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ruleId = inputData.getString(KEY_RULE_ID) ?: return Result.failure()
        val encrypted = inputData.getString(KEY_PAYLOAD) ?: return Result.failure()
        return try {
            val config = SmsConfigStore.load(applicationContext)
            val rule = config.rules.firstOrNull { it.id == ruleId && it.enabled && it.sendWebhook }
                ?: return Result.success()
            val payload = JSONObject(SmsConfigStore.decryptPayload(encrypted))
            val sender = payload.optString("sender")
            val timestamp = payload.optLong("receivedAt")
            val connection = (URL(rule.webhookUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                rule.webhookHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            val code = try {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                connection.responseCode
            } finally { connection.disconnect() }
            when {
                code in 200..299 -> {
                    SmsConfigStore.addEvent(applicationContext, rule.name, sender, "Webhook HTTP $code", timestamp)
                    Result.success()
                }
                code == 408 || code == 429 || code >= 500 -> {
                    if (runAttemptCount >= 4) {
                        SmsConfigStore.addEvent(applicationContext, rule.name, sender, "Webhook failed HTTP $code", timestamp)
                        Result.failure()
                    } else Result.retry()
                }
                else -> {
                    SmsConfigStore.addEvent(applicationContext, rule.name, sender, "Webhook rejected HTTP $code", timestamp)
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            if (runAttemptCount >= 4) {
                SmsConfigStore.addEvent(applicationContext, "Webhook", "Unknown sender",
                    "Webhook failed (${e.javaClass.simpleName})", System.currentTimeMillis())
                Result.failure()
            } else Result.retry()
        }
    }

    companion object {
        const val KEY_RULE_ID = "rule_id"
        const val KEY_PAYLOAD = "encrypted_payload"
    }
}
