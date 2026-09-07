package com.btcsignal.app.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * File-based model store under app-private storage (no external permissions needed):
 *   filesDir/nexa_model/active/    - the currently loaded package (model.onnx + json files)
 *   filesDir/nexa_model/previous/  - the last-replaced package, kept for one-step rollback
 *   filesDir/nexa_model/staging/   - scratch space for the package currently being verified
 *
 * REQUIRED files inside a NEXA_MODEL_PACKAGE.zip: manifest.json, model.onnx,
 * feature_schema.json, calibration.json, adverse_threshold_curve.json. training_report.json
 * is copied through if present but not required to activate.
 */
class ModelManager(private val context: Context) {

    private val root = File(context.filesDir, "nexa_model")
    private val activeDir = File(root, "active")
    private val previousDir = File(root, "previous")
    private val stagingDir = File(root, "staging")

    private val _state = MutableStateFlow(ModelState.MODEL_NOT_INSTALLED)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("No model installed - using the 27-strategy engine directly.")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    var activeManifest: ModelManifest? = null; private set
    var activeSchema: FeatureSchema? = null; private set
    var activeCalibration: CalibrationTable? = null; private set
    var activeThresholds: AdverseThresholdCurve? = null; private set

    private val inference = OnnxInferenceEngine()

    /** Call once at app startup (e.g. from AppContainer) to pick up a previously-activated model. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (activeDir.exists() && File(activeDir, "model.onnx").exists()) {
            loadFrom(activeDir).onFailure {
                _state.value = ModelState.LOAD_FAILED
                _statusMessage.value = "Stored model failed to reload (${it.message}) - falling back to the 27-strategy engine."
            }
        }
    }

    fun currentInferenceEngine(): OnnxInferenceEngine? = if (_state.value == ModelState.INFERENCE_READY) inference else null

    suspend fun importFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _state.value = ModelState.VERIFYING
            _statusMessage.value = "Verifying package..."
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            val resolver = context.contentResolver
            val entries = mutableMapOf<String, ByteArray>()
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            entries[entry.name] = zip.readBytes()
                        }
                        entry = zip.nextEntry
                    }
                }
            } ?: return@withContext Result.failure<Unit>(IllegalStateException("could not open file")).also {
                _state.value = ModelState.VERIFICATION_FAILED
                _statusMessage.value = "Could not open the selected file."
            }

            val required = listOf("manifest.json", "model.onnx", "feature_schema.json", "calibration.json", "adverse_threshold_curve.json")
            val missing = required.filter { it !in entries }
            if (missing.isNotEmpty()) {
                _state.value = ModelState.VERIFICATION_FAILED
                _statusMessage.value = "Package is missing: ${missing.joinToString(", ")}"
                return@withContext Result.failure(IllegalStateException("missing files: $missing"))
            }

            val manifestJson = JSONObject(String(entries["manifest.json"]!!, Charsets.UTF_8))
            val checksums = manifestJson.getJSONObject("files")
            for (fname in listOf("model.onnx", "feature_schema.json", "calibration.json", "adverse_threshold_curve.json")) {
                val expected = checksums.optString(fname, "")
                val actual = sha256Hex(entries[fname]!!)
                if (expected.isNotEmpty() && !expected.equals(actual, ignoreCase = true)) {
                    _state.value = ModelState.VERIFICATION_FAILED
                    _statusMessage.value = "Checksum mismatch for $fname - package may be corrupted. Not activated."
                    return@withContext Result.failure(IllegalStateException("checksum mismatch: $fname"))
                }
            }

            for ((name, bytes) in entries) {
                if ("/" in name || name.contains("..")) continue // path-traversal guard
                File(stagingDir, name).writeBytes(bytes)
            }

            // smoke-test: load the staged model and run one real inference before activating
            val schema = parseSchema(entries["feature_schema.json"]!!)
            val probe = DoubleArray(schema.featureCount) { 0.0 }
            val probeEngine = OnnxInferenceEngine()
            val loadResult = probeEngine.load(entries["model.onnx"]!!, schema.featureCount)
            if (loadResult.isFailure) {
                probeEngine.close()
                _state.value = ModelState.VERIFICATION_FAILED
                _statusMessage.value = "model.onnx failed to load: ${loadResult.exceptionOrNull()?.message}"
                return@withContext Result.failure(loadResult.exceptionOrNull() ?: IllegalStateException("load failed"))
            }
            val predictResult = probeEngine.predictRaw(probe)
            probeEngine.close()
            if (predictResult.isFailure) {
                _state.value = ModelState.VERIFICATION_FAILED
                _statusMessage.value = "model.onnx loaded but a test inference failed: ${predictResult.exceptionOrNull()?.message}"
                return@withContext Result.failure(predictResult.exceptionOrNull() ?: IllegalStateException("inference failed"))
            }

            // atomic-ish activation: previous <- active, active <- staging
            if (activeDir.exists()) {
                previousDir.deleteRecursively()
                activeDir.copyRecursively(previousDir, overwrite = true)
            }
            activeDir.deleteRecursively()
            stagingDir.copyRecursively(activeDir, overwrite = true)
            stagingDir.deleteRecursively()

            loadFrom(activeDir).onSuccess {
                _statusMessage.value = "Model activated: ${activeManifest?.modelVersion ?: "unknown version"}"
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            _state.value = ModelState.VERIFICATION_FAILED
            _statusMessage.value = "Import failed: ${t.message}"
            Result.failure(t)
        }
    }

    suspend fun rollback(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!previousDir.exists() || !File(previousDir, "model.onnx").exists()) {
            return@withContext Result.failure(IllegalStateException("no previous model to roll back to"))
        }
        val tmp = File(root, "rollback_tmp")
        tmp.deleteRecursively()
        activeDir.copyRecursively(tmp, overwrite = true)
        activeDir.deleteRecursively()
        previousDir.copyRecursively(activeDir, overwrite = true)
        previousDir.deleteRecursively()
        tmp.copyRecursively(previousDir, overwrite = true)
        tmp.deleteRecursively()
        loadFrom(activeDir).onSuccess { _statusMessage.value = "Rolled back to ${activeManifest?.modelVersion ?: "previous model"}" }
    }

    private suspend fun loadFrom(dir: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val manifest = parseManifest(File(dir, "manifest.json").readBytes())
            val schema = parseSchema(File(dir, "feature_schema.json").readBytes())
            val calib = parseCalibration(File(dir, "calibration.json").readBytes())
            val thresholds = parseThresholds(File(dir, "adverse_threshold_curve.json").readBytes())
            val modelBytes = File(dir, "model.onnx").readBytes()

            val loadResult = inference.load(modelBytes, schema.featureCount)
            if (loadResult.isFailure) {
                _state.value = ModelState.LOAD_FAILED
                return@withContext Result.failure(loadResult.exceptionOrNull() ?: IllegalStateException("load failed"))
            }
            activeManifest = manifest
            activeSchema = schema
            activeCalibration = calib
            activeThresholds = thresholds
            _state.value = ModelState.INFERENCE_READY
            Result.success(Unit)
        } catch (t: Throwable) {
            _state.value = ModelState.LOAD_FAILED
            _statusMessage.value = "Failed to load stored model: ${t.message}"
            Result.failure(t)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseManifest(bytes: ByteArray): ModelManifest {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val filesObj = o.getJSONObject("files")
        val checksums = mutableMapOf<String, String>()
        filesObj.keys().forEach { k -> checksums[k] = filesObj.getString(k) }
        return ModelManifest(
            packageFormatVersion = o.optInt("package_format_version", 1),
            modelName = o.optString("model_name", "unknown"),
            modelVersion = o.optString("model_version", "unknown"),
            asset = o.optString("asset", "BTCUSDT"),
            targetTimeframe = o.optString("target_timeframe", "5m"),
            fileChecksums = checksums,
        )
    }

    private fun parseSchema(bytes: ByteArray): FeatureSchema {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val arr = o.getJSONArray("feature_order")
        val order = (0 until arr.length()).map { arr.getString(it) }
        return FeatureSchema(
            schemaVersion = o.optInt("schema_version", 1),
            inputTensorName = o.optString("input_tensor_name", "features"),
            outputTensorName = o.optString("output_tensor_name", "probability"),
            featureOrder = order,
        )
    }

    private fun parseCalibration(bytes: ByteArray): CalibrationTable {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val arr = o.getJSONArray("points")
        val points = (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            CalibrationPoint(p.getDouble("raw"), p.getDouble("calibrated"))
        }.sortedBy { it.raw }
        return CalibrationTable(points)
    }

    private fun parseThresholds(bytes: ByteArray): AdverseThresholdCurve {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        return AdverseThresholdCurve(
            breakevenWinRate = o.optDouble("breakeven_win_rate", 2.0 / 3.0),
            margin = o.optDouble("margin", 0.03),
            normalMinConfidence = if (o.isNull("normal_min_confidence")) null else o.optDouble("normal_min_confidence"),
            adverseMinConfidence = if (o.isNull("adverse_min_confidence")) null else o.optDouble("adverse_min_confidence"),
        )
    }
}
