import Foundation
import Capacitor

@objc(InsetBrowserPlugin)
public class InsetBrowserPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "InsetBrowserPlugin"
    public let jsName = "InsetBrowser"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "open", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "close", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "navigate", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "goBack", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "goForward", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setInsets", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "show", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hide", returnType: CAPPluginReturnPromise)
    ]

    private var browser: InsetBrowser?

    @objc func open(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url"), let url = URL(string: urlString) else {
            call.reject("A valid url is required")
            return
        }
        let insetTop = call.getDouble("insetTop").map { CGFloat($0) }
        let insetBottom = call.getDouble("insetBottom").map { CGFloat($0) }
        let includeSafeArea = call.getBool("includeSafeArea")
        let incognito = call.getBool("incognito") ?? false

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if let browser = self.browser {
                // Single instance: open() again navigates the existing browser.
                browser.setInsets(top: insetTop, bottom: insetBottom, includeSafeArea: includeSafeArea)
                browser.setHidden(false)
                browser.load(url)
                call.resolve()
                return
            }
            guard let host = self.bridge?.webView else {
                call.reject("Capacitor webview not available")
                return
            }
            let browser = InsetBrowser(
                host: host,
                insetTop: insetTop ?? 0,
                insetBottom: insetBottom ?? 0,
                includeSafeArea: includeSafeArea ?? true,
                incognito: incognito,
                emit: { [weak self] name, data in self?.notifyListeners(name, data: data) }
            )
            self.browser = browser
            browser.load(url)
            call.resolve()
        }
    }

    @objc func close(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if let browser = self.browser {
                browser.destroy()
                self.browser = nil
                self.notifyListeners("closed", data: [:])
            }
            call.resolve()
        }
    }

    @objc func navigate(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url"), let url = URL(string: urlString) else {
            call.reject("A valid url is required")
            return
        }
        withBrowser(call) { $0.load(url) }
    }

    @objc func goBack(_ call: CAPPluginCall) { withBrowser(call) { $0.goBack() } }
    @objc func goForward(_ call: CAPPluginCall) { withBrowser(call) { $0.goForward() } }
    @objc func reload(_ call: CAPPluginCall) { withBrowser(call) { $0.reload() } }
    @objc func show(_ call: CAPPluginCall) { withBrowser(call) { $0.setHidden(false) } }
    @objc func hide(_ call: CAPPluginCall) { withBrowser(call) { $0.setHidden(true) } }

    @objc func setInsets(_ call: CAPPluginCall) {
        let top = call.getDouble("insetTop").map { CGFloat($0) }
        let bottom = call.getDouble("insetBottom").map { CGFloat($0) }
        withBrowser(call) { $0.setInsets(top: top, bottom: bottom, includeSafeArea: nil) }
    }

    private func withBrowser(_ call: CAPPluginCall, _ action: @escaping (InsetBrowser) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let browser = self?.browser else {
                call.reject("InsetBrowser is not open")
                return
            }
            action(browser)
            call.resolve()
        }
    }
}
