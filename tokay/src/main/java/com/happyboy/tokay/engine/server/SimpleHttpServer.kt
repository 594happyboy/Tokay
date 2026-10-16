package com.happyboy.tokay.engine.server

import android.util.Log
import com.happyboy.tokay.engine.server.mapping.MappingManager
import com.happyboy.tokay.engine.webview.interceptor.ServeBytesEntry
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 HTTP 服务器：静态资源映射（[MappingManager]）+ `/dyn/` 动态字节登记。
 * 仅支持 GET；只监听 127.0.0.1，不对局域网暴露；大文件分块转发。
 */
class SimpleHttpServer(
    private val mappingManager: MappingManager,
    private val port: Int, // 固定端口，避免缓存失效。使用更冷门的端口，降低碰撞概率
    private val dynamicProvider: ((String) -> ServeBytesEntry?)? = null
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(false)
    var actualPort: Int = -1
        private set

    companion object {
        private const val TAG = "SimpleHttpServer"

        private val FALLBACK_PORTS = listOf(39583, 47291, 58437, 69341, 78453)

        /**
         * 仅监听回环地址：该服务器只服务同进程内的 GeckoView/WebView 资源加载，
         * 不应被同一局域网内的其它主机访问。
         */
        private const val BIND_ADDRESS = "127.0.0.1"

        private const val BACKLOG = 50
    }

    fun start() {
        if (isRunning.get()) {
            return
        }
        
        val portsToTry = listOf(port) + FALLBACK_PORTS
        var lastException: Exception? = null
        val bindAddress = InetAddress.getByName(BIND_ADDRESS)
        
        for (tryPort in portsToTry) {
            try {
                serverSocket = ServerSocket(tryPort, BACKLOG, bindAddress)
                actualPort = serverSocket!!.localPort
                isRunning.set(true)
                Log.d(TAG, "SimpleHttpServer 启动成功，使用固定端口: $actualPort")
                break
            } catch (e: Exception) {
                lastException = e
                Log.d(TAG, "端口 $tryPort 被占用，尝试下一个端口...")
                continue
            }
        }
        
        if (!isRunning.get()) {
            try {
                serverSocket = ServerSocket(0, BACKLOG, bindAddress) // 端口0表示随机分配
                actualPort = serverSocket!!.localPort
                isRunning.set(true)
                Log.d(TAG, "SimpleHttpServer 启动成功，使用随机端口: $actualPort (所有固定端口都被占用)")
            } catch (e: Exception) {
                val errorMsg = "所有固定端口和随机端口都无法分配，无法启动服务器"
                Log.e(TAG, errorMsg)
                throw Exception(errorMsg, lastException)
            }
        }

        try {
            executor.execute {
                try {
                    while (isRunning.get() && !serverSocket!!.isClosed) {
                        try {
                            val client = serverSocket!!.accept()
                            executor.execute { handleClient(client) }
                        } catch (e: SocketException) {
                            if (isRunning.get()) {
                                Log.w(TAG, "接受连接失败", e)
                            }
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "接受连接异常", e)
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "服务器循环异常退出", e)
                } finally {
                    isRunning.set(false)
                }
            }
        } catch (e: Exception) {
            isRunning.set(false)
            throw e
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭服务器失败", e)
        }
        executor.shutdownNow()
    }

    fun isRunning(): Boolean = isRunning.get()

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = BufferedOutputStream(socket.getOutputStream())

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val pathWithQuery = parts[1]
                val path = pathWithQuery.substringBefore('?')

                if (method != "GET") {
                    writeResponse(
                        writer,
                        405,
                        "Method Not Allowed",
                        "text/plain",
                        emptyMap(),
                        "Only GET supported".toByteArray()
                    )
                    return
                }

                if (path.startsWith("/dyn/")) {
                    handleDynamicRequest(writer, path.removePrefix("/dyn/"))
                } else {
                    val result = mappingManager.getFile(path)
                    if (result != null) {
                        writeStreamResponse(writer, 200, "OK", result.mimeType, emptyMap(), result.inputStream)
                    } else {
                        writeResponse(
                            writer,
                            404,
                            "Not Found",
                            "text/plain",
                            emptyMap(),
                            "File Not Found".toByteArray()
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "处理请求失败", e)
            }
        }
    }

    private fun handleDynamicRequest(writer: OutputStream, rawKey: String) {
        val provider = dynamicProvider ?: run {
            writeResponse(writer, 404, "Not Found", "text/plain", emptyMap(), "Not Found".toByteArray())
            return
        }

        val key = try {
            URLDecoder.decode(rawKey, Charsets.UTF_8.name())
        } catch (_: Exception) {
            rawKey
        }.trim()

        val entry = provider(key) ?: run {
            writeResponse(writer, 404, "Not Found", "text/plain", emptyMap(), "Not Found".toByteArray())
            return
        }

        val headers = mutableMapOf<String, String>()
        headers.putAll(entry.headers)
        if (!headers.containsKey("Cache-Control")) {
            headers["Cache-Control"] = "no-store"
        }

        writeResponse(
            writer,
            entry.statusCode,
            "OK",
            entry.mimeType,
            headers,
            entry.bytes
        )
    }

    private fun writeResponse(
        writer: OutputStream,
        code: Int,
        status: String,
        mimeType: String,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        try {
            writer.write("HTTP/1.1 $code $status\r\n".toByteArray())
            writer.write("Content-Type: $mimeType\r\n".toByteArray())
            for ((k, v) in headers) {
                writer.write("$k: $v\r\n".toByteArray())
            }
            writer.write("Content-Length: ${body.size}\r\n".toByteArray())
            writer.write("Connection: close\r\n".toByteArray())
            writer.write("\r\n".toByteArray())
            writer.write(body)
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "写响应失败", e)
        }
    }

    private fun writeStreamResponse(
        writer: OutputStream,
        code: Int,
        status: String,
        mimeType: String,
        headers: Map<String, String>,
        inputStream: InputStream
    ) {
        try {
            val buffer = ByteArray(8192)
            var bytesRead: Int

            writer.write("HTTP/1.1 $code $status\r\n".toByteArray())
            writer.write("Content-Type: $mimeType\r\n".toByteArray())
            for ((k, v) in headers) {
                writer.write("$k: $v\r\n".toByteArray())
            }
            writer.write("Transfer-Encoding: chunked\r\n".toByteArray())
            writer.write("Connection: close\r\n".toByteArray())
            writer.write("\r\n".toByteArray())

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                writer.write(String.format("%X\r\n", bytesRead).toByteArray())
                writer.write(buffer, 0, bytesRead)
                writer.write("\r\n".toByteArray())
            }
            writer.write("0\r\n\r\n".toByteArray())
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "写流式响应失败", e)
        } finally {
            try {
                inputStream.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭响应流失败", e)
            }
        }
    }
}
