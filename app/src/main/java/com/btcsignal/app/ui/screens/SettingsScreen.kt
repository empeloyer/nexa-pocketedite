package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.data.repository.AppSettings
import com.btcsignal.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settingsRepo = remember { AppContainer.settingsRepository(context) }
    val notificationHelper = remember { AppContainer.notificationHelper(context) }
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settingsFlow.collectAsState(initial = AppSettings())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        SectionCard("Notifications") {
            SettingRow("Notifications", settings.notificationsEnabled) {
                scope.launch { settingsRepo.setNotificationsEnabled(it) }
            }
            SettingRow("Sound", settings.soundEnabled) {
                scope.launch { settingsRepo.setSoundEnabled(it) }
            }
            SettingRow("Vibration", settings.vibrationEnabled) {
                scope.launch { settingsRepo.setVibrationEnabled(it) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    notificationHelper.sendTestNotification(settings.soundEnabled, settings.vibrationEnabled)
                }) { Text("Test Notification") }
                OutlinedButton(onClick = {
                    notificationHelper.sendTestNotification(soundEnabled = true, vibrationEnabled = settings.vibrationEnabled)
                }) { Text("Test Sound") }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
