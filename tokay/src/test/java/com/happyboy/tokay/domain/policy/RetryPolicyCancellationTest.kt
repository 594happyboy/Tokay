package com.happyboy.tokay.domain.policy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 回归护栏：`RetryPolicy.executeWithRetry` 不得把协程取消当成一次可重试的失败。
 * 取消必须原样抛出（不能收成 `Result.failure`）、不得回调 `onRetrying`、不得再调用 block；
 * `baseDelayMs = 0` 时 `delay(0)` 不检查取消，去掉那行重抛就会空转到 `maxAttempts`。
 */
class RetryPolicyCancellationTest {

    /** 只把延迟与次数调到能被单测驱动，不改被测的重试判定本身。 */
    private fun zeroDelayRetryConfig() = RetryConfig(
        enabled = true,
        maxAttempts = 3,
        baseDelayMs = 0L,
        strategy = RetryConfig.RetryStrategy.FIXED,
        maxDelayMs = 0L
    )

    @Test
    fun cancellationIsRethrownImmediatelyAndNeverCountedAsARetryableFailure() {
        val policy = RetryPolicy(zeroDelayRetryConfig())
        val cancellation = CancellationException("协程已取消：结果不再需要")
        var retryingCalls = 0
        var blockCalls = 0

        var propagated: Throwable? = null
        var returnedResult: Result<Unit>? = null
        try {
            returnedResult = runBlocking {
                policy.executeWithRetry(
                    onRetrying = { _, _, _ -> retryingCalls++ }
                ) {
                    blockCalls++
                    throw cancellation
                }
            }
        } catch (e: Throwable) {
            propagated = e
        }

        // 取消不许触发任何一次「将重试」
        assertEquals("取消不得被当成可重试的失败：onRetrying 必须一次都不调用", 0, retryingCalls)

        // 取消必须传播出来，而不是变成一次「正常的失败返回值」
        assertNull("取消不得被收成 Result.failure，必须抛出", returnedResult)
        assertSame("CancellationException 必须原样传播（同一个实例）", cancellation, propagated)

        // 取消之后不得再调用 block
        assertEquals("取消后不得再尝试下一次", 1, blockCalls)
    }
}
