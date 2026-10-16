package com.happyboy.tokay.compat

import com.happyboy.tokay.engine.webview.HybridWebViewError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 二进制兼容护栏：对外回调接口的「默认体方法」在 JVM 上必须真的是默认方法。默认编译配置下其字节码
 * 仍是 `public abstract`、默认体被搬到 `DefaultImpls` 静态桥，未重编的实现类（增量构建不重编源码
 * 未变的类，消费者升级 AAR 同理）会在回调点抛 `AbstractMethodError`；去掉 `-Xjvm-default=all` 即红。
 */
class InterfaceDefaultMethodBinaryCompatTest {

    private data class CallbackTarget(val binaryName: String, val methodName: String)

    private val targets = listOf(
        CallbackTarget("com.happyboy.tokay.engine.webview.HybridWebView\$LoadListener", "onLoadError")
    )

    /**
     * 核心断言：默认体方法不能是 abstract。用 `initialize = false` 加载，避免为查修饰符而触发类初始化。
     */
    @Test
    fun defaultBodyCallbacksAreRealJvmDefaultMethods() {
        for (target in targets) {
            val type = Class.forName(target.binaryName, false, javaClass.classLoader)
            assertTrue("${target.binaryName} 应当是接口", type.isInterface)

            val method = type.getDeclaredMethod(target.methodName, HybridWebViewError::class.java)
            assertNotNull("${target.binaryName}.${target.methodName} 必须存在", method)

            assertFalse(
                "${target.binaryName}.${target.methodName} 在 JVM 上仍是 abstract —— " +
                    "说明默认体被编译成了 DefaultImpls 静态桥，任何未重编的实现类都会崩在 " +
                    "AbstractMethodError。请确认 :tokay 的 kotlinOptions.freeCompilerArgs " +
                    "里有 '-Xjvm-default=all'（AGENTS.md §2.2）",
                Modifier.isAbstract(method.modifiers)
            )
        }
    }

    /**
     * `-Xjvm-default=all` 会把 `DefaultImpls` 整体去掉；断言它不存在于本模块刚编出的字节码里，
     * 而不是去扫源码。
     */
    @Test
    fun defaultImplsBridgesAreGoneFromTheCompiledArtifacts() {
        for (target in targets) {
            val bridge = "${target.binaryName}\$DefaultImpls"
            val found = try {
                Class.forName(bridge, false, javaClass.classLoader)
                true
            } catch (e: ClassNotFoundException) {
                false
            }
            assertFalse(
                "$bridge 仍然存在于编译产物里 —— 说明 -Xjvm-default 没生效（或不是 'all'）；" +
                    "本批次的验收要求 DefaultImpls 在 build/ 下消失",
                found
            )
        }
    }
}
