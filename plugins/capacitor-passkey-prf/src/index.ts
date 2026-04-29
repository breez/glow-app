import { registerPlugin } from '@capacitor/core';

import type { PasskeyPrfPlugin } from './definitions';

// No web fallback — on web, glow-web uses the SDK's PasskeyProvider directly.
// This plugin only provides the native bridge (iOS/Android).
const PasskeyPrf = registerPlugin<PasskeyPrfPlugin>('PasskeyPrf');

export * from './definitions';
export { PasskeyPrf };
