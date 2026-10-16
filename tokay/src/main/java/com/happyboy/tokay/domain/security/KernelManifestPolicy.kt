package com.happyboy.tokay.domain.security

import com.happyboy.tokay.domain.models.SoInfo
import com.happyboy.tokay.domain.policy.NonRetryableFailure

/**
 * 清单/信任类失败：签名不对、缺 ABI、sha256 为空、拒绝降级。
 *
 * 这些都是服务端发布的文件内容决定的事实，重试十次还是同一份坏清单，所以标记为
 * [NonRetryableFailure]，让 `RetryPolicy` 直接放弃而不是白等一轮指数退避。
 */
class KernelTrustException(message: String, cause: Throwable? = null) :
    Exception(message, cause), NonRetryableFailure

/** 防降级判定结论；[Rejected.reason] 是给开发者看的拒绝原因。 */
sealed interface DowngradeVerdict {
    object Allowed : DowngradeVerdict
    data class Rejected(val reason: String) : DowngradeVerdict
}

/**
 * 信任链中间一环的准入判定：内置公钥验清单签名（[KernelSignatureVerifier]）→ 本对象校验
 * `so.<abi>` 与版本 → 解压后的结构校验（`FileValidator`）。
 *
 * 纯函数、无 Android 依赖，可直接在 JVM 单测里驱动。
 */
internal object KernelManifestPolicy {

    /**
     * 取指定 ABI 的内核包信息；缺哪一项就报哪一项，失败文案里必须带 ABI 名。
     *
     * `sha256` 强制非空：允许「空 sha256 = 跳过哈希校验」等于给镜像替换留一个静默后门。
     */
    fun requireSoInfo(abiTag: String, so: Map<String, SoInfo>?): SoInfo {
        val declared = so?.keys?.sorted()?.joinToString(", ").orEmpty().ifEmpty { "（清单未声明任何 ABI）" }

        val soInfo = so?.get(abiTag)
            ?: throw KernelTrustException("清单中没有 ABI=$abiTag 的内核条目；清单声明的 ABI: $declared")

        if (soInfo.url.isBlank()) {
            throw KernelTrustException("ABI=$abiTag 的内核包 URL 为空（该 ABI 尚未发布内核包）")
        }

        if (soInfo.sha256.isNullOrBlank()) {
            throw KernelTrustException(
                "ABI=$abiTag 的内核包 sha256 缺失或为空：拒绝安装无法校验完整性的内核包"
            )
        }

        return soInfo
    }

    /**
     * 拒绝安装低于已安装版本的清单，除非显式 [force]。
     *
     * [installedVersion] 必须取自 `meta/state.json`（跨进程事实），不能读 `GeckoSdk.currentState`
     * ——那只是本进程内存态，冷启动或多进程下拿不到。读不到（首次安装）时放行。
     */
    fun checkDowngrade(
        manifestVersion: String,
        installedVersion: String?,
        force: Boolean
    ): DowngradeVerdict {
        if (installedVersion.isNullOrBlank()) return DowngradeVerdict.Allowed
        if (force) return DowngradeVerdict.Allowed

        return if (compareVersions(manifestVersion, installedVersion) < 0) {
            DowngradeVerdict.Rejected(
                "拒绝降级安装：清单版本 $manifestVersion 低于已安装版本 $installedVersion" +
                    "（如确需降级，请显式传 forceDowngrade=true）"
            )
        } else {
            DowngradeVerdict.Allowed
        }
    }

    /**
     * 版本号比较（负数 = a 低于 b）。
     *
     * 形如 `147.0.20260212191108`，按 `.`/`-`/`_` 切段后逐段**按数值**比较——用字典序会让
     * `147.0.10` 低于 `147.0.9`。段数不同时缺位补 0，非数值段退回字符串比较。
     */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split('.', '-', '_')
        val right = b.split('.', '-', '_')
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i) ?: "0"
            val y = right.getOrNull(i) ?: "0"
            val xn = x.toLongOrNull()
            val yn = y.toLongOrNull()
            val c = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }
}
