package com.insetbrowser.capacitor

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.getcapacitor.JSObject
import java.io.ByteArrayOutputStream
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
    private val activity: Activity,
    private val host: WebView,
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
    /** URL of the main-frame load that failed; its onPageFinished is not reported. */
    private var failedUrl: String? = null
    private var lastNavigationState: Triple<String, Boolean, Boolean>? = null

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
                failedUrl = null // WebView order is: started -> error -> finished
                emit("pageLoadStarted", JSObject().put("url", url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                // WebView also "finishes" its own error page; iOS does not, so
                // keep the platforms consistent: an error is never followed by pageLoaded.
                if (url == failedUrl) {
                    failedUrl = null
                    return
                }
                emit("pageLoaded", JSObject().put("url", url))
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                failedUrl = request.url.toString()
                emit(
                    "pageLoadError",
                    JSObject().put("url", request.url.toString()).put("message", error.description.toString()),
                )
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Only report real changes (e.g. not a reload), like iOS. isReload
                // itself is unreliable: it is also true for some back/forward loads.
                val state = Triple(url, view.canGoBack(), view.canGoForward())
                if (state == lastNavigationState) return
                lastNavigationState = state
                emit(
                    "navigationChanged",
                    JSObject()
                        .put("url", state.first)
                        .put("canGoBack", state.second)
                        .put("canGoForward", state.third),
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
    /** Current state for getState(); call on the main thread. */
    fun state(): JSObject = JSObject()
        .put("isOpen", true)
        .put("isVisible", isVisible())
        .put("url", webView.url ?: "")
        .put("canGoBack", webView.canGoBack())
        .put("canGoForward", webView.canGoForward())

    fun onPause() = webView.onPause()
    fun onResume() = webView.onResume()

    fun setInsets(top: Double?, bottom: Double?, includeSafeArea: Boolean?) {
        top?.let { insetTop = it }
        bottom?.let { insetBottom = it }
        includeSafeArea?.let { this.includeSafeArea = it }
        applyLayout()
    }

    /**
     * JPEG image of what the browser currently shows, plus its frame in the
     * Capacitor webview's CSS px (dp), so the page can put the image exactly
     * where the browser is (e.g. before hiding it while a menu is open).
     * [done] is called with (result, null) or (null, error), possibly off the
     * main thread. Must be called on the main thread.
     */
    fun snapshot(quality: Int, done: (JSObject?, String?) -> Unit) {
        val w = webView.width
        val h = webView.height
        if (!isVisible() || w == 0 || h == 0) {
            done(null, "InsetBrowser is not visible")
            return
        }
        val loc = IntArray(2).also { webView.getLocationInWindow(it) }
        val hostLoc = IntArray(2).also { host.getLocationInWindow(it) }
        val frame = JSObject()
            .put("left", ((loc[0] - hostLoc[0]) / density).toDouble())
            .put("top", ((loc[1] - hostLoc[1]) / density).toDouble())
            .put("width", (w / density).toDouble())
            .put("height", (h / density).toDouble())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        fun encode() = Thread {
            try {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bitmap.recycle()
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                done(frame.put("dataUrl", "data:image/jpeg;base64,$b64"), null)
            } catch (e: Exception) {
                done(null, "Snapshot encoding failed: ${e.message}")
            }
        }.start()

        fun drawFallback() {
            webView.draw(Canvas(bitmap))
            encode()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Copies the composited screen pixels, so hardware-rendered content
            // (video, canvas) is included, unlike View.draw().
            val src = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
            PixelCopy.request(activity.window, src, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) encode() else drawFallback()
            }, Handler(Looper.getMainLooper()))
        } else {
            drawFallback()
        }
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
     * Left/right matter in landscape on devices with a side cutout.
     */
    private fun safeArea(): Insets {
        if (!includeSafeArea) return Insets.NONE
        val insets = ViewCompat.getRootWindowInsets(parent) ?: return Insets.NONE
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val loc = IntArray(2)
        parent.getLocationInWindow(loc)
        val root = parent.rootView
        val gapLeft = loc[0]
        val gapTop = loc[1]
        val gapRight = max(0, root.width - (loc[0] + parent.width))
        val gapBottom = max(0, root.height - (loc[1] + parent.height))
        return Insets.of(
            max(0, bars.left - gapLeft),
            max(0, bars.top - gapTop),
            max(0, bars.right - gapRight),
            max(0, bars.bottom - gapBottom),
        )
    }

    private fun applyLayout() {
        val safe = safeArea()
        val top = (insetTop * density).roundToInt() + safe.top
        val bottom = (insetBottom * density).roundToInt() + safe.bottom
        // Mutate the params the parent generated (e.g. CoordinatorLayout.LayoutParams)
        // instead of replacing them with a foreign type.
        val lp = webView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin == top && lp.bottomMargin == bottom &&
            lp.leftMargin == safe.left && lp.rightMargin == safe.right
        ) return
        lp.setMargins(safe.left, top, safe.right, bottom)
        webView.layoutParams = lp
    }
}
