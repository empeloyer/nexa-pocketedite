package com.btcsignal.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btcsignal.app.ui.theme.*

/** A frosted "glass" surface: a soft translucent gradient fill over a hairline
 * highlight border. This is the base building block for every card in the app's
 * glassmorphism theme — cheap to draw (no real-time blur needed) and works down to
 * minSdk 26. */
fun Modifier.glassPanel(shape: Shape = RoundedCornerShape(18.dp)): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(listOf(GlassFillElevated, GlassFill))
    )
    .border(1.dp, Brush.verticalGradient(listOf(GlassHighlight, GlassStroke)), shape)

@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TextPrimary) {
    Column(
        modifier = modifier
            .glassPanel(RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        if (title.isNotEmpty()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** Lightweight equity-curve / PnL line chart, pure Canvas, no external chart library. */
@Composable
fun SimpleLineChart(values: List<Double>, modifier: Modifier = Modifier, lineColor: Color = GreenSignal) {
    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        if (values.size < 2) return@Canvas
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).takeIf { it != 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val points = values.mapIndexed { i, v ->
            Offset(i * stepX, size.height - ((v - minV) / range * size.height).toFloat())
        }
        for (i in 0 until points.size - 1) {
            drawLine(lineColor, points[i], points[i + 1], strokeWidth = 4f, cap = StrokeCap.Round)
        }
    }
}

/** Lightweight bar chart, pure Canvas. Positive values green, negative red. */
@Composable
fun SimpleBarChart(values: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        if (values.isEmpty()) return@Canvas
        val maxAbs = values.maxOf { kotlin.math.abs(it) }.takeIf { it != 0.0 } ?: 1.0
        val barWidth = size.width / values.size * 0.7f
        val gap = size.width / values.size * 0.3f
        val zeroY = size.height / 2f
        values.forEachIndexed { i, v ->
            val barHeight = (kotlin.math.abs(v) / maxAbs * zeroY).toFloat()
            val x = i * (barWidth + gap)
            val color = if (v >= 0) GreenSignal else RedSignal
            val top = if (v >= 0) zeroY - barHeight else zeroY
            drawRect(color, topLeft = Offset(x, top), size = androidx.compose.ui.geometry.Size(barWidth, barHeight))
        }
    }
}

@Composable
fun LabeledValueInline(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TextPrimary) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, modifier = Modifier.weight(0.6f))
    }
}

@Composable
fun DirectionPill(direction: String, modifier: Modifier = Modifier) {
    val color = if (direction == "GREEN") GreenSignal else RedSignal
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.7f))))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(direction, color = Color.Black, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Circular countdown ring: sweeps down from 12 o'clock as the 5-minute candle window
 * elapses, with the remaining mm:ss centered inside. [ringColor] is the neutral accent
 * until a signal is locked, at which point the caller passes the signal's own color
 * (green/red) so the ring visually matches the active prediction.
 */
@Composable
fun CircularCountdown(
    remainingSeconds: Long,
    totalSeconds: Long,
    ringColor: Color,
    modifier: Modifier = Modifier,
    label: String = "Countdown",
    diameter: androidx.compose.ui.unit.Dp = 128.dp,
    ringStrokeWidth: androidx.compose.ui.unit.Dp = 10.dp,
    timeFontSize: androidx.compose.ui.unit.TextUnit = 20.sp,
    showLabel: Boolean = true
) {
    val fraction = if (totalSeconds <= 0) 0f else (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    val mm = remainingSeconds / 60
    val ss = remainingSeconds % 60
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = ringStrokeWidth.toPx()
            val trackColor = GlassStroke
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "%02d:%02d".format(mm, ss),
                color = TextPrimary,
                fontSize = timeFontSize,
                fontWeight = FontWeight.Bold
            )
            if (showLabel) {
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
