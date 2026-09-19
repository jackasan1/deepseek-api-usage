package com.rikka.dsusage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

/** 柱状图，进场时柱子从底部生长出来。 */
@Composable
fun GlassBarChart(
    values: List<Float>,
    startLabel: String,
    midLabel: String,
    endLabel: String,
    barColor: Color,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 120.dp,
) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
    }
    val maxV = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)

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
                drawRoundRect(
                    color = barColor,
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
