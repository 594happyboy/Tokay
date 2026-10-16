package com.happyboy.tokay.domain.policy

/**
 * 标记「下一次尝试不可能变好」的失败（签名验不过、清单缺 ABI/sha256——由服务端发布的内容决定）。
 * [RetryPolicy.executeWithRetry] 见到实现会立即放弃：不退避、不回调 `onRetrying`、不产生 `Retrying`。
 * 默认仍是可重试，只有显式标记的异常才绕过重试。
 */
interface NonRetryableFailure
