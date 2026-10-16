package com.happyboy.tokay.utils

import android.os.Build

internal object DeviceUtil {

    /** 本库发布内核包的 ABI。其它 ABI 没有对应的内核包，见 [pickAbiTag]。 */
    private val SHIPPED_ABIS = arrayOf("arm64-v8a", "x86_64")

    private const val FALLBACK_ABI = "arm64-v8a"

    /**
     * 当前设备用于定位内核的 ABI 标签。主 ABI 不在 [SHIPPED_ABIS] 时**原样返回**而不回退到
     * `arm64-v8a`：回退会下载一个装不上的内核包，真机炸成 `dlopen failed` 且根因不可见；
     * 原样返回让失败发生在清单校验里，并打出带 ABI 名的 `KernelTrustException`。
     */
    fun getDeviceAbiTag(): String = pickAbiTag(Build.SUPPORTED_ABIS)

    /**
     * [getDeviceAbiTag] 的纯函数内核：按系统上报顺序取第一个发布过的 ABI；上报为空（android.jar
     * 桩的 `returnDefaultValues` 或异常设备）时回退 [FALLBACK_ABI]。抽出来是为了能在 JVM 单测里
     * 覆盖「设备主 ABI 不在发布范围」这条真机构造不出的分支。
     */
    fun pickAbiTag(reportedAbis: Array<String>?): String {
        val reported = reportedAbis ?: return FALLBACK_ABI
        for (abi in reported) {
            if (SHIPPED_ABIS.contains(abi)) return abi
        }
        return reported.firstOrNull() ?: FALLBACK_ABI
    }
}
