package com.happyboy.tokay.engine.webview.ime

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 键盘避让：把 IME 高度作为**底部 padding 施加在 HybridWebView 自己身上**，让引擎随之缩小可视区。
 * 为什么这样做、何时开启、对宿主布局的要求与已知边界见
 * [com.happyboy.tokay.engine.webview.HybridWebView.setImeResizeEnabled]。
 */
internal class ImeInsetResizer(
    private val view: View,
    private val onImeSettled: () -> Unit
) {

    private var enabled = false
    private var animationRunning = false
    private var baseBottomPadding = 0
    private var animationCallback: WindowInsetsAnimationCompat.Callback? = null

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (enabled) {
            // 记住宿主自己的底部 padding，只在它之上叠加 IME 高度。
            baseBottomPadding = view.paddingBottom
            install()
            ViewCompat.requestApplyInsets(view)
        } else {
            uninstall()
            applyImeBottom(0)
        }
    }

    fun release() {
        if (!enabled) return
        enabled = false
        uninstall()
    }

    private fun install() {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            // 动画期间由 onProgress 逐帧驱动，避免与动画抢同一个值。
            if (!animationRunning) {
                applyImeBottom(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            }
            insets
        }

        val callback = object : WindowInsetsAnimationCompat.Callback(
            DISPATCH_MODE_CONTINUE_ON_SUBTREE
        ) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                    animationRunning = true
                }
            }

            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>
            ): WindowInsetsCompat {
                applyImeBottom(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                animationRunning = false
                val imeBottom = ViewCompat.getRootWindowInsets(view)
                    ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                applyImeBottom(imeBottom)
                if (imeBottom > 0) {
                    onImeSettled()
                }
            }
        }
        animationCallback = callback
        ViewCompat.setWindowInsetsAnimationCallback(view, callback)
    }

    private fun uninstall() {
        ViewCompat.setOnApplyWindowInsetsListener(view, null)
        animationCallback?.let { ViewCompat.setWindowInsetsAnimationCallback(view, null) }
        animationCallback = null
        animationRunning = false
    }

    private fun applyImeBottom(imeBottom: Int) {
        val target = baseBottomPadding + imeBottom.coerceAtLeast(0)
        if (view.paddingBottom != target) {
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, target)
        }
    }
}
