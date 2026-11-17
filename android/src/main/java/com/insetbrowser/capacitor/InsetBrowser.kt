package com.insetbrowser.capacitor

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.getcapacitor.JSObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A native WebView added to the same parent as the Capacitor webview, on top
 * of it, with top/bottom margins so a strip of the host app stays uncovered.
 * Taps in that strip reach the Capacitor webview because nothing covers it.
 * Layout is recomputed on every parent layout change (rotation, resize,
 * system bar changes).
 */
@SuppressLint("SetJavaScriptEnabled")
class InsetBrowser(
    activity: Activity,
    host: WebView,
    private var insetTop: Double,
    private var insetBottom: Double,
    private var includeSafeArea: Boolean,
    private val emit: (String, JSObject) -> Unit,
) {
    private val parent: ViewGroup = host.parent as? ViewGroup
        ?: throw IllegalStateException("Capacitor webview has no parent view")
    private val webView = WebView(activity)
    private val density = activity.resources.displayMetrics.density
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        // Defer: changing layout params inside a layout pass is not allowed.
        parent.post { applyLayout() }
    }

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false) // target=_blank loads in this webview
            javaScriptCanOpenWindowsAutomatically = false
        }
        // Default persistent cookie store (shared process-wide with the app webview).
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                emit("pageLoadStarted", JSObject().put("url", url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                emit("pageLoaded", JSObject().put("url", url))
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                emit(
                    "pageLoadError",
                    JSObject().put("url", request.url.toString()).put("message", error.description.toString()),
                )
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                emit(
                    "navigationChanged",
                    JSObject()
                        .put("url", url)
                        .put("canGoBack", view.canGoBack())
                        .put("canGoForward", view.canGoForward()),
                )
            }
        }

        val lp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // Appended last => drawn above the Capacitor webview in the same parent.
        parent.addView(webView, lp)
        webView.bringToFront()
        parent.addOnLayoutChangeListener(layoutListener)
        applyLayout()
        // Insets are only reliable after the first layout pass.
        parent.post { applyLayout() }
    }

    fun load(url: String) = webView.loadUrl(url)
    fun canGoBack() = webView.canGoBack()
    fun goBack() { if (webView.canGoBack()) webView.goBack() }
    fun goForward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() = webView.reload()
    fun isVisible() = webView.visibility == View.VISIBLE
    fun setVisible(visible: Boolean) {
        webView.visibility = if (visible) View.VISIBLE else View.GONE
    }
    fun onPause() = webView.onPause()
    fun onResume() = webView.onResume()

    fun setInsets(top: Double?, bottom: Double?, includeSafeArea: Boolean?) {
        top?.let { insetTop = it }
        bottom?.let { insetBottom = it }
        includeSafeArea?.let { this.includeSafeArea = it }
        applyLayout()
    }

    fun destroy() {
        parent.removeOnLayoutChangeListener(layoutListener)
        webView.stopLoading()
        parent.removeView(webView)
        webView.destroy()
    }

    /**
     * Safe area relative to the parent: system bar/cutout insets minus the part
     * the parent already avoids (e.g. when the app is not edge-to-edge).
     */
    private fun safeArea(): Pair<Int, Int> {
        if (!includeSafeArea) return 0 to 0
        val insets = ViewCompat.getRootWindowInsets(parent) ?: return 0 to 0
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val loc = IntArray(2)
        parent.getLocationInWindow(loc)
        val rootHeight = parent.rootView.height
        val parentTop = loc[1]
        val parentBottomGap = max(0, rootHeight - (loc[1] + parent.height))
        return max(0, bars.top - parentTop) to max(0, bars.bottom - parentBottomGap)
    }

    private fun applyLayout() {
        val (safeTop, safeBottom) = safeArea()
        val top = (insetTop * density).roundToInt() + safeTop
        val bottom = (insetBottom * density).roundToInt() + safeBottom
        // Mutate the params the parent generated (e.g. CoordinatorLayout.LayoutParams)
        // instead of replacing them with a foreign type.
        val lp = webView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin == top && lp.bottomMargin == bottom) return
        lp.topMargin = top
        lp.bottomMargin = bottom
        webView.layoutParams = lp
    }
}
