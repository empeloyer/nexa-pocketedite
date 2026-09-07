package com.btcsignal.app.ai

import com.btcsignal.app.data.model.Candle
import com.btcsignal.app.data.model.Checkpoint
import com.btcsignal.app.data.model.Direction
import com.btcsignal.app.data.model.StrategyDatabase
import com.btcsignal.app.engine.ComponentEvaluator
import com.btcsignal.app.engine.EvalContext
import com.btcsignal.app.engine.MarketDataStore
import com.btcsignal.app.engine.MarketRegimeClassifier
import com.btcsignal.app.engine.indicators.Indicators
import kotlin.math.abs
import kotlin.math.max

/**
 * MUST produce features in EXACTLY the order feature_schema.json lists them in - this is
 * the Android side of the causal feature contract also implemented (independently, then
 * cross-checked) in nexa_engine_py/build_causal_dataset.py and in the browser trainer's
 * JS feature engine (nexa_html_trainer/js/features.js). If you change one, change all
 * three and re-train, or inference will silently read the wrong column.
 *
 * Every value read here comes from either (a) MarketDataStore's closed1m/5m/1h/4h lists,
 * which by construction never contain the still-forming 5m candle (see MarketDataStore.kt
 * class doc), or (b) ctx.minute1Candle/minute2Candle, which are only non-null once that
 * specific 1-minute candle has itself closed. No future data is reachable from here.
 */
object AiFeatureExtractor {

    /** Order MUST match feature_schema.json "feature_order" shipped in NEXA_MODEL_PACKAGE.zip. */
    val FEATURE_ORDER = listOf(
        "entry_move_pct", "m1_body_pct", "m1_upper_wick", "m1_lower_wick", "m2_body_pct",
        "momentum_ratio_long", "momentum_ratio_short", "rsi_5m", "rsi_1h", "macd_hist_5m",
        "bb_pctb_5m", "adx_5m", "di_diff_5m", "stoch_rsi_5m", "dist_swing_high_pct",
        "dist_swing_low_pct", "vol_m1", "trend_bullish", "trend_bearish",
        "vol_high", "vol_low", "mom_strong_up", "mom_strong_down",
        "strat_n_green", "strat_n_red", "strat_any_fired", "strat_best_score", "checkpoint_b"
    )

    private fun bodyPct(c: Candle): Double {
        val range = max(c.high - c.low, 1e-9)
        return (c.close - c.open) / range
    }

    private fun upperWick(c: Candle): Double {
        val range = max(c.high - c.low, 1e-9)
        return (c.high - max(c.open, c.close)) / range
    }

    private fun lowerWick(c: Candle): Double {
        val range = max(c.high - c.low, 1e-9)
        return (minOf(c.open, c.close) - c.low) / range
    }

    /**
     * @param store live/backtest market data store (closed candles only)
     * @param candleOpen open of the 5m candle currently being evaluated
     * @param referencePrice minute1.close at Checkpoint A, minute2.close at Checkpoint B
     * @param checkpoint A or B
     * @param minute1 the just-closed (or already-closed) minute-1 candle - never null when called
     * @param minute2 the just-closed minute-2 candle - null at Checkpoint A
     * @param strategyDb the 27-strategy database, used only to compute the strat_* diagnostic
     *   features (existing strategies are NOT the decision-maker any more - see CoreSignalEngine.kt)
     */
    fun extract(
        store: MarketDataStore,
        candleOpen: Double,
        referencePrice: Double,
        checkpoint: Checkpoint,
        minute1: Candle,
        minute2: Candle?,
        strategyDb: StrategyDatabase,
    ): DoubleArray {
        val entryMovePct = if (candleOpen == 0.0) 0.0 else (referencePrice - candleOpen) / candleOpen * 100.0

        val m1BodyPct = bodyPct(minute1)
        val m1UpperWick = upperWick(minute1)
        val m1LowerWick = lowerWick(minute1)
        val m2BodyPct = if (checkpoint == Checkpoint.B && minute2 != null) bodyPct(minute2) else 0.0

        // ATR(3) and momentum-ratio always reference minute 1 specifically at BOTH
        // checkpoints (matches training - see feature_schema.json "notes").
        val closed1mAll = store.closed1m()
        val closed1mAsOfMinute1 = if (checkpoint == Checkpoint.B && closed1mAll.isNotEmpty() &&
            closed1mAll.last().openTimeMillis == minute2?.openTimeMillis
        ) closed1mAll.dropLast(1) else closed1mAll
        val atr3 = Indicators.atrSeries(closed1mAsOfMinute1, 3).lastOrNull()
        val momentumRatioLong = if (atr3 != null && atr3 > 0.0) (minute1.high - candleOpen) / atr3 else 0.0
        val momentumRatioShort = if (atr3 != null && atr3 > 0.0) (candleOpen - minute1.low) / atr3 else 0.0

        val closed5m = store.closed5m()
        val closed1h = store.closed1h()
        val closes5m = closed5m.map { it.close }
        val closes1h = closed1h.map { it.close }

        val rsi5m = Indicators.rsi(closes5m, 7) ?: 50.0
        val rsi1h = Indicators.rsi(closes1h, 14) ?: 50.0
        val macdHist5m = Indicators.macd(closes5m, 12, 26, 9)?.histogram ?: 0.0
        val bb5m = Indicators.bollingerPercentB(closes5m, 20, 2.0) ?: 0.5
        val adx = Indicators.adxDi(closed5m, 14)
        val adx5m = adx?.adx ?: 0.0
        val diDiff5m = if (adx != null) adx.plusDi - adx.minusDi else 0.0
        val stochRsi5m = Indicators.stochRsi(closes5m, 14, 14) ?: 50.0

        var distSwingHighPct = 0.0
        var distSwingLowPct = 0.0
        if (closed5m.size >= 20 && referencePrice > 0.0) {
            val window = closed5m.takeLast(20)
            val swingHigh = window.maxOf { it.high }
            val swingLow = window.minOf { it.low }
            distSwingHighPct = (swingHigh - referencePrice) / referencePrice * 100.0
            distSwingLowPct = (referencePrice - swingLow) / referencePrice * 100.0
        }

        val volM1 = minute1.volume

        val regime = MarketRegimeClassifier.classify(closed5m)
        val trendBullish = if (regime?.trend?.code == "Trend_Bullish") 1.0 else 0.0
        val trendBearish = if (regime?.trend?.code == "Trend_Bearish") 1.0 else 0.0
        val volHigh = if (regime?.volatility?.code == "Vol_High") 1.0 else 0.0
        val volLow = if (regime?.volatility?.code == "Vol_Low") 1.0 else 0.0
        val momStrongUp = if (regime?.momentum?.code == "Mom_StrongUp") 1.0 else 0.0
        val momStrongDown = if (regime?.momentum?.code == "Mom_StrongDown") 1.0 else 0.0

        val votes = existingStrategyVotes(strategyDb, store, candleOpen, referencePrice, checkpoint, minute1, minute2, regime?.activeCodes())

        val checkpointB = if (checkpoint == Checkpoint.B) 1.0 else 0.0

        val values = doubleArrayOf(
            entryMovePct, m1BodyPct, m1UpperWick, m1LowerWick, m2BodyPct,
            momentumRatioLong, momentumRatioShort, rsi5m, rsi1h, macdHist5m,
            bb5m, adx5m, diDiff5m, stochRsi5m, distSwingHighPct,
            distSwingLowPct, volM1, trendBullish, trendBearish,
            volHigh, volLow, momStrongUp, momStrongDown,
            votes.nGreen.toDouble(), votes.nRed.toDouble(), if (votes.anyFired) 1.0 else 0.0, votes.bestScore,
            checkpointB
        )
        check(values.size == FEATURE_ORDER.size) { "feature vector length mismatch" }
        return values
    }

    private data class StrategyVotes(val nGreen: Int, val nRed: Int, val anyFired: Boolean, val bestScore: Double)

    /**
     * Existing-strategy outputs used ONLY as diagnostic/benchmark features (per
     * 01_ANDROID_INTEGRATION_PROMPT.md "existing strategies as optional features only") -
     * NOT as the decision-maker. Mirrors run_full_year_backtest.py's eligibility + firing
     * logic exactly (same regime-gate matching, same "all components must agree" rule).
     */
    private fun existingStrategyVotes(
        db: StrategyDatabase,
        store: MarketDataStore,
        candleOpen: Double,
        referencePrice: Double,
        checkpoint: Checkpoint,
        minute1: Candle,
        minute2: Candle?,
        activeRegimeCodes: Set<String>?,
    ): StrategyVotes {
        val active = activeRegimeCodes ?: setOf("All")
        var nGreen = 0
        var nRed = 0
        var anyFired = false
        var bestScore = 0.0
        val ctx = EvalContext(store, candleOpen, referencePrice, checkpoint, minute1, minute2)
        for (s in db.strategies) {
            if (s.regimeGateCode != "All" && s.regimeGateCode !in active) continue
            var dir: Direction? = null
            var agree = true
            for (c in s.components) {
                val d = ComponentEvaluator.evaluate(c, ctx).direction
                if (d == null) { agree = false; break }
                if (dir == null) dir = d else if (dir != d) { agree = false; break }
            }
            if (!agree || dir == null) continue
            anyFired = true
            if (dir == Direction.GREEN) nGreen++ else nRed++
            val z = s.performance.oosZScoreVsBreakeven ?: 0.0
            if (z > bestScore) bestScore = z
        }
        return StrategyVotes(nGreen, nRed, anyFired, bestScore)
    }
}
