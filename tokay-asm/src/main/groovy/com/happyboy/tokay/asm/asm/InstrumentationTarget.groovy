package com.happyboy.tokay.asm.asm

import org.objectweb.asm.MethodVisitor

/**
 * 一条 ASM 插桩目标：期望在 GeckoView 字节码里存在、且会被 visitor 改写的方法。
 *
 * 同一组 owner / 方法名 / descriptor 同时驱动 visitor 匹配（{@link #matches}）与构建期断言
 * （{@link com.happyboy.tokay.asm.verify.InstrumentationTargetVerifier}），因此两者不会漂移。
 */
class InstrumentationTarget {

    /** 目标类内部名，如 `org/mozilla/gecko/mozglue/GeckoLoader` */
    final String owner

    final String methodName

    final String descriptor

    /**
     * visitor 匹配时额外要求的访问标志（0 = 不检查）。GeckoLoader 的 5 个方法都要求 `ACC_STATIC`；
     * GeckoThread.getMainProcessArgs 是 private 实例方法，不检查。
     */
    final int requiredAccess

    private final Closure methodVisitorFactory

    InstrumentationTarget(String owner,
                          String methodName,
                          String descriptor,
                          int requiredAccess,
                          Closure methodVisitorFactory) {
        this.owner = owner
        this.methodName = methodName
        this.descriptor = descriptor
        this.requiredAccess = requiredAccess
        this.methodVisitorFactory = methodVisitorFactory
    }

    /**
     * 断言用的签名键：`方法名 + descriptor`。descriptor 一定以 `(` 开头，拼接不会产生歧义；
     * `requiredAccess` 只参与 visitor 匹配，不进签名键。
     */
    String signature() {
        return methodName + descriptor
    }

    /** 人类可读的完整签名（错误信息用） */
    String display() {
        return "${owner.replace('/', '.')}#${methodName}${descriptor}"
    }

    /** visitor 的匹配条件（唯一数据源） */
    boolean matches(int access, String name, String descriptor) {
        return name == methodName &&
                descriptor == this.descriptor &&
                (requiredAccess == 0 || (access & requiredAccess) == requiredAccess)
    }

    /** 为该目标创建替换方法体的 MethodVisitor */
    MethodVisitor createVisitor(int api, MethodVisitor mv, int access, String name, String descriptor) {
        return (MethodVisitor) methodVisitorFactory.call(api, mv, access, name, descriptor)
    }

    @Override
    String toString() {
        return display()
    }
}
