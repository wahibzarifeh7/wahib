// Publishes ONLY the web app. The site's publish directory used to be the whole
// project folder, which put the backend source, database schema/seed script and the
// desktop installer on the public internet. Now just index.html is uploaded;
// server code lives in netlify/functions and is deployed as functions, not files.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const out = path.join(root, 'public');

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
fs.copyFileSync(path.join(root, 'index.html'), path.join(out, 'index.html'));
console.log('Built public/ with:', fs.readdirSync(out).join(', '));
