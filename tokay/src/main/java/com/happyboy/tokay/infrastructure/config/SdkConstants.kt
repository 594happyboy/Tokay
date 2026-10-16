package com.happyboy.tokay.infrastructure.config

import com.happyboy.tokay.BuildConfig

/**
 * 模块内常量：内核清单地址 + 本地资源域的路径常量。
 * `internal` 是刻意的：对外入口（`GeckoSdk` / `HybridWebView`）不暴露它们。
 */
internal object SdkConstants {

    /**
     * 内核清单（sdkconfig.json）的地址，构建期注入（`tokay/build.gradle` 的 `SDK_CONFIG_URL`），
     * **没有默认值**：本地联调用 `-PsdkConfigUrl=http://127.0.0.1:28080/sdkconfig.json` 覆盖，
     * 生产环境由接入方自己给；空值会在 `GeckoSdk.init` 立刻失败。
     */
    val SDK_CONFIG_URL: String = BuildConfig.SDK_CONFIG_URL

    const val ASSET_LOADER_HOST = "appassets.androidplatform.net"
    const val ASSET_LOADER_BASE_URL = "https://$ASSET_LOADER_HOST"
    const val ANDROID_ASSET_PATH = "file://android_asset"
}
