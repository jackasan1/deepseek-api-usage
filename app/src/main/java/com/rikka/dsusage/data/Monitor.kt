package com.rikka.dsusage.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.workDataOf
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rikka.dsusage.MainActivity
import com.rikka.dsusage.widget.BalanceWidget
import java.util.concurrent.TimeUnit

const val KEY_API = "api_key"
const val KEY_TOKEN = "web_token"

/** 非敏感的监控设置，走普通 SharedPreferences（凭证才走 Keystore）。 */
class MonitorSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ds_usage_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("monitor_enabled", false)
        set(value) {
            prefs.edit().putBoolean("monitor_enabled", value).apply()
        }

    var threshold: Double
        get() = prefs.getString("threshold", null)?.toDoubleOrNull() ?: DEFAULT_THRESHOLD
        set(value) {
            prefs.edit().putString("threshold", value.toString()).apply()
        }

    companion object {
        const val DEFAULT_THRESHOLD = 10.0
    }
}

object Notifier {

    private const val CHANNEL_ID = "ds_usage_alert"
    private const val NOTIFY_ID = 1001

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "余额预警",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "DeepSeek 余额低于阈值时提醒" }
            )
        }
    }

    fun lowBalance(ctx: Context, balance: Double, threshold: Double, currency: String) {
        ensureChannel(ctx)
        val symbol = if (currency == "USD") "$" else "¥"
        val pi = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "当前余额 $symbol$balance（$currency），已低于你设定的阈值 $symbol$threshold，建议尽快充值。"
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("DeepSeek 余额不足")
            .setContentText("当前 $symbol$balance，低于阈值 $symbol$threshold")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, n)
        } catch (e: SecurityException) {
            // 用户拒绝了通知权限，静默跳过
        }
    }
}

/**
 * 后台查一次余额，低于阈值就推通知。
 * API Key 从 Keystore 加密存储里读，不出设备。
 */
class BalanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val settings = MonitorSettings(applicationContext)
        val key = SecureStore(applicationContext).get(KEY_API)
        if (key.isNullOrBlank()) return Result.success()
        // force 用于"小组件手动刷新"，此时即使没开后台监控也要跑
        if (!settings.enabled && !force) return Result.success()

        val threshold = settings.threshold
        return DeepSeekApi.fetchBalance(key).fold(
            onSuccess = { r ->
                val info = r.balanceInfos.firstOrNull()
                // 落地缓存：App 冷启动与桌面小组件都读它
                DashCache(applicationContext).saveBalance(
                    info,
                    r.isAvailable,
                    System.currentTimeMillis(),
                )
                val total = info?.totalBalance?.toDoubleOrNull()
                val low = !r.isAvailable || (total != null && total < threshold)
                if (low) {
                    Notifier.lowBalance(
                        applicationContext,
                        total ?: 0.0,
                        threshold,
                        info?.currency ?: "CNY",
                    )
                }
                BalanceWidget.refreshAll(applicationContext)
                Result.success()
            },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        private const val KEY_FORCE = "force"

        fun forceInputData(): Data = workDataOf(KEY_FORCE to true)
    }
}
object MonitorScheduler {

    private const val WORK_NAME = "ds_balance_monitor"

    fun sync(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        Notifier.ensureChannel(ctx)
        val request = PeriodicWorkRequestBuilder<BalanceWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
