// Glass Finder: a tiny local web server (no dependencies). Run with `node server.mjs`.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { createStore } from './lib/store.mjs';
import { createImageService, testSerperKey } from './lib/images.mjs';
import { createLicenseStore, grouped } from './lib/license.mjs';
import { createSettingsStore } from './lib/settings.mjs';
import { createRememberStore } from './lib/remember.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const dataDir = process.env.GLASS_DATA_DIR || path.join(root, 'data');
const imagesDir = path.join(dataDir, 'images');
const publicDir = path.join(root, 'public');
const PORT = Number(process.env.PORT) || 3737;
const HOST = process.env.HOST || '127.0.0.1';
const licenseDir = process.env.GLASS_LICENSE_DIR || root;

const store = createStore(dataDir);
const settings = createSettingsStore(licenseDir);
const images = createImageService(store, imagesDir, settings);
const license = createLicenseStore(licenseDir);
const remember = createRememberStore(licenseDir);

// Plain logins are in-memory only: everyone has to log back in after the app restarts, which is
// expected since closing the console window is how this app is normally stopped. "Remember me"
// logins are the opt-in exception - see lib/remember.mjs. Both map a token to WHO it belongs to, so
// actions can be attributed to a real person in the activity log.
const sessions = new Map(); // token -> { username, role }
function getCookie(req, name) {
  for (const part of (req.headers.cookie || '').split(';')) {
    const i = part.indexOf('=');
    if (i > -1 && part.slice(0, i).trim() === name) return decodeURIComponent(part.slice(i + 1).trim());
  }
  return null;
}
/** `{ username, role } | null` for whoever this request is signed in as. */
function currentUser(req) {
  const token = getCookie(req, 'gf_session');
  if (!token) return null;
  const s = sessions.get(token);
  if (s) return s;
  const r = remember.get(token);
  return r ? { username: r.username, role: license.roleOf(r.username) } : null;
}
const isLoggedIn = (req) => !!currentUser(req);
function requireAdmin(req) {
  const u = currentUser(req);
  if (!u || u.role !== 'admin') throw new HttpError(403, 'Only an admin can do that.');
  return u;
}
function loginCookie(user, rememberMe) {
  const token = crypto.randomBytes(24).toString('base64url');
  if (rememberMe) {
    remember.add(token, user.username);
    return { 'Set-Cookie': `gf_session=${token}; HttpOnly; Path=/; SameSite=Lax; Max-Age=${90 * 24 * 3600}` };
  }
  sessions.set(token, user);
  return { 'Set-Cookie': `gf_session=${token}; HttpOnly; Path=/; SameSite=Lax` };
}

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.avif': 'image/avif',
  '.ico': 'image/x-icon',
};

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const send = (res, status, body, headers = {}) => {
  const text = typeof body === 'string';
  res.writeHead(status, {
    'Content-Type': text ? 'text/plain; charset=utf-8' : 'application/json',
    'Cache-Control': 'no-store',
    ...headers,
  });
  res.end(text ? body : JSON.stringify(body));
};

function readBody(req, limit = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new HttpError(413, 'That file is too large'));
        req.destroy();
      } else chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

async function readJson(req, limit = 2 * 1024 * 1024) {
  const raw = (await readBody(req, limit)).toString('utf8');
  try {
    return raw ? JSON.parse(raw) : {};
  } catch {
    throw new HttpError(400, 'Invalid JSON');
  }
}

const missList = () =>
  Object.entries(store.data.misses).map(([key, m]) => ({ key, ...m })).sort((a, b) => b.count - a.count || b.last.localeCompare(a.last));
// Only boxes that don't currently exist are worth offering to restore - a reused number's old record
// is still kept (in case a future delete of the reused box should merge with it), just not surfaced.
const deletedBoxList = () => store.data.deletedBoxes.filter((d) => !(d.box in store.data.boxes)).sort((a, b) => b.deletedAt.localeCompare(a.deletedAt));
const FREQUENT_KEPT = 8;
const frequentList = () =>
  Object.entries(store.data.hits)
    .sort((a, b) => b[1].count - a[1].count || b[1].last.localeCompare(a[1].last))
    .map(([id]) => store.byId(id))
    .filter(Boolean)
    .slice(0, FREQUENT_KEPT);
const snapshot = () => ({
  models: store.data.models,
  boxes: store.data.boxes,
  misses: missList(),
  deletedBoxes: deletedBoxList(),
  frequent: frequentList(),
  lowStockThreshold: settings.lowStockThreshold,
  images: images.status(),
});

function needModel(id) {
  const m = store.byId(id);
  if (!m) throw new HttpError(404, 'Model not found');
  return m;
}

// Errors from the photo code are already worded for the user; pass them on as-is.
async function plain(fn) {
  try {
    return await fn();
  } catch (e) {
    throw e instanceof HttpError ? e : new HttpError(400, e.message === 'fetch failed' ? 'Could not reach that website' : e.message);
  }
}

function csv() {
  const esc = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const flat = store.data.models.flatMap((m) => m.boxes.map((b) => [b, m.brand, m.name]));
  flat.sort((a, b) => a[0] - b[0] || a[1].localeCompare(b[1]) || a[2].localeCompare(b[2], undefined, { numeric: true }));
  return '﻿' + ['Box,Brand,Model', ...flat.map((r) => r.map(esc).join(','))].join('\r\n');
}

async function api(req, res, url) {
  const p = url.pathname.replace(/^\/api/, '');
  const m = req.method;
  let match;

  // These have to work before the PC is activated and before anyone is logged in.
  if (m === 'GET' && p === '/auth') {
    const me = currentUser(req);
    return send(res, 200, { status: license.status(), deviceId: grouped(license.deviceId()), loggedIn: !!me, username: me?.username || null, role: me?.role || null });
  }
  if (m === 'POST' && p === '/activate') {
    const body = await readJson(req);
    try {
      license.activate(body);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    return send(res, 200, { ok: true }, loginCookie({ username: String(body.username).trim(), role: 'admin' }, false));
  }
  if (m === 'POST' && p === '/renew') {
    // Re-keys the device binding after e.g. a Windows reinstall - deliberately does NOT log anyone
    // in, so a code alone (without the password) still can't get past the login screen.
    try {
      license.renew((await readJson(req)).code);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    return send(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/restore-license') {
    try {
      license.importBackup(await readJson(req, 64 * 1024));
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    return send(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/login') {
    const { username, password, remember: rememberMe } = await readJson(req);
    if (license.status() !== 'ok') throw new HttpError(403, 'This copy needs to be activated first.');
    const user = license.login(username, password);
    if (!user) throw new HttpError(401, 'Wrong username or password.');
    return send(res, 200, { ok: true }, loginCookie(user, !!rememberMe));
  }
  if (m === 'POST' && p === '/logout') {
    const token = getCookie(req, 'gf_session');
    sessions.delete(token);
    remember.remove(token);
    return send(res, 200, { ok: true }, { 'Set-Cookie': 'gf_session=; Max-Age=0; Path=/' });
  }

  // Everything below needs an activated PC and a logged-in session.
  if (license.status() !== 'ok') throw new HttpError(403, 'This copy needs to be activated first.');
  const me = currentUser(req);
  if (!me) throw new HttpError(401, 'Please log in.');

  if (m === 'POST' && p === '/change-password') {
    const { currentPassword, newPassword } = await readJson(req);
    try {
      license.changePassword(me.username, currentPassword, newPassword);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    remember.clearForUser(me.username); // a new password should mean every remembered device for THIS person signs in again
    return send(res, 200, { ok: true });
  }

  if (m === 'GET' && p === '/users') {
    requireAdmin(req);
    return send(res, 200, license.listUsers());
  }
  if (m === 'POST' && p === '/users') {
    requireAdmin(req);
    const { username, password, role } = await readJson(req);
    try {
      license.addUser(username, password, role);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    store.logActivity(me.username, 'user-added', `Added ${role === 'admin' ? 'admin' : 'staff'} account "${String(username).trim()}"`);
    return send(res, 200, license.listUsers());
  }
  if ((match = p.match(/^\/users\/([^/]+)$/)) && m === 'DELETE') {
    requireAdmin(req);
    const target = decodeURIComponent(match[1]);
    try {
      license.removeUser(target);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    remember.clearForUser(target);
    store.logActivity(me.username, 'user-removed', `Removed account "${target}"`);
    return send(res, 200, license.listUsers());
  }
  if ((match = p.match(/^\/users\/([^/]+)\/password$/)) && m === 'POST') {
    requireAdmin(req);
    const target = decodeURIComponent(match[1]);
    const { newPassword } = await readJson(req);
    try {
      license.adminSetPassword(target, newPassword);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    remember.clearForUser(target);
    store.logActivity(me.username, 'user-password-reset', `Reset the password for "${target}"`);
    return send(res, 200, { ok: true });
  }

  if (m === 'GET' && p === '/activity') {
    requireAdmin(req);
    return send(res, 200, store.data.activity);
  }

  if (m === 'GET' && p === '/data') return send(res, 200, snapshot());
  if (m === 'GET' && p === '/images/status') return send(res, 200, images.status());

  if (m === 'GET' && p === '/license-backup') {
    let backup;
    try {
      backup = license.exportBackup();
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    return send(res, 200, JSON.stringify(backup), {
      'Content-Type': 'application/json',
      'Content-Disposition': 'attachment; filename="glass-finder-activation-backup.json"',
    });
  }

  if (m === 'GET' && p === '/settings') {
    const key = settings.serperApiKey;
    return send(res, 200, {
      serperApiKey: key ? `${'•'.repeat(Math.max(0, key.length - 4))}${key.slice(-4)}` : null,
      lowStockThreshold: settings.lowStockThreshold,
    });
  }
  if (m === 'PUT' && p === '/settings/low-stock-threshold') {
    try {
      settings.setLowStockThreshold((await readJson(req)).threshold);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    return send(res, 200, { lowStockThreshold: settings.lowStockThreshold });
  }
  if (m === 'POST' && p === '/search-hit') {
    store.logHit((await readJson(req)).modelId);
    return send(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/settings/serper-key') {
    const { key } = await readJson(req);
    const clean = String(key || '').trim();
    if (!clean) throw new HttpError(400, 'Paste an API key first.');
    try {
      await testSerperKey(clean);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    settings.setSerperApiKey(clean);
    return send(res, 200, { ok: true });
  }
  if (m === 'DELETE' && p === '/settings/serper-key') {
    settings.setSerperApiKey('');
    return send(res, 200, { ok: true });
  }

  if (m === 'POST' && p === '/add') {
    const { box, models } = await readJson(req);
    const n = Number(box);
    if (!Number.isInteger(n) || n < 1) throw new HttpError(400, 'Enter a box number (1 or higher)');
    if (!Array.isArray(models) || !models.length) throw new HttpError(400, 'No models to add');
    const r = store.addToBox(n, models);
    images.enqueue(r.created.map((c) => c.id));
    if (r.created.length || r.joined.length) {
      store.logActivity(me.username, 'models-added', `Added ${r.created.length + r.joined.length} model(s) to box ${n}`);
    }
    return send(res, 200, { created: r.created.length, joined: r.joined.length, already: r.already.length, ...snapshot() });
  }

  if (m === 'POST' && p === '/models/merge') {
    const { keepId, otherId } = await readJson(req);
    let r;
    try {
      r = store.mergeModels(keepId, otherId);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    if (r.absorbedImage) fs.rmSync(path.join(imagesDir, r.absorbedImage), { force: true });
    store.logActivity(me.username, 'models-merged', `Merged into ${r.keep.brand} ${r.keep.name}`);
    return send(res, 200, { keep: r.keep, ...snapshot() });
  }

  if ((match = p.match(/^\/models\/([\w-]+)$/))) {
    if (m === 'PATCH') {
      const before = needModel(match[1]);
      const label = `${before.brand} ${before.name}`.trim();
      const updated = store.update(match[1], await readJson(req));
      if (!updated) throw new HttpError(404, 'Model not found');
      store.logActivity(me.username, 'model-edited', `Edited ${label} → ${updated.brand} ${updated.name} (boxes: ${updated.boxes.join(', ')})`);
      return send(res, 200, updated);
    }
    if (m === 'DELETE') {
      const gone = store.remove(match[1]);
      if (!gone) throw new HttpError(404, 'Model not found');
      images.clear(gone);
      store.logActivity(me.username, 'model-deleted', `Deleted ${gone.brand} ${gone.name} (was in box ${gone.boxes.join(', ') || 'none'})`);
      return send(res, 200, { ok: true });
    }
  }

  if ((match = p.match(/^\/models\/([\w-]+)\/image(\/[a-z-]+)?$/))) {
    const model = needModel(match[1]);
    const sub = match[2];
    if (m === 'GET' && sub === '/candidates') return send(res, 200, await plain(() => images.candidates(model)));
    if (m === 'POST' && sub === '/url') {
      const { url: link } = await readJson(req);
      await plain(() => images.setFromUrl(model, String(link || '')));
      return send(res, 200, model);
    }
    if (m === 'POST' && sub === '/upload') {
      const body = await readBody(req);
      await plain(async () => images.setFromBuffer(model, body, (req.headers['content-type'] || '').split(';')[0]));
      return send(res, 200, model);
    }
    if (m === 'DELETE' && !sub) {
      images.clear(model);
      return send(res, 200, model);
    }
  }

  if (m === 'POST' && p === '/images/queue') {
    const { ids, missing, retryFailed, first } = await readJson(req);
    const list = missing ? store.data.models.map((x) => x.id) : Array.isArray(ids) ? ids : [];
    const queued = images.enqueue(list, { first: !!first, retryFailed: !!retryFailed, resume: !!missing });
    return send(res, 200, { queued, ...images.status() });
  }

  if (p === '/misses') {
    if (m === 'POST') {
      const { query } = await readJson(req);
      store.logMiss(query);
      return send(res, 200, { misses: missList() });
    }
    if (m === 'DELETE') {
      const key = url.searchParams.get('key');
      if (key) store.dismissMiss(key);
      else store.clearMisses();
      return send(res, 200, { misses: missList() });
    }
  }

  if ((match = p.match(/^\/boxes\/(\d+)\/sell$/)) && m === 'POST') {
    const n = Number(match[1]);
    let stock;
    try {
      stock = store.decrementStock(n);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    return send(res, 200, { stock, ...snapshot() });
  }

  if ((match = p.match(/^\/boxes\/(\d+)$/))) {
    const n = Number(match[1]);
    if (m === 'PUT') {
      const body = await readJson(req);
      if ('note' in body) store.setBoxNote(n, body.note);
      if ('stock' in body) store.setBoxStock(n, body.stock);
      return send(res, 200, { ok: true });
    }
    if (m === 'DELETE') {
      const { deleted, updated } = store.removeBox(n);
      for (const model of deleted) images.clear(model);
      store.logActivity(me.username, 'box-deleted', `Deleted box ${n} — ${deleted.length} model(s) removed, ${updated.length} kept in other boxes`);
      return send(res, 200, { deletedModels: deleted.length, updatedModels: updated.length, ...snapshot() });
    }
  }

  if (m === 'DELETE' && p === '/deleted-boxes') {
    requireAdmin(req);
    const n = store.clearDeletedBoxes();
    store.logActivity(me.username, 'deleted-boxes-cleared', `Cleared ${n} deleted-box record(s)`);
    return send(res, 200, snapshot());
  }
  if ((match = p.match(/^\/deleted-boxes\/(\d+)$/)) && m === 'DELETE') {
    const n = Number(match[1]);
    store.dismissDeletedBox(n);
    store.logActivity(me.username, 'deleted-box-forgotten', `Forgot the deleted record for box ${n}`);
    return send(res, 200, snapshot());
  }
  if ((match = p.match(/^\/deleted-boxes\/(\d+)\/restore$/)) && m === 'POST') {
    const n = Number(match[1]);
    let result;
    try {
      result = store.restoreBox(n);
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    images.enqueue(store.data.models.filter((mo) => mo.boxes.includes(n) && !mo.image).map((mo) => mo.id));
    store.logActivity(me.username, 'box-restored', `Restored box ${n} — ${result.recreated + result.rejoined + result.reattached} model(s) back in it`);
    return send(res, 200, { ...result, ...snapshot() });
  }

  if (m === 'GET' && p === '/export.csv') {
    return send(res, 200, csv(), {
      'Content-Type': 'text/csv; charset=utf-8',
      'Content-Disposition': 'attachment; filename="glass-boxes.csv"',
    });
  }
  if (m === 'GET' && p === '/backup.json') {
    return send(res, 200, JSON.stringify(store.data), {
      'Content-Type': 'application/json',
      'Content-Disposition': 'attachment; filename="glass-finder-backup.json"',
    });
  }
  if (m === 'POST' && p === '/restore') {
    try {
      store.replaceAll(await readJson(req, 64 * 1024 * 1024));
    } catch (e) {
      throw new HttpError(400, e.message);
    }
    return send(res, 200, snapshot());
  }
  throw new HttpError(404, 'Not found');
}

function serveFile(res, dir, rel, cache) {
  const file = path.join(dir, path.normalize(rel).replace(/^(\.\.[\\/])+/, ''));
  if (!file.startsWith(dir + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) return send(res, 404, 'Not found');
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Cache-Control': cache });
  fs.createReadStream(file).pipe(res);
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname.startsWith('/api/')) return await api(req, res, url);
    if (url.pathname.startsWith('/images/')) {
      if (!isLoggedIn(req)) return send(res, 401, 'Not logged in');
      return serveFile(res, imagesDir, decodeURIComponent(url.pathname.slice(8)), 'public, max-age=604800, immutable');
    }
    return serveFile(res, publicDir, url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname.slice(1)), 'no-cache');
  } catch (e) {
    if (!(e instanceof HttpError)) console.error(e);
    if (!res.headersSent) send(res, e.status || 500, { error: e.status ? e.message : `Something went wrong: ${e.message}` });
  }
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') console.log(`Glass Finder is already running on port ${PORT}. Open http://localhost:${PORT}`);
  else console.error(e);
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  console.log(`Glass Finder is running.\n  On this PC:  http://localhost:${PORT}`);
  if (HOST !== '127.0.0.1') {
    for (const list of Object.values(os.networkInterfaces())) {
      for (const i of list) if (i.family === 'IPv4' && !i.internal) console.log(`  On your phone (same Wi-Fi):  http://${i.address}:${PORT}`);
    }
  }
  console.log('\nKeep this window open while you use the app. Close it to stop.');
});
