package com.btcsignal.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "btc_signal_settings")

/** Default floor applied on top of the model's own calibrated minimum confidence
 *  (see AiSignalDecisionEngine.decide) when the user hasn't touched the AI panel's
 *  slider yet. */
const val DEFAULT_AI_CONFIDENCE_THRESHOLD = 0.60f

data class AppSettings(
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val blockedStrategyIds: Set<String> = emptySet(),
    val aiConfidenceThreshold: Float = DEFAULT_AI_CONFIDENCE_THRESHOLD
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val BLOCKED_STRATEGY_IDS = stringSetPreferencesKey("blocked_strategy_ids")
        val AI_CONFIDENCE_THRESHOLD = floatPreferencesKey("ai_confidence_threshold")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            soundEnabled = prefs[Keys.SOUND] ?: true,
            vibrationEnabled = prefs[Keys.VIBRATION] ?: true,
            blockedStrategyIds = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: emptySet(),
            aiConfidenceThreshold = prefs[Keys.AI_CONFIDENCE_THRESHOLD] ?: DEFAULT_AI_CONFIDENCE_THRESHOLD
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND] = enabled }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATION] = enabled }
    }

    /** User-adjustable floor for the AI decision layer (0.50-0.95). This is applied as
     *  an EXTRA requirement on top of the model's own calibrated minimum confidence in
     *  AiSignalDecisionEngine.decide - raising it never re-enables a zone the model's
     *  calibration found no real edge for (adverseMinConfidence/normalMinConfidence ==
     *  null stays disabled regardless of this value). */
    suspend fun setAiConfidenceThreshold(value: Float) {
        context.dataStore.edit { it[Keys.AI_CONFIDENCE_THRESHOLD] = value.coerceIn(0.50f, 0.95f) }
    }

    /** Toggles a strategy's temporary block state (Strategies screen "Block" button).
     * A blocked strategy is skipped by CoreSignalEngine on the Live path only — it
     * cannot emit new signals until unblocked again. */
    suspend fun setStrategyBlocked(strategyId: String, blocked: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: emptySet()
            prefs[Keys.BLOCKED_STRATEGY_IDS] = if (blocked) current + strategyId else current - strategyId
        }
    }
}
