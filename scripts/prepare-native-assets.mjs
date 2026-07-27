#!/usr/bin/env node
/**
 * Prepare native asset source files for @capacitor/assets.
 *
 * Reads the Glow logo from the glow-web submodule and produces the
 * source PNGs in ./resources that @capacitor/assets consumes to
 * generate every iOS AppIcon size, Android mipmap density, and splash
 * variant, plus per-density Android splash drawables that the `make
 * assets` post-step copies into android/res directly.
 *
 * Outputs:
 *   - resources/icon-only.png      (1024x1024, logo centered, transparent)
 *   - resources/icon-foreground.png (1024x1024, logo at adaptive-icon
 *                                    safe-zone size, transparent)
 *   - resources/splash.png         (2732x2732, logo centered on
 *                                    #0f0f18 canvas, opaque)
 *   - resources/splash_logo-<density>.png (144dp cold-launch logo,
 *                                    mdpi..xxxhdpi, transparent)
 *   - resources/splash_icon-<density>.png (288dp Android 12+ system
 *                                    splash icon, mdpi..xxxhdpi,
 *                                    transparent)
 *   - resources/splash_mark@<scale>x.png (144pt iOS launch mark,
 *                                    1x..3x, transparent)
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
// The one size the mark is drawn at anywhere on the launch path, as a
// square box the mark is fit-contained into (so it renders 130.9 wide
// by 144 tall). Android reads it as dp, iOS as pt, glow-web as CSS px:
// index.html's .splash-logo and the w-36 launch screens use the same
// box, so nothing resizes as the launch hands off from the system
// splash to the WebView to the first React screen.
//
// It must be absolute, not a fraction of screen width. The Android 12+
// splash icon is specced in dp and does not scale with the screen, so
// a viewport-relative counterpart can only agree with it at a single
// screen width. Sizing the native asset to chase a 33.6vw web logo is
// what left issue #122 open: it matched at ~351dp and diverged by up
// to 37% on wider phones.
//
// Ceiling is 166dp. The system masks the splash icon to config_icon_mask
// inscribed in the 192dp icon view, and the mark's ray tips reach
// 0.5758 of its box side, so 96/0.5758 = 166.7dp before a tip clips.
// (The same measurement reproduces the 0.58 launcher ceiling below.)
const MARK_BOX_DP = 144;

// Android cold-launch splash logo, emitted once per density bucket. A
// single mid-density asset is not enough: Android upscales it 1.5x/2x
// on xxhdpi/xxxhdpi devices, which reads as a soft, blurry logo.
// Feeds the Capacitor plugin's ImageView fallback, which is only
// reached if the Android 12 splash path throws.
const SPLASH_LOGO_DP = MARK_BOX_DP;

// Android 12+ system splash icon (windowSplashScreenAnimatedIcon in
// styles.xml), and via core-splashscreen's compat drawable the whole
// splash on Android 11 and below. Without an explicit icon the system
// falls back to the launcher icon. Platform spec for an icon with no
// icon background: the drawable is painted across a 288dp box centred
// on a 192dp icon view, and masked to that view.
//
// Note the system rasterises any non-Animatable drawable at 108dp and
// upscales it ~1.78x, so this renders soft no matter what density it
// ships at. Only an AnimatedVectorDrawable escapes that path.
const SPLASH_ICON_DP = 288;
const SPLASH_ICON_LOGO_FRACTION = MARK_BOX_DP / SPLASH_ICON_DP;

// iOS launch image, emitted at 1x/2x/3x. The mark alone on a
// transparent canvas: LaunchScreen.storyboard centres it at a fixed
// MARK_BOX_DP and paints the canvas colour itself. A full-screen
// image would be aspect-fit to the screen and so scale with device
// width, which is the iOS form of the same bug.
const IOS_SCALES = [1, 2, 3];

// Density buckets for android/res drawable-<bucket>/ outputs.
const ANDROID_DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

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
// Full-screen splash canvas. Only feeds capacitor-assets, which
// requires a splash input; the launch surfaces it generates are all
// overwritten by the `make assets` post-step with the fixed-size
// MARK_BOX_DP assets above.
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

  // splash_logo-<density>.png: transparent canvas with the logo at 100%
  // fill, one per density bucket, for the Android cold-launch layer-list
  // drawable (separate from the post-launch plugin splash above).
  // Capacitor-assets ignores these; the `make assets` post-step copies
  // each into android/res drawable-<density>/splash_logo.png.
  for (const [bucket, scale] of Object.entries(ANDROID_DENSITIES)) {
    const size = Math.round(SPLASH_LOGO_DP * scale);
    await createCenteredLogo({
      canvasSize: size,
      logoFraction: 1.0,
      background: TRANSPARENT,
      outputPath: join(OUTPUT_DIR, `splash_logo-${bucket}.png`),
    });
    console.log(`  ✓ splash_logo-${bucket}.png (${size}x${size}, ${SPLASH_LOGO_DP}dp, transparent)`);
  }

  // splash_icon-<density>.png: Android 12+ system splash icon, one per
  // density bucket. The `make assets` post-step copies each into
  // android/res drawable-<density>/splash_icon.png; styles.xml points
  // windowSplashScreenAnimatedIcon at it.
  for (const [bucket, scale] of Object.entries(ANDROID_DENSITIES)) {
    const size = Math.round(SPLASH_ICON_DP * scale);
    await createCenteredLogo({
      canvasSize: size,
      logoFraction: SPLASH_ICON_LOGO_FRACTION,
      background: TRANSPARENT,
      outputPath: join(OUTPUT_DIR, `splash_icon-${bucket}.png`),
    });
    console.log(`  ✓ splash_icon-${bucket}.png (${size}x${size}, ${SPLASH_ICON_DP}dp, logo ${Math.round(SPLASH_ICON_LOGO_FRACTION * 100)}%, transparent)`);
  }

  // splash_mark@<scale>x.png: the iOS launch image. capacitor-assets
  // regenerates Splash.imageset from splash.png above, so the `make
  // assets` post-step copies these over its three renditions.
  for (const scale of IOS_SCALES) {
    const size = MARK_BOX_DP * scale;
    await createCenteredLogo({
      canvasSize: size,
      logoFraction: 1.0,
      background: TRANSPARENT,
      outputPath: join(OUTPUT_DIR, `splash_mark@${scale}x.png`),
    });
    console.log(`  ✓ splash_mark@${scale}x.png (${size}x${size}, ${MARK_BOX_DP}pt, transparent)`);
  }

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
