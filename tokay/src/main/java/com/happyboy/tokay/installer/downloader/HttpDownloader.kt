package com.happyboy.tokay.installer.downloader

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.installer.validator.FileValidator
import com.happyboy.tokay.infrastructure.logging.SdkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** HTTP 下载器：协程 Flow 回调进度，支持断点续传与 sha256 校验。 */
internal class HttpDownloader {
    
    private var currentJob: Job? = null
    private val validator = FileValidator()
    
    fun download(
        url: String,
        targetFile: File,
        expectedSha256: String?
    ): Flow<DownloadResult> = callbackFlow {
        currentJob = coroutineContext[Job]
        
        withContext(Dispatchers.IO) {
            try {
                // 下载 URL 是敏感数据，必须受日志开关管辖（裸 Log.d 在 release 下照样输出）。
                SdkLogger.logNetworkRequest(url, method = "GET", status = "download-start")
                
                targetFile.parentFile?.mkdirs()
                
                // 已存在且校验通过就跳过下载；无效则删掉重下
                if (targetFile.exists()) {
                    val validationResult = validator.validateDownloadedFile(targetFile, expectedSha256)
                    if (validationResult.isValid()) {
                        Log.d(LOG_TAG, "文件已存在且有效，跳过下载")
                        send(DownloadResult.Success(targetFile))
                        channel.close()
                        return@withContext
                    } else {
                        Log.d(LOG_TAG, "现有文件无效，重新下载")
                        targetFile.delete()
                    }
                }
                
                // 断点续传：`.part` 已有多长就从多长开始
                val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
                var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L
                
                val connection = setupConnection(url, downloadedBytes)
                
                val responseCode = connection.responseCode
                if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                    send(DownloadResult.Failure(
                        Exception("服务器返回 HTTP $responseCode: ${connection.responseMessage}")
                    ))
                    channel.close()
                    return@withContext
                }
                
                val totalBytes = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    val contentRange = connection.getHeaderField("Content-Range")
                    contentRange?.substringAfter('/')?.toLongOrNull() ?: -1L
                } else {
                    connection.contentLength.toLong() + downloadedBytes
                }
                
                val startTime = System.currentTimeMillis()
                var lastProgressUpdate = 0L
                
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile, downloadedBytes > 0).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        
                        while (isActive) {
                            bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            // 进度限流：每 100ms 才发一次
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastProgressUpdate >= 100) {
                                val progress = if (totalBytes > 0) {
                                    downloadedBytes.toFloat() / totalBytes
                                } else {
                                    0f
                                }
                                
                                val elapsedMs = currentTime - startTime
                                val speedBps = if (elapsedMs > 0) {
                                    (downloadedBytes * 1000 / elapsedMs)
                                } else {
                                    0L
                                }
                                
                                val estimatedRemainingMs = if (speedBps > 0 && totalBytes > 0) {
                                    ((totalBytes - downloadedBytes) * 1000 / speedBps)
                                } else {
                                    -1L
                                }
                                
                                send(DownloadResult.Progress(
                                    DownloadProgress(
                                        progress = progress,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        speedBps = speedBps,
                                        estimatedRemainingMs = estimatedRemainingMs
                                    )
                                ))
                                
                                lastProgressUpdate = currentTime
                            }
                        }
                    }
                }
                
                connection.disconnect()
                
                if (!isActive) {
                    Log.d(LOG_TAG, "下载被取消")
                    send(DownloadResult.Failure(Exception("下载被取消")))
                    channel.close()
                    return@withContext
                }
                
                if (!tempFile.renameTo(targetFile)) {
                    send(DownloadResult.Failure(Exception("重命名下载文件失败")))
                    channel.close()
                    return@withContext
                }
                
                // sha256 在下载 Flow 内部校验，所以被篡改的包报出来的阶段是 DOWNLOADING 而非 VALIDATING
                val validationResult = validator.validateDownloadedFile(targetFile, expectedSha256)
                if (!validationResult.isValid()) {
                    targetFile.delete()
                    send(DownloadResult.Failure(Exception("文件验证失败")))
                    channel.close()
                    return@withContext
                }
                
                Log.d(LOG_TAG, "下载完成: ${targetFile.absolutePath}")
                send(DownloadResult.Success(targetFile))
                channel.close()
                
            } catch (e: Exception) {
                Log.e(LOG_TAG, "下载失败", e)
                send(DownloadResult.Failure(e))
                channel.close()
            }
        }
        
        awaitClose {
            Log.d(LOG_TAG, "下载 Flow 关闭")
        }
    }
    
    fun cancel() {
        currentJob?.cancel()
        Log.d(LOG_TAG, "下载已取消")
    }
    
    private fun setupConnection(url: String, resumeFrom: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 30000
            readTimeout = 30000
            
            if (resumeFrom > 0) {
                setRequestProperty("Range", "bytes=$resumeFrom-")
                Log.d(LOG_TAG, "断点续传，从 $resumeFrom 字节开始")
            }
            
            connect()
        }
        return connection
    }
}

sealed class DownloadResult {
    data class Progress(val progress: DownloadProgress) : DownloadResult()

    data class Success(val file: File) : DownloadResult()

    data class Failure(val error: Throwable) : DownloadResult()
}

