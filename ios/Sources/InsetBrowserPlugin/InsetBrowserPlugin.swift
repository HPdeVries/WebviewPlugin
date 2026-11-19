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
        CAPPluginMethod(name: "hide", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "snapshot", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getState", returnType: CAPPluginReturnPromise)
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
        let headers = Self.headers(call)
        if let error = Self.validate(url, headers: headers) {
            call.reject(error)
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if let browser = self.browser {
                // Single instance: open() again navigates the existing browser.
                browser.setInsets(top: insetTop, bottom: insetBottom, includeSafeArea: includeSafeArea)
                browser.setHidden(false)
                browser.load(url, headers: headers)
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
            browser.load(url, headers: headers)
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
        let headers = Self.headers(call)
        if let error = Self.validate(url, headers: headers) {
            call.reject(error)
            return
        }
        withBrowser(call) { $0.load(url, headers: headers) }
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

    /// Never rejects: when closed it reports isOpen false.
    @objc func getState(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let browser = self?.browser else {
                call.resolve(["isOpen": false, "isVisible": false, "url": "", "canGoBack": false, "canGoForward": false])
                return
            }
            call.resolve(browser.state())
        }
    }

    @objc func snapshot(_ call: CAPPluginCall) {
        let quality = CGFloat(min(max(call.getDouble("quality") ?? 0.8, 0.1), 1.0))
        DispatchQueue.main.async { [weak self] in
            guard let browser = self?.browser else {
                call.reject("InsetBrowser is not open")
                return
            }
            browser.snapshot(quality: quality) { result in
                switch result {
                case .success(let data): call.resolve(data)
                case .failure(let error): call.reject(error.localizedDescription)
                }
            }
        }
    }

    /// Only http(s) may be loaded (no `javascript:`, `file:`, custom schemes).
    /// Headers (e.g. tokens) are only sent over https.
    /// Returns an error message, or nil when valid.
    private static func validate(_ url: URL, headers: [String: String]) -> String? {
        let scheme = url.scheme?.lowercased()
        guard scheme == "http" || scheme == "https" else { return "Only http and https URLs are allowed" }
        if !headers.isEmpty && scheme != "https" { return "Headers are only allowed with https URLs" }
        return nil
    }

    /// Optional `headers` object of string values; other values are ignored.
    private static func headers(_ call: CAPPluginCall) -> [String: String] {
        (call.getObject("headers") ?? [:]).compactMapValues { $0 as? String }
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
