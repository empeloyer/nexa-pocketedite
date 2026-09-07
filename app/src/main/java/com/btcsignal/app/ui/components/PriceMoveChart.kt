package com.btcsignal.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btcsignal.app.live.LiveEngineState
import com.btcsignal.app.ui.theme.AccentPurple
import com.btcsignal.app.ui.theme.AmberWarning
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TextPrimary
import com.btcsignal.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

private const val CANDLE_MS = 5 * 60 * 1000L

/**
 * Live "price move since candle open" chart, ported from the magic.z1 app's
 * TargetChart widget. Shows the price path since the current 5m candle opened
 * relative to a dashed line at the candle's open price (green fill above,
 * red fill below the open), with a glowing live-price line, restyled to this
 * app's glass theme and placed where the old standalone "Current Prediction"
 * card used to sit.
 *
 * When [signalPrice] is set (a signal is active for this candle), a solid yellow
 * line is drawn at that exact price -- the price the candle was AT the moment the
 * signal fired -- and the green/red fill switches to use it, instead of the open,
 * as the win/lose boundary, matching `CoreSignalEngine.evaluateResult`: red wins
 * below the yellow line, green wins above it. The dashed "Target" line at the
 * candle open is left in place either way, purely as a reference.
 */
@Composable
fun PriceMoveChart(
    livePrice: Double?,
    candleOpenPrice: Double,
    candleOpenTime: Long,
    countdownMs: Long,
    isConnected: Boolean,
    signalPrice: Double? = null,
    modifier: Modifier = Modifier
) {
    val target = candleOpenPrice.takeIf { it > 0.0 }
    val elapsedMs = (CANDLE_MS - countdownMs).coerceIn(0L, CANDLE_MS)

    // x = elapsed ms, y = price. Backed by LiveEngineState (not a local `remember`) so the
    // walked path survives navigating to another bottom-nav tab and back -- see its KDoc.
    val points = LiveEngineState.priceMovePoints
    val currentLivePrice by rememberUpdatedState(livePrice)
    val currentTarget by rememberUpdatedState(target)

    LaunchedEffect(candleOpenTime, target) {
        val t = target ?: return@LaunchedEffect
        if (candleOpenTime > 0 && LiveEngineState.priceMoveLastCandleOpenTime != candleOpenTime) {
            points.clear()
            points.add(Offset(0f, t.toFloat()))
            LiveEngineState.priceMoveLastCandleOpenTime = candleOpenTime
        }
    }

    var anchorElapsedMs by remember { mutableStateOf(elapsedMs) }
    var anchorRealtimeMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(elapsedMs) {
        anchorElapsedMs = elapsedMs
        anchorRealtimeMs = android.os.SystemClock.elapsedRealtime()
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            val interpolatedElapsed = (anchorElapsedMs + (android.os.SystemClock.elapsedRealtime() - anchorRealtimeMs))
                .coerceIn(0L, CANDLE_MS)
            val p = currentLivePrice
            val t = currentTarget
            if (p != null && t != null) {
                points.add(Offset(interpolatedElapsed.toFloat(), p.toFloat()))
                if (points.size > 1600) points.removeAt(0)
            }
            delay(200)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Price Move Since Candle Open", style = MaterialTheme.typography.titleMedium)
            LiveBadge()
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            val t = target
            if (t == null || points.isEmpty()) return@Canvas

            val padRight = 62.dp.toPx()
            val padTop = 4.dp.toPx()
            val padBottom = 4.dp.toPx()
            val chartW = (size.width - padRight).coerceAtLeast(1f)
            val chartH = (size.height - padTop - padBottom).coerceAtLeast(1f)

            var maxDev = 8.0
            points.forEach { p -> maxDev = max(maxDev, abs(p.y - t.toFloat()).toDouble()) }
            if (signalPrice != null) maxDev = max(maxDev, abs(signalPrice - t).toDouble())
            maxDev *= 1.35
            val yMin = t - maxDev
            val yMax = t + maxDev
            val span = (yMax - yMin).takeIf { it > 0.0 } ?: 1.0

            fun xOf(elapsed: Float) = (elapsed / CANDLE_MS.toFloat()) * chartW
            fun yOf(price: Float) = padTop + (1f - ((price - yMin) / span).toFloat()) * chartH
            val targetY = yOf(t.toFloat())
            // Win/lose boundary for shading: the signal price once one exists (matches
            // CoreSignalEngine.evaluateResult), otherwise the candle open as before.
            val winBoundaryY = if (signalPrice != null) yOf(signalPrice.toFloat()) else targetY

            // green zone above the win boundary, red zone below it
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(GreenSignal.copy(alpha = 0.22f), GreenSignal.copy(alpha = 0.02f)),
                    startY = padTop, endY = winBoundaryY.coerceAtLeast(padTop)
                ),
                topLeft = Offset(0f, padTop),
                size = Size(chartW, (winBoundaryY - padTop).coerceAtLeast(0f))
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(RedSignal.copy(alpha = 0.02f), RedSignal.copy(alpha = 0.20f)),
                    startY = winBoundaryY, endY = (padTop + chartH).coerceAtLeast(winBoundaryY)
                ),
                topLeft = Offset(0f, winBoundaryY),
                size = Size(chartW, (padTop + chartH - winBoundaryY).coerceAtLeast(0f))
            )

            // grid lines + $ price labels
            val step = niceStep(span)
            var v = ceil(yMin / step) * step
            val gridPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#9AA3B8")
                textSize = 11.sp.toPx()
                isAntiAlias = true
            }
            while (v <= yMax) {
                val y = yOf(v.toFloat())
                drawLine(
                    color = Color.White.copy(alpha = 0.05f),
                    start = Offset(0f, y), end = Offset(chartW, y), strokeWidth = 1f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "$" + "%,.0f".format(v), chartW + 8.dp.toPx(), y + 4.dp.toPx(), gridPaint
                )
                v += step
            }

            // dashed "open price" target line + "Target" pill
            drawLine(
                color = Color.White.copy(alpha = 0.38f),
                start = Offset(0f, targetY), end = Offset(chartW, targetY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
            drawRect(
                color = Color(0xFF141925),
                topLeft = Offset(chartW + 3.dp.toPx(), targetY - 10.dp.toPx()),
                size = Size(padRight - 6.dp.toPx(), 20.dp.toPx())
            )
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 11.sp.toPx()
                isFakeBoldText = true
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText("Target", chartW + 8.dp.toPx(), targetY + 4.dp.toPx(), labelPaint)

            // solid yellow "signal price" line: the exact price when the active signal
            // fired. This -- not the dashed Target line above -- is the win/lose boundary.
            if (signalPrice != null) {
                val signalY = yOf(signalPrice.toFloat())
                drawLine(
                    color = AmberWarning,
                    start = Offset(0f, signalY), end = Offset(chartW, signalY),
                    strokeWidth = 2.dp.toPx()
                )
                drawRect(
                    color = Color(0xFF141925),
                    topLeft = Offset(chartW + 3.dp.toPx(), signalY - 10.dp.toPx()),
                    size = Size(padRight - 6.dp.toPx(), 20.dp.toPx())
                )
                val signalLabelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#FFC24B")
                    textSize = 11.sp.toPx()
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText("Signal", chartW + 8.dp.toPx(), signalY + 4.dp.toPx(), signalLabelPaint)
            }

            // glowing live-price path
            if (points.size >= 2) {
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = xOf(p.x); val y = yOf(p.y)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path, color = AccentPurple,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            points.lastOrNull()?.let { last ->
                val lx = xOf(last.x); val ly = yOf(last.y)
                drawCircle(AccentPurple.copy(alpha = 0.35f), radius = 12.dp.toPx(), center = Offset(lx, ly))
                drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(lx, ly))
                drawCircle(AccentPurple, radius = 4.dp.toPx(), center = Offset(lx, ly), style = Stroke(width = 2.dp.toPx()))
            }
        }

        Text(
            if (isConnected) "Live connection established" else "Connecting to Binance...",
            color = TextSecondary, fontSize = 11.sp
        )
    }
}

@Composable
private fun LiveBadge() {
    val infinite = rememberInfiniteTransition(label = "priceMoveChartPulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseAlpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .glassPanel(RoundedCornerShape(100))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text("Binance Live", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(GreenSignal.copy(alpha = alpha), CircleShape)
        )
    }
}

private fun niceStep(range: Double): Double {
    val rough = range / 4.0
    val mag = 10.0.pow(floor(log10(if (rough > 0.0) rough else 1.0)))
    val norm = rough / mag
    val step = when {
        norm < 1.5 -> 1.0
        norm < 3.0 -> 2.0
        norm < 7.0 -> 5.0
        else -> 10.0
    }
    return step * mag
}
