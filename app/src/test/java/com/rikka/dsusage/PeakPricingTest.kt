package com.rikka.dsusage

import com.rikka.dsusage.data.ModelStat
import com.rikka.dsusage.data.PeakPricing
import com.rikka.dsusage.data.RateTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 峰值计价的回归测试。
 * 2026-09-19 是周六；2026-09-21 是周一；2026-09-25 是周五；2026-09-28 是周一。
 */
class PeakPricingTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): ZonedDateTime =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)

    @Test
    fun `周末全天都算空闲`() {
        assertFalse(PeakPricing.isPeak(at(2026, 9, 19, 10)))
        assertFalse(PeakPricing.isPeak(at(2026, 9, 20, 15)))
        assertTrue(PeakPricing.isWeekend(at(2026, 9, 19, 3)))
    }

    @Test
    fun `工作日高峰窗口的四个边界`() {
        assertFalse("08:59 应为空闲", PeakPricing.isPeak(at(2026, 9, 21, 8, 59)))
        assertTrue("09:00 应为高峰", PeakPricing.isPeak(at(2026, 9, 21, 9, 0)))
        assertTrue("11:59 应为高峰", PeakPricing.isPeak(at(2026, 9, 21, 11, 59)))
        assertFalse("12:00 应转空闲", PeakPricing.isPeak(at(2026, 9, 21, 12, 0)))
        assertFalse("13:59 应为空闲", PeakPricing.isPeak(at(2026, 9, 21, 13, 59)))
        assertTrue("14:00 应为高峰", PeakPricing.isPeak(at(2026, 9, 21, 14, 0)))
        assertTrue("17:59 应为高峰", PeakPricing.isPeak(at(2026, 9, 21, 17, 59)))
        assertFalse("18:00 应转空闲", PeakPricing.isPeak(at(2026, 9, 21, 18, 0)))
    }

    @Test
    fun `周五下班后跳过周末直接到周一九点`() {
        assertEquals(at(2026, 9, 28, 9, 0), PeakPricing.nextSwitch(at(2026, 9, 25, 19, 0)))
    }

    @Test
    fun `周六深夜与周日中午的下一跳都是随后的周一九点`() {
        assertEquals(at(2026, 9, 28, 9, 0), PeakPricing.nextSwitch(at(2026, 9, 26, 23, 0)))
        assertEquals(at(2026, 9, 21, 9, 0), PeakPricing.nextSwitch(at(2026, 9, 20, 12, 0)))
    }

    @Test
    fun `高峰中下一跳是窗口结束`() {
        assertEquals(at(2026, 9, 21, 12, 0), PeakPricing.nextSwitch(at(2026, 9, 21, 10, 0)))
        assertEquals(at(2026, 9, 21, 18, 0), PeakPricing.nextSwitch(at(2026, 9, 21, 14, 30)))
    }

    /**
     * 反推公式的全部前提：高峰价恒为空闲价的 2 倍。
     * 这一条如果被改坏，高峰占比推算就整体失效。
     */
    @Test
    fun `每一档费率的高峰价都恰好是空闲价的两倍`() {
        RateTier.values().forEach { tier ->
            val off = PeakPricing.offPeakRate(tier)
            val peak = PeakPricing.peakRate(tier)
            assertEquals(off.hit * 2, peak.hit, 1e-9)
            assertEquals(off.miss * 2, peak.miss, 1e-9)
            assertEquals(off.resp * 2, peak.resp, 1e-9)
        }
    }

    @Test
    fun `费率表数值锁定`() {
        val f = PeakPricing.offPeakRate(RateTier.FLASH)
        assertEquals(0.02, f.hit, 1e-9)
        assertEquals(1.0, f.miss, 1e-9)
        assertEquals(4.0, f.resp, 1e-9)
        val p = PeakPricing.offPeakRate(RateTier.PRO)
        assertEquals(0.15, p.hit, 1e-9)
        assertEquals(4.5, p.miss, 1e-9)
        assertEquals(13.5, p.resp, 1e-9)
        val l = PeakPricing.offPeakRate(RateTier.LEGACY)
        assertEquals(0.05, l.hit, 1e-9)
        assertEquals(1.5, l.miss, 1e-9)
        assertEquals(4.5, l.resp, 1e-9)
    }

    @Test
    fun `模型名映射到正确的费率档`() {
        assertEquals(RateTier.FLASH, PeakPricing.tierOf("deepseek-flash"))
        assertEquals(RateTier.PRO, PeakPricing.tierOf("deepseek-v4-pro"))
        assertEquals(RateTier.LEGACY, PeakPricing.tierOf("deepseek-v4-flash"))
        assertEquals(RateTier.LEGACY, PeakPricing.tierOf("deepseek-v4-flash-vision-exp"))
    }

    @Test
    fun `纯空闲用量的高峰占比为零`() {
        val m = ModelStat(
            model = "deepseek-flash",
            requests = 100,
            cacheHit = 1_000_000,
            cacheMiss = 1_000_000,
            response = 1_000_000,
            cost = 5.02, // 1×0.02 + 1×1.0 + 1×4.0
        )
        val s = PeakPricing.analyze(listOf(m))
        assertEquals(0.0, s.peakRatio, 1e-6)
        assertEquals(0.0, s.savingTotal, 1e-6)
    }

    @Test
    fun `全高峰用量的高峰占比为一`() {
        val m = ModelStat(
            model = "deepseek-flash",
            requests = 100,
            cacheHit = 1_000_000,
            cacheMiss = 1_000_000,
            response = 1_000_000,
            cost = 5.02 * 2,
        )
        val s = PeakPricing.analyze(listOf(m))
        assertEquals(1.0, s.peakRatio, 1e-6)
        assertEquals(5.02, s.savingTotal, 1e-6)
    }

    @Test
    fun `数据异常时占比与节省被夹在合法区间`() {
        val m = ModelStat(model = "deepseek-flash", cacheHit = 1_000_000, cost = 0.001)
        val s = PeakPricing.analyze(listOf(m))
        assertTrue(s.peakRatio >= 0.0)
        assertTrue(s.peakRatio <= 1.0)
        assertTrue(s.savingTotal >= 0.0)
    }
}
