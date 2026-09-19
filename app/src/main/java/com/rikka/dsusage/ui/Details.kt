package com.rikka.dsusage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikka.dsusage.data.DayStat
import com.rikka.dsusage.data.ModelStat
import com.rikka.dsusage.data.MonthRecord
import com.rikka.dsusage.data.PeakPricing
import java.time.Duration
import java.time.LocalDate
import kotlin.math.abs
import java.util.Locale

/** 主页面可以下钻到的详情页。 */
sealed interface Detail {
    data object Balance : Detail
    data object Peak : Detail
    data object History : Detail
    data class Model(val name: String) : Detail
}

/* ---------------- 通用 ---------------- */

@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = LocalGlass.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = c.accent, fontSize = 16.sp) }
            Text(
                title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = c.onGlass,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun KvRow(label: String, value: String, valueColor: Color? = null) {
    val c = LocalGlass.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.onGlassMuted)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: c.onGlass,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = LocalGlass.current.onGlassMuted)
}

/* ---------------- 余额详情 ---------------- */

@Composable
fun BalanceDetail(state: DashState, onBack: () -> Unit, onTopUp: () -> Unit) {
    val c = LocalGlass.current
    val bal = state.info?.totalBalance?.toDoubleOrNull()
    val cost = state.snap?.totalCost ?: 0.0

    DetailScaffold("余额详情", onBack) {
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 30.dp) {
            SectionLabel("当前余额")
            Row(verticalAlignment = Alignment.Bottom) {
                RollingMoney(
                    target = bal ?: 0.0,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = c.onGlass,
                )
                Spacer(Modifier.width(8.dp))
                Text(state.info?.currency ?: "", color = c.onGlassMuted)
            }
            GlassPill(
                text = if (state.available) "账户状态正常" else "余额不可用",
                tint = if (state.available) c.offPeak else c.peak,
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("余额构成")
            KvRow("充值余额", "¥" + (state.info?.toppedUpBalance ?: "--"))
            KvRow("赠送余额", "¥" + (state.info?.grantedBalance ?: "--"))
            HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
            Text(
                "官方扣费规则：充值余额与赠送余额同时存在时，优先扣减赠送余额。",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
        }

        if (bal != null && cost > 0.0) {
            val elapsed = LocalDate.now().dayOfMonth.coerceAtLeast(1)
            val daily = cost / elapsed
            val left = if (daily > 0) (bal / daily).toInt() else 0
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("可用天数估算")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$left",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (left <= 7) c.peak else c.offPeak,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("天", color = c.onGlassMuted, modifier = Modifier.padding(bottom = 6.dp))
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
                Text("估算方式", style = MaterialTheme.typography.bodySmall, color = c.onGlassMuted)
                Text(
                    "本月消费 ${fmtMoney(cost)} ÷ 已过 $elapsed 天 = 日均 ${fmtMoney(daily)}\n" +
                        "当前余额 ${fmtMoney(bal)} ÷ 日均 ${fmtMoney(daily)} ≈ $left 天",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onGlass,
                )
                Text(
                    "这是线性外推，实际消耗会随你的调用量波动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onGlassMuted,
                )
            }
        }

        state.snap?.let { snap ->
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("本月消耗")
                StatTriple(
                    "消费金额" to fmtMoney(snap.totalCost),
                    "请求次数" to String.format(Locale.CHINA, "%,d", snap.totalRequests),
                    "Tokens" to fmtTokens(snap.totalTokens),
                )
            }
        }

        GlassPrimaryButton("去充值", Modifier.fillMaxWidth(), onClick = onTopUp)
    }
}

/* ---------------- 时段详情 ---------------- */

@Composable
private fun WeekPeakGrid() {
    val c = LocalGlass.current
    val DOW = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    GlassCard(Modifier.fillMaxWidth()) {
        SectionLabel("一周时段表")
        Text(
            "红色 = 高峰（单价 ×2）　绿色 = 空闲",
            style = MaterialTheme.typography.bodySmall,
            color = c.onGlassMuted,
        )
        Canvas(Modifier.fillMaxWidth().height(104.dp)) {
            val rows = 7
            val cols = 24
            val gap = 1.6.dp.toPx()
            val cw = (size.width - gap * (cols - 1)) / cols
            val ch = (size.height - gap * (rows - 1)) / rows
            val radius = CornerRadius(cw * 0.3f, cw * 0.3f)
            for (row in 0 until rows) {
                val weekend = row >= 5
                for (col in 0 until cols) {
                    val peakHour = !weekend && ((col in 9..11) || (col in 14..17))
                    drawRoundRect(
                        color = if (peakHour) c.peak.copy(alpha = 0.78f)
                        else c.offPeak.copy(alpha = 0.26f),
                        topLeft = Offset(col * (cw + gap), row * (ch + gap)),
                        size = Size(cw, ch),
                        cornerRadius = radius,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = c.onGlassMuted)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                DOW.take(5).forEach {
                    Text("$it ", style = MaterialTheme.typography.labelSmall, color = c.onGlassMuted)
                }
            }
        }
        Text(
            "高峰只在工作日出现，共 7 小时/天（35 小时/周）；" +
                "周末与工作日其余 17 小时全部按空闲价计费。",
            style = MaterialTheme.typography.bodySmall,
            color = c.onGlassMuted,
        )
    }
}

@Composable
fun PeakDetail(state: DashState, onBack: () -> Unit) {
    val c = LocalGlass.current
    val now = rememberNowTicker()
    val peak = PeakPricing.isPeak(now)
    val next = PeakPricing.nextSwitch(now)
    val secs = Duration.between(now, next).seconds
    val summary = remember(state.snap) { state.snap?.let { PeakPricing.analyze(it.models) } }

    DetailScaffold("错峰详情", onBack) {
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 30.dp) {
            SectionLabel("当前状态 · 北京时间")
            Text(
                if (peak) "高峰时段" else "空闲时段",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = if (peak) c.peak else c.offPeak,
            )
            Text(
                (if (peak) "再过 " else "距离进入高峰还有 ") + PeakPricing.humanize(secs) +
                    (if (peak) " 转入空闲" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onGlass,
            )
        }

        WeekPeakGrid()

        summary?.let { s ->
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("本月错峰分析")
                StatTriple(
                    "实际消费" to fmtMoney(s.actualTotal),
                    "全部闲时" to fmtMoney(s.offPeakTotal),
                    "错峰可省" to fmtMoney(s.savingTotal),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "约 ${fmtPct(s.peakRatio)} 的用量落在高峰",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onGlassMuted,
                    )
                    GlassProgress(s.peakRatio.toFloat(), c.peak, Modifier.fillMaxWidth())
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
                Text(
                    "推算依据：官方每一项高峰单价都恰好是空闲价的 2 倍，所以" +
                        "「实际消费 ÷ 闲时理论成本 − 1」就等于高峰时段占比，不需要小时级数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onGlassMuted,
                )
            }
        }

        RateTableGlass()
    }
}

/* ---------------- 模型详情 ---------------- */

@Composable
fun ModelDetail(name: String, state: DashState, onBack: () -> Unit) {
    val c = LocalGlass.current
    val model: ModelStat? = state.snap?.models?.firstOrNull { it.model == name }

    DetailScaffold(name, onBack) {
        if (model == null) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("没有该模型的数据", color = c.onGlassMuted)
            }
            return@DetailScaffold
        }

        val tier = PeakPricing.tierOf(model.model)
        val rate = PeakPricing.offPeakRate(tier)
        val peakRate = PeakPricing.peakRate(tier)
        val off = (model.cacheHit / 1e6) * rate.hit +
            (model.cacheMiss / 1e6) * rate.miss +
            (model.response / 1e6) * rate.resp
        val peakRatio = if (off > 0.0001) (model.cost / off - 1.0).coerceIn(0.0, 1.0) else 0.0
        val saving = (model.cost - off).coerceAtLeast(0.0)

        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 30.dp) {
            SectionLabel("消费与用量")
            Row(verticalAlignment = Alignment.Bottom) {
                RollingMoney(
                    target = model.cost,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = c.onGlass,
                )
            }
            StatTriple(
                "Tokens" to fmtTokens(model.tokens),
                "请求次数" to String.format(Locale.CHINA, "%,d", model.requests),
                "缓存命中" to fmtPct(model.cacheRatio),
            )
            GlassPill("费率档：${tier.label}", c.accent)
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("Token 构成")
            val total = model.tokens.coerceAtLeast(1L).toDouble()
            listOf(
                Triple("缓存命中输入", model.cacheHit, c.offPeak),
                Triple("缓存未命中输入", model.cacheMiss, c.accent),
                Triple("模型输出", model.response, c.peak),
            ).forEach { (label, value, tint) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = c.onGlassMuted)
                        Text(
                            "${fmtTokens(value)} · ${fmtPct(value / total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onGlass,
                        )
                    }
                    GlassProgress((value / total).toFloat(), tint, Modifier.fillMaxWidth())
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
            Text(
                "缓存命中的输入价格只有未命中的 1/50，所以命中率是控制成本最有效的杠杆。",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("单次请求成本")
            val req = model.requests.coerceAtLeast(1L)
            KvRow("平均每次 tokens", fmtTokens(model.tokens / req))
            KvRow("平均每次花费", "¥" + String.format(Locale.CHINA, "%.4f", model.cost / req))
            if (model.requests == 0L) {
                Text("本月无请求记录", style = MaterialTheme.typography.bodySmall, color = c.onGlassMuted)
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("错峰空间")
            KvRow("该模型高峰占比", fmtPct(peakRatio), if (peakRatio > 0.2) c.peak else c.onGlass)
            KvRow("全部闲时的成本", fmtMoney(off))
            KvRow("错峰可省", fmtMoney(saving), if (saving > 0.5) c.peak else c.onGlass)
            GlassProgress(peakRatio.toFloat(), c.peak, Modifier.fillMaxWidth())
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("适用费率（元 / 百万 tokens）")
            KvRow("空闲 · 命中 / 未命中 / 输出",
                "${rate.hit} / ${rate.miss} / ${rate.resp}")
            KvRow("高峰 · 命中 / 未命中 / 输出",
                "${peakRate.hit} / ${peakRate.miss} / ${peakRate.resp}", c.peak)
        }
    }
}

/** 把详情页编码成字符串，便于用 rememberSaveable 跨旋屏保存。 */
fun detailFromKey(key: String?): Detail? = when {
    key == null -> null
    key == "balance" -> Detail.Balance
    key == "peak" -> Detail.Peak
    key == "history" -> Detail.History
    key.startsWith("model:") -> Detail.Model(key.removePrefix("model:"))
    else -> null
}

/* ---------------- 历史记录 ---------------- */

@Composable
private fun MonthRow(rec: MonthRecord, prev: MonthRecord?) {
    val c = LocalGlass.current
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                rec.month,
                style = MaterialTheme.typography.bodyMedium,
                color = c.onGlass,
                fontWeight = FontWeight.Medium,
            )
            Text(fmtMoney(rec.cost), style = MaterialTheme.typography.bodyMedium, color = c.onGlass)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${fmtTokens(rec.tokens)} · ${String.format(Locale.CHINA, "%,d", rec.requests)} 次 · 命中 ${fmtPct(rec.cacheRatio)}",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
            if (prev != null && prev.cost > 0.0) {
                val delta = rec.cost - prev.cost
                val up = delta >= 0
                Text(
                    (if (up) "▲ " else "▼ ") + fmtPct(abs(delta / prev.cost)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (up) c.peak else c.offPeak,
                )
            } else {
                Text("—", style = MaterialTheme.typography.bodySmall, color = c.onGlassMuted)
            }
        }
    }
}

@Composable
fun HistoryDetail(state: DashState, onBack: () -> Unit) {
    val c = LocalGlass.current
    val hist = state.history

    DetailScaffold("历史记录", onBack) {
        if (hist.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("还没有归档记录", color = c.onGlass, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "每次成功拉取某个自然月的用量后，该月会自动归档。" +
                        "在「用量」页切换到想补录的月份即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onGlassMuted,
                )
            }
            return@DetailScaffold
        }

        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 30.dp) {
            SectionLabel("累计")
            StatTriple(
                "归档月数" to "${hist.size}",
                "累计消费" to fmtMoney(hist.sumOf { it.cost }),
                "累计 Tokens" to fmtTokens(hist.sumOf { it.tokens }),
            )
        }

        if (hist.size >= 2) {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("月度消费趋势")
                val last = hist.takeLast(12)
                GlassBarChart(
                    values = last.map { it.cost.toFloat() },
                    startLabel = last.first().month,
                    midLabel = last[last.size / 2].month,
                    endLabel = last.last().month,
                    barColor = c.accent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("逐月明细")
            Text(
                "由新到旧；右侧箭头是与上一个月的环比。",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
            for (i in hist.indices.reversed()) {
                MonthRow(hist[i], if (i > 0) hist[i - 1] else null)
            }
        }
    }
}
