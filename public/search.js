// Forgiving model search: ignores spaces/punctuation/case, understands brand shorthand
// ("rm note 10", "sam a52"), and falls back to close spellings ("hot 10 paly").
import { brandFromWord, BRAND_PARENT } from './parse.js';

export const squash = (s) => String(s ?? '').toLowerCase().replace(/[^a-z0-9+]/g, '');
const words = (s) => String(s ?? '').toLowerCase().replace(/[^a-z0-9+]+/g, ' ').trim().split(' ').filter(Boolean);

// Positions (in the squashed name) where a word ends: "A52 5G" -> {3, 5}.
function wordEnds(name) {
  const ends = new Set();
  let n = 0;
  for (const w of words(name)) ends.add((n += w.length));
  return ends;
}

export function buildIndex(models) {
  return models.map((m) => ({
    m,
    nameK: squash(m.name),
    brandK: squash(m.brand),
    fullK: squash(m.brand) + squash(m.name),
    nameW: ' ' + words(m.name).join(' '),
    bounds: wordEnds(m.name),
  }));
}

export function parseQuery(query) {
  const ws = words(query);
  const brands = [];
  const rest = [];
  for (const w of ws) {
    const b = ws.length > 1 ? brandFromWord(w) : null;
    if (b) brands.push(b);
    else rest.push(w);
  }
  // "xiaomi poco x3": the more specific brand wins
  const brand = brands.find((b) => brands.includes(BRAND_PARENT[b])) || brands[0] || null;
  return { brand, words: rest, qk: rest.join(''), q: rest.join(' ') };
}

// Edit distance (insert, delete, substitute, swap neighbours) that gives up past `max`.
function editDistance(a, b, max) {
  if (Math.abs(a.length - b.length) > max) return max + 1;
  let prev2 = null;
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    let rowMin = i;
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      let v = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
      if (prev2 && i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) v = Math.min(v, prev2[j - 2] + 1);
      cur[j] = v;
      if (v < rowMin) rowMin = v;
    }
    if (rowMin > max) return max + 1;
    prev2 = prev;
    prev = cur;
  }
  return prev[b.length];
}

function nameScore(it, P) {
  const { qk, q } = P;
  if (!qk) return 0;
  if (it.nameK === qk) return 100;
  if (it.nameK.startsWith(qk)) {
    // "A52" is a cleaner match for "A52 5G" than for "A52S 5G" or "A5 2020"
    return 80 - Math.min(20, it.nameK.length - qk.length) - (it.bounds.has(qk.length) ? 0 : 12);
  }
  if (it.nameW.includes(' ' + q)) return 62 - Math.min(15, Math.floor((it.nameK.length - qk.length) / 2));
  if (qk.length >= 2 && it.nameK.includes(qk)) return 45;
  if (P.words.length > 1 && P.words.every((w) => it.nameK.includes(w))) return 40;
  if (P.words.length === 1 && qk.length >= 3 && it.brandK.startsWith(qk)) return 50;
  return 0;
}

function brandAdjust(entryBrand, wanted) {
  if (!wanted) return 0;
  if (!entryBrand) return 0;
  if (entryBrand === wanted || BRAND_PARENT[entryBrand] === wanted) return 8;
  return -60;
}

const byBest = (a, b) =>
  b.score - a.score || a.it.nameK.length - b.it.nameK.length || a.it.m.name.localeCompare(b.it.m.name, undefined, { numeric: true });

/**
 * @returns {{ hits: {it, score}[], similar: {it, score}[], query: object }}
 * `hits` are real matches; `similar` are close spellings, only filled when there are few hits.
 */
export function search(index, text) {
  const P = parseQuery(text);
  if (!P.qk && !P.brand) return { hits: [], similar: [], query: P };

  const hits = [];
  for (const it of index) {
    let score = P.qk ? nameScore(it, P) : 50;
    if (score <= 0) continue;
    score += brandAdjust(it.m.brand, P.brand);
    if (score > 0) hits.push({ it, score });
  }
  hits.sort(byBest);

  let similar = [];
  const strong = hits.filter((h) => h.score >= 45).length;
  if (strong < 3 && P.qk.length >= 4 && !(hits[0] && hits[0].score >= 80)) {
    const max = P.qk.length <= 5 ? 1 : P.qk.length <= 9 ? 2 : 3;
    const seen = new Set(hits.map((h) => h.it));
    for (const it of index) {
      if (seen.has(it)) continue;
      const d = Math.min(
        editDistance(P.qk, it.nameK, max),
        editDistance(P.qk, it.nameK.slice(0, P.qk.length), max),
        editDistance(P.qk, it.fullK.slice(0, P.qk.length + 1), max),
        editDistance(P.qk, it.fullK, max),
      );
      if (d <= max) similar.push({ it, score: 30 - d * 6 + brandAdjust(it.m.brand, P.brand) });
    }
    similar = similar.filter((s) => s.score > 0).sort(byBest).slice(0, 12);
  }
  return { hits, similar, query: P };
}

/** Wrap the part of `name` that matches `qk` (compared without spaces/punctuation) in <mark>. */
export function highlight(name, qk, esc) {
  if (!qk) return esc(name);
  const map = [];
  let squashed = '';
  for (let i = 0; i < name.length; i++) {
    const c = name[i].toLowerCase();
    if (/[a-z0-9+]/.test(c)) {
      map.push(i);
      squashed += c;
    }
  }
  const at = squashed.indexOf(qk);
  if (at < 0) return esc(name);
  const from = map[at];
  const to = map[at + qk.length - 1] + 1;
  return esc(name.slice(0, from)) + '<mark>' + esc(name.slice(from, to)) + '</mark>' + esc(name.slice(to));
}
