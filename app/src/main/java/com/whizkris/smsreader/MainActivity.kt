package com.whizkris.smsreader

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SmsMessage(
    val sender: String,
    val body: String,
    val date: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmsReaderScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsReaderScreen() {
    val context = LocalContext.current

    var messages by remember { mutableStateOf(emptyList<SmsMessage>()) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadMessages() {
        if (!hasPermission) return

        try {
            val result = mutableListOf<SmsMessage>()
            val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI

            val cursor: Cursor? = context.contentResolver.query(
                uri,
                arrayOf("address", "body", "date"),
                null,
                null,
                "date DESC"
            )

            cursor?.use {
                val addressIndex = it.getColumnIndex("address")
                val bodyIndex = it.getColumnIndex("body")
                val dateIndex = it.getColumnIndex("date")

                while (it.moveToNext()) {
                    result.add(
                        SmsMessage(
                            sender = it.getString(addressIndex) ?: "Unknown",
                            body = it.getString(bodyIndex) ?: "",
                            date = it.getLong(dateIndex)
                        )
                    )
                }
            }

            messages = result
            errorMessage = null
        } catch (e: Exception) {
            errorMessage = "Unable to read messages: ${e.message}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) loadMessages()
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) loadMessages()
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "SMS Reader",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { loadMessages() },
                            enabled = hasPermission
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh"
                            )
                        }
                    }
                )
            }
        ) { padding ->

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {

                Text(
                    text = if (hasPermission)
                        "${messages.size} messages"
                    else
                        "SMS access required",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (!hasPermission) {
                    Text(
                        "Allow SMS access to display messages stored in your phone's inbox."
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.READ_SMS)
                        }
                    ) {
                        Text("Grant SMS Permission")
                    }

                } else if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No SMS messages found.")
                    }

                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages) { message ->
                            SmsCard(message)
                        }
                    }
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun SmsCard(message: SmsMessage) {
    val formattedDate = remember(message.date) {
        SimpleDateFormat(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        ).format(Date(message.date))
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = message.sender,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = formattedDate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
