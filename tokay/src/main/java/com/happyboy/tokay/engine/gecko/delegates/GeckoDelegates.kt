package com.happyboy.tokay.engine.gecko.delegates

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.engine.webview.HybridWebViewError
import com.happyboy.tokay.engine.webview.HybridWebViewErrorMapper
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError

class GeckoDelegates {
    companion object {
        /** 创建导航委托；[onLoadError] 用默认参数而非必填，以免扩大破坏面。 */
        fun navigation(
            onCanGoBack: (Boolean) -> Unit,
            onCanGoForward: (Boolean) -> Unit,
            onLocationChange: (String?) -> Unit,
            onLoadError: ((HybridWebViewError) -> Unit)? = null
        ) =
            object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    onCanGoBack(canGoBack)
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                    onCanGoForward(canGoForward)
                }

                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    perms: List<GeckoSession.PermissionDelegate.ContentPermission?>,
                    hasUserGesture: Boolean
                ) {
                    onLocationChange(url)
                }

                override fun onLoadError(
                    session: GeckoSession,
                    uri: String?,
                    error: WebRequestError
                ): GeckoResult<String?>? {
                    Log.e(LOG_TAG, "加载错误: $uri, error: $error")
                    // Gecko 的 onLoadError 也会携带 SSL 类错误码；不单独识别会让两个内核
                    // 对「证书无效」报出不同 phase。
                    onLoadError?.invoke(toLoadError(uri, error))
                    return null
                }
            }

        fun content(onTitle: (String?) -> Unit) = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                onTitle(title)
            }
        }

        /** Gecko `WebRequestError` → 宿主错误模型：仅 SSL/证书类单独识别，其余按文档加载失败归类。 */
        fun toLoadError(uri: String?, error: WebRequestError): HybridWebViewError {
            val isSecurity = error.code == WebRequestError.ERROR_SECURITY_SSL ||
                error.code == WebRequestError.ERROR_SECURITY_BAD_CERT ||
                error.category == WebRequestError.ERROR_CATEGORY_SECURITY
            return if (isSecurity) {
                HybridWebViewErrorMapper.sslError(
                    url = uri,
                    description = error.toString(),
                    cause = error
                )
            } else {
                HybridWebViewErrorMapper.loadError(
                    url = uri,
                    description = error.toString(),
                    nativeCode = error.code,
                    cause = error
                )
            }
        }
    }
}