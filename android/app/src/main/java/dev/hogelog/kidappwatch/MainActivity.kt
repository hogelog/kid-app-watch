package dev.hogelog.kidappwatch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LaunchMonitorScheduler.enqueue(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen()
                }
            }
        }
    }
}

@Composable
private fun AppScreen() {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(AppSettings()) }
    var serverUrl by remember { mutableStateOf("") }
    var extraHeaders by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var hasUsageAccess by remember { mutableStateOf(UsageAccessHelper.hasUsageAccess(context)) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(repository) {
        repository.settings.collect { current ->
            settings = current
            serverUrl = current.serverUrl
            extraHeaders = current.extraHeaders
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Kid App Watch", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "Back" else "Settings")
            }
        }

        if (showSettings) {
            SettingsPanel(
                settings = settings,
                serverUrl = serverUrl,
                onServerUrlChange = { serverUrl = it },
                extraHeaders = extraHeaders,
                onExtraHeadersChange = { extraHeaders = it },
                hasUsageAccess = hasUsageAccess,
                status = status,
                onOpenUsageAccess = { context.startActivity(UsageAccessHelper.settingsIntent()) },
                onSave = {
                    scope.launch {
                        status = "Saving..."
                        repository.saveConnection(serverUrl = serverUrl, extraHeaders = extraHeaders)
                        LaunchMonitorScheduler.enqueue(context)
                        status = "Saved"
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        } else {
            MainPanel(
                settings = settings,
                onCheckNow = {
                    scope.launch {
                        hasUsageAccess = UsageAccessHelper.hasUsageAccess(context)
                        repository.saveCheckStatus("Checking now...")
                        LaunchMonitorScheduler.enqueue(context)
                        LaunchMonitorScheduler.enqueueCheckNow(context)
                        Toast.makeText(context, "Check queued", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenWatchPage = {
                    val baseUrl = settings.serverUrl.trim().trimEnd('/')
                    if (baseUrl.isBlank()) {
                        Toast.makeText(context, "Server URL is empty", Toast.LENGTH_SHORT).show()
                    } else {
                        val url = "$baseUrl/?device_id=${Uri.encode(settings.deviceId)}"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
            )
        }
    }
}

@Composable
private fun MainPanel(
    settings: AppSettings,
    onCheckNow: () -> Unit,
    onOpenWatchPage: () -> Unit,
) {
    if (settings.lastCheckSummary.isNotBlank()) {
        Text(settings.lastCheckSummary, style = MaterialTheme.typography.bodySmall)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onCheckNow) {
            Text("Check Now")
        }
        Button(onClick = onOpenWatchPage) {
            Text("Open watch page")
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text("Sent events", style = MaterialTheme.typography.titleMedium)
    if (settings.lastEventSummaries.isEmpty()) {
        Text("-")
    } else {
        settings.lastEventSummaries.forEach { summary ->
            Text(summary)
        }
    }
}

@Composable
private fun SettingsPanel(
    settings: AppSettings,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    extraHeaders: String,
    onExtraHeadersChange: (String) -> Unit,
    hasUsageAccess: Boolean,
    status: String,
    onOpenUsageAccess: () -> Unit,
    onSave: () -> Unit,
) {
    Text("Settings", style = MaterialTheme.typography.titleMedium)
    Text("Usage access: ${if (hasUsageAccess) "granted" else "not granted"}")
    if (!hasUsageAccess) {
        Button(onClick = onOpenUsageAccess) {
            Text("Grant usage access")
        }
    }
    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Server URL") },
        placeholder = { Text("https://example.com") },
    )
    Text("Device ID: ${settings.deviceId}", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = extraHeaders,
        onValueChange = onExtraHeadersChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        label = { Text("Headers") },
        placeholder = { Text("Header-Name: value\nAnother-Header: value") },
    )
    Button(onClick = onSave) {
        Text("Save")
    }
    if (status.isNotBlank()) {
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
