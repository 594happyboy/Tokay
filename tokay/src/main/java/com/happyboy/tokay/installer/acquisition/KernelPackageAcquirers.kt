package com.happyboy.tokay.installer.acquisition

import android.content.res.AssetManager
import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.domain.models.KernelSource
import com.happyboy.tokay.domain.repository.ConfigRepository
import com.happyboy.tokay.installer.downloader.HttpDownloader
import com.happyboy.tokay.installer.validator.FileValidator

/**
 * 按 [KernelSource] 造取包实现，顺带处理「声明与包里实际内容不一致」的两种情况：
 *
 * - 声明 [KernelSource.Packaged] 却拿不到 assets 入口（没给 `AssetManager`）→ 立刻失败，
 *   不静默退化成「没装内核」；
 * - 声明 [KernelSource.Remote] 而 APK 里却带着内核包 → **按声明走远程**，只打一条 WARN：
 *   包里那份不会被使用，只是白占体积（误留就删掉，并检查构建时是不是开了 `-PpackageGeckoKernel`）。
 */
internal fun createKernelPackageAcquirer(
    kernelSource: KernelSource,
    assets: AssetManager?,
    validator: FileValidator
): KernelPackageAcquirer = when (kernelSource) {
    is KernelSource.Packaged -> PackagedKernelPackageAcquirer(
        ApkKernelAssetAccess(
            requireNotNull(assets) {
                "KernelSource.Packaged 要读 APK assets，但没拿到 AssetManager。" +
                    "请用 GeckoSdk.init(application, ...) 初始化，不要直接构造 GeckoInstaller。"
            }
        )
    )

    is KernelSource.Remote -> {
        warnStrayPackagedKernel(assets)
        RemoteKernelPackageAcquirer(
            configRepository = ConfigRepository(kernelSource),
            downloader = HttpDownloader(),
            validator = validator
        )
    }
}

private fun warnStrayPackagedKernel(assets: AssetManager?) {
    if (assets == null) return
    val stray = PackagedKernelContract.strayKernelPackages(
        ApkKernelAssetAccess(assets).listKernelPackageNames()
    )
    if (stray.isNotEmpty()) {
        Log.w(
            LOG_TAG,
            "按 Remote 模式安装，但 APK 的 assets 里带着内核包 ${stray.joinToString()}：" +
                "它们不会被使用，只是白占体积。误留就删掉，并确认构建时没开 -PpackageGeckoKernel。"
        )
    }
}
