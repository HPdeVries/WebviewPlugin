import type { PluginListenerHandle } from '@capacitor/core';

export interface OpenOptions {
  /** URL to load. */
  url: string;
  /**
   * Height of the strip kept free at the bottom, in CSS px
   * (pt on iOS, dp on Android). Excludes the safe area. Default 0.
   */
  insetBottom?: number;
  /**
   * Height of the strip kept free at the top, in CSS px
   * (pt on iOS, dp on Android). Excludes the safe area. Default 0.
   */
  insetTop?: number;
  /**
   * Add the device safe area (notch / home indicator / system bars)
   * on top of insetTop/insetBottom. Default true.
   */
  includeSafeArea?: boolean;
  /**
   * Use a non-persistent cookie/storage store. Default false
   * (default persistent store). iOS only; ignored on Android, where the
   * cookie store is shared process-wide with the app webview.
   */
  incognito?: boolean;
}

export interface NavigateOptions {
  url: string;
}

export interface InsetOptions {
  insetBottom?: number;
  insetTop?: number;
}

export interface UrlEvent {
  url: string;
}

export interface PageLoadErrorEvent {
  url: string;
  message: string;
}

export interface NavigationChangedEvent {
  url: string;
  canGoBack: boolean;
  canGoForward: boolean;
}

export interface SnapshotOptions {
  /** JPEG quality, 0.1 - 1.0. Default 0.8. */
  quality?: number;
}

export interface SnapshotResult {
  /** `data:image/jpeg;base64,...`, usable directly as an `<img>` src. */
  dataUrl: string;
  /** Position and size of the browser in the app page, in CSS px (fixed/viewport coordinates). */
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface StateResult {
  /** True between open() and close(), also while hidden. */
  isOpen: boolean;
  /** True when open and not hidden. */
  isVisible: boolean;
  /** Current page, '' when closed. */
  url: string;
  canGoBack: boolean;
  canGoForward: boolean;
}

export interface InsetBrowserPlugin {
  /** Open the inset browser. If already open, navigates it to `url` (and applies any given insets). */
  open(options: OpenOptions): Promise<void>;
  /** Close and destroy the inset browser. Emits `closed`. */
  close(): Promise<void>;
  navigate(options: NavigateOptions): Promise<void>;
  goBack(): Promise<void>;
  goForward(): Promise<void>;
  reload(): Promise<void>;
  /** Change the free strips. Omitted values keep their current value. */
  setInsets(options: InsetOptions): Promise<void>;
  /** Show a hidden browser (state is kept). */
  show(): Promise<void>;
  /** Hide the browser without destroying it (state is kept). */
  hide(): Promise<void>;
  /**
   * JPEG image of what the visible browser shows, with its position in the
   * app page. Show it in an `<img>` at that position, then `hide()`, so app
   * panels (e.g. a side menu) can appear over a still image of the page.
   * Rejects when the browser is not open or hidden. Not supported on web.
   */
  snapshot(options?: SnapshotOptions): Promise<SnapshotResult>;
  /** Whether the browser is open and visible, and its navigation state. Never rejects. */
  getState(): Promise<StateResult>;

  addListener(eventName: 'pageLoadStarted', listenerFunc: (event: UrlEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'pageLoaded', listenerFunc: (event: UrlEvent) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'pageLoadError',
    listenerFunc: (event: PageLoadErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'navigationChanged',
    listenerFunc: (event: NavigationChangedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'closed', listenerFunc: () => void): Promise<PluginListenerHandle>;
  /**
   * Android only: hardware back pressed while the browser is visible and
   * cannot go back any further. The browser is NOT closed automatically.
   */
  addListener(eventName: 'backButtonPressed', listenerFunc: () => void): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;
}
