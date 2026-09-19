package com.rikka.dsusage.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikka.dsusage.data.BalanceInfo
import com.rikka.dsusage.data.ModelStat
import com.rikka.dsusage.data.MonthRecord
import com.rikka.dsusage.data.UsageSnapshot
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import kotlin.math.abs
import java.util.Locale

/* ---------------- 共享状态与工具 ---------------- */

data class DashState(
    val info: BalanceInfo? = null,
    val available: Boolean = false,
    val balError: String? = null,
    val balLoading: Boolean = false,
    val snap: UsageSnapshot? = null,
    val useError: String? = null,
    val useLoading: Boolean = false,
    val expired: Boolean = false,
    val ym: YearMonth = YearMonth.now(),
    val apiKey: String = "",
    val token: String = "",
    val dataAtMs: Long = 0L,
    val fromCache: Boolean = false,
    val history: List<MonthRecord> = emptyList(),
)

/** 每秒跳动一次的当前时刻，供倒计时使用。 */
@Composable
fun rememberNowTicker(): ZonedDateTime {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = ZonedDateTime.now()
        }
    }
    return now
}

fun fmtTokens(n: Long): String = when {
    n >= 100_000_000L -> String.format(Locale.CHINA, "%.2f 亿", n / 100_000_000.0)
    n >= 10_000L -> String.format(Locale.CHINA, "%.2f 万", n / 10_000.0)
    else -> n.toString()
}

fun fmtMoney(v: Double): String = String.format(Locale.CHINA, "¥%.2f", v)
fun fmtPct(v: Double): String = String.format(Locale.CHINA, "%.0f", v * 100) + "%"

/** 玻璃质感的进度条，数值变化时平滑过渡。 */
@Composable
fun GlassProgress(fraction: Float, tint: Color, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "glassProgress",
    )
    Box(
        modifier
            .height(7.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.16f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(f)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(tint, tint.copy(alpha = 0.6f))))
        )
    }
}

/** 三格并列的统计块。 */
@Composable
fun StatTriple(vararg items: Pair<String, String>) {
    val c = LocalGlass.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { (label, value) ->
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = c.onGlassMuted)
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = c.onGlass,
                )
            }
        }
    }
}

@Composable
fun GlassPrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalGlass.current
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.accent,
            contentColor = if (c.isDark) Color(0xFF070B1A) else Color.White,
        ),
    ) { Text(text) }
}

@Composable
fun GlassGhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalGlass.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.onGlass),
    ) { Text(text) }
}

/* ---------------- 概览 ---------------- */

@Composable
fun OverviewTab(
    state: DashState,
    onRefresh: () -> Unit,
    onNeedLogin: () -> Unit,
    onTopUp: () -> Unit,
    onOpenBalance: () -> Unit,
    onOpenPeak: () -> Unit,
) {
    val c = LocalGlass.current
    val busy = state.balLoading || state.useLoading

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 30.dp, onClick = onOpenBalance) {
            Text("账户余额", style = MaterialTheme.typography.labelLarge, color = c.onGlassMuted)
            if (state.balLoading && state.info == null) {
                CircularProgressIndicator(Modifier.size(28.dp), color = c.accent)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingMoney(
                        target = state.info?.totalBalance?.toDoubleOrNull() ?: 0.0,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = c.onGlass,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.info?.currency ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onGlassMuted,
                    )
                }
                GlassPill(
                    text = if (state.available) "账户状态正常" else "余额不可用",
                    tint = if (state.available) c.offPeak else c.peak,
                )
                val bal = state.info?.totalBalance?.toDoubleOrNull()
                val cost = state.snap?.totalCost ?: 0.0
                if (bal != null && cost > 0.0 && state.ym == YearMonth.now()) {
                    val elapsed = LocalDate.now().dayOfMonth.coerceAtLeast(1)
                    val daily = cost / elapsed
                    if (daily > 0) {
                        val left = (bal / daily).toInt()
                        Text(
                            "按本月日均 ¥${String.format(Locale.CHINA, "%.2f", daily)} 估算，余额约可用 $left 天",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (left <= 7) c.peak else c.onGlassMuted,
                        )
                    }
                }
            }
            state.balError?.let { Text(it, color = c.peak, style = MaterialTheme.typography.bodySmall) }
            DetailHint("余额构成与可用天数")
        }

        FreshnessLine(state)

        PeakStatusStrip(onOpenPeak)

        GlassCard(Modifier.fillMaxWidth()) {
            Text("本月概览", style = MaterialTheme.typography.labelLarge, color = c.onGlassMuted)
            val snap = state.snap
            if (snap == null) {
                Text(
                    if (state.expired) "登录已失效，请重新获取用量凭证"
                    else "尚未获取用量数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onGlassMuted,
                )
                GlassPrimaryButton(
                    text = if (state.expired) "重新登录" else "去获取",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNeedLogin,
                )
            } else {
                StatTriple(
                    "消费金额" to fmtMoney(snap.totalCost),
                    "请求次数" to String.format(Locale.CHINA, "%,d", snap.totalRequests),
                    "Tokens" to fmtTokens(snap.totalTokens),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "缓存命中率 ${fmtPct(snap.cacheRatio)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onGlassMuted,
                    )
                    GlassProgress(snap.cacheRatio.toFloat(), c.accent, Modifier.fillMaxWidth())
                }
            }
            state.useError?.takeIf { !state.expired }?.let {
                Text(it, color = c.peak, style = MaterialTheme.typography.bodySmall)
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("余额构成", style = MaterialTheme.typography.labelLarge, color = c.onGlassMuted)
            listOf(
                "充值余额" to (state.info?.toppedUpBalance ?: "--"),
                "赠送余额" to (state.info?.grantedBalance ?: "--"),
            ).forEach { (label, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = c.onGlassMuted)
                    Text("¥$v", style = MaterialTheme.typography.bodyMedium, color = c.onGlass, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassPrimaryButton(
                text = if (busy) "刷新中…" else "刷新",
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = onRefresh,
            )
            GlassGhostButton(
                modifier = Modifier.weight(1f),
                text = "去充值",
            onClick = onTopUp,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ---------------- 用量 ---------------- */

@Composable
fun UsageTab(
    state: DashState,
    onMonthChange: (YearMonth) -> Unit,
    onOpenModel: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val c = LocalGlass.current
    val snap = state.snap
    val ym = state.ym

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth(), contentPadding = 10.dp) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onMonthChange(ym.minusMonths(1)) }) {
                    Text("‹ 上月", color = c.accent)
                }
                Text(
                    "${ym.year} 年 ${ym.monthValue} 月",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = c.onGlass,
                )
                TextButton(
                    onClick = { onMonthChange(ym.plusMonths(1)) },
                    enabled = ym < YearMonth.now(),
                ) {
                    Text("下月 ›", color = if (ym < YearMonth.now()) c.accent else c.onGlassMuted)
                }
            }
        }

        HistoryTrendCard(state) { onOpenHistory() }

        when {
            state.useLoading && snap == null -> GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = c.accent)
                }
            }
            snap == null -> GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    if (state.expired) "登录已失效" else "该月没有可用数据",
                    color = c.onGlassMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("本月汇总", style = MaterialTheme.typography.labelLarge, color = c.onGlassMuted)
                    StatTriple(
                        "消费金额" to fmtMoney(snap.totalCost),
                        "请求次数" to String.format(Locale.CHINA, "%,d", snap.totalRequests),
                        "Tokens" to fmtTokens(snap.totalTokens),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "缓存命中率 ${fmtPct(snap.cacheRatio)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onGlassMuted,
                        )
                        GlassProgress(snap.cacheRatio.toFloat(), c.offPeak, Modifier.fillMaxWidth())
                    }
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    Text("每日 Token 趋势", style = MaterialTheme.typography.labelLarge, color = c.onGlassMuted)
                    val filled = remember(snap) { fillMissingDays(snap.days) }
                    if (filled.isEmpty()) {
                        Text("暂无逐日数据", color = c.onGlassMuted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        val mid = filled[filled.size / 2].date.takeLast(5)
                        GlassBarChart(
                            values = filled.map { it.tokens.toFloat() },
                            startLabel = filled.first().date.takeLast(5),
                            midLabel = mid,
                            endLabel = filled.last().date.takeLast(5),
                            barColor = c.accent,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val peakDay = filled.maxByOrNull { it.tokens }
                        if (peakDay != null && peakDay.tokens > 0) {
                            Text(
                                "峰值 ${fmtTokens(peakDay.tokens)} · ${peakDay.date}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.onGlassMuted,
                            )
                        }
                        val lastActive = filled.lastOrNull { it.tokens > 0 }
                        if (lastActive != null) {
                            Text(
                                "最新一日 ${lastActive.date}：${fmtTokens(lastActive.tokens)} tokens，${fmtMoney(lastActive.cost)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.onGlassMuted,
                            )
                        }
                    }
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    Text("分模型明细", style = MaterialTheme.typography.labelLarge, color = c.onGlassMuted)
                    snap.models.filter { it.tokens > 0 || it.cost > 0 }.forEach { m ->
                        ModelRow(m) { onOpenModel(m.model) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ModelRow(m: ModelStat, onClick: () -> Unit) {
    val c = LocalGlass.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(m.model, style = MaterialTheme.typography.bodyMedium, color = c.onGlass, fontWeight = FontWeight.Medium)
            Text(fmtMoney(m.cost), style = MaterialTheme.typography.bodyMedium, color = c.onGlass)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${fmtTokens(m.tokens)} · ${String.format(Locale.CHINA, "%,d", m.requests)} 次",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
            Text(
                "命中 ${fmtPct(m.cacheRatio)}",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
        }
    }
}

/** 可下钻卡片的提示行，同时充当点击可发现性的提示。 */
@Composable
fun DetailHint(text: String) {
    Text(
        "查看$text ›",
        style = MaterialTheme.typography.labelSmall,
        color = LocalGlass.current.accent,
    )
}

private fun freshnessLabel(dataAtMs: Long, fromCache: Boolean): String {
    if (dataAtMs <= 0L) return "尚未获取数据"
    val t = java.time.Instant.ofEpochMilli(dataAtMs).atZone(java.time.ZoneId.systemDefault())
    val hh = t.hour.toString().padStart(2, '0')
    val mm = t.minute.toString().padStart(2, '0')
    return if (fromCache) "缓存数据 · $hh:$mm（下拉可刷新）" else "已更新 · $hh:$mm"
}

/** 数据新鲜度提示：区分"缓存数据"与"刚拉取"。 */
@Composable
fun FreshnessLine(state: DashState) {
    Text(
        freshnessLabel(state.dataAtMs, state.fromCache),
        style = MaterialTheme.typography.labelSmall,
        color = LocalGlass.current.onGlassMuted,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/** 历史趋势：环比上月 + 近半年消费。整卡可点进完整记录。 */
@Composable
fun HistoryTrendCard(state: DashState, onClick: () -> Unit) {
    val c = LocalGlass.current
    val hist = state.history

    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Text("历史趋势", style = MaterialTheme.typography.labelLarge, color = c.onGlassMuted)

        if (hist.isEmpty()) {
            Text(
                "还没有归档记录。成功拉取某个月的用量后会自动归档。",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
            return@GlassCard
        }

        val cur = hist.firstOrNull { it.month == state.ym.toString() }
        val prev = hist.firstOrNull { it.month == state.ym.minusMonths(1).toString() }

        if (cur != null && prev != null && prev.cost > 0.0) {
            val delta = cur.cost - prev.cost
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "环比 ${prev.month}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onGlassMuted,
                )
                Text(
                    (if (delta >= 0) "▲ " else "▼ ") + fmtMoney(abs(delta)) +
                        "（" + fmtPct(abs(delta / prev.cost)) + "）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (delta >= 0) c.peak else c.offPeak,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            Text(
                "缺少上月归档，暂时算不出环比",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
        }

        val last = hist.takeLast(6)
        if (last.size >= 2) {
            GlassBarChart(
                values = last.map { it.cost.toFloat() },
                startLabel = last.first().month.takeLast(2) + "月",
                midLabel = last[last.size / 2].month.takeLast(2) + "月",
                endLabel = last.last().month.takeLast(2) + "月",
                barColor = c.accent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DetailHint("全部 ${hist.size} 个月记录")
    }
}
