import { registerPlugin } from '@capacitor/core';
const InsetBrowser = registerPlugin('InsetBrowser', {
    web: () => import('./web').then((m) => new m.InsetBrowserWeb()),
});
export * from './definitions';
export { InsetBrowser };
//# sourceMappingURL=index.js.map