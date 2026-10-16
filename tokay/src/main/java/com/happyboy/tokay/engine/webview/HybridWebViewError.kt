package com.happyboy.tokay.engine.webview

/**
 * 加载链路的统一错误模型：把失败归到一个**阶段**（[Phase]），并给一个**非空的稳定错误码**（[code]）。
 *
 * `code` 是**宿主侧的自有编码**，与内核原生错误码不是同一套，**不要互相映射**；内核原生码另有
 * [nativeCode] 字段，只用于排查。[Phase] 的每个取值都有真实生产者，内核安装失败**不在这里**上报
 * ——那是 `GeckoSdk.installState` 的职责。本模型刻意不携带 Android 依赖，[HybridWebViewErrorMapper]
 * 可以在纯 JVM 单测里跑。
 */
data class HybridWebViewError(
    val phase: Phase,
    /** 稳定、非空的错误码；同一阶段内可区分具体失败原因。 */
    val code: String,
    /** 人类可读的描述，来自内核，不做文案承诺。 */
    val message: String? = null,
    /** 相关 URL（统一为 Hybrid 语义，若可得）。 */
    val url: String? = null,
    /** HTTP 状态码，仅 [Phase.HTTP] 有值。 */
    val httpStatusCode: Int? = null,
    /** 内核原生错误码（Gecko `WebRequestError.code` / `WebResourceError` 码），仅用于排查。 */
    val nativeCode: Int? = null,
    /** 原始异常，可能为 null（如超时类失败）。 */
    val cause: Throwable? = null
) {

    enum class Phase {
        /** 主文档没加载出来（Gecko 导航错误、系统内核主资源错误）。 */
        LOAD,

        /** 主文档返回 4xx/5xx（系统内核 `onReceivedHttpError`）。 */
        HTTP,

        /** 证书/SSL 错误（系统内核 `onReceivedSslError`）。 */
        SSL
    }

    /** 单行稳定文本，专供 logcat 真机核对使用（字段名固定、顺序固定）。 */
    fun describe(): String = buildString {
        append("phase=").append(phase.name)
        append(" code=").append(code)
        message?.let { append(" message=").append(it) }
        url?.let { append(" url=").append(it) }
        httpStatusCode?.let { append(" httpStatus=").append(it) }
        nativeCode?.let { append(" nativeCode=").append(it) }
    }

    override fun toString(): String = "HybridWebViewError(${describe()})"
}

/**
 * 把内核链路上的异构失败输入映射成 [HybridWebViewError]；全部是纯函数（无 Android 依赖、无副作用），
 * 可在 `src/test/java` 里直接断言。
 */
object HybridWebViewErrorMapper {

    const val CODE_LOAD_ERROR = "LOAD_ERROR"
    const val CODE_SSL_ERROR = "SSL_ERROR"
    const val CODE_UNKNOWN = "UNKNOWN_ERROR"

    /** HTTP 错误码形如 `HTTP_404`；状态码非法时退回 [CODE_UNKNOWN]。 */
    fun httpCode(statusCode: Int): String =
        if (statusCode > 0) "HTTP_$statusCode" else CODE_UNKNOWN

    /**
     * 主文档加载失败（Gecko `NavigationDelegate.onLoadError`、系统内核 `onReceivedError`）。
     *
     * `nativeCode`（Gecko `WebRequestError.code` / `WebResourceError.errorCode`）不参与 `code` 生成，
     * 避免把内核码体系混进宿主编码。
     */
    fun loadError(
        url: String?,
        description: String?,
        nativeCode: Int? = null,
        cause: Throwable? = null
    ): HybridWebViewError = HybridWebViewError(
        phase = HybridWebViewError.Phase.LOAD,
        code = CODE_LOAD_ERROR,
        message = description,
        url = url,
        nativeCode = nativeCode,
        cause = cause
    )

    /** HTTP 层错误（系统内核 `onReceivedHttpError`）。 */
    fun httpError(
        statusCode: Int,
        url: String?,
        description: String? = null
    ): HybridWebViewError = HybridWebViewError(
        phase = HybridWebViewError.Phase.HTTP,
        code = httpCode(statusCode),
        message = description,
        url = url,
        httpStatusCode = statusCode
    )

    /** SSL/证书错误（系统内核 `onReceivedSslError`）。 */
    fun sslError(
        url: String?,
        description: String?,
        cause: Throwable? = null
    ): HybridWebViewError = HybridWebViewError(
        phase = HybridWebViewError.Phase.SSL,
        code = CODE_SSL_ERROR,
        message = description,
        url = url,
        cause = cause
    )
}
