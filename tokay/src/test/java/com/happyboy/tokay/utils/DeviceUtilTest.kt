package com.happyboy.tokay.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DeviceUtil.pickAbiTag] 的选择规则。真机构造不出「设备主 ABI 不在发布范围」的现场，只能在这里钉住；
 * 它决定的不是装不装得上，而是**失败发生在哪里**——清单校验里一句带 ABI 名的错，还是下载 57 MiB
 * 之后在 `dlopen` 处炸出看不出根因的错。
 */
class DeviceUtilTest {

    @Test
    fun `按系统顺序取第一个已发布内核包的 ABI`() {
        assertEquals("arm64-v8a", DeviceUtil.pickAbiTag(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("x86_64", DeviceUtil.pickAbiTag(arrayOf("x86_64", "x86")))
    }

    @Test
    fun `跳过没有内核包的 ABI 后命中已发布的那个`() {
        assertEquals("arm64-v8a", DeviceUtil.pickAbiTag(arrayOf("armeabi-v7a", "arm64-v8a")))
    }

    @Test
    fun `设备主 ABI 不在发布范围时原样返回，让清单校验去报错`() {
        assertEquals("armeabi-v7a", DeviceUtil.pickAbiTag(arrayOf("armeabi-v7a")))
        assertEquals("x86", DeviceUtil.pickAbiTag(arrayOf("x86")))
    }

    @Test
    fun `系统没报 ABI 时回退到 arm64-v8a`() {
        assertEquals("arm64-v8a", DeviceUtil.pickAbiTag(null))
        assertEquals("arm64-v8a", DeviceUtil.pickAbiTag(emptyArray()))
    }
}
