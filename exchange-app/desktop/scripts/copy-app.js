// Copies the offline desktop app source into desktop/app/ before packaging.
//
// This intentionally does NOT pull from exchange-app/index.html — that file
// is the live, API-backed build (shared Supabase data, server-enforced
// single-session login), meant for the web deploy. The desktop build is the
// fully offline, localStorage-only variant, maintained separately here in
// app-source/ since the two have genuinely different data layers.
const fs = require('fs');
const path = require('path');

const src = path.join(__dirname, '..', 'app-source', 'index.html');
const destDir = path.join(__dirname, '..', 'app');
const dest = path.join(destDir, 'index.html');

if (!fs.existsSync(src)) {
  console.error('Cannot find', src);
  process.exit(1);
}
fs.mkdirSync(destDir, { recursive: true });
fs.copyFileSync(src, dest);
console.log('Copied', src, '->', dest);
