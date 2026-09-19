package com.rikka.dsusage.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rikka.dsusage.data.CachedDash
import com.rikka.dsusage.data.DashCache
import com.rikka.dsusage.data.DeepSeekApi
import com.rikka.dsusage.data.KEY_API
import com.rikka.dsusage.data.KEY_TOKEN
import com.rikka.dsusage.data.PlatformApi
import com.rikka.dsusage.data.SecureStore
import com.rikka.dsusage.data.HistoryStore
import com.rikka.dsusage.data.MonthRecord
import com.rikka.dsusage.data.PeakPricing
import com.rikka.dsusage.data.TokenExpiredException
import com.rikka.dsusage.widget.BalanceWidget
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * 持有全部业务状态与请求逻辑。
 *
 * 放在 ViewModel 而非 composable 的 remember 里，解决两件事：
 *  1. 旋屏 / 进程重建不再丢状态、不再重复请求两次接口
 *  2. 连续刷新时用 Job 取消上一次，避免并发请求互相覆盖
 */
class DashViewModel(app: Application) : AndroidViewModel(app) {

    private val secret = SecureStore(app)
    private val cache = DashCache(app)
    private val history = HistoryStore(app)

    var state by mutableStateOf(DashState())
        private set

    private var balanceJob: Job? = null
    private var usageJob: Job? = null

    init {
        state = state.copy(
            apiKey = secret.get(KEY_API).orEmpty(),
            token = secret.get(KEY_TOKEN).orEmpty(),
        )

        // 先用本地缓存填充，保证冷启动与离线时也有内容可看
        cache.load()?.let { c ->
            state = state.copy(
                info = c.balance,
                available = c.balance != null && c.available,
                snap = if (c.usage != null && c.month == state.ym.toString()) c.usage else null,
                dataAtMs = c.savedAtMs,
                fromCache = c.balance != null || c.usage != null,
            )
        }

        state = state.copy(history = history.load())

        refresh()
    }

    /** 手动刷新 / 回到前台时调用。 */
    fun refresh() {
        loadBalance()
        loadUsage()
    }

    fun onMonthChange(ym: YearMonth) {
        if (ym == state.ym) return
        state = state.copy(ym = ym, snap = null, useError = null, expired = false)
        loadUsage()
    }

    fun saveApiKey(value: String) {
        val v = value.trim()
        if (v.isEmpty()) secret.remove(KEY_API) else secret.put(KEY_API, v)
        state = state.copy(apiKey = v, balError = null)
        loadBalance()
    }

    fun saveToken(value: String) {
        val v = value.trim()
        if (v.isEmpty()) secret.remove(KEY_TOKEN) else secret.put(KEY_TOKEN, v)
        state = state.copy(token = v)
        loadUsage()
    }

    private fun loadBalance() {
        val key = state.apiKey
        if (key.isBlank()) return
        balanceJob?.cancel()
        balanceJob = viewModelScope.launch {
            state = state.copy(balLoading = true, balError = null)
            DeepSeekApi.fetchBalance(key)
                .onSuccess { r ->
                    state = state.copy(
                        balLoading = false,
                        available = r.isAvailable,
                        info = r.balanceInfos.firstOrNull(),
                    )
                    persist()
                }
                .onFailure {
                    state = state.copy(balLoading = false, balError = it.message ?: "未知错误")
                }
        }
    }

    private fun loadUsage() {
        val token = state.token
        if (token.isBlank()) {
            state = state.copy(snap = null)
            return
        }
        val ym = state.ym
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            state = state.copy(useLoading = true, useError = null, expired = false)
            PlatformApi.fetchMonth(token, ym.year, ym.monthValue)
                .onSuccess {
                    state = state.copy(useLoading = false, snap = it)
                    persist()
                }
                .onFailure { e ->
                    state = state.copy(
                        useLoading = false,
                        useError = e.message ?: "未知错误",
                        expired = e is TokenExpiredException,
                    )
                }
        }
    }

    /**
     * 与缓存内容不同 → 说明是刚拉取的，打新时间戳并写缓存。
     * 相同 → 当前显示的本来就是缓存，绝不能把时间戳刷成"现在"。
     */
    /**
     * 归档 + 落盘。
     *
     * 归档对**任意月份**都做（历史月份重复拉取也能补录）；
     * 而"最近快照缓存"只存当月，避免翻旧月份时污染冷启动数据。
     */
    private fun persist() {
        val snap = state.snap

        if (snap != null) {
            val analysis = PeakPricing.analyze(snap.models)
            val wrote = history.upsert(
                MonthRecord(
                    month = state.ym.toString(),
                    cost = snap.totalCost,
                    tokens = snap.totalTokens,
                    requests = snap.totalRequests,
                    cacheRatio = snap.cacheRatio,
                    peakRatio = analysis.peakRatio,
                    savedAtMs = System.currentTimeMillis(),
                )
            )
            if (wrote) state = state.copy(history = history.load())
        }

        if (state.info == null && snap == null) return
        if (state.ym != YearMonth.now()) return

        val old = cache.load()
        if (old != null && old.balance == state.info && old.usage == snap) return

        val now = System.currentTimeMillis()
        cache.save(
            CachedDash(
                savedAtMs = now,
                month = state.ym.toString(),
                balance = state.info,
                available = state.available,
                usage = snap,
            )
        )
        state = state.copy(dataAtMs = now, fromCache = false)
        BalanceWidget.refreshAll(getApplication())
    }
}
