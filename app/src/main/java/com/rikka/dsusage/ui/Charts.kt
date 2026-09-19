package com.rikka.dsusage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rikka.dsusage.data.DayStat
import java.time.LocalDate

/**
 * 把稀疏的日数据补成连续日期序列（缺失日补 0），
 * 这样柱子的位置才严格对应日期，x 轴是等距的。
 */
fun fillMissingDays(days: List<DayStat>): List<DayStat> {
    if (days.isEmpty()) return emptyList()
    val first = runCatching { LocalDate.parse(days.first().date) }.getOrNull() ?: return days
    val last = runCatching { LocalDate.parse(days.last().date) }.getOrNull() ?: return days
    val byDate = days.associateBy { it.date }
    val out = ArrayList<DayStat>()
    var d = first
    while (!d.isAfter(last)) {
        val key = d.toString()
        out += byDate[key] ?: DayStat(key, 0L, 0.0)
        d = d.plusDays(1)
    }
    return out
}

/**
 * 环形进度。用于缓存命中率——比横条更能表达"占比"这件事。
 */
@Composable
fun GlassRing(
    progress: Float,
    tint: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 66.dp,
    strokeWidth: Dp = 6.dp,
) {
    val p by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(750, easing = FastOutSlowInEasing),
        label = "ring",
    )
    val track = Color.White.copy(alpha = 0.08f)
    Canvas(modifier.size(diameter)) {
        val sw = strokeWidth.toPx()
        val inset = sw / 2f
        val arcSize = Size(size.width - sw, size.height - sw)
        drawArc(
            color = track,
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(width = sw),
        )
        if (p > 0f) {
            drawArc(
                color = tint,
                startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 面积折线图。用于 Hero 区块的迷你趋势——比柱状更轻，适合放在大数字下方。
 * 最高点会画一个圆点标记。
 */
@Composable
fun GlassAreaChart(
    values: List<Float>,
    tint: Color,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 46.dp,
) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
    }
    if (values.size < 2) return
    val maxV = (values.maxOrNull() ?: 0f).coerceAtLeast(0.0001f)
    val peakIndex = values.indexOfFirst { it >= maxV }

    Canvas(modifier.fillMaxWidth().height(chartHeight)) {
        val n = values.size
        val stepX = size.width / (n - 1)
        fun px(i: Int) = i * stepX
        fun py(v: Float) = size.height * (1f - (v / maxV) * grow.value) * 0.92f + size.height * 0.04f

        val line = Path()
        val fill = Path()
        line.moveTo(px(0), py(values[0]))
        for (i in 1 until n) line.lineTo(px(i), py(values[i]))
        fill.addPath(line)
        fill.lineTo(size.width, size.height)
        fill.lineTo(0f, size.height)
        fill.close()

        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                listOf(tint.copy(alpha = 0.42f), tint.copy(alpha = 0f)),
            ),
        )
        drawPath(
            path = line,
            color = tint,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
        )
        if (peakIndex >= 0 && grow.value > 0.6f) {
            drawCircle(color = tint, radius = 3.dp.toPx(), center = Offset(px(peakIndex), py(values[peakIndex])))
        }
    }
}

/**
 * 柱状图。最近一天用高亮色，其余用主色——一眼看出"最新"在哪。
 */
@Composable
fun GlassBarChart(
    values: List<Float>,
    startLabel: String,
    midLabel: String,
    endLabel: String,
    barColor: Color,
    modifier: Modifier = Modifier,
    highlightColor: Color? = null,
    chartHeight: Dp = 120.dp,
) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
    }
    val maxV = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val hi = highlightColor ?: barColor

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
            val n = values.size
            if (n == 0) return@Canvas
            val gap = if (n > 1) size.width / n * 0.26f else 0f
            val bw = (size.width - gap * (n - 1)) / n
            if (bw <= 0f) return@Canvas
            values.forEachIndexed { i, v ->
                val h = (size.height - 2f) * (v / maxV) * grow.value
                if (h <= 0.5f) return@forEachIndexed
                val isLast = i == n - 1
                drawRoundRect(
                    color = if (isLast) hi else barColor,
                    topLeft = Offset(i * (bw + gap), size.height - h),
                    size = Size(bw, h),
                    cornerRadius = CornerRadius(bw / 2.6f, bw / 2.6f),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(startLabel, midLabel, endLabel).forEach {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
