package com.happyboy.tokay.asm

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

import com.happyboy.tokay.asm.asm.GeckoThreadVisitorFactory
import com.happyboy.tokay.asm.asm.GeckoLoaderVisitorFactory
import com.happyboy.tokay.asm.scanner.AarScanner
import com.happyboy.tokay.asm.cleaner.AssetsCleaner
import com.happyboy.tokay.asm.cleaner.GeckoSoExcluder
import com.happyboy.tokay.asm.task.GeckoArchPackageTask
import com.happyboy.tokay.asm.task.StagePackagedKernelTask
import com.happyboy.tokay.asm.verify.VerifyGeckoviewInstrumentationTargetsTask

/**
 * GeckoView 构建插件：对 GeckoLoader / GeckoThread 做 ASM 插桩；按配置剔除 GeckoView AAR 的
 * assets 与 native 库；按 CPU 架构注册内核 7z 打包任务；随包模式（`-PpackageGeckoKernel=true`）
 * 把内核包注入 APK assets；注册构建期插桩断言。仅对 Android Application 项目生效，支持 AGP 8.x。
 */
class GeckoViewInstrumentPlugin implements Plugin<Project> {

    /** 随包模式支持的 ABI；运行期只认这几份，加 ABI 需与 `tokay` 的 `DeviceUtil.SHIPPED_ABIS` 同步。 */
    private static final List<String> SUPPORTED_ARCHS = ['arm64-v8a', 'x86_64']

    @Override
    void apply(Project project) {
        project.plugins.withId('com.android.application') {
            if (PackagedKernelSwitch.isEnabled(project)) {
                // 7z 已经压过一遍，让 AGP 再 deflate 只是白花打包时间（体积几乎不变）
                def androidResources = project.extensions.findByName('android')?.androidResources
                androidResources?.noCompress?.add('7z')
            }

            configureAndroidComponents(project)

            registerArchitectureSpecificTasks(project)

            registerInstrumentationVerificationTask(project)

            // 为每个变体在任务阶段解析 geckoview 依赖，并注册 assets 与 so 的清理
            def androidExt = project.extensions.findByName('android')
            if (androidExt != null && androidExt.hasProperty('applicationVariants')) {
                androidExt.applicationVariants.all { variant ->
                    def variantName = variant.name
                    def configurationName = variant.runtimeConfiguration?.name ?: "${variantName}RuntimeClasspath"
                    def abiMapProvider = project.providers.provider {
                        AarScanner.collectAbiMapFromConfiguration(project, configurationName, Constants.GECKO_GROUP, Constants.GECKO_MODULE)
                    }
                    def assetsProvider = project.providers.provider {
                        AarScanner.collectAssetsFromConfiguration(project, configurationName, Constants.GECKO_GROUP, Constants.GECKO_MODULE)
                    }
                    project.afterEvaluate {
                        def abiMap = abiMapProvider.get()
                        // 待清理清单 = GeckoView AAR 自己的 assets。扫描为空即配置/坐标出了问题，
                        // 交给 AssetsCleaner 响亮报错（不在这里静默兜底）。
                        Set<String> aarAssets = assetsProvider.get()

                        AssetsCleaner.removeSelectedAssetsForVariant(project, variantName, aarAssets)
                        GeckoSoExcluder.removeSelectedSoOnMergeNativeLibs(project, variantName, abiMap)
                    }
                }
            }
        }
    }

    private void configureAndroidComponents(Project project) {
        def androidComponents = project.extensions.findByName('androidComponents')
        if (androidComponents == null) return

        // AGP 8.x 需要用 selector 指定范围
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            // 延迟加载类，保证能找到 AGP API
            def instrumentationScopeClass = Class.forName(
                    'com.android.build.api.instrumentation.InstrumentationScope',
                    true,
                    variant.class.classLoader
            )
            def framesModeClass = Class.forName(
                    'com.android.build.api.instrumentation.FramesComputationMode',
                    true,
                    variant.class.classLoader
            )

            // 需要修改远程依赖中的 GeckoThread，强制使用 ALL 作用域
            def scope = instrumentationScopeClass.ALL

            variant.instrumentation.transformClassesWith(
                    GeckoThreadVisitorFactory,
                    scope
            ) {}
            
            variant.instrumentation.transformClassesWith(
                    GeckoLoaderVisitorFactory,
                    scope
            ) {}

            variant.instrumentation.setAsmFramesComputationMode(
                    framesModeClass.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
            )

            if (PackagedKernelSwitch.isEnabled(project)) {
                wirePackagedKernelAssets(project, variant)
            }
        }
    }

    private void registerArchitectureSpecificTasks(Project project) {
        def tokayProject = project.rootProject.findProject(':tokay')
        if (tokayProject == null) {
            throw new GradleException("[GeckoViewASM] 找不到 :tokay 模块，无法定位 runtime_extensions（它要被打进包内 base.apk）")
        }

        SUPPORTED_ARCHS.each { arch ->
            project.tasks.register("packageGecko${arch.replaceAll('-', '').capitalize()}", GeckoArchPackageTask) {
                description = "Package Gecko dependencies for ${arch} architecture"
                getArchitecture().set(arch)
                // 声明为输入：改了 bridge JS 必须重打包，否则设备上跑的还是旧脚本（见任务里的说明）
                getRuntimeExtensionsDir().set(
                        new File(tokayProject.projectDir, 'src/main/assets/runtime_extensions'))
                outputDir.set(project.layout.buildDirectory.dir("gecko-package/${arch}"))
            }
        }
    }

    /**
     * 把该变体 ABI 的内核包作为 generated assets 注入。asset 名与运行期契约一致
     * （`gecko-kernel-<abi>.7z`），两边是 Groovy 与 Kotlin，无法共享常量。
     */
    private void wirePackagedKernelAssets(Project project, def variant) {
        def androidExt = project.extensions.findByName('android')
        List<String> abis = resolvePackagedKernelAbis(androidExt, variant)
        def assets = variant.sources.assets
        if (assets == null) {
            throw new GradleException("[GeckoViewASM] 变体 ${variant.name} 没有 assets 源，无法注入内核包")
        }

        abis.each { abi ->
            def packageTask = project.tasks.named("packageGecko${abi.replaceAll('-', '').capitalize()}")
            def stageTask = project.tasks.register(
                    "stage${variant.name.capitalize()}Kernel${abi.replaceAll('-', '').capitalize()}",
                    StagePackagedKernelTask) { t ->
                t.abiTag.set(abi)
                // flatMap 既取到输出目录，也带上「先打包」的任务依赖
                t.packageDir.set(packageTask.flatMap { it.outputDir })
            }
            assets.addGeneratedSourceDirectory(stageTask) { t -> t.outputDir }
        }
        project.logger.lifecycle("[GeckoViewASM] [packaged-kernel] 变体 ${variant.name} 注入 ABI=${abis}")
    }

    /**
     * 该变体实际要打进 APK 的 ABI 集合 = defaultConfig + 该变体的 flavor + 该变体的 buildType 上的
     * `ndk.abiFilters`（与 AGP 的合并语义一致）。取不到就响亮报错，不做「全都塞进去」的兜底——
     * 那会让 APK 比「内核留在 `lib/` 里」还大。
     */
    private static List<String> resolvePackagedKernelAbis(def androidExt, def variant) {
        if (androidExt?.splits?.abi?.enable) {
            throw new GradleException(
                    "[GeckoViewASM] 随包模式（-P${PackagedKernelSwitch.PROPERTY}=true）不支持 ABI splits：\n" +
                            "  assets 是变体级的、每个变体只合并一次，拆出来的多个 split APK 会各带所有 ABI 的内核包。\n" +
                            "  请改用 defaultConfig { ndk { abiFilters '<abi>' } }。")
        }

        Set<String> abis = new LinkedHashSet<String>()
        def collect = { cfg -> cfg?.ndk?.abiFilters?.each { abis << it.toString() } }
        collect(androidExt?.defaultConfig)
        try {
            variant.productFlavors?.each { pair -> collect(androidExt?.productFlavors?.findByName(pair.getSecond())) }
        } catch (Throwable ignored) {
            // 取不到 flavor 信息时只按 defaultConfig / buildType 算；空集会被下面的检查兜住
        }
        collect(androidExt?.buildTypes?.findByName(variant.buildType))

        if (abis.isEmpty()) {
            throw new GradleException(
                    "[GeckoViewASM] 随包模式（-P${PackagedKernelSwitch.PROPERTY}=true）要求该变体声明 ndk.abiFilters。\n" +
                            "  内核包是按 ABI 分的，没有过滤就没法知道该带哪一份；全带上会让 APK 涨到约 200 MiB。\n" +
                            "  请在 android.defaultConfig 里加：\n" +
                            "      ndk { abiFilters 'arm64-v8a' }")
        }
        // 不声明 List：Groovy 的 findAll 在 Set 上返回 Set，赋给 List 会在空集时炸在强制转换上
        def unsupported = abis.findAll { !SUPPORTED_ARCHS.contains(it) }
        if (!unsupported.isEmpty()) {
            throw new GradleException(
                    "[GeckoViewASM] 随包模式只支持 ${SUPPORTED_ARCHS}，收到不支持的 ABI: ${unsupported}")
        }
        return abis.toList()
    }

    /**
     * 注册构建期插桩断言任务：只核对 `InstrumentationTargets` 的签名在已解析的 GeckoView AAR
     * 中是否仍存在，挂载方式由消费方 build.gradle 决定。
     */
    private void registerInstrumentationVerificationTask(Project project) {
        project.tasks.register(
                VerifyGeckoviewInstrumentationTargetsTask.TASK_NAME,
                VerifyGeckoviewInstrumentationTargetsTask) {
            group = 'verification'
            description = '校验 ASM 插桩目标（GeckoLoader / GeckoThread 的方法签名）在已解析的 GeckoView AAR 中仍然存在；缺失即构建失败。'
        }
    }
}
