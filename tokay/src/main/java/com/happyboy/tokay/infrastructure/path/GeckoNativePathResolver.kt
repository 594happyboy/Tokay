package com.happyboy.tokay.infrastructure.path

import android.content.Context
import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.GeckoSdk
import com.happyboy.tokay.infrastructure.state.GeckoCoreStateStore
import com.happyboy.tokay.utils.DeviceUtil
import java.io.File

/**
 * 统一的 Gecko native 资源路径解析工具。
 *
 * 只依赖 `Context.filesDir` + 本地 `state.json`，**不依赖 `GeckoSdk.init()`**，因此可安全地多进程
 * 静态调用（这是 ASM 插桩能调用它的前提）。优先多版本目录
 * `<filesDir>/geckoview/versions/<version>/libs/...`，找不到再回退旧结构 `<filesDir>/geckoview/libs/...`
 * 与 `packageResourcePath` / `nativeLibraryDir`。
 */
object GeckoNativePathResolver {

    /**
     * 获取主进程使用的 base.apk 路径。
     *
     * 调用方约定：ASM 插桩用它替代 `Context.getPackageResourcePath()`，让 `-greomni` 指向内核包里的
     * `res/base.apk`（APK 内不再有 `omni.ja`）。本地没装内核时回退到原路径。
     */
    @JvmStatic
    fun getMainProcessApkPath(context: Context): String {
        val rootDir = getRootDir(context)
        val currentVersion = readCurrentVersion(rootDir)

        if (!currentVersion.isNullOrBlank()) {
            val versionBaseApk = File(
                File(File(File(rootDir, "versions"), currentVersion), "libs"),
                "res/base.apk"
            )
            if (versionBaseApk.isFile) {
                Log.d(LOG_TAG, "使用多版本 base.apk: ${versionBaseApk.absolutePath}")
                return versionBaseApk.absolutePath
            }
        }

        val fallback = context.packageResourcePath
        Log.w(LOG_TAG, "未找到本地 base.apk，回退到 packageResourcePath: $fallback")
        return fallback
    }

    /**
     * 获取当前激活版本的 ABI so 目录：返回绝对路径，调用方负责在其中加载具体的 libxxx.so。
     */
    @JvmStatic
    fun getAbiLibDir(context: Context): String {
        val rootDir = getRootDir(context)
        val currentVersion = readCurrentVersion(rootDir)
        val abiTag = DeviceUtil.getDeviceAbiTag()

        if (!currentVersion.isNullOrBlank()) {
            val versionAbiDir = File(
                File(File(File(rootDir, "versions"), currentVersion), "libs/jni"),
                abiTag
            )
            if (versionAbiDir.isDirectory) {
                Log.d(LOG_TAG, "使用多版本 ABI 目录: ${versionAbiDir.absolutePath}")
                return versionAbiDir.absolutePath
            }
        }

        val legacyAbiDir = File(File(File(rootDir, "libs"), "jni"), abiTag)
        if (legacyAbiDir.isDirectory) {
            Log.d(LOG_TAG, "使用兼容 ABI 目录: ${legacyAbiDir.absolutePath}")
            return legacyAbiDir.absolutePath
        }

        // 兜底：nativeLibraryDir（不推荐，但保证不为空）
        val nativeLibDir = context.applicationInfo?.nativeLibraryDir
        if (!nativeLibDir.isNullOrBlank()) {
            Log.w(LOG_TAG, "未找到 Gecko 本地 ABI 目录，回退到 nativeLibraryDir: $nativeLibDir")
            return nativeLibDir
        }

        // 极端兜底：rootDir，后续加载必然失败，但不返回空字符串
        Log.e(LOG_TAG, "未找到任何可用 ABI 目录，使用 rootDir 兜底: ${rootDir.absolutePath}")
        return rootDir.absolutePath
    }

    private fun getRootDir(context: Context): File {
        return File(context.filesDir, GeckoSdk.ROOT_DIR_NAME)
    }

    private fun readCurrentVersion(rootDir: File): String? {
        val metaDir = File(rootDir, "meta")
        val stateStore = GeckoCoreStateStore(metaDir)
        val state = stateStore.readState()
        return state.currentCoreVersion
    }
}

