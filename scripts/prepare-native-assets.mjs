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
 *                                    #0f0f18 canvas, opaque)
 *
 * After running this script, run:
 *   npx capacitor-assets generate \
 *     --iconBackgroundColor '#0a0a0f' \
 *     --iconBackgroundColorDark '#0a0a0f' \
 *     --splashBackgroundColor '#0f0f18' \
 *     --splashBackgroundColorDark '#0f0f18'
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

// glow-web's brand mark switched from PNG to SVG in PR #193 (gradient
// rebrand). sharp rasterizes SVG natively when `density` is set high
// enough; we render at the largest target output (2732 for splash) so
// the per-canvas resize below downscales rather than upscales.
const SOURCE_LOGO = join(rootDir, 'glow-web/public/assets/Glow_Logo.svg');
const SVG_RASTER_DENSITY = 600;
const OUTPUT_DIR = join(rootDir, 'resources');
const SPLASH_CANVAS_COLOR = { r: 15, g: 15, b: 24, alpha: 1 }; // #0f0f18 (spark_dark, matches HomePage)

// Target canvas sizes
const ICON_SIZE = 1024;
const SPLASH_SIZE = 2732;
// Android cold-launch splash logo target size (xhdpi baseline).
// Used by the layer-list `splash_window` drawable that the launch
// theme points at — Android's per-density auto-scaling lands the
// logo at consistent logical dp on every device (~210dp, larger
// than HomePage's <GlowLogo sizePx={144}> so the cold-launch mark
// reads clearly on its own).
const SPLASH_LOGO_SIZE = 420;

// Logo occupancy on each canvas (as a fraction of the canvas size).
// Values are tuned for the trimmed SVG content (no transparent margin),
// so they land on the visible logo's bounding box rather than the
// SVG viewBox.
const ICON_LOGO_FRACTION = 0.75;       // Full app icon: logo fills ~75% of the canvas
// Match the PWA maskable icon's visual presence (issue #88): at 0.50
// the starburst floated in the dark field and read smaller, softer,
// and flatter than the web icon, whose rays run out to the mask edge.
// Android's nominal 66/108 safe zone is ~63% effective after launcher
// masks. Measured on the trimmed render, the longest ray tip stays
// inside a round launcher mask (72/108 of the canvas) only up to
// ~0.58; at 0.58 it sits tangent to the rim, the near-edge look the
// PWA icon has, and anything higher cuts it. Treat 0.58 as a measured
// ceiling; re-run the circular-mask preview from issue #88 before
// raising it.
const FOREGROUND_LOGO_FRACTION = 0.58; // Adaptive icon foreground
// Match HomePage's <GlowLogo sizePx={144}> on-screen size (~37% of a
// 393-wide phone). This requires androidScaleType=FIT_CENTER (set in
// capacitor.config.ts) so the splash drawable doesn't get stretched
// to fill the screen, which would otherwise nearly double the
// on-screen logo size on Android relative to iOS / HomePage.
const SPLASH_LOGO_FRACTION = 0.37;

const TRANSPARENT = { r: 0, g: 0, b: 0, alpha: 0 };

async function createCenteredLogo({ canvasSize, logoFraction, background, outputPath }) {
  const logoSize = Math.round(canvasSize * logoFraction);

  // Trim the SVG's transparent border to the actual logo content. The
  // gradient mark's viewBox (510x561) is non-square and includes a small
  // built-in margin; without trim, the resize below honors viewBox bounds
  // rather than the visible logo, so the same fraction renders smaller
  // than the previous square-PNG source did. Trim normalizes that.
  const logoBuffer = await sharp(SOURCE_LOGO, { density: SVG_RASTER_DENSITY })
    .trim()
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

  console.log('Preparing native asset sources from Glow_Logo.svg...');

  // icon-only.png: 1024x1024 transparent canvas with the logo at ~80% size.
  // @capacitor/assets fills the canvas via --iconBackgroundColor at generation time.
  await createCenteredLogo({
    canvasSize: ICON_SIZE,
    logoFraction: ICON_LOGO_FRACTION,
    background: TRANSPARENT,
    outputPath: join(OUTPUT_DIR, 'icon-only.png'),
  });
  console.log(`  ✓ icon-only.png (${ICON_SIZE}x${ICON_SIZE}, logo ${Math.round(ICON_LOGO_FRACTION * 100)}%, transparent)`);

  // icon-foreground.png: 1024x1024 transparent canvas with the logo at
  // FOREGROUND_LOGO_FRACTION. @capacitor/assets composites this onto the
  // background color from --iconBackgroundColor.
  await createCenteredLogo({
    canvasSize: ICON_SIZE,
    logoFraction: FOREGROUND_LOGO_FRACTION,
    background: TRANSPARENT,
    outputPath: join(OUTPUT_DIR, 'icon-foreground.png'),
  });
  console.log(`  ✓ icon-foreground.png (${ICON_SIZE}x${ICON_SIZE}, logo ${Math.round(FOREGROUND_LOGO_FRACTION * 100)}% safe zone, transparent)`);

  // splash.png: 2732x2732 with logo at 22% centered on the canvas color.
  // Bake the #0f0f18 spark-dark background into the PNG itself so the
  // LaunchScreen and Android splash render a solid canvas that matches
  // HomePage even if --splashBackgroundColor compositing lags at paint time.
  await createCenteredLogo({
    canvasSize: SPLASH_SIZE,
    logoFraction: SPLASH_LOGO_FRACTION,
    background: SPLASH_CANVAS_COLOR,
    outputPath: join(OUTPUT_DIR, 'splash.png'),
  });
  console.log(`  ✓ splash.png (${SPLASH_SIZE}x${SPLASH_SIZE}, logo ${Math.round(SPLASH_LOGO_FRACTION * 100)}%, baked #0f0f18 canvas)`);

  // splash_logo.png: small transparent canvas with the logo at 100% fill,
  // sized for the Android cold-launch layer-list drawable (separate from
  // the post-launch plugin splash above). Capacitor-assets ignores this;
  // `make assets` post-step copies it into drawable-xhdpi/ for Android's
  // per-density auto-scaling.
  await createCenteredLogo({
    canvasSize: SPLASH_LOGO_SIZE,
    logoFraction: 1.0,
    background: TRANSPARENT,
    outputPath: join(OUTPUT_DIR, 'splash_logo.png'),
  });
  console.log(`  ✓ splash_logo.png (${SPLASH_LOGO_SIZE}x${SPLASH_LOGO_SIZE}, logo 100%, transparent — Android cold-launch)`);

  console.log('\nDone. Next:');
  console.log("  npx capacitor-assets generate --ios --android \\");
  console.log("    --iconBackgroundColor '#0a0a0f' \\");
  console.log("    --iconBackgroundColorDark '#0a0a0f' \\");
  console.log("    --splashBackgroundColor '#0f0f18' \\");
  console.log("    --splashBackgroundColorDark '#0f0f18'");
}

main().catch((err) => {
  console.error('Error:', err);
  process.exit(1);
});
