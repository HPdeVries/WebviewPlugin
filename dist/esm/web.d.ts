import { WebPlugin } from '@capacitor/core';
import type { InsetBrowserPlugin, InsetOptions, NavigateOptions, OpenOptions, SnapshotResult } from './definitions';
/**
 * Development fallback: a fixed-position iframe that leaves the requested
 * strips free. Many sites refuse to be framed (X-Frame-Options / CSP), and
 * cross-origin history is not observable, so canGoBack/canGoForward are
 * always false here.
 */
export declare class InsetBrowserWeb extends WebPlugin implements InsetBrowserPlugin {
    private frame;
    private insetTop;
    private insetBottom;
    private includeSafeArea;
    open(options: OpenOptions): Promise<void>;
    close(): Promise<void>;
    navigate(options: NavigateOptions): Promise<void>;
    goBack(): Promise<void>;
    goForward(): Promise<void>;
    reload(): Promise<void>;
    setInsets(options: InsetOptions): Promise<void>;
    show(): Promise<void>;
    hide(): Promise<void>;
    snapshot(): Promise<SnapshotResult>;
    private applyLayout;
    private currentUrl;
    private tryHistory;
}
