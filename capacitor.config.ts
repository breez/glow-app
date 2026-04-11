import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'technology.breez.glow',
  appName: 'Glow',
  webDir: 'glow-web/dist',
  server: {
    // HTTPS scheme required for SharedArrayBuffer support in Android WebView
    androidScheme: 'https',
    // COOP/COEP headers required by the WASM module (Breez Spark SDK)
    headers: {
      'Cross-Origin-Embedder-Policy': 'require-corp',
      'Cross-Origin-Opener-Policy': 'same-origin',
    },
  },
};

export default config;
