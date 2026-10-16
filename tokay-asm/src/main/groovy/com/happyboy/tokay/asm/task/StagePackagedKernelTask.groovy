package com.happyboy.tokay.asm.task

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 把内核 7z 以 `gecko-kernel-<abiTag>.7z` 的名字摆进一个独立目录，作为变体的 generated assets 源。
 *
 * 为什么不直接把 [getPackageDir] 当 assets 源：那个目录里还有 `base.apk` 与临时结构目录，
 * 会被当成 assets 一并打进 APK。
 *
 * 命名必须与运行期 `PackagedKernelContract.assetName` 一致（跨语言，改一处必须同时改另一处）。
 */
abstract class StagePackagedKernelTask extends DefaultTask {

    @Input
    abstract Property<String> getAbiTag()

    @InputDirectory
    @PathSensitive(PathSensitivity.NAME_ONLY)
    abstract DirectoryProperty getPackageDir()

    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    @TaskAction
    void stage() {
        def abi = abiTag.get()
        def packageDir = getPackageDir().get().asFile
        def archives = (packageDir.listFiles() ?: new File[0]).findAll {
            it.isFile() && it.name.startsWith('geckoview-') && it.name.endsWith('.7z')
        }
        if (archives.size() != 1) {
            throw new GradleException(
                    "[GeckoViewASM] [packaged-kernel] ${packageDir} 下应当恰好有一个 geckoview-*.7z，" +
                            "实际 ${archives.size()} 个：${archives*.name}")
        }

        def source = archives.first()
        def outputDir = getOutputDir().get().asFile
        project.delete(outputDir)
        outputDir.mkdirs()

        def target = new File(outputDir, "gecko-kernel-${abi}.7z")
        source.withInputStream { input ->
            target.withOutputStream { output -> output << input }
        }

        logger.lifecycle(
                "[GeckoViewASM] [packaged-kernel] ${source.name} -> ${target.absolutePath}（${target.length()} 字节）")
    }
}
