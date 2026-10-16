package com.happyboy.tokay.asm.pack

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZFileOptions
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

/**
 * 用与 tokay-archive 同一套解码器（commons-compress SevenZFile）读回 [SevenZWriter] 的产物，
 * 验证目录树、目录条目形态与内容都还原正确——打包任务已经不再有外部 7z 可用作对照。
 *
 * 字典取小值：LZMA2 编码器的堆占用与字典同阶，内核包用的 64 MiB 会撑爆测试 JVM。
 */
class SevenZWriterTest {

    private static final int TEST_DICT_SIZE_BYTES = 1 << 20
    private static final int TEST_NICE_LEN = 64

    @Rule
    public TemporaryFolder temp = new TemporaryFolder()

    @Test
    void roundTripKeepsHierarchyDirectoryShapeAndContent() {
        File root = temp.newFolder('root')
        writeFile(new File(root, 'jni/arm64-v8a/libxul.so'), 'fake-libxul' * 512)
        writeFile(new File(root, 'jni/arm64-v8a/libmozglue.so'), 'fake-mozglue')
        writeFile(new File(root, 'res/base.apk'), 'fake-apk')
        new File(root, 'jni/x86_64').mkdirs()   // 空目录：原任务的临时结构里不会出现，但要保证不被丢掉

        File archive = new File(temp.root, 'out/nested/pkg.7z')
        SevenZWriter.writeTree(root, archive, TEST_DICT_SIZE_BYTES, TEST_NICE_LEN)

        assertTrue('7z 未产出', archive.isFile() && archive.length() > 0)

        Archive read = Archive.read(archive)
        // 目录在前、文件在后，两组各自按名称升序；目录名不带尾部斜杠（解压端按 isDirectory 判定）
        assertEquals(['jni', 'jni/arm64-v8a', 'jni/x86_64', 'res',
                      'jni/arm64-v8a/libmozglue.so', 'jni/arm64-v8a/libxul.so', 'res/base.apk'], read.names)
        assertEquals([true, true, true, true, false, false, false], read.directoryFlags)
        assertTrue(read.names.every { !it.endsWith('/') })

        assertEquals('fake-mozglue', read.contents['jni/arm64-v8a/libmozglue.so'])
        assertEquals('fake-libxul' * 512, read.contents['jni/arm64-v8a/libxul.so'])
        assertEquals('fake-apk', read.contents['res/base.apk'])
    }

    @Test
    void overwritesExistingArchiveInsteadOfAppending() {
        File root = temp.newFolder('root2')
        writeFile(new File(root, 'res/base.apk'), 'apk')

        File archive = new File(temp.root, 'pkg.7z')
        archive.bytes = 'garbage that is not a 7z archive' as byte[]

        SevenZWriter.writeTree(root, archive, TEST_DICT_SIZE_BYTES, TEST_NICE_LEN)
        SevenZWriter.writeTree(root, archive, TEST_DICT_SIZE_BYTES, TEST_NICE_LEN)

        Archive read = Archive.read(archive)
        assertEquals(['res', 'res/base.apk'], read.names)
        assertEquals('apk', read.contents['res/base.apk'])
    }

    @Test
    void missingRootDirectoryFailsLoudly() {
        File archive = new File(temp.root, 'nope.7z')
        try {
            SevenZWriter.writeTree(new File(temp.root, 'does-not-exist'), archive, TEST_DICT_SIZE_BYTES, TEST_NICE_LEN)
            throw new AssertionError('应当抛异常')
        } catch (IllegalArgumentException expected) {
            assertFalse('不应产出半成品', archive.exists())
        }
    }

    private static void writeFile(File file, String content) {
        file.parentFile.mkdirs()
        file.bytes = content.getBytes('UTF-8')
    }

    /** 一次性读出全部条目：7z 是单遍流，必须先读完当前条目才能取下一个。 */
    private static class Archive {
        List<String> names = []
        List<Boolean> directoryFlags = []
        Map<String, String> contents = [:]

        @SuppressWarnings('deprecation')
        static Archive read(File file) {
            Archive archive = new Archive()
            new SevenZFile(file, SevenZFileOptions.DEFAULT).withCloseable { SevenZFile sevenZ ->
                SevenZArchiveEntry entry
                while ((entry = sevenZ.getNextEntry()) != null) {
                    archive.names << entry.name
                    archive.directoryFlags << entry.isDirectory()
                    ByteArrayOutputStream out = new ByteArrayOutputStream()
                    byte[] buffer = new byte[4096]
                    int read
                    while ((read = sevenZ.read(buffer)) > 0) {
                        out.write(buffer, 0, read)
                    }
                    archive.contents[entry.name] = out.toString('UTF-8')
                }
            }
            return archive
        }
    }
}
