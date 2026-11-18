package com.whizkris.smsreader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OtpForwarderApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtpForwarderApp() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(SmsConfigStore.load(context)) }
    var history by remember { mutableStateOf(SmsConfigStore.history(context)) }
    var editingRule by remember { mutableStateOf<SmsRule?>(null) }
    var creatingRule by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var receiveGranted by remember { mutableStateOf(hasPermission(Manifest.permission.RECEIVE_SMS)) }
    var sendGranted by remember { mutableStateOf(hasPermission(Manifest.permission.SEND_SMS)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        receiveGranted = hasPermission(Manifest.permission.RECEIVE_SMS)
        sendGranted = hasPermission(Manifest.permission.SEND_SMS)
    }

    fun persist(updated: SmsConfig) {
        try {
            SmsConfigStore.save(context, updated)
            config = updated
            message = "Settings saved on this device."
        } catch (e: Exception) {
            message = "Could not save settings: ${e.message ?: "unknown error"}"
        }
    }

    fun setMaster(enabled: Boolean) {
        if (enabled && !receiveGranted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
            message = "Allow SMS reception, then switch forwarding on."
            return
        }
        if (enabled && config.rules.any { it.enabled && it.sendSms } && !sendGranted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.SEND_SMS))
            message = "Allow SMS sending, then switch forwarding on."
            return
        }
        if (enabled) {
            val invalid = config.rules.filter { it.enabled }.firstNotNullOfOrNull { SmsConfigStore.validate(it) }
            if (invalid != null) {
                message = invalid
                return
            }
            if (config.rules.none { it.enabled }) {
                message = "Enable at least one rule before turning forwarding on."
                return
            }
        }
        persist(config.copy(enabled = enabled))
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF26C6B8),
            secondary = androidx.compose.ui.graphics.Color(0xFF58A6FF),
            background = androidx.compose.ui.graphics.Color(0xFF0B1220),
            surface = androidx.compose.ui.graphics.Color(0xFF121D2E)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("SMS Relay", fontWeight = FontWeight.Bold)
                            Text("Rules • Actions • Webhooks", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        Icon(Icons.Default.Security, contentDescription = "Encrypted settings",
                            modifier = Modifier.padding(end = 16.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { creatingRule = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add rule")
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Forwarding", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(if (config.enabled) "Active — incoming SMS are checked against enabled rules"
                                    else "Paused — incoming SMS are not forwarded",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = config.enabled, onCheckedChange = ::setMaster)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = { Text("${config.rules.size} total rules") })
                            AssistChip(onClick = {}, label = { Text("${config.rules.count { it.enabled }} enabled") })
                        }
                        if (!receiveGranted || (config.enabled && config.rules.any { it.enabled && it.sendSms } && !sendGranted)) {
                            Text("Permissions needed to run the active actions.", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                val missing = buildList {
                                    if (!receiveGranted) add(Manifest.permission.RECEIVE_SMS)
                                    if (config.rules.any { it.enabled && it.sendSms } && !sendGranted) add(Manifest.permission.SEND_SMS)
                                }
                                if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
                            }) { Text("Grant permissions") }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automation rules", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Every incoming SMS is evaluated against every active rule.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { creatingRule = true }) {
                        Icon(Icons.Default.Add, null)
                        Text("Add")
                    }
                }

                if (config.rules.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No rules configured", fontWeight = FontWeight.SemiBold)
                            Text("Create a rule using a sender pattern, message-body keyword, or both. Either matching criterion is enough.",
                                style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { creatingRule = true }) { Text("Create first rule") }
                        }
                    }
                } else {
                    config.rules.forEach { rule ->
                        RuleCard(
                            rule = rule,
                            onToggle = { enabled ->
                                if (enabled && config.enabled && rule.sendSms && !sendGranted) {
                                    message = "Allow SMS sending before activating this rule."
                                    permissionLauncher.launch(arrayOf(Manifest.permission.SEND_SMS))
                                } else {
                                    val changed = rule.copy(enabled = enabled)
                                    val error = if (enabled) SmsConfigStore.validate(changed) else null
                                    if (error != null) message = error
                                    else persist(config.copy(rules = config.rules.map { if (it.id == rule.id) changed else it }))
                                }
                            },
                            },
                            onEdit = { editingRule = rule },
                            onDelete = {
                                persist(config.copy(rules = config.rules.filterNot { it.id == rule.id }))
                            }
                        )
                    }
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { history = SmsConfigStore.history(context) }) { Text("Refresh") }
                }
                if (history.isEmpty()) {
                    Text("No matching messages processed yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    history.take(20).forEach { event ->
                        ListItem(
                            headlineContent = { Text(event.ruleName, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("${event.sender} • ${event.status}") },
                            trailingContent = { Text(SmsConfigStore.formatTime(event.timestamp), style = MaterialTheme.typography.labelSmall) }
                        )
                        HorizontalDivider()
                    }
                }
                Text(
                    "Privacy: rule settings and webhook headers are encrypted on this device. Message bodies are not written to activity history. A matching rule sends the full SMS content to its configured destinations, so only configure destinations and webhooks you trust.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(76.dp))
            }
        }
    }

    if (creatingRule || editingRule != null) {
        MaterialTheme(colorScheme = darkColorScheme()) {
        RuleEditorDialog(
            initial = editingRule ?: SmsRule(name = "Rule ${config.rules.size + 1}"),
            onDismiss = { creatingRule = false; editingRule = null },
            onSave = { rule ->
                val updatedRules = if (config.rules.any { it.id == rule.id })
                    config.rules.map { if (it.id == rule.id) rule else it }
                else config.rules + rule
                persist(config.copy(rules = updatedRules))
                creatingRule = false
                editingRule = null
            }
        )
        }
    }

    LaunchedEffect(Unit) {
        if (!receiveGranted) permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
    }
}

@Composable
private fun RuleCard(rule: SmsRule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (rule.enabled) "Rule active" else "Rule paused",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            val sender = rule.senderPatterns.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "not set"
            val body = rule.bodyKeywords.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "not set"
            Text("MATCH: sender [$sender] OR body [$body]", style = MaterialTheme.typography.bodySmall)
            if (rule.excludeKeywords.isNotEmpty())
                Text("EXCLUDE: ${rule.excludeKeywords.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rule.sendSms) AssistChip(onClick = {}, label = { Text("SMS • ${rule.smsDestinations.size} destination(s)") })
                if (rule.sendWebhook) AssistChip(onClick = {}, label = { Text("Webhook") })
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDelete) { Icon(Icons.Default.Delete, null); Text("Delete") }
                TextButton(onClick = onEdit) { Icon(Icons.Default.Edit, null); Text("Edit") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorDialog(initial: SmsRule, onDismiss: () -> Unit, onSave: (SmsRule) -> Unit) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }
    var senders by remember(initial.id) { mutableStateOf(initial.senderPatterns.joinToString("\n")) }
    var body by remember(initial.id) { mutableStateOf(initial.bodyKeywords.joinToString("\n")) }
    var excludes by remember(initial.id) { mutableStateOf(initial.excludeKeywords.joinToString("\n")) }
    var sendSms by remember(initial.id) { mutableStateOf(initial.sendSms) }
    var destinations by remember(initial.id) { mutableStateOf(initial.smsDestinations.joinToString("\n")) }
    var sendWebhook by remember(initial.id) { mutableStateOf(initial.sendWebhook) }
    var webhookUrl by remember(initial.id) { mutableStateOf(initial.webhookUrl) }
    var headers by remember(initial.id) { mutableStateOf(initial.webhookHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }) }
    var showHeaders by remember(initial.id) { mutableStateOf(false) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rule settings") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Rule name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rule enabled", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text("MATCH CONDITIONS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Sender patterns (optional). Wildcards * and ? are supported. Example: *SBIBNK*",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(senders, { senders = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    placeholder = { Text("*SBIBNK*\\nAD-HDFCBK") })
                Text("Message-body keywords (optional). One per line; any one matching is sufficient.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(body, { body = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    placeholder = { Text("OTP\\nverification code\\nlogin code") })
                Text("A sender match OR a body match triggers this rule. If both are entered, either is sufficient.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Text("Exclude keywords (optional; always block if found)", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(excludes, { excludes = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                HorizontalDivider()
                Text("ACTIONS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Send SMS", modifier = Modifier.weight(1f))
                    Switch(checked = sendSms, onCheckedChange = { sendSms = it })
                }
                if (sendSms) {
                    Text("Destination numbers (one per line)", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(destinations, { destinations = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                        placeholder = { Text("+919876543210") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Send to webhook", modifier = Modifier.weight(1f))
                    Switch(checked = sendWebhook, onCheckedChange = { sendWebhook = it })
                }
                if (sendWebhook) {
                    OutlinedTextField(webhookUrl, { webhookUrl = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("HTTPS webhook URL") }, singleLine = true)
                    Text("Custom headers (one per line, Name: Value). Values are encrypted on this device.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(headers, { headers = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                        visualTransformation = if (showHeaders) VisualTransformation.None else PasswordVisualTransformation(),
                        placeholder = { Text("Authorization: Bearer your-secret\\nX-API-Key: your-key") })
                    TextButton(onClick = { showHeaders = !showHeaders }) {
                        Text(if (showHeaders) "Hide header values" else "Show header values")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val headerMap = headers.lines().mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
                }.filter { it.first.isNotBlank() }.toMap()
                val rule = initial.copy(
                    name = name.trim().ifBlank { "Unnamed rule" },
                    enabled = enabled,
                    senderPatterns = senders.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                    bodyKeywords = body.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                    excludeKeywords = excludes.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                    sendSms = sendSms,
                    smsDestinations = destinations.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                    sendWebhook = sendWebhook,
                    webhookUrl = webhookUrl.trim(),
                    webhookHeaders = headerMap
                )
                val validation = if (enabled) SmsConfigStore.validate(rule) else null
                if (validation != null) error = validation else onSave(rule)
            }) { Text("Save rule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
