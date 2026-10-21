// Dev-only: generates an activation code for one PC. Needs installer/keys/private.pem, which is
// never shipped in the installer, so only someone with this project can run this.
//
// Usage:
//   node installer/activate.mjs                  (this PC's own device ID, if run on Windows)
//   node installer/activate.mjs <DEVICE-ID>       (a device ID someone sent you)
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { deviceId, grouped } from '../lib/license.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const keyPath = path.join(here, 'keys', 'private.pem');
if (!fs.existsSync(keyPath)) {
  console.error(`Missing ${keyPath}.\nThis is the one file that can authorize a new PC - if it's gone, generate a new keypair\n(see installer/keys/) and re-activate every existing install with a code from the new key.`);
  process.exit(1);
}
const privateKey = crypto.createPrivateKey(fs.readFileSync(keyPath, 'utf8'));

const devId = (process.argv[2] || deviceId()).toUpperCase().replace(/[^A-Z2-7]/g, '');
if (!devId) {
  console.error('Usage: node installer/activate.mjs <DEVICE-ID>');
  process.exit(1);
}

const sigBuf = crypto.sign(null, Buffer.from(devId, 'utf8'), privateKey);
// Re-encode with the same base32 helper the app uses, without importing its private internals.
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
let bits = 0, value = 0, code = '';
for (const byte of sigBuf) {
  value = (value << 8) | byte;
  bits += 8;
  while (bits >= 5) {
    code += B32[(value >>> (bits - 5)) & 31];
    bits -= 5;
  }
}
if (bits > 0) code += B32[(value << (5 - bits)) & 31];

console.log(`Device ID:       ${grouped(devId)}`);
console.log(`Activation code: ${grouped(code)}`);
