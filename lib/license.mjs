// PC-locked activation + login.
//
// The app is signed for one specific PC. `installer/keys/private.pem` (never shipped — it lives
// only in the dev project) signs a code for a given device ID; this file only holds the matching
// PUBLIC key, which can check a code but never produce one. So a copy of the installed app can be
// moved anywhere, but it will only ever unlock on the PC it was activated for; a new PC needs a new
// code from whoever holds the private key (run `node installer/activate.mjs <deviceId>`).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';

// Public half of the keypair in installer/keys/. Safe to ship: it can verify signatures, not make them.
const PUBLIC_KEY_PEM = `-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEAWctW9HaS+QCQMcTbHnD5XXQDV1CdcUmWy/zFaAYrkVI=
-----END PUBLIC KEY-----
`;
const publicKey = crypto.createPublicKey(PUBLIC_KEY_PEM);

// A tiny base32 (RFC 4648, no padding): unlike base64, every character is safe to uppercase and
// never collides with '-' or a space, so device IDs and codes can be grouped for display and pasted
// back in any mix of case/spacing without corrupting the bytes underneath.
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
function b32encode(buf) {
  let bits = 0, value = 0, out = '';
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}
function b32decode(str) {
  let bits = 0, value = 0;
  const out = [];
  for (const ch of str.toUpperCase()) {
    const idx = B32.indexOf(ch);
    if (idx === -1) continue; // skip spaces, dashes, anything pasted alongside the code
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

const hashId = (raw) => b32encode(crypto.createHash('sha256').update(raw).digest().subarray(0, 10)); // 16 chars

/** The motherboard's own serial, from firmware - survives a Windows reinstall (or even a new disk), unlike
 * anything stored inside Windows itself. Some systems report a placeholder for privacy; those are rejected. */
function biosUuid() {
  try {
    const out = execFileSync('powershell', ['-NoProfile', '-Command', '(Get-CimInstance -ClassName Win32_ComputerSystemProduct).UUID'], {
      encoding: 'utf8',
      timeout: 8000,
    }).trim();
    if (/^[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}$/i.test(out) && !/^(0+|F+)$/i.test(out.replace(/-/g, ''))) return out;
  } catch {
    // No PowerShell/CIM access - fall back below.
  }
  return null;
}

/** The device ID this file used before it could read the BIOS - kept only so PCs activated back then
 * (before a Windows reinstall changes it) don't suddenly need re-activating once this code shipped. */
export function legacyDeviceId() {
  let raw = os.hostname();
  try {
    const out = execFileSync('reg', ['query', 'HKLM\\SOFTWARE\\Microsoft\\Cryptography', '/v', 'MachineGuid'], { encoding: 'utf8' });
    const m = out.match(/MachineGuid\s+REG_SZ\s+([0-9a-f-]+)/i);
    if (m) raw = m[1];
  } catch {
    // No registry access (non-Windows, or blocked) - fall back to the hostname alone.
  }
  return hashId(raw);
}

/** A short, stable ID for this PC. Prefers the motherboard's own serial (survives a Windows reinstall);
 * falls back to a Windows-generated one (does not) only when the motherboard serial isn't readable. */
export function deviceId() {
  const bios = biosUuid();
  return bios ? hashId(bios) : legacyDeviceId();
}

/** Insert spaces every 4 characters, purely for showing/typing a device ID or code. */
export const grouped = (s) => s.match(/.{1,4}/g).join(' ');

/** True if `code` is a genuine signature (made with the private key) over exactly this device ID. */
export function verifyCode(devId, code) {
  try {
    return crypto.verify(null, Buffer.from(devId, 'utf8'), publicKey, b32decode(code));
  } catch {
    return false;
  }
}

const SCRYPT_KEYLEN = 64;
export function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, SCRYPT_KEYLEN);
  return `${salt.toString('hex')}:${hash.toString('hex')}`;
}
export function checkPassword(password, stored) {
  const [saltHex, hashHex] = String(stored || '').split(':');
  if (!saltHex || !hashHex) return false;
  const salt = Buffer.from(saltHex, 'hex');
  const expected = Buffer.from(hashHex, 'hex');
  const actual = crypto.scryptSync(password, salt, SCRYPT_KEYLEN);
  return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

const MIN_PASSWORD = 4;

// Installs from before multiple accounts existed have one `username`/`passHash` pair directly on the
// license. Fold that into a one-item `users` list (as the admin) the first time this file sees it, so
// an existing login keeps working exactly as it did - nobody has to reactivate or reset a password.
function migrateToUsers(s) {
  if (s && !s.users) {
    s.users = s.username ? [{ username: s.username, passHash: s.passHash, role: 'admin', createdAt: s.activatedAt || new Date().toISOString() }] : [];
    delete s.username;
    delete s.passHash;
  }
  return s;
}

/**
 * Reads/writes license.json, kept next to `data/` rather than inside it: it must never be swept up
 * by "download backup" / "restore backup" (those only cover the phone list), and never seeded by the
 * installer (every install must be activated on its own).
 */
export function createLicenseStore(appDir) {
  const file = path.join(appDir, 'license.json');
  let state = null;
  let migrated = false;
  if (fs.existsSync(file)) {
    state = JSON.parse(fs.readFileSync(file, 'utf8'));
    migrated = !state.users;
    migrateToUsers(state);
  }

  const persist = () => fs.writeFileSync(file, JSON.stringify(state, null, 2));
  if (migrated) persist(); // write the new shape back now, rather than waiting on the next unrelated change

  // A code was issued for whichever id was current when it was activated - accept either so a PC that
  // was fine yesterday doesn't lock up just because this file learned a better way to identify it.
  const matchesThisPc = (id) => id === deviceId() || id === legacyDeviceId();

  /** 'never' (not activated), 'locked' (activated for a different PC), or 'ok'. */
  function status() {
    if (!state) return 'never';
    if (!matchesThisPc(state.deviceId) || !verifyCode(state.deviceId, state.code)) return 'locked';
    return 'ok';
  }

  function verifiedCode(code) {
    const devId = deviceId();
    const clean = b32encode(b32decode(code)); // canonical form, whatever spacing/case was pasted in
    if (!verifyCode(devId, clean)) throw new Error('That activation code is not valid for this PC.');
    return { devId, clean };
  }

  const findUser = (username) => state?.users.find((u) => u.username === username);

  function activate({ code, username, password }) {
    const { devId, clean } = verifiedCode(code);
    if (!username || !String(username).trim()) throw new Error('Enter a username.');
    if (!password || String(password).length < MIN_PASSWORD) throw new Error(`The password needs at least ${MIN_PASSWORD} characters.`);
    state = {
      deviceId: devId, code: clean, activatedAt: new Date().toISOString(),
      users: [{ username: String(username).trim(), passHash: hashPassword(password), role: 'admin', createdAt: new Date().toISOString() }],
    };
    persist();
  }

  /** Re-key an existing activation onto this PC's current ID (e.g. after Windows was reinstalled and the
   * fallback ID changed) without touching any accounts already set up. Needs a fresh code either way. */
  function renew(code) {
    if (!state) throw new Error('Nothing to renew yet - activate this PC first.');
    const { devId, clean } = verifiedCode(code);
    state.deviceId = devId;
    state.code = clean;
    state.renewedAt = new Date().toISOString();
    persist();
  }

  /** Returns the matched `{ username, role }` on success, or null. */
  function login(username, password) {
    const u = findUser(username);
    if (!u || !checkPassword(password, u.passHash)) return null;
    return { username: u.username, role: u.role };
  }

  function changePassword(username, currentPassword, newPassword) {
    const u = findUser(username);
    if (!u || !checkPassword(currentPassword, u.passHash)) throw new Error('Current password is wrong.');
    if (!newPassword || newPassword.length < MIN_PASSWORD) throw new Error(`The new password needs at least ${MIN_PASSWORD} characters.`);
    u.passHash = hashPassword(newPassword);
    persist();
  }

  /** Sets someone else's password without knowing their old one - the server only lets an admin call this. */
  function adminSetPassword(username, newPassword) {
    const u = findUser(username);
    if (!u) throw new Error(`No account named "${username}".`);
    if (!newPassword || newPassword.length < MIN_PASSWORD) throw new Error(`The new password needs at least ${MIN_PASSWORD} characters.`);
    u.passHash = hashPassword(newPassword);
    persist();
  }

  function addUser(username, password, role = 'staff') {
    username = String(username || '').trim();
    if (!username) throw new Error('Enter a username.');
    if (findUser(username)) throw new Error(`"${username}" already has an account.`);
    if (!password || password.length < MIN_PASSWORD) throw new Error(`The password needs at least ${MIN_PASSWORD} characters.`);
    state.users.push({ username, passHash: hashPassword(password), role: role === 'admin' ? 'admin' : 'staff', createdAt: new Date().toISOString() });
    persist();
  }

  function removeUser(username) {
    const u = findUser(username);
    if (!u) throw new Error(`No account named "${username}".`);
    if (u.role === 'admin' && state.users.filter((x) => x.role === 'admin').length <= 1) {
      throw new Error('That’s the only admin account - add another admin before removing this one.');
    }
    state.users = state.users.filter((x) => x.username !== username);
    persist();
  }

  const listUsers = () => (state?.users || []).map(({ username, role, createdAt }) => ({ username, role, createdAt }));
  const roleOf = (username) => findUser(username)?.role || null;

  /** The whole license file, for "download a backup" - lets a lost/corrupted license.json be restored
   * without a new code, as long as this is still the same PC. Restoring it onto a different PC just
   * comes back "locked", same as any other mismatched code. */
  function exportBackup() {
    if (!state) throw new Error('Nothing to back up yet - this copy isn’t activated.');
    return state;
  }

  function importBackup(data) {
    if (data && typeof data === 'object') migrateToUsers(data);
    if (!data || typeof data !== 'object' || !data.deviceId || !data.code || !Array.isArray(data.users) || !data.users.length) {
      throw new Error('That doesn’t look like a Glass Finder activation backup file.');
    }
    state = data;
    persist();
  }

  return {
    status, activate, renew, login, changePassword, adminSetPassword, addUser, removeUser, listUsers, roleOf,
    exportBackup, importBackup, deviceId,
  };
}
