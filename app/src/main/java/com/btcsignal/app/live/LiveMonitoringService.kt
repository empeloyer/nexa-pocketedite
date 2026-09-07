package com.btcsignal.app.live

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.btcsignal.app.MainActivity
import com.btcsignal.app.R
import com.btcsignal.app.AppContainer
import com.btcsignal.app.ai.AdaptiveLearningEngine
import com.btcsignal.app.ai.ModelManager
import com.btcsignal.app.data.binance.BinanceRestClient
import com.btcsignal.app.data.binance.BinanceStreamListener
import com.btcsignal.app.data.binance.BinanceWebSocketClient
import com.btcsignal.app.data.binance.ConnectionState
import com.btcsignal.app.data.local.AppDatabase
import com.btcsignal.app.data.model.*
import com.btcsignal.app.data.repository.SettingsRepository
import com.btcsignal.app.data.repository.SignalRepository
import com.btcsignal.app.engine.*
import com.btcsignal.app.notifications.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * The Live Engine (spec sections 25-27). Runs as a foreground service so monitoring can
 * continue while the app is backgrounded, subject to normal Android restrictions.
 * Wires: BinanceWebSocketClient -> MarketDataStore -> CandleAggregator ->
 * CoreSignalEngine -> SignalRepository (Room) -> NotificationHelper, all funneling
 * through LiveEngineState for the UI. This is the ONLY place the live path is wired;
 * BacktestEngine wires the same CoreSignalEngine independently for historical replay.
 */
class LiveMonitoringService : Service(), BinanceStreamListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var database: StrategyDatabase
    private val marketDataStore = MarketDataStore()
    private val aggregator = CandleAggregator()
    private lateinit var wsClient: BinanceWebSocketClient
    private val restClient = BinanceRestClient()
    private lateinit var signalRepo: SignalRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var prefs: SharedPreferences
    private lateinit var modelManager: ModelManager
    private val adaptiveLearning = AdaptiveLearningEngine()

    @Volatile private var resyncing = true
    @Volatile private var lastProcessedOpenTime = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("live_service_prefs", MODE_PRIVATE)
        prefs.edit { putBoolean("was_running", true) }

        database = StrategyRegistry.load(applicationContext)
        signalRepo = SignalRepository(AppDatabase.get(applicationContext).signalDao())
        settingsRepo = SettingsRepository(applicationContext)
        notificationHelper = NotificationHelper(applicationContext)
        wsClient = BinanceWebSocketClient(this)
        modelManager = AppContainer.modelManager(applicationContext)
        serviceScope.launch {
            modelManager.initialize()
            modelManager.activeCalibration?.let {
                adaptiveLearning.seedFromCalibration(it, isAdverse = false)
                adaptiveLearning.seedFromCalibration(it, isAdverse = true)
            }
        }

        startForeground(FOREGROUND_ID, buildForegroundNotification("Connecting to Binance\u2026"))

        LiveEngineState.appState.value = AppState.SYNCING
        serviceScope.launch {
            warmUp()
            resyncing = false
            wsClient.connect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        prefs.edit { putBoolean("was_running", false) }
        wsClient.disconnect()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /** Backfills enough 1-minute history via REST for every indicator's longest lookback
     *  before trusting live data (spec section 26: "resynchronize candle state before
     *  allowing a new signal"). 4h ADX needs 2*period+1=29 closed 4h candles => up to
     *  ~4.8 days; we pull 8 days for headroom. */
    private suspend fun warmUp() {
        val end = System.currentTimeMillis()
        val start = end - WARMUP_DAYS * 24L * 60 * 60 * 1000
        try {
            val candles = restClient.getKlines("BTCUSDT", "1m", start, end)
            for (c in candles) {
                marketDataStore.addClosed1m(c)
                aggregator.onClosed1mCandle(c)
                lastProcessedOpenTime = c.openTimeMillis
            }
        } catch (e: Exception) {
            LiveEngineState.appState.value = AppState.ERROR
        }
    }

    override fun onKlineUpdate(candle: Candle) {
        LiveEngineState.livePrice.value = candle.close

        if (!candle.isClosed && aggregator.isNewBucket(candle)) {
            // The next 5-minute candle has already started forming on Binance's side
            // (its first 1-minute sub-candle just opened but hasn't closed yet). The
            // aggregator itself only commits this transition once that sub-candle
            // closes via onClosed1mCandle -- a full minute from now. Without this,
            // the UI would keep showing the previous candle's signal/timer/open price
            // through all of minute 1 of the new candle, even though no signal has
            // been issued for it yet. Reflect the new candle immediately here instead.
            LiveEngineState.candleOpen.value = candle.open
            LiveEngineState.candleOpenTimeMillis.value = aggregator.bucketStartFor(candle)
            LiveEngineState.currentMovePct.value =
                if (candle.open != 0.0) (candle.close - candle.open) / candle.open * 100.0 else 0.0
            LiveEngineState.candlePhase.value = CandlePhase.MINUTE_1
            LiveEngineState.appState.value = AppState.ANALYZING
            LiveEngineState.currentSignal.value = null
        } else {
            aggregator.currentCandleOpen()?.let { open ->
                LiveEngineState.candleOpen.value = open
                LiveEngineState.currentMovePct.value = if (open != 0.0) (candle.close - open) / open * 100.0 else 0.0
            }
            aggregator.currentCandleOpenTimeMillis()?.let { LiveEngineState.candleOpenTimeMillis.value = it }
        }

        if (!candle.isClosed) return
        if (candle.openTimeMillis <= lastProcessedOpenTime) return // duplicate guard (section 27)
        lastProcessedOpenTime = candle.openTimeMillis

        marketDataStore.addClosed1m(candle)
        val events = aggregator.onClosed1mCandle(candle)

        serviceScope.launch {
            for (event in events) {
                when (event) {
                    is CandleEvent.PhaseChanged -> {
                        LiveEngineState.candlePhase.value = event.phase
                        LiveEngineState.appState.value = when (event.phase) {
                            CandlePhase.MINUTE_1, CandlePhase.MINUTE_2 -> AppState.ANALYZING
                            CandlePhase.PREDICTION_WINDOW_CLOSED -> AppState.PREDICTION_WINDOW_CLOSED
                            CandlePhase.CANDLE_CLOSED -> AppState.CANDLE_CLOSED
                        }
                        if (event.phase == CandlePhase.MINUTE_1) LiveEngineState.reset()
                    }
                    is CandleEvent.CheckpointReached -> handleCheckpoint(event)
                    is CandleEvent.FiveMinuteCandleClosed -> handleCandleClosed(event.candle)
                }
            }
        }
    }

    private suspend fun handleCheckpoint(event: CandleEvent.CheckpointReached) {
        if (resyncing) return // never signal off incomplete post-reconnect state (section 26)
        val candleId = java.time.Instant.ofEpochMilli(event.candleOpenTimeMillis).toString()
        if (signalRepo.isCandleLocked(candleId, isBacktest = false)) return // signal lock (section 9)

        // Precompute rolling stats for every strategy up front (suspend), so the engine
        // itself stays synchronous and identical between Live and Backtest call sites.
        val statsCache = HashMap<String, RecentWindowStats>()
        for (s in database.strategies) statsCache[s.id] = signalRepo.recentWindowStatsFor(s.id)
        val settings = settingsRepo.settingsFlow.first()

        val result = CoreSignalEngine.evaluateCheckpoint(
            database = database,
            store = marketDataStore,
            candleOpenTimeMillis = event.candleOpenTimeMillis,
            candleOpen = event.candleOpen,
            checkpoint = event.checkpoint,
            referencePrice = event.referencePrice,
            minute1Candle = event.minute1,
            minute2Candle = event.minute2,
            timestampMillis = event.timestampMillis,
            statsProvider = { id -> statsCache[id] ?: RecentWindowStats(0, 0.0) },
            blockedStrategyIds = settings.blockedStrategyIds,
            aiModelManager = modelManager,
            adaptiveLearning = adaptiveLearning,
            userMinConfidence = settings.aiConfidenceThreshold.toDouble()
        )
        LiveEngineState.pushTrace(result.trace)
        result.trace.regime?.let { LiveEngineState.marketRegime.value = it }

        val signal = result.signal ?: return
        signalRepo.saveSignal(signal, isBacktest = false)
        LiveEngineState.currentSignal.value = signal
        LiveEngineState.appState.value = AppState.SIGNAL_LOCKED
        updateForegroundNotification("Signal locked: ${signal.direction} \u2022 ${signal.activeStrategyId}")

        if (settings.notificationsEnabled && signalRepo.markNotifiedIfNeeded(signal.signalId)) {
            notificationHelper.notifySignal(signal, settings.soundEnabled, settings.vibrationEnabled)
        }
    }

    private suspend fun handleCandleClosed(candle: Candle) {
        val candleId = java.time.Instant.ofEpochMilli(candle.openTimeMillis).toString()
        val existing = signalRepo.getMostRecentActiveLiveSignal()
        if (existing != null && existing.candleId == candleId) {
            resolveSignalOutcome(existing, candle.close)
        }
        // Safety net (see resolveStaleActiveSignals kdoc): catch up any OLDER signal
        // that's still sitting at ACTIVE because its own candle's close event never
        // fired locally, now that we know a later candle has definitely closed.
        resolveStaleActiveSignals(candle.openTimeMillis)
        LiveEngineState.candlePhase.value = CandlePhase.CANDLE_CLOSED
        LiveEngineState.appState.value = AppState.WAITING
    }

    /** Shared by the normal (exact-match) and stale-signal-catchup paths so both resolve
     *  a signal identically. */
    private suspend fun resolveSignalOutcome(existing: com.btcsignal.app.data.local.SignalEntity, finalClose: Double) {
        val signalDirection = Direction.valueOf(existing.direction)
        val (status, pnl) = CoreSignalEngine.evaluateResult(
            database,
            Signal(
                signalId = existing.signalId, candleId = existing.candleId,
                candleOpenTimeMillis = existing.candleOpenTimeMillis,
                signalTimestampMillis = existing.signalTimestampMillis,
                candleOpen = existing.candleOpen, signalPrice = existing.signalPrice,
                direction = signalDirection, activeStrategyId = existing.activeStrategyId,
                activeStrategyName = existing.activeStrategyName,
                // Result evaluation only needs candleOpen/direction/financial model (see
                // CoreSignalEngine.evaluateResult); regime is not re-derived here.
                marketRegime = MarketRegimeState(TrendRegime.SIDEWAYS, VolatilityRegime.MEDIUM, MomentumRegime.WEAK),
                strategyScore = existing.strategyScore, confidencePct = existing.confidencePct,
                entryMovePct = existing.entryMovePct, checkpoint = Checkpoint.valueOf(existing.checkpoint)
            ),
            finalClose
        )
        signalRepo.markResult(existing.signalId, status, finalClose, pnl)
        if (existing.decisionSource == "AI_MODEL" && existing.aiCalibratedProbability != null && existing.aiEntryContext != null) {
            val isAdverse = existing.aiEntryContext == "ADVERSE_REVERSAL_ENTRY"
            val confidence = if (existing.direction == "GREEN") existing.aiCalibratedProbability else 1.0 - existing.aiCalibratedProbability
            adaptiveLearning.recordOutcome(confidence, isAdverse, won = status == SignalStatus.WON)
        }
    }

    /** Whenever a candle closes normally, any OTHER Live signal still marked ACTIVE from
     *  an earlier 5-minute window is by now guaranteed to have actually finished on
     *  Binance's side too - it's just stuck locally because CandleAggregator never saw
     *  all 5 of its 1-minute sub-candles (a brief WebSocket hiccup that didn't trip a
     *  full DISCONNECTED state, so resyncGap() never ran for it). Rather than leave it
     *  "ACTIVE" in History/Performance forever with no win/lose ever recorded, fetch its
     *  real close directly from Binance's REST API - independent of our local candle
     *  stream - and resolve it the same way a normal close would. Best-effort: if
     *  Binance is briefly unreachable this just retries on the next candle close. */
    private suspend fun resolveStaleActiveSignals(currentClosedOpenTimeMillis: Long) {
        val stale = signalRepo.getActiveLiveSignals()
            .filter { it.candleOpenTimeMillis < currentClosedOpenTimeMillis }
        for (existing in stale) {
            try {
                val windowStart = existing.candleOpenTimeMillis
                val windowEnd = windowStart + FIVE_MIN_MILLIS
                val subCandles = restClient.getKlines("BTCUSDT", "1m", windowStart, windowEnd)
                val finalClose = subCandles.lastOrNull { it.openTimeMillis < windowEnd }?.close ?: continue
                resolveSignalOutcome(existing, finalClose)
            } catch (e: Exception) {
                // Binance REST temporarily unavailable - leave ACTIVE, retried on the next candle close.
            }
        }
    }

    override fun onConnectionStateChanged(state: ConnectionState) {
        when (state) {
            ConnectionState.CONNECTING -> {
                LiveEngineState.appState.value = AppState.CONNECTING
                updateForegroundNotification("Status: CONNECTING")
            }
            ConnectionState.CONNECTED -> {
                if (resyncing.not() && lastProcessedOpenTime > 0) {
                    // resyncGap() owns the full SYNCING -> CONNECTED transition itself
                    // (including resetting back to CONNECTED and refreshing the
                    // notification once it's done) - it must NOT also be set to
                    // CONNECTED here, or the two concurrent writes race and can leave
                    // the state (and the persistent notification) stuck showing
                    // "Syncing" indefinitely even once the app is fully connected and
                    // analyzing again.
                    serviceScope.launch { resyncGap() }
                } else {
                    LiveEngineState.appState.value = AppState.CONNECTED
                    updateForegroundNotification("Status: CONNECTED")
                }
            }
            ConnectionState.DISCONNECTED -> {
                LiveEngineState.appState.value = AppState.DISCONNECTED
                updateForegroundNotification("Status: DISCONNECTED")
            }
            ConnectionState.ERROR -> {
                LiveEngineState.appState.value = AppState.ERROR
                updateForegroundNotification("Status: ERROR")
            }
        }
    }

    private suspend fun resyncGap() {
        resyncing = true
        LiveEngineState.appState.value = AppState.SYNCING
        updateForegroundNotification("Status: SYNCING (catching up)")
        var failed = false
        try {
            val start = lastProcessedOpenTime + 60_000L
            val end = System.currentTimeMillis()
            if (end > start) {
                val gapCandles = restClient.getKlines("BTCUSDT", "1m", start, end)
                for (c in gapCandles) {
                    if (c.openTimeMillis <= lastProcessedOpenTime) continue
                    marketDataStore.addClosed1m(c)
                    aggregator.onClosed1mCandle(c)
                    lastProcessedOpenTime = c.openTimeMillis
                }
            }
        } catch (e: Exception) {
            failed = true
            LiveEngineState.appState.value = AppState.ERROR
            updateForegroundNotification("Status: ERROR (resync failed, will retry)")
        } finally {
            resyncing = false
        }
        if (!failed) {
            // Explicitly leave SYNCING here instead of waiting for some later, unrelated
            // event to eventually overwrite it - that wait could otherwise take minutes
            // (up to the next full 1-minute candle close) during which the app is
            // actually fully connected and analyzing, but the status still reads
            // "Syncing".
            LiveEngineState.appState.value = AppState.CONNECTED
            updateForegroundNotification("Status: CONNECTED")
        }
    }

    override fun onError(message: String) {
        LiveEngineState.appState.value = AppState.ERROR
    }

    private fun buildForegroundNotification(text: String): Notification {
        notificationHelper.ensureChannels(soundEnabled = true, vibrationEnabled = true)
        val pendingIntent = androidx.core.app.TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(Intent(this@LiveMonitoringService, MainActivity::class.java))
            getPendingIntent(0, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BTCUSDT Signal monitoring")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(FOREGROUND_ID, buildForegroundNotification(text))
    }

    companion object {
        private const val FOREGROUND_ID = 42
        private const val WARMUP_DAYS = 8L
        private const val FIVE_MIN_MILLIS = 5 * 60_000L
    }
}
