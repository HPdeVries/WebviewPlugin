import Foundation
import UIKit
import WebKit

/// A WKWebView added as a subview of the Capacitor webview and pinned with
/// Auto Layout so a strip at the top and/or bottom stays uncovered. Taps in
/// that strip fall through to the Capacitor webview because nothing covers it.
/// Constraints recalculate automatically on rotation, resize and safe-area changes.
final class InsetBrowser: NSObject, WKNavigationDelegate, WKUIDelegate {
    typealias Emit = (_ event: String, _ data: [String: Any]) -> Void

    private let webView: WKWebView
    private weak var host: UIView?
    private let emit: Emit

    private var insetTop: CGFloat
    private var insetBottom: CGFloat
    private var includeSafeArea: Bool
    private var layoutConstraints: [NSLayoutConstraint] = []
    private var observations: [NSKeyValueObservation] = []

    init(host: UIView, insetTop: CGFloat, insetBottom: CGFloat, includeSafeArea: Bool, incognito: Bool, emit: @escaping Emit) {
        self.host = host
        self.insetTop = insetTop
        self.insetBottom = insetBottom
        self.includeSafeArea = includeSafeArea
        self.emit = emit

        let config = WKWebViewConfiguration()
        config.websiteDataStore = incognito ? .nonPersistent() : .default()
        config.allowsInlineMediaPlayback = true
        webView = WKWebView(frame: .zero, configuration: config)
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.contentInsetAdjustmentBehavior = .never

        super.init()

        webView.navigationDelegate = self
        webView.uiDelegate = self

        // The Capacitor bridge view controller's root view IS the Capacitor
        // webview, so the inset browser is added as its subview (on top of it).
        host.addSubview(webView)
        host.bringSubviewToFront(webView)
        applyConstraints()

        let notify: (WKWebView) -> Void = { [weak self] _ in self?.emitNavigationChanged() }
        observations = [
            webView.observe(\.url, options: [.new]) { wv, _ in notify(wv) },
            webView.observe(\.canGoBack, options: [.new]) { wv, _ in notify(wv) },
            webView.observe(\.canGoForward, options: [.new]) { wv, _ in notify(wv) }
        ]
    }

    // MARK: - Public API

    func load(_ url: URL) {
        webView.load(URLRequest(url: url))
    }

    func goBack() { if webView.canGoBack { webView.goBack() } }
    func goForward() { if webView.canGoForward { webView.goForward() } }
    func reload() { webView.reload() }
    func setHidden(_ hidden: Bool) { webView.isHidden = hidden }

    func setInsets(top: CGFloat?, bottom: CGFloat?, includeSafeArea: Bool?) {
        if let top = top { insetTop = top }
        if let bottom = bottom { insetBottom = bottom }
        if let include = includeSafeArea { self.includeSafeArea = include }
        applyConstraints()
    }

    func destroy() {
        observations.forEach { $0.invalidate() }
        observations = []
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        NSLayoutConstraint.deactivate(layoutConstraints)
        webView.removeFromSuperview()
    }

    // MARK: - Layout

    private func applyConstraints() {
        guard let host = host else { return }
        NSLayoutConstraint.deactivate(layoutConstraints)
        let guide = host.safeAreaLayoutGuide
        let topAnchor = includeSafeArea ? guide.topAnchor : host.topAnchor
        let bottomAnchor = includeSafeArea ? guide.bottomAnchor : host.bottomAnchor
        layoutConstraints = [
            webView.leadingAnchor.constraint(equalTo: host.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: host.trailingAnchor),
            webView.topAnchor.constraint(equalTo: topAnchor, constant: insetTop),
            webView.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -insetBottom)
        ]
        NSLayoutConstraint.activate(layoutConstraints)
        host.setNeedsLayout()
    }

    // MARK: - Events

    private func emitNavigationChanged() {
        emit("navigationChanged", [
            "url": webView.url?.absoluteString ?? "",
            "canGoBack": webView.canGoBack,
            "canGoForward": webView.canGoForward
        ])
    }

    private func emitError(_ error: Error) {
        let nsError = error as NSError
        // Cancelled loads (e.g. a new navigation replacing the current one) are not errors.
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled { return }
        let failingUrl = (nsError.userInfo[NSURLErrorFailingURLStringErrorKey] as? String)
            ?? webView.url?.absoluteString ?? ""
        emit("pageLoadError", ["url": failingUrl, "message": nsError.localizedDescription])
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        emit("pageLoadStarted", ["url": webView.url?.absoluteString ?? ""])
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        emit("pageLoaded", ["url": webView.url?.absoluteString ?? ""])
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        emitError(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        emitError(error)
    }

    // MARK: - WKUIDelegate

    /// target="_blank" / window.open: load in the same webview.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil {
            webView.load(navigationAction.request)
        }
        return nil
    }
}
