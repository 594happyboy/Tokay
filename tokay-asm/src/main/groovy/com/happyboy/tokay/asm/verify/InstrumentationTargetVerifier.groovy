package com.happyboy.tokay.asm.verify

import com.happyboy.tokay.asm.asm.InstrumentationTarget

/**
 * 判定器：**纯函数**，无 Gradle / 文件系统 / 类加载依赖，可直接用 JVM 单测覆盖。
 * 与 ASM 读取（{@link ClassFileSignatureReader}）和 Gradle 任务分离：前者提供事实，这里只做比对。
 */
class InstrumentationTargetVerifier {

    /**
     * @param targets 期望存在的插桩目标（通常 {@link com.happyboy.tokay.asm.asm.InstrumentationTargets#all()}）
     * @param presentSignatures 类内部名 -> 该类实际存在的方法签名集合（`方法名 + descriptor`）；
     *        **键缺失表示该类的 class 文件在 classes.jar 里不存在**
     * @return 缺失清单（保持 targets 的顺序）；空列表 = 全部命中
     */
    static List<MissingInstrumentationTarget> findMissing(List<InstrumentationTarget> targets,
                                                         Map<String, Set<String>> presentSignatures) {
        List<MissingInstrumentationTarget> missing = new ArrayList<>()
        if (targets == null) {
            return missing
        }
        targets.each { InstrumentationTarget target ->
            Set<String> signatures = presentSignatures == null ? null : presentSignatures.get(target.owner)
            if (signatures == null) {
                missing.add(new MissingInstrumentationTarget(target, true))
            } else if (!signatures.contains(target.signature())) {
                missing.add(new MissingInstrumentationTarget(target, false))
            }
        }
        return missing
    }
}
