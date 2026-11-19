package com.insetbrowser.capacitor

import android.net.Uri
import androidx.activity.OnBackPressedCallback
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlin.math.roundToInt

@CapacitorPlugin(name = "InsetBrowser")
class InsetBrowserPlugin : Plugin() {

    private var browser: InsetBrowser? = null

    /**
     * Enabled only while the browser is visible. Navigates back inside the
     * inset browser; when that is not possible it emits `backButtonPressed`
     * and lets the host app decide (never closes automatically).
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val b = browser ?: return
            if (b.canGoBack()) b.goBack() else notifyListeners("backButtonPressed", JSObject())
        }
    }


    @PluginMethod
    fun open(call: PluginCall) {
        val url = call.getString("url")
        if (url.isNullOrBlank()) {
            call.reject("A valid url is required")
            return
        }
        val insetTop = call.getDouble("insetTop")
        val insetBottom = call.getDouble("insetBottom")
        val includeSafeArea = call.getBoolean("includeSafeArea")
        val thirdPartyCookies = call.getBoolean("thirdPartyCookies")
        val headers = headers(call)
        validate(url, headers)?.let {
            call.reject(it)
            return
        }

        activity.runOnUiThread {
            val existing = browser
            if (existing != null) {
                // Single instance: open() again navigates the existing browser.
                existing.setInsets(insetTop, insetBottom, includeSafeArea)
                thirdPartyCookies?.let { existing.setThirdPartyCookies(it) }
                existing.setVisible(true)
                existing.load(url, headers)
            } else {
                val b = InsetBrowser(
                    activity = activity,
                    host = bridge.webView,
                    insetTop = insetTop ?: 0.0,
                    insetBottom = insetBottom ?: 0.0,
                    includeSafeArea = includeSafeArea ?: true,
                    thirdPartyCookies = thirdPartyCookies ?: false,
                    emit = { name, data -> notifyListeners(name, data) },
                )
                browser = b
                b.load(url, headers)
            }
            updateBackCallback()
            call.resolve()
        }
    }

    @PluginMethod
    fun close(call: PluginCall) {
        activity.runOnUiThread {
            browser?.let {
                it.destroy()
                browser = null
                notifyListeners("closed", JSObject())
            }
            updateBackCallback()
            call.resolve()
        }
    }

    @PluginMethod
    fun navigate(call: PluginCall) {
        val url = call.getString("url")
        if (url.isNullOrBlank()) {
            call.reject("A valid url is required")
            return
        }
        val headers = headers(call)
        validate(url, headers)?.let {
            call.reject(it)
            return
        }
        withBrowser(call) { it.load(url, headers) }
    }

    @PluginMethod fun goBack(call: PluginCall) = withBrowser(call) { it.goBack() }
    @PluginMethod fun goForward(call: PluginCall) = withBrowser(call) { it.goForward() }
    @PluginMethod fun reload(call: PluginCall) = withBrowser(call) { it.reload() }
    @PluginMethod fun show(call: PluginCall) = withBrowser(call) { it.setVisible(true) }
    @PluginMethod fun hide(call: PluginCall) = withBrowser(call) { it.setVisible(false) }

    @PluginMethod
    fun setInsets(call: PluginCall) {
        val top = call.getDouble("insetTop")
        val bottom = call.getDouble("insetBottom")
        withBrowser(call) { it.setInsets(top, bottom, null) }
    }

    /** Never rejects: when closed it reports isOpen false. */
    @PluginMethod
    fun getState(call: PluginCall) {
        activity.runOnUiThread {
            call.resolve(
                browser?.state() ?: JSObject()
                    .put("isOpen", false)
                    .put("isVisible", false)
                    .put("url", "")
                    .put("canGoBack", false)
                    .put("canGoForward", false),
            )
        }
    }

    @PluginMethod
    fun snapshot(call: PluginCall) {
        val quality = ((call.getDouble("quality") ?: 0.8).coerceIn(0.1, 1.0) * 100).roundToInt()
        activity.runOnUiThread {
            val b = browser
            if (b == null) {
                call.reject("InsetBrowser is not open")
                return@runOnUiThread
            }
            b.snapshot(quality) { data, error ->
                if (data != null) call.resolve(data) else call.reject(error ?: "Snapshot failed")
            }
        }
    }

    /**
     * Only http(s) may be loaded: `javascript:` would run script in the current
     * page, `file:`/`content:` would expose local data. Headers (e.g. tokens) are
     * only sent over https. Returns an error message, or null when valid.
     */
    private fun validate(url: String, headers: Map<String, String>): String? {
        val scheme = Uri.parse(url.trim()).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Only http and https URLs are allowed"
        if (headers.isNotEmpty() && scheme != "https") return "Headers are only allowed with https URLs"
        return null
    }

    /** Optional `headers` object of string values; other values are ignored. */
    private fun headers(call: PluginCall): Map<String, String> {
        val obj = call.getObject("headers") ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        obj.keys().forEach { key -> (obj.opt(key) as? String)?.let { map[key] = it } }
        return map
    }

    private fun withBrowser(call: PluginCall, action: (InsetBrowser) -> Unit) {
        activity.runOnUiThread {
            val b = browser
            if (b == null) {
                call.reject("InsetBrowser is not open")
                return@runOnUiThread
            }
            action(b)
            updateBackCallback()
            call.resolve()
        }
    }

    /**
     * The dispatcher gives priority to the most recently added callback, and
     * other plugins (e.g. @capacitor/app) register theirs too. Re-adding ours
     * whenever the browser becomes visible keeps it on top regardless of
     * plugin load order. Must run on the main thread.
     */
    private fun updateBackCallback() {
        val visible = browser?.isVisible() == true
        if (visible && !backCallback.isEnabled) {
            backCallback.remove()
            activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        }
        backCallback.isEnabled = visible
    }

    override fun handleOnPause() {
        super.handleOnPause()
        browser?.onPause()
    }

    override fun handleOnResume() {
        super.handleOnResume()
        browser?.onResume()
    }

    override fun handleOnDestroy() {
        browser?.destroy()
        browser = null
        backCallback.remove()
        super.handleOnDestroy()
    }
}
