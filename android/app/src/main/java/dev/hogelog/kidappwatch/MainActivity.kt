package dev.hogelog.kidappwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
    var deviceId by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var extraHeaderName1 by remember { mutableStateOf("") }
    var extraHeaderValue1 by remember { mutableStateOf("") }
    var extraHeaderName2 by remember { mutableStateOf("") }
    var extraHeaderValue2 by remember { mutableStateOf("") }
    var hasUsageAccess by remember { mutableStateOf(UsageAccessHelper.hasUsageAccess(context)) }

    LaunchedEffect(repository) {
        repository.settings.collect { current ->
            settings = current
            serverUrl = current.serverUrl
            deviceId = current.deviceId
            apiToken = current.apiToken
            extraHeaderName1 = current.extraHeaderName1
            extraHeaderValue1 = current.extraHeaderValue1
            extraHeaderName2 = current.extraHeaderName2
            extraHeaderValue2 = current.extraHeaderValue2
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Kid App Watch", style = MaterialTheme.typography.headlineMedium)
        Text("Usage access: ${if (hasUsageAccess) "granted" else "not granted"}")

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
                    hasUsageAccess = UsageAccessHelper.hasUsageAccess(context)
                },
            ) {
                Text("Refresh")
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
        OutlinedTextField(
            value = deviceId,
            onValueChange = { deviceId = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Device ID") },
        )
        OutlinedTextField(
            value = apiToken,
            onValueChange = { apiToken = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("API token") },
        )
        OutlinedTextField(
            value = extraHeaderName1,
            onValueChange = { extraHeaderName1 = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Extra header 1 name") },
        )
        OutlinedTextField(
            value = extraHeaderValue1,
            onValueChange = { extraHeaderValue1 = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("Extra header 1 value") },
        )
        OutlinedTextField(
            value = extraHeaderName2,
            onValueChange = { extraHeaderName2 = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Extra header 2 name") },
        )
        OutlinedTextField(
            value = extraHeaderValue2,
            onValueChange = { extraHeaderValue2 = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("Extra header 2 value") },
        )

        Button(
            onClick = {
                scope.launch {
                    repository.saveConnection(
                        serverUrl = serverUrl,
                        deviceId = deviceId,
                        apiToken = apiToken,
                        extraHeaderName1 = extraHeaderName1,
                        extraHeaderValue1 = extraHeaderValue1,
                        extraHeaderName2 = extraHeaderName2,
                        extraHeaderValue2 = extraHeaderValue2,
                    )
                    LaunchMonitorScheduler.enqueue(context)
                }
            },
        ) {
            Text("Save")
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Last sent event", style = MaterialTheme.typography.titleMedium)
        Text(settings.lastEventSummary.ifBlank { "-" })
    }
}
