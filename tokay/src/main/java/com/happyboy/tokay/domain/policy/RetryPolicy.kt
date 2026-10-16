package com.happyboy.tokay.domain.policy

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

class RetryPolicy(private val config: RetryConfig) {
    
    fun calculateDelay(attemptNumber: Int): Long {
        if (!config.enabled) return 0L
        
        val delay = when (config.strategy) {
            RetryConfig.RetryStrategy.FIXED -> {
                config.baseDelayMs
            }
            
            RetryConfig.RetryStrategy.LINEAR -> {
                config.baseDelayMs * attemptNumber
            }
            
            RetryConfig.RetryStrategy.EXPONENTIAL_BACKOFF -> {
                val exponentialDelay = (2.0.pow(attemptNumber - 1) * config.baseDelayMs).toLong()
                exponentialDelay
            }
        }
        
        return min(delay, config.maxDelayMs)
    }
    
    fun shouldRetry(attemptNumber: Int): Boolean {
        return config.enabled && attemptNumber <= config.maxAttempts
    }
    
    /**
     * 取消必须立即传播：`CancellationException` 不是「这次尝试失败」，不得退避重试，
     * 也不该被收进 `Result.failure`——否则 `baseDelayMs = 0` 时会空转到 `maxAttempts`。
     * @param block 入参为尝试序号（从 1 开始）
     */
    suspend fun <T> executeWithRetry(
        onRetrying: ((attemptNumber: Int, delay: Long, lastError: Throwable) -> Unit)? = null,
        block: suspend (attemptNumber: Int) -> T
    ): Result<T> {
        var attemptNumber = 1
        
        while (true) {
            try {
                val result = block(attemptNumber)
                return Result.success(result)
            } catch (e: Exception) {
                // 取消立即传播：语义上不是失败，且延迟为 0 时 delay(0) 不检查取消会空转到 maxAttempts。
                if (e is CancellationException) throw e

                // 再试也不会变的失败（签名/ABI/sha256/防降级）直接放弃：不退避、不回调、不产生 Retrying。
                if (e is NonRetryableFailure) {
                    Log.w(LOG_TAG, "不可重试的失败，直接放弃: ${e.message}")
                    return Result.failure(e)
                }

                if (!shouldRetry(attemptNumber)) {
                    Log.w(LOG_TAG, "重试次数已用尽 ($attemptNumber 次)")
                    return Result.failure(e)
                }
                
                val delay = calculateDelay(attemptNumber)
                Log.d(LOG_TAG, "操作失败，将在 ${delay}ms 后重试 (${attemptNumber}/${config.maxAttempts})")
                
                onRetrying?.invoke(attemptNumber, delay, e)
                
                delay(delay)
                attemptNumber++
            }
        }
    }
}

