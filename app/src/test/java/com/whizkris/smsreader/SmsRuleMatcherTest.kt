package com.whizkris.smsreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsRuleMatcherTest {
    @Test fun wildcardMatchesChangingSenderPrefixesAndSuffixes() {
        val rule = SmsRule(enabled = true, senderPatterns = listOf("*SBIBNK*"), sendSms = true,
            smsDestinations = listOf("+919876543210"))
        assertTrue(SmsRuleMatcher.matches(rule, "VC-SBIBNK-T", "Your OTP is 123456"))
        assertTrue(SmsRuleMatcher.matches(rule, "VX-SBIBNK-S", "Your OTP is 123456"))
        assertFalse(SmsRuleMatcher.matches(rule, "VM-OTHER-T", "Your OTP is 123456"))
    }

    @Test fun bodyOnlyRuleCanMatchWithoutSenderCondition() {
        val rule = SmsRule(enabled = true, bodyKeywords = listOf("verification code"), sendSms = true,
            smsDestinations = listOf("+919876543210"))
        assertTrue(SmsRuleMatcher.matches(rule, "UNKNOWN-SENDER", "Your verification code is 654321"))
        assertFalse(SmsRuleMatcher.matches(rule, "UNKNOWN-SENDER", "Your account balance is low"))
    }

    @Test fun senderAndBodyConditionsAreAlternatives() {
        val rule = SmsRule(enabled = true, senderPatterns = listOf("*BANK*"),
            bodyKeywords = listOf("OTP"), sendSms = true, smsDestinations = listOf("+919876543210"))
        assertTrue(SmsRuleMatcher.matches(rule, "AD-BANK-T", "Your statement is ready"))
        assertTrue(SmsRuleMatcher.matches(rule, "AD-OTHER-T", "Your OTP is 123456"))
        assertFalse(SmsRuleMatcher.matches(rule, "AD-OTHER-T", "Your statement is ready"))
    }

    @Test fun exclusionsAlwaysBlockAnOtherwiseMatchingMessage() {
        val rule = SmsRule(enabled = true, senderPatterns = listOf("*SBIBNK*"),
            excludeKeywords = listOf("debit alert"), sendSms = true,
            smsDestinations = listOf("+919876543210"))
        assertTrue(SmsRuleMatcher.matches(rule, "VC-SBIBNK-T", "Your OTP is 123456"))
        assertFalse(SmsRuleMatcher.matches(rule, "VC-SBIBNK-T", "Debit alert OTP 123456"))
    }

    @Test fun disabledAndUnconfiguredRulesNeverMatch() {
        val disabled = SmsRule(enabled = false, senderPatterns = listOf("*BANK*"))
        val empty = SmsRule(enabled = true)
        assertFalse(SmsRuleMatcher.matches(disabled, "AD-BANK-T", "OTP"))
        assertFalse(SmsRuleMatcher.matches(empty, "AD-BANK-T", "OTP"))
    }
}
