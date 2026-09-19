package com.rikka.dsusage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rikka.dsusage.MainActivity
import com.rikka.dsusage.R
import com.rikka.dsusage.data.BalanceWorker
import com.rikka.dsusage.data.DashCache
import com.rikka.dsusage.data.DayStat
import com.rikka.dsusage.data.HistoryStore
import com.rikka.dsusage.data.PeakPricing
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * 桌面小组件：余额 / 时段倒计时 / 本月消费与环比 / 请求 / 命中率 / 趋势 / 可用天数。
 *
 * RemoteViews 不支持 Canvas，趋势图是**先渲染成 Bitmap** 再 setImageViewBitmap 推入。
 * 数据全部来自本地缓存，渲染不联网。
 */
class BalanceWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<BalanceWorker>()
                    .setInputData(BalanceWorker.forceInputData())
                    .build()
            )
        }
    }

    companion object {

        const val ACTION_REFRESH = "com.rikka.dsusage.WIDGET_REFRESH"
        private const val CHART_DAYS = 14
        private const val CHART_HEIGHT_DP = 20

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, BalanceWidget::class.java))
            ids.forEach { render(context, manager, it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_balance)
            val cached = DashCache(context).load()
            val info = cached?.balance
            val snap = cached?.usage
            val total = info?.totalBalance?.toDoubleOrNull()
            val now = ZonedDateTime.now()

            /* ---- 余额 + 状态 ---- */
            if (total != null) {
                val symbol = if (info.currency == "USD") "$" else "¥"
                views.setTextViewText(
                    R.id.widget_balance,
                    symbol + String.format(Locale.CHINA, "%.2f", total),
                )
                val ok = cached.available
                views.setTextViewText(
                    R.id.widget_status,
                    context.getString(
                        if (ok) R.string.widget_status_ok else R.string.widget_status_bad
                    ),
                )
                views.setTextColor(
                    R.id.widget_status,
                    context.getColor(if (ok) R.color.widget_ok else R.color.widget_bad),
                )
            } else {
                views.setTextViewText(R.id.widget_balance, "—")
                views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_no_key))
                views.setTextColor(R.id.widget_status, context.getColor(R.color.widget_label))
            }

            /* ---- 时段胶囊（含倒计时） ---- */
            val peak = PeakPricing.isPeak(now)
            val seconds = Duration.between(now, PeakPricing.nextSwitch(now)).seconds
            views.setTextViewText(
                R.id.widget_peak,
                "● " + (if (peak) "高峰" else "空闲") + " " + countdown(seconds),
            )
            views.setInt(
                R.id.widget_peak,
                "setBackgroundResource",
                if (peak) R.drawable.widget_pill_bad else R.drawable.widget_pill_ok,
            )
            views.setTextColor(
                R.id.widget_peak,
                context.getColor(if (peak) R.color.widget_bad else R.color.widget_ok),
            )

            /* ---- 本月统计 ---- */
            val isCurrentMonth = cached?.month == YearMonth.now().toString()
            if (snap != null && snap.totalTokens > 0L) {
                views.setTextViewText(R.id.widget_cost, money(snap.totalCost))
                views.setTextViewText(
                    R.id.widget_requests,
                    String.format(Locale.CHINA, "%,d", snap.totalRequests),
                )
                views.setTextViewText(R.id.widget_cache, pct(snap.cacheRatio))

                // 环比：读取上月归档；没有就隐藏，不显示假数字
                val prevCost = previousMonthCost(context, cached?.month)
                if (prevCost != null && prevCost > 0.0) {
                    val delta = snap.totalCost - prevCost
                    views.setTextViewText(
                        R.id.widget_delta,
                        (if (delta >= 0) "▲" else "▼") +
                            String.format(Locale.CHINA, "%.0f%%", abs(delta / prevCost) * 100),
                    )
                    views.setTextColor(
                        R.id.widget_delta,
                        context.getColor(if (delta >= 0) R.color.widget_bad else R.color.widget_ok),
                    )
                    views.setViewVisibility(R.id.widget_delta, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.widget_delta, View.GONE)
                }

                // 趋势图：画每日消费
                val widthDp = manager.getAppWidgetOptions(widgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                    .coerceIn(180, 640)
                val density = context.resources.displayMetrics.density
                val chartW = ((widthDp - 28) * density).toInt().coerceIn(200, 2200)
                val chartH = (CHART_HEIGHT_DP * density).toInt().coerceAtLeast(20)
                views.setImageViewBitmap(
                    R.id.widget_chart,
                    trendBitmap(context, snap.days, chartW, chartH),
                )
                views.setViewVisibility(R.id.widget_chart, View.VISIBLE)
            } else {
                views.setTextViewText(R.id.widget_cost, "—")
                views.setTextViewText(R.id.widget_requests, "—")
                views.setTextViewText(R.id.widget_cache, "—")
                views.setViewVisibility(R.id.widget_delta, View.GONE)
                views.setViewVisibility(R.id.widget_chart, View.GONE)
            }

            /* ---- 页脚 ---- */
            val left = if (snap != null && total != null && isCurrentMonth) {
                val day = now.dayOfMonth.coerceAtLeast(1)
                val daily = snap.totalCost / day
                if (daily > 0.0) {
                    "可用约 " + (total / daily).toInt() + " 天 · 日均 " + money(daily)
                } else {
                    "可用约 -- 天"
                }
            } else {
                ""
            }
            val lastDay = snap?.days?.lastOrNull { it.cost > 0.0 }
            val right = buildString {
                if (lastDay != null) {
                    append(lastDay.date.takeLast(5)).append(' ').append(money(lastDay.cost)).append(" · ")
                }
                append("更新 ").append(fmtTime(cached?.savedAtMs ?: 0L))
            }
            views.setTextViewText(R.id.widget_left, left)
            views.setTextViewText(R.id.widget_time, right)

            /* ---- 点击：主体打开 App，时间区立即刷新 ---- */
            val open = openApp(context)
            listOf(
                R.id.widget_balance,
                R.id.widget_status,
                R.id.widget_cost,
                R.id.widget_requests,
                R.id.widget_cache,
                R.id.widget_chart,
                R.id.widget_left,
            ).forEach { views.setOnClickPendingIntent(it, open) }
            views.setOnClickPendingIntent(R.id.widget_time, refresh(context))

            manager.updateAppWidget(widgetId, views)
        }

        private fun previousMonthCost(context: Context, month: String?): Double? {
            if (month == null) return null
            val prevKey = runCatching { YearMonth.parse(month).minusMonths(1).toString() }
                .getOrNull() ?: return null
            return HistoryStore(context).load().firstOrNull { it.month == prevKey }?.cost
        }

        /**
         * 最近 14 天的**每日消费**柱状图。
         * 缺失日期补 0，保证柱子均匀分布在时间轴上。
         */
        private fun trendBitmap(context: Context, days: List<DayStat>, wPx: Int, hPx: Int): Bitmap {
            val safeW = wPx.coerceAtLeast(1)
            val safeH = hPx.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
            if (days.isEmpty()) return bitmap

            val recent = days.takeLast(CHART_DAYS)
            val firstDate = runCatching { LocalDate.parse(recent.first().date) }.getOrNull()
            val lastDate = runCatching { LocalDate.parse(recent.last().date) }.getOrNull()

            val series: List<Double> = firstDate?.let { from ->
                lastDate?.let { to ->
                    val byDate = recent.associate { it.date to it.cost }
                    val span = ChronoUnit.DAYS.between(from, to).toInt()
                    val dayCount = (span + 1).coerceIn(1, 62)
                    (0 until dayCount).map { offset ->
                        byDate[from.plusDays(offset.toLong()).toString()] ?: 0.0
                    }
                }
            } ?: recent.map { it.cost }

            if (series.isEmpty()) return bitmap
            val maxValue = (series.maxOrNull() ?: 0.0).coerceAtLeast(0.0001).toFloat()
            val count = series.size
            val gap = if (count > 1) safeW.toFloat() / count * 0.26f else 0f
            val barWidth = (safeW - gap * (count - 1)) / count
            if (barWidth <= 0f) return bitmap

            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = context.getColor(R.color.widget_bar)
            val hiPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = context.getColor(R.color.widget_bar_hi)
            }
            val rect = RectF()
            val radius = barWidth / 2.6f

            series.forEachIndexed { index, value ->
                val barHeight = safeH * (value.toFloat() / maxValue)
                if (barHeight > 1f) {
                    val left = index * (barWidth + gap)
                    rect.set(left, safeH - barHeight, left + barWidth, safeH.toFloat())
                    canvas.drawRoundRect(rect, radius, radius, if (index == series.size - 1) hiPaint else paint)
                }
            }
            return bitmap
        }

        /** 紧凑倒计时：51h38m / 38m */
        private fun countdown(seconds: Long): String {
            if (seconds <= 0L) return "0m"
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            return if (h > 0) "${h}h${m}m" else "${m}m"
        }

        private fun money(value: Double): String = String.format(Locale.CHINA, "¥%.2f", value)

        private fun pct(value: Double): String =
            String.format(Locale.CHINA, "%.0f", value * 100) + "%"

        private fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun refresh(context: Context): PendingIntent {
            val intent = Intent(context, BalanceWidget::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(
                context,
                1002,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun fmtTime(ms: Long): String {
            if (ms <= 0L) return "--:--"
            val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            return t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
        }
    }
}
