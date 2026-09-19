package com.rikka.dsusage.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 一个自然月的归档记录。接口只给单月数据，历史靠本地累积。 */
@Serializable
data class MonthRecord(
    val month: String,
    val cost: Double = 0.0,
    val tokens: Long = 0L,
    val requests: Long = 0L,
    val cacheRatio: Double = 0.0,
    val peakRatio: Double = 0.0,
    val savedAtMs: Long = 0L,
)

/**
 * 按月归档用量，用于环比与长期趋势。
 *
 * 覆盖式写入（同月记录被替换），因为当月数据每天都在长；
 * 历史月份重复拉取也不会出问题，值相同就跳过不写。
 */
class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("ds_usage_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val serializer = ListSerializer(MonthRecord.serializer())

    /** 按月份升序。 */
    fun load(): List<MonthRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrDefault(emptyList())
            .sortedBy { it.month }
    }

    /** @return 是否真的写入了（值没变则不写，避免无意义的重组与磁盘写） */
    fun upsert(record: MonthRecord): Boolean {
        if (record.cost <= 0.0 && record.tokens <= 0L) return false
        val all = load()
        val existing = all.firstOrNull { it.month == record.month }
        if (existing != null &&
            existing.cost == record.cost &&
            existing.tokens == record.tokens &&
            existing.requests == record.requests
        ) {
            return false
        }
        val next = (all.filterNot { it.month == record.month } + record)
            .sortedBy { it.month }
            .takeLast(MAX_MONTHS)
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(serializer, next)).apply()
        }
        return true
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "months"
        const val MAX_MONTHS = 24
    }
}
