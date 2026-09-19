package com.rikka.dsusage.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-256-GCM 主密钥加密后落到 SharedPreferences。
 * 明文私钥永不落盘，密钥材料由 TEE/StrongBox 保管，卸载 App 即失效。
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences("ds_usage_secure", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(key, blob).apply()
    }

    fun get(key: String): String? {
        val blob = prefs.getString(key, null) ?: return null
        val parts = blob.split(":")
        if (parts.size != 2) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val ALIAS = "ds_usage_master_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

@Serializable
data class BalanceInfo(
    val currency: String = "",
    @SerialName("total_balance") val totalBalance: String = "",
    @SerialName("granted_balance") val grantedBalance: String = "",
    @SerialName("topped_up_balance") val toppedUpBalance: String = "",
)

@Serializable
data class BalanceResponse(
    @SerialName("is_available") val isAvailable: Boolean = false,
    @SerialName("balance_infos") val balanceInfos: List<BalanceInfo> = emptyList(),
)

/** 官方公开接口：只有余额。用量统计没有官方 API，见 PlatformApi。 */
object DeepSeekApi {

    private const val BALANCE_URL = "https://api.deepseek.com/user/balance"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchBalance(apiKey: String): Result<BalanceResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = httpGet(
                    BALANCE_URL,
                    mapOf(
                        "Authorization" to "Bearer $apiKey",
                        "Accept" to "application/json",
                    )
                )
                json.decodeFromString<BalanceResponse>(body)
            }
        }

    internal fun httpGet(url: String, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return when {
                code == 200 -> body
                code == 401 -> throw IllegalStateException("API Key 无效或已过期")
                code == 402 -> throw IllegalStateException("余额不足，请充值")
                code == 429 -> throw IllegalStateException("请求过于频繁，请稍后重试")
                code in 500..599 -> throw IllegalStateException("DeepSeek 服务端错误（HTTP $code）")
                else -> throw IllegalStateException("请求失败 HTTP $code：${body.take(180)}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
