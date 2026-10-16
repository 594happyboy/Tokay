package com.happyboy.tokay.asm.verify

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 用 ASM `ClassReader` 从**已解析的 GeckoView AAR** 里读方法签名：读 `classes.jar` 而非编译产物
 * （增量构建、缓存命中下产物未必在），也不用反射（拿不到 private 方法，还会触发 GeckoLoader 的类加载）。
 * `ClassReader` 不受方法可见性影响，也不执行 `<clinit>`。
 */
class ClassFileSignatureReader {

    /** AAR 规范里的 classes 入口名 */
    private static final String CLASSES_JAR_NAME = 'classes.jar'

    /**
     * @param internalNames 关心的类内部名（如 `org/mozilla/gecko/mozglue/GeckoLoader`）
     * @return 类内部名 -> 方法签名集合（`方法名 + descriptor`）；classes.jar 里没有的类不出现
     */
    static Map<String, Set<String>> readMethodSignaturesFromAar(File aarFile, Collection<String> internalNames) {
        if (aarFile == null || !aarFile.isFile()) {
            throw new IllegalStateException("GeckoView AAR 不存在: ${aarFile}")
        }
        return new ZipFile(aarFile).withCloseable { ZipFile aar ->
            ZipEntry classesJarEntry = aar.entries().find {
                !it.isDirectory() && (it.name == CLASSES_JAR_NAME || it.name.endsWith('/' + CLASSES_JAR_NAME))
            }
            if (classesJarEntry == null) {
                throw new IllegalStateException("AAR 内没有 ${CLASSES_JAR_NAME}: ${aarFile.absolutePath}")
            }
            byte[] jarBytes = readAllBytes(aar.getInputStream(classesJarEntry))
            return readMethodSignaturesFromJarBytes(jarBytes, internalNames)
        }
    }

    /** 同上，但直接从 classes.jar 的字节开始（便于纯 JVM 用例用合成 jar 驱动） */
    static Map<String, Set<String>> readMethodSignaturesFromJarBytes(byte[] jarBytes, Collection<String> internalNames) {
        Map<String, Set<String>> result = new LinkedHashMap<>()
        Set<String> wanted = new LinkedHashSet<>(internalNames ?: [])
        if (jarBytes == null || jarBytes.length == 0) {
            return result
        }
        new ZipInputStream(new ByteArrayInputStream(jarBytes)).withCloseable { ZipInputStream zis ->
            ZipEntry entry
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.name.endsWith('.class')) {
                    continue
                }
                String internalName = entry.name.substring(0, entry.name.length() - '.class'.length())
                if (!wanted.contains(internalName)) {
                    continue
                }
                // 只读当前 entry（ZipInputStream 在 entry 末尾返回 EOF），SKIP_CODE 下不解析方法体。
                // 刻意不用 Groovy 的 `zis.bytes`：它会顺手 close 掉流，导致读不到第二个 entry。
                byte[] classBytes = readAllBytes(zis)
                def collector = new MethodSignatureCollector()
                new ClassReader(classBytes).accept(
                        collector,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
                result.put(internalName, collector.signatures)
            }
        }
        return result
    }

    /** 读尽当前 entry（ZipInputStream 语义下到 entry 末尾即 EOF），不关闭流 */
    private static byte[] readAllBytes(InputStream inputStream) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        byte[] chunk = new byte[8192]
        int read
        while ((read = inputStream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}

/** 只收方法签名（`方法名 + descriptor`），不读方法体 */
class MethodSignatureCollector extends ClassVisitor {

    final Set<String> signatures = new LinkedHashSet<String>()

    MethodSignatureCollector() {
        super(Opcodes.ASM9)
    }

    @Override
    MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        signatures.add(name + descriptor)
        return null
    }
}
