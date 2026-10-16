package com.happyboy.tokay.installer.acquisition

import android.content.res.AssetManager
import com.happyboy.tokay.domain.policy.NonRetryableFailure
import java.io.File
import java.io.InputStream

/**
 * 随包模式的**跨语言契约**：APK assets 根目录下的 `gecko-kernel-<abiTag>.7z`。
 *
 * 构建侧由 `tokay-asm` 的 `GeckoViewInstrumentPlugin` 按同一命名注入，两边是 Groovy 与 Kotlin，
 * 无法共享常量——**改命名必须两边同时改**。
 *
 * `<abiTag>` 与 [com.happyboy.tokay.utils.DeviceUtil.getDeviceAbiTag] 的取值一致
 * （`arm64-v8a` / `x86_64`）：运行期只找本机 ABI 的那一份。
 */
internal object PackagedKernelContract {

    private const val PREFIX = "gecko-kernel-"
    private const val SUFFIX = ".7z"

    /** 本机 ABI 对应的 asset 名。 */
    fun assetName(abiTag: String): String = "$PREFIX$abiTag$SUFFIX"

    /** 是不是内核包（用于在 assets 列表里挑出多余的那份，见 [strayKernelPackages]）。 */
    fun isKernelPackage(name: String): Boolean =
        name.length > PREFIX.length + SUFFIX.length &&
            name.startsWith(PREFIX) &&
            name.endsWith(SUFFIX)

    /**
     * 随包模式下要用的 asset 名；本机 ABI 那份不在 [presentNames] 里就**响亮失败**。
     *
     * 这是构建期开关 / `ndk.abiFilters` 配错的确定性错误：同一份 APK 重试多少次都还是缺，
     * 所以带上 [NonRetryableFailure]（不退避、不重试）。
     */
    fun requireAssetName(abiTag: String, presentNames: Set<String>): String {
        val expected = assetName(abiTag)
        if (expected !in presentNames) {
            val present = presentNames.filter { isKernelPackage(it) }.sorted()
            throw PackagedKernelAssetMissingException(
                "APK 里没有本机 ABI 的内核包：期望 assets/$expected，本机 ABI=$abiTag，" +
                    "包里现有的内核包=${if (present.isEmpty()) "（一个都没有）" else present.joinToString()}。" +
                    "请检查构建：随包模式要用 -PpackageGeckoKernel=true 构建，" +
                    "并在该变体的 ndk.abiFilters 里包含 $abiTag。"
            )
        }
        return expected
    }

    /** Remote 模式下 APK 里多带的内核包：按声明仍走远程，这些条只是白占体积（误留就删掉）。 */
    fun strayKernelPackages(presentNames: Set<String>): List<String> =
        presentNames.filter { isKernelPackage(it) }.sorted()
}

/** 随包模式下 APK 里缺本机 ABI 的内核包：构建期配错导致，重试没有意义。 */
internal class PackagedKernelAssetMissingException(message: String) :
    Exception(message), NonRetryableFailure

/**
 * APK assets 的读取入口。抽成接口是为了让「本机 ABI 那份在不在」「声明 Remote 却带了内核包」这类
 * 判定能进 JVM 单测——`AssetManager` 只在真机/模拟器上有实例。
 */
internal interface KernelAssetAccess {

    /** assets 根目录下的内核包条名（只看这一层，不递归子目录）。 */
    fun listKernelPackageNames(): Set<String>

    /** 打开该 asset。不存在就抛，**不返回 null**：静默给一个空流会让失败推到解压阶段且看不出根因。 */
    fun open(assetName: String): InputStream
}

/** [KernelAssetAccess] 的真机实现。 */
internal class ApkKernelAssetAccess(private val assets: AssetManager) : KernelAssetAccess {

    override fun listKernelPackageNames(): Set<String> =
        (assets.list("") ?: emptyArray())
            .filter { PackagedKernelContract.isKernelPackage(it) }
            .toSet()

    override fun open(assetName: String): InputStream = assets.open(assetName)
}

/**
 * 把 asset 字节**原样**写到 [target]（不解码、不转码），返回写入的字节数。
 *
 * 单独抽出来是为了能在 JVM 单测里用真实磁盘核对「落盘字节与包里那份一模一样」——
 * `ApkKernelAssetAccess` 本身只有真机跑得起来。
 */
internal fun copyAssetToFile(input: InputStream, target: File): Long {
    target.parentFile?.mkdirs()
    var written = 0L
    target.outputStream().use { output ->
        input.use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
            }
        }
    }
    return written
}
