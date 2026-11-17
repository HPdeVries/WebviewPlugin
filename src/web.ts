import { WebPlugin } from '@capacitor/core';

import type { InsetBrowserPlugin, InsetOptions, NavigateOptions, OpenOptions } from './definitions';

/**
 * Development fallback: a fixed-position iframe that leaves the requested
 * strips free. Many sites refuse to be framed (X-Frame-Options / CSP), and
 * cross-origin history is not observable, so canGoBack/canGoForward are
 * always false here.
 */
export class InsetBrowserWeb extends WebPlugin implements InsetBrowserPlugin {
  private frame: HTMLIFrameElement | null = null;
  private insetTop = 0;
  private insetBottom = 0;
  private includeSafeArea = true;

  async open(options: OpenOptions): Promise<void> {
    if (!options?.url) throw new Error('url is required');
    if (options.insetTop !== undefined) this.insetTop = options.insetTop;
    if (options.insetBottom !== undefined) this.insetBottom = options.insetBottom;
    if (options.includeSafeArea !== undefined) this.includeSafeArea = options.includeSafeArea;

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

  async close(): Promise<void> {
    if (!this.frame) return;
    this.frame.remove();
    this.frame = null;
    this.notifyListeners('closed', {});
  }

  async navigate(options: NavigateOptions): Promise<void> {
    if (!this.frame) throw new Error('InsetBrowser is not open');
    this.notifyListeners('pageLoadStarted', { url: options.url });
    this.frame.src = options.url;
  }

  async goBack(): Promise<void> {
    this.tryHistory((h) => h.back());
  }

  async goForward(): Promise<void> {
    this.tryHistory((h) => h.forward());
  }

  async reload(): Promise<void> {
    if (this.frame) this.frame.src = this.frame.src;
  }

  async setInsets(options: InsetOptions): Promise<void> {
    if (options.insetTop !== undefined) this.insetTop = options.insetTop;
    if (options.insetBottom !== undefined) this.insetBottom = options.insetBottom;
    this.applyLayout();
  }

  async show(): Promise<void> {
    if (this.frame) this.frame.style.display = '';
  }

  async hide(): Promise<void> {
    if (this.frame) this.frame.style.display = 'none';
  }

  private applyLayout(): void {
    if (!this.frame) return;
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

  private currentUrl(): string {
    try {
      return this.frame?.contentWindow?.location.href ?? this.frame?.src ?? '';
    } catch {
      return this.frame?.src ?? ''; // cross-origin
    }
  }

  private tryHistory(fn: (h: History) => void): void {
    try {
      const h = this.frame?.contentWindow?.history;
      if (h) fn(h);
    } catch {
      // cross-origin: not supported in the web fallback
    }
  }
}
