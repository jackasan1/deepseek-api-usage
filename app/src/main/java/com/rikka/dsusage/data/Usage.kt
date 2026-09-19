package com.rikka.dsusage.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 网页登录凭证失效（接口 code=40003），UI 据此提示重新登录。 */
class TokenExpiredException(message: String) : IllegalStateException(message)

@Serializable
data class UsageEnvelope(
    val code: Int = -1,
    val msg: String = "",
    val data: UsagePayload? = null,
)

@Serializable
data class UsagePayload(
    @SerialName("biz_code") val bizCode: Int = -1,
    @SerialName("biz_msg") val bizMsg: String = "",
    @SerialName("biz_data") val bizData: JsonElement? = null,
)

@Serializable
data class UsageItem(val type: String = "", val amount: String = "0")

@Serializable
data class ModelUsage(val model: String = "", val usage: List<UsageItem> = emptyList())

@Serializable
data class DayBlock(val date: String = "", val data: List<ModelUsage> = emptyList())

@Serializable
data class BizData(
    val total: List<ModelUsage> = emptyList(),
    val days: List<DayBlock> = emptyList(),
)

/* ---------------- UI 层用的聚合模型 ---------------- */

@Serializable
data class ModelStat(
    val model: String,
    val requests: Long = 0,
    val cacheHit: Long = 0,
    val cacheMiss: Long = 0,
    val response: Long = 0,
    val cost: Double = 0.0,
) {
    val tokens: Long get() = cacheHit + cacheMiss + response
    val cacheRatio: Double
        get() = if (cacheHit + cacheMiss > 0) cacheHit.toDouble() / (cacheHit + cacheMiss) else 0.0
}

@Serializable
data class DayStat(val date: String, val tokens: Long, val cost: Double)

@Serializable
data class UsageSnapshot(
    val models: List<ModelStat>,
    val days: List<DayStat>,
    val totalCost: Double,
    val totalRequests: Long,
    val totalTokens: Long,
) {
    val cacheRatio: Double
        get() {
            var h = 0L
            var m = 0L
            models.forEach { h += it.cacheHit; m += it.cacheMiss }
            return if (h + m > 0) h.toDouble() / (h + m) else 0.0
        }
}

/**
 * platform.deepseek.com 的内部用量接口（非官方，可能变更）。
 *
 * 抓包确认的事实：
 *  - 鉴权用的是网页登录 token（localStorage.userToken 里 JSON 的 .value），不是 API Key
 *  - 鉴权失败时 HTTP 状态码依然是 200，必须判 body 里的 code
 *  - amount 的 biz_data 是对象；cost 的 biz_data 是数组，真正的数据在 [0]
 */
object PlatformApi {

    private const val BASE = "https://platform.deepseek.com/api/v0/usage"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchMonth(token: String, year: Int, month: Int): Result<UsageSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val amount = fetchOne("$BASE/amount?month=$month&year=$year", token)
                val cost = fetchOne("$BASE/cost?month=$month&year=$year", token)
                merge(amount, cost)
            }
        }

    private fun fetchOne(url: String, token: String): BizData {
        val body = DeepSeekApi.httpGet(
            url,
            mapOf(
                "accept" to "application/json",
                "authorization" to "Bearer $token",
                "user-agent" to UA,
                "accept-language" to "zh-CN,zh;q=0.9",
            )
        )
        val env = json.decodeFromString<UsageEnvelope>(body)
        if (env.code != 0) {
            throw when (env.code) {
                40003 -> TokenExpiredException("登录已失效，请重新登录 DeepSeek")
                40001 -> TokenExpiredException("缺少登录凭证")
                else -> IllegalStateException("用量接口返回 code=${env.code} ${env.msg}")
            }
        }
        val raw = env.data?.bizData
        val obj: JsonObject? = when {
            raw is JsonObject -> raw
            raw is JsonArray && raw.isNotEmpty() -> raw[0] as? JsonObject
            else -> null
        }
        if (obj == null) throw IllegalStateException("用量数据格式异常")
        return json.decodeFromString<BizData>(obj.toString())
    }

    internal fun merge(amount: BizData, cost: BizData): UsageSnapshot {
        val acc = LinkedHashMap<String, ModelStat>()

        fun apply(list: List<ModelUsage>, isCost: Boolean) {
            list.forEach { mu ->
                val cur = acc[mu.model] ?: ModelStat(mu.model)
                var requests = cur.requests
                var hit = cur.cacheHit
                var miss = cur.cacheMiss
                var resp = cur.response
                var c = cur.cost
                mu.usage.forEach { u ->
                    val v = u.amount.toDoubleOrNull() ?: 0.0
                    when (u.type) {
                        "PROMPT_CACHE_HIT_TOKEN" -> if (isCost) c += v else hit = v.toLong()
                        "PROMPT_CACHE_MISS_TOKEN" -> if (isCost) c += v else miss = v.toLong()
                        "RESPONSE_TOKEN" -> if (isCost) c += v else resp = v.toLong()
                        // PROMPT_TOKEN 恒为 0，且已包含在 hit + miss 中，跳过以免重复计数
                        "REQUEST" -> if (!isCost) requests = v.toLong()
                    }
                }
                acc[mu.model] = cur.copy(
                    requests = requests,
                    cacheHit = hit,
                    cacheMiss = miss,
                    response = resp,
                    cost = c,
                )
            }
        }

        apply(amount.total, false)
        apply(cost.total, true)

        val dayMap = LinkedHashMap<String, DayStat>()
        amount.days.forEach { d ->
            val t = d.data.sumOf { mu ->
                mu.usage.filter { it.type != "REQUEST" }
                    .sumOf { it.amount.toDoubleOrNull()?.toLong() ?: 0L }
            }
            dayMap[d.date] = DayStat(d.date, t, 0.0)
        }
        cost.days.forEach { d ->
            val c = d.data.sumOf { mu -> mu.usage.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } }
            val prev = dayMap[d.date]
            dayMap[d.date] = DayStat(d.date, prev?.tokens ?: 0L, c)
        }

        val models = acc.values.sortedByDescending { it.tokens }
        return UsageSnapshot(
            models = models,
            days = dayMap.values
                .filter { it.tokens > 0 || it.cost > 0 }
                .sortedBy { it.date },
            totalCost = models.sumOf { it.cost },
            totalRequests = models.sumOf { it.requests },
            totalTokens = models.sumOf { it.tokens },
        )
    }
}
