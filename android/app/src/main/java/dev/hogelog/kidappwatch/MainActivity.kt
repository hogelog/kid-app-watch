package dev.hogelog.kidappwatch

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LaunchMonitorScheduler.enqueue(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(AppSettings()) }
    var serverUrl by remember { mutableStateOf("") }
    var extraHeaders by remember { mutableStateOf("") }
    var saveStatus by remember { mutableStateOf("") }
    var testStatus by remember { mutableStateOf("") }
    var hasUsageAccess by remember { mutableStateOf(UsageAccessHelper.hasUsageAccess(context)) }

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
        Text("Kid App Watch", style = MaterialTheme.typography.headlineMedium)
        Text("Usage access: ${if (hasUsageAccess) "granted" else "not granted"}")
        if (saveStatus.isNotBlank()) {
            Text(saveStatus, style = MaterialTheme.typography.bodySmall)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    context.startActivity(UsageAccessHelper.settingsIntent())
                },
            ) {
                Text("Open usage access")
            }
            Button(
                onClick = {
                    scope.launch {
                        hasUsageAccess = UsageAccessHelper.hasUsageAccess(context)
                        saveStatus = "Checking now..."
                        repository.saveConnection(
                            serverUrl = serverUrl,
                            extraHeaders = extraHeaders,
                        )
                        LaunchMonitorScheduler.enqueue(context)
                        LaunchMonitorScheduler.enqueueCheckNow(context)
                        Toast.makeText(context, "Check queued", Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Text("Check Now")
            }
        }

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Server URL") },
            placeholder = { Text("https://example.com") },
        )
        Text("Device ID: ${settings.deviceId}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = extraHeaders,
            onValueChange = { extraHeaders = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text("Headers") },
            placeholder = { Text("Header-Name: value\nAnother-Header: value") },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        saveStatus = "Saving..."
                        repository.saveConnection(
                            serverUrl = serverUrl,
                            extraHeaders = extraHeaders,
                        )
                        LaunchMonitorScheduler.enqueue(context)
                        saveStatus = "Saved"
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Text("Save")
            }
            Button(
                onClick = {
                    scope.launch {
                        testStatus = "Testing..."
                        repository.saveConnection(
                            serverUrl = serverUrl,
                            extraHeaders = extraHeaders,
                        )

                        val testSettings = settings.copy(
                            serverUrl = serverUrl.trim().trimEnd('/'),
                            extraHeaders = extraHeaders.trim(),
                        )
                        runCatching {
                            withContext(Dispatchers.IO) {
                                ApiClient().fetchConfig(testSettings).size
                            }
                        }.fold(
                            onSuccess = { count ->
                                testStatus = "OK: $count watched apps"
                                LaunchMonitorScheduler.enqueue(context)
                            },
                            onFailure = { error ->
                                testStatus = "Failed: ${error.message ?: error::class.java.simpleName}"
                            },
                        )
                    }
                },
            ) {
                Text("Test")
            }
        }
        if (testStatus.isNotBlank()) {
            Text(testStatus)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Last sent event", style = MaterialTheme.typography.titleMedium)
        Text(settings.lastEventSummary.ifBlank { "-" })
    }
}
