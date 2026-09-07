package com.btcsignal.app.ai

/**
 * Lifecycle of the imported ONNX model package. Mirrors 01_ANDROID_INTEGRATION_PROMPT.md
 * section 2. MODEL_NOT_INSTALLED is the state a fresh install starts in - in that state
 * CoreSignalEngine.evaluateCheckpoint() falls back to the original 27-strategy-only
 * behaviour unchanged (see CoreSignalEngine.kt "AI fallback" note).
 */
enum class ModelState {
    MODEL_NOT_INSTALLED,
    VERIFYING,
    VERIFICATION_FAILED,
    LOADED,
    LOAD_FAILED,
    INFERENCE_READY,
}

data class FeatureSchema(
    val schemaVersion: Int,
    val inputTensorName: String,
    val outputTensorName: String,
    val featureOrder: List<String>,
) {
    val featureCount: Int get() = featureOrder.size
}

data class ModelManifest(
    val packageFormatVersion: Int,
    val modelName: String,
    val modelVersion: String,
    val asset: String,
    val targetTimeframe: String,
    val fileChecksums: Map<String, String>, // filename -> sha256 hex
)

/** One point of the piecewise-linear isotonic calibration curve (raw model score -> calibrated P(GREEN)). */
data class CalibrationPoint(val raw: Double, val calibrated: Double)

data class CalibrationTable(val points: List<CalibrationPoint>) {
    /** Linear interpolation between the two nearest points; clamps at the ends. */
    fun apply(rawScore: Double): Double {
        if (points.isEmpty()) return rawScore
        if (rawScore <= points.first().raw) return points.first().calibrated
        if (rawScore >= points.last().raw) return points.last().calibrated
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            if (rawScore in a.raw..b.raw) {
                if (b.raw == a.raw) return a.calibrated
                val t = (rawScore - a.raw) / (b.raw - a.raw)
                return a.calibrated + t * (b.calibrated - a.calibrated)
            }
        }
        return rawScore
    }
}

data class ThresholdBucket(val confidenceLo: Double, val confidenceHi: Double, val n: Int, val empiricalWinRate: Double, val clearsBreakevenPlusMargin: Boolean)

/**
 * Empirically-derived, data-calibrated confidence requirements for NORMAL vs ADVERSE
 * entry contexts (02_PC_HTML_TRAINER_PROMPT.md section on risk-adaptive thresholds).
 * `adverseMinConfidence == null` means NO confidence bucket in the training data's
 * adverse zone cleared breakeven+margin, so adverse-reversal entries are disabled - this
 * is an honest finding, not a placeholder (see training_report.json "known_limitation").
 */
data class AdverseThresholdCurve(
    val breakevenWinRate: Double,
    val margin: Double,
    val normalMinConfidence: Double?,
    val adverseMinConfidence: Double?,
) {
    val adverseEntriesEnabled: Boolean get() = adverseMinConfidence != null
}

enum class EntryContext { NORMAL_DIRECTIONAL_ENTRY, ADVERSE_REVERSAL_ENTRY, TOO_LATE_DIRECTIONAL_ENTRY }

enum class SignalDirection { GREEN, RED }

/** What the AI decision layer concluded for one checkpoint. `null` action means NO_SIGNAL/WAIT. */
data class AiDecision(
    val rawScore: Double,
    val calibratedProbabilityGreen: Double,
    val entryMovePct: Double,
    val entryContextForGreen: EntryContext,
    val entryContextForRed: EntryContext,
    val action: SignalDirection?,
    val confidence: Double,
    val reason: String,
    val modelVersion: String,
)
