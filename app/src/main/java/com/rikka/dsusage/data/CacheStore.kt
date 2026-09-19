package com.rikka.dsusage.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 最后一次成功拉取的快照，用于冷启动秒开与离线查看。 */
@Serializable
data class CachedDash(
    val savedAtMs: Long = 0L,
    val month: String = "",
    val balance: BalanceInfo? = null,
    val available: Boolean = false,
    val usage: UsageSnapshot? = null,
)

class DashCache(context: Context) {

    private val prefs = context.getSharedPreferences("ds_usage_cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(): CachedDash? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString<CachedDash>(raw) }.getOrNull()
    }

    fun save(value: CachedDash) {
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(CachedDash.serializer(), value)).apply()
        }
    }

    /** 后台任务只拉到余额时使用：保留已有的用量数据，不整体覆盖。 */
    fun saveBalance(info: BalanceInfo?, available: Boolean, atMs: Long) {
        val cur = load() ?: CachedDash()
        save(cur.copy(balance = info, available = available, savedAtMs = atMs))
    }
    private companion object {
        const val KEY = "last_snapshot"
    }
}
