package com.happyboy.tokay.domain.models

import com.happyboy.tokay.infrastructure.config.SdkConstants

/**
 * 内核 7z 的来源，同时也是信任根：Remote 走 OTA 清单那条链（验 `.sig` → 校 sha256），
 * Packaged 直接信任 APK 签名（包就在已签名的 APK 里）。
 *
 * 两种来源只在「字节从哪来」这一步不同；取到本地文件之后的解压、结构校验、落盘、`state.json`
 * 完全合流。是**运行期显式声明**而不是自动探测：探测会在「声明 Remote 而包里恰好带了内核包」时
 * 悄悄换掉信任根。
 */
sealed interface KernelSource {

    /**
     * 内核包随 APK：首次运行从 `assets/gecko-kernel-<abiTag>.7z` 解压。没有清单地址、不验 `.sig`、
     * 不算 sha256，也没有地址可配。
     */
    object Packaged : KernelSource

    /**
     * 运行时 OTA 下发；清单的分离式签名文件是 `<configUrl>` 的同名 `.sig`。
     * @param publicKeyP256 裸 X/Y（64 字节 = X(32)||Y(32)，大端，不含 `0x04` 前缀，可直接重建
     *        `ECPublicKeySpec`）。`null` = 不验签（信任根只剩 TLS，安装打 WARN），非空 = 强制验签且不降级。
     */
    class Remote(
        val configUrl: String,
        val publicKeyP256: ByteArray?
    ) : KernelSource

    companion object {

        /**
         * 演示公钥 X。配对的私钥随仓库公开（`tools/tokay-sign/keys/demo-p256.key`），
         * 任何人都能用它伪造内核，**生产必须替换**。
         */
        const val DEMO_P256_X_HEX = "9339f5aa1f4ebc17b16f9ac2c1a0688448d09fb16df326029a0a7335ab71c5ea"

        /** 演示公钥 Y（同上，生产必须替换）。 */
        const val DEMO_P256_Y_HEX = "cb486af92ce5e7d803c6621e1c9109c059c2d6cdb4145a6fe6f99b5fdb16b7cd"

        /** 内置演示公钥的裸 X/Y（64 字节）。 */
        val DEMO_P256_XY: ByteArray = hexToBytes(DEMO_P256_X_HEX) + hexToBytes(DEMO_P256_Y_HEX)

        /**
         * 默认信任源：构建期注入的清单地址（`-PsdkConfigUrl` 可覆盖）+ 内置演示公钥。
         * 传 `KernelSource.Remote(url, null)` 即不验签，安装时打 WARN。
         */
        val DEFAULT: Remote
            get() = Remote(SdkConstants.SDK_CONFIG_URL, DEMO_P256_XY)

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "十六进制字符串长度必须是偶数: ${hex.length}" }
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}

/**
 * 接入方是否给了能用的来源。地址为空 = 忘了配：在这里立刻失败，而不是等到下载阶段报「装不上内核」——
 * 后者会把「你没配地址」误读成「网络/签名/服务端坏了」。随包模式没有地址可配，也就没有这条要求。
 */
internal fun KernelSource.requireUsable() {
    if (this is KernelSource.Remote) {
        check(configUrl.isNotBlank()) {
            "内核清单地址为空：请用 -PsdkConfigUrl=<url> 构建，或显式传 " +
                "GeckoSdk.init(kernelSource = KernelSource.Remote(configUrl = ...))；" +
                "内核随 APK 下发则传 KernelSource.Packaged。" +
                "本库刻意不提供默认地址——内核来源与验签公钥属于接入方的信任决策。"
        }
    }
}
