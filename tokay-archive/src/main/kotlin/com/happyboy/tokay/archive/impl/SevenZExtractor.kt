package com.happyboy.tokay.archive.impl

import com.happyboy.tokay.archive.api.ExtractError
import com.happyboy.tokay.archive.api.ExtractOptions
import com.happyboy.tokay.archive.api.ExtractProgress
import com.happyboy.tokay.archive.api.ExtractResult
import com.happyboy.tokay.archive.util.FileOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZFileOptions
import java.io.File
import java.io.FileOutputStream

/**
 * 7z 解压（Apache Commons Compress `SevenZFile`）：需要随机访问，因此仅支持从文件路径读取；
 * 支持取消与进度回调，路径校验防 Zip Slip。
 */
object SevenZExtractor {
    suspend fun extract(
        source: File,
        targetDir: File,
        options: ExtractOptions,
        onProgress: (ExtractProgress) -> Unit
    ): ExtractResult {
        var success = 0
        var failures = 0
        val errors = mutableListOf<ExtractError>()
        var bytesProcessed = 0L
        var entriesProcessed = 0

        val start = System.nanoTime()
        val ctx = currentCoroutineContext()

        return try {
            FileOps.ensureDir(targetDir)

            // 该重载与其 SevenZFileOptions 已废弃但刻意不迁：模块没有测试源集、本机也没有 7z，
            // 改完无法验证（同 ZipExtractor 的决定）。
            SevenZFile(source, SevenZFileOptions.DEFAULT).use { sevenZ ->
                val totalEntries: Int? = null // 7z 流式遍历时通常无法预知总条目数

                fun report(entryName: String?) {
                    onProgress(
                        ExtractProgress(
                            entriesProcessed = entriesProcessed,
                            totalEntries = totalEntries,
                            bytesProcessed = bytesProcessed,
                            currentEntry = entryName
                        )
                    )
                }

                report(null)

                while (true) {
                    ctx.ensureActive()
                    val entry = sevenZ.nextEntry ?: break
                    val name = entry.name
                    try {
                        if (entry.isDirectory) {
                            val dir = FileOps.safeResolve(targetDir, name)
                            FileOps.ensureDir(dir)
                            success++
                            entriesProcessed++
                            report(name)
                            continue
                        }
                        val outFile = FileOps.safeResolve(targetDir, name)
                        val parent = outFile.parentFile ?: targetDir
                        FileOps.ensureDir(parent)

                        if (outFile.exists() && options.overwrite == com.happyboy.tokay.archive.api.OverwriteMode.NEVER) {
                            entriesProcessed++
                            success++
                            report(name)
                            continue
                        }

                        FileOutputStream(outFile, false).use { output ->
                            // SevenZFile 充当当前条目的输入源
                            val bufferSize = options.bufferSize
                            val buffer = ByteArray(bufferSize)
                            while (true) {
                                ctx.ensureActive()
                                val read = sevenZ.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                bytesProcessed += read
                                onProgress(
                                    ExtractProgress(
                                        entriesProcessed = entriesProcessed,
                                        totalEntries = totalEntries,
                                        bytesProcessed = bytesProcessed,
                                        currentEntry = name
                                    )
                                )
                            }
                            output.flush()
                        }
                        // 7z 的时间戳支持在不同实现上存在差异，此处不强制设置
                        success++
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (se: SecurityException) {
                        failures++
                        errors.add(ExtractError.SecurityViolation(name, se))
                    } catch (t: Throwable) {
                        failures++
                        errors.add(ExtractError.Io(name, t))
                    } finally {
                        entriesProcessed++
                        report(name)
                    }
                }
            }

            ExtractResult(
                successCount = success,
                failureCount = failures,
                errors = errors,
                canceled = false,
                durationMs = (System.nanoTime() - start) / 1_000_000L
            )
        } catch (ce: CancellationException) {
            ExtractResult(
                successCount = success,
                failureCount = failures,
                errors = errors + ExtractError.Canceled(),
                canceled = true,
                durationMs = (System.nanoTime() - start) / 1_000_000L
            )
        } catch (t: Throwable) {
            failures++
            val fatal = if (t is SecurityException) ExtractError.SecurityViolation(source.name, t)
                        else ExtractError.Corrupted(source.absolutePath, t)
            ExtractResult(
                successCount = success,
                failureCount = failures,
                errors = errors + fatal,
                canceled = false,
                durationMs = (System.nanoTime() - start) / 1_000_000L
            )
        }
    }
}
