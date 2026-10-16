package com.happyboy.tokay.engine.server.mapping

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `/files/` 目录映射必须拒绝一切逃出映射目录的路径；每条穿越用例的目标文件都真实创建在临时目录里，
 * 以免「返回 null」只是因为目标文件不存在。
 */
class DirectoryMappingModuleTest {

    private lateinit var tempRoot: File
    private lateinit var module: DirectoryMappingModule

    @Before
    fun setUp() {
        // root/files/web/viewer.html 是映射目录；root/x、root/secret、databases/x.db 是各穿越用例的真实目标
        tempRoot = Files.createTempDirectory("directory-mapping-module").toFile()
        val mappingDir = File(tempRoot, "root/files")
        assert(mappingDir.mkdirs())

        File(mappingDir, "web").mkdirs()
        File(mappingDir, "web/viewer.html").writeText("<html>ok</html>")
        File(tempRoot, "root/x").writeText("outside-1")
        File(tempRoot, "root/secret").writeText("outside-2")
        File(tempRoot, "databases").mkdirs()
        File(tempRoot, "databases/x.db").writeText("outside-3")

        module = DirectoryMappingModule(mappingDir)
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun getFile_servesFileInsideMappingDirectory() {
        val result = module.getFile("/files/web/viewer.html")
        assertNotNull(result)
        assertEquals("<html>ok</html>", result!!.inputStream.bufferedReader().use { it.readText() })
    }

    @Test
    fun getFile_rejectsParentTraversalToExistingFile() {
        // 目标 root/x 确实存在，仍然必须返回 null
        assertEquals("outside-1", File(tempRoot, "root/x").readText())
        assertNull(module.getFile("/files/../x"))
    }

    @Test
    fun getFile_rejectsDeepTraversalToExistingFile() {
        assertEquals("outside-3", File(tempRoot, "databases/x.db").readText())
        assertNull(module.getFile("/files/../../databases/x.db"))
    }

    @Test
    fun getFile_rejectsDotDotAfterDotSegment() {
        assertEquals("outside-2", File(tempRoot, "root/secret").readText())
        assertNull(module.getFile("/files/./../secret"))
    }

    @Test
    fun getFile_rejectsEmptyRelativePath() {
        assertNull(module.getFile("/files/"))
    }
}
