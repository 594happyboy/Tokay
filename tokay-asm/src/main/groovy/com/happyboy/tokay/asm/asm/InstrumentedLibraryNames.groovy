package com.happyboy.tokay.asm.asm

/**
 * ASM 发射字节码时引用的「本库自己的类」的**唯一数据源**。visitor 发射的是 slash 形式的 JVM
 * 内部名（`com/happyboy/tokay/BuildConfig`），点号形式的 grep 看不到，写错只在真机上炸
 * `NoClassDefFoundError`；构建期由 `VerifyGeckoviewInstrumentationTargetsTask` 核对是否仍一致。
 */
final class InstrumentedLibraryNames {

    /** 库的 namespace —— 改包名只改这一处。 */
    static final String LIBRARY_NAMESPACE = 'com.happyboy.tokay'

    /** 手写类，有源文件可直接查存在性；它是 GeckoLoader / GeckoThread 补丁唯一的落脚点。 */
    static final String RESOLVER = 'infrastructure/path/GeckoNativePathResolver'

    private InstrumentedLibraryNames() {
    }

    static String namespaceInternal() {
        return LIBRARY_NAMESPACE.replace('.', '/')
    }

    /** 相对类路径 → JVM 内部名，例如 `infrastructure/path/GeckoNativePathResolver` */
    static String internalName(String relativeClassPath) {
        return namespaceInternal() + '/' + relativeClassPath
    }

    /** 相对类路径 → 源码文件相对 `tokay` 模块根的位置（仅手写类有意义） */
    static String sourcePath(String relativeClassPath) {
        return "src/main/java/${namespaceInternal()}/${relativeClassPath}.kt"
    }
}
