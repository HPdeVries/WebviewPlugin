package com.insetbrowser.capacitor

import androidx.activity.OnBackPressedCallback
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

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

    override fun load() {
        activity.runOnUiThread {
            activity.onBackPressedDispatcher.addCallback(activity, backCallback)
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

        activity.runOnUiThread {
            val existing = browser
            if (existing != null) {
                // Single instance: open() again navigates the existing browser.
                existing.setInsets(insetTop, insetBottom, includeSafeArea)
                existing.setVisible(true)
                existing.load(url)
            } else {
                val b = InsetBrowser(
                    activity = activity,
                    host = bridge.webView,
                    insetTop = insetTop ?: 0.0,
                    insetBottom = insetBottom ?: 0.0,
                    includeSafeArea = includeSafeArea ?: true,
                    emit = { name, data -> notifyListeners(name, data) },
                )
                browser = b
                b.load(url)
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
        withBrowser(call) { it.load(url) }
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

    private fun updateBackCallback() {
        backCallback.isEnabled = browser?.isVisible() == true
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
