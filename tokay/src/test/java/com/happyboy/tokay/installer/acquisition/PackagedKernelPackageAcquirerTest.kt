package com.happyboy.tokay.installer.acquisition

import com.happyboy.tokay.domain.policy.NonRetryableFailure
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 随包模式取包：从 assets 落一份临时副本。这里用假 [KernelAssetAccess] 覆盖真机构造不出的分支
 * （缺本机 ABI 那份包、包体大于一个缓冲），并用真实磁盘核对**落盘字节与包里那份一模一样**。
 */
class PackagedKernelPackageAcquirerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val version = "147.0.20260212191108"

    /** 假 assets：只认自己那几条，缺了就像 `AssetManager.open` 一样抛。 */
    private class FakeKernelAssets(private val entries: Map<String, ByteArray>) : KernelAssetAccess {
        override fun listKernelPackageNames(): Set<String> =
            entries.keys.filter { PackagedKernelContract.isKernelPackage(it) }.toSet()

        override fun open(assetName: String): InputStream =
            entries[assetName]?.let { ByteArrayInputStream(it) }
                ?: throw FileNotFoundException(assetName)
    }

    private fun workDir(): File = tempFolder.newFolder("work-${System.nanoTime()}")

    /** 300 KB 且逐字节不同：跨得过 64 KiB 的拷贝缓冲，任何截断/错位都会被逐字节比对抓到。 */
    private fun payload(size: Int = 300_000): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun acquire(
        assets: KernelAssetAccess,
        abiTag: String = "arm64-v8a",
        workDir: File = workDir()
    ): AcquisitionEvent.Acquired = runBlocking {
        PackagedKernelPackageAcquirer(assets)
            .acquire(version, abiTag, workDir)
            .filterIsInstance<AcquisitionEvent.Acquired>()
            .first()
    }

    @Test
    fun `落盘的临时副本与 asset 字节完全一致`() {
        val bytes = payload()
        val assets = FakeKernelAssets(mapOf("gecko-kernel-arm64-v8a.7z" to bytes))

        val event = acquire(assets)

        assertEquals(
            "文件名必须是 asset 名（解压器的日志/排障都按它认包）",
            "gecko-kernel-arm64-v8a.7z",
            event.file.name
        )
        assertTrue("从 assets 落下来的只能是临时副本", event.temporary)
        assertFalse("随包模式没有「复用磁盘上已有的包」这条路径", event.reusedExisting)
        assertEquals("字节数要对得上", bytes.size.toLong(), event.file.length())
        assertArrayEquals("字节必须一模一样", bytes, event.file.readBytes())
    }

    @Test
    fun `缺本机 ABI 那份包时在取包阶段就响亮失败`() {
        val assets = FakeKernelAssets(mapOf("gecko-kernel-x86_64.7z" to payload(16)))

        val error = assertThrows(PackagedKernelAssetMissingException::class.java) {
            runBlocking {
                PackagedKernelPackageAcquirer(assets).acquire(version, "arm64-v8a", workDir()).collect { }
            }
        }

        assertTrue("确定性失败不该被重试", error is NonRetryableFailure)
        assertTrue(
            "要点名期望的 asset 名: ${error.message}",
            error.message!!.contains("gecko-kernel-arm64-v8a.7z")
        )
    }

    @Test
    fun `缺包时不会在中转目录里留下半个文件`() {
        val workDir = workDir()
        val assets = FakeKernelAssets(emptyMap())

        assertThrows(PackagedKernelAssetMissingException::class.java) {
            runBlocking {
                PackagedKernelPackageAcquirer(assets).acquire(version, "arm64-v8a", workDir).collect { }
            }
        }

        assertEquals("判定在开写之前完成，目录里不该有产物", emptyList<File>(), workDir.listFiles()!!.toList())
    }
}
