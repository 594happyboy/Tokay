package com.happyboy.tokay.installer.extractor

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.installer.validator.FileValidator
import com.happyboy.tokay.archive.SevenZip
import com.happyboy.tokay.archive.api.ExtractOptions as SzExtractOptions
import com.happyboy.tokay.archive.api.ExtractProgress as SzExtractProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/** 7z 解压器：协程 Flow 回报进度，支持取消。 */
internal class SevenZipExtractor {

    private var currentJob: Job? = null
    private val validator = FileValidator()

    fun extract(
        sourceFile: File,
        targetDir: File
    ): Flow<ExtractResult> = callbackFlow {
        currentJob = coroutineContext[Job]
        val ch = channel

        withContext(Dispatchers.IO) {
            try {
                Log.d(LOG_TAG, "Start extract ${sourceFile.name} -> ${targetDir.absolutePath}")

                if (!sourceFile.exists()) {
                    send(ExtractResult.Failure(Exception("Source file not found: ${sourceFile.absolutePath}")))
                    ch.close()
                    return@withContext
                }

                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }

                val validation = validator.validateExtractedFiles(targetDir)
                if (validation.isValid()) {
                    Log.d(LOG_TAG, "Already extracted and valid, skip")
                    send(ExtractResult.Success(targetDir))
                    ch.close()
                    return@withContext
                }

                var lastUpdate = 0L
                val options = SzExtractOptions()
                val result = SevenZip.extract(
                    source = sourceFile,
                    targetDir = targetDir,
                    options = options
                ) { p: SzExtractProgress ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 100) {
                        val totalEntries = p.totalEntries
                        val ratio = if (totalEntries != null && totalEntries > 0) {
                            (p.entriesProcessed.toFloat() / totalEntries).coerceIn(0f, 1f)
                        } else {
                            val compSize = sourceFile.length().toFloat()
                            if (compSize > 0f) (p.bytesProcessed.toFloat() / compSize).coerceIn(0f, 1f) else 0f
                        }
                        val totalBytesForUi = if (totalEntries == null) sourceFile.length() else -1L
                        trySend(
                            ExtractResult.Progress(
                                ExtractProgress(
                                    progress = ratio,
                                    extractedBytes = p.bytesProcessed,
                                    totalBytes = totalBytesForUi,
                                    currentFileName = p.currentEntry ?: ""
                                )
                            )
                        )
                        lastUpdate = now
                    }
                }

                if (result.canceled) {
                    Log.d(LOG_TAG, "Extraction canceled")
                    send(ExtractResult.Failure(Exception("Extraction canceled")))
                    ch.close()
                    return@withContext
                }
                
                // 补发一次 100% 进度：解压过快时 UI 收不到满格
                send(
                    ExtractResult.Progress(
                        ExtractProgress(
                            progress = 1f,
                            extractedBytes = sourceFile.length(),
                            totalBytes = sourceFile.length(),
                            currentFileName = ""
                        )
                    )
                )

                // 只要有条目没写出来，这次解压就是**不完整**的：不写成功标志、不报成功。
                // 归档层对单条目失败是"计数不抛"（磁盘满、Zip Slip、IO 错都只 ++ 后继续），
                // 在这里放行等于把缺文件的目录宣布成装好了——而它会被复用判定反复接受，设备上修不回来。
                if (result.failureCount > 0) {
                    val detail = result.errors.take(3).joinToString("; ") { it.message }
                    Log.e(
                        LOG_TAG,
                        "解压不完整：失败 ${result.failureCount} 项、成功 ${result.successCount} 项$detail"
                    )
                    send(
                        ExtractResult.Failure(
                            Exception(
                                "解压不完整：失败 ${result.failureCount} 项、成功 ${result.successCount} 项$detail"
                            )
                        )
                    )
                    ch.close()
                    return@withContext
                }

                runCatching { validator.createExtractSuccessFlag(targetDir) }

                Log.d(LOG_TAG, "Extract completed: ${targetDir.absolutePath}, success=${result.successCount}, failure=${result.failureCount}")
                send(ExtractResult.Success(targetDir))
                ch.close()

            } catch (e: Exception) {
                Log.e(LOG_TAG, "解压过程出错", e)
                send(ExtractResult.Failure(e))
                ch.close()
            }
        }

        awaitClose {
            Log.d(LOG_TAG, "解压 Flow 关闭")
        }
    }

    fun cancel() {
        currentJob?.cancel()
        Log.d(LOG_TAG, "解压已取消")
    }
}

sealed class ExtractResult {
    data class Progress(val progress: ExtractProgress) : ExtractResult()

    data class Success(val outputDir: File) : ExtractResult()

    data class Failure(val error: Throwable) : ExtractResult()
}
