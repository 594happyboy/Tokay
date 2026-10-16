package com.happyboy.tokay.installer.validator

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.infrastructure.path.GeckoKernelLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** 文件完整性验证：sha256 校验与解压产物完整性检查。 */
internal class FileValidator {
    
    suspend fun validateDownloadedFile(
        file: File,
        expectedSha256: String? = null
    ): ValidationResult = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext ValidationResult.NotFound(file)
            }
            
            if (file.length() == 0L) {
                return@withContext ValidationResult.Invalid(
                    file = file,
                    reason = "文件大小为 0"
                )
            }
            
            if (!expectedSha256.isNullOrBlank()) {
                val actualSha256 = calculateSha256(file)
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    Log.w(LOG_TAG, "SHA-256 校验失败 - 期望: $expectedSha256, 实际: $actualSha256")
                    return@withContext ValidationResult.Invalid(
                        file = file,
                        reason = "SHA-256 校验失败"
                    )
                }
                Log.d(LOG_TAG, "SHA-256 校验通过: $actualSha256")
            }
            
            ValidationResult.Valid(file)
            
        } catch (e: Exception) {
            Log.e(LOG_TAG, "文件验证出错", e)
            ValidationResult.Invalid(
                file = file,
                reason = "验证过程出错: ${e.message}",
                error = e
            )
        }
    }
    
    /**
     * 解压结果校验：完整可用的判定收敛在 [GeckoKernelLayout.isComplete]（解压标志 + `res/base.apk` +
     * `jni/<abi>`）。**不能只看标志与"目录非空"**——标志正是解压器刚落下的，那个条件恒真，
     * 缺文件的内核会被当成装好了反复复用。
     */
    suspend fun validateExtractedFiles(targetDir: File): ValidationResult = withContext(Dispatchers.IO) {
        try {
            if (!targetDir.exists()) {
                return@withContext ValidationResult.NotFound(targetDir)
            }
            
            if (!GeckoKernelLayout.isComplete(targetDir)) {
                return@withContext ValidationResult.Invalid(
                    file = targetDir,
                    reason = "解压产物不完整：缺少 ${GeckoKernelLayout.unzipSuccessFlag(targetDir).name} / res/base.apk / jni/<abi> 之一"
                )
            }
            
            Log.d(LOG_TAG, "解压产物完整: ${targetDir.absolutePath}")
            ValidationResult.Valid(targetDir)
            
        } catch (e: Exception) {
            Log.e(LOG_TAG, "解压文件验证出错", e)
            ValidationResult.Invalid(
                file = targetDir,
                reason = "验证过程出错: ${e.message}",
                error = e
            )
        }
    }
    
    suspend fun calculateSha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    
    suspend fun createExtractSuccessFlag(targetDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val flagFile = GeckoKernelLayout.unzipSuccessFlag(targetDir)
            flagFile.createNewFile()
            Log.d(LOG_TAG, "创建解压成功标志文件: ${flagFile.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "创建标志文件失败", e)
            Result.failure(e)
        }
    }
}

