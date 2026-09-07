package com.btcsignal.app.ai

import kotlin.math.max
import kotlin.math.min

/**
 * Tracks REALIZED win/loss outcomes per confidence decile and compares them against what
 * the shipped calibration table predicted for that decile, using a Beta-Bernoulli
 * posterior (prior = calibration table's predicted rate, weighted as if it were
 * PRIOR_STRENGTH observations - i.e. genuine live data can only gradually outweigh it).
 *
 * This deliberately does NOT retrain the ONNX model or rewrite calibration.json on-device
 * - that would be exactly the kind of self-modifying/self-reported-metric behaviour the
 * project's own spec warns against. What it DOES do: if a confidence decile's live
 * outcomes are running well below what calibration promised, [effectiveMinConfidence]
 * raises the bar for that zone so the app quietly becomes more conservative rather than
 * silently keep trusting a prediction that live data is contradicting. Feed it outcomes
 * via [recordOutcome] as each signal resolves (see CoreSignalEngine.kt call site).
 */
class AdaptiveLearningEngine(private val priorStrength: Double = 30.0) {

    private data class Bucket(var wins: Double = 0.0, var losses: Double = 0.0)

    private val normalBuckets = HashMap<Int, Bucket>()   // decile index 5..9 (confidence 0.5-1.0)
    private val adverseBuckets = HashMap<Int, Bucket>()

    private fun decile(confidence: Double): Int = min(9, max(5, (confidence * 10).toInt()))

    fun seedFromCalibration(calibration: CalibrationTable, isAdverse: Boolean) {
        val target = if (isAdverse) adverseBuckets else normalBuckets
        for (d in 5..9) {
            val midConfidence = (d + 0.5) / 10.0
            val predicted = calibration.apply(midConfidence).coerceIn(0.01, 0.99)
            val b = target.getOrPut(d) { Bucket() }
            b.wins = predicted * priorStrength
            b.losses = (1 - predicted) * priorStrength
        }
    }

    /** Call once a signal's outcome (win/loss) is known. */
    fun recordOutcome(confidence: Double, isAdverse: Boolean, won: Boolean) {
        val target = if (isAdverse) adverseBuckets else normalBuckets
        val b = target.getOrPut(decile(confidence)) { Bucket() }
        if (won) b.wins += 1.0 else b.losses += 1.0
    }

    /** Posterior mean win rate for the given confidence/zone, or null if no data at all yet. */
    fun posteriorWinRate(confidence: Double, isAdverse: Boolean): Double? {
        val target = if (isAdverse) adverseBuckets else normalBuckets
        val b = target[decile(confidence)] ?: return null
        val total = b.wins + b.losses
        if (total <= 0.0) return null
        return b.wins / total
    }

    /**
     * Returns a possibly-raised minimum confidence for this zone: if live posterior
     * performance at [baseMinConfidence] is below breakeven+margin, walks up the deciles
     * until it finds one that still clears it (or returns 1.01 = "effectively disabled"
     * if none do). Never LOWERS the shipped threshold - only tightens it.
     */
    fun effectiveMinConfidence(baseMinConfidence: Double?, isAdverse: Boolean, breakeven: Double, margin: Double): Double? {
        if (baseMinConfidence == null) return null
        var d = decile(baseMinConfidence)
        while (d <= 9) {
            val wr = posteriorWinRate((d + 0.5) / 10.0, isAdverse)
            if (wr == null || wr >= breakeven + margin) return max(baseMinConfidence, d / 10.0)
            d += 1
        }
        return null // even the top decile's live performance is failing to clear breakeven -> disable
    }
}
