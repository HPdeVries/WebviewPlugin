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
| Android | `android.webkit.WebView`, added to the Capacitor webview's parent with margins (insets + safe area, incl. side cutouts in landscape) |
| Web | fixed `<iframe>` (dev only; many sites refuse framing) |

Supports Capacitor 7 and 8 (iOS 14+ / Android API 23+; SPM and CocoaPods). Tested on
Capacitor 7.6 and 8.6, iOS 26 simulator and Android 16 emulator, in edge-to-edge,
non-edge-to-edge and `adjustMarginsForEdgeToEdge: "force"` configurations.

## API

```ts
open({ url, insetBottom?, insetTop?, includeSafeArea?, incognito? }): Promise<void>
close(): Promise<void>
navigate({ url }): Promise<void>
goBack() / goForward() / reload(): Promise<void>
setInsets({ insetBottom?, insetTop? }): Promise<void>
show() / hide(): Promise<void>   // toggle visibility, keep state
snapshot({ quality? }): Promise<{ dataUrl, left, top, width, height }>
```

- `insetTop` / `insetBottom`: CSS px (= pt on iOS, dp on Android), **excluding** safe area. Default `0`.
- `includeSafeArea` (default `true`): the safe area (notch, home indicator, system bars) is added
  to the inset. So with `insetBottom: 56` the free strip is `56px + env(safe-area-inset-bottom)` —
  exactly what a menu styled `height:56px; padding-bottom:env(safe-area-inset-bottom)` occupies.
- `incognito` (default `false`): non-persistent data store. **iOS only**; on Android the cookie
  store is process-wide and shared with the app webview.
- Only one instance exists. `open()` while open navigates it (and applies any insets passed) and makes it visible.
- Layout is recalculated on rotation/resize automatically.

### Panels over the browser (`snapshot`)

The inset browser is a native view above the app page, so HTML panels (side menu, popups)
open *under* it. To show a panel over it, freeze the browser first: take a snapshot, put it
in an `<img>` at the returned position (CSS px, fixed coordinates), then hide the browser.
Undo it when the panel closes:

```js
async function freeze() {
  const s = await InsetBrowser.snapshot();            // JPEG data URL + frame
  const img = document.createElement('img');
  img.id = 'inset-browser-freeze';
  img.src = s.dataUrl;
  img.style.cssText = `position:fixed;left:${s.left}px;top:${s.top}px;width:${s.width}px;` +
    `height:${s.height}px;z-index:1;pointer-events:none`;   // below the panel
  document.body.appendChild(img);
  await img.decode().catch(() => {});                 // no flicker
  await InsetBrowser.hide();
}
async function unfreeze() {
  await InsetBrowser.show();
  document.getElementById('inset-browser-freeze')?.remove();
}
```

The image must sit below the panel and its backdrop (low `z-index`, or append it to a
container the panel covers). `snapshot()` rejects when the browser is closed or hidden. On
Android it uses `PixelCopy` (API 26+), so video/canvas content is included.

### Events

| Event | Payload |
|---|---|
| `pageLoadStarted` | `{ url }` |
| `pageLoaded` | `{ url }` |
| `pageLoadError` | `{ url, message }` (main-frame network errors) |
| `navigationChanged` | `{ url, canGoBack, canGoForward }` — once per committed navigation (incl. pushState/#hash); not repeated for reloads |
| `closed` | `{}` |
| `backButtonPressed` | `{}` — Android only, see below |

Event notes:
- `pageLoadError` is not followed by `pageLoaded` on either platform.
- Android shows its own error page and adds it to history, so a failed load also emits
  `navigationChanged` with the failed URL and `canGoBack: true`. On iOS a failed load leaves the
  current page and history unchanged.
- On Android, `pageLoadStarted` is reported when the WebView starts the page (for unreachable hosts
  that can be after the DNS timeout); on iOS it is reported immediately.

**Android back button:** while the browser is visible, back navigates inside it. When it
cannot go back, `backButtonPressed` is emitted and nothing else happens — the host app decides
(e.g. call `close()`). While hidden/closed, back is handled by the app as usual. This takes
priority over other back handlers such as `@capacitor/app`, regardless of plugin load order.

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
   (pin a tag or commit). `dist/` is committed and there is no `prepare` script, so a git install
   needs no build step.
2. **Create a Mobile Library** in ODC Studio (e.g. `InsetBrowserPlugin`) and set its
   **extensibility configuration** (MABS 12+, Capacitor apps). The plugin source is an
   [npm package spec](https://docs.npmjs.com/cli/using-npm/package-spec), so both a registry
   version and a git URL work:

   ```json
   {
     "buildConfigurations": {
       "capacitor": {
         "source": {
           "npm": "git+https://github.com/HPdeVries/WebviewPlugin.git#v0.2.0"
         }
       }
     }
   }
   ```

   If published to npm, use `"npm": "capacitor-inset-browser@0.2.0"` instead. The plugin needs no
   permissions (INTERNET is declared in its own manifest), so no `pluginConfigurations` are needed.
   Schema: [Library (plugin) extensibility configuration JSON schema](https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/mobile_apps/configure_mobile_apps/universal_extensibility_configurations_json_schema/library_plugin_extensibility_configuration_json_schema/),
   guide: [Integrate Capacitor plugin into a mobile app](https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/mobile_apps/use_mobile_plugins/integrate_capacitor_plugin_into_a_mobile_app/).
   The ODC docs only show registry specs (`name@version`); a git spec is valid npm syntax but
   confirm it in a MABS build, or publish to npm to be safe.

3. **Wrap the API in client actions** with a *JavaScript* node each. MABS bundles the plugin's JS
   and exposes it as `window.CapacitorPlugins.InsetBrowser` (the package's export name);
   `Capacitor.Plugins.InsetBrowser` is the native proxy and works too. Check availability first:

   **Open** (inputs `Url` text, `InsetBottom` integer):
   ```js
   var InsetBrowser = (window.CapacitorPlugins && window.CapacitorPlugins.InsetBrowser) ||
     (window.Capacitor && Capacitor.isPluginAvailable('InsetBrowser') && Capacitor.Plugins.InsetBrowser);
   if (!InsetBrowser) {
     $parameters.Error = 'InsetBrowser plugin not available';
     $resolve();
     return;
   }
   InsetBrowser.open({
     url: $parameters.Url,
     insetBottom: $parameters.InsetBottom
   }).then($resolve).catch(function (e) {
     $parameters.Error = e.message; $resolve();
   });
   ```
   (Mark the JavaScript node as asynchronous / use `$resolve` so the action waits.)

   **Close**:
   ```js
   window.CapacitorPlugins.InsetBrowser.close().then($resolve);
   ```

   **Listen to events** (e.g. in the menu block's OnReady; remove in OnDestroy):
   ```js
   window.CapacitorPlugins.InsetBrowser.addListener('navigationChanged', function (e) {
     $actions.OnNavigationChanged(e.url, e.canGoBack);
   });
   window.CapacitorPlugins.InsetBrowser.addListener('backButtonPressed', function () {
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
