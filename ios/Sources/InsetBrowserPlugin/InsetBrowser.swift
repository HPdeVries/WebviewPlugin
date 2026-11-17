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
    private var navigationChangePending = false
    private var lastNavigationState: (url: String, canGoBack: Bool, canGoForward: Bool)?

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

        // KVO also catches same-document navigations (pushState, #hash),
        // which produce no navigation delegate callbacks.
        let notify: (WKWebView) -> Void = { [weak self] _ in self?.scheduleNavigationChanged() }
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

    /// JPEG image of what the browser currently shows, plus its frame in the
    /// Capacitor webview's CSS px (pt), so the page can put the image exactly
    /// where the browser is (e.g. before hiding it while a menu is open).
    func snapshot(quality: CGFloat, completion: @escaping (Result<[String: Any], Error>) -> Void) {
        guard let host = host, !webView.isHidden, !webView.bounds.isEmpty else {
            completion(.failure(InsetBrowserError("InsetBrowser is not visible")))
            return
        }
        let frame = webView.convert(webView.bounds, to: host)
        webView.takeSnapshot(with: WKSnapshotConfiguration()) { image, error in
            guard let image = image else {
                completion(.failure(error ?? InsetBrowserError("Snapshot failed")))
                return
            }
            // JPEG encoding off the main thread; it can take tens of ms.
            DispatchQueue.global(qos: .userInitiated).async {
                guard let data = image.jpegData(compressionQuality: quality) else {
                    completion(.failure(InsetBrowserError("Snapshot encoding failed")))
                    return
                }
                completion(.success([
                    "dataUrl": "data:image/jpeg;base64," + data.base64EncodedString(),
                    "left": Double(frame.minX),
                    "top": Double(frame.minY),
                    "width": Double(frame.width),
                    "height": Double(frame.height)
                ]))
            }
        }
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

    /// Coalesces the separate url/canGoBack/canGoForward changes of one
    /// navigation into a single event, and drops unchanged states.
    /// The state is read from the back-forward list, which only changes when
    /// a navigation commits: `webView.url` already holds the provisional URL
    /// (even for loads that then fail), so it is only used as a trigger.
    private func scheduleNavigationChanged() {
        guard !navigationChangePending else { return }
        navigationChangePending = true
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.navigationChangePending = false
            let list = self.webView.backForwardList
            guard let current = list.currentItem else { return } // nothing committed yet
            let state = (url: current.url.absoluteString,
                         canGoBack: list.backItem != nil,
                         canGoForward: list.forwardItem != nil)
            if let last = self.lastNavigationState, last == state { return }
            self.lastNavigationState = state
            self.emit("navigationChanged", ["url": state.url, "canGoBack": state.canGoBack, "canGoForward": state.canGoForward])
        }
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

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        scheduleNavigationChanged()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        scheduleNavigationChanged()
        emit("pageLoaded", ["url": webView.url?.absoluteString ?? ""])
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        scheduleNavigationChanged()
        emitError(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        scheduleNavigationChanged()
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

struct InsetBrowserError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
