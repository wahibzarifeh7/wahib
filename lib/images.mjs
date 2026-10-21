// Finds a phone photo on the web and saves it under data/images.
//
// Two sources, tried in order:
//  1. Serper (https://serper.dev) - a paid Google Images API, used when the shop has set an API
//     key in Photo settings. Reliable and fast; the free allowance (2,500 searches, no card) easily
//     covers a one-time backfill of the whole catalogue plus ongoing new models.
//  2. DuckDuckGo's unofficial image search - free, keyless, but occasionally rate-limits this PC's
//     address, so it's paced slowly to avoid making that worse. Used whenever no key is set, and as
//     a fallback if Serper's call fails for some reason.
import fs from 'node:fs';
import path from 'node:path';
import dns from 'node:dns/promises';
import net from 'node:net';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
const MAX_BYTES = 6 * 1024 * 1024;
const EXT = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif', 'image/avif': 'avif' };

export function searchQuery(m) {
  const brand = m.brand === 'Poco' || m.brand === 'Redmi' ? `Xiaomi ${m.brand}` : m.brand;
  return `${brand ? brand + ' ' : ''}${m.name} ${m.brand ? 'phone' : 'smartphone'}`.replace(/\s+/g, ' ');
}

const squash = (s) => String(s).toLowerCase().replace(/[^a-z0-9+]/g, '');

function score(c, m) {
  const t = squash(c.title || '');
  let s = 0;
  if (t.includes(squash(m.name))) s += 4;
  if (m.brand && t.includes(m.brand.toLowerCase())) s += 2;
  if (c.height >= c.width * 1.25) s += 2;
  else if (c.width > c.height * 1.3) s -= 1;
  if (/case|cover|tempered|protector|charger|battery|wallpaper|logo/i.test(c.title || '')) s -= 4;
  return s;
}

const BRAND_WORDS = { Poco: ['poco'], Redmi: ['redmi', 'xiaomi'], Xiaomi: ['xiaomi', 'mi'] };

/** True when the photo's title names both the brand and the model, so it is safe to attach without asking. */
function isConfident(c, m) {
  const title = squash(c.title || '');
  const name = squash(m.name);
  if (!title.includes(name)) return false;
  if (!m.brand) return name.length >= 4;
  return (BRAND_WORDS[m.brand] || [m.brand.toLowerCase()]).some((w) => title.includes(w));
}

function rank(list, m, limit) {
  return list
    .map((c) => ({ ...c, score: score(c, m), confident: isConfident(c, m) }))
    .sort((a, b) => b.score - a.score)
    .slice(0, limit);
}

/** Ask Serper (google.serper.dev/images) for photos of `q`. Throws a message worth showing the user. */
async function searchSerper(q, apiKey, limit) {
  const res = await fetch('https://google.serper.dev/images', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-KEY': apiKey },
    body: JSON.stringify({ q, num: Math.min(limit, 20) }),
    signal: AbortSignal.timeout(15000),
  });
  if (res.status === 401 || res.status === 403) throw new Error('Serper says this API key is not valid.');
  if (res.status === 429) throw new Error('Serper says too many requests right now - try again shortly.');
  if (!res.ok) throw new Error(`Serper returned an error (${res.status}).`);
  const json = await res.json();
  return (json.images || []).map((r) => ({
    thumb: r.thumbnailUrl || r.imageUrl,
    full: r.imageUrl,
    title: r.title || '',
    width: r.thumbnailWidth || r.imageWidth || 0,
    height: r.thumbnailHeight || r.imageHeight || 0,
  }));
}

/** Quick, cheap call used when the shop first saves a key, so Photo settings can say right away if it works. */
export async function testSerperKey(apiKey) {
  await searchSerper('test photo', apiKey, 1);
}

async function searchDuckDuckGo(q) {
  const page = await fetch(`https://duckduckgo.com/?q=${encodeURIComponent(q)}&iax=images&ia=images`, {
    headers: { 'User-Agent': UA },
    signal: AbortSignal.timeout(15000),
  });
  const vqd = (await page.text()).match(/vqd=["']?([\d-]+)/)?.[1];
  if (!vqd) throw new Error(`DuckDuckGo did not answer (${page.status})`);
  const res = await fetch(`https://duckduckgo.com/i.js?l=us-en&o=json&q=${encodeURIComponent(q)}&vqd=${vqd}&f=,,,,,&p=1`, {
    headers: { 'User-Agent': UA, Referer: 'https://duckduckgo.com/' },
    signal: AbortSignal.timeout(15000),
  });
  if (!res.ok) throw new Error(`DuckDuckGo returned ${res.status}`);
  const json = await res.json();
  return (json.results || [])
    .filter((r) => r.thumbnail)
    .map((r) => ({ thumb: r.thumbnail, full: r.image, title: r.title, width: r.width, height: r.height }));
}

async function assertPublicHost(urlStr) {
  const u = new URL(urlStr);
  if (!/^https?:$/.test(u.protocol)) throw new Error('Only http(s) image links are allowed');
  const host = u.hostname.replace(/^\[|\]$/g, '');
  const addrs = net.isIP(host) ? [{ address: host }] : await dns.lookup(host, { all: true });
  for (const { address } of addrs) {
    if (/^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|0\.|::1$|fc|fd|fe80)/i.test(address)) {
      throw new Error('That address points at a private network');
    }
  }
}

/** Download an image URL into memory (size- and type-checked). */
export async function download(urlStr) {
  await assertPublicHost(urlStr);
  const res = await fetch(urlStr, { headers: { 'User-Agent': UA }, redirect: 'follow', signal: AbortSignal.timeout(15000) });
  if (!res.ok) throw new Error(`Download failed (${res.status})`);
  const type = (res.headers.get('content-type') || '').split(';')[0].trim();
  if (!EXT[type]) throw new Error(`Not an image (${type || 'unknown type'})`);
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length > MAX_BYTES) throw new Error('Image is larger than 6 MB');
  return { buf, ext: EXT[type] };
}

export function createImageService(store, imagesDir, settings) {
  fs.mkdirSync(imagesDir, { recursive: true });

  // After a "blocked" answer, leave DuckDuckGo alone for a while instead of digging the hole deeper.
  // Serper is a paid API meant for exactly this, so it never gets this treatment.
  let ddgBlockedUntil = 0;
  let lastSerperError = '';

  /** Search the web for photos of a phone. Returns candidates, best first, plus which source answered. */
  async function searchCandidates(m, limit = 12) {
    const key = settings?.serperApiKey;
    if (key) {
      try {
        return { source: 'serper', list: rank(await searchSerper(searchQuery(m), key, limit), m, limit) };
      } catch (e) {
        // Fall through to DuckDuckGo so one bad key doesn't stop photos altogether; status() still
        // reports the Serper error so Photo settings can warn that the key isn't working.
        lastSerperError = e.message;
      }
    }
    if (Date.now() < ddgBlockedUntil) {
      const mins = Math.ceil((ddgBlockedUntil - Date.now()) / 60000);
      throw new Error(`The free photo search is limiting requests. Try again in about ${mins} min, or add a Serper API key in Photo settings.`);
    }
    try {
      return { source: 'duckduckgo', list: rank(await searchDuckDuckGo(searchQuery(m)), m, limit) };
    } catch (e) {
      if (/40[39]|429/.test(e.message)) ddgBlockedUntil = Date.now() + 10 * 60000;
      throw e;
    }
  }

  function saveFile(model, buf, ext) {
    const old = model.image && path.join(imagesDir, path.basename(model.image));
    const name = `${model.id}-${Date.now().toString(36)}.${ext}`;
    fs.writeFileSync(path.join(imagesDir, name), buf);
    if (old && fs.existsSync(old)) fs.unlinkSync(old);
    model.image = name;
    store.save();
  }

  const queue = [];
  let running = false;
  let pausedUntil = 0;
  let failStreak = 0;
  const stats = { done: 0, failed: 0, lastError: '' };
  const delay = (ms) => new Promise((r) => setTimeout(r, ms));

  async function fetchOne(m) {
    const { source, list } = await searchCandidates(m, 8);
    for (const c of list.filter((x) => x.confident).slice(0, 3)) {
      try {
        const { buf, ext } = await download(c.thumb);
        saveFile(m, buf, ext);
        m.imgState = 'auto';
        return { ok: true, source };
      } catch {
        // try the next candidate
      }
    }
    m.imgState = 'failed';
    store.save();
    return { ok: false, source };
  }

  async function run() {
    if (running) return;
    running = true;
    while (queue.length) {
      if (Date.now() < pausedUntil) {
        await delay(2000);
        continue;
      }
      const id = queue.shift();
      const m = store.byId(id);
      if (!m || m.image) continue;
      let source = settings?.serperApiKey ? 'serper' : 'duckduckgo';
      try {
        const r = await fetchOne(m);
        source = r.source;
        if (r.ok) stats.done++;
        else stats.failed++;
        failStreak = 0;
        stats.lastError = '';
      } catch (e) {
        stats.lastError = e.message;
        failStreak++;
        queue.unshift(id);
        // back off hard: 1, 2, 4, 8 ... minutes, at most 30
        pausedUntil = Date.now() + Math.min(30, 2 ** (failStreak - 1)) * 60000;
      }
      // Serper is a real rate-limited API, safe to move quickly; DuckDuckGo's scraping needs care.
      await delay(source === 'serper' ? 400 : 3500);
    }
    running = false;
  }

  return {
    /** Queue models that have no image. `first` puts them at the front (what the user is looking at). */
    enqueue(ids, { first = false, retryFailed = false, resume = false } = {}) {
      if (resume) {
        pausedUntil = 0;
        failStreak = 0;
        stats.lastError = '';
      }
      const fresh = ids.filter((id) => {
        const m = store.byId(id);
        return m && !m.image && (retryFailed || m.imgState !== 'failed') && !queue.includes(id);
      });
      if (first) queue.unshift(...fresh);
      else queue.push(...fresh);
      run();
      return fresh.length;
    },
    status: () => ({
      pending: queue.length,
      paused: Date.now() < pausedUntil,
      resumeInSeconds: Math.max(0, Math.ceil((pausedUntil - Date.now()) / 1000)),
      usingSerper: !!settings?.serperApiKey,
      serperError: settings?.serperApiKey ? lastSerperError : '',
      ...stats,
    }),
    candidates: async (m) => (await searchCandidates(m)).list,
    setFromUrl: async (m, url) => {
      const { buf, ext } = await download(url);
      saveFile(m, buf, ext);
      m.imgState = 'manual';
      store.save();
    },
    setFromBuffer: (m, buf, mime) => {
      const ext = EXT[mime];
      if (!ext) throw new Error('Please choose a JPG, PNG, WebP or GIF image');
      if (buf.length > MAX_BYTES) throw new Error('Image is larger than 6 MB');
      saveFile(m, buf, ext);
      m.imgState = 'manual';
      store.save();
    },
    clear: (m) => {
      if (m.image) fs.rmSync(path.join(imagesDir, path.basename(m.image)), { force: true });
      m.image = null;
      m.imgState = 'none';
      store.save();
    },
  };
}
