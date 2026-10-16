package com.happyboy.tokay.core

import com.happyboy.tokay.domain.models.InstallState
import com.happyboy.tokay.domain.models.KernelSource
import com.happyboy.tokay.domain.policy.RetryConfig
import com.happyboy.tokay.domain.security.KernelTrustException
import com.happyboy.tokay.infrastructure.state.GeckoCoreStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 信任链失败在**真实安装编排**里的行为：清单类失败必须绕开重试，且 `Failed.phase` 是 CHECKING
 * （签名在「取下载配置」这一步校验，此时还没有任何下载状态；对照：sha256 不匹配报 DOWNLOADING）。
 * 用真实 orchestrator + 真实 state store，只把清单地址指向连不上的回环端口，全程不触网。
 */
class KernelInstallSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val newVersion = "147.0.20260212191108"
    private val installedHigherVersion = "148.0.20270101000000"
    private val abi = "arm64-v8a"

    /** 清单地址刻意不可达：本组用例的失败必须发生在取配置**之前**。 */
    private val unreachableSource = KernelSource.Remote("http://127.0.0.1:1/sdkconfig.json", null)

    private fun retryConfig(maxAttempts: Int) = RetryConfig(
        enabled = true,
        maxAttempts = maxAttempts,
        baseDelayMs = 0L,
        strategy = RetryConfig.RetryStrategy.FIXED,
        maxDelayMs = 0L
    )

    /** 预写「已安装一个更高版本」的跨进程事实（meta/state.json）。 */
    private fun markInstalled(version: String) {
        GeckoCoreStateStore(java.io.File(tempFolder.root, "meta")).updateOnInstallSuccess(version)
    }

    private fun runInstallAndRecord(
        forceDowngrade: Boolean,
        maxAttempts: Int = 3
    ): List<InstallState> = runBlocking {
        val installer = GeckoInstaller(
            version = newVersion,
            abi = abi,
            rootDir = tempFolder.root,
            retryConfig = retryConfig(maxAttempts),
            kernelSource = unreachableSource,
            forceDowngrade = forceDowngrade
        )
        val recorded = mutableListOf<InstallState>()
        val job = launch(Dispatchers.Unconfined) { installer.installState.collect { recorded += it } }
        installer.installWithRetry(enableRetry = true)
        job.cancel()
        recorded.toList()
    }

    @Test
    fun downgradeIsRejectedAndBypassesRetryEntirely() {
        markInstalled(installedHigherVersion)

        val states = runInstallAndRecord(forceDowngrade = false, maxAttempts = 3)
        val failed = states.filterIsInstance<InstallState.Failed>()

        assertEquals("必须恰好失败一次", 1, failed.size)
        val error = failed.single().error
        assertTrue("必须是信任类失败，实际 $error", error is KernelTrustException)
        assertTrue("文案必须是「拒绝降级」而不是网络错误: ${error.message}", error.message!!.contains("拒绝降级"))
        assertTrue("文案要带已安装版本: ${error.message}", error.message!!.contains(installedHigherVersion))

        // 核心断言：不可重试的失败**一次都不许重试**
        assertEquals(
            "清单类失败必须绕过重试（否则会白退避 3 次）",
            0,
            states.filterIsInstance<InstallState.Retrying>().size
        )
        assertEquals("失败阶段必须是真实阶段 CHECKING", InstallState.Phase.CHECKING, failed.single().phase)
    }

    @Test
    fun forceDowngradeSkipsTheGuardAndProceedsToConfigFetch() {
        markInstalled(installedHigherVersion)

        val states = runInstallAndRecord(forceDowngrade = true, maxAttempts = 1)
        val failed = states.filterIsInstance<InstallState.Failed>()

        assertEquals(1, failed.size)
        val error = failed.single().error
        assertTrue(
            "显式 force 之后不该再报「拒绝降级」，实际: ${error.message}",
            error !is KernelTrustException || !error.message!!.contains("拒绝降级")
        )
    }

    @Test
    fun downgradeCheckReadsInstalledVersionFromStateFileNotMemory() {
        // 反向对照：state.json 没有已安装版本时同一个清单必须被放行到「取配置」，错误于是变成网络错误
        val states = runInstallAndRecord(forceDowngrade = false, maxAttempts = 1)

        val error = states.filterIsInstance<InstallState.Failed>().single().error
        assertTrue(
            "没有已安装版本时必须放行（错误应来自网络，而不是防降级）: ${error.message}",
            error !is KernelTrustException || !error.message!!.contains("拒绝降级")
        )
    }
}
