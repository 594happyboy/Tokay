package com.happyboy.tokay.asm.verify

import com.happyboy.tokay.asm.asm.InstrumentationTarget

/** 一条未命中的插桩目标（纯函数输出元素） */
class MissingInstrumentationTarget {

    final InstrumentationTarget target

    /** true = 目标类在 classes.jar 里根本不存在；false = 类在，但没有 name + descriptor 都匹配的方法 */
    final boolean classMissing

    MissingInstrumentationTarget(InstrumentationTarget target, boolean classMissing) {
        this.target = target
        this.classMissing = classMissing
    }

    /** 错误信息里的一行（必须带类名 + 方法名 + descriptor） */
    String describe() {
        if (classMissing) {
            return "类不存在: ${target.owner.replace('/', '.')}（期望方法 ${target.methodName}${target.descriptor}）"
        }
        return "方法缺失/签名不匹配: ${target.display()}"
    }

    @Override
    String toString() {
        return describe()
    }
}
