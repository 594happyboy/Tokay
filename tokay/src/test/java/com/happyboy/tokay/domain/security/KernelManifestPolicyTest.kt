package com.happyboy.tokay.domain.security

import com.happyboy.tokay.domain.models.SoInfo
import com.happyboy.tokay.domain.policy.NonRetryableFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单准入策略的纯函数护栏：ABI/sha256 强制非空、防降级、版本比较。
 * 不依赖设备与网络，所以「ABI 缺失」「sha256 为空」「低于已安装版本」这些真机难摆的场景都能直接喂。
 */
class KernelManifestPolicyTest {

    private val validSo = SoInfo(url = "https://example.invalid/kernel.7z", sha256 = "ab".repeat(32))

    // ABI / sha256 强制非空

    @Test
    fun validEntryIsReturned() {
        val so = mapOf("arm64-v8a" to validSo)

        assertEquals(validSo, KernelManifestPolicy.requireSoInfo("arm64-v8a", so))
    }

    @Test
    fun missingAbiIsRejectedAndNamesTheAbiAndWhatIsDeclared() {
        val so = mapOf("arm64-v8a" to validSo)

        val error = runCatching { KernelManifestPolicy.requireSoInfo("x86_64", so) }.exceptionOrNull()

        assertTrue("必须是 KernelTrustException，实际 $error", error is KernelTrustException)
        assertTrue("必须不可重试", error is NonRetryableFailure)
        assertTrue("文案要点明缺哪个 ABI: ${error!!.message}", error.message!!.contains("x86_64"))
        assertTrue("文案要列出清单里有哪个 ABI: ${error.message}", error.message!!.contains("arm64-v8a"))
    }

    @Test
    fun blankUrlIsRejectedWithAbiInMessage() {
        val so = mapOf("x86_64" to SoInfo(url = "", sha256 = "ab".repeat(32)))

        val error = runCatching { KernelManifestPolicy.requireSoInfo("x86_64", so) }.exceptionOrNull()

        assertTrue(error is KernelTrustException)
        assertTrue("文案要点明 ABI: ${error!!.message}", error.message!!.contains("x86_64"))
        assertTrue("文案要点明 URL 为空: ${error.message}", error.message!!.contains("URL 为空"))
    }

    @Test
    fun nullAndBlankSha256AreBothRejectedWithAbiInMessage() {
        val cases = mapOf(
            "sha256 缺失（字段为 null）" to SoInfo(url = "https://example.invalid/kernel.7z", sha256 = null),
            "sha256 为空串" to SoInfo(url = "https://example.invalid/kernel.7z", sha256 = ""),
            "sha256 只有空白" to SoInfo(url = "https://example.invalid/kernel.7z", sha256 = "  ")
        )

        for ((caseName, soInfo) in cases) {
            val error = runCatching {
                KernelManifestPolicy.requireSoInfo("arm64-v8a", mapOf("arm64-v8a" to soInfo))
            }.exceptionOrNull()

            assertTrue("$caseName：必须拒绝，实际 $error", error is KernelTrustException)
            assertTrue("$caseName：必须不可重试", error is NonRetryableFailure)
            assertTrue("$caseName：文案要带 ABI: ${error!!.message}", error.message!!.contains("arm64-v8a"))
            assertTrue("$caseName：文案要点明 sha256: ${error.message}", error.message!!.contains("sha256"))
        }
    }

    // 防降级

    @Test
    fun lowerManifestVersionThanInstalledIsRejected() {
        val verdict = KernelManifestPolicy.checkDowngrade(
            manifestVersion = "143.0.20250908174027",
            installedVersion = "147.0.20260212191108",
            force = false
        )

        assertTrue("必须拒绝降级，实际 $verdict", verdict is DowngradeVerdict.Rejected)
        val reason = (verdict as DowngradeVerdict.Rejected).reason
        assertTrue("文案要点明两个版本: $reason", reason.contains("143.0.20250908174027") && reason.contains("147.0.20260212191108"))
        assertTrue("文案要给出强制入口: $reason", reason.contains("forceDowngrade"))
    }

    @Test
    fun sameOrHigherManifestVersionIsAllowed() {
        val installed = "147.0.20260212191108"

        assertTrue(
            KernelManifestPolicy.checkDowngrade(installed, installed, force = false) is DowngradeVerdict.Allowed
        )
        assertTrue(
            "更高的清单版本是升级，不是降级",
            KernelManifestPolicy.checkDowngrade("148.0.20270101000000", installed, force = false) is DowngradeVerdict.Allowed
        )
    }

    @Test
    fun forceAllowsDowngrade() {
        val verdict = KernelManifestPolicy.checkDowngrade(
            manifestVersion = "143.0.20250908174027",
            installedVersion = "147.0.20260212191108",
            force = true
        )

        assertTrue("显式 force 必须放行，实际 $verdict", verdict is DowngradeVerdict.Allowed)
    }

    @Test
    fun unknownInstalledVersionAllowsInstall() {
        // 首次安装：meta/state.json 里还没有 currentCoreVersion
        assertTrue(
            KernelManifestPolicy.checkDowngrade("147.0.20260212191108", null, force = false) is DowngradeVerdict.Allowed
        )
        assertTrue(
            KernelManifestPolicy.checkDowngrade("147.0.20260212191108", "  ", force = false) is DowngradeVerdict.Allowed
        )
    }

    // 版本比较（防降级的判定基础）

    @Test
    fun versionComparisonIsNumericPerSegmentNotLexicographic() {
        assertTrue("147.0.9 必须低于 147.0.10（字典序会判反）", KernelManifestPolicy.compareVersions("147.0.9", "147.0.10") < 0)
        assertTrue(KernelManifestPolicy.compareVersions("147.0.20260212191108", "143.0.20250908174027") > 0)
        assertEquals(0, KernelManifestPolicy.compareVersions("147.0.1", "147.0.1"))
        assertEquals(
            "缺位补 0：147.0 与 147.0.0 视为同一版本",
            0,
            KernelManifestPolicy.compareVersions("147.0", "147.0.0")
        )
        assertTrue(KernelManifestPolicy.compareVersions("147.0", "147.0.1") < 0)
    }
}
