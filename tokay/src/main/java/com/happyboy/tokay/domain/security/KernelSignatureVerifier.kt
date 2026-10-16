package com.happyboy.tokay.domain.security

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * 清单签名算法。当前只有 **ECDSA P-256 / SHA-256**：Ed25519 在 `minSdk 26` 覆盖的机型上
 * JCA 里根本没有（Conscrypt 到 API 33 才加，原始输出见 README）。
 *
 * 做成枚举是为了将来提高了 minSdk 之后，加回 Ed25519 只在这里加一项。
 */
enum class KernelSignatureAlgorithm(
    /** JCA 的 `Signature` 算法名（与 Python `cryptography` 的 DER 签名互通） */
    val jcaSignatureName: String,
    /** JCA 的曲线名（`ECGenParameterSpec`） */
    val curveName: String,
    /** 单个坐标的字节数（P-256 = 32） */
    val coordinateLengthBytes: Int
) {
    ECDSA_P256_SHA256("SHA256withECDSA", "secp256r1", 32)
}

/**
 * 验签结论。用「结论」而不是「布尔 + 异常」，是因为跳过验签（没配公钥）与验签失败是两种不同的
 * 事实：前者允许继续安装但要打 WARN，后者必须拒绝。
 */
sealed interface SignatureVerdict {
    object Valid : SignatureVerdict

    /**
     * 没有配置内置公钥 → 跳过验签（信任根只剩 TLS）。
     *
     * [warning] 由这里给出而不是让调用点自己拼：这样单测能断言「确实产出了一条 WARN 文案」，
     * 而不是只断言「没抛异常」。
     */
    data class NoPublicKey(val warning: String) : SignatureVerdict

    /** 验签不通过（签名缺失 / base64 非法 / 字节不匹配 / 公钥不可用），[reason] 可直接给用户看 */
    data class Invalid(val reason: String) : SignatureVerdict
}

/**
 * 分离式签名校验器：验签对象是清单响应体的**原始字节**，签名取自同名的 `<configUrl>.sig`。
 *
 * 刻意不把 `signature` 内嵌进 JSON——那要求签发端（Python）与校验端（Kotlin）实现同一套规范化
 * 序列化，一处空格或键序差异的症状就是设备上「装不上内核」且没有任何指向根因的报错。
 *
 * 纯 JVM 实现（只用 `java.security` + `java.util.Base64`，API 26 起可用），核心判定无需设备即可测。
 */
internal class KernelSignatureVerifier(
    private val algorithm: KernelSignatureAlgorithm = KernelSignatureAlgorithm.ECDSA_P256_SHA256
) {

    /**
     * @param payload 清单**原始字节**，不能是重新序列化后的字符串
     * @param signatureBase64 `<configUrl>.sig` 的内容（允许带换行）；null/空白视为签名缺失
     * @param publicKeyRawXY 内置公钥的裸 X/Y（64 字节）；null = 不验签
     */
    fun verify(
        payload: ByteArray,
        signatureBase64: String?,
        publicKeyRawXY: ByteArray?
    ): SignatureVerdict {
        if (publicKeyRawXY == null) {
            return SignatureVerdict.NoPublicKey(
                "未配置内置公钥，跳过清单签名校验——信任根只有 TLS；" +
                    "生产环境请通过 GeckoSdk.init(kernelSource = ...) 配置 publicKeyP256"
            )
        }
        if (signatureBase64.isNullOrBlank()) {
            return SignatureVerdict.Invalid("签名缺失：<configUrl>.sig 不存在或为空")
        }

        val signatureBytes = try {
            // 必须用严格解码器：MIME 解码器会静默忽略非 base64 字符，于是一个写坏的签名文件会
            // 退化成"验签不匹配"，把「签名文件坏了」误报成「清单被篡改」。
            Base64.getDecoder().decode(signatureBase64.trim())
        } catch (e: IllegalArgumentException) {
            return SignatureVerdict.Invalid("签名不是合法的 base64: ${e.message}")
        }

        return try {
            val verifier = Signature.getInstance(algorithm.jcaSignatureName)
            verifier.initVerify(rebuildPublicKey(publicKeyRawXY))
            verifier.update(payload)
            if (verifier.verify(signatureBytes)) {
                SignatureVerdict.Valid
            } else {
                SignatureVerdict.Invalid("签名与清单字节不匹配（清单被篡改，或签名与内置公钥不是一对）")
            }
        } catch (e: Exception) {
            SignatureVerdict.Invalid("签名校验无法执行: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 由裸 X/Y 重建公钥，省掉 PEM/DER 解析器。 */
    private fun rebuildPublicKey(rawXY: ByteArray): PublicKey {
        val n = algorithm.coordinateLengthBytes
        require(rawXY.size == n * 2) {
            "内置公钥必须是裸 X/Y 共 ${n * 2} 字节（X、Y 各 $n 字节），实际 ${rawXY.size} 字节"
        }
        val x = BigInteger(1, rawXY.copyOfRange(0, n))
        val y = BigInteger(1, rawXY.copyOfRange(n, rawXY.size))
        // 不能写成 AlgorithmParameters.getInstance("EC").apply { init(...) }：在 apply 作用域里
        // `algorithm` 会解析成 AlgorithmParameters.getAlgorithm()（被 Kotlin 合成成属性），
        // 把本类的 algorithm 属性屏蔽掉。
        val ecParams = AlgorithmParameters.getInstance("EC")
        ecParams.init(ECGenParameterSpec(algorithm.curveName))
        val params = ecParams.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }
}
