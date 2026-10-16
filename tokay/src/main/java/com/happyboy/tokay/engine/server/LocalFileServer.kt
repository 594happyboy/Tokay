package com.happyboy.tokay.engine.server

import android.content.Context
import android.util.Log
import com.happyboy.tokay.engine.server.mapping.AssetsMappingModule
import com.happyboy.tokay.engine.server.mapping.ContentUriMappingModule
import com.happyboy.tokay.engine.server.mapping.DirectoryMappingModule
import com.happyboy.tokay.engine.server.mapping.MappingManager
import com.happyboy.tokay.engine.webview.interceptor.DataPolicy
import com.happyboy.tokay.engine.webview.interceptor.InterceptionLimits
import com.happyboy.tokay.engine.webview.interceptor.ServeBytesEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程级本地 HTTP 服务（`/assets/`、`/files/`、`/dyn/`、`/content/`）。
 * 适配器按引用计数共同持有（构造期 [acquire]、销毁时 [release]）：只有最后一个持有者释放才 shutdown
 * 并清空 `/dyn` 登记；`buildBaseUrl` 一族只读、不隐式启动，因此切换适配器不会关掉别人的服务。
 */
object LocalFileServer {
    private const val TAG = "LocalFileServer"
    private const val LOCALHOST = "127.0.0.1"
    private const val FIXED_PORT = 28471

    /** 保护服务启停与引用计数。 */
    private val lock = Any()
    private val refCounter = RefCounter()

    @Volatile
    private var server: SimpleHttpServer? = null

    @Volatile
    private var currentPort: Int = -1

    private data class ServeBytesRecord(
        val entry: ServeBytesEntry,
        val expiresAtMillis: Long?
    )

    private val serveBytesRegistry = ConcurrentHashMap<String, ServeBytesRecord>()
    private val serveBytesTotalSizeBytes = AtomicLong(0L)

    /**
     * 登记一个持有者，必要时启动服务。返回的令牌必须在销毁时原样交给 [release]；
     * 启动失败会抛出，且不留下任何持有登记。
     */
    fun acquire(context: Context): Long = synchronized(lock) {
        if (server?.isRunning() != true) {
            startLocked(context)
        }
        val token = refCounter.acquire()
        // 持有者数量是「服务该不该活着」的唯一依据。
        Log.d(TAG, "acquire: token=$token holders=${refCounter.current()}")
        token
    }

    /** 释放一个持有者；未知令牌（重复销毁、服务已停机后的残留）是 no-op，不会关掉别人的服务。 */
    fun release(token: Long) {
        val shouldStop = synchronized(lock) { refCounter.release(token) }
        Log.d(TAG, "release: token=$token holders=${refCounter.current()} shouldStop=$shouldStop")
        if (shouldStop) {
            shutdownInternal()
        }
    }

    /** 调用方必须已持有 [lock]。 */
    private fun startLocked(context: Context) {
        val manager = MappingManager().apply {
            addModule(AssetsMappingModule(context.assets, "/assets/"))
            addModule(DirectoryMappingModule(context.filesDir, "/${context.filesDir.name}/"))
            // content:// 流式直读：不落盘、不进 /dyn，直接把 ContentProvider 的流交给响应体。
            addModule(ContentUriMappingModule(context))
        }
        val newServer = SimpleHttpServer(
            mappingManager = manager,
            port = FIXED_PORT,
            dynamicProvider = { key ->
                getServeBytesOrNull(key)
            }
        )
        // start() 抛异常时赋值不发生，服务保持「未启动」，调用方可以重试。
        newServer.start()

        server = newServer
        currentPort = newServer.actualPort
    }

    private fun shutdownInternal() {
        val s = synchronized(lock) {
            val closing = server
            server = null
            currentPort = -1
            closing
        }
        try {
            s?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止本地服务失败", e)
        }
        clearServeBytes()
    }

    /** 本地服务基址。**只读**：未启动时抛异常，而不是静默启动新服务把端口与 `/dyn` 搅乱。 */
    @Suppress("UNUSED_PARAMETER")
    fun buildBaseUrl(context: Context): String {
        val port = currentPort
        check(port > 0) {
            "LocalFileServer 未运行：本地 URL 只能在持有者（适配器）acquire 之后构造。" +
                "如果是在适配器构造期调用，请确认 acquire 早于一切 URL 构造。"
        }
        return "http://$LOCALHOST:$port"
    }

    fun buildDynamicUrl(context: Context, key: String): String {
        return "${buildBaseUrl(context)}/dyn/${key.trim()}"
    }

    private fun registerServeBytes(key: String, entry: ServeBytesEntry, limits: InterceptionLimits): Boolean {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return false
        if (!normalizedKey.matches(Regex("^[A-Za-z0-9._-]{1,128}$"))) return false

        val size = entry.bytes.size.toLong()
        if (size <= 0) return false
        if (size > limits.maxServeBytesEntrySizeBytes) return false

        while (true) {
            val current = serveBytesTotalSizeBytes.get()
            val existing = serveBytesRegistry[normalizedKey]
            val existingSize = existing?.entry?.bytes?.size?.toLong() ?: 0L
            val next = current - existingSize + size
            if (next > limits.maxServeBytesTotalSizeBytes) return false
            if (serveBytesTotalSizeBytes.compareAndSet(current, next)) break
        }

        serveBytesRegistry[normalizedKey] = ServeBytesRecord(entry = entry, expiresAtMillis = null)
        return true
    }

    fun putServeBytes(entry: ServeBytesEntry, policy: DataPolicy, limits: InterceptionLimits): String? {
        val key = generateServeBytesKey()
        val normalizedKey = key.trim()

        val expiresAtMillis = when (policy) {
            is DataPolicy.Session -> null
            is DataPolicy.OneShot -> null
            is DataPolicy.Ttl -> System.currentTimeMillis() + policy.ttlMillis
        }

        val effectiveEntry = when (policy) {
            is DataPolicy.OneShot -> entry.copy(oneShot = true)
            else -> entry
        }

        val ok = registerServeBytes(normalizedKey, effectiveEntry, limits)
        if (!ok) return null

        if (expiresAtMillis != null) {
            serveBytesRegistry[normalizedKey] = ServeBytesRecord(entry = effectiveEntry, expiresAtMillis = expiresAtMillis)
        }

        return normalizedKey
    }

    private fun unregisterServeBytes(key: String) {
        val normalizedKey = key.trim()
        val removed = serveBytesRegistry.remove(normalizedKey) ?: return
        serveBytesTotalSizeBytes.addAndGet(-removed.entry.bytes.size.toLong())
    }

    private fun clearServeBytes() {
        serveBytesRegistry.clear()
        serveBytesTotalSizeBytes.set(0L)
    }

    fun getServeBytesOrNull(key: String): ServeBytesEntry? {
        val normalizedKey = key.trim()
        val record = serveBytesRegistry[normalizedKey] ?: return null

        val expiresAt = record.expiresAtMillis
        if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
            unregisterServeBytes(normalizedKey)
            return null
        }

        val entry = record.entry
        if (entry.oneShot) {
            unregisterServeBytes(normalizedKey)
        }
        return entry
    }

    private fun generateServeBytesKey(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "")
    }
}
