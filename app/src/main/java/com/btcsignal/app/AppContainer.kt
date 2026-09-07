package com.btcsignal.app

import android.content.Context
import com.btcsignal.app.ai.AdaptiveLearningEngine
import com.btcsignal.app.ai.ModelManager
import com.btcsignal.app.backtest.BacktestEngine
import com.btcsignal.app.data.binance.BinanceRestClient
import com.btcsignal.app.data.local.AppDatabase
import com.btcsignal.app.data.model.StrategyDatabase
import com.btcsignal.app.data.repository.SettingsRepository
import com.btcsignal.app.data.repository.SignalRepository
import com.btcsignal.app.engine.StrategyRegistry
import com.btcsignal.app.notifications.NotificationHelper

/**
 * Simple, explicit dependency wiring (spec section: Repository pattern, clean
 * separation of UI and business logic) — deliberately not a DI framework, to keep the
 * project buildable without an extra Gradle plugin/annotation-processor chain.
 */
object AppContainer {
    @Volatile private var strategyDb: StrategyDatabase? = null
    @Volatile private var modelMgr: ModelManager? = null
    @Volatile private var adaptiveEngine: AdaptiveLearningEngine? = null

    fun strategyDatabase(context: Context): StrategyDatabase =
        strategyDb ?: synchronized(this) {
            strategyDb ?: StrategyRegistry.load(context.applicationContext).also { strategyDb = it }
        }

    fun signalRepository(context: Context): SignalRepository =
        SignalRepository(AppDatabase.get(context.applicationContext).signalDao())

    fun settingsRepository(context: Context): SettingsRepository =
        SettingsRepository(context.applicationContext)

    fun notificationHelper(context: Context): NotificationHelper =
        NotificationHelper(context.applicationContext)

    /** One process-wide ModelManager so Live, Backtest and Settings all see the same
     *  active/loaded model without re-importing or re-verifying it per screen. */
    fun modelManager(context: Context): ModelManager =
        modelMgr ?: synchronized(this) {
            modelMgr ?: ModelManager(context.applicationContext).also { modelMgr = it }
        }

    fun adaptiveLearningEngine(): AdaptiveLearningEngine =
        adaptiveEngine ?: synchronized(this) {
            adaptiveEngine ?: AdaptiveLearningEngine().also { adaptiveEngine = it }
        }

    fun backtestEngine(context: Context): BacktestEngine =
        BacktestEngine(
            BinanceRestClient(),
            signalRepository(context),
            modelManager(context),
            adaptiveLearningEngine(),
            settingsRepository(context)
        )
}
