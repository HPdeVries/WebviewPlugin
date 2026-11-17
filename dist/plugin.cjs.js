'use strict';

var core = require('@capacitor/core');

const InsetBrowser = core.registerPlugin('InsetBrowser', {
    web: () => Promise.resolve().then(function () { return web; }).then((m) => new m.InsetBrowserWeb()),
});

/**
 * Development fallback: a fixed-position iframe that leaves the requested
 * strips free. Many sites refuse to be framed (X-Frame-Options / CSP), and
 * cross-origin history is not observable, so canGoBack/canGoForward are
 * always false here.
 */
class InsetBrowserWeb extends core.WebPlugin {
    constructor() {
        super(...arguments);
        this.frame = null;
        this.insetTop = 0;
        this.insetBottom = 0;
        this.includeSafeArea = true;
    }
    async open(options) {
        if (!(options === null || options === void 0 ? void 0 : options.url))
            throw new Error('url is required');
        if (options.insetTop !== undefined)
            this.insetTop = options.insetTop;
        if (options.insetBottom !== undefined)
            this.insetBottom = options.insetBottom;
        if (options.includeSafeArea !== undefined)
            this.includeSafeArea = options.includeSafeArea;
        if (!this.frame) {
            const f = document.createElement('iframe');
            f.style.position = 'fixed';
            f.style.left = '0';
            f.style.right = '0';
            f.style.width = '100%';
            f.style.border = '0';
            f.style.zIndex = '2147483000';
            f.style.background = '#fff';
            f.addEventListener('load', () => {
                const url = this.currentUrl();
                this.notifyListeners('pageLoaded', { url });
                this.notifyListeners('navigationChanged', { url, canGoBack: false, canGoForward: false });
            });
            f.addEventListener('error', () => {
                this.notifyListeners('pageLoadError', { url: this.currentUrl(), message: 'Failed to load' });
            });
            document.body.appendChild(f);
            this.frame = f;
        }
        this.applyLayout();
        this.frame.style.display = '';
        await this.navigate({ url: options.url });
    }
    async close() {
        if (!this.frame)
            return;
        this.frame.remove();
        this.frame = null;
        this.notifyListeners('closed', {});
    }
    async navigate(options) {
        if (!this.frame)
            throw new Error('InsetBrowser is not open');
        this.notifyListeners('pageLoadStarted', { url: options.url });
        this.frame.src = options.url;
    }
    async goBack() {
        this.tryHistory((h) => h.back());
    }
    async goForward() {
        this.tryHistory((h) => h.forward());
    }
    async reload() {
        if (this.frame)
            this.frame.src = this.frame.src;
    }
    async setInsets(options) {
        if (options.insetTop !== undefined)
            this.insetTop = options.insetTop;
        if (options.insetBottom !== undefined)
            this.insetBottom = options.insetBottom;
        this.applyLayout();
    }
    async show() {
        if (this.frame)
            this.frame.style.display = '';
    }
    async hide() {
        if (this.frame)
            this.frame.style.display = 'none';
    }
    async getState() {
        const f = this.frame;
        return {
            isOpen: !!f,
            isVisible: !!f && f.style.display !== 'none',
            url: f ? this.currentUrl() : '',
            canGoBack: false,
            canGoForward: false,
        };
    }
    async snapshot() {
        // A cross-origin iframe cannot be rendered to an image.
        throw this.unavailable('snapshot is not supported on web');
    }
    applyLayout() {
        if (!this.frame)
            return;
        const top = this.includeSafeArea
            ? `calc(${this.insetTop}px + env(safe-area-inset-top, 0px))`
            : `${this.insetTop}px`;
        const bottom = this.includeSafeArea
            ? `calc(${this.insetBottom}px + env(safe-area-inset-bottom, 0px))`
            : `${this.insetBottom}px`;
        this.frame.style.top = top;
        this.frame.style.bottom = bottom;
        this.frame.style.height = `calc(100% - (${top}) - (${bottom}))`;
    }
    currentUrl() {
        var _a, _b, _c, _d, _e, _f, _g;
        try {
            return (_e = (_c = (_b = (_a = this.frame) === null || _a === void 0 ? void 0 : _a.contentWindow) === null || _b === void 0 ? void 0 : _b.location.href) !== null && _c !== void 0 ? _c : (_d = this.frame) === null || _d === void 0 ? void 0 : _d.src) !== null && _e !== void 0 ? _e : '';
        }
        catch (_h) {
            return (_g = (_f = this.frame) === null || _f === void 0 ? void 0 : _f.src) !== null && _g !== void 0 ? _g : ''; // cross-origin
        }
    }
    tryHistory(fn) {
        var _a, _b;
        try {
            const h = (_b = (_a = this.frame) === null || _a === void 0 ? void 0 : _a.contentWindow) === null || _b === void 0 ? void 0 : _b.history;
            if (h)
                fn(h);
        }
        catch (_c) {
            // cross-origin: not supported in the web fallback
        }
    }
}

var web = /*#__PURE__*/Object.freeze({
    __proto__: null,
    InsetBrowserWeb: InsetBrowserWeb
});

exports.InsetBrowser = InsetBrowser;
//# sourceMappingURL=plugin.cjs.js.map
