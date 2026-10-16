package com.happyboy.tokay.asm.task

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.GradleException

import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

import com.happyboy.tokay.asm.Constants
import com.happyboy.tokay.asm.pack.SevenZWriter
import com.happyboy.tokay.asm.scanner.AarScanner

/**
 * 从 GeckoView AAR 提取指定架构的 JNI 库与 assets，并入本地 runtime_extensions，打包成 7z。
 *
 * 包内结构固定为 `jni/<abi>/*.so` + `res/base.apk`：运行时按 `libs/{jni,res}` 落盘后加载，改动即破坏
 * 已发布内核包与 `tokay-archive` 的约定。压缩由 [SevenZWriter] 在进程内完成，不依赖外部 7z 可执行文件，
 * 因此任务不区分平台；仓库里没有任何内核包，7z 是运行时下发的产物。
 */
abstract class GeckoArchPackageTask extends DefaultTask {

    private static final String TEMP_DIR_NAME = "temp"
    private static final String TEMP_7Z_STRUCTURE_PREFIX = "temp-7z-structure"
    private static final String GECKO_ASSETS_DIR_NAME = "gecko-assets"
    private static final String GECKO_JNI_DIR_NAME = "gecko-jni"
    private static final String MERGED_ASSETS_DIR_NAME = "merged-assets"
    private static final String FINAL_JNI_DIR_NAME = "final-jni"
    private static final String FINAL_RES_DIR_NAME = "final-res"
    
    private static final String BASE_APK_NAME = "base.apk"

    protected File workDir
    
    protected File mainTempDir
    
    protected File geckoAssetsDir
    
    protected File geckoJniDir
    
    protected File mergedAssetsDir
    
    protected File finalJniDir
    
    protected File finalResDir
    
    protected File baseApkFile
    
    protected File temp7zStructureDir

    @Input
    abstract Property<String> getArchitecture()

    /**
     * 本地 runtime_extensions（bridge JS、manifest）会被打进包内 `res/base.apk`，而**运行时的 bridge
     * 脚本正是从那里加载的**（`resource://android/assets/...` 解析到 `-greomni` 根）。所以它必须算输入：
     * 不声明的话，改了 JS 打包任务照样判 UP-TO-DATE，设备上继续跑旧脚本——实测踩过。
     */
    @InputDirectory
    abstract DirectoryProperty getRuntimeExtensionsDir()

    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    GeckoArchPackageTask() {
        group = 'gecko'
        description = 'Package Gecko dependencies for specific architecture into 7z file'

        outputDir.convention(project.layout.buildDirectory.dir("gecko-package"))
    }

    private void initializeDirectories() {
        def targetArch = architecture.get()
        logger.debug("[GeckoArchPackageTask] Initializing directories for architecture: ${targetArch}")
        
        workDir = outputDir.get().asFile
        
        mainTempDir = new File(workDir, TEMP_DIR_NAME)
        
        geckoAssetsDir = new File(mainTempDir, GECKO_ASSETS_DIR_NAME)
        geckoJniDir = new File(mainTempDir, GECKO_JNI_DIR_NAME)
        
        mergedAssetsDir = new File(mainTempDir, MERGED_ASSETS_DIR_NAME)
        finalJniDir = new File(mainTempDir, FINAL_JNI_DIR_NAME)
        finalResDir = new File(mainTempDir, FINAL_RES_DIR_NAME)
        
        baseApkFile = new File(workDir, BASE_APK_NAME)
        
        temp7zStructureDir = new File(workDir, "${TEMP_7Z_STRUCTURE_PREFIX}-${System.currentTimeMillis()}")
        
        logger.debug("[GeckoArchPackageTask] Directory structure initialized:")
        logger.debug("  - Work Directory: ${workDir.absolutePath}")
        logger.debug("  - Main Temp Directory: ${mainTempDir.absolutePath}")
        logger.debug("  - Gecko Assets Directory: ${geckoAssetsDir.absolutePath}")
        logger.debug("  - Gecko JNI Directory: ${geckoJniDir.absolutePath}")
        logger.debug("  - Final JNI Directory: ${finalJniDir.absolutePath}")
        logger.debug("  - Final Res Directory: ${finalResDir.absolutePath}")
    }

    private void createRequiredDirectories() {
        logger.debug("[GeckoArchPackageTask] Creating required directories...")
        
        if (workDir.exists()) {
            project.delete(workDir)
        }
        workDir.mkdirs()
        
        [mainTempDir, geckoAssetsDir, geckoJniDir, mergedAssetsDir, finalJniDir, finalResDir].each { dir ->
            dir.mkdirs()
            logger.debug("[GeckoArchPackageTask] Created directory: ${dir.absolutePath}")
        }
        
        logger.lifecycle("[GeckoArchPackageTask] All required directories created successfully")
    }

    private void extractGeckoAarContents(Set<File> geckoAarFiles) {
        logger.lifecycle("[GeckoArchPackageTask] Extracting contents from ${geckoAarFiles.size()} Gecko AAR files...")
        
        geckoAarFiles.each { aarFile ->
            extractAssetsFromAar(aarFile, geckoAssetsDir)
            extractJniFromAar(aarFile, geckoJniDir)
        }
        
        logger.debug("[GeckoArchPackageTask] Gecko AAR contents extraction completed")
    }

    private void validateTargetArchitecture(String targetArch) {
        def archJniDir = new File(geckoJniDir, "jni/${targetArch}")
        if (!archJniDir.exists()) {
            throw new GradleException("Architecture ${targetArch} not found in JNI directory: ${geckoJniDir.absolutePath}")
        }
        logger.lifecycle("[GeckoArchPackageTask] Target architecture ${targetArch} validated successfully")
    }

    private void cleanupTemporaryFiles() {
        logger.debug("[GeckoArchPackageTask] Cleaning up temporary files...")
        
        if (mainTempDir.exists()) {
            project.delete(mainTempDir)
            logger.debug("[GeckoArchPackageTask] Cleaned up main temp directory: ${mainTempDir.absolutePath}")
        }
        
        if (temp7zStructureDir.exists()) {
            project.delete(temp7zStructureDir)
            logger.debug("[GeckoArchPackageTask] Cleaned up temp 7z structure directory: ${temp7zStructureDir.absolutePath}")
        }
        
        logger.debug("[GeckoArchPackageTask] Temporary files cleanup completed")
    }

    private void performEmergencyCleanup() {
        logger.warn("[GeckoArchPackageTask] Performing emergency cleanup...")
        
        try {
            if (mainTempDir?.exists()) {
                project.delete(mainTempDir)
                logger.debug("[GeckoArchPackageTask] Emergency cleanup: removed main temp directory")
            }
            
            if (temp7zStructureDir?.exists()) {
                project.delete(temp7zStructureDir)
                logger.debug("[GeckoArchPackageTask] Emergency cleanup: removed temp 7z structure directory")
            }
            
            if (workDir?.exists()) {
                workDir.listFiles()?.each { file ->
                    if (file.isDirectory() && file.name.startsWith(TEMP_7Z_STRUCTURE_PREFIX)) {
                        project.delete(file)
                        logger.debug("[GeckoArchPackageTask] Emergency cleanup: removed temp directory ${file.name}")
                    }
                }
            }
            
        } catch (Exception cleanupEx) {
            logger.error("[GeckoArchPackageTask] Emergency cleanup failed: ${cleanupEx.message}")
        }
    }

    @TaskAction
    void packageGeckoDependencies() {
        def targetArch = architecture.get()
        logger.lifecycle("[GeckoArchPackageTask] Starting Gecko dependencies packaging for architecture: ${targetArch}")

        try {
            initializeDirectories()
            
            cleanupOldCaches()
            
            createRequiredDirectories()

            def geckoAarFiles = findGeckoAarFiles()
            if (geckoAarFiles.isEmpty()) {
                throw new GradleException("No Gecko AAR files found!")
            }
            logger.debug("[GeckoArchPackageTask] Found ${geckoAarFiles.size()} Gecko AAR files")

            extractGeckoAarContents(geckoAarFiles)

            validateTargetArchitecture(targetArch)

            mergeAssets(geckoAssetsDir, mergedAssetsDir)

            createBaseApk(mergedAssetsDir, baseApkFile)

            prepareFinal7zStructureForArch(targetArch, geckoJniDir, baseApkFile, finalJniDir, finalResDir)

            def geckoVersion = extractVersionFromAar(geckoAarFiles.first())
            def output7zFile = new File(workDir, "geckoview-${targetArch}-${geckoVersion}.7z")
            create7zPackage(finalJniDir, finalResDir, output7zFile)

            cleanupTemporaryFiles()

            logger.lifecycle("[GeckoArchPackageTask] Successfully created: ${output7zFile.absolutePath}")

        } catch (Exception e) {
            logger.error("[GeckoArchPackageTask] Failed to package Gecko dependencies for ${targetArch}", e)
            
            performEmergencyCleanup()
            
            throw new GradleException("Gecko packaging failed for ${targetArch}: ${e.message}", e)
        }
    }

    private Set<File> findGeckoAarFiles() {
        String configName = determineConfigurationName()
        
        def aars = AarScanner.resolveAarFilesFromConfiguration(
            project, 
            configName, 
            Constants.GECKO_GROUP, 
            Constants.GECKO_MODULE
        )
        
        if (aars && !aars.isEmpty()) {
            logger.debug("[GeckoArchPackageTask] Found ${aars.size()} Gecko AAR files from ${configName}")
        } else {
            logger.warn("[GeckoArchPackageTask] No Gecko AAR files found in ${configName}")
        }
        
        return aars ?: new LinkedHashSet<File>()
    }
    
    /** 打包任务不在变体图里，没有变体上下文，只能固定用 debug 运行时配置来解析 GeckoView AAR。 */
    private String determineConfigurationName() {
        return "debugRuntimeClasspath"
    }

    private String extractVersionFromAar(File aarFile) {
        String version = "unknown"
        
        try {
            String fileName = aarFile.name
            if (fileName.startsWith(Constants.GECKO_MODULE) && fileName.endsWith(".aar")) {
                String versionPart = fileName.substring(Constants.GECKO_MODULE.length() + 1, fileName.length() - 4)
                if (versionPart && !versionPart.isEmpty()) {
                    version = versionPart
                    logger.lifecycle("[GeckoArchPackageTask] Extracted version from filename: ${version}")
                    return version
                }
            }
        } catch (Exception e) {
            logger.warn("[GeckoArchPackageTask] Failed to extract version: ${e.message}")
        }
        
        logger.lifecycle("[GeckoArchPackageTask] Using default version: ${version}")
        return version
    }

    void extractAssetsFromAar(File aarFile, File targetDir) {
        logger.lifecycle("[GeckoArchPackageTask] Extracting assets from: ${aarFile.name}")

        new ZipFile(aarFile).withCloseable { zipFile ->
            zipFile.entries().each { ZipEntry entry ->
                if (!entry.name.startsWith("assets/") || entry.isDirectory()) {
                    return
                }

                def relativePath = entry.name.substring("assets/".length())
                def targetFile = new File(targetDir, relativePath)
                targetFile.parentFile.mkdirs()

                targetFile.withOutputStream { output ->
                    zipFile.getInputStream(entry).withStream { input ->
                        output << input
                    }
                }
            }
        }
    }

    void extractJniFromAar(File aarFile, File targetDir) {
        logger.lifecycle("[GeckoArchPackageTask] Extracting jni from: ${aarFile.name}")

        new ZipFile(aarFile).withCloseable { zipFile ->
            zipFile.entries().each { ZipEntry entry ->
                if (!entry.name.startsWith("jni/") || entry.isDirectory()) {
                    return
                }

                def targetFile = new File(targetDir, entry.name)
                targetFile.parentFile.mkdirs()

                targetFile.withOutputStream { output ->
                    zipFile.getInputStream(entry).withStream { input ->
                        output << input
                    }
                }
            }
        }
    }

    void mergeAssets(File geckoAssetsDir, File mergedAssetsDir) {
        logger.lifecycle("[GeckoArchPackageTask] Merging assets...")

        mergedAssetsDir.mkdirs()

        if (!geckoAssetsDir.exists()) {
            throw new GradleException("Gecko assets directory not found: ${geckoAssetsDir.absolutePath}")
        }
        project.copy {
            from geckoAssetsDir
            into mergedAssetsDir
        }

        // runtime_extensions 固定打入 base.apk。目录来自 getRuntimeExtensionsDir（插件在注册任务时注入），
        // 这里不再自己 findProject：输入声明与实际取值必须是同一个来源，否则会出现「输入看 A、打包用 B」。
        def localRuntimeExtensionsDir = runtimeExtensionsDir.get().asFile
        if (!localRuntimeExtensionsDir.exists() || !localRuntimeExtensionsDir.isDirectory()) {
            throw new GradleException("runtime_extensions directory not found: ${localRuntimeExtensionsDir.absolutePath}")
        }
        project.copy {
            from localRuntimeExtensionsDir
            into new File(mergedAssetsDir, "runtime_extensions")
        }

        logger.lifecycle("[GeckoArchPackageTask] Gecko assets merged with local runtime_extensions")
    }

    void createBaseApk(File assetsDir, File apkFile) {
        logger.lifecycle("[GeckoArchPackageTask] Creating base.apk...")

        apkFile.parentFile.mkdirs()

        new ZipOutputStream(new FileOutputStream(apkFile)).withCloseable { zos ->

            zos.putNextEntry(new ZipEntry("META-INF/"))
            zos.closeEntry()

            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0\nCreated-By: GeckoArchPackageTask\n".getBytes("UTF-8"))
            zos.closeEntry()

            if (assetsDir.exists()) {
                addDirectoryToZip(zos, assetsDir, "assets/")
            }

            zos.putNextEntry(new ZipEntry("AndroidManifest.xml"))
            zos.write([0x03, 0x00, 0x08, 0x00] as byte[])
            zos.closeEntry()
        }

        logger.lifecycle("[GeckoArchPackageTask] Created base.apk: ${apkFile.absolutePath}")
    }

    void addDirectoryToZip(ZipOutputStream zos, File dir, String basePath) {
        dir.listFiles()?.each { file ->
            if (file.isDirectory()) {
                def dirPath = basePath + file.name + "/"
                zos.putNextEntry(new ZipEntry(dirPath))
                zos.closeEntry()
                addDirectoryToZip(zos, file, dirPath)
            } else {
                def filePath = basePath + file.name
                zos.putNextEntry(new ZipEntry(filePath))
                file.withInputStream { input ->
                    zos << input
                }
                zos.closeEntry()
            }
        }
    }

    void prepareFinal7zStructureForArch(String architecture, File allJniDir, File baseApkFile, File finalJniDir, File finalResDir) {
        logger.lifecycle("[GeckoArchPackageTask] Preparing 7z structure for architecture: ${architecture}")

        finalJniDir.mkdirs()
        finalResDir.mkdirs()

        def sourceArchDir = new File(allJniDir, "jni/${architecture}")
        if (sourceArchDir.exists()) {
            def targetJniDir = new File(finalJniDir, "jni")
            targetJniDir.mkdirs()

            def targetArchDir = new File(targetJniDir, architecture)
            project.copy {
                from sourceArchDir
                into targetArchDir
            }
            logger.lifecycle("[GeckoArchPackageTask] Copied ${architecture} libraries to jni/${architecture}")
        } else {
            throw new GradleException("Architecture ${architecture} not found in source JNI directory")
        }

        def targetApkFile = new File(finalResDir, "base.apk")
        Files.copy(baseApkFile.toPath(), targetApkFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    void create7zPackage(File jniDir, File resDir, File output7zFile) {
        logger.lifecycle("[GeckoArchPackageTask] Creating 7z package...")

        if (output7zFile.exists()) {
            output7zFile.delete()
        }

        if (temp7zStructureDir.exists()) {
            project.delete(temp7zStructureDir)
        }
        temp7zStructureDir.mkdirs()

        if (!jniDir.exists()) {
            throw new GradleException("JNI directory not found: ${jniDir.absolutePath}")
        }
        // jniDir已经包含了jni/架构名结构，直接复制到临时目录根目录
        project.copy {
            from jniDir
            into temp7zStructureDir
        }

        if (!resDir.exists()) {
            throw new GradleException("Resource directory not found: ${resDir.absolutePath}")
        }
        project.copy {
            from resDir
            into new File(temp7zStructureDir, "res")
        }

        logger.lifecycle("[GeckoArchPackageTask] Compressing with LZMA2: dictionary ${SevenZWriter.DICT_SIZE_BYTES >> 20} MiB, fast bytes ${SevenZWriter.NICE_LEN}, solid, no BCJ filter")
        logger.lifecycle("  Note: LZMA2 encoding is single-threaded and needs heap in the same order as the dictionary; expect minutes")

        SevenZWriter.writeTree(temp7zStructureDir, output7zFile)

        if (!output7zFile.exists() || output7zFile.length() == 0) {
            throw new GradleException("7z packaging produced no output: ${output7zFile.absolutePath}")
        }

        logger.lifecycle("[GeckoArchPackageTask] Successfully created 7z package: ${output7zFile.absolutePath}")
    }

    private void cleanupOldCaches() {
        logger.lifecycle("[GeckoArchPackageTask] Cleaning up old caches...")
        
        try {
            if (workDir?.exists()) {
                logger.debug("[GeckoArchPackageTask] Cleaning work directory: ${workDir.absolutePath}")
                project.delete(workDir)
            }
            
            if (workDir?.parentFile?.exists()) {
                workDir.parentFile.listFiles()?.each { file ->
                    if (file.isDirectory() && file.name.startsWith(TEMP_7Z_STRUCTURE_PREFIX)) {
                        try {
                            logger.debug("[GeckoArchPackageTask] Cleaning old temp 7z structure directory: ${file.absolutePath}")
                            project.delete(file)
                        } catch (Exception e) {
                            logger.warn("[GeckoArchPackageTask] Failed to clean temp 7z structure directory ${file.absolutePath}: ${e.message}")
                        }
                    }
                }
            }
            
            // 只清理任务实际使用的目录，不清理系统或 Gradle 临时目录
            
            logger.debug("[GeckoArchPackageTask] Old caches cleanup completed")
            
        } catch (Exception e) {
            // 缓存清理失败不应该中断任务执行，只记录警告
            logger.warn("[GeckoArchPackageTask] Failed to cleanup old caches: ${e.message}")
        }
    }

}
