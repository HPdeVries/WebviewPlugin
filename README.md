# capacitor-inset-browser

Capacitor plugin **InsetBrowser**: shows external web content in a native webview
while keeping a strip of the host app (bottom or top menu) visible and tappable.

Unlike the standard InAppBrowser it does not open a fullscreen activity/view
controller. It adds a second native webview **on top of** the Capacitor webview,
sized to leave `insetTop` / `insetBottom` free. Taps in the free strip reach the
Capacitor webview, because nothing covers it.

| Platform | Implementation |
|---|---|
| iOS | `WKWebView`, subview of the Capacitor webview (which is the bridge VC's root view), Auto Layout to the safe area |
| Android | `android.webkit.WebView`, added to the Capacitor webview's parent with top/bottom margins |
| Web | fixed `<iframe>` (dev only; many sites refuse framing) |

Requires Capacitor 7+.

## API

```ts
open({ url, insetBottom?, insetTop?, includeSafeArea?, incognito? }): Promise<void>
close(): Promise<void>
navigate({ url }): Promise<void>
goBack() / goForward() / reload(): Promise<void>
setInsets({ insetBottom?, insetTop? }): Promise<void>
show() / hide(): Promise<void>   // toggle visibility, keep state
```

- `insetTop` / `insetBottom`: CSS px (= pt on iOS, dp on Android), **excluding** safe area. Default `0`.
- `includeSafeArea` (default `true`): the safe area (notch, home indicator, system bars) is added
  to the inset. So with `insetBottom: 56` the free strip is `56px + env(safe-area-inset-bottom)` —
  exactly what a menu styled `height:56px; padding-bottom:env(safe-area-inset-bottom)` occupies.
- `incognito` (default `false`): non-persistent data store. **iOS only**; on Android the cookie
  store is process-wide and shared with the app webview.
- Only one instance exists. `open()` while open navigates it (and applies any insets passed) and makes it visible.
- Layout is recalculated on rotation/resize automatically.

### Events

| Event | Payload |
|---|---|
| `pageLoadStarted` | `{ url }` |
| `pageLoaded` | `{ url }` |
| `pageLoadError` | `{ url, message }` (main-frame network errors) |
| `navigationChanged` | `{ url, canGoBack, canGoForward }` |
| `closed` | `{}` |
| `backButtonPressed` | `{}` — Android only, see below |

**Android back button:** while the browser is visible, back navigates inside it. When it
cannot go back, `backButtonPressed` is emitted and nothing else happens — the host app decides
(e.g. call `close()`). While hidden/closed, back is handled by the app as usual.

## Usage from JavaScript

```js
const InsetBrowser = Capacitor.Plugins.InsetBrowser;

await InsetBrowser.addListener('navigationChanged', (e) => console.log(e.url, e.canGoBack));
await InsetBrowser.addListener('backButtonPressed', () => InsetBrowser.close());

await InsetBrowser.open({ url: 'https://example.com', insetBottom: 56 });
// ...
await InsetBrowser.close();
```

With a bundler: `import { InsetBrowser } from 'capacitor-inset-browser';`

A full minimal page is in [`example/index.html`](example/index.html).

## OutSystems ODC integration

1. **Make the plugin reachable.** Either publish to npm, or reference this git repo
   (pin a tag/commit). `dist/` is committed, so a git install works without a build step.
2. **Create a Mobile Library** in ODC Studio (e.g. `InsetBrowserPlugin`) and set its
   **extensibility configuration** to reference the plugin, e.g.:

   ```json
   {
     "plugin": {
       "url": "https://github.com/HPdeVries/WebviewPlugin.git#v0.1.0"
     }
   }
   ```

   > ⚠️ Verify this JSON against the current ODC docs ("Mobile app extensibility
   > settings" / MABS 12 Capacitor plugins); the exact keys (e.g. git URL vs npm
   > name, `mobile frameworks` = Capacitor) could not be confirmed while writing this.

3. **Wrap the API in client actions** with a *JavaScript* node each. Check availability first:

   **Open** (inputs `Url` text, `InsetBottom` integer):
   ```js
   if (!window.Capacitor || !Capacitor.isPluginAvailable('InsetBrowser')) {
     $parameters.Error = 'InsetBrowser plugin not available';
     return;
   }
   Capacitor.Plugins.InsetBrowser.open({
     url: $parameters.Url,
     insetBottom: $parameters.InsetBottom
   }).then($resolve).catch(function (e) {
     $parameters.Error = e.message; $resolve();
   });
   ```
   (Mark the JavaScript node as asynchronous / use `$resolve` so the action waits.)

   **Close**:
   ```js
   Capacitor.Plugins.InsetBrowser.close().then($resolve);
   ```

   **Listen to events** (e.g. in the menu block's OnReady; remove in OnDestroy):
   ```js
   Capacitor.Plugins.InsetBrowser.addListener('navigationChanged', function (e) {
     $actions.OnNavigationChanged(e.url, e.canGoBack);
   });
   Capacitor.Plugins.InsetBrowser.addListener('backButtonPressed', function () {
     $actions.OnBackPressed();
   });
   ```

4. Reference the library from your mobile app, generate a new mobile package, and test on device
   (native plugins don't run in the browser preview; there the iframe fallback is used only if the
   JS package is bundled).

5. Size `insetBottom` to your menu's height in CSS px, without safe area.

## Limitations

- No file uploads (`<input type=file>`), downloads, or permission prompts (camera/geo) inside the
  inset browser yet.
- `target="_blank"` / `window.open` links load in the same inset browser.
- Android: cookies are shared with the app webview; `incognito` is ignored.
- Web fallback cannot observe cross-origin history; `canGoBack`/`canGoForward` are always `false`.

## Build

```bash
npm install
npm run build   # tsc + rollup -> dist/
```
