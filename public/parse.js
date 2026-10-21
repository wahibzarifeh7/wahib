// Turns pasted text such as "SAM A05/A05S ; RM 13C/NOTE 50" into [{ brand, name }].
// Shared by the browser (live preview on the Add screen) and Node (Excel import).

const BRAND_ALIASES = {
  Samsung: ['SAM', 'SAMSUNG', 'SA', 'GALAXY'],
  Redmi: ['RM', 'REDMI'],
  Xiaomi: ['XM', 'XIAOMI'],
  Poco: ['POCO'],
  Oppo: ['OP', 'OPPO'],
  Vivo: ['VI', 'VO', 'VOO', 'VIVO'],
  Realme: ['REALME', 'REAL', 'R-ME', 'RME'],
  Infinix: ['INF', 'INFI', 'INFINIX'],
  Tecno: ['TEC', 'TECNO'],
  Itel: ['ITEL', 'IT'],
  Huawei: ['HW', 'HUAWEI'],
  Honor: ['HONOR'],
  Motorola: ['MT', 'MOTO', 'MOTOROLA'],
  OnePlus: ['ONEPLUS'],
  ZTE: ['ZTE'],
  Nokia: ['NOKIA'],
  Apple: ['APPLE', 'IPHONE'],
};

export const BRAND_NAMES = Object.keys(BRAND_ALIASES);

// Brands that are part of a bigger family, for searching ("xiaomi note 10" should find Redmi).
export const BRAND_PARENT = { Redmi: 'Xiaomi', Poco: 'Xiaomi' };

const ALIAS_TO_BRAND = new Map();
for (const [brand, list] of Object.entries(BRAND_ALIASES)) {
  for (const a of list) ALIAS_TO_BRAND.set(a, brand);
}
export function brandFromWord(word) {
  return ALIAS_TO_BRAND.get(String(word).toUpperCase()) || null;
}

// Aliases long enough to be safely glued to a model ("HONOR400SMART", "REALME5", "SAMA07").
const GLUE_ALIASES = [...ALIAS_TO_BRAND.keys()].filter((a) => a.length >= 3).sort((a, b) => b.length - a.length);

// Model families that only exist under some brands. First brand is the default; if the brand
// already in force is in the list it wins, otherwise the default replaces it.
const FAMILIES = [
  [/^(HOT|ZERO)\b/, ['Infinix']],
  [/^P SMART/, ['Huawei']],
  [/^SMART\s?\d/, ['Infinix', 'Huawei', 'Honor']],
  [/^(SPARK|CAMON|POVA|POP|PHANTOM|KC8)\b/, ['Tecno']],
  [/^NARZO/, ['Realme']],
  [/^IQOO/, ['Vivo']],
  [/^RENO/, ['Oppo']],
  [/^Y\d/, ['Vivo', 'Huawei', 'Honor']],
  [/^NOVA/, ['Huawei']],
  [/^EDGE\b/, ['Motorola']],
  [/^(NORD|ACE)\b/, ['OnePlus']],
  [/^NOTE\s?\d/, ['Infinix', 'Redmi', 'Samsung', 'Realme']],
];

const CONTINUATION = new Set([
  'NFC', 'INDIA', 'IND', 'CHINA', 'IDN', 'GLOBAL', 'SPORT', 'PRO', 'PLUS', 'LITE', 'MAX', 'ULTRA', 'PRIME',
  'DECEMBER', 'ACTIV', 'HD', 'PRO+', 'GT', 'TURBO', 'BLACK', 'CORE', 'POWER', 'PLAY', 'SE',
]);
const FAMILY_WORDS = new Set([
  'NOTE', 'HOT', 'SMART', 'SPARK', 'CAMON', 'POVA', 'POP', 'RENO', 'NARZO', 'NOVA', 'EDGE', 'ZERO', 'VISION',
]);
// "X9C SMART" has SMART as a suffix, so these only start a new model when they carry their number.
const SUFFIX_LIKE = new Set(['SMART']);

const isTerminal = (t) => /^([345]G|20\d\d)$/i.test(t);
const isModelish = (t) => /^[A-Z]{1,2}\d{1,3}[A-Z+]*$/i.test(t) && !/^[345]G$/i.test(t);

function startsNewModel(cur, tok) {
  const prev = cur[cur.length - 1];
  const up = tok.toUpperCase();
  if (isTerminal(prev) && !CONTINUATION.has(up) && !isTerminal(tok)) return true;
  if (cur.length === 1 && isModelish(prev) && isModelish(tok)) return true;
  if (isModelish(prev) && FAMILY_WORDS.has(up) && !SUFFIX_LIKE.has(up)) return true;
  const first = cur[0].toUpperCase();
  if (cur.length > 1 && up === first && /[A-Z]/.test(first)) return true;
  const prefix = first.match(/^([A-Z]{3,})\d/);
  if (prefix && up === prefix[1]) return true;
  const glued = up.match(/^([A-Z]{3,})\d/); // "SMART6", "POP7": a family word with its number attached
  if (glued && (cur.length >= 2 || (prefix && prefix[1] === glued[1]))) return true;
  if (/^\d{1,2}$/.test(first) && /^\d{1,2}$/.test(tok) && cur.length > 1 && !/^\d/.test(prev)) return true;
  return false;
}

function splitModels(tokens) {
  const out = [];
  let cur = [];
  for (const tok of tokens) {
    if (cur.length && startsNewModel(cur, tok)) {
      out.push(cur);
      cur = [];
    }
    cur.push(tok);
  }
  if (cur.length) out.push(cur);
  return out;
}

const PRETTY_FAMILY = /\b(HOT|SMART|NOTE|SPARK|CAMON|POVA|POP|NARZO|RENO|EDGE|NEO|NORD|CE|ACE|NOVA|GO|TURBO|GT|ZERO|VISION|BLADE)(\d)/g;
const PRETTY_SUFFIX = /(\d)(PRO|PLUS|LITE|NEO|PRIME|NFC|HD|MAX|PLAY|AIR|VIP|TURBO|PREMIER|GO|CORE|SE|POWER|SMART|ULTRA|FE)\b/g;

export function prettyName(name) {
  let s = name.toUpperCase().replace(/\s+/g, ' ').replace(/^[\s.,;:&\-]+|[\s.,;:&\-]+$/g, '');
  s = s.replace(PRETTY_FAMILY, '$1 $2').replace(PRETTY_SUFFIX, '$1 $2');
  return s.replace(/\s+/g, ' ').trim();
}

export function keyOf(brand, name) {
  return `${(brand || '').toLowerCase()}|${name.toLowerCase().replace(/[^a-z0-9+]/g, '')}`;
}

function familyBrand(name, ctx) {
  for (const [re, allowed] of FAMILIES) {
    if (re.test(name)) return ctx && allowed.includes(ctx) ? ctx : allowed[0];
  }
  return ctx || '';
}

// Splits on / ; , and newlines, but not inside parentheses: "A13(4G/5G)" stays together.
function splitSegments(text) {
  let depth = 0;
  let cur = '';
  const segs = [];
  for (const ch of text) {
    if (ch === '(') depth++;
    if (ch === ')') depth = Math.max(0, depth - 1);
    if (depth === 0 && /[\/;,\n\r]/.test(ch)) {
      segs.push(cur);
      cur = '';
    } else cur += ch;
  }
  segs.push(cur);
  return segs;
}

// "A13(4G/5G)" -> ["A13 4G", "A13 5G"];  "Y78(CHINA)" -> ["Y78 CHINA"]
function expandParens(seg) {
  const m = seg.match(/^(.*?)\(([^)]*)\)\s*(.*)$/s);
  if (!m) return [seg];
  const [, base, inner, rest] = m;
  const parts = inner.split(/[\/,]/).map((p) => p.trim()).filter(Boolean);
  if (parts.length && parts.every((p) => /^[345]G$/i.test(p))) {
    return [...parts.map((p) => `${base.trim()} ${p}`), ...(rest ? expandParens(rest) : [])];
  }
  return expandParens(`${base} ${parts.join(' ')} ${rest}`.trim());
}

function tokenBrand(token) {
  const up = token.toUpperCase();
  const direct = ALIAS_TO_BRAND.get(up);
  if (direct) return { brand: direct, rest: '' };
  if (up.startsWith('1+')) return { brand: 'OnePlus', rest: token.slice(2) };
  if (/^SAM(?=A\d)/.test(up)) return { brand: 'Samsung', rest: token.slice(3) };
  for (const alias of GLUE_ALIASES) {
    if (up.startsWith(alias) && /^\d/.test(up.slice(alias.length))) {
      return { brand: ALIAS_TO_BRAND.get(alias), rest: token.slice(alias.length) };
    }
  }
  return null;
}

/**
 * Parse free text into unique models.
 * @param {string} text
 * @param {{ defaultBrand?: string }} [opts] brand assumed until the text names another one
 * @returns {{ brand: string, name: string, key: string }[]}
 */
export function parseModels(text, opts = {}) {
  let ctx = opts.defaultBrand || '';
  const found = [];
  const seen = new Set();
  let prevSeg = null; // last emitted name in this run, for orphan "5G" segments
  let family = null; // { word, ctx } of the last "SPARK 8"-style model, so a bare "8C" becomes "SPARK 8C"

  const emit = (tokens) => {
    const raw = tokens.join(' ').trim();
    if (!raw) return;
    let name = prettyName(raw);
    if (!name || name === 'FOR' || name === 'G' || /^[^A-Z0-9]*$/.test(name)) return;
    if (/^\d/.test(name) && family && family.ctx === ctx) name = `${family.word} ${name}`;
    const brand = familyBrand(name, ctx);
    if (!ctx && brand) ctx = brand;
    const word = name.match(/^([A-Z]{3,}) ?\d/);
    family = word && FAMILY_WORDS.has(word[1]) ? { word: word[1], ctx } : /^\d/.test(name) ? family : null;
    const key = keyOf(brand, name);
    prevSeg = name;
    if (seen.has(key)) return;
    seen.add(key);
    found.push({ brand, name, key });
  };

  for (const segRaw of splitSegments(text)) {
    for (const seg of expandParens(segRaw)) {
      const cleaned = seg.replace(/^\s*(GB\d+\s+)?FOR\s*:?\s*/i, '').replace(/\bFOR\s*:\s*/gi, ' ').trim();
      if (!cleaned) continue;

      // orphan "5G" left over from "A72 4G/5G"
      if (/^[345]G$/i.test(cleaned) && prevSeg) {
        const base = prevSeg.replace(/\s*[345]G$/i, '');
        emit([base, cleaned.toUpperCase()]);
        continue;
      }

      let run = [];
      const flush = () => {
        for (const group of splitModels(run)) emit(group);
        run = [];
      };
      for (const token of cleaned.split(/\s+/)) {
        if (token === '&' || token === '-') continue;
        const b = tokenBrand(token);
        if (b) {
          flush();
          ctx = b.brand;
          if (b.rest) run.push(b.rest);
        } else run.push(token);
      }
      flush();
    }
  }
  return found;
}

/**
 * Give unbranded models the brand of an identical name that already exists under exactly one brand
 * ("A71" -> Samsung when only "Samsung A71" is known). Ambiguous names are left unbranded.
 * @param {{ brand: string, name: string }[]} models models to fix up (mutated and returned)
 * @param {Iterable<{ brand: string, name: string }>} known models that already have brands
 */
export function inferBrands(models, known) {
  const byName = new Map();
  for (const k of known) {
    if (!k.brand) continue;
    const nk = keyOf('', k.name);
    if (!byName.has(nk)) byName.set(nk, new Set());
    byName.get(nk).add(k.brand);
  }
  for (const m of models) {
    if (m.brand) continue;
    const brands = byName.get(keyOf('', m.name));
    if (brands && brands.size === 1) {
      m.brand = [...brands][0];
      m.key = keyOf(m.brand, m.name);
      m.inferred = true;
    }
  }
  return models;
}
