package com.happyboy.tokay.asm.asm

import org.objectweb.asm.Opcodes

/**
 * ASM 插桩目标签名表 —— **唯一数据源**。visitor 匹配与构建期断言消费同一张表；GeckoView 一旦
 * 改了这些方法签名就会匹配不上、补丁静默失效（构建仍绿，真机才炸
 * `dlopen failed: library "libmozglue.so" not found`），断言把这种失效提前到构建期。
 * 表不可变、不放 Gradle / Project 状态（并行构建与配置缓存下安全）。
 */
final class InstrumentationTargets {

    static final String GECKO_LOADER = 'org/mozilla/gecko/mozglue/GeckoLoader'
    static final String GECKO_THREAD = 'org/mozilla/gecko/GeckoThread'

    private static final int STATIC = Opcodes.ACC_STATIC

    // Groovy 的"尾随闭包"只对方法调用有效，构造器必须把闭包写在括号里。
    private static final List<InstrumentationTarget> TARGETS = Collections.unmodifiableList([
            new InstrumentationTarget(GECKO_LOADER, 'getLibraryPath', '(Ljava/lang/String;)Ljava/lang/String;', STATIC,
                    { api, mv, access, name, descriptor ->
                        new GeckoLoaderGetLibraryPathReplacer(api, mv, access, name, descriptor)
                    }),
            new InstrumentationTarget(GECKO_LOADER, 'getLibraryBase', '()Ljava/lang/String;', STATIC,
                    { api, mv, access, name, descriptor ->
                        new GeckoLoaderGetLibraryBaseReplacer(api, mv, access, name, descriptor)
                    }),
            new InstrumentationTarget(GECKO_LOADER, 'doLoadLibrary', '(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Throwable;', STATIC,
                    { api, mv, access, name, descriptor ->
                        new GeckoLoaderDoLoadLibraryReplacer(api, mv, access, name, descriptor)
                    }),
            new InstrumentationTarget(GECKO_LOADER, 'attemptLoad', '(Ljava/lang/String;)Z', STATIC,
                    { api, mv, access, name, descriptor ->
                        new GeckoLoaderAttemptLoadVisitor(api, mv, access, name, descriptor)
                    }),
            new InstrumentationTarget(GECKO_LOADER, 'loadGeckoLibs', '(Landroid/content/Context;)V', STATIC,
                    { api, mv, access, name, descriptor ->
                        new GeckoLoaderLoadGeckoLibsReplacer(api, mv, access, name, descriptor)
                    }),
            // getMainProcessArgs() 是 private **实例**方法，故不检查 ACC_STATIC。
            new InstrumentationTarget(GECKO_THREAD, 'getMainProcessArgs', '()[Ljava/lang/String;', 0,
                    { api, mv, access, name, descriptor ->
                        new GeckoThreadGetMainProcessArgsVisitor(api, mv, access, name, descriptor)
                    }),
    ])

    private InstrumentationTargets() {
    }

    /** 全部插桩目标（顺序即断言报告顺序） */
    static List<InstrumentationTarget> all() {
        return TARGETS
    }

    /** 表里涉及的全部类内部名 */
    static Set<String> instrumentedClasses() {
        return TARGETS.collect { it.owner } as LinkedHashSet<String>
    }

    /**
     * `internalName` 是否属于本表里的类。刻意与 factory 自己的 owner 比对：否则两个 factory 会
     * 互相把对方的类也宣布为 instrumentable，平白多走一遍 transform。
     */
    static boolean hasTargetsFor(String owner, String internalName) {
        return internalName != null &&
                internalName == owner &&
                TARGETS.any { it.owner == owner }
    }

    /** 表里属于某个类的目标 */
    static List<InstrumentationTarget> forClass(String owner) {
        return TARGETS.findAll { it.owner == owner }
    }

    /** visitor 匹配入口：返回命中的目标，未命中返回 null */
    static InstrumentationTarget match(String owner, int access, String name, String descriptor) {
        return TARGETS.find { it.owner == owner && it.matches(access, name, descriptor) }
    }
}
