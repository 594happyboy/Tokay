package com.happyboy.tokay.domain.policy

import com.happyboy.tokay.domain.security.KernelTrustException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `NonRetryableFailure` 的语义护栏：同一套配置下普通 `IOException` 必须重试满 `maxAttempts`，
 * 而 `KernelTrustException` 一次都不重试。只断言「不可重试不重试」是空转的——把重试功能整个
 * 删掉它照样绿。
 */
class RetryPolicyNonRetryableTest {

    /** 延迟设 0：这里要验的是「重不重试」，不是退避曲线。 */
    private fun policy(maxAttempts: Int) = RetryPolicy(
        RetryConfig(
            enabled = true,
            maxAttempts = maxAttempts,
            baseDelayMs = 0L,
            strategy = RetryConfig.RetryStrategy.FIXED,
            maxDelayMs = 0L
        )
    )

    @Test
    fun nonRetryableFailureGivesUpImmediatelyWithoutOnRetrying() {
        val maxAttempts = 5
        var calls = 0
        var retryCallbacks = 0

        val result = runBlocking {
            policy(maxAttempts).executeWithRetry(
                onRetrying = { _, _, _ -> retryCallbacks++ }
            ) { _ ->
                calls++
                throw KernelTrustException("清单签名不对")
            }
        }

        assertTrue("必须收成 Result.failure（而不是把异常抛穿）", result.isFailure)
        assertEquals("清单类失败只允许尝试一次", 1, calls)
        assertEquals("不可重试时 onRetrying 一次都不许调用（否则会发出 Retrying 状态）", 0, retryCallbacks)
        assertTrue(result.exceptionOrNull() is KernelTrustException)
    }

    @Test
    fun ordinaryIoFailureStillRetriesUpToMaxAttempts() {
        val maxAttempts = 5
        var calls = 0
        var retryCallbacks = 0

        val result = runBlocking {
            policy(maxAttempts).executeWithRetry(
                onRetrying = { _, _, _ -> retryCallbacks++ }
            ) { _ ->
                calls++
                throw IOException("网络断了")
            }
        }

        assertTrue(result.isFailure)
        assertEquals("可重试失败必须尝试 maxAttempts + 1 次", maxAttempts + 1, calls)
        assertEquals("每次重试都要回调 onRetrying", maxAttempts, retryCallbacks)
    }
}
