package com.happyboy.tokay.asm.verify

import com.happyboy.tokay.asm.asm.InstrumentationTarget
import com.happyboy.tokay.asm.asm.InstrumentationTargets
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertTrue

/**
 * 合成一个含 classes.jar 的 AAR，用目标表生成 class，验证「ASM 读取 → 纯函数判定」整条链；
 * 即便没有 GeckoView 依赖缓存，也能证明断言读的是 classes.jar。
 */
class ClassFileSignatureReaderTest {

    @Test
    void readsSignaturesFromSyntheticAarAndAllTargetsAreFound() {
        File aar = syntheticAar(InstrumentationTargets.instrumentedClasses() as List)

        Map<String, Set<String>> present =
                ClassFileSignatureReader.readMethodSignaturesFromAar(aar, InstrumentationTargets.instrumentedClasses())

        assertEquals(InstrumentationTargets.instrumentedClasses().size(), present.size())
        assertTrue(InstrumentationTargetVerifier.findMissing(InstrumentationTargets.all(), present).isEmpty())
    }

    @Test
    void missingClassIsReportedWhenJarLacksIt() {
        List<String> classesInJar = [InstrumentationTargets.GECKO_LOADER]
        File aar = syntheticAar(classesInJar)

        Map<String, Set<String>> present =
                ClassFileSignatureReader.readMethodSignaturesFromAar(aar, InstrumentationTargets.instrumentedClasses())
        List<MissingInstrumentationTarget> missing =
                InstrumentationTargetVerifier.findMissing(InstrumentationTargets.all(), present)

        int expected = InstrumentationTargets.forClass(InstrumentationTargets.GECKO_THREAD).size()
        assertEquals(expected, missing.size())
        assertTrue(missing.every { it.classMissing })
    }

    @Test
    void aarWithoutClassesJarFailsLoudly() {
        File aar = File.createTempFile('geckoview-no-classes', '.aar')
        aar.deleteOnExit()
        new ZipOutputStream(new FileOutputStream(aar)).withCloseable { ZipOutputStream zos ->
            zos.putNextEntry(new ZipEntry('AndroidManifest.xml'))
            zos.write('<manifest/>'.getBytes('UTF-8'))
            zos.closeEntry()
        }

        try {
            ClassFileSignatureReader.readMethodSignaturesFromAar(aar, InstrumentationTargets.instrumentedClasses())
            assertTrue('classes.jar 缺失时必须抛异常', false)
        } catch (IllegalStateException expected) {
            assertTrue(expected.message.contains('classes.jar'))
        }
    }

    private static File syntheticAar(List<String> ownerInternalNames) {
        Map<String, byte[]> classes = new LinkedHashMap<>()
        ownerInternalNames.each { String owner ->
            classes.put(owner, classBytes(InstrumentationTargets.forClass(owner)))
        }
        byte[] classesJar = jarBytes(classes)

        File aar = File.createTempFile('geckoview-synthetic', '.aar')
        aar.deleteOnExit()
        new ZipOutputStream(new FileOutputStream(aar)).withCloseable { ZipOutputStream zos ->
            zos.putNextEntry(new ZipEntry('classes.jar'))
            zos.write(classesJar)
            zos.closeEntry()
            zos.putNextEntry(new ZipEntry('jni/arm64-v8a/libmozglue.so'))
            zos.write([0x7f, 0x45, 0x4c, 0x46] as byte[])
            zos.closeEntry()
        }
        return aar
    }

    /** 用目标表生成一个"只有这些方法"的抽象类（不执行，ClassReader 也不关心方法体） */
    private static byte[] classBytes(List<InstrumentationTarget> targets) {
        assertNotNull(targets)
        assertFalse(targets.isEmpty())
        ClassWriter cw = new ClassWriter(0)
        cw.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                targets.get(0).owner, null, 'java/lang/Object', null)
        targets.each { InstrumentationTarget target ->
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    target.methodName, target.descriptor, null, null).visitEnd()
        }
        // 一个干扰方法：读取器不应把它也算进目标
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, 'unrelated', '()V', null, null).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private static byte[] jarBytes(Map<String, byte[]> classes) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream()
        new ZipOutputStream(bos).withCloseable { ZipOutputStream zos ->
            classes.each { String owner, byte[] bytes ->
                zos.putNextEntry(new ZipEntry(owner + '.class'))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
