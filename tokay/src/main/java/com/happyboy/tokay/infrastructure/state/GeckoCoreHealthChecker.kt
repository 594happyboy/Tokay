package com.happyboy.tokay.infrastructure.state

import android.app.Application
import android.util.Log
import com.happyboy.tokay.BuildConfig
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.GeckoSdk
import com.happyboy.tokay.infrastructure.path.GeckoKernelLayout
import java.io.File

/**
 * Gecko 内核本地健康检查：`state.json` 的 `javaApiVersion` 与当前 `BuildConfig.GECKOVIEW_VERSION`
 * 不一致即重置状态走全新安装，绝不复用旧 APK 遗留的内核；同代则只检查当前版本目录还在不在。
 *
 * 没有"代内回退到上一版"这回事：内核版本被钉死成当前 `BuildConfig.GECKOVIEW_VERSION`
 * （`ConfigRepository` 拒绝其余版本的清单），上一版不可能是另一个版本号。
 */
internal object GeckoCoreHealthChecker {

    /** 当前 Java 代无健康内核时返回 false，由上层触发安装。 */
    fun ensureHealthyForCurrentJava(application: Application): Boolean {
        val rootDir = File(application.filesDir, GeckoSdk.ROOT_DIR_NAME)
        val metaDir = File(rootDir, "meta")
        val stateStore = GeckoCoreStateStore(metaDir)
        val state = stateStore.readState()

        // 代际不同直接重置状态：旧 APK 遗留的内核不能复用
        if (state.javaApiVersion != null && state.javaApiVersion != BuildConfig.GECKOVIEW_VERSION) {
            Log.w(
                LOG_TAG,
                "检测到 Gecko 内核代际不一致，丢弃旧状态: javaVersion=${state.javaApiVersion}, current=${state.currentCoreVersion}"
            )
            stateStore.writeState {
                GeckoCoreState(
                    javaApiVersion = BuildConfig.GECKOVIEW_VERSION,
                    currentCoreVersion = null
                )
            }
            return false
        }

        if (state.javaApiVersion == null) {
            stateStore.writeState { it.copy(javaApiVersion = BuildConfig.GECKOVIEW_VERSION) }
        }

        val currentVersion = state.currentCoreVersion
        if (!currentVersion.isNullOrBlank() && isVersionHealthy(rootDir, currentVersion)) {
            return true
        }

        Log.w(LOG_TAG, "当前代无可用 Gecko 内核，待重新安装")
        stateStore.writeState { it.copy(currentCoreVersion = null) }
        return false
    }

    /**
     * 只做目录结构检查，不做完整 hash 校验。判定与安装侧**同一处**
     * （[GeckoKernelLayout.isComplete]）——两边一旦各写一套，就会出现"健康检查说不健康、安装短路说已装好"
     * 的死循环，坏掉的内核再也修不回来。
     */
    private fun isVersionHealthy(rootDir: File, version: String): Boolean {
        val libsDir = File(File(File(rootDir, "versions"), version), "libs")
        val healthy = GeckoKernelLayout.isComplete(libsDir)
        if (!healthy) {
            Log.w(
                LOG_TAG,
                "检测到 Gecko 版本目录不完整: version=$version, libs=${libsDir.absolutePath}"
            )
        }
        return healthy
    }
}
