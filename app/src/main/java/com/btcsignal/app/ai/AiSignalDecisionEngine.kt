package com.btcsignal.app.ai

/**
 * The actual decision-maker once a model is INFERENCE_READY. CoreSignalEngine.kt calls
 * this with the raw ONNX score; everything about WHETHER to fire and in which entry
 * context lives here so it's independently testable from the Compose/Room/live-service
 * plumbing around it.
 */
object AiSignalDecisionEngine {

    fun decide(
        rawScore: Double,
        entryMovePct: Double,
        entryRangeGreen: ClosedFloatingPointRange<Double>,
        entryRangeRed: ClosedFloatingPointRange<Double>,
        calibration: CalibrationTable,
        thresholds: AdverseThresholdCurve,
        adaptive: AdaptiveLearningEngine?,
        modelVersion: String,
        userMinConfidence: Double? = null,
    ): AiDecision {
        val calibrated = calibration.apply(rawScore).coerceIn(0.0, 1.0)
        val contextForGreen = classify(entryMovePct, isGreen = true, entryRangeGreen, entryRangeRed)
        val contextForRed = classify(entryMovePct, isGreen = false, entryRangeGreen, entryRangeRed)

        val callGreen = calibrated >= 0.5
        val confidence = if (callGreen) calibrated else 1.0 - calibrated
        val contextOfCall = if (callGreen) contextForGreen else contextForRed

        if (contextOfCall == EntryContext.TOO_LATE_DIRECTIONAL_ENTRY) {
            return AiDecision(rawScore, calibrated, entryMovePct, contextForGreen, contextForRed, null, confidence,
                "TOO_LATE: entry move ${"%.4f".format(entryMovePct)}%% is beyond the entry zone in the same direction - never fired regardless of confidence.",
                modelVersion)
        }

        val isAdverse = contextOfCall == EntryContext.ADVERSE_REVERSAL_ENTRY
        val baseMin = if (isAdverse) thresholds.adverseMinConfidence else thresholds.normalMinConfidence
        val adaptedMin = adaptive?.effectiveMinConfidence(baseMin, isAdverse, thresholds.breakevenWinRate, thresholds.margin) ?: baseMin
        // The user's "AI Confidence Threshold" slider (Settings) is an EXTRA floor on
        // top of the model's own calibration - it can only make the bar stricter, never
        // looser, and it never re-enables a zone the calibration disabled (adaptedMin
        // == null stays null / disabled regardless of this value).
        val effectiveMin = if (adaptedMin != null && userMinConfidence != null) maxOf(adaptedMin, userMinConfidence) else adaptedMin

        if (effectiveMin == null) {
            val zoneLabel = if (isAdverse) "ADVERSE_REVERSAL" else "NORMAL"
            return AiDecision(rawScore, calibrated, entryMovePct, contextForGreen, contextForRed, null, confidence,
                "$zoneLabel entries are currently disabled (no confidence bucket clears breakeven+margin in calibration data).",
                modelVersion)
        }

        if (confidence < effectiveMin) {
            return AiDecision(rawScore, calibrated, entryMovePct, contextForGreen, contextForRed, null, confidence,
                "confidence ${"%.3f".format(confidence)} below required ${"%.3f".format(effectiveMin)} for $contextOfCall.",
                modelVersion)
        }

        val action = if (callGreen) SignalDirection.GREEN else SignalDirection.RED
        return AiDecision(rawScore, calibrated, entryMovePct, contextForGreen, contextForRed, action, confidence,
            "fired $action at confidence ${"%.3f".format(confidence)} ($contextOfCall).", modelVersion)
    }

    private fun classify(
        movePct: Double, isGreen: Boolean,
        greenRange: ClosedFloatingPointRange<Double>, redRange: ClosedFloatingPointRange<Double>,
    ): EntryContext = if (isGreen) {
        when {
            movePct in greenRange -> EntryContext.NORMAL_DIRECTIONAL_ENTRY
            movePct < greenRange.start -> EntryContext.ADVERSE_REVERSAL_ENTRY
            else -> EntryContext.TOO_LATE_DIRECTIONAL_ENTRY
        }
    } else {
        when {
            movePct in redRange -> EntryContext.NORMAL_DIRECTIONAL_ENTRY
            movePct > redRange.endInclusive -> EntryContext.ADVERSE_REVERSAL_ENTRY
            else -> EntryContext.TOO_LATE_DIRECTIONAL_ENTRY
        }
    }
}
