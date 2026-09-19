package com.rikka.dsusage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rikka.dsusage.data.CachedDash
import com.rikka.dsusage.data.DashCache
import com.rikka.dsusage.data.DeepSeekApi
import com.rikka.dsusage.data.Exporter
import com.rikka.dsusage.data.KEY_API
import com.rikka.dsusage.data.KEY_TOKEN
import com.rikka.dsusage.data.MonitorScheduler
import com.rikka.dsusage.data.MonitorSettings
import com.rikka.dsusage.data.Notifier
import com.rikka.dsusage.data.PlatformApi
import com.rikka.dsusage.data.SecureStore
import com.rikka.dsusage.data.TokenExpiredException
import com.rikka.dsusage.ui.BalanceDetail
import com.rikka.dsusage.ui.DashState
import com.rikka.dsusage.ui.DashViewModel
import com.rikka.dsusage.ui.Detail
import com.rikka.dsusage.ui.detailFromKey
import com.rikka.dsusage.ui.GlassBackground
import com.rikka.dsusage.ui.GlassSurface
import com.rikka.dsusage.ui.Glyph
import com.rikka.dsusage.ui.HistoryDetail
import com.rikka.dsusage.ui.LocalGlass
import com.rikka.dsusage.ui.LoginWebView
import com.rikka.dsusage.ui.ModelDetail
import com.rikka.dsusage.ui.NavGlyph
import com.rikka.dsusage.ui.OverviewTab
import com.rikka.dsusage.ui.PeakDetail
import com.rikka.dsusage.ui.PeakTab
import com.rikka.dsusage.ui.SettingsTab
import com.rikka.dsusage.ui.UsageTab
import kotlinx.coroutines.launch
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = SecureStore(applicationContext)
        Notifier.ensureChannel(applicationContext)
        setContent { App(store) }
    }
}

private val NAV_LABELS = listOf("概览", "用量", "错峰", "设置")
private val NAV_GLYPHS = listOf(Glyph.WALLET, Glyph.BARS, Glyph.CLOCK, Glyph.SLIDERS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: SecureStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val monitor = remember { MonitorSettings(ctx) }
    val cache = remember { DashCache(ctx) }

    val vm: DashViewModel = viewModel()
    val dash = vm.state
    val apiKey = dash.apiKey
    val token = dash.token

    var showLogin by remember { mutableStateOf(apiKey.isBlank()) }
    var exporting by remember { mutableStateOf(false) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }

    val pagerState = rememberPagerState(pageCount = { NAV_LABELS.size })

    // 回到前台自动刷新（跳过冷启动的第一次 ON_RESUME，避免与初始请求重复）
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumedOnce) vm.refresh() else resumedOnce = true
    }

    LaunchedEffect(Unit) { MonitorScheduler.sync(ctx, monitor.enabled) }

    // 全面屏手势：系统返回键 / 边缘侧滑返回
    //   详情页 → 回主页面；非首页签 → 回首页签
    BackHandler(enabled = detailKey != null || pagerState.currentPage != 0) {
        if (detailKey != null) {
            detailKey = null
        } else {
            scope.launch { pagerState.animateScrollToPage(0) }
        }
    }

    fun openTopUp() {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/top_up"))
        )
    }

    fun doExport() {
        if (exporting) return
        if (token.isBlank()) {
            Toast.makeText(ctx, "请先在设置里获取用量凭证", Toast.LENGTH_SHORT).show()
            return
        }
        exporting = true
        scope.launch {
            val ym = YearMonth.now()
            PlatformApi.fetchMonth(token, ym.year, ym.monthValue)
                .onSuccess { snap ->
                    val ok = Exporter.exportCsv(ctx, snap, ym) != null
                    Toast.makeText(ctx, if (ok) "已导出到下载目录" else "导出失败", Toast.LENGTH_LONG).show()
                }
                .onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
            exporting = false
        }
    }

    GlassSurface {
        val c = LocalGlass.current
        val scheme = if (c.isDark) {
            darkColorScheme(
                primary = c.accent,
                background = Color.Transparent,
                onBackground = c.onGlass,
                surface = Color.Transparent,
                onSurface = c.onGlass,
                surfaceVariant = Color.White.copy(alpha = 0.10f),
                onSurfaceVariant = c.onGlassMuted,
                outline = Color.White.copy(alpha = 0.35f),
                error = c.peak,
            )
        } else {
            lightColorScheme(
                primary = c.accent,
                background = Color.Transparent,
                onBackground = c.onGlass,
                surface = Color.Transparent,
                onSurface = c.onGlass,
                surfaceVariant = Color.White.copy(alpha = 0.55f),
                onSurfaceVariant = c.onGlassMuted,
                outline = Color.White.copy(alpha = 0.70f),
                error = c.peak,
            )
        }

        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize()) {
                GlassBackground(Modifier.fillMaxSize())

                if (showLogin) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "登录 DeepSeek",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.onGlass,
                            )
                            TextButton(onClick = {
                                if (token.isNotBlank() || apiKey.isNotBlank()) showLogin = false
                            }) { Text("取消", color = c.accent) }
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            LoginWebView { captured ->
                                vm.saveToken(captured)
                                showLogin = false
                                scope.launch { pagerState.animateScrollToPage(1) }
                            }
                        }
                    }
                } else {
                    Scaffold(
                        containerColor = Color.Transparent,
                        bottomBar = {
                            GlassNavBar(pagerState.currentPage) { i ->
                                scope.launch { pagerState.animateScrollToPage(i) }
                            }
                        },
                    ) { pad ->
                        Column(Modifier.fillMaxSize().padding(pad)) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "DeepSeek 用量",
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = c.onGlass,
                                )
                                TextButton(
                                    onClick = { vm.refresh() },
                                    enabled = !(dash.balLoading || dash.useLoading),
                                ) {
                                    Text(
                                        if (dash.balLoading || dash.useLoading) "刷新中" else "刷新",
                                        color = c.accent,
                                    )
                                }
                            }

                            AnimatedContent(
                                targetState = detailKey,
                                transitionSpec = {
                                    if (targetState != null) {
                                        (slideInHorizontally(tween(300)) { it } + fadeIn(tween(220)))
                                            .togetherWith(fadeOut(tween(140)))
                                    } else {
                                        fadeIn(tween(200))
                                            .togetherWith(
                                                slideOutHorizontally(tween(300)) { it } +
                                                    fadeOut(tween(160))
                                            )
                                    }
                                },
                                label = "detailNav",
                            ) { key ->
                                val d = detailFromKey(key)
                                if (d == null) {
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxSize(),
                                    ) { page ->
                                        when (page) {
                                            0 -> OverviewTab(
                                                state = dash,
                                                onRefresh = { vm.refresh() },
                                                onNeedLogin = { showLogin = true },
                                                onTopUp = { openTopUp() },
                                                onOpenBalance = { detailKey = "balance" },
                                                onOpenPeak = { detailKey = "peak" },
                                            )
                                            1 -> UsageTab(
                                                state = dash,
                                                onMonthChange = { ym -> vm.onMonthChange(ym) },
                                                onOpenModel = { name -> detailKey = "model:$name" },
                                                onOpenHistory = { detailKey = "history" },
                                            )
                                            2 -> PeakTab(
                                                state = dash,
                                                onNeedLogin = { showLogin = true },
                                                onOpenPeak = { detailKey = "peak" },
                                            )
                                            else -> SettingsTab(
                                                apiKey = apiKey,
                                                token = token,
                                                monitor = monitor,
                                                onOpenLogin = { showLogin = true },
                                                onExport = { doExport() },
                                                onSaveApiKey = { k -> vm.saveApiKey(k) },
                                                onSaveToken = { t -> vm.saveToken(t) },
                                            )
                                        }
                                    }
                                } else {
                                    when (d) {
                                        Detail.Balance -> BalanceDetail(
                                            state = dash,
                                            onBack = { detailKey = null },
                                            onTopUp = { openTopUp() },
                                        )
                                        Detail.Peak -> PeakDetail(dash) { detailKey = null }
                                        Detail.History -> HistoryDetail(dash) { detailKey = null }
                                        is Detail.Model -> ModelDetail(d.name, dash) { detailKey = null }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassNavBar(current: Int, onSelect: (Int) -> Unit) {
    val c = LocalGlass.current
    val shape = RoundedCornerShape(30.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .shadow(
                elevation = 20.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (c.isDark) 0.40f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (c.isDark) 0.40f else 0.10f),
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.cardTop, c.cardBottom)))
            .border(1.dp, Brush.linearGradient(c.border), shape)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NAV_LABELS.forEachIndexed { i, label ->
            val selected = current == i
            val scale by animateFloatAsState(if (selected) 1f else 0.9f, tween(220), label = "navScale")
            val tint by animateColorAsState(
                if (selected) c.accent else c.onGlassMuted,
                tween(220),
                label = "navTint",
            )
            Column(
                Modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) c.accent.copy(alpha = 0.24f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                NavGlyph(NAV_GLYPHS[i], tint, Modifier.size(21.dp))
                Text(label, color = tint, fontSize = 11.sp)
            }
        }
    }
}
