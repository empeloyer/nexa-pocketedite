package com.btcsignal.app.engine

import com.btcsignal.app.ai.AiDecision
import com.btcsignal.app.ai.AiFeatureExtractor
import com.btcsignal.app.ai.AiSignalDecisionEngine
import com.btcsignal.app.ai.AdaptiveLearningEngine
import com.btcsignal.app.ai.ModelManager
import com.btcsignal.app.ai.SignalDirection
import com.btcsignal.app.data.model.*
import java.time.Instant
import java.util.UUID

data class EngineResult(
    val signal: Signal?,
    val trace: DebugTraceEntry
)

/**
 * The ONE Core Signal Engine (spec sections 11, 13, 39). LiveMonitoringService and
 * BacktestEngine both call [evaluateCheckpoint] with data of the same shape — the
 * former sourced from the Binance WebSocket, the latter from Binance historical REST
 * klines replayed in order — and get identical decisions for identical inputs. No
 * strategy logic is duplicated anywhere else in the app.
 *
 * AI INTEGRATION (2026 update, see PATCH_NOTES.md "Android integration" for the full
 * writeup): [aiModelManager] is optional and defaults to null. When null, or when no
 * model package has been imported and verified yet (ModelState != INFERENCE_READY),
 * this function's behaviour is BYTE-FOR-BYTE IDENTICAL to before this update — the
 * original 27-strategy rule engine decides everything, exactly as in the shipped v1
 * app. This is the "AI fallback" contract required by 01_ANDROID_INTEGRATION_PROMPT.md
 * section 3: the rule engine is always a safe, working fallback, never removed.
 *
 * Once a model is INFERENCE_READY, the 27 strategies STOP being the decision-maker:
 * their votes are computed only as input features to the AI model (see
 * AiFeatureExtractor) and as diagnostic trace detail. The AI model's calibrated
 * probability, filtered through AiSignalDecisionEngine's entry-context policy
 * (NORMAL / ADVERSE_REVERSAL / TOO_LATE, see that file), decides GREEN / RED / NO_SIGNAL.
 *
 * Order of operations for the legacy (fallback) path exactly matches spec section 7:
 *   1. Hard Constraints (entry range, per candidate direction)
 *   2. Strategy conditions (AND of all components, same direction)
 *   3. Market Regime (strategy's regime_gate vs current 3-dimension regime state)
 *   4. Strategy Score (Dynamic Score)
 *   5. Conflict Resolver
 *   6. Build + lock Signal
 */
object CoreSignalEngine {

    fun evaluateCheckpoint(
        database: StrategyDatabase,
        store: MarketDataStore,
        candleOpenTimeMillis: Long,
        candleOpen: Double,
        checkpoint: Checkpoint,
        referencePrice: Double,
        minute1Candle: Candle?,
        minute2Candle: Candle?,
        timestampMillis: Long,
        statsProvider: (String) -> RecentWindowStats,
        blockedStrategyIds: Set<String> = emptySet(),
        aiModelManager: ModelManager? = null,
        adaptiveLearning: AdaptiveLearningEngine? = null,
        userMinConfidence: Double? = null,
    ): EngineResult {
        val candleId = Instant.ofEpochMilli(candleOpenTimeMillis).toString()
        val movePct = if (candleOpen == 0.0) 0.0 else (referencePrice - candleOpen) / candleOpen * 100.0

        val closed5m = store.closed5m()
        val regime = MarketRegimeClassifier.classify(closed5m)

        if (regime == null) {
            val trace = DebugTraceEntry(
                candleId, timestampMillis, checkpoint, referencePrice, movePct,
                null, emptyList(), "N/A - insufficient regime data", "NO_SIGNAL: insufficient historical data to classify market regime", false
            )
            return EngineResult(null, trace)
        }

        val ctx = EvalContext(store, candleOpen, referencePrice, checkpoint, minute1Candle, minute2Candle)
        val strategyTraces = ArrayList<StrategyTraceEntry>()
        val votes = ArrayList<StrategyVote>()

        for (strategy in database.strategies) {
            if (strategy.id in blockedStrategyIds) {
                strategyTraces.add(StrategyTraceEntry(strategy.id, false, emptyList(), emptyList(), false, null, null))
                continue
            }
            val eligible = strategy.regimeGateCode == "All" || strategy.regimeGateCode in regime.activeCodes()
            if (!eligible) {
                strategyTraces.add(StrategyTraceEntry(strategy.id, false, emptyList(), emptyList(), false, null, null))
                continue
            }

            val results = strategy.components.map { ComponentEvaluator.evaluate(it, ctx) }
            val dirs = results.map { it.direction }
            val nonNullDirs = dirs.filterNotNull().toSet()
            val allFired = dirs.none { it == null } && nonNullDirs.size == 1
            val firedDirection = if (allFired) nonNullDirs.first() else null

            var passesEntryRange = false
            if (firedDirection != null) {
                val range = if (firedDirection == Direction.GREEN)
                    database.hardConstraints.entryRangeGreenPct else database.hardConstraints.entryRangeRedPct
                passesEntryRange = movePct in range
            }

            val finalFired = allFired && passesEntryRange
            var score: Double? = null
            if (finalFired && firedDirection != null) {
                score = DynamicScore.compute(strategy, statsProvider(strategy.id))
                votes.add(StrategyVote(strategy, firedDirection, score))
            }

            strategyTraces.add(
                StrategyTraceEntry(
                    strategyId = strategy.id,
                    regimeEligible = true,
                    componentDetails = results.map { it.detail },
                    componentDirections = dirs,
                    fired = finalFired,
                    firedDirection = if (finalFired) firedDirection else null,
                    score = score
                )
            )
        }

        // ------------------------------------------------------------------
        // AI path: only taken when a verified model is actually loaded AND we have at
        // least minute1Candle (features need it). Otherwise falls through to the
        // original rule-engine ConflictResolver path below, unchanged.
        // ------------------------------------------------------------------
        val inferenceEngine = aiModelManager?.currentInferenceEngine()
        val schema = aiModelManager?.activeSchema
        val calibration = aiModelManager?.activeCalibration
        val thresholds = aiModelManager?.activeThresholds
        val manifest = aiModelManager?.activeManifest
        if (inferenceEngine != null && schema != null && calibration != null && thresholds != null && minute1Candle != null) {
            val features = AiFeatureExtractor.extract(
                store, candleOpen, referencePrice, checkpoint, minute1Candle, minute2Candle, database
            )
            val rawResult = inferenceEngine.predictRaw(features)
            val rawScore = rawResult.getOrNull()
            if (rawScore != null) {
                val decision: AiDecision = AiSignalDecisionEngine.decide(
                    rawScore, movePct,
                    database.hardConstraints.entryRangeGreenPct, database.hardConstraints.entryRangeRedPct,
                    calibration, thresholds, adaptiveLearning, manifest?.modelVersion ?: "unknown",
                    userMinConfidence
                )
                val aiSummary = "AI: raw=${"%.4f".format(decision.rawScore)} cal=${"%.4f".format(decision.calibratedProbabilityGreen)} - ${decision.reason}"
                if (decision.action == null) {
                    val trace = DebugTraceEntry(
                        candleId, timestampMillis, checkpoint, referencePrice, movePct,
                        regime, strategyTraces, aiSummary, "NO_SIGNAL: $aiSummary", false
                    )
                    return EngineResult(null, trace)
                }
                val directionModel = if (decision.action == SignalDirection.GREEN) Direction.GREEN else Direction.RED
                val signal = Signal(
                    signalId = UUID.randomUUID().toString(),
                    candleId = candleId,
                    candleOpenTimeMillis = candleOpenTimeMillis,
                    signalTimestampMillis = timestampMillis,
                    candleOpen = candleOpen,
                    signalPrice = referencePrice,
                    direction = directionModel,
                    activeStrategyId = "AI_MODEL",
                    activeStrategyName = "NEXA AI model (${manifest?.modelVersion ?: "unknown"})",
                    marketRegime = regime,
                    strategyScore = decision.confidence,
                    confidencePct = decision.confidence * 100.0,
                    entryMovePct = movePct,
                    checkpoint = checkpoint,
                    decisionSource = "AI_MODEL",
                    aiRawScore = decision.rawScore,
                    aiCalibratedProbability = decision.calibratedProbabilityGreen,
                    aiEntryContext = (if (directionModel == Direction.GREEN) decision.entryContextForGreen else decision.entryContextForRed).name,
                    aiModelVersion = manifest?.modelVersion,
                )
                val trace = DebugTraceEntry(
                    candleId, timestampMillis, checkpoint, referencePrice, movePct,
                    regime, strategyTraces, aiSummary,
                    "SIGNAL_GENERATED (AI): $directionModel (confidence=${"%.4f".format(decision.confidence)})", true
                )
                return EngineResult(signal, trace)
            }
            // inference failed (rawResult.isFailure) -> fall through to rule engine below
            // rather than silently produce NO_SIGNAL forever; the rule engine is always
            // a safe fallback per the class doc above.
        }

        // ------------------------------------------------------------------
        // Legacy rule-engine path (also the fallback when AI isn't ready/available).
        // ------------------------------------------------------------------
        val resolution = ConflictResolver.resolve(votes)

        return when (resolution) {
            is ConflictResolution.NoSignal -> {
                val trace = DebugTraceEntry(
                    candleId, timestampMillis, checkpoint, referencePrice, movePct,
                    regime, strategyTraces, resolution.reason, "NO_SIGNAL: ${resolution.reason}", false
                )
                EngineResult(null, trace)
            }
            is ConflictResolution.Decision -> {
                val winner = resolution.winner
                val signal = Signal(
                    signalId = UUID.randomUUID().toString(),
                    candleId = candleId,
                    candleOpenTimeMillis = candleOpenTimeMillis,
                    signalTimestampMillis = timestampMillis,
                    candleOpen = candleOpen,
                    signalPrice = referencePrice,
                    direction = winner.direction,
                    activeStrategyId = winner.strategy.id,
                    activeStrategyName = "${winner.strategy.id} (${winner.strategy.marketConditionBucket})",
                    marketRegime = regime,
                    strategyScore = winner.score,
                    confidencePct = winner.strategy.performance.oosWinRatePct,
                    entryMovePct = movePct,
                    checkpoint = checkpoint
                )
                val trace = DebugTraceEntry(
                    candleId, timestampMillis, checkpoint, referencePrice, movePct,
                    regime, strategyTraces, "Decision: ${winner.strategy.id} -> ${winner.direction}",
                    "SIGNAL_GENERATED: ${winner.strategy.id} -> ${winner.direction} (score=${"%.4f".format(winner.score)})",
                    true
                )
                EngineResult(signal, trace)
            }
        }
    }

    /**
     * Result determination (spec section 28, updated per the "yellow signal line" change):
     * compare final close to the signal price — the exact price the candle was AT when the
     * signal fired (the yellow line on the live chart) — not the candle's open price. A RED
     * signal wins iff the candle closes below that price; a GREEN signal wins iff it closes
     * above it. Tie (close exactly equals signal price) counts as Red, same convention as
     * before. This function is the single shared path for Live (LiveMonitoringService),
     * Backtest (BacktestEngine), and the stale-signal catch-up path, so this change applies
     * identically everywhere a signal is resolved — no separate backtest logic to update.
     */
    fun evaluateResult(database: StrategyDatabase, signal: Signal, finalClose: Double): Pair<SignalStatus, Double> {
        val actualDirection = when {
            finalClose > signal.signalPrice -> Direction.GREEN
            finalClose < signal.signalPrice -> Direction.RED
            else -> if (database.hardConstraints.outcomeTieCountsAsRed) Direction.RED else Direction.RED
        }
        val won = actualDirection == signal.direction
        val pnl = if (won) database.financialModel.winUsd else database.financialModel.lossUsd
        val status = if (won) SignalStatus.WON else SignalStatus.LOST
        return status to pnl
    }
}
