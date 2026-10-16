package com.happyboy.tokay.asm.verify

import com.happyboy.tokay.asm.Constants
import com.happyboy.tokay.asm.asm.InstrumentationTarget
import com.happyboy.tokay.asm.asm.InstrumentationTargets
import com.happyboy.tokay.asm.asm.InstrumentedLibraryNames
import com.happyboy.tokay.asm.scanner.AarScanner
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction

/**
 * 构建期「生效断言」：读已解析 GeckoView AAR 内的 `classes.jar`，用与 visitor 同一张表逐条核对
 * 插桩目标签名；任何一条缺失就抛 `GradleException`，不只打日志——签名匹配不上意味着补丁静默失效，
 * 构建仍绿而真机才炸 `dlopen failed: library "libmozglue.so" not found`。
 *
 * task action 会解析 `*RuntimeClasspath` 配置，因此本任务与 Gradle 配置缓存不兼容。
 */
class VerifyGeckoviewInstrumentationTargetsTask extends DefaultTask {

    static final String TASK_NAME = 'verifyGeckoviewInstrumentationTargets'

    /**
     * 输出给 `tools/tokay-compat-matrix.sh` 汇总用的稳定标记行；改格式等于改矩阵脚本，需同步。
     */
    static final String RESULT_PASS_PREFIX = '[GeckoViewASM] 插桩目标校验通过:'
    static final String RESULT_FAIL_PREFIX = '[GeckoViewASM] 插桩目标校验失败:'
    static final String MISSING_PREFIX = '[GeckoViewASM] 缺失目标:'

    /** 引用核对（InstrumentedLibraryNames）通过的标记行，与上面那条刻意区分开 */
    static final String RESULT_NAMES_PASS = '[GeckoViewASM] 插桩引用校验通过:'

    @TaskAction
    void verify() {
        // 先核对「本插件发射出去的类名」，再核对 GeckoView 的签名：两者防的是不同的失效。
        verifyInstrumentedLibraryNames()

        List<InstrumentationTarget> targets = InstrumentationTargets.all()
        Set<String> owners = InstrumentationTargets.instrumentedClasses()

        String configurationName = pickRuntimeConfigurationName()
        if (configurationName == null) {
            throw new GradleException(buildFailureMessage('未知（未解析到 AAR）', [], null,
                    "${project.path} 上找不到任何以 RuntimeClasspath 结尾的配置，无法定位 GeckoView AAR"))
        }

        List<AarScanner.ResolvedAar> aars
        try {
            aars = AarScanner.resolveAarArtifactsFromConfiguration(
                    project, configurationName, Constants.GECKO_GROUP, Constants.GECKO_MODULE)
        } catch (Throwable t) {
            throw new GradleException(buildFailureMessage('未知（解析失败）', [], configurationName, t.message), t)
        }
        if (aars.isEmpty()) {
            throw new GradleException(buildFailureMessage('未知（未解析到 AAR）', [], configurationName,
                    "${configurationName} 里没有 ${Constants.GECKO_GROUP}:${Constants.GECKO_MODULE} 的 AAR（依赖坐标或配置名变了？）"))
        }

        Set<String> versions = new LinkedHashSet<>()
        Map<String, Set<String>> present = new LinkedHashMap<>()
        aars.each { AarScanner.ResolvedAar aar ->
            if (aar.version) {
                versions.add(aar.version)
            }
            Map<String, Set<String>> found
            try {
                found = ClassFileSignatureReader.readMethodSignaturesFromAar(aar.file, owners)
            } catch (Throwable t) {
                throw new GradleException(buildFailureMessage(aar.version ?: '未知', [], configurationName,
                        "读取 ${aar.file.name} 的 classes.jar 失败: ${t.message}"), t)
            }
            found.each { String owner, Set<String> signatures ->
                present.computeIfAbsent(owner) { new LinkedHashSet<String>() }.addAll(signatures)
            }
        }

        String resolvedVersion = versions ? versions.join(', ') : '未知'
        List<MissingInstrumentationTarget> missing = InstrumentationTargetVerifier.findMissing(targets, present)

        if (!missing.isEmpty()) {
            // 先打稳定标记行（矩阵脚本据此汇总），再抛异常让构建失败
            logger.lifecycle("${RESULT_FAIL_PREFIX} version=${resolvedVersion} targets=${targets.size()} missing=${missing.size()}")
            missing.each { MissingInstrumentationTarget item ->
                logger.lifecycle("${MISSING_PREFIX} ${item.describe()}")
            }
            throw new GradleException(buildFailureMessage(resolvedVersion, missing, configurationName, null))
        }

        logger.lifecycle("${RESULT_PASS_PREFIX} version=${resolvedVersion} targets=${targets.size()} classes=${owners.size()} missing=0")
    }

    /**
     * 断言只关心被插桩的 GeckoView AAR 是哪个版本，与 buildType 无关：优先 debug、其次 release，
     * 最后取任意 `*RuntimeClasspath`。
     */    private String pickRuntimeConfigurationName() {
        for (String name : ['debugRuntimeClasspath', 'releaseRuntimeClasspath']) {
            if (project.configurations.findByName(name) != null) {
                return name
            }
        }
        List<String> candidates = project.configurations.names.findAll { it.endsWith('RuntimeClasspath') }.sort()
        return candidates.isEmpty() ? null : candidates.first()
    }

    /**
     * 第二层断言：核对 visitor **发射出去的类名**（{@link InstrumentedLibraryNames}）在库侧仍成立。
     * 它防的是与签名断言不同的失效——本仓改了包名 / 挪了类而发射的类名没跟着改：visitor 发射的是
     * **slash 形式的 JVM 内部名**，点号形式的 grep 看不到，后果同样是构建全绿、真机才报
     * `NoClassDefFoundError`。刻意不读字节码产物（会引入「preBuild 早于库编译」的任务顺序依赖）。
     */
    private void verifyInstrumentedLibraryNames() {
        def utilProject = project.rootProject.findProject(':tokay')
        if (utilProject == null) {
            throw new GradleException(instrumentedNamesFailure('找不到 :tokay 子项目，无法核对插桩引用的类名'))
        }

        String declared = readDeclaredNamespace(utilProject)
        if (declared == null) {
            throw new GradleException(instrumentedNamesFailure(
                    '读不到 tokay 的 namespace（既取不到 android 扩展，也没在 build.gradle 里匹配到 namespace 声明）'))
        }
        if (declared != InstrumentedLibraryNames.LIBRARY_NAMESPACE) {
            throw new GradleException(instrumentedNamesFailure(
                    "namespace 不一致: tokay 声明 '${declared}'，"
                            + "而 asm-plugin 的 InstrumentedLibraryNames.LIBRARY_NAMESPACE 是 "
                            + "'${InstrumentedLibraryNames.LIBRARY_NAMESPACE}'"))
        }

        File resolverSource = new File(utilProject.projectDir,
                InstrumentedLibraryNames.sourcePath(InstrumentedLibraryNames.RESOLVER))
        if (!resolverSource.isFile()) {
            throw new GradleException(instrumentedNamesFailure(
                    "插桩引用的手写类源文件不存在: ${resolverSource.path}\n"
                            + "  （GeckoNativePathResolver 被挪走或改名了？它是 GeckoLoader / GeckoThread 补丁唯一的落脚点）"))
        }

        logger.lifecycle("${RESULT_NAMES_PASS} namespace=${InstrumentedLibraryNames.LIBRARY_NAMESPACE} "
                + "resolver=${InstrumentedLibraryNames.internalName(InstrumentedLibraryNames.RESOLVER)}")
    }

    /** 先取 AGP 的 android.namespace，取不到就回退读 build.gradle 的声明文本 */
    private String readDeclaredNamespace(Project utilProject) {
        try {
            def androidExt = utilProject.extensions.findByName('android')
            if (androidExt != null && androidExt.hasProperty('namespace')) {
                def ns = androidExt.namespace
                if (ns != null && ns.toString().trim()) {
                    return ns.toString().trim()
                }
            }
        } catch (Throwable ignore) {
            // 配置早期 AGP 扩展可能还不可读，走下面的文本回退
        }
        try {
            File buildFile = utilProject.file('build.gradle')
            if (buildFile.isFile()) {
                def matcher = buildFile.text =~ /(?m)^\s*namespace\s+['"]([^'"]+)['"]/
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }
        } catch (Throwable ignore) {
            // 落到调用方报错
        }
        return null
    }

    private String instrumentedNamesFailure(String detail) {
        StringBuilder sb = new StringBuilder()
        sb.append('\n[GeckoViewASM] ASM 插桩「引用核对」失败（verifyGeckoviewInstrumentationTargets）\n')
        sb.append("  ${detail}\n")
        sb.append('  影响: visitor 会把引用发射到一个不存在的类上——**构建仍然绿**，真机才报 ')
        sb.append('NoClassDefFoundError。\n')
        sb.append('  指引: 改包名时除了改源码目录与 build.gradle 的 namespace，还必须同步 ')
        sb.append('tokay-asm 的 InstrumentedLibraryNames.LIBRARY_NAMESPACE')
        sb.append('（visitor 发射的是 slash 形式内部名，点号形式的 grep 看不到它们）。')
        return sb.toString()
    }

    private String buildFailureMessage(String resolvedVersion,
                                       List<MissingInstrumentationTarget> missing,
                                       String configurationName,
                                       String directCause) {
        StringBuilder sb = new StringBuilder()
        sb.append('\n[GeckoViewASM] ASM 插桩「生效断言」失败（verifyGeckoviewInstrumentationTargets）\n')
        sb.append("  当前 geckoviewVersion: ${resolvedVersion}${declaredVersionNote(resolvedVersion)}\n")
        if (configurationName != null) {
            sb.append("  解析配置: ${project.path}:${configurationName}\n")
        }
        if (directCause != null) {
            sb.append("  直接原因: ${directCause}\n")
        }
        if (missing != null && !missing.isEmpty()) {
            sb.append("  以下 ${missing.size()} 个插桩目标在 GeckoView classes.jar 中不存在：\n")
            missing.each { MissingInstrumentationTarget item ->
                sb.append("    - ${item.describe()}\n")
            }
            sb.append('  影响: 对应 visitor 不会命中，插桩静默失效——构建仍然绿，真机才会报 ')
            sb.append('dlopen failed: library "libmozglue.so" not found\n')
        }
        sb.append('  指引: 请更新 tokay-asm 的 InstrumentationTargets 签名表与对应 visitor')
        sb.append('（GeckoLoader / GeckoThread），再重新运行 :demo:verifyGeckoviewInstrumentationTargets 验证。')
        return sb.toString()
    }

    /** 只提示「tokay/build.gradle 声明的版本」与解析出的 AAR 版本是否一致 */
    private String declaredVersionNote(String resolvedVersion) {
        try {
            def utilProject = project.rootProject.findProject(':tokay')
            def declared = utilProject?.ext?.properties?.get('geckoviewVersion')?.toString()
            if (declared != null && resolvedVersion != null && declared != resolvedVersion) {
                return "（tokay/build.gradle 声明为 ${declared}，与解析出的 AAR 不一致，请检查依赖坐标）"
            }
        } catch (Throwable ignore) {
            // 取不到就不提示，不影响断言本身
        }
        return ''
    }
}
