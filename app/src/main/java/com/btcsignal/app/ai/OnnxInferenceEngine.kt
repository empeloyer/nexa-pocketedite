package com.btcsignal.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Wraps a single loaded ONNX session. One instance per active model; call [close] before
 * loading a replacement (ModelManager handles that as part of atomic activation/rollback).
 *
 * Dependency added in app/build.gradle.kts: com.microsoft.onnxruntime:onnxruntime-android.
 * NOTE (honesty, matches README.md's own disclosure style): this file cannot be compiled
 * or run in the sandbox this patch was written in (no Android SDK / no network to fetch
 * the Gradle dependency) - it is written carefully against the documented
 * ai.onnxruntime.* Java API and the exact 452-byte model this project's own
 * nexa_engine_py/verify_onnx.py already loaded and numerically verified with the same
 * underlying ONNX Runtime engine (desktop onnxruntime Python package, same op set:
 * MatMul + Add + Sigmoid, opset 13), but it has not been exercised on-device. Test on a
 * real device before trusting it with real signals - see PATCH_NOTES.md "What has and
 * hasn't been tested".
 */
class OnnxInferenceEngine {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String = "features"
    private var featureCount: Int = 0

    val isReady: Boolean get() = session != null

    fun load(modelBytes: ByteArray, expectedFeatureCount: Int): Result<Unit> {
        return try {
            close()
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            val s = e.createSession(modelBytes, opts)
            val inName = s.inputNames.iterator().next()
            val inputShape = (s.inputInfo[inName]?.info as? ai.onnxruntime.TensorInfo)?.shape
            val declaredF = inputShape?.getOrNull(1)?.toInt()
            if (declaredF != null && declaredF > 0 && declaredF != expectedFeatureCount) {
                s.close()
                return Result.failure(IllegalStateException(
                    "model.onnx expects $declaredF features but feature_schema.json declares $expectedFeatureCount"
                ))
            }
            env = e
            session = s
            inputName = inName
            featureCount = expectedFeatureCount
            Result.success(Unit)
        } catch (t: Throwable) {
            close()
            Result.failure(t)
        }
    }

    /** Runs inference for one checkpoint. Returns the RAW (pre-calibration) P(GREEN) in [0,1]. */
    fun predictRaw(features: DoubleArray): Result<Double> {
        val s = session ?: return Result.failure(IllegalStateException("no model loaded"))
        val e = env ?: return Result.failure(IllegalStateException("no environment"))
        if (features.size != featureCount) {
            return Result.failure(IllegalArgumentException("expected $featureCount features, got ${features.size}"))
        }
        return try {
            val floatData = FloatArray(features.size) { features[it].toFloat() }
            OnnxTensor.createTensor(e, FloatBuffer.wrap(floatData), longArrayOf(1, features.size.toLong())).use { tensor ->
                s.run(mapOf(inputName to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = result[0].value
                    val prob = when (out) {
                        is Array<*> -> ((out[0] as FloatArray)[0]).toDouble()
                        is FloatArray -> out[0].toDouble()
                        else -> return Result.failure(IllegalStateException("unexpected ONNX output shape: $out"))
                    }
                    Result.success(prob.coerceIn(0.0, 1.0))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun close() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        env = null
    }
}
