package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.data.local.SignalEntity
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(highlightSignalId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { AppContainer.signalRepository(context) }
    val history by repo.observeLiveHistory().collectAsState(initial = emptyList())
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear history?") },
            text = { Text("This deletes all live signal history. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch { repo.clearLiveHistory() }
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("No") }
            }
        )
    }

    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No live signals yet.", color = TextSecondary)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Signal History", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { showClearConfirm = true }) {
                    Text("Clear", color = RedSignal)
                }
            }
        }
        items(history, key = { it.signalId }) { entity ->
            HistoryRow(entity, highlighted = entity.signalId == highlightSignalId)
        }
    }
}

@Composable
private fun HistoryRow(entity: SignalEntity, highlighted: Boolean) {
    val directionColor = if (entity.direction == "GREEN") GreenSignal else RedSignal
    val statusColor = when (entity.status) {
        "WON" -> GreenSignal
        "LOST" -> RedSignal
        else -> TextSecondary
    }
    SectionCard(title = "") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(entity.direction, color = directionColor, style = MaterialTheme.typography.titleMedium)
                Text(entity.activeStrategyId, style = MaterialTheme.typography.bodyMedium)
                Text(
                    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date(entity.signalTimestampMillis)) + " UTC",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(entity.status, color = statusColor, style = MaterialTheme.typography.titleMedium)
                entity.pnlUsd?.let {
                    Text("${if (it >= 0) "+" else ""}$${"%.2f".format(it)}", style = MaterialTheme.typography.bodyMedium)
                }
                Text("${"%.1f".format(entity.confidencePct)}% conf.", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (highlighted) {
            Spacer(Modifier.height(6.dp))
            Text("\u2190 Opened from notification", style = MaterialTheme.typography.labelSmall)
        }
    }
}
