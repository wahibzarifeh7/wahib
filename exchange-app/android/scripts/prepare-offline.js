// Builds build/offline-assets/app/ for the offline APK from the desktop app's
// offline source (desktop/app-source/index.html):
//  - the Tailwind Play CDN script + inline config are replaced by tailwind.css,
//    compiled from tailwind.config.js (keep the two themes in sync);
//  - the Google Fonts link is replaced by fonts.css + woff2 files from @fontsource.
// The result needs no network at all.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const root = path.join(__dirname, '..');
const src = path.join(root, '..', 'desktop', 'app-source', 'index.html');
const out = path.join(root, 'build', 'offline-assets', 'app');
const FONTS = {
  // package: weights
  inter: [400, 500, 600, 700, 800],
  fraunces: [500, 600, 700],
  'ibm-plex-mono': [400, 500, 600, 700],
  cairo: [400, 500, 600, 700, 800],
};
const SUBSETS = ['latin', 'latin-ext', 'arabic'];

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(path.join(out, 'fonts'), { recursive: true });

// --- HTML
let html = fs.readFileSync(src, 'utf8');
function replaceOnce(re, replacement, what) {
  if (!re.test(html)) throw new Error('prepare-offline: could not find ' + what + ' in ' + src);
  html = html.replace(re, replacement);
}
replaceOnce(/<link rel="preconnect"[^>]*>\s*<link rel="preconnect"[^>]*>\s*/, '', 'font preconnects');
replaceOnce(/<link href="https:\/\/fonts\.googleapis\.com[^>]*>/,
  '<link rel="stylesheet" href="fonts.css" />', 'Google Fonts link');
replaceOnce(/<script src="https:\/\/cdn\.tailwindcss\.com"><\/script>\s*<script>\s*tailwind\.config[\s\S]*?<\/script>/,
  '<link rel="stylesheet" href="tailwind.css" />', 'Tailwind CDN script and config');
if (/https?:\/\/(?!www\.w3\.org)/.test(html.replace(/xmlns[^ >]*/g, ''))) {
  console.warn('prepare-offline: warning: page still references a remote URL');
}
fs.writeFileSync(path.join(out, 'index.html'), html);

// --- Tailwind
fs.writeFileSync(path.join(root, 'build', 'tailwind-input.css'),
  '@tailwind base;\n@tailwind components;\n@tailwind utilities;\n');
execFileSync(process.execPath, [
  require.resolve('tailwindcss/lib/cli.js'),
  '-c', path.join(root, 'tailwind.config.js'),
  '-i', path.join(root, 'build', 'tailwind-input.css'),
  '-o', path.join(out, 'tailwind.css'),
  '--minify',
], { cwd: root, stdio: 'inherit' });

// --- Fonts
let css = '';
for (const [pkg, weights] of Object.entries(FONTS)) {
  const dir = path.dirname(require.resolve('@fontsource/' + pkg + '/package.json'));
  for (const w of weights) {
    const faces = fs.readFileSync(path.join(dir, w + '.css'), 'utf8').split(/(?=\/\* )/);
    for (const face of faces) {
      const m = /url\(\.\/files\/([^)]+\.woff2)\)/.exec(face);
      if (!m) continue;
      const subset = m[1].replace(pkg + '-', '').replace(/-\d+-normal\.woff2$/, '');
      if (!SUBSETS.includes(subset)) continue;
      fs.copyFileSync(path.join(dir, 'files', m[1]), path.join(out, 'fonts', m[1]));
      css += face
        .replace(/src: [^;]+;/, "src: url(fonts/" + m[1] + ") format('woff2');")
        .replace('font-display: swap;', 'font-display: block;');
    }
  }
}
fs.writeFileSync(path.join(out, 'fonts.css'), css);
console.log('Prepared offline app in', path.relative(root, out),
  '(' + fs.readdirSync(path.join(out, 'fonts')).length + ' font files)');
