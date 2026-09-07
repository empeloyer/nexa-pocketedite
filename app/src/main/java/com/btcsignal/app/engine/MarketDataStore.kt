package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Candle
import java.util.Collections

/**
 * Holds CLOSED 1-minute candles and derives 5m/1h/4h candles purely from already-closed
 * 1-minute candles — the mechanism that prevents look-ahead bias (spec sections 5, 10, 36):
 * a higher-timeframe candle is only ever produced once every one of its constituent
 * 1-minute candles has actually closed.
 *
 * PERFORMANCE FIX: this class previously re-aggregated the ENTIRE 1-minute history from
 * scratch on every single call to closed5m()/closed1h()/closed4h(). Those are called many
 * times per checkpoint (once per strategy component that needs that timeframe), so over a
 * multi-day backtest that amounted to millions of full-history rescans and made the
 * Backtest screen appear to hang indefinitely. Aggregation is now INCREMENTAL — each
 * closed 1-minute candle is folded into the in-progress 5m/1h/4h bucket in O(1), and a
 * completed higher-timeframe candle is appended once, not recomputed. Each timeframe's
 * retained history is also capped to comfortably cover the largest lookback any strategy
 * component or MarketRegimeClassifier actually uses (verified against
 * strategies_parameters.json: max period per timeframe is 5m=20, 1h=50, 4h=50, 1m=14;
 * MarketRegimeClassifier's own largest lookback is 211 on 5m), with generous headroom —
 * this does not change any strategy's indicator values, since none of them look back
 * further than the cap.
 */
class MarketDataStore(
    private val maxOneMinuteCandles: Int = 3_000,
    private val max5mCandles: Int = 500,
    private val max1hCandles: Int = 200,
    private val max4hCandles: Int = 200
) {

    private val oneMinute = ArrayDeque<Candle>()
    private val fiveMinute = ArrayDeque<Candle>()
    private val oneHour = ArrayDeque<Candle>()
    private val fourHour = ArrayDeque<Candle>()

    private val bucket5 = BucketBuilder(5)
    private val bucket60 = BucketBuilder(60)
    private val bucket240 = BucketBuilder(240)

    @Synchronized
    fun addClosed1m(candle: Candle) {
        require(candle.isClosed) { "MarketDataStore only accepts CLOSED candles" }
        if (oneMinute.isNotEmpty() && candle.openTimeMillis <= oneMinute.last().openTimeMillis) {
            // Duplicate or out-of-order candle - ignore (data integrity guard, spec section 27).
            return
        }
        addBounded(oneMinute, candle, maxOneMinuteCandles)

        bucket5.onCandle(candle)?.let { addBounded(fiveMinute, it, max5mCandles) }
        bucket60.onCandle(candle)?.let { addBounded(oneHour, it, max1hCandles) }
        bucket240.onCandle(candle)?.let { addBounded(fourHour, it, max4hCandles) }
    }

    private fun addBounded(list: ArrayDeque<Candle>, candle: Candle, cap: Int) {
        list.addLast(candle)
        if (list.size > cap) list.removeFirst()
    }

    @Synchronized
    fun closed1m(): List<Candle> = Collections.unmodifiableList(oneMinute)

    @Synchronized
    fun closed5m(): List<Candle> = Collections.unmodifiableList(fiveMinute)

    @Synchronized
    fun closed1h(): List<Candle> = Collections.unmodifiableList(oneHour)

    @Synchronized
    fun closed4h(): List<Candle> = Collections.unmodifiableList(fourHour)

    fun bySymbolTimeframe(minutes: Int): List<Candle> = when (minutes) {
        1 -> closed1m()
        5 -> closed5m()
        60 -> closed1h()
        240 -> closed4h()
        else -> throw IllegalArgumentException("Unsupported timeframe: ${minutes}m")
    }

    /**
     * Folds a stream of closed 1-minute candles into completed [intervalMinutes] buckets,
     * one candle at a time, emitting a finished bucket exactly once (when the NEXT
     * candle proves it's complete) instead of ever rescanning prior candles.
     */
    private class BucketBuilder(private val intervalMinutes: Int) {
        private val bucketMillis = intervalMinutes * 60_000L
        private var bucketStart = -1L
        private var open = 0.0
        private var high = Double.NEGATIVE_INFINITY
        private var low = Double.POSITIVE_INFINITY
        private var close = 0.0
        private var volume = 0.0
        private var count = 0
        private var lastCloseTime = 0L

        /** Returns a completed candle if [candle] just closed out the PREVIOUS bucket. */
        fun onCandle(candle: Candle): Candle? {
            val bStart = (candle.openTimeMillis / bucketMillis) * bucketMillis
            var completed: Candle? = null

            if (bucketStart == -1L) {
                startBucket(bStart, candle)
            } else if (bStart != bucketStart) {
                completed = if (count == intervalMinutes) {
                    Candle(bucketStart, open, high, low, close, volume, lastCloseTime, true)
                } else null // incomplete bucket (missing 1m data) - dropped, never emitted (section 27)
                startBucket(bStart, candle)
            } else {
                high = maxOf(high, candle.high)
                low = minOf(low, candle.low)
                close = candle.close
                volume += candle.volume
                count += 1
                lastCloseTime = candle.closeTimeMillis
            }
            return completed
        }

        private fun startBucket(bStart: Long, candle: Candle) {
            bucketStart = bStart
            open = candle.open
            high = candle.high
            low = candle.low
            close = candle.close
            volume = candle.volume
            count = 1
            lastCloseTime = candle.closeTimeMillis
        }
    }
}
