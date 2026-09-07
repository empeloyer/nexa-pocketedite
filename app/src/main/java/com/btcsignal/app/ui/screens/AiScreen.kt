package com.btcsignal.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.ai.ModelState
import com.btcsignal.app.data.repository.AppSettings
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.theme.AiInfoYellow
import kotlinx.coroutines.launch

/**
 * Dedicated "AI" panel (own bottom-nav tab). Previously the AI Model card lived at the
 * bottom of the Settings screen; it now has its own home here, alongside the
 * user-adjustable AI Confidence Threshold.
 */
@Composable
fun AiScreen() {
    val context = LocalContext.current
    val settingsRepo = remember { AppContainer.settingsRepository(context) }
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settingsFlow.collectAsState(initial = AppSettings())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AI", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        AiConfidenceThresholdSection(
            threshold = settings.aiConfidenceThreshold,
            onThresholdChange = { scope.launch { settingsRepo.setAiConfidenceThreshold(it) } }
        )

        Spacer(Modifier.height(16.dp))
        AiModelSection()
    }
}

@Composable
private fun AiConfidenceThresholdSection(threshold: Float, onThresholdChange: (Float) -> Unit) {
    // Local slider position so the thumb tracks the drag smoothly; committed to the
    // repository (and clamped there) once the user releases it.
    var sliderValue by remember(threshold) { mutableStateOf(threshold) }

    SectionCard("AI Confidence Threshold") {
        Text(
            "Minimum confidence the AI model must reach before it fires a signal. " +
                "This is an extra floor on top of the model's own calibration - raising " +
                "it makes the AI more selective; it can never re-enable a zone the " +
                "model's calibration found no real edge for.",
            style = MaterialTheme.typography.bodySmall,
            color = AiInfoYellow
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Threshold", style = MaterialTheme.typography.bodyLarge)
            Text("${(sliderValue * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onThresholdChange(sliderValue) },
            valueRange = 0.50f..0.95f,
            steps = 8 // 0.05 increments between 0.50 and 0.95
        )
    }
}

@Composable
private fun AiModelSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelManager = remember { AppContainer.modelManager(context) }
    val state by modelManager.state.collectAsState()
    val statusMessage by modelManager.statusMessage.collectAsState()
    var busy by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                modelManager.importFromUri(uri)
                busy = false
            }
        }
    }

    SectionCard("AI Model") {
        Text(
            when (state) {
                ModelState.MODEL_NOT_INSTALLED -> "No model installed"
                ModelState.VERIFYING -> "Verifying\u2026"
                ModelState.VERIFICATION_FAILED -> "Verification failed"
                ModelState.LOADED, ModelState.INFERENCE_READY -> "Active: ${modelManager.activeManifest?.modelVersion ?: "?"}"
                ModelState.LOAD_FAILED -> "Load failed"
            },
            style = MaterialTheme.typography.titleMedium
        )
        Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = AiInfoYellow)
        if (state == ModelState.INFERENCE_READY) {
            Text(
                "The engine now decides GREEN/RED using this model (with the 27 " +
                    "strategies feeding it as inputs only). Uninstalling/rolling back " +
                    "reverts to the original 27-strategy engine.",
                style = MaterialTheme.typography.bodySmall,
                color = AiInfoYellow
            )
            modelManager.activeThresholds?.let {
                Text(
                    if (it.adverseEntriesEnabled)
                        "Adverse-reversal entries: enabled (min confidence ${"%.2f".format(it.adverseMinConfidence)})"
                    else
                        "Adverse-reversal entries: disabled (this model's calibration found no confidence level with a real edge against current momentum)",
                    style = MaterialTheme.typography.bodySmall,
                    color = AiInfoYellow
                )
            }
        } else {
            Text(
                "Using the original 27-strategy rule engine directly (safe fallback).",
                style = MaterialTheme.typography.bodySmall,
                color = AiInfoYellow
            )
        }
        Spacer(Modifier.height(8.dp))
        // Stacked full-width buttons instead of a plain Row: the "Import
        // NEXA_MODEL_PACKAGE.zip" label is long enough that sharing a Row with "Roll
        // back" squeezed the second button down to a sliver, wrapping its text one
        // letter per line. Full-width rows avoid that regardless of label length.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy,
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Working\u2026" else "Import NEXA_MODEL_PACKAGE.zip")
            }
            OutlinedButton(
                enabled = !busy && state == ModelState.INFERENCE_READY,
                onClick = {
                    busy = true
                    scope.launch { modelManager.rollback(); busy = false }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Roll back") }
        }
    }
}
