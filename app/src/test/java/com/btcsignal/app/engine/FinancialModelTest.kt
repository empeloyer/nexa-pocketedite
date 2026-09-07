package com.btcsignal.app.engine

import com.btcsignal.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class FinancialModelTest {

    private val database = StrategyDatabase(
        asset = "BTCUSDT",
        targetTimeframe = "5m",
        hardConstraints = HardConstraints(0.0..0.03, -0.03..0.0, outcomeTieCountsAsRed = true),
        financialModel = FinancialModelSpec(startCapitalUsd = 100.0, stakePerSignalUsd = 1.0, winUsd = 0.5, lossUsd = -1.0, breakevenWinRatePct = 66.7),
        strategies = emptyList()
    )

    private fun signal(direction: Direction, signalPrice: Double = 50_000.0, candleOpen: Double = signalPrice) = Signal(
        signalId = "s1", candleId = "c1", candleOpenTimeMillis = 0, signalTimestampMillis = 0,
        candleOpen = candleOpen, signalPrice = signalPrice, direction = direction,
        activeStrategyId = "TEST-001", activeStrategyName = "test",
        marketRegime = MarketRegimeState(TrendRegime.SIDEWAYS, VolatilityRegime.MEDIUM, MomentumRegime.WEAK),
        strategyScore = 1.0, confidencePct = 55.0, entryMovePct = 0.01, checkpoint = Checkpoint.A
    )

    @Test
    fun `GREEN signal wins when candle closes above the signal price`() {
        val (status, pnl) = CoreSignalEngine.evaluateResult(database, signal(Direction.GREEN), finalClose = 50_010.0)
        assertEquals(SignalStatus.WON, status)
        assertEquals(0.5, pnl, 0.0001)
    }

    @Test
    fun `GREEN signal loses when candle closes below the signal price`() {
        val (status, pnl) = CoreSignalEngine.evaluateResult(database, signal(Direction.GREEN), finalClose = 49_990.0)
        assertEquals(SignalStatus.LOST, status)
        assertEquals(-1.0, pnl, 0.0001)
    }

    @Test
    fun `RED signal wins when candle closes below the signal price`() {
        val (status, pnl) = CoreSignalEngine.evaluateResult(database, signal(Direction.RED), finalClose = 49_990.0)
        assertEquals(SignalStatus.WON, status)
        assertEquals(0.5, pnl, 0.0001)
    }

    @Test
    fun `result follows the signal price (yellow line), not the candle open, when they differ`() {
        // Candle opened at 50,000 but the signal didn't fire until price had already moved
        // to 50,020 (that's the yellow line). A close of 50,010 is above the OPEN but still
        // below the SIGNAL PRICE -- so GREEN must lose and RED must win here. This is the
        // case the old candleOpen-based comparison got wrong.
        val greenCall = signal(Direction.GREEN, signalPrice = 50_020.0, candleOpen = 50_000.0)
        val (greenStatus, _) = CoreSignalEngine.evaluateResult(database, greenCall, finalClose = 50_010.0)
        assertEquals(SignalStatus.LOST, greenStatus)

        val redCall = signal(Direction.RED, signalPrice = 50_020.0, candleOpen = 50_000.0)
        val (redStatus, _) = CoreSignalEngine.evaluateResult(database, redCall, finalClose = 50_010.0)
        assertEquals(SignalStatus.WON, redStatus)
    }

    @Test
    fun `a tie (close equals signal price) counts as Red per the Strategy Database outcome rule`() {
        val greenResult = CoreSignalEngine.evaluateResult(database, signal(Direction.GREEN), finalClose = 50_000.0)
        assertEquals(SignalStatus.LOST, greenResult.first) // GREEN signal, tie resolves to Red -> loses

        val redResult = CoreSignalEngine.evaluateResult(database, signal(Direction.RED), finalClose = 50_000.0)
        assertEquals(SignalStatus.WON, redResult.first) // RED signal, tie resolves to Red -> wins
    }

    @Test
    fun `balance simulation matches the fixed financial model`() {
        var balance = database.financialModel.startCapitalUsd
        val outcomes = listOf(true, true, false, true, false, false) // WIN, WIN, LOSS, WIN, LOSS, LOSS
        for (won in outcomes) {
            balance += if (won) database.financialModel.winUsd else database.financialModel.lossUsd
        }
        // 100 + 0.5 + 0.5 - 1.0 + 0.5 - 1.0 - 1.0 = 98.5
        assertEquals(98.5, balance, 0.0001)
    }
}
