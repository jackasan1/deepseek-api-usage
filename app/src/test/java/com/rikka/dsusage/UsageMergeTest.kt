package com.rikka.dsusage

import com.rikka.dsusage.data.BizData
import com.rikka.dsusage.data.DayBlock
import com.rikka.dsusage.data.ModelUsage
import com.rikka.dsusage.data.PlatformApi
import com.rikka.dsusage.data.UsageItem
import com.rikka.dsusage.ui.fillMissingDays
import com.rikka.dsusage.data.DayStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用量合并逻辑的回归测试——这里最容易出静默的重复计数错误。 */
class UsageMergeTest {

    private fun item(type: String, amount: String) = UsageItem(type, amount)

    @Test
    fun `PROMPT_TOKEN 必须被跳过否则会重复计数`() {
        val amount = BizData(
            total = listOf(
                ModelUsage(
                    "deepseek-flash",
                    listOf(
                        item("PROMPT_TOKEN", "999999"),
                        item("PROMPT_CACHE_HIT_TOKEN", "1000000"),
                        item("PROMPT_CACHE_MISS_TOKEN", "1000000"),
                        item("RESPONSE_TOKEN", "1000000"),
                        item("REQUEST", "10"),
                    ),
                )
            )
        )
        val cost = BizData(
            total = listOf(
                ModelUsage(
                    "deepseek-flash",
                    listOf(
                        item("PROMPT_TOKEN", "0"),
                        item("PROMPT_CACHE_HIT_TOKEN", "0.02"),
                        item("PROMPT_CACHE_MISS_TOKEN", "1.0"),
                        item("RESPONSE_TOKEN", "4.0"),
                    ),
                )
            )
        )
        val snap = PlatformApi.merge(amount, cost)
        assertEquals("应是 3 百万而不是多算了 PROMPT_TOKEN", 3_000_000L, snap.totalTokens)
        assertEquals(10L, snap.totalRequests)
        assertEquals(5.02, snap.totalCost, 1e-9)
    }

    @Test
    fun `按天汇总要跳过 REQUEST 并合并两边的数据`() {
        val amount = BizData(
            days = listOf(
                DayBlock(
                    "2026-09-01",
                    listOf(
                        ModelUsage(
                            "deepseek-flash",
                            listOf(
                                item("PROMPT_CACHE_HIT_TOKEN", "1000000"),
                                item("RESPONSE_TOKEN", "1000000"),
                                item("REQUEST", "12345"),
                            ),
                        )
                    ),
                )
            )
        )
        val cost = BizData(
            days = listOf(
                DayBlock(
                    "2026-09-01",
                    listOf(
                        ModelUsage(
                            "deepseek-flash",
                            listOf(
                                item("PROMPT_CACHE_HIT_TOKEN", "0.02"),
                                item("RESPONSE_TOKEN", "4.0"),
                            ),
                        )
                    ),
                )
            )
        )
        val snap = PlatformApi.merge(amount, cost)
        assertEquals(1, snap.days.size)
        assertEquals(2_000_000L, snap.days[0].tokens)
        assertEquals(4.02, snap.days[0].cost, 1e-9)
    }

    @Test
    fun `零用量与零成本的日期被过滤掉`() {
        val amount = BizData(
            days = listOf(
                DayBlock("2026-09-01", listOf(ModelUsage("deepseek-flash", listOf(item("RESPONSE_TOKEN", "0"))))),
                DayBlock("2026-09-02", listOf(ModelUsage("deepseek-flash", listOf(item("RESPONSE_TOKEN", "500"))))),
            )
        )
        val snap = PlatformApi.merge(amount, BizData())
        assertEquals(1, snap.days.size)
        assertEquals("2026-09-02", snap.days[0].date)
    }

    @Test
    fun `补齐缺失日期为零值`() {
        val days = listOf(
            DayStat("2026-09-01", 100, 1.0),
            DayStat("2026-09-04", 400, 4.0),
        )
        val filled = fillMissingDays(days)
        assertEquals(4, filled.size)
        assertEquals(
            listOf("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04"),
            filled.map { it.date },
        )
        assertEquals(0L, filled[1].tokens)
        assertEquals(0.0, filled[2].cost, 1e-9)
    }

    @Test
    fun `补齐函数对空列表返回空`() {
        assertTrue(fillMissingDays(emptyList()).isEmpty())
    }
}
