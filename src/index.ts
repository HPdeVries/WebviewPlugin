import { registerPlugin } from '@capacitor/core';

import type { InsetBrowserPlugin } from './definitions';

const InsetBrowser = registerPlugin<InsetBrowserPlugin>('InsetBrowser', {
  web: () => import('./web').then((m) => new m.InsetBrowserWeb()),
});

export * from './definitions';
export { InsetBrowser };
