import { registerPlugin } from '@capacitor/core';

import type { NativeVaultPlugin } from './definitions';

// No web fallback — on web, glow-web's secureStorage abstraction selects
// NoopSecureStorage and never reaches into this plugin.
const NativeVault = registerPlugin<NativeVaultPlugin>('NativeVault');

export * from './definitions';
export { NativeVault };
