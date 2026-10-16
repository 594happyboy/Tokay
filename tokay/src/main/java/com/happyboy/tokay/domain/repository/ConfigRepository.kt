package com.happyboy.tokay.domain.repository

import android.util.Log
import com.happyboy.tokay.BuildConfig
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.domain.models.KernelSource
import com.happyboy.tokay.domain.models.SdkConfig
import com.happyboy.tokay.domain.models.SoInfo
import com.happyboy.tokay.domain.security.KernelManifestPolicy
import com.happyboy.tokay.domain.security.KernelSignatureVerifier
import com.happyboy.tokay.domain.security.KernelTrustException
import com.happyboy.tokay.domain.security.SignatureVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * SDK 配置（内核清单）仓库，同时是**信任链的第一环**：清单的 ECDSA P-256 签名在这一层校验，
 * 验签对象是**响应体的原始字节**（`<configUrl>.sig`，不是重新序列化后的字符串）。它只在编排器
 * 「获取下载配置」这一步被调用（此时尚无下载状态），故签名失败报出的 `Failed.phase` 是 **CHECKING**。
 *
 * 只接受 [KernelSource.Remote]：随包模式没有清单、没有 `.sig`，也就没有这一环。
 */
internal class ConfigRepository(
    private val kernelSource: KernelSource.Remote = KernelSource.DEFAULT,
    private val signatureVerifier: KernelSignatureVerifier = KernelSignatureVerifier()
) {

    companion object {
        /** 分离式签名后缀：签名文件与被签文件同名，追加 `.sig` */
        const val SIGNATURE_SUFFIX = ".sig"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 获取 SDK 配置；验签不通过时 `Result.failure` 里是 `NonRetryableFailure` 的 `KernelTrustException`。 */
    suspend fun fetchConfig(): Result<SdkConfig> = withContext(Dispatchers.IO) {
        try {
            val payload = httpGetBytes(kernelSource.configUrl)

            // 没配公钥就不取签名文件：此时「签名缺失」不是失败，而是跳过验签的形态
            val signatureBase64 = if (kernelSource.publicKeyP256 == null) null else fetchSignatureOrNull()

            when (val verdict = signatureVerifier.verify(payload, signatureBase64, kernelSource.publicKeyP256)) {
                is SignatureVerdict.Valid ->
                    Log.d(LOG_TAG, "清单签名校验通过: ${kernelSource.configUrl}")

                is SignatureVerdict.NoPublicKey ->
                    Log.w(LOG_TAG, verdict.warning)

                is SignatureVerdict.Invalid ->
                    // 不可重试：同一份坏清单重试多少次结果都一样（见 KernelTrustException）
                    throw KernelTrustException("清单签名校验失败: ${verdict.reason}（${kernelSource.configUrl}）")
            }

            val config = json.decodeFromString<SdkConfig>(String(payload, Charsets.UTF_8))
            Log.d(LOG_TAG, "SDK 配置获取成功")
            Result.success(config)

        } catch (e: KernelTrustException) {
            // 不能包装：包装会丢掉 NonRetryableFailure 标记与原始文案，重试策略就认不出「不该重试」
            Log.e(LOG_TAG, "清单信任校验失败（不可重试）", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "获取 SDK 配置失败", e)
            Result.failure(Exception("获取 SDK 配置失败: ${e.message}", e))
        }
    }

    /** 取指定 Java 版本与 ABI 的 so 下载信息；ABI 缺失 / url 空 / sha256 空都是不可重试的 `KernelTrustException`。 */
    suspend fun getDownloadInfo(geckoJavaVersion: String, abiTag: String): Result<SoInfo> {
        return fetchConfig().mapCatching { config ->
            if (config.geckoJavaVersion != BuildConfig.GECKOVIEW_VERSION) {
                throw Exception("配置的 geckoJavaVersion=${config.geckoJavaVersion} 与本地 ${BuildConfig.GECKOVIEW_VERSION} 不匹配")
            }

            // 防止误用旧版本的下载链接
            if (geckoJavaVersion != BuildConfig.GECKOVIEW_VERSION) {
                throw Exception("请求的版本号 $geckoJavaVersion 与本地 ${BuildConfig.GECKOVIEW_VERSION} 不一致")
            }

            val soInfo = KernelManifestPolicy.requireSoInfo(abiTag, config.so)

            Log.d(
                LOG_TAG,
                "找到 so 下载配置 - geckoJavaVersion=${config.geckoJavaVersion}, ABI=$abiTag, URL=${soInfo.url}, sha256=${soInfo.sha256}"
            )
            soInfo
        }
    }

    /** 取 `<configUrl>.sig`；404/超时/空内容都返回 null，由验签器报「签名缺失」。 */
    private fun fetchSignatureOrNull(): String? = runCatching {
        String(httpGetBytes(kernelSource.configUrl + SIGNATURE_SUFFIX), Charsets.UTF_8)
            .trim()
            .ifBlank { null }
    }.onFailure { e ->
        Log.w(
            LOG_TAG,
            "清单签名文件获取失败: ${kernelSource.configUrl}$SIGNATURE_SUFFIX（${e.message}）"
        )
    }.getOrNull()

    /** GET 一个地址并返回**原始字节**：验签必须基于字节，不能基于解码后的字符串再编码回去。 */
    private fun httpGetBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                connect()
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("服务器返回 HTTP ${connection.responseCode}: ${connection.responseMessage}")
            }

            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
