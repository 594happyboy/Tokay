package com.happyboy.tokay.installer.extractor

import com.happyboy.tokay.infrastructure.path.GeckoKernelLayout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「解压不完整就不许报成功」的 JVM 护栏。
 *
 * 归档层对单条目失败是**计数不抛**（磁盘满、Zip Slip、IO 错都只 `failures++` 后继续），
 * 所以"解压器无条件写 `.unzip_success` 并报 Success"会把一份缺文件的目录宣布成装好了；
 * 那份目录之后会被复用判定反复接受，设备上再也修不回来。
 *
 * 用 zip 而不是 7z 驱动这条路径：`SevenZip.extract` 按魔数识别类型，而仓库里**没有任何内核包**
 * （内核是运行时下发的产物）。越界条目（`../`）触发的是 `FileOps.safeResolve` 的确定性
 * `SecurityException`，不依赖"磁盘刚好满"这类不可复现的条件。
 */
class SevenZipExtractorIncompleteExtractionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newExtractor() = SevenZipExtractor()

    private fun writeZip(vararg entries: String): File {
        val zip = File(tempFolder.root, "kernel-${entries.size}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            entries.forEach { name ->
                out.putNextEntry(ZipEntry(name))
                out.write("noop".toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun incompleteExtractionMustFailAndMustNotLeaveSuccessFlag() = runBlocking {
        val targetDir = File(tempFolder.root, "libs")
        val zip = writeZip("jni/arm64-v8a/libmozglue.so", "../escape.so")

        val results = newExtractor().extract(zip, targetDir).toList()

        assertTrue(
            "有条目没解出来时必须报 Failure，实际: $results",
            results.last() is ExtractResult.Failure
        )
        assertFalse(
            "解压不完整就不该留下成功标志——它会让缺文件的目录被反复复用",
            GeckoKernelLayout.unzipSuccessFlag(targetDir).exists()
        )
    }

    @Test
    fun completeExtractionWritesSuccessFlag() = runBlocking {
        val targetDir = File(tempFolder.root, "libs")
        val zip = writeZip("jni/arm64-v8a/libmozglue.so", "res/base.apk")

        val results = newExtractor().extract(zip, targetDir).toList()

        assertTrue("全部条目都解出来时必须报 Success，实际: $results", results.last() is ExtractResult.Success)
        assertTrue(
            "完整解压必须留下成功标志",
            GeckoKernelLayout.unzipSuccessFlag(targetDir).isFile
        )
    }
}
