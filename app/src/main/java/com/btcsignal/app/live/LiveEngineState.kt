package com.btcsignal.app.live

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import com.btcsignal.app.data.model.*
import com.btcsignal.app.engine.DebugTraceEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton, UI-observable snapshot of the live engine (spec section 19, Live Signal
 * Panel). LiveMonitoringService is the only writer; ui/screens/LiveSignalScreen.kt is
 * the primary reader. Kept separate from the Service itself so the Compose UI can
 * observe engine state regardless of Activity/Service lifecycle timing.
 */
object LiveEngineState {
    val appState = MutableStateFlow(AppState.WAITING)
    val candlePhase = MutableStateFlow(CandlePhase.CANDLE_CLOSED)
    val livePrice = MutableStateFlow(0.0)
    val candleOpen = MutableStateFlow(0.0)
    val currentMovePct = MutableStateFlow(0.0)
    val candleOpenTimeMillis = MutableStateFlow(0L)
    val marketRegime = MutableStateFlow<MarketRegimeState?>(null)
    val currentSignal = MutableStateFlow<Signal?>(null)
    val debugTraceLog = MutableStateFlow<List<DebugTraceEntry>>(emptyList())

    /** Accumulated "price move since candle open" path for [com.btcsignal.app.ui.components.PriceMoveChart],
     *  kept here (instead of composable-local `remember`) so it survives the user
     *  switching bottom-nav tabs and coming back -- the Live screen's composition is
     *  torn down on tab switch, which used to wipe a locally-remembered list and make
     *  the chart look like it "restarted" instead of showing the path walked so far. */
    val priceMovePoints = mutableStateListOf<Offset>()
    var priceMoveLastCandleOpenTime: Long? = null

    private const val MAX_TRACE_ENTRIES = 200

    fun pushTrace(entry: DebugTraceEntry) {
        val updated = (debugTraceLog.value + entry).takeLast(MAX_TRACE_ENTRIES)
        debugTraceLog.value = updated
    }

    /** Clears the previous candle's signal. Does NOT touch [candlePhase] -- callers
     *  (currently only the MINUTE_1 transition in LiveMonitoringService) are
     *  responsible for setting the phase themselves; overwriting it here used to
     *  immediately stomp a just-set MINUTE_1 back to CANDLE_CLOSED, which made every
     *  phase chip in the UI render as "active" the instant the new candle began. */
    fun reset() {
        currentSignal.value = null
    }
}
