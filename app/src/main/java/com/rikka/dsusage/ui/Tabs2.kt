package com.rikka.dsusage.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikka.dsusage.data.MonitorScheduler
import com.rikka.dsusage.data.MonitorSettings
import com.rikka.dsusage.data.PeakPricing
import com.rikka.dsusage.data.RateTier
import java.time.Duration
import java.util.Locale

/* ---------------- 时段状态条（概览页复用） ---------------- */

@Composable
fun PeakStatusStrip(onClick: () -> Unit = {}) {
    val c = LocalGlass.current
    val now = rememberNowTicker()
    val peak = PeakPricing.isPeak(now)
    val next = PeakPricing.nextSwitch(now)
    val secs = Duration.between(now, next).seconds
    val accent by animateColorAsState(
        targetValue = if (peak) c.peak else c.offPeak,
        label = "peakAccent",
    )

    GlassCard(Modifier.fillMaxWidth(), contentPadding = 16.dp, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前时段 · 北京时间", style = MaterialTheme.typography.labelSmall, color = c.onGlassMuted)
                GlassPill(if (peak) "高峰时段" else "空闲时段", accent)
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    if (peak) "转入空闲还有" else "转入高峰还有",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.onGlassMuted,
                )
                Text(
                    PeakPricing.humanize(secs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
        }
    }
}

/* ---------------- 错峰页 ---------------- */

@Composable
fun PeakTab(state: DashState, onNeedLogin: () -> Unit, onOpenPeak: () -> Unit) {
    val c = LocalGlass.current
    val now = rememberNowTicker()
    val peak = PeakPricing.isPeak(now)
    val next = PeakPricing.nextSwitch(now)
    val secs = Duration.between(now, next).seconds
    val accent by animateColorAsState(
        targetValue = if (peak) c.peak else c.offPeak,
        label = "peakAccent2",
    )
    val summary = remember(state.snap) {
        state.snap?.let { PeakPricing.analyze(it.models) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 30.dp, onClick = onOpenPeak) {
            Text("当前时段 · 北京时间", style = MaterialTheme.typography.labelLarge, color = c.onGlassMuted)
            Text(
                if (peak) "高峰时段" else "空闲时段",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                (if (peak) "再过 " else "距离进入高峰还有 ") + PeakPricing.humanize(secs) +
                    (if (peak) " 转入空闲，单价减半" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onGlass,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
            Text(
                "高峰：周一至周五 9:00–12:00、14:00–18:00　（共 7 小时/工作日）" +
                    "其余时段及整个周末均为空闲，单价是高峰的一半。",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("错峰分析", style = MaterialTheme.typography.labelLarge, color = c.onGlassMuted)
            if (summary == null) {
                Text("需要用量数据才能分析", style = MaterialTheme.typography.bodySmall, color = c.onGlassMuted)
                GlassPrimaryButton(
                    text = "去获取用量凭证",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNeedLogin,
                )
            } else {
                StatTriple(
                    "实际消费" to fmtMoney(summary.actualTotal),
                    "全部闲时" to fmtMoney(summary.offPeakTotal),
                    "错峰可省" to fmtMoney(summary.savingTotal),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "本月约 ${fmtPct(summary.peakRatio)} 的用量落在高峰时段",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onGlassMuted,
                    )
                    GlassProgress(summary.peakRatio.toFloat(), c.peak, Modifier.fillMaxWidth())
                }
                summary.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            r.model,
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onGlass,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "高峰 ${fmtPct(r.peakRatio)} · 可省 ${fmtMoney(r.saving)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onGlassMuted,
                        )
                    }
                }
            }
        }

        summary?.legacyModels?.takeIf { it.isNotEmpty() }?.let { legacy ->
            Card(
                colors = CardDefaults.cardColors(containerColor = c.warn.copy(alpha = 0.10f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚠ 检测到已下线的旧模型名", style = MaterialTheme.typography.labelLarge, color = c.warn)
                    Text(legacy.joinToString("、"), style = MaterialTheme.typography.bodySmall, color = c.onGlass)
                    Text(
                        "官方已声明这些名字下线，且实测其单价高于 deepseek-flash" +
                            "（缓存命中 ¥0.05 vs ¥0.02，未命中 ¥1.5 vs ¥1.0）。" +
                            "deepseek-flash 本身支持图像理解，建议把调用端显式改为 deepseek-flash。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onGlassMuted,
                    )
                }
            }
        }

        RateTableGlass()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun RateTableGlass() {
    val c = LocalGlass.current
    var expanded by remember { mutableStateOf(false) }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("费率表", style = MaterialTheme.typography.labelLarge, color = c.onGlassMuted)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开", color = c.accent)
            }
        }
        Text(
            "单位：元 / 百万 tokens　列：缓存命中 / 未命中 / 输出",
            style = MaterialTheme.typography.bodySmall,
            color = c.onGlassMuted,
        )
        if (expanded) {
            listOf(RateTier.FLASH, RateTier.PRO, RateTier.LEGACY).forEach { tier ->
                val off = PeakPricing.offPeakRate(tier)
                val pk = PeakPricing.peakRate(tier)
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(tier.label, style = MaterialTheme.typography.bodyMedium, color = c.onGlass, fontWeight = FontWeight.Medium)
                    listOf("空闲" to off, "高峰" to pk).forEach { (label, r) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (label == "高峰") c.peak else c.offPeak,
                            )
                            Text(
                                "${price(r.hit)} / ${price(r.miss)} / ${price(r.resp)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.onGlass,
                            )
                        }
                    }
                }
            }
            Text(
                "FLASH 与 PRO 两档取自官方定价页；旧模型名一档是用「周末必为纯空闲时段」" +
                    "这一约束从实际扣费数据反推得出。",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
        }
    }
}

private fun price(v: Double): String =
    if (v < 0.1) String.format(Locale.CHINA, "%.2f", v) else String.format(Locale.CHINA, "%.1f", v)

/* ---------------- 设置页 ---------------- */

@Composable
fun SettingsTab(
    apiKey: String,
    token: String,
    monitor: MonitorSettings,
    onOpenLogin: () -> Unit,
    onExport: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSaveToken: (String) -> Unit,
) {
    val c = LocalGlass.current
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(apiKey) }
    var tok by remember { mutableStateOf(token) }
    var monitorOn by remember { mutableStateOf(monitor.enabled) }
    var threshold by remember { mutableStateOf(monitor.threshold.toString()) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(ctx, "没有通知权限，余额预警无法提醒", Toast.LENGTH_LONG).show()
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.onGlass,
        unfocusedTextColor = c.onGlass,
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.onGlassMuted.copy(alpha = 0.5f),
        focusedLabelColor = c.accent,
        unfocusedLabelColor = c.onGlassMuted,
        cursorColor = c.accent,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text("余额查询凭证", style = MaterialTheme.typography.titleSmall, color = c.onGlass)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("DeepSeek API Key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "在 platform.deepseek.com → API Keys 创建。走官方接口，稳定可靠。",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
            GlassPrimaryButton("保存 API Key", Modifier.fillMaxWidth()) { onSaveApiKey(key.trim()) }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("用量统计凭证", style = MaterialTheme.typography.titleSmall, color = c.onGlass)
            Text(
                if (token.isBlank()) "状态：尚未获取" else "状态：已获取（${token.length} 字符）",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
            GlassPrimaryButton("打开内置登录页自动获取", Modifier.fillMaxWidth(), onClick = onOpenLogin)
            OutlinedTextField(
                value = tok,
                onValueChange = { tok = it.trim() },
                label = { Text("或手动粘贴 userToken") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassPrimaryButton("保存 Token", Modifier.fillMaxWidth()) { onSaveToken(tok.trim()) }
            GlassGhostButton("导出本月用量 CSV", Modifier.fillMaxWidth(), onClick = onExport)
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("余额预警", style = MaterialTheme.typography.titleSmall, color = c.onGlass)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("后台定时监控", style = MaterialTheme.typography.bodyMedium, color = c.onGlass)
                    Text(
                        "每 30 分钟联网查一次余额，低于阈值时推送通知",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onGlassMuted,
                    )
                }
                Switch(
                    checked = monitorOn,
                    onCheckedChange = { on ->
                        monitorOn = on
                        monitor.enabled = on
                        MonitorScheduler.sync(ctx, on)
                        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }
            OutlinedTextField(
                value = threshold,
                onValueChange = { threshold = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("预警阈值（元）") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassPrimaryButton("保存阈值", Modifier.fillMaxWidth()) {
                val v = threshold.toDoubleOrNull()
                if (v == null || v < 0) {
                    Toast.makeText(ctx, "请输入有效数字", Toast.LENGTH_SHORT).show()
                } else {
                    monitor.threshold = v
                    Toast.makeText(ctx, "阈值已保存：¥$v", Toast.LENGTH_SHORT).show()
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("关于", style = MaterialTheme.typography.titleSmall, color = c.onGlass)
            Text(
                "余额走 DeepSeek 官方接口，稳定可靠。用量统计没有官方 API，只能借用网页登录凭证；" +
                    "该接口为非官方接口，可能变更，Token 短期有效，失效后 App 会提示重新登录。" +
                    "所有凭证仅保存在本机 Keystore 加密存储中，不会上传到任何地方。",
                style = MaterialTheme.typography.bodySmall,
                color = c.onGlassMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
