package com.happyboy.tokay.asm

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * 随包模式的构建期开关：`-PpackageGeckoKernel=true`（默认 false）。
 *
 * 只认 `true` / `false`：写成别的值（含 `-PpackageGeckoKernel` 这种空值）直接失败——静默当成
 * 某个布尔会让「以为开了随包、其实没开」变成设备上找不到内核包。
 *
 * 运行期侧按同一个属性注入自己的 BuildConfig 布尔（见 `demo/build.gradle`），两边判的必须是同一个值。
 */
class PackagedKernelSwitch {

    static final String PROPERTY = 'packageGeckoKernel'

    static boolean isEnabled(Project project) {
        def raw = project.findProperty(PROPERTY)
        if (raw == null) return false

        def text = raw.toString().trim().toLowerCase(Locale.ROOT)
        if (text == 'true') return true
        if (text == 'false') return false

        throw new GradleException(
                "-P${PROPERTY} 只接受 true / false，实际是 '${raw}'。" +
                        "内核对随包下发请写 -P${PROPERTY}=true。")
    }
}
