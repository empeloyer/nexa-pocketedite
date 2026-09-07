# NEXA Android — Patch Notes (AI Model Integration)

This documents every change made on top of the `nexa_alpha_v1` app, applying
`01_ANDROID_INTEGRATION_PROMPT.md`, and the methodology behind the model produced by
`NEXA_PC_Trainer.html` / trained once already in `nexa_engine_py/`. Written in the same
spirit as the original `README.md`: state plainly what is real/tested and what isn't.

## 0.1 [2026-09-07] Win/lose now keyed to the signal price, not the candle open

Per request: the Live chart draws a solid yellow line on `PriceMoveChart` at the exact
price the candle was AT the moment a signal fired (`Signal.signalPrice`, already existed
as a field — nothing new to capture), and correctness now reads off that line instead of
the candle-open "Target" dashed line:

- **RED signal wins iff the candle closes below the yellow line, loses if it closes
  above.** **GREEN signal wins iff it closes above the yellow line, loses if below.**
  (Tie keeps the existing "counts as Red" convention, now measured against the signal
  price instead of the open.)
- Changed in exactly one place — `CoreSignalEngine.evaluateResult()` — which per the
  architecture (section 2 above) is the single function Live (`LiveMonitoringService`),
  Backtest (`BacktestEngine`), and the stale-signal catch-up path all call to resolve a
  signal. So this is applied to the backtest automatically, not a second implementation
  kept in sync by hand.
- `PriceMoveChart.kt` gained an optional `signalPrice` param: when present, it draws the
  yellow line + "Signal" label, extends the auto-scaled Y axis to keep it in view, and
  moves the green/red win-zone shading to that line so the coloring always matches what
  actually decides the outcome. The dashed white "Target" line at the candle open is
  untouched — still shown, just no longer the thing that determines win/lose.
  `LiveSignalScreen.kt` passes `signal?.signalPrice` through; when no signal is active yet
  the chart falls back to its original candle-open-only behavior exactly as before.
- `FinancialModelTest.kt` updated: renamed the existing win/lose tests to say "signal
  price," and added one that sets `signalPrice != candleOpen` specifically to prove the
  result follows the signal price and not the open — the old tests couldn't have caught a
  regression here since their fixture happened to set the two equal.
- Same disclosure as section 4 below applies to this change too: no Android
  SDK/Gradle/network in this sandbox, so this could not be compiled or run on a device.
  Brace/paren balance was checked on every touched file, and every symbol the new code
  references (`AmberWarning`, `Signal.signalPrice`, `Direction`) was re-read from its
  actual source file, not assumed.

## 0. Headline honest finding (read this first)

The original 27 rule-based strategies were selected/tuned on a **90-day** window
(2026-05-26 → 2026-08-24, see `strategies_parameters.json` → `meta.backtest_period_days`).
Re-run chronologically over the **full year** you supplied (2025-09-02 → 2026-09-02, with
a 45-day warm-up excluded), using the exact same engine logic:

- **Overall: 65.79% win rate, -$55.50 PnL** on $100 starting capital (below the 66.67%
  breakeven win rate implied by the $0.50-win/$1.00-loss payout) — see
  `results/full_year_signals.json` and the monthly table below.
- Performance is **strongly regime-dependent**: Nov 2025–Apr 2026 was a sustained losing
  stretch (worst: March 2026, 53.27% win rate, -$67.50); May–Aug 2026 — which is exactly
  the strategies' own tuning window — was strongly profitable (68–74% win rate). This is
  a textbook case of a model looking great in-sample on the exact period it was fit to,
  and struggling outside it. It's the same problem you described in your message, now
  with a full year of real numbers behind it instead of just a suspicion.
- One strategy (STRAT-023, "Weak Momentum" mean-reversion) supplied nearly all of the
  system's positive PnL; the other 23 firing strategies were net losers as a group.

This is why the fix implemented here is not "re-tune the same 27 rules on more data" —
it's replacing them as the decision-maker with a model trained with proper chronological
walk-forward validation across the *whole* year (all regimes), and demoting the 27
strategies to input features / diagnostic benchmarks only, per your update spec.

## 1. What the AI model actually is (please read before trusting it with capital)

Trained on your full one-year dataset with 5-fold chronological walk-forward validation
(never random-shuffled; each fold trains only on the past, tests on unseen future data):

- **Pooled out-of-sample AUC ≈ 0.73–0.74**, consistently across every fold (including the
  regime that broke the old strategies) — see `training_report.json` inside
  `NEXA_MODEL_PACKAGE.zip` for the exact per-fold numbers.
- **Feature-ablation testing found the model's edge comes almost entirely from
  `entry_move_pct`** — i.e., intra-5-minute-candle momentum persistence (if price has
  already moved in a direction 1-2 minutes into the candle, it's disproportionately
  likely to close that way). A model using *only* that one feature scores within ~0.002
  AUC of the full 28-feature model. The other features (RSI/MACD/ADX/Bollinger/StochRSI/
  swing-distance/the 27 strategies' votes) measured close to **random (~0.52 AUC)** once
  `entry_move_pct` is excluded and evaluated near the decision boundary. This is a
  meaningfully narrower finding than "a rich multi-factor AI" — it's closer to "a properly
  calibrated, continuous version of the momentum check the app's `micro_momentum`
  component already did crudely."
- **In the ADVERSE_REVERSAL zone specifically** (model calling against the current
  intra-candle momentum) — the exact new capability this update was asked to add —
  realized win rate across every confidence bucket tested was **40–49%, at or below a
  coin flip, well under the 66.67% breakeven**. There is no evidence in this data that
  the model can call reversals. Because of this, `adverse_threshold_curve.json` in the
  shipped package has `adverse_entries_enabled: false` — computed, not hand-set — and
  `AiSignalDecisionEngine.kt` respects that: it will not fire adverse-reversal signals
  with this package installed. The infrastructure for it is real and will honor a future
  package that *does* find genuine adverse-zone skill (e.g. with more/newer data, or tick
  data instead of 1-minute bars), but this one honestly doesn't have it.
- A leakage bug was found and fixed mid-development (regime features were reading the
  candle's own not-yet-closed high/low/close, i.e. peeking a few minutes into the future)
  — see `nexa_engine_py/strategy_engine.py` and `build_causal_dataset.py` docstrings for
  the full trace. The numbers above are all post-fix.

**Recommendation**: treat this as a real, modest, well-validated edge in normal-zone
continuation entries — not a solved trading system. Paper-trade / shadow-run before
risking capital, the same way you would with any new strategy, and re-run
`NEXA_PC_Trainer.html` periodically as new data accumulates (markets drift).

## 2. Files added (new `ai/` package)

| File | Purpose |
|---|---|
| `ai/AiModels.kt` | `ModelState`, `FeatureSchema`, `ModelManifest`, `CalibrationTable`, `AdverseThresholdCurve`, `AiDecision` — the data contracts. |
| `ai/AiFeatureExtractor.kt` | Computes the 28-feature causal vector from `MarketDataStore` + minute1/minute2, in the exact order `feature_schema.json` declares. Must stay in lockstep with `nexa_engine_py/build_causal_dataset.py` and `nexa_html_trainer`'s `features.js` — all three were cross-validated against each other on real data (max diff ~1e-13, floating-point noise) during development. |
| `ai/OnnxInferenceEngine.kt` | Thin wrapper over `ai.onnxruntime` (ONNX Runtime Android). Loads `model.onnx`, runs single-row inference. |
| `ai/ModelManager.kt` | Import (from a picked `NEXA_MODEL_PACKAGE.zip`), checksum verification, smoke-test inference before activating, atomic activation with one-step rollback, `StateFlow<ModelState>` for the UI. Storage: `filesDir/nexa_model/{active,previous,staging}/`. |
| `ai/AdaptiveLearningEngine.kt` | Beta-Bernoulli posterior over realized win rate per confidence decile, seeded from the shipped calibration and updated from real signal outcomes. Can only *tighten* the shipped confidence thresholds (never loosen them) if live performance underperforms what calibration promised — it does not retrain the model or rewrite calibration.json on-device. |
| `ai/AiSignalDecisionEngine.kt` | The actual decision function: classifies entry context (NORMAL / ADVERSE_REVERSAL / TOO_LATE) from `entryMovePct`, applies the confidence policy, returns fire/no-fire. Pure function, independently testable. |
| `ai/Indicators.kt` additions | `rsiSeries` (full series, needed for StochRSI) and `stochRsi` — additive only, nothing existing was changed. |

## 3. Files modified

- **`engine/CoreSignalEngine.kt`** — `evaluateCheckpoint` gained two optional trailing
  parameters (`aiModelManager`, `adaptiveLearning`), both defaulting to `null`. **With no
  arguments passed, behavior is unchanged from the original app** — this is the "AI
  fallback" contract from the spec. When a model is `INFERENCE_READY`, the 27 strategies'
  votes are still computed (for the trace UI and as model input features) but no longer
  decide the outcome; `AiSignalDecisionEngine` does. If inference fails for any reason,
  it falls through to the original rule-engine path rather than going silent.
- **`data/model/Signal.kt`**, **`data/local/SignalEntity.kt`** — 5 new trailing fields
  (`decisionSource`, `aiRawScore`, `aiCalibratedProbability`, `aiEntryContext`,
  `aiModelVersion`), all defaulted/nullable, purely additive.
- **`data/local/AppDatabase.kt`** — version 1 → 2, `.fallbackToDestructiveMigration()`
  added. This app has never shipped/compiled (see original README.md), so there is no
  installed v1 database to preserve; if that stops being true before this ships, replace
  this with a real `Migration(1, 2)` that `ALTER TABLE ADD COLUMN`s the 5 new fields
  instead of wiping history.
- **`AppContainer.kt`** — added `modelManager(context)` (process-wide singleton) and
  `adaptiveLearningEngine()`; `backtestEngine(context)` now wires both through.
- **`live/LiveMonitoringService.kt`**, **`backtest/BacktestEngine.kt`** — initialize
  `ModelManager` on start, pass it (+ adaptive engine) into `evaluateCheckpoint`, and
  (live service only) feed real outcomes back into `AdaptiveLearningEngine.recordOutcome`
  when a signal resolves.
- **`ui/screens/SettingsScreen.kt`** — new "AI Model" section: status, import (file
  picker → `ModelManager.importFromUri`), rollback, and a plain-language note on whether
  adverse-reversal entries are currently enabled and why.
- **`app/build.gradle.kts`** — added `com.microsoft.onnxruntime:onnxruntime-android:1.18.0`
  (official Maven Central artifact; needs network on first Gradle sync, same as every
  other dependency here).

## 4. What has and hasn't been tested (read this — same disclosure the original README made)

This patch was written in a sandboxed environment with **no Android SDK, no Gradle, and
no network access** — identical constraints to whoever built the original app (see its
README). So, same as before:

**Not done / can't be done here:** compiling, running on a device or emulator, or any
Gradle build. There will likely be small compile errors to fix (an import path, a
`Direction` vs `SignalDirection` enum mismatch, etc.) — the diff is large enough that I'd
be overstating confidence to claim otherwise.

**Genuinely done and verified, not just written:**
- Every formula in the new `ai/` Kotlin code has a Python **and** a from-scratch
  JavaScript implementation that were cross-checked against each other and against real
  BTC data (see `nexa_engine_py/` test runs and `nexa_html_trainer/js_dev/` — indicators
  matched to ~1e-13, the full 28-feature dataset matched to exact equality across 25,918
  real rows, ONNX bytes written by Python were successfully parsed and correctly executed
  by an independently-written JS reader and vice versa).
- The actual `model.onnx` shipped in `NEXA_MODEL_PACKAGE.zip` was loaded and run with the
  real `onnxruntime` Python package (same engine family as `onnxruntime-android`) and
  matched a pure-NumPy reference computation to ~1e-7 (float32 precision).
- `NEXA_PC_Trainer.html` was tested end-to-end in an actual headless Chromium browser
  (not just Node) on your real one-year CSV: file upload → parse → validate → resample →
  build 185,572-row feature set → 5-fold walk-forward train → calibrate → export ONNX →
  verify → package → download, zero console errors, and the downloaded package re-loads
  correctly in `onnxruntime`.
- Static checks (brace/paren/quote balance) pass on every new/modified Kotlin file, and
  every function signature the new code calls (`Indicators.*`, `EvalContext`,
  `ComponentEvaluator.evaluate`, `MarketRegimeClassifier.classify`, `StrategyDatabase`,
  `Checkpoint`, `Direction`, `MarketDataStore.closed*()`) was re-read from the actual
  source file immediately before being used, not assumed from memory.

If you have Android Studio available, opening the project and letting it sync/build is
the natural next step — expect to fix a handful of small issues, not a rewrite.

## 5. Known simplifications vs. the original spec documents

Being upfront about scope, per the spec's own anti-fabrication instructions:

- **Model architecture**: a single logistic-regression (linear+sigmoid) layer, not a
  deeper network. Chosen because a 16-unit MLP tested no better out-of-sample (see
  `train_walk_forward.py` fold comparisons) — added depth wasn't earning its complexity
  here, not a shortcut taken to save effort.
- **Causal snapshots**: exactly 2 per candle (Checkpoint A / B), matching the finest
  granularity 1-minute source data actually supports — see the methodology note in
  `nexa_engine_py/strategy_engine.py`. True sub-minute/tick data would allow finer
  "continuous within-minute" sampling if you have that data in the future.
- **CVD/order-flow feature**: prototyped using `taker_buy_base` from the raw Binance CSV,
  then dropped from the shipped model — it would require extending Android's `Candle`
  data class and the Binance parsing layer (which this patch deliberately left untouched
  to minimize risk in code that can't be compiled here), and it contributed negligibly to
  OOS AUC anyway.
- **Candlestick pattern library**: the trainer computes basic shape features (body %,
  wick ratios) for minute-1/minute-2, not a full named-pattern library (engulfing,
  hammer, etc.) — those were judged unlikely to add much given how small the non-momentum
  features' contribution already measured out to be.
- **Drift monitoring (PSI/KS)**: not implemented. `AdaptiveLearningEngine`'s Beta-Bernoulli
  tightening is the safety net actually shipped; a full statistical drift dashboard was
  out of scope for this pass.
- **ONNX package uses `raw_data` + unpacked `dims`, not the packed-repeated encoding.**
  Both are valid per the protobuf spec (a compliant parser must accept either), but it's
  worth knowing this isn't byte-identical to what the official `onnx` Python package would
  emit — it's a minimal from-scratch writer, necessary because this sandbox has no network
  to install `onnx`/`protoc`. It has been verified extensively (see section 4), not just
  asserted.

## 6. Model package format (`NEXA_MODEL_PACKAGE.zip`)

```
manifest.json                 - sha256 of every other file, model name/version
model.onnx                    - features[batch,28] -> MatMul(W) -> Add(b) -> Sigmoid -> probability[batch,1]
feature_schema.json           - the 28 feature names, in order, + checkpoint semantics
calibration.json              - isotonic mapping: raw sigmoid output -> calibrated P(GREEN)
adverse_threshold_curve.json  - empirical confidence requirements for NORMAL vs ADVERSE zones
training_report.json          - per-fold walk-forward metrics + the honest limitations above
```

Both `NEXA_PC_Trainer.html` (for future retraining on fresh data) and the one-off Python
training run in `nexa_engine_py/` produce this exact same format — either can be imported
from Settings → AI Model in the app.
