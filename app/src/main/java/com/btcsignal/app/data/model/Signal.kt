package com.btcsignal.app.data.model

enum class SignalStatus { ACTIVE, WON, LOST, INVALIDATED }

/**
 * The ONE canonical Signal object (spec section 18). This exact type is shared by:
 * the Core Signal Engine (live + backtest), Room persistence, the Live Signal Panel UI,
 * and the Android notification content. No other Signal representation exists in the app.
 */
data class Signal(
    val signalId: String,
    val candleId: String,             // 5m candle open time as ISO instant, doubles as unique candle key
    val candleOpenTimeMillis: Long,
    val signalTimestampMillis: Long,
    val symbol: String = "BTCUSDT",
    val candleOpen: Double,
    val signalPrice: Double,          // close of the 1m sub-candle at the checkpoint that fired
    val direction: Direction,         // == prediction
    val activeStrategyId: String,
    val activeStrategyName: String,
    val marketRegime: MarketRegimeState,
    val strategyScore: Double,
    val confidencePct: Double,        // OOS win rate of the winning strategy, used as displayed "confidence"
    val entryMovePct: Double,
    val checkpoint: Checkpoint,
    var status: SignalStatus = SignalStatus.ACTIVE,
    var finalClose: Double? = null,
    var pnlUsd: Double? = null,
    var notified: Boolean = false,
    // --- AI decision fields, added for the ONNX model integration (ai/ package). All
    // default so every existing call site (and every existing Signal(...) in tests)
    // keeps compiling unchanged; decisionSource distinguishes AI-sourced signals from
    // the original 27-strategy rule engine, which remains the fallback (see
    // CoreSignalEngine.kt "AI fallback" note) when no model is installed. ---
    val decisionSource: String = "RULE_ENGINE",   // "RULE_ENGINE" or "AI_MODEL"
    val aiRawScore: Double? = null,
    val aiCalibratedProbability: Double? = null,
    val aiEntryContext: String? = null,           // NORMAL_DIRECTIONAL_ENTRY / ADVERSE_REVERSAL_ENTRY / TOO_LATE_DIRECTIONAL_ENTRY
    val aiModelVersion: String? = null
)
