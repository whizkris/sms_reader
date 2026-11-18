package com.whizkris.smsreader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OtpForwarderScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtpForwarderScreen() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(SmsConfigStore.load(context)) }
    var ruleEnabled by remember { mutableStateOf(config.ruleEnabled) }
    var destination by remember { mutableStateOf(config.destination) }
    var senders by remember { mutableStateOf(config.allowedSenders.joinToString("\n")) }
    var includes by remember { mutableStateOf(config.includeKeywords.joinToString("\n")) }
    var excludes by remember { mutableStateOf(config.excludeKeywords.joinToString("\n")) }
    var history by remember { mutableStateOf(SmsConfigStore.history(context)) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    fun permissionGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var permissionsOk by remember {
        mutableStateOf(
            permissionGranted(Manifest.permission.RECEIVE_SMS) &&
                permissionGranted(Manifest.permission.SEND_SMS)
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsOk = permissionGranted(Manifest.permission.RECEIVE_SMS) &&
            permissionGranted(Manifest.permission.SEND_SMS)
    }

    fun saveSettings(enable: Boolean = config.enabled, rule: Boolean = ruleEnabled) {
        val updated = SmsConfig(
            enabled = enable,
            ruleEnabled = rule,
            destination = destination.trim(),
            allowedSenders = senders.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            includeKeywords = includes.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            excludeKeywords = excludes.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
        )
        val validation = if (enable && rule) SmsConfigStore.validate(updated) else null
        if (validation != null) {
            savedMessage = validation
            ruleEnabled = config.ruleEnabled
            return
        }
        try {
            SmsConfigStore.save(context, updated)
            config = updated
            savedMessage = "Settings saved on this device."
        } catch (e: Exception) {
            savedMessage = "Could not securely save settings: ${e.message ?: "unknown error"}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OTP Forwarder", fontWeight = FontWeight.Bold)
                        Text("Forward only approved OTP messages", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    Icon(Icons.Default.Security, contentDescription = "On-device security",
                        modifier = Modifier.padding(end = 16.dp))
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Forwarding control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (config.enabled) "Forwarding is ON" else "Forwarding is OFF",
                                fontWeight = FontWeight.SemiBold)
                            Text("Only messages matching every required rule can be forwarded.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { enabled ->
                                saveSettings(enable = enabled)
                            },
                            enabled = permissionsOk
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("OTP filter rule", fontWeight = FontWeight.SemiBold)
                            Text("Pause this rule without changing the master switch.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = ruleEnabled,
                            onCheckedChange = { enabled ->
                                ruleEnabled = enabled
                                saveSettings(rule = enabled)
                            },
                            enabled = permissionsOk
                        )
                    }
                    if (!permissionsOk) {
                        Text("SMS permissions are required to receive and send the selected messages.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            permissionLauncher.launch(arrayOf(
                                Manifest.permission.RECEIVE_SMS,
                                Manifest.permission.SEND_SMS
                            ))
                        }) { Text("Grant SMS permissions") }
                    }
                }
            }

            Text("Forwarding destination", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = destination, onValueChange = { destination = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Wife's mobile number") },
                placeholder = { Text("+91XXXXXXXXXX") }
            )

            Text("Allowed senders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Required. Add the exact sender IDs or phone numbers that are permitted to send OTPs. One per line.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = senders, onValueChange = { senders = it },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                placeholder = { Text("AD-BANKXX\nVM-LOGIN") }
            )

            Text("Include keywords", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Required. At least one keyword must appear in the SMS. One per line.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = includes, onValueChange = { includes = it },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                placeholder = { Text("OTP\nverification code\none time password") }
            )

            Text("Exclude keywords (optional)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("If any excluded keyword appears, the SMS will not be forwarded.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = excludes, onValueChange = { excludes = it },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                placeholder = { Text("transaction alert\nbalance") }
            )

            Button(onClick = { saveSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text("Save settings")
            }
            savedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Settings saved")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent forwarding activity", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { history = SmsConfigStore.history(context) }) { Text("Refresh") }
            }
            if (history.isEmpty()) {
                Text("No forwarding activity yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                history.forEach { item ->
                    ListItem(
                        headlineContent = { Text(item.sender) },
                        supportingContent = { Text(item.status) },
                        trailingContent = { Text(SmsConfigStore.formatTime(item.timestamp), style = MaterialTheme.typography.labelSmall) }
                    )
                    HorizontalDivider()
                }
            }

            Text(
                "Privacy and safety: SMS OTPs are authentication credentials. Keep the sender list narrow and forward only messages you are authorised to share. Activity history stores sender, time and result—not the OTP text. SMS delivery by the mobile network is not guaranteed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
