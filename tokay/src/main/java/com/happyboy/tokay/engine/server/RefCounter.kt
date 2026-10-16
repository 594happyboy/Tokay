package com.happyboy.tokay.engine.server

/**
 * 本地服务的持有者计数（纯 Kotlin，可 JVM 单测）。
 * **令牌式**：`acquire()` 返回令牌，`release(token)` 只认自己发过的令牌——重复销毁是 no-op，
 * 未知令牌一律忽略、计数永不为负；`release` 的返回值即「服务此刻该不该停」。
 */
internal class RefCounter {

    private val holders = LinkedHashSet<Long>()
    private var nextToken = 0L

    @Synchronized
    fun acquire(): Long = (++nextToken).also { holders.add(it) }

    /** 释放令牌对应的持有；未知令牌返回 false 且不改动计数。 */
    @Synchronized
    fun release(token: Long): Boolean {
        if (!holders.remove(token)) return false
        return holders.isEmpty()
    }

    @Synchronized
    fun current(): Int = holders.size

    /** 计数归零（强制停机后调用，让残留令牌失效）。 */
    @Synchronized
    fun reset() {
        holders.clear()
    }
}
