package com.happyboy.tokay.asm.verify

import com.happyboy.tokay.asm.asm.InstrumentationTarget
import com.happyboy.tokay.asm.asm.InstrumentationTargets
import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertSame
import static org.junit.Assert.assertTrue

/**
 * 判定器纯函数用例：全存在 / 缺一个方法 / 类不存在 / descriptor 不匹配 四种情形。
 * 放在本模块自己的 test 源集，因为它要引用 asm-plugin 里的 {@link InstrumentationTargets}。
 */
class InstrumentationTargetVerifierTest {

    /** 用目标表本身生成"全部存在"的事实，避免用例里再抄一份签名 */
    private static Map<String, Set<String>> allPresent() {
        Map<String, Set<String>> present = new LinkedHashMap<>()
        InstrumentationTargets.all().each { InstrumentationTarget target ->
            present.computeIfAbsent(target.owner) { new LinkedHashSet<String>() }.add(target.signature())
        }
        return present
    }

    private static InstrumentationTarget firstOf(String owner) {
        List<InstrumentationTarget> targets = InstrumentationTargets.forClass(owner)
        assertFalse("表里应当有 ${owner} 的目标", targets.isEmpty())
        return targets.get(0)
    }

    @Test
    void allTargetsPresent() {
        List<MissingInstrumentationTarget> missing =
                InstrumentationTargetVerifier.findMissing(InstrumentationTargets.all(), allPresent())

        assertTrue("全部存在时不应有缺失: ${missing}", missing.isEmpty())
    }

    @Test
    void oneMethodMissing() {
        Map<String, Set<String>> present = allPresent()
        InstrumentationTarget victim = firstOf(InstrumentationTargets.GECKO_LOADER)
        present.get(victim.owner).remove(victim.signature())

        List<MissingInstrumentationTarget> missing =
                InstrumentationTargetVerifier.findMissing(InstrumentationTargets.all(), present)

        assertEquals(1, missing.size())
        assertSame(victim, missing.get(0).target)
        assertFalse("类在、只是方法缺，不是 classMissing", missing.get(0).classMissing)
        assertTrue("错误行必须带 descriptor: ${missing.get(0).describe()}",
                missing.get(0).describe().contains(victim.descriptor))
    }

    @Test
    void classMissing() {
        Map<String, Set<String>> present = allPresent()
        String owner = InstrumentationTargets.GECKO_THREAD
        present.remove(owner)

        List<MissingInstrumentationTarget> missing =
                InstrumentationTargetVerifier.findMissing(InstrumentationTargets.all(), present)

        int expected = InstrumentationTargets.forClass(owner).size()
        assertEquals(expected, missing.size())
        missing.each { MissingInstrumentationTarget item ->
            assertTrue("类不存在时 classMissing 应为 true", item.classMissing)
            assertTrue("错误行必须带类名: ${item.describe()}",
                    item.describe().contains(owner.replace('/', '.')))
        }
    }

    @Test
    void descriptorMismatch() {
        Map<String, Set<String>> present = allPresent()
        InstrumentationTarget victim = firstOf(InstrumentationTargets.GECKO_LOADER)
        // 方法名还在，但 descriptor 变了 —— 这正是 GeckoView 升级最常见的破坏方式
        present.get(victim.owner).remove(victim.signature())
        present.get(victim.owner).add(victim.methodName + '(Ljava/lang/Object;)Ljava/lang/Object;')

        List<MissingInstrumentationTarget> missing =
                InstrumentationTargetVerifier.findMissing(InstrumentationTargets.all(), present)

        assertEquals(1, missing.size())
        assertSame(victim, missing.get(0).target)
        assertFalse("类存在，只是签名对不上", missing.get(0).classMissing)
        assertTrue(missing.get(0).describe().contains(victim.descriptor))
    }

    @Test
    void emptyPresentReportsEveryTarget() {
        List<MissingInstrumentationTarget> missing =
                InstrumentationTargetVerifier.findMissing(InstrumentationTargets.all(), [:])

        assertEquals(InstrumentationTargets.all().size(), missing.size())
        assertTrue(missing.every { it.classMissing })
    }
}
