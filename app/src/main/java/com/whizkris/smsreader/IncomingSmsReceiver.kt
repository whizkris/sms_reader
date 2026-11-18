package com.whizkris.smsreader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.Locale

class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return

        val config = SmsConfigStore.load(context)
        if (!config.enabled || !config.ruleEnabled || config.destination.isBlank() ||
            config.allowedSenders.isEmpty() || config.includeKeywords.isEmpty()) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val sender = parts.first().originatingAddress?.trim().orEmpty()
        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
        val timestamp = parts.first().timestampMillis
        if (sender.isBlank() || body.isBlank()) return

        if (config.allowedSenders.none { senderMatches(sender, it) }) return
        val lowerBody = body.lowercase(Locale.ROOT)
        if (config.includeKeywords.none { lowerBody.contains(it.lowercase(Locale.ROOT)) }) return
        if (config.excludeKeywords.any { lowerBody.contains(it.lowercase(Locale.ROOT)) }) return

        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sender|$body|$timestamp".toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (SmsConfigStore.isDuplicate(context, digest)) return

        val forwardedText = "OTP message from $sender:\n$body"
        try {
            val manager = context.getSystemService(SmsManager::class.java)
            val smsParts = manager.divideMessage(forwardedText)
            if (smsParts.size > 1) {
                manager.sendMultipartTextMessage(config.destination, null, smsParts, null, null)
            } else {
                manager.sendTextMessage(config.destination, null, forwardedText, null, null)
            }
            SmsConfigStore.addEvent(context, sender, "Submitted to SMS service", timestamp)
        } catch (e: Exception) {
            SmsConfigStore.addEvent(context, sender, "Send failed: ${e.javaClass.simpleName}", timestamp)
        }
    }

    private fun senderMatches(actual: String, allowed: String): Boolean {
        val a = actual.trim()
        val b = allowed.trim()
        if (a.equals(b, ignoreCase = true)) return true
        val digitsA = a.filter(Char::isDigit)
        val digitsB = b.filter(Char::isDigit)
        return digitsA.length >= 8 && digitsB.length >= 8 &&
            (digitsA.endsWith(digitsB) || digitsB.endsWith(digitsA))
    }
}
