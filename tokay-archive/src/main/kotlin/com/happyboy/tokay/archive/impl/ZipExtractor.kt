package com.happyboy.tokay.archive.impl

import com.happyboy.tokay.archive.api.ExtractError
import com.happyboy.tokay.archive.api.ExtractOptions
import com.happyboy.tokay.archive.api.ExtractProgress
import com.happyboy.tokay.archive.api.ExtractResult
import com.happyboy.tokay.archive.api.OverwriteMode
import com.happyboy.tokay.archive.util.FileOps
import com.happyboy.tokay.archive.util.copyStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

/**
 * Zip 解压（Apache Commons Compress）：逐条目流式读取，支持取消与进度回调；
 * 路径校验防 Zip Slip，并拒绝符号链接。
 */
object ZipExtractor {
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

            // 此构造接收文件名字符串（`options.charset`）；已废弃但刻意不迁：模块没有测试源集、
            // 本机也没有 7z 命令行，改完无法验证。
            ZipFile(source, options.charset.name()).use { zip ->
                val entryList: List<ZipArchiveEntry> = Collections.list(zip.entries)
                val totalEntries = entryList.size

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

                for (entry in entryList) {
                    ctx.ensureActive()
                    val name = entry.name
                    try {
                        // 拒绝符号链接，避免潜在目录逃逸
                        if (entry.isUnixSymlink) {
                            failures++
                            errors.add(ExtractError.SecurityViolation(name))
                            continue
                        }
                        val isDir = entry.isDirectory || name.endsWith("/")
                        val outFile = FileOps.safeResolve(targetDir, name)
                        if (isDir) {
                            FileOps.ensureDir(outFile)
                            success++
                            entriesProcessed++
                            report(name)
                            continue
                        } else {
                            val parent = outFile.parentFile ?: targetDir
                            FileOps.ensureDir(parent)
                        }

                        if (outFile.exists() && options.overwrite == OverwriteMode.NEVER) {
                            entriesProcessed++
                            success++
                            report(name)
                            continue
                        }

                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile, false).use { output ->
                                copyStream(input, output, options.bufferSize) { delta ->
                                    bytesProcessed += delta
                                    onProgress(
                                        ExtractProgress(
                                            entriesProcessed = entriesProcessed,
                                            totalEntries = totalEntries,
                                            bytesProcessed = bytesProcessed,
                                            currentEntry = name
                                        )
                                    )
                                }
                            }
                        }
                        if (options.preserveTimestamps) {
                            val time = entry.lastModifiedDate?.time ?: -1L
                            if (time > 0) outFile.setLastModified(time)
                        }
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
