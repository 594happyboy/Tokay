package com.happyboy.tokay.installer.extractor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExtractPathGuardTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("extract-path-guard").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun resolveInside_keepsNormalEntryUnderRoot() {
        val resolved = ExtractPathGuard.resolveInside(root, "web/viewer.html")
        assertEquals(File(root, "web/viewer.html").canonicalPath, resolved.path)
    }

    @Test
    fun resolveInside_allowsDirectoryEntry() {
        val resolved = ExtractPathGuard.resolveInside(root, "assets/runtime_extensions/")
        assertTrue(resolved.path.startsWith(root.canonicalPath + File.separator))
    }

    @Test(expected = SecurityException::class)
    fun resolveInside_rejectsParentTraversal() {
        ExtractPathGuard.resolveInside(root, "../escape.txt")
    }

    @Test(expected = SecurityException::class)
    fun resolveInside_rejectsDeepTraversal() {
        ExtractPathGuard.resolveInside(root, "a/b/../../../../etc/passwd")
    }

    @Test(expected = SecurityException::class)
    fun resolveInside_rejectsAbsolutePath() {
        ExtractPathGuard.resolveInside(root, "/etc/passwd")
    }

    @Test(expected = SecurityException::class)
    fun resolveInside_rejectsWindowsDrivePath() {
        ExtractPathGuard.resolveInside(root, "C:/Windows/system32/evil.dll")
    }
}
