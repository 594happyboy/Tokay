package com.happyboy.tokay.engine.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LocalFileServer` 引用计数语义的回归护栏（纯 JVM）。对应真机缺陷：任一 Gecko 适配器销毁就无条件
 * 停服并清空 `/dyn`，另一个仍在显示的文档直接 404。
 */
class RefCounterTest {

    @Test
    fun twoAcquiresThenOneRelease_keepsServerAlive() {
        val counter = RefCounter()
        val first = counter.acquire()
        counter.acquire()

        // 释放一个：还剩一个持有者 → 服务不能停
        assertFalse(counter.release(first))
        assertEquals(1, counter.current())
    }

    @Test
    fun lastReleaseSignalsStop() {
        val counter = RefCounter()
        val first = counter.acquire()
        val second = counter.acquire()

        assertFalse(counter.release(first))
        // 释放到 0：最后一个持有者离开 → 服务应停
        assertTrue(counter.release(second))
        assertEquals(0, counter.current())
    }

    @Test
    fun releaseOnZeroCount_doesNotUnderflow() {
        val counter = RefCounter()

        // 计数已为 0 时释放未知令牌：不产生负数、也不表示「要停服务」
        assertFalse(counter.release(42L))
        assertEquals(0, counter.current())

        // 正常持有后再把残留令牌多释放几次，计数同样不为负
        val token = counter.acquire()
        assertTrue(counter.release(token))
        assertFalse(counter.release(token))
        assertFalse(counter.release(token))
        assertEquals(0, counter.current())
    }

    @Test
    fun repeatedReleaseBySameHolder_isIdempotent() {
        val counter = RefCounter()
        val holderA = counter.acquire()
        val holderB = counter.acquire()

        // 同一个持有者重复 release（例如 doDestroy 被调两次）不能把 B 的服务关掉
        assertFalse(counter.release(holderA))
        assertFalse(counter.release(holderA))
        assertEquals(1, counter.current())

        // B 仍然是最后一个持有者，释放它才停
        assertTrue(counter.release(holderB))
        assertEquals(0, counter.current())
    }

    @Test
    fun reset_invalidatesRemainingTokens() {
        val counter = RefCounter()
        val stale = counter.acquire()
        counter.acquire()

        counter.reset()

        assertEquals(0, counter.current())
        assertFalse(counter.release(stale))
        assertEquals(0, counter.current())
    }

    @Test
    fun acquireAfterReset_startsFromZeroAgain() {
        val counter = RefCounter()
        counter.acquire()
        counter.reset()

        val fresh = counter.acquire()
        assertEquals(1, counter.current())
        assertTrue(counter.release(fresh))
        assertEquals(0, counter.current())
    }
}
