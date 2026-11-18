package com.whizkris.smsreader

import java.util.Locale

object SmsRuleMatcher {
    fun matches(rule: SmsRule, sender: String, body: String): Boolean {
        if (!rule.enabled) return false
        val senderConfigured = rule.senderPatterns.isNotEmpty()
        val bodyConfigured = rule.bodyKeywords.isNotEmpty()
        if (!senderConfigured && !bodyConfigured) return false
        val senderMatched = senderConfigured && rule.senderPatterns.any { wildcardMatch(sender, it) }
        val lowerBody = body.lowercase(Locale.ROOT)
        val bodyMatched = bodyConfigured && rule.bodyKeywords.any {
            lowerBody.contains(it.trim().lowercase(Locale.ROOT))
        }
        if (!(senderMatched || bodyMatched)) return false
        return rule.excludeKeywords.none { keyword ->
            keyword.isNotBlank() && lowerBody.contains(keyword.trim().lowercase(Locale.ROOT))
        }
    }

    fun wildcardMatch(value: String, pattern: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        val regex = buildString {
            append("^")
            p.forEach { ch ->
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    else -> {
                        if (ch in ".\\+()^$|{}[]") append('\\')
                        append(ch)
                    }
                }
            }
            append("$")
        }
        return try { Regex(regex, RegexOption.IGNORE_CASE).matches(value.trim()) }
        catch (_: Exception) { false }
    }
}
