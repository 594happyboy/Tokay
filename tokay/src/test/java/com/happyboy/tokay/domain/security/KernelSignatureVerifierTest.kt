package com.happyboy.tokay.domain.security

import com.happyboy.tokay.domain.models.KernelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * 清单分离式签名校验的回归护栏：签名正确 / 错误 / 缺失 / base64 非法 / 公钥为 null 时跳过并产出
 * WARN，外加跨语言（Python `cryptography` → JVM JCA）互通的固化夹具。纯 JVM，不需要设备。
 */
class KernelSignatureVerifierTest {

    private val verifier = KernelSignatureVerifier()

    /**
     * `tools/tokay-sign/sign.sh sign` 用 `keys/demo-p256.key` 签出的固化夹具（195 字节清单 + 71 字节
     * DER 签名）。它把签发工具的行为、内置公钥常量与 App 侧验签实现钉在一起：换密钥、换编码、
     * 把 DER 换成裸 R||S 都会让这条用例变红。
     */
    private val toolSignedPayload = (
        """{"geckoJavaVersion":"147.0.20260212191108","so":{"arm64-v8a":""" +
            """{"url":"https://example.invalid/geckoview-arm64-v8a.7z",""" +
            """"sha256":"256e036d9fe327ed43ff274bc01bcedaa749640a28190407fccecb133af84298"}}}"""
        ).toByteArray(Charsets.UTF_8)

    private val toolSignedSignature =
        "MEUCIAHX05oc96/LtoguMTnRi2fUmUTDhwuwmEhGdvmQ64dDAiEAogmH0fryzf6ZOqLumR2wfJFNkU6fA3GSwBuDENR785M="

    // 内置公钥常量自身的形状

    @Test
    fun builtInPublicKeyIsRawXY64Bytes() {
        assertEquals("裸 X/Y 必须是 X、Y 各 32 字节", 64, KernelSource.DEMO_P256_XY.size)
        assertEquals("X 是 32 字节 = 64 个十六进制字符", 32, KernelSource.DEMO_P256_X_HEX.length / 2)
        assertEquals("Y 是 32 字节 = 64 个十六进制字符", 32, KernelSource.DEMO_P256_Y_HEX.length / 2)
    }

    // 签名正确，且是另一个进程/语言签出来的

    @Test
    fun signatureFromTokaySignToolVerifiesWithBuiltInKey() {
        val verdict = verifier.verify(
            payload = toolSignedPayload,
            signatureBase64 = toolSignedSignature,
            publicKeyRawXY = KernelSource.DEMO_P256_XY
        )
        assertEquals("Python cryptography 签出的 DER 签名必须能被 App 侧验签通过", SignatureVerdict.Valid, verdict)
    }

    /** 同一把算法、另一对密钥的自签自验（证明「由裸 X/Y 重建公钥」这条路本身可用）。 */
    @Test
    fun signatureFromFreshlyGeneratedP256KeyPairVerifies() {
        val keyPair = generateP256KeyPair()
        val verdict = verifier.verify(
            payload = toolSignedPayload,
            signatureBase64 = sign(toolSignedPayload, keyPair),
            publicKeyRawXY = rawXY(keyPair)
        )
        assertEquals(SignatureVerdict.Valid, verdict)
    }

    // 签名错误：篡改载荷 / 换了一把密钥

    @Test
    fun tamperedPayloadIsRejectedWithReadableReason() {
        val tampered = toolSignedPayload.copyOf().also { it[10] = (it[10] + 1).toByte() }

        val verdict = verifier.verify(tampered, toolSignedSignature, KernelSource.DEMO_P256_XY)

        assertTrue("篡改载荷必须是 Invalid，实际 $verdict", verdict is SignatureVerdict.Invalid)
        assertTrue(
            "失败文案必须点明「签名不匹配」，实际: ${(verdict as SignatureVerdict.Invalid).reason}",
            verdict.reason.contains("不匹配")
        )
    }

    @Test
    fun signatureFromAnotherKeyIsRejected() {
        val keyPair = generateP256KeyPair()

        val verdict = verifier.verify(
            payload = toolSignedPayload,
            signatureBase64 = sign(toolSignedPayload, keyPair),
            publicKeyRawXY = KernelSource.DEMO_P256_XY
        )

        assertTrue(verdict is SignatureVerdict.Invalid)
    }

    // 签名缺失 / base64 非法

    @Test
    fun missingSignatureIsRejectedWithReadableReason() {
        for (missing in listOf(null, "", "   ", "\n")) {
            val verdict = verifier.verify(toolSignedPayload, missing, KernelSource.DEMO_P256_XY)
            assertTrue("签名=$missing 时必须拒绝", verdict is SignatureVerdict.Invalid)
            assertTrue(
                "文案必须点明签名缺失，实际: ${(verdict as SignatureVerdict.Invalid).reason}",
                verdict.reason.contains("签名缺失")
            )
        }
    }

    @Test
    fun nonBase64SignatureIsRejectedWithReadableReason() {
        val verdict = verifier.verify(toolSignedPayload, "这不是 base64!!!", KernelSource.DEMO_P256_XY)

        assertTrue(verdict is SignatureVerdict.Invalid)
        assertTrue(
            "文案必须点明 base64 非法，实际: ${(verdict as SignatureVerdict.Invalid).reason}",
            verdict.reason.contains("base64")
        )
    }

    /** 合法 base64 但根本不是 DER 签名（例如把整个文件当签名传了）也不能崩，只能是 Invalid。 */
    @Test
    fun base64ThatIsNotADerSignatureIsRejectedWithoutCrash() {
        val verdict = verifier.verify(toolSignedPayload, "aGVsbG8gd29ybGQ=", KernelSource.DEMO_P256_XY)

        assertTrue(verdict is SignatureVerdict.Invalid)
    }

    /** 公钥常量被写错长度（例如漏了一半）时给出可读原因，而不是抛到调用栈外面。 */
    @Test
    fun malformedPublicKeyLengthIsRejectedWithReadableReason() {
        val verdict = verifier.verify(toolSignedPayload, toolSignedSignature, ByteArray(10))

        assertTrue(verdict is SignatureVerdict.Invalid)
        assertTrue(
            "文案必须点明裸 X/Y 的字节数要求，实际: ${(verdict as SignatureVerdict.Invalid).reason}",
            verdict.reason.contains("64")
        )
    }

    // 公钥为 null → 跳过验签，并产出一条 WARN 文案

    @Test
    fun nullPublicKeySkipsVerificationAndProducesWarning() {
        // 没配公钥就不该去取签名文件，签名哪怕完全非法也要跳过
        val verdict = verifier.verify(toolSignedPayload, "garbage", null)

        assertTrue("公钥为 null 时必须跳过验签，实际 $verdict", verdict is SignatureVerdict.NoPublicKey)
        val warning = (verdict as SignatureVerdict.NoPublicKey).warning
        assertTrue("WARN 必须说清信任根只有 TLS，实际: $warning", warning.contains("TLS"))
        assertTrue("WARN 必须给出配置入口，实际: $warning", warning.contains("publicKeyP256"))
    }

    // helpers

    private fun generateP256KeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(payload: ByteArray, keyPair: KeyPair): String =
        java.util.Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(keyPair.private)
                update(payload)
            }.sign()
        )

    private fun rawXY(keyPair: KeyPair): ByteArray {
        val publicKey = keyPair.public as ECPublicKey
        return fixed32(publicKey.w.affineX) + fixed32(publicKey.w.affineY)
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            // BigInteger 为正数时会补一个 0x00 符号字节
            bytes.size == 33 -> bytes.copyOfRange(1, 33)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> error("坐标超长: ${bytes.size}")
        }
    }
}
