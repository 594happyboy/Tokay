package com.happyboy.tokay.archive.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.io.InputStream
import java.io.OutputStream

/**
 * 以可取消的方式复制输入流到输出流，并按字节回调增量统计：循环中检查协程上下文以支持取消，
 * 周期性 `yield()` 避免长时间占用调度器线程；调用方负责在 IO 线程（Dispatchers.IO）下运行。
 */
suspend fun copyStream(
    input: InputStream,
    output: OutputStream,
    bufferSize: Int,
    onBytes: (Long) -> Unit
) {
    val ctx = currentCoroutineContext()
    val buffer = ByteArray(bufferSize)
    var bytesSinceYield = 0
    while (true) {
        ctx.ensureActive()
        val read = input.read(buffer)
        if (read <= 0) break
        output.write(buffer, 0, read)
        onBytes(read.toLong())
        bytesSinceYield += read
        if (bytesSinceYield >= (1 shl 20)) { // 每处理约 1MB 主动让出调度器
            yield()
            bytesSinceYield = 0
        }
    }
    output.flush()
}
