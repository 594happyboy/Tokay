package com.happyboy.tokay.domain.policy

/** 重试配置；`maxDelayMs` 给指数退避封顶。 */
data class RetryConfig(
    val enabled: Boolean = true,

    val maxAttempts: Int = 10,

    val baseDelayMs: Long = 2000L,

    val strategy: RetryStrategy = RetryStrategy.EXPONENTIAL_BACKOFF,

    val maxDelayMs: Long = 60_000L
) {
    enum class RetryStrategy {
        FIXED,

        LINEAR,

        EXPONENTIAL_BACKOFF
    }
    
    companion object {
        val DEFAULT = RetryConfig()
    }
}

