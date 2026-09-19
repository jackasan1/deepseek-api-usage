package com.rikka.dsusage.data

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** 单价，单位：元 / 百万 tokens。官方约定：高峰价 = 空闲价 × 2。 */
data class Rate(val hit: Double, val miss: Double, val resp: Double)

enum class RateTier(val label: String) {
    FLASH("deepseek-flash"),
    PRO("deepseek-v4-pro"),
    LEGACY("旧模型名（已下线）"),
}

/**
 * 峰谷计价。
 *
 * 官方规则：高峰为北京时间周一至周五 9:00-12:00、14:00-18:00，其余（含整个周末）为空闲；
 * 空闲单价为高峰的一半。
 *
 * 三档费率中，FLASH 与 PRO 取自官方定价页；LEGACY 是用「周末必为纯空闲时段」
 * 这一约束从实际扣费数据反推出来的（三个周末日均精确吻合到 1.000）。
 */
object PeakPricing {

    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    private val PEAK_WINDOWS = listOf(9 to 12, 14 to 18)

    private val OFF_PEAK = mapOf(
        RateTier.FLASH to Rate(0.02, 1.0, 4.0),
        RateTier.PRO to Rate(0.15, 4.5, 13.5),
        RateTier.LEGACY to Rate(0.05, 1.5, 4.5),
    )

    private val LEGACY_PATTERN = Regex("v4-flash", RegexOption.IGNORE_CASE)

    fun tierOf(model: String): RateTier = when {
        model.contains("v4-pro", ignoreCase = true) -> RateTier.PRO
        LEGACY_PATTERN.containsMatchIn(model) -> RateTier.LEGACY
        else -> RateTier.FLASH
    }

    fun isLegacy(model: String): Boolean = LEGACY_PATTERN.containsMatchIn(model)

    fun offPeakRate(tier: RateTier): Rate = OFF_PEAK.getValue(tier)

    fun peakRate(tier: RateTier): Rate = offPeakRate(tier).let {
        Rate(it.hit * 2, it.miss * 2, it.resp * 2)
    }

    /* ---------------- 时段判定 ---------------- */

    fun isWeekend(t: ZonedDateTime): Boolean {
        val d = t.withZoneSameInstant(ZONE).dayOfWeek
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY
    }

    fun isPeak(t: ZonedDateTime): Boolean {
        val z = t.withZoneSameInstant(ZONE)
        if (isWeekend(z)) return false
        val minutes = z.hour * 60 + z.minute
        return PEAK_WINDOWS.any { (s, e) -> minutes >= s * 60 && minutes < e * 60 }
    }

    /** 下一次时段切换时刻。周末没有切换点，会直接跳到周一 09:00。 */
    fun nextSwitch(t: ZonedDateTime): ZonedDateTime {
        val z = t.withZoneSameInstant(ZONE)
        for (i in 0L..8L) {
            val date = z.toLocalDate().plusDays(i)
            for (h in listOf(9, 12, 14, 18)) {
                val cand = date.atTime(h, 0).atZone(ZONE)
                if (isWeekend(cand)) continue
                if (cand.isAfter(z)) return cand
            }
        }
        return z.plusDays(1)
    }

    fun humanize(seconds: Long): String {
        if (seconds <= 0) return "即将切换"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "$h 小时 $m 分"
            m > 0 -> "$m 分 $s 秒"
            else -> "$s 秒"
        }
    }

    /* ---------------- 错峰分析 ---------------- */

    data class PeakStat(
        val model: String,
        val offPeakCost: Double,
        val actualCost: Double,
        val peakRatio: Double,
        val saving: Double,
        val legacy: Boolean,
    )

    data class PeakSummary(
        val rows: List<PeakStat>,
        val offPeakTotal: Double,
        val actualTotal: Double,
        val savingTotal: Double,
        val peakRatio: Double,
        val legacyModels: List<String>,
    )

    /**
     * 因为高峰价恰好是空闲价的 2 倍（三档费率、每个计费项都成立），所以
     *
     *     实际消费 = 闲时理论成本 × (1 + 高峰占比)
     *
     * 于是高峰占比可以直接反推出来，不需要接口提供小时粒度数据。
     */
    fun analyze(models: List<ModelStat>): PeakSummary {
        val rows = models.mapNotNull { m ->
            val rate = offPeakRate(tierOf(m.model))
            val off = (m.cacheHit / 1e6) * rate.hit +
                (m.cacheMiss / 1e6) * rate.miss +
                (m.response / 1e6) * rate.resp
            if (off <= 0.0001 && m.cost <= 0.0001) return@mapNotNull null
            val ratio =
                if (off > 0.0001) (m.cost / off - 1.0).coerceIn(0.0, 1.0) else 0.0
            PeakStat(
                model = m.model,
                offPeakCost = off,
                actualCost = m.cost,
                peakRatio = ratio,
                saving = (m.cost - off).coerceAtLeast(0.0),
                legacy = isLegacy(m.model),
            )
        }
        val off = rows.sumOf { it.offPeakCost }
        val act = rows.sumOf { it.actualCost }
        return PeakSummary(
            rows = rows.sortedByDescending { it.actualCost },
            offPeakTotal = off,
            actualTotal = act,
            savingTotal = (act - off).coerceAtLeast(0.0),
            peakRatio = if (off > 0.0001) (act / off - 1.0).coerceIn(0.0, 1.0) else 0.0,
            legacyModels = rows.filter { it.legacy }.map { it.model },
        )
    }
}
