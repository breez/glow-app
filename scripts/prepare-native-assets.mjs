#!/usr/bin/env node
/**
 * Prepare native asset source files for @capacitor/assets.
 *
 * Reads the Glow logo from the glow-web submodule and produces three
 * transparent-background PNGs in ./resources that @capacitor/assets
 * consumes to generate every iOS AppIcon size, Android mipmap density,
 * and splash variant.
 *
 * Outputs:
 *   - resources/icon-only.png      (1024x1024, logo centered, transparent)
 *   - resources/icon-foreground.png (1024x1024, logo at adaptive-icon
 *                                    safe-zone size, transparent)
 *   - resources/splash.png         (2732x2732, logo centered on
 *                                    #0a0a0f canvas, opaque)
 *
 * After running this script, run:
 *   npx capacitor-assets generate \
 *     --iconBackgroundColor '#0a0a0f' \
 *     --iconBackgroundColorDark '#0a0a0f' \
 *     --splashBackgroundColor '#0a0a0f' \
 *     --splashBackgroundColorDark '#0a0a0f'
 *
 * or the `make assets` target which runs both in sequence.
 */

import sharp from 'sharp';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { existsSync, mkdirSync } from 'fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const rootDir = join(__dirname, '..');

const SOURCE_LOGO = join(rootDir, 'glow-web/public/assets/Glow_Logo.png');
const OUTPUT_DIR = join(rootDir, 'resources');
const CANVAS_COLOR = { r: 10, g: 10, b: 15, alpha: 1 }; // #0a0a0f

// Target canvas sizes
const ICON_SIZE = 1024;
const SPLASH_SIZE = 2732;

// Logo occupancy on each canvas (as a fraction of the canvas size)
const ICON_LOGO_FRACTION = 0.80;       // Full app icon: logo fills ~80% of the canvas
const FOREGROUND_LOGO_FRACTION = 0.66; // Adaptive icon foreground: safe zone is ~66%
const SPLASH_LOGO_FRACTION = 0.22;     // Splash: logo is a small centered element

const TRANSPARENT = { r: 0, g: 0, b: 0, alpha: 0 };

async function createCenteredLogo({ canvasSize, logoFraction, background, outputPath }) {
  const logoSize = Math.round(canvasSize * logoFraction);

  // Resize the logo with aspect-ratio preservation.
  const logoBuffer = await sharp(SOURCE_LOGO)
    .resize(logoSize, logoSize, {
      fit: 'contain',
      background: TRANSPARENT,
    })
    .png()
    .toBuffer();

  // Composite the logo onto the canvas (transparent or solid).
  await sharp({
    create: {
      width: canvasSize,
      height: canvasSize,
      channels: 4,
      background,
    },
  })
    .composite([{ input: logoBuffer, gravity: 'center' }])
    .png({ quality: 90, compressionLevel: 9 })
    .toFile(outputPath);
}

async function main() {
  if (!existsSync(SOURCE_LOGO)) {
    console.error(`Source logo not found: ${SOURCE_LOGO}`);
    console.error('Is the glow-web submodule checked out?');
    process.exit(1);
  }

  if (!existsSync(OUTPUT_DIR)) {
    mkdirSync(OUTPUT_DIR, { recursive: true });
  }

  console.log('Preparing native asset sources from Glow_Logo.png...');

  // icon-only.png: 1024x1024 transparent canvas with the logo at ~80% size.
  // @capacitor/assets fills the canvas via --iconBackgroundColor at generation time.
  await createCenteredLogo({
    canvasSize: ICON_SIZE,
    logoFraction: ICON_LOGO_FRACTION,
    background: TRANSPARENT,
    outputPath: join(OUTPUT_DIR, 'icon-only.png'),
  });
  console.log(`  ✓ icon-only.png (${ICON_SIZE}x${ICON_SIZE}, logo ${Math.round(ICON_LOGO_FRACTION * 100)}%, transparent)`);

  // icon-foreground.png: 1024x1024 transparent canvas with the logo at 66%
  // (adaptive icon safe zone). @capacitor/assets composites this onto the
  // background color from --iconBackgroundColor.
  await createCenteredLogo({
    canvasSize: ICON_SIZE,
    logoFraction: FOREGROUND_LOGO_FRACTION,
    background: TRANSPARENT,
    outputPath: join(OUTPUT_DIR, 'icon-foreground.png'),
  });
  console.log(`  ✓ icon-foreground.png (${ICON_SIZE}x${ICON_SIZE}, logo ${Math.round(FOREGROUND_LOGO_FRACTION * 100)}% safe zone, transparent)`);

  // splash.png: 2732x2732 with logo at 22% centered on the canvas color.
  // Bake the #0a0a0f background into the PNG itself so the LaunchScreen
  // and Android splash render a solid dark canvas even if
  // --splashBackgroundColor compositing lags at paint time.
  await createCenteredLogo({
    canvasSize: SPLASH_SIZE,
    logoFraction: SPLASH_LOGO_FRACTION,
    background: CANVAS_COLOR,
    outputPath: join(OUTPUT_DIR, 'splash.png'),
  });
  console.log(`  ✓ splash.png (${SPLASH_SIZE}x${SPLASH_SIZE}, logo ${Math.round(SPLASH_LOGO_FRACTION * 100)}%, baked #0a0a0f canvas)`);

  console.log('\nDone. Next:');
  console.log("  npx capacitor-assets generate \\");
  console.log("    --iconBackgroundColor '#0a0a0f' \\");
  console.log("    --iconBackgroundColorDark '#0a0a0f' \\");
  console.log("    --splashBackgroundColor '#0a0a0f' \\");
  console.log("    --splashBackgroundColorDark '#0a0a0f'");
}

main().catch((err) => {
  console.error('Error:', err);
  process.exit(1);
});
