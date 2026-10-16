package com.happyboy.tokay.domain.models

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [requireUsable] 的两种来源差异：只有 Remote 才有「清单地址」这个概念，地址为空是接入方漏配，
 * 必须在 `GeckoSdk.init` 就响亮失败；Packaged 没有地址可配，不能被这条检查挡下来。
 */
class KernelSourceTest {

    @Test
    fun `随包模式没有清单地址，不触发地址检查`() {
        KernelSource.Packaged.requireUsable()
    }

    @Test
    fun `远程模式地址为空时立刻失败，且文案要点名开关与随包选项`() {
        try {
            KernelSource.Remote("", null).requireUsable()
            fail("地址为空必须失败")
        } catch (e: IllegalStateException) {
            val message = e.message!!
            assertTrue("文案要给出构建期开关: $message", message.contains("-PsdkConfigUrl"))
            assertTrue("文案要提到随包选项: $message", message.contains("KernelSource.Packaged"))
        }
    }

    @Test
    fun `远程模式给了地址就放行`() {
        KernelSource.Remote("http://127.0.0.1:28080/sdkconfig.json", null).requireUsable()
    }

    @Test
    fun `DEFAULT 仍是带内置演示公钥的 Remote`() {
        val source: KernelSource = KernelSource.DEFAULT
        assertTrue("DEFAULT 必须还是远程模式", source is KernelSource.Remote)
        assertNotNull("DEFAULT 必须带内置演示公钥", KernelSource.DEFAULT.publicKeyP256)
    }
}
