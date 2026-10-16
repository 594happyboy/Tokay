package com.happyboy.tokay.installer.acquisition

import com.happyboy.tokay.domain.policy.NonRetryableFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随包模式的跨语言契约：asset 名由 ABI 决定，构建侧 `tokay-asm` 按同一命名注入。
 * 这里钉住的是**失败发生在哪里**——找不到本机 ABI 那份包时必须在取包阶段就点名报错，
 * 而不是解压出一个结构不对的目录再报一句看不懂的错。
 */
class PackagedKernelContractTest {

    @Test
    fun `ABI 映射到约定的 asset 名`() {
        assertEquals("gecko-kernel-arm64-v8a.7z", PackagedKernelContract.assetName("arm64-v8a"))
        assertEquals("gecko-kernel-x86_64.7z", PackagedKernelContract.assetName("x86_64"))
    }

    @Test
    fun `只把 gecko-kernel- 前缀且 7z 结尾的条当内核包`() {
        assertTrue(PackagedKernelContract.isKernelPackage("gecko-kernel-arm64-v8a.7z"))
        assertFalse("别的扩展名不算", PackagedKernelContract.isKernelPackage("gecko-kernel-arm64-v8a.zip"))
        assertFalse("别的前缀不算", PackagedKernelContract.isKernelPackage("omni.ja"))
        assertFalse("光有前后缀没有 ABI 不算", PackagedKernelContract.isKernelPackage("gecko-kernel-.7z"))
    }

    @Test
    fun `本机 ABI 那份在包里时返回它的名字`() {
        val present = setOf("gecko-kernel-arm64-v8a.7z", "gecko-kernel-x86_64.7z", "test")
        assertEquals(
            "gecko-kernel-arm64-v8a.7z",
            PackagedKernelContract.requireAssetName("arm64-v8a", present)
        )
    }

    @Test
    fun `本机 ABI 那份不在包里时响亮失败，且不可重试`() {
        val error = assertThrows(PackagedKernelAssetMissingException::class.java) {
            PackagedKernelContract.requireAssetName(
                abiTag = "arm64-v8a",
                presentNames = setOf("gecko-kernel-x86_64.7z", "omni.ja")
            )
        }

        assertTrue("缺 asset 是确定性失败，必须绕过重试", error is NonRetryableFailure)
        val message = error.message!!
        assertTrue("要点名期望的 asset 名: $message", message.contains("assets/gecko-kernel-arm64-v8a.7z"))
        assertTrue("要点名本机 ABI: $message", message.contains("arm64-v8a"))
        assertTrue("要点名构建期开关: $message", message.contains("-PpackageGeckoKernel"))
        assertTrue("要提示检查 abiFilters: $message", message.contains("abiFilters"))
        assertTrue("要点出包里现有的内核包: $message", message.contains("gecko-kernel-x86_64.7z"))
    }

    @Test
    fun `包里一个内核包都没有时文案也要说得清`() {
        val error = assertThrows(PackagedKernelAssetMissingException::class.java) {
            PackagedKernelContract.requireAssetName("x86_64", setOf("test", "runtime_extensions"))
        }
        assertTrue(
            "要明确说「一个都没有」: ${error.message}",
            error.message!!.contains("一个都没有")
        )
    }

    @Test
    fun `Remote 模式下多带的内核包会被列出来（按体积白占处理）`() {
        assertEquals(
            listOf("gecko-kernel-arm64-v8a.7z", "gecko-kernel-x86_64.7z"),
            PackagedKernelContract.strayKernelPackages(
                setOf("gecko-kernel-x86_64.7z", "omni.ja", "test", "gecko-kernel-arm64-v8a.7z")
            )
        )
    }

    @Test
    fun `Remote 模式下没多带内核包时列表为空`() {
        assertTrue(PackagedKernelContract.strayKernelPackages(setOf("omni.ja", "test")).isEmpty())
    }
}
