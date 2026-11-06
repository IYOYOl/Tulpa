package com.lxylx.aicamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 胶囊式变焦滑块。
 * 刻度按设备实际支持范围生成（如 0.6 / 1x / 2 / 3 / 10），**固定间距紧凑居中**，
 * 不再铺满整条轨道；点击、拖动、双指捏合都同步。
 */
@Composable
fun ZoomSlider(
    ratio: Float,
    minRatio: Float,
    maxRatio: Float,
    modifier: Modifier = Modifier,
    onRatioChange: (Float) -> Unit
) {
    val min = minRatio.coerceAtLeast(0.1f)
    val max = maxRatio.coerceAtLeast(min)
    val hasRange = max > min * 1.02f
    val stops = remember(min, max) { zoomStops(min, max) }
    val measurer = rememberTextMeasurer()
    // 相邻刻度的固定间距（屏幕太窄时自动压缩）
    val gapMaxPx = with(LocalDensity.current) { 54.dp.toPx() }

    fun layoutOf(width: Float): Pair<Float, Float> {
        if (stops.size < 2) return width / 2f to 0f
        val usable = (width - gapMaxPx * 0.9f).coerceAtLeast(1f)
        val gap = min(gapMaxPx, usable / (stops.size - 1))
        val groupWidth = gap * (stops.size - 1)
        return (width - groupWidth) / 2f to gap
    }

    fun ratioAt(x: Float, width: Float): Float {
        if (stops.size < 2) return stops.firstOrNull() ?: min
        val (startX, gap) = layoutOf(width)
        if (gap <= 0f) return stops.first()
        val t = ((x - startX) / (gap * (stops.size - 1))).coerceIn(0f, 1f)
        val scaled = t * (stops.size - 1)
        val index = floor(scaled).toInt().coerceIn(0, stops.size - 2)
        val frac = scaled - index
        val a = stops[index]
        val b = stops[index + 1]
        return exp(ln(a) + frac * (ln(b) - ln(a))).coerceIn(min, max)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .pointerInput(min, max, stops) {
                detectTapGestures { offset ->
                    if (hasRange) {
                        val (startX, gap) = layoutOf(size.width.toFloat())
                        if (gap > 0f) {
                            // 点哪个刻度就吸附到哪个，保证描边一定能对上
                            val index = ((offset.x - startX) / gap).roundToInt()
                                .coerceIn(0, stops.size - 1)
                            onRatioChange(stops[index])
                        } else {
                            onRatioChange(ratioAt(offset.x, size.width.toFloat()))
                        }
                    }
                }
            }
            .pointerInput(min, max, stops) {
                detectDragGestures { change, _ ->
                    if (hasRange) onRatioChange(ratioAt(change.position.x, size.width.toFloat()))
                }
            }
    ) {
        val h = size.height
        val (startX, gap) = layoutOf(size.width)

        if (!hasRange || stops.size < 2) {
            val pillWidth = h * 2.6f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.12f),
                cornerRadius = CornerRadius(h / 2f),
                topLeft = Offset((size.width - pillWidth) / 2f, 0f),
                size = Size(pillWidth, h)
            )
            val layout = measurer.measure(
                AnnotatedString("1x"),
                style = TextStyle(fontSize = 13.sp, color = Color.White)
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset((size.width - layout.size.width) / 2f, (h - layout.size.height) / 2f)
            )
            return@Canvas
        }

        // 胶囊只包住刻度组本身，不会横跨整块屏幕
        val pillPad = h * 0.95f
        val groupWidth = gap * (stops.size - 1)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.12f),
            cornerRadius = CornerRadius(h / 2f),
            topLeft = Offset(startX - pillPad, 0f),
            size = Size(groupWidth + pillPad * 2, h)
        )

        // 永远高亮"离当前倍率最近"的刻度，保证每个刻度都能亮起描边
        val activeStop = stops.minByOrNull { abs(ln(it) - ln(ratio.coerceAtLeast(0.1f))) }

        stops.forEachIndexed { index, value ->
            val centerX = startX + index * gap
            val label = zoomLabel(value)
            val isActive = activeStop != null && abs(value - activeStop) < 0.001f
            val layout = measurer.measure(
                AnnotatedString(label),
                style = TextStyle(
                    fontSize = if (isActive) 14.sp else 13.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isActive) Accent else Color.White.copy(alpha = 0.45f)
                )
            )
            val left = (centerX - layout.size.width / 2f)
                .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            if (isActive) {
                val circleCenter = Offset(centerX, h / 2f)
                drawCircle(color = Color(0xE61A1A1A), radius = h * 0.46f, center = circleCenter)
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = h * 0.46f,
                    center = circleCenter,
                    style = Stroke(width = 1.5f)
                )
            }
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(left, (h - layout.size.height) / 2f)
            )
        }
    }
}

private fun zoomStops(min: Float, max: Float): List<Float> {
    val candidates = mutableListOf(min, 1f, 2f, 3f, max)
    return candidates
        .filter { it >= min - 0.001f && it <= max + 0.001f }
        .map { it.coerceIn(min, max) }
        .distinctBy { (it * 1000).toInt() }
        .sorted()
}

private fun zoomLabel(value: Float): String = when {
    abs(value - 1f) < 0.01f -> "1x"
    abs(value - 0.6f) < 0.06f -> "0.6"
    value - value.toInt() < 0.05f -> String.format(Locale.US, "%.0f", value)
    else -> String.format(Locale.US, "%.1f", value)
}
