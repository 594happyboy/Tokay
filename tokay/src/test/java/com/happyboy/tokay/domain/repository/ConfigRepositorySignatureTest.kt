package com.happyboy.tokay.domain.repository

import com.happyboy.tokay.BuildConfig
import com.happyboy.tokay.domain.models.KernelSource
import com.happyboy.tokay.domain.models.SdkConfig
import com.happyboy.tokay.domain.policy.NonRetryableFailure
import com.happyboy.tokay.domain.security.KernelTrustException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 清单验签走「真实 HTTP 取字节」这条路径的回归护栏：验签对象必须是**响应体的原始字节**，若顺手写成
 * 解码成 String 再编码回去，清单带非 ASCII 字符或 BOM 时会静默失配。
 * 用裸 [ServerSocket] 而非 `com.sun.net.httpserver`：后者在 Android 单测的编译类路径上解析不到。
 */
class ConfigRepositorySignatureTest {

    private lateinit var server: StubHttpServer
    private lateinit var keyPair: KeyPair
    private var baseUrl: String = ""
    private val responses = ConcurrentHashMap<String, ByteArray>()

    @Before
    fun setUp() {
        server = StubHttpServer(responses)
        baseUrl = "http://127.0.0.1:${server.port}"
        keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // 签名正确

    @Test
    fun validSignatureOverServedBytesIsAccepted() {
        val manifest = manifestBytes()
        serve("/sdkconfig.json", manifest)
        serve("/sdkconfig.json.sig", sign(manifest).toByteArray(Charsets.US_ASCII))

        val config = runBlocking { repository(baseUrl).fetchConfig().getOrThrow() }

        assertEquals(BuildConfig.GECKOVIEW_VERSION, config.geckoJavaVersion)
        assertEquals("https://example.invalid/kernel.7z", config.so.getValue("arm64-v8a").url)
    }

    // 清单被篡改（服务端字节变了，签名还是旧的）

    @Test
    fun tamperedManifestBytesAreRejectedAsNonRetryableFailure() {
        val original = manifestBytes()
        serve("/sdkconfig.json.sig", sign(original).toByteArray(Charsets.US_ASCII))
        // 签名之后再改一个字节：模拟「上传后被替换 / 中间人改了一个字符」
        val tampered = original.copyOf().also { it[it.size - 2] = 'X'.code.toByte() }
        serve("/sdkconfig.json", tampered)

        val error = runBlocking { repository(baseUrl).fetchConfig() }.exceptionOrNull()

        assertNotNull("篡改清单必须让 fetchConfig 失败", error)
        assertTrue("必须是 KernelTrustException，实际 $error", error is KernelTrustException)
        assertTrue(
            "必须是**不可重试**失败，否则会白退避 10 次；实际类型 ${error!!::class.java}",
            error is NonRetryableFailure
        )
        assertTrue(
            "失败文案必须可读，实际: ${error.message}",
            error.message!!.contains("签名") && error.message!!.contains("不匹配")
        )
    }

    // 签名缺失（服务端没有 .sig）

    @Test
    fun missingSignatureFileIsRejectedAsNonRetryableFailure() {
        serve("/sdkconfig.json", manifestBytes())
        // 刻意不 serve /sdkconfig.json.sig → 服务端回 404

        val error = runBlocking { repository(baseUrl).fetchConfig() }.exceptionOrNull()

        assertTrue("签名文件缺失必须拒绝，实际 $error", error is KernelTrustException)
        assertTrue(error is NonRetryableFailure)
        assertTrue("文案必须点明签名缺失，实际: ${error!!.message}", error.message!!.contains("签名缺失"))
    }

    // 签名 base64 非法

    @Test
    fun nonBase64SignatureFileIsRejectedWithReadableReason() {
        serve("/sdkconfig.json", manifestBytes())
        serve("/sdkconfig.json.sig", "not-a-base64!!!".toByteArray(Charsets.US_ASCII))

        val error = runBlocking { repository(baseUrl).fetchConfig() }.exceptionOrNull()

        assertTrue(error is KernelTrustException)
        assertTrue("文案必须点明 base64 非法，实际: ${error!!.message}", error.message!!.contains("base64"))
    }

    // 公钥为 null（显式放弃验签）→ 跳过并继续

    @Test
    fun nullPublicKeySkipsSignatureEntirelyAndStillLoadsConfig() {
        serve("/sdkconfig.json", manifestBytes())
        // 没配公钥就不该去读签名文件，哪怕它完全是垃圾
        serve("/sdkconfig.json.sig", "garbage!!!".toByteArray(Charsets.US_ASCII))

        val config = runBlocking {
            ConfigRepository(KernelSource.Remote("$baseUrl/sdkconfig.json", null)).fetchConfig().getOrThrow()
        }

        assertEquals(BuildConfig.GECKOVIEW_VERSION, config.geckoJavaVersion)
    }

    // 取到配置后的 ABI / sha256 强制校验（走完整 JSON 解析路径）

    @Test
    fun blankSha256IsRejectedWithAbiInMessage() {
        val manifest = manifestBytes(sha256 = "")
        serve("/sdkconfig.json", manifest)
        serve("/sdkconfig.json.sig", sign(manifest).toByteArray(Charsets.US_ASCII))

        val error = runBlocking {
            repository(baseUrl).getDownloadInfo(BuildConfig.GECKOVIEW_VERSION, "arm64-v8a")
        }.exceptionOrNull()

        assertTrue("sha256 为空必须拒绝，实际 $error", error is KernelTrustException)
        assertTrue(error is NonRetryableFailure)
        assertTrue("文案必须带 ABI 名，实际: ${error!!.message}", error.message!!.contains("arm64-v8a"))
        assertTrue("文案必须点明 sha256，实际: ${error!!.message}", error.message!!.contains("sha256"))
    }

    @Test
    fun missingAbiIsRejectedWithAbiListInMessage() {
        val manifest = manifestBytes()
        serve("/sdkconfig.json", manifest)
        serve("/sdkconfig.json.sig", sign(manifest).toByteArray(Charsets.US_ASCII))

        val error = runBlocking {
            repository(baseUrl).getDownloadInfo(BuildConfig.GECKOVIEW_VERSION, "x86_64")
        }.exceptionOrNull()

        assertTrue("ABI 缺失必须拒绝，实际 $error", error is KernelTrustException)
        assertTrue(error is NonRetryableFailure)
        assertTrue(
            "文案必须点明缺哪个 ABI、清单里有哪些 ABI，实际: ${error!!.message}",
            error.message!!.contains("x86_64") && error.message!!.contains("arm64-v8a")
        )
    }

    // helpers

    private fun repository(baseUrl: String) =
        ConfigRepository(KernelSource.Remote("$baseUrl/sdkconfig.json", rawXY(keyPair)))

    private fun manifestBytes(sha256: String = SHA256_VALUE): ByteArray = (
        """{"geckoJavaVersion":"${BuildConfig.GECKOVIEW_VERSION}",""" +
            """"so":{"arm64-v8a":{"url":"https://example.invalid/kernel.7z","sha256":"$sha256"}}}"""
        ).toByteArray(Charsets.UTF_8)

    private fun sign(payload: ByteArray): String =
        Base64.getEncoder().encodeToString(
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
            bytes.size == 33 -> bytes.copyOfRange(1, 33)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> error("坐标超长: ${bytes.size}")
        }
    }

    private fun serve(path: String, body: ByteArray) {
        responses[path] = body
    }

    /** 最小 HTTP 桩：只认路径、只回 200/404（外加 Content-Length 与 `Connection: close`），每次响应都关连接。 */
    private class StubHttpServer(private val responses: Map<String, ByteArray>) {

        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        val port: Int get() = serverSocket.localPort

        private val thread = Thread({ serveLoop() }, "tokay-stub-http").apply {
            isDaemon = true
            start()
        }

        private fun serveLoop() {
            while (!serverSocket.isClosed) {
                try {
                    serverSocket.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "ISO-8859-1"))
                        val requestLine = reader.readLine() ?: return@use
                        val path = requestLine.split(' ').getOrNull(1) ?: "/"
                        // 请求全是 GET，没有 body；把头读完即可
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                        }

                        val body = responses[path]
                        val out = socket.getOutputStream()
                        if (body == null) {
                            out.write(
                                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.ISO_8859_1)
                            )
                        } else {
                            out.write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.ISO_8859_1)
                            )
                            out.write(body)
                        }
                        out.flush()
                    }
                } catch (_: Exception) {
                    // close() 会让 accept() 抛异常，循环条件随即结束
                }
            }
        }

        fun close() {
            runCatching { serverSocket.close() }
        }
    }

    private companion object {
        const val SHA256_VALUE = "256e036d9fe327ed43ff274bc01bcedaa749640a28190407fccecb133af84298"
    }
}
