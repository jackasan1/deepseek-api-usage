package com.rikka.dsusage.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.time.YearMonth
import java.util.Locale

/** 把某个月的用量快照导出成 CSV（按模型 + 按天两段）。 */
object Exporter {

    fun exportCsv(ctx: Context, snap: UsageSnapshot, ym: YearMonth): String? {
        val sb = StringBuilder()
        sb.append("模型,Tokens,缓存命中,缓存未命中,输出Token,请求次数,消费(CNY),缓存命中率\n")
        snap.models.filter { it.tokens > 0 || it.cost > 0 }.forEach { m ->
            sb.append(
                listOf(
                    csvCell(m.model),
                    m.tokens.toString(),
                    m.cacheHit.toString(),
                    m.cacheMiss.toString(),
                    m.response.toString(),
                    m.requests.toString(),
                    money(m.cost),
                    pct(m.cacheRatio),
                ).joinToString(",")
            ).append('\n')
        }
        sb.append('\n')
        sb.append("日期,Tokens,消费(CNY)\n")
        snap.days.forEach { d -> sb.append("${d.date},${d.tokens},${money(d.cost)}\n") }
        sb.append('\n')
        sb.append("合计,,,,\n")
        sb.append("总Tokens,${snap.totalTokens}\n")
        sb.append("总请求,${snap.totalRequests}\n")
        sb.append("总消费(CNY),${money(snap.totalCost)}\n")
        sb.append("缓存命中率,${pct(snap.cacheRatio)}\n")

        val name = "deepseek-usage-${ym.year}-${pad(ym.monthValue)}.csv"
        return write(ctx, name, sb.toString())
    }

    private fun pad(v: Int) = String.format(Locale.CHINA, "%02d", v)
    private fun money(v: Double) = String.format(Locale.CHINA, "%.4f", v)
    private fun pct(v: Double) = String.format(Locale.CHINA, "%.2f%%", v * 100)

    /** 模型名里可能带逗号或 &，按 CSV 规则加引号并转义。 */
    private fun csvCell(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    private fun write(ctx: Context, name: String, content: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 走 MediaStore，直接落到公共下载目录，无需任何存储权限
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                ctx.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
            }
            uri?.toString()
        } else {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = File(dir, name)
            f.writeText(content)
            f.absolutePath
        }
    } catch (e: Exception) {
        null
    }
}
