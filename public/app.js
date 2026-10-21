import { parseModels, inferBrands, keyOf, BRAND_NAMES } from './parse.js';
import { buildIndex, search, highlight, squash } from './search.js';
import { t, plural, getLang, setLang, applyDirection } from './i18n.js';
import { getTheme, applyTheme, cycleTheme } from './theme.js';

const $ = (sel, root = document) => root.querySelector(sel);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
// Model/brand names are Latin/numeric shop data, never translated (same reasoning as the search box,
// activation code and device-id already being hard dir="ltr"). Wrapped like this, the bidi algorithm
// can't reorder a trailing weak character - e.g. it would otherwise flip "X5+" to "+X5" - just because
// the name sits inside Arabic (RTL) text.
const ltr = (s) => `<span dir="ltr">${esc(s)}</span>`;
const naturalSort = (a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
const PAGE = 40;

const state = {
  models: [],
  boxes: {},
  index: [],
  byBox: new Map(),
  images: { pending: 0 },
  misses: [],
  deletedBoxes: [],
  deletedIndex: [],
  frequent: [],
  lowStockThreshold: 2,
  shown: PAGE,
};
const asked = new Set(); // models we already asked the server to fetch a photo for

const PHONE_SVG = '<svg viewBox="0 0 24 34" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="1.5" width="18" height="31" rx="4"/><circle cx="12" cy="28" r="1"/></svg>';

/* ---------------- helpers ---------------- */
function toast(msg, kind = '') {
  const el = document.createElement('div');
  el.className = `toast ${kind}`;
  el.textContent = msg;
  $('#toasts').append(el);
  setTimeout(() => el.remove(), kind === 'err' ? 6000 : 3800);
}

async function api(path, opts = {}) {
  const init = { ...opts };
  if (init.json !== undefined) {
    init.method ||= 'POST';
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(init.json);
    delete init.json;
  }
  let res;
  try {
    res = await fetch(`/api${path}`, init);
  } catch {
    throw new Error(t('err.cannotReachServer'));
  }
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    if ((res.status === 401 || res.status === 403) && path !== '/login') checkAuth();
    throw new Error(body.error || t('err.requestFailed', { status: res.status }));
  }
  return body;
}

const guard = (fn) => async (...args) => {
  try {
    return await fn(...args);
  } catch (e) {
    toast(e.message, 'err');
  }
};

function applySnapshot(snap) {
  state.models = snap.models;
  state.boxes = snap.boxes;
  state.images = snap.images;
  state.misses = snap.misses || [];
  state.deletedBoxes = snap.deletedBoxes || [];
  state.frequent = snap.frequent || [];
  if (Number.isInteger(snap.lowStockThreshold)) state.lowStockThreshold = snap.lowStockThreshold;
  renderMissBadge();
  renderDeletedBadge();
  renderDataQualityBadges();
  state.index = buildIndex(state.models);
  // One entry per model that used to live in a now-deleted box, so a search for it can say so.
  state.deletedIndex = buildIndex(state.deletedBoxes.flatMap((d) => d.deletedModels.map((m) => ({ ...m, box: d.box, deletedAt: d.deletedAt }))));
  state.byBox = new Map();
  for (const m of state.models) for (const b of m.boxes) {
    if (!state.byBox.has(b)) state.byBox.set(b, []);
    state.byBox.get(b).push(m);
  }
  for (const b of Object.keys(state.boxes)) if (!state.byBox.has(Number(b))) state.byBox.set(Number(b), []);
}
const boxNumbers = () => [...state.byBox.keys()].sort((a, b) => a - b);
const modelById = (id) => state.models.find((m) => m.id === id);
const models = (n) => plural('count.models', n);
const boxCount = (n) => (state.byBox.get(n) || []).length;
// The built-in shorthand list, plus any brand the shop has already typed in for a model (so a brand
// added once - e.g. a manufacturer with no built-in shorthand - is suggested everywhere from then on).
const allBrandNames = () => [...new Set([...BRAND_NAMES, ...state.models.map((m) => m.brand).filter(Boolean)])].sort((a, b) => a.localeCompare(b));

const lowStockBoxes = () =>
  boxNumbers()
    .filter((n) => state.boxes[n]?.stock != null && state.boxes[n].stock <= state.lowStockThreshold)
    .sort((a, b) => state.boxes[a].stock - state.boxes[b].stock || a - b);
const unknownBrandModels = () => state.models.filter((m) => !m.brand).sort((a, b) => naturalSort(a.name, b.name));

function renderDataQualityBadges() {
  const low = lowStockBoxes().length;
  $('#lowStockBadge').hidden = !low;
  $('#lowStockBadge').textContent = low;
  const unk = unknownBrandModels().length;
  $('#unknownBrandBadge').hidden = !unk;
  $('#unknownBrandBadge').textContent = unk;
}

const imgUrl = (m) => `/images/${encodeURIComponent(m.image)}`;
const thumbHtml = (m) =>
  m.image
    ? `<div class="thumb"><img src="${imgUrl(m)}" alt="" loading="lazy"></div>`
    : `<div class="thumb ${asked.has(m.id) && m.imgState !== 'failed' ? 'loading' : ''}">${PHONE_SVG}</div>`;

const BOX_WORD = { en: 'BOX', ar: 'صندوق' };
const tagHtml = (n, big = false, tag = 'button') =>
  `<${tag} class="tag ${big ? 'big' : ''}" ${tag === 'button' ? 'type="button"' : ''} data-box="${n}" aria-label="${esc(t('box.tagAria', { n }))}"><small>${BOX_WORD[getLang()]}</small><b>${n}</b></${tag}>`;

/* ---------------- views ---------------- */
const view = $('#view');
const input = $('#q');

function route() {
  const m = location.hash.match(/^#\/box\/(\d+)/);
  if (m) return { name: 'box', box: Number(m[1]) };
  if (location.hash === '#/all') return { name: 'all' };
  return { name: 'home' };
}

// "#12", "#12", "box 12", "box#12" (case-insensitive) or the Arabic "صندوق 12" jump straight to that
// box, so staff who already know the box number don't have to search a model name to get there.
function jumpBoxNumber(text) {
  const s = text.trim();
  const m = s.match(/^#\s*(\d+)$/) || s.match(/^box\s*#?\s*(\d+)$/i) || s.match(/^صندوق\s*(\d+)$/);
  return m ? Number(m[1]) : null;
}

function render() {
  const r = route();
  $('#clear').hidden = !input.value;
  if (r.name === 'box' || !input.value.trim()) watchMiss(null);
  if (r.name === 'home' && input.value.trim()) {
    const n = jumpBoxNumber(input.value);
    if (n != null) {
      input.value = '';
      $('#clear').hidden = true;
      location.hash = `#/box/${n}`;
      return;
    }
  }
  if (r.name === 'box') return renderBox(r.box);
  if (r.name === 'all') return renderAll();
  if (input.value.trim()) return renderResults();
  return renderShelf();
}

function renderShelf() {
  const boxes = boxNumbers();
  if (!boxes.length) {
    view.innerHTML = `<div class="empty"><h2>${t('shelf.emptyTitle')}</h2><p>${t('shelf.emptyBody')}</p><button class="btn primary" data-act="add">${t('shelf.addModels')}</button></div>`;
    return;
  }
  const total = state.models.length;
  const freq = state.frequent.filter((m) => modelById(m.id));
  view.innerHTML = `
    <p class="hint">${esc(t('search.hint', { boxes: plural('count.boxes', boxes.length), models: plural('count.models', total) }))}</p>
    ${freq.length ? `
      <div class="section-title" style="margin-top:0">${t('shelf.frequentTitle')}</div>
      <div class="chips frequent">${freq.map((m) => `
        <button type="button" class="chip freq" data-id="${m.id}" dir="ltr">${m.brand ? `<small>${esc(m.brand.toUpperCase())}</small> ` : ''}${esc(m.name)}</button>`).join('')}</div>` : ''}
    <div class="shelf">
      <a class="slot all-slot" href="#/all">
        <div class="all-icon">${PHONE_SVG}</div>
        <b class="all-label">${esc(t('shelf.allLabel'))}</b>
        <span class="count">${models(total)}</span>
      </a>
      ${boxes.map((n) => `
        <a class="slot" href="#/box/${n}">
          ${tagHtml(n, false, 'span')}
          <span class="count">${models(boxCount(n))}</span>
          ${state.boxes[n]?.stock != null ? `<span class="stock ${state.boxes[n].stock === 0 ? 'out' : ''}">${state.boxes[n].stock === 0 ? t('stock.out') : t('stock.in', { n: state.boxes[n].stock })}</span>` : ''}
          ${state.boxes[n]?.note ? `<span class="note">${esc(state.boxes[n].note)}</span>` : ''}
        </a>`).join('')}
    </div>`;
}

function sellRowHtml(m) {
  const trackable = m.boxes.filter((b) => state.boxes[b]?.stock != null);
  if (!trackable.length) return '';
  return `<div class="sellrow">${trackable
    .map((b) => `<button type="button" class="btn small sell" data-act="sell" data-box="${b}">${esc(t('sell.button', { n: b, stock: state.boxes[b].stock }))}</button>`)
    .join('')}</div>`;
}

function cardHtml(h, qk) {
  const m = h.it.m;
  return `
    <article class="card" tabindex="0" data-id="${m.id}">
      ${thumbHtml(m)}
      <div class="info">
        <div class="brand">${esc(m.brand || t('search.brandUnknown'))}</div>
        <h3 class="name" dir="ltr">${highlight(m.name, qk, esc)}</h3>
      </div>
      <div class="tags">${m.boxes.map((b) => tagHtml(b)).join('')}</div>
      ${sellRowHtml(m)}
    </article>`;
}

function renderResults() {
  const text = input.value;
  const { hits, similar, query } = search(state.index, text);
  const shown = hits.slice(0, state.shown);
  const parts = [];

  if (!hits.length) {
    const past = search(state.deletedIndex, text).hits[0];
    if (past && past.score >= 70) {
      const dm = past.it.m; // { brand, name, box, deletedAt }
      const modelName = ltr(`${dm.brand ? dm.brand + ' ' : ''}${dm.name}`);
      parts.push(`
        <div class="deleted-note">
          <span class="txt">${t('deletedNote.was', { model: `<b>${modelName}</b>`, box: dm.box, when: when(dm.deletedAt) })}</span>
          <button class="btn small primary" data-act="restore-box" data-box="${dm.box}">${esc(t('deletedNote.restoreBtn', { n: dm.box }))}</button>
        </div>`);
    }
  }
  if (hits.length) {
    parts.push(`<div class="section-title">${esc(plural('count.matches', hits.length))}${query.brand ? esc(t('search.brandSuffix', { brand: query.brand })) : ''}</div>`);
    parts.push(`<div class="list">${shown.map((h) => cardHtml(h, query.qk)).join('')}</div>`);
    if (hits.length > shown.length) parts.push(`<button class="btn more" data-act="more">${esc(t('search.showMore', { n: Math.min(PAGE, hits.length - shown.length), left: hits.length - shown.length }))}</button>`);
  }
  if (similar.length) {
    parts.push(`<div class="section-title">${hits.length ? t('search.closeSpellings') : t('search.didYouMean')}</div>`);
    parts.push(`<div class="list">${similar.map((h) => cardHtml(h, '')).join('')}</div>`);
  }
  if (!hits.length && !similar.length) {
    parts.push(`<div class="empty"><h2>${t('search.notInAnyBox')}</h2><p>${esc(t('search.noModelMatches', { q: text.trim() }))}</p><button class="btn primary" data-act="add-text">${esc(t('search.addToBox', { q: text.trim() }))}</button></div>`);
  }
  view.innerHTML = parts.join('');
  wantPhotos([...shown.slice(0, 8), ...similar.slice(0, 3)].map((h) => h.it.m));
  watchMiss(hits.length === 0 ? text : null);
}

let boxFilter = '';
const UNKNOWN_BRAND = '##unknown-brand##'; // internal-only grouping key; never shown, always translated at display time
const chipHtml = (m) => `<button type="button" class="chip photo" data-id="${m.id}">${thumbHtml(m)}<span class="chip-name" dir="ltr">${esc(m.name)}</span></button>`;

// Shared by the box page and the "all models" page: group by brand, show each as a photo+name tile
// (instead of plain text) so a missing photo is visible at a glance, and queue background fetches for it.
function modelGridHtml(list) {
  const groups = new Map();
  for (const m of list) {
    const k = m.brand || UNKNOWN_BRAND;
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(m);
  }
  const ordered = [...groups.entries()].sort((a, b) => (a[0] === UNKNOWN_BRAND) - (b[0] === UNKNOWN_BRAND) || a[0].localeCompare(b[0]));
  wantPhotos(list);
  return ordered.map(([brand, ms]) => `
    <section class="group">
      <h3>${esc(brand === UNKNOWN_BRAND ? t('search.brandUnknown') : brand)} <span>${ms.length}</span></h3>
      <div class="chips">${ms.sort((a, b) => naturalSort(a.name, b.name)).map(chipHtml).join('')}</div>
    </section>`).join('') || `<p class="help" style="margin-top:16px">${esc(t('box.noFilterMatch'))}</p>`;
}

function renderAll() {
  const f = squash(boxFilter);
  const shown = f ? state.models.filter((m) => squash(m.brand + m.name).includes(f)) : state.models;
  view.innerHTML = `
    <div class="crumb"><a class="btn small ghost" href="#/" style="text-decoration:none;display:inline-block">${t('nav.allBoxes')}</a></div>
    <div class="boxhead">
      <div class="all-icon big">${PHONE_SVG}</div>
      <div class="facts">
        <div class="count">${esc(t('all.subtitle', { models: models(state.models.length) }))}</div>
      </div>
    </div>
    <div class="filter"><input id="boxFilter" value="${esc(boxFilter)}" placeholder="${esc(t('all.filterPlaceholder'))}" aria-label="${esc(t('all.filterAria'))}" autocomplete="off"></div>
    ${modelGridHtml(shown)}`;
}

function renderBox(n) {
  const list = state.byBox.get(n);
  if (!list) {
    view.innerHTML = `<div class="empty"><h2>${esc(t('box.doesNotExist', { n }))}</h2><button class="btn primary" data-act="add-box" data-box="${n}">${esc(t('box.addModelsToBox', { n }))}</button></div>`;
    return;
  }
  const f = squash(boxFilter);
  const shown = f ? list.filter((m) => squash(m.brand + m.name).includes(f)) : list;
  view.innerHTML = `
    <div class="crumb"><a class="btn small ghost" href="#/" style="text-decoration:none;display:inline-block">${t('nav.allBoxes')}</a></div>
    <div class="boxhead">
      ${tagHtml(n, true, 'span')}
      <div class="facts">
        <div class="count">${esc(t('box.shareThisGlass', { models: models(list.length) }))}</div>
        <input id="boxNote" value="${esc(state.boxes[n]?.note || '')}" placeholder="${esc(t('box.notePlaceholder'))}" aria-label="${esc(t('box.noteAria', { n }))}" maxlength="80">
        <div style="display:flex;gap:8px;align-items:flex-end;flex-wrap:wrap">
          <label class="lab" style="margin:0"><span style="font-size:11px">${esc(t('box.inStockLabel'))}</span><input id="boxStock" type="number" min="0" step="1" inputmode="numeric" value="${state.boxes[n]?.stock ?? ''}" placeholder="${esc(t('box.stockPlaceholder'))}" aria-label="${esc(t('box.stockAria', { n }))}"></label>
          <button class="btn small" data-act="sell" data-box="${n}" ${state.boxes[n]?.stock == null ? 'disabled' : ''}>${esc(t('sell.short'))}</button>
        </div>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn small" data-act="add-box" data-box="${n}">${esc(t('box.addModelsShort', { n }))}</button>
          <button class="btn small danger" data-act="delete-box" data-box="${n}">${esc(t('box.deleteBox', { n }))}</button>
        </div>
      </div>
    </div>
    <div class="filter"><input id="boxFilter" value="${esc(boxFilter)}" placeholder="${esc(t('box.filterPlaceholder'))}" aria-label="${esc(t('box.filterAria'))}" autocomplete="off"></div>
    ${modelGridHtml(shown)}`;
}

/* ---------------- searches that found nothing ---------------- */
// A search is logged only after the user stops on it, so half-typed words never count.
let missTimer = null;
let loggedMiss = null; // { key, text } of the search logged for what is on screen now
let forgivenKey = null; // a search the user resolved by opening a suggestion; do not log it again
function watchMiss(text) {
  clearTimeout(missTimer);
  // moving on to another search (or clearing the box) closes the last one, so searching it again counts again
  if (loggedMiss && (!text || squash(text) !== loggedMiss.key)) loggedMiss = null;
  if (forgivenKey && (!text || squash(text) !== forgivenKey)) forgivenKey = null;
  if (!text || squash(text).length < 2) return;
  const key = squash(text);
  if (loggedMiss?.key === key || forgivenKey === key) return;
  missTimer = setTimeout(async () => {
    if (input.value.trim() === '' || squash(input.value) !== key) return;
    try {
      const r = await api('/misses', { json: { query: text.trim() } });
      state.misses = r.misses;
      loggedMiss = { key, text };
      renderMissBadge();
    } catch {
      /* logging is a bonus; never bother the user with it */
    }
  }, 1500);
}

// If the user opens a "Did you mean" suggestion, the search was only a typo. Take it back out of the log.
async function forgiveMiss() {
  if (!loggedMiss || squash(input.value) !== loggedMiss.key) return;
  const { key } = loggedMiss;
  loggedMiss = null;
  forgivenKey = key;
  const r = await api(`/misses?key=${encodeURIComponent(key)}`, { method: 'DELETE' });
  state.misses = r.misses;
  renderMissBadge();
}

function updateMenuDot() {
  $('#menuBtn').classList.toggle('has-dot', state.misses.length > 0 || state.deletedBoxes.length > 0);
}
function renderMissBadge() {
  const n = state.misses.length;
  const badge = $('#missBadge');
  badge.hidden = !n;
  badge.textContent = n;
  updateMenuDot();
}

const missDlg = $('#missDlg');
const TODAY_WORD = { en: 'today', ar: 'اليوم' };
const when = (iso) => {
  const d = new Date(iso);
  const lang = getLang();
  // numberingSystem: latn keeps plain 0-9 digits even in Arabic, matching every other number in the app
  return d.toDateString() === new Date().toDateString() ? TODAY_WORD[lang] : d.toLocaleDateString(lang === 'ar' ? 'ar-EG' : undefined, { day: 'numeric', month: 'short', numberingSystem: 'latn' });
};

function openMisses() {
  const rows = state.misses;
  missDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head"><h2>${t('misses.title')}</h2><button class="x" data-close aria-label="${esc(t('misses.close'))}">&times;</button></div>
      <p class="help">${rows.length ? t('misses.bodyNonEmpty') : t('misses.bodyEmpty')}</p>
      <div class="misslist">
        ${rows.map((m) => `
          <div class="miss">
            <b dir="ltr">${esc(m.q)}</b>
            <span class="when">${esc(plural('count.searched', m.count))}${esc(t('misses.lastWhen', { when: when(m.last) }))}</span>
            <span class="acts">
              <button class="btn small primary" data-miss-add="${esc(m.key)}">${esc(t('misses.addToBox'))}</button>
              <button class="btn small" data-miss-drop="${esc(m.key)}" aria-label="${esc(t('misses.dismissAria', { q: m.q }))}">${esc(t('misses.dismiss'))}</button>
            </span>
          </div>`).join('')}
      </div>
      <div class="actions">
        ${rows.length ? `<button class="btn danger spread" data-miss-clear>${esc(t('misses.clearList'))}</button>` : ''}
        <button class="btn" data-close>${esc(t('misses.close'))}</button>
      </div>
    </div>`;
  if (!missDlg.open) missDlg.showModal();
}

missDlg.addEventListener('click', guard(async (e) => {
  const add = e.target.closest('[data-miss-add]');
  const drop = e.target.closest('[data-miss-drop]');
  if (add) {
    const m = state.misses.find((x) => x.key === add.dataset.missAdd);
    missDlg.close();
    return openAdd({ text: m ? m.q : '' });
  }
  if (drop || e.target.closest('[data-miss-clear]')) {
    if (!drop && !confirm(t('confirm.clearMisses'))) return;
    const r = await api(drop ? `/misses?key=${encodeURIComponent(drop.dataset.missDrop)}` : '/misses', { method: 'DELETE' });
    state.misses = r.misses;
    loggedMiss = null;
    renderMissBadge();
    openMisses();
  }
}));

/* ---------------- deleted boxes ---------------- */
function renderDeletedBadge() {
  const n = state.deletedBoxes.length;
  const badge = $('#deletedBadge');
  badge.hidden = !n;
  badge.textContent = n;
  updateMenuDot();
}

const deletedDlg = $('#deletedDlg');
let expandedDeletedBoxes = new Set();

const ghostChipHtml = (name) => `<div class="chip photo ghost" title="${esc(t('deletedBoxes.goneForever'))}">${thumbHtml({})}<span class="chip-name" dir="ltr">${esc(name)}</span></div>`;

function deletedBoxModelsHtml(d) {
  const still = d.updatedModels.map((u) => modelById(u.id)).filter(Boolean);
  if (!still.length && !d.deletedModels.length) return `<p class="help" style="margin:8px 0 0">${esc(t('deletedBoxes.noModels'))}</p>`;
  return `
    <p class="help" style="margin:8px 0">${esc(t('deletedBoxes.modelsCaption'))}</p>
    <div class="chips">${still.map(chipHtml).join('')}${d.deletedModels.map((m) => ghostChipHtml(m.name)).join('')}</div>`;
}

function openDeletedBoxes() {
  const rows = state.deletedBoxes;
  deletedDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head"><h2>${t('deletedBoxes.title')}</h2><button class="x" data-close aria-label="${esc(t('misses.close'))}">&times;</button></div>
      <p class="help">${rows.length ? t('deletedBoxes.bodyNonEmpty') : t('deletedBoxes.bodyEmpty')}</p>
      <div class="misslist">
        ${rows.map((d) => `
          <div class="miss deleted-row" data-toggle-box="${d.box}" style="cursor:pointer">
            <b>${esc(t('box.tagAria', { n: d.box }))} <span class="expand-arrow">${expandedDeletedBoxes.has(d.box) ? '▾' : '▸'}</span></b>
            <span class="when">${models(d.deletedModels.length + d.updatedModels.length)}${esc(t('deletedBoxes.deletedWhen', { when: when(d.deletedAt) }))}${d.note ? ` · ${esc(d.note)}` : ''}</span>
            <span class="acts">
              <button class="btn small primary" data-restore-box="${d.box}">${esc(t('deletedBoxes.restore'))}</button>
              <button class="btn small" data-dismiss-box="${d.box}" aria-label="${esc(t('deletedBoxes.forgetAria', { n: d.box }))}">${esc(t('deletedBoxes.forget'))}</button>
            </span>
            ${expandedDeletedBoxes.has(d.box) ? `<div class="deleted-models" style="grid-column:1/-1">${deletedBoxModelsHtml(d)}</div>` : ''}
          </div>`).join('')}
      </div>
      <div class="actions">
        ${rows.length && me?.role === 'admin' ? `<button class="btn danger spread" id="clearDeletedBoxes">${esc(t('deletedBoxes.clearList'))}</button>` : ''}
        <button class="btn" data-close>${esc(t('misses.close'))}</button>
      </div>
    </div>`;
  if (!deletedDlg.open) deletedDlg.showModal();
}

deletedDlg.addEventListener('click', guard(async (e) => {
  const restore = e.target.closest('[data-restore-box]');
  const dismiss = e.target.closest('[data-dismiss-box]');
  if (restore) {
    const n = Number(restore.dataset.restoreBox);
    const r = await api(`/deleted-boxes/${n}/restore`, { method: 'POST' });
    applySnapshot(r);
    toast(t('toast.boxRestored', { n, models: models(r.recreated + r.rejoined + r.reattached) }));
    deletedDlg.close();
    render();
    return;
  }
  if (dismiss) {
    const n = Number(dismiss.dataset.dismissBox);
    if (!confirm(t('confirm.forgetDeletedBox', { n }))) return;
    const r = await api(`/deleted-boxes/${n}`, { method: 'DELETE' });
    applySnapshot(r);
    expandedDeletedBoxes.delete(n);
    openDeletedBoxes();
    return;
  }
  const toggle = e.target.closest('[data-toggle-box]');
  if (toggle) {
    const n = Number(toggle.dataset.toggleBox);
    if (expandedDeletedBoxes.has(n)) expandedDeletedBoxes.delete(n);
    else expandedDeletedBoxes.add(n);
    openDeletedBoxes();
    return;
  }
  if (e.target.closest('#clearDeletedBoxes')) {
    if (!confirm(t('confirm.clearDeletedBoxes'))) return;
    const r = await api('/deleted-boxes', { method: 'DELETE' });
    applySnapshot(r);
    openDeletedBoxes();
  }
}));

/* ---------------- low stock / reorder / data quality / merge ---------------- */
const lowStockDlg = $('#lowStockDlg');
function openLowStock() {
  const rows = lowStockBoxes();
  lowStockDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head"><h2>${t('lowStock.title')}</h2><button class="x" data-close aria-label="${esc(t('misses.close'))}">&times;</button></div>
      <label class="lab">${t('lowStock.thresholdLabel')}<input id="lowStockThreshold" type="number" min="0" step="1" inputmode="numeric" value="${state.lowStockThreshold}"></label>
      <p class="help">${rows.length ? t('lowStock.bodyNonEmpty') : t('lowStock.bodyEmpty')}</p>
      <div class="misslist">
        ${rows.map((n) => `
          <div class="miss">
            <b>${esc(t('box.tagAria', { n }))}</b>
            <span class="when">${state.boxes[n].stock === 0 ? t('stock.out') : t('stock.in', { n: state.boxes[n].stock })}${state.boxes[n].note ? ` · ${esc(state.boxes[n].note)}` : ''}</span>
            <span class="acts">
              <button class="btn small" data-act="sell" data-box="${n}">${esc(t('sell.short'))}</button>
              <a class="btn small primary" href="#/box/${n}" data-close>${esc(t('lowStock.openBox'))}</a>
            </span>
          </div>`).join('')}
      </div>
      <div class="actions"><button class="btn" data-close>${esc(t('misses.close'))}</button></div>
    </div>`;
  if (!lowStockDlg.open) lowStockDlg.showModal();
}
lowStockDlg.addEventListener('change', guard(async (e) => {
  if (e.target.id === 'lowStockThreshold') {
    const r = await api('/settings/low-stock-threshold', { method: 'PUT', json: { threshold: e.target.value } });
    state.lowStockThreshold = r.lowStockThreshold;
    renderDataQualityBadges();
    openLowStock();
  }
}));
const reorderDlg = $('#reorderDlg');
function reorderText() {
  const lines = [t('reorder.copyHeaderLow')];
  const low = lowStockBoxes();
  if (low.length) low.forEach((n) => lines.push(`- ${t('box.tagAria', { n })}: ${state.boxes[n].stock === 0 ? t('stock.out') : t('stock.in', { n: state.boxes[n].stock })}${state.boxes[n].note ? ` (${state.boxes[n].note})` : ''}`));
  else lines.push(t('lowStock.bodyEmpty'));
  lines.push('', t('reorder.copyHeaderMissing'));
  if (state.misses.length) state.misses.forEach((m) => lines.push(`- ${m.q} (${plural('count.searched', m.count)})`));
  else lines.push(t('misses.bodyEmpty'));
  return lines.join('\n');
}
function openReorder() {
  const low = lowStockBoxes();
  const missed = state.misses;
  reorderDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head"><h2>${t('reorder.title')}</h2><button class="x" data-close aria-label="${esc(t('misses.close'))}">&times;</button></div>
      <p class="help">${t('reorder.explain')}</p>
      <div class="section-title" style="margin-top:0">${t('reorder.lowOrOut')}</div>
      ${low.length
        ? `<div class="misslist">${low.map((n) => `<div class="miss"><b>${esc(t('box.tagAria', { n }))}</b><span class="when">${state.boxes[n].stock === 0 ? t('stock.out') : t('stock.in', { n: state.boxes[n].stock })}${state.boxes[n].note ? ` · ${esc(state.boxes[n].note)}` : ''}</span></div>`).join('')}</div>`
        : `<p class="help">${t('lowStock.bodyEmpty')}</p>`}
      <div class="section-title">${t('reorder.requestedNotStocked')}</div>
      ${missed.length
        ? `<div class="misslist">${missed.map((m) => `<div class="miss"><b dir="ltr">${esc(m.q)}</b><span class="when">${esc(plural('count.searched', m.count))}</span></div>`).join('')}</div>`
        : `<p class="help">${t('misses.bodyEmpty')}</p>`}
      <div class="actions">
        <button type="button" class="btn spread" id="reorderCopy">${esc(t('reorder.copyBtn'))}</button>
        <button class="btn" data-close>${esc(t('misses.close'))}</button>
      </div>
    </div>`;
  if (!reorderDlg.open) reorderDlg.showModal();
  $('#reorderCopy').addEventListener('click', guard(async () => {
    const text = reorderText();
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // Clipboard permission can be blocked (e.g. no HTTPS/user-gesture context); fall back to the
      // classic select-and-copy trick so the report can still be copied by hand.
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.append(ta);
      ta.select();
      document.execCommand('copy');
      ta.remove();
    }
    toast(t('reorder.copied'));
  }));
}

const unknownBrandDlg = $('#unknownBrandDlg');
function openUnknownBrand() {
  const rows = unknownBrandModels();
  unknownBrandDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head"><h2>${t('unknownBrand.title')}</h2><button class="x" data-close aria-label="${esc(t('misses.close'))}">&times;</button></div>
      <p class="help">${rows.length ? t('unknownBrand.bodyNonEmpty') : t('unknownBrand.bodyEmpty')}</p>
      <div class="misslist">
        ${rows.map((m) => `
          <div class="miss">
            <b dir="ltr">${esc(m.name)}</b>
            <span class="when">${esc(t('unknownBrand.inBoxes', { boxes: m.boxes.join(', ') }))}</span>
            <span class="acts"><button class="btn small primary" data-fix="${m.id}">${esc(t('unknownBrand.fix'))}</button></span>
          </div>`).join('')}
      </div>
      <div class="actions"><button class="btn" data-close>${esc(t('misses.close'))}</button></div>
    </div>`;
  if (!unknownBrandDlg.open) unknownBrandDlg.showModal();
}
unknownBrandDlg.addEventListener('click', (e) => {
  const fix = e.target.closest('[data-fix]');
  if (fix) { unknownBrandDlg.close(); openDetail(fix.dataset.fix); }
});

const mergeDlg = $('#mergeDlg');
function openMergePicker(id) {
  const current = modelById(id);
  const label = (m) => `${m.brand ? m.brand + ' ' : ''}${m.name}`;
  mergeDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head"><h2>${t('merge.title', { name: ltr(label(current)) })}</h2><button class="x" data-close aria-label="${esc(t('misses.close'))}">&times;</button></div>
      <p class="help">${t('merge.explain')}</p>
      <label class="lab">${t('merge.searchLabel')}<input id="mergeSearch" autocomplete="off"></label>
      <div class="misslist" id="mergeResults"></div>
      <div class="actions"><button class="btn" data-close>${esc(t('misses.close'))}</button></div>
    </div>`;
  mergeDlg.showModal();
  const doSearch = () => {
    const q = $('#mergeSearch').value.trim();
    const results = q ? search(buildIndex(state.models.filter((m) => m.id !== id)), q).hits.slice(0, 8) : [];
    $('#mergeResults').innerHTML = results.length
      ? results.map((h) => `
          <div class="miss">
            <b dir="ltr">${esc(label(h.it.m))}</b>
            <span class="when">${esc(t('merge.inBoxes', { boxes: h.it.m.boxes.join(', ') }))}</span>
            <span class="acts"><button class="btn small primary" data-merge-into="${h.it.m.id}">${esc(t('merge.absorbBtn'))}</button></span>
          </div>`).join('')
      : q ? `<p class="help">${t('merge.noMatches')}</p>` : '';
  };
  $('#mergeSearch').addEventListener('input', doSearch);
  $('#mergeSearch').focus();
  $('#mergeResults').addEventListener('click', guard(async (e) => {
    const btn = e.target.closest('[data-merge-into]');
    if (!btn) return;
    const target = modelById(btn.dataset.mergeInto);
    if (!confirm(t('confirm.mergeModels', { from: label(current), into: label(target) }))) return;
    const r = await api('/models/merge', { json: { keepId: target.id, otherId: id } });
    applySnapshot(r);
    mergeDlg.close();
    detailDlg.close();
    toast(t('toast.merged', { name: label(r.keep) }));
    render();
  }));
}

/* ---------------- photos ---------------- */
// Only ask for photos once the user has paused on a set of results, and only for the first few.
let photoTimer = null;
function wantPhotos(models, { wait = 900, max = 8 } = {}) {
  clearTimeout(photoTimer);
  photoTimer = setTimeout(() => {
    const ids = models.filter((m) => !m.image && m.imgState !== 'failed' && !asked.has(m.id)).slice(0, max).map((m) => m.id);
    if (!ids.length) return;
    ids.forEach((id) => asked.add(id));
    api('/images/queue', { json: { ids, first: true } }).then(pollImages).catch(() => {});
  }, wait);
}

let lastDone = -1;
let polling = false;
async function pollImages() {
  if (polling) return;
  polling = true;
  try {
    for (;;) {
      const s = await api('/images/status');
      state.images = s;
      renderStatus();
      const progress = s.done + s.failed;
      if (lastDone !== -1 && progress !== lastDone) {
        const snap = await api('/data');
        applySnapshot(snap);
        if (!document.querySelector('dialog[open]') && document.activeElement !== $('#boxNote')) render();
      }
      lastDone = progress;
      if (!s.pending) break;
      await new Promise((r) => setTimeout(r, 2500));
    }
  } catch {
    /* server stopped; the next action will explain */
  } finally {
    polling = false;
  }
}

function renderStatus() {
  const el = $('#imgStatus');
  const s = state.images;
  if (s.pending > 0) {
    el.hidden = false;
    el.classList.toggle('warn', !!s.paused);
    const mins = Math.ceil(s.resumeInSeconds / 60);
    el.textContent = s.paused ? t('status.pausedFor', { mins, n: s.pending }) : t('status.gettingPhotos', { n: s.pending });
    el.title = s.paused ? t('status.pausedTitle', { err: s.lastError }) : '';
  } else if (s.lastError) {
    el.hidden = false;
    el.classList.add('warn');
    el.textContent = t('status.stopped');
    el.title = s.lastError;
  } else el.hidden = true;
}

/* ---------------- detail dialog ---------------- */
const detailDlg = $('#detailDlg');
let detailId = null;

function openDetail(id) {
  const m = modelById(id);
  if (!m) return;
  detailId = id;
  const brandOptions = allBrandNames().map((b) => `<option value="${b}"></option>`).join('');
  detailDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head">
        <h2 dir="ltr">${esc(m.brand ? m.brand + ' ' : '')}${esc(m.name)}</h2>
        <button class="x" data-close aria-label="${esc(t('detail.close'))}">&times;</button>
      </div>
      <div class="detail-top">
        <div>
          <div class="detail-img">${m.image ? `<img src="${imgUrl(m)}" alt="${esc(m.name)}">` : PHONE_SVG}</div>
          <div class="imgtools">
            <button class="btn small" data-act="img-web">${m.image ? esc(t('photo.changePhoto')) : esc(t('photo.findPhoto'))}</button>
            ${m.image ? `<button class="btn small danger" data-act="img-remove">${esc(t('photo.remove'))}</button>` : ''}
          </div>
        </div>
        <div style="display:grid;gap:12px;align-content:start">
          <div>
            <div class="section-title" style="margin-top:0">${t('detail.thisGlassIsIn')}</div>
            <div class="tags left">${m.boxes.map((b) => tagHtml(b)).join('')}</div>
          </div>
          <p class="help">${m.boxes.map((b) => esc(t('detail.boxHolds', { b, models: models(boxCount(b)) }))).join(' ')}${esc(t('detail.tapToSeeAll'))}</p>
          <button type="button" class="btn small" data-act="merge" style="justify-self:start">${esc(t('detail.mergeInto'))}</button>
        </div>
      </div>
      <form id="editForm" class="dlg" style="padding:0">
        <div class="row">
          <label class="lab">${t('detail.modelLabel')}<input name="name" value="${esc(m.name)}" dir="ltr" required></label>
          <label class="lab">${t('detail.brandLabel')}<input name="brand" value="${esc(m.brand)}" dir="ltr" list="brandList" placeholder="${esc(t('detail.brandUnknownPlaceholder'))}"><datalist id="brandList">${brandOptions}</datalist></label>
        </div>
        <label class="lab">${t('detail.boxesLabel')}<input name="boxes" value="${m.boxes.join(', ')}" inputmode="numeric" required></label>
        <div class="actions">
          <button type="button" class="btn danger spread" data-act="delete">${esc(t('detail.deleteModel'))}</button>
          <button type="button" class="btn" data-close>${esc(t('detail.close'))}</button>
          <button class="btn primary">${esc(t('detail.saveChanges'))}</button>
        </div>
      </form>
    </div>`;
  detailDlg.showModal();
  wantPhotos([m], { wait: 300 });
}

detailDlg.addEventListener('submit', guard(async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const boxes = String(f.get('boxes')).split(/[\s,;]+/).filter(Boolean).map(Number);
  if (!boxes.length || boxes.some((n) => !Number.isInteger(n) || n < 1)) throw new Error(t('err.boxesMustBeNumbers'));
  const key = keyOf(String(f.get('brand')).trim(), String(f.get('name')).trim());
  const clash = state.models.find((x) => x.id !== detailId && keyOf(x.brand, x.name) === key);
  if (clash) throw new Error(t('err.alreadyExists', { brand: clash.brand, name: clash.name, boxes: clash.boxes.join(', ') }));
  const updated = await api(`/models/${detailId}`, { method: 'PATCH', json: { name: f.get('name'), brand: f.get('brand'), boxes } });
  Object.assign(modelById(detailId), updated);
  await refresh();
  toast(t('toast.saved'));
  openDetail(detailId);
}));

/* ---------------- image chooser ---------------- */
const imgDlg = $('#imgDlg');

async function openImageChooser(id) {
  const m = modelById(id);
  imgDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head"><h2>${t('photo.for', { name: ltr(m.name) })}</h2><button class="x" data-close aria-label="${esc(t('photo.cancel'))}">&times;</button></div>
      <p class="help" id="candHelp">${t('photo.searchingWeb')}</p>
      <div class="cands" id="cands"></div>
      <div class="row">
        <label class="lab">${t('photo.pasteLink')}<input id="imgUrl" placeholder="https://…" inputmode="url"></label>
        <label class="lab">${t('photo.uploadPhoto')}<input id="imgFile" type="file" accept="image/*"></label>
      </div>
      <div class="actions"><button class="btn" data-close>${esc(t('photo.cancel'))}</button><button class="btn primary" data-act="img-url">${esc(t('photo.useLink'))}</button></div>
    </div>`;
  imgDlg.showModal();
  try {
    const cands = await api(`/models/${id}/image/candidates`);
    $('#candHelp').textContent = cands.length ? t('photo.tapMatch') : t('photo.noneFound');
    $('#cands').innerHTML = cands.map((c, i) => `<button class="cand" data-i="${i}" title="${esc(c.title)}"><img src="${esc(c.thumb)}" alt="${esc(c.title)}" loading="lazy" referrerpolicy="no-referrer"></button>`).join('');
    $('#cands').onclick = guard(async (e) => {
      const b = e.target.closest('.cand');
      if (!b) return;
      await setImage(id, { url: cands[Number(b.dataset.i)].thumb });
    });
  } catch (e) {
    $('#candHelp').textContent = t('photo.errorSuffix', { err: e.message });
    $('#candHelp').classList.add('err');
  }
  $('#imgFile').onchange = guard(async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const updated = await api(`/models/${id}/image/upload`, { method: 'POST', headers: { 'Content-Type': file.type }, body: file });
    Object.assign(modelById(id), updated);
    imgDlg.close();
    await refresh();
    openDetail(id);
  });
}

async function setImage(id, { url }) {
  $('#candHelp').textContent = t('photo.savingPhoto');
  const updated = await api(`/models/${id}/image/url`, { json: { url } });
  Object.assign(modelById(id), updated);
  imgDlg.close();
  await refresh();
  openDetail(id);
}

/* ---------------- add dialog ---------------- */
const addDlg = $('#addDlg');
let removed = new Set();

function openAdd({ box = '', text = '' } = {}) {
  removed = new Set();
  const brandOpts = allBrandNames().map((b) => `<option value="${b}">`).join('');
  addDlg.innerHTML = `
    <form class="dlg" id="addForm">
      <div class="dlg-head"><h2>${t('add.title')}</h2><button type="button" class="x" data-close aria-label="${esc(t('detail.close'))}">&times;</button></div>
      <div class="row">
        <label class="lab">${t('add.boxNumberLabel')}<input name="box" type="number" min="1" step="1" inputmode="numeric" value="${esc(box)}" list="boxList" required><datalist id="boxList">${boxNumbers().map((n) => `<option value="${n}">`).join('')}</datalist></label>
        <label class="lab">${t('add.brandForNamesLabel')}<input name="brand" dir="ltr" list="addBrandList" placeholder="${esc(t('add.workItOut'))}"><datalist id="addBrandList">${brandOpts}</datalist></label>
      </div>
      <p class="help" id="boxHelp"></p>
      <label class="lab">${t('add.pasteNames')}
        <textarea name="text" placeholder="SAM A05/A05S/A06 ; RM 13C/NOTE 50 ; VI Y04/Y29S&#10;or one model per line" spellcheck="false">${esc(text)}</textarea>
      </label>
      <p class="help">${t('add.separatorHelp')}</p>
      <div id="preview"></div>
      <div class="actions">
        <button type="button" class="btn" data-close>${esc(t('add.cancel'))}</button>
        <button class="btn primary" id="addSubmit" disabled>${esc(t('add.addModelsBtn'))}</button>
      </div>
    </form>`;
  addDlg.showModal();
  updatePreview();
  (box ? addDlg.querySelector('textarea') : addDlg.querySelector('input[name=box]')).focus();
}

function previewItems() {
  const f = $('#addForm');
  const box = Number(f.box.value);
  const parsed = inferBrands(parseModels(f.text.value, { defaultBrand: f.brand.value.trim() }), state.models);
  return parsed.map((p) => {
    const existing = state.models.find((m) => keyOf(m.brand, m.name) === p.key);
    const status = !existing ? 'new' : existing.boxes.includes(box) ? 'here' : 'other';
    return { ...p, status, boxes: existing ? existing.boxes : [] };
  });
}

function updatePreview() {
  const f = $('#addForm');
  if (!f) return;
  const box = Number(f.box.value);
  const n = boxCount(box);
  $('#boxHelp').textContent = !box ? '' : state.byBox.has(box) ? t('add.boxHoldsAlready', { box, models: models(n) }) : t('add.boxIsNew', { box });
  const items = previewItems().filter((p) => !removed.has(p.key));
  const todo = items.filter((p) => p.status !== 'here');
  const counts = { new: 0, other: 0, here: 0 };
  items.forEach((p) => counts[p.status]++);
  $('#preview').innerHTML = items.length
    ? `<div class="section-title" style="margin-top:0">${esc(t('add.modelsFound', { n: plural('count.models', items.length) }))}</div>
       <div class="preview"><div class="chips">${items.map((p) => `
         <span class="pchip ${p.status}" dir="ltr" title="${p.status === 'other' ? esc(t('add.alreadyInAnotherBox', { boxes: p.boxes.join(', '), box: box || '?' })) : p.status === 'here' ? esc(t('add.alreadyInThisBox')) : esc(t('add.newModel'))}">
           ${p.brand ? `<small>${esc(p.brand.toUpperCase())}</small>` : ''}${esc(p.name)}
           <button type="button" data-remove="${esc(p.key)}" aria-label="${esc(t('add.removeAria', { name: p.name }))}">&times;</button>
         </span>`).join('')}</div></div>
       <div class="legend"><span><i></i>${esc(t('add.legendNew', { n: counts.new }))}</span><span><i class="other"></i>${esc(t('add.legendOther', { n: counts.other }))}</span><span><i class="here"></i>${esc(t('add.legendHere', { n: counts.here }))}</span></div>`
    : `<p class="help">${t('add.previewHint')}</p>`;
  const btn = $('#addSubmit');
  btn.disabled = !box || !todo.length;
  btn.textContent = todo.length ? t('add.addNToBox', { modelsPhrase: models(todo.length), box: box || '…' }) : t('add.addModelsToBoxGeneric');
}

addDlg.addEventListener('input', updatePreview);
addDlg.addEventListener('click', (e) => {
  const r = e.target.closest('[data-remove]');
  if (r) {
    removed.add(r.dataset.remove);
    updatePreview();
  }
});
addDlg.addEventListener('submit', guard(async (e) => {
  e.preventDefault();
  const f = e.target;
  const box = Number(f.box.value);
  const models = previewItems().filter((p) => !removed.has(p.key) && p.status !== 'here').map(({ brand, name }) => ({ brand, name }));
  const btn = $('#addSubmit');
  btn.disabled = true;
  const r = await api('/add', { json: { box, models } });
  applySnapshot(r);
  addDlg.close();
  const bits = [];
  if (r.created) bits.push(t('toast.addNew', { n: r.created }));
  if (r.joined) bits.push(t('toast.addJoined', { n: r.joined, box }));
  if (r.already) bits.push(t('toast.addAlready', { n: r.already }));
  toast(t('toast.addSummary', { box, bits: bits.join(' · ') }) + (r.created ? t('toast.gettingPhotosSuffix') : ''));
  render();
  pollImages();
}));

/* ---------------- events ---------------- */
document.addEventListener('click', guard(async (e) => {
  const close = e.target.closest('[data-close]');
  if (close) return close.closest('dialog').close();

  const tag = e.target.closest('.tag[data-box]');
  if (tag && tag.tagName === 'BUTTON') {
    e.stopPropagation();
    detailDlg.open && detailDlg.close();
    location.hash = `#/box/${tag.dataset.box}`;
    return;
  }

  const act = e.target.closest('[data-act]')?.dataset.act;
  if (act === 'sell') {
    const n = Number(e.target.closest('[data-box]').dataset.box);
    const r = await api(`/boxes/${n}/sell`, { method: 'POST' });
    applySnapshot(r);
    toast(t('toast.sold', { n, stock: r.stock }));
    render();
    if (lowStockDlg.open) openLowStock();
    return;
  }
  if (act === 'more') { state.shown += PAGE; return render(); }
  if (act === 'add') return openAdd();
  if (act === 'add-text') return openAdd({ text: input.value.trim() });
  if (act === 'add-box') return openAdd({ box: e.target.closest('[data-box]').dataset.box });
  if (act === 'delete-box') {
    const n = Number(e.target.closest('[data-box]').dataset.box);
    const list = state.byBox.get(n) || [];
    const onlyHere = list.filter((m) => m.boxes.length === 1).length;
    const alsoElsewhere = list.length - onlyHere;
    const msg = !list.length
      ? t('confirm.deleteEmptyBox', { n })
      : onlyHere === 0
      ? t('confirm.deleteBoxAllShared', { n, models: models(list.length) })
      : alsoElsewhere === 0
      ? t('confirm.deleteBoxAllOnly', { n, models: models(list.length) })
      : t('confirm.deleteBoxMixed', { n, onlyModels: models(onlyHere), sharedModels: models(alsoElsewhere) });
    if (!confirm(msg)) return;
    const r = await api(`/boxes/${n}`, { method: 'DELETE' });
    applySnapshot(r);
    location.hash = '#/';
    toast(t('toast.boxDeleted', { n }) + (r.deletedModels ? t('toast.boxDeletedRemoved', { models: models(r.deletedModels) }) : '') + (r.updatedModels ? t('toast.boxDeletedKept', { models: models(r.updatedModels) }) : ''));
    return;
  }
  if (act === 'restore-box') {
    const n = Number(e.target.closest('[data-box]').dataset.box);
    const r = await api(`/deleted-boxes/${n}/restore`, { method: 'POST' });
    applySnapshot(r);
    toast(t('toast.boxRestored', { n, models: models(r.recreated + r.rejoined + r.reattached) }));
    render();
    return;
  }
  if (act === 'merge') return openMergePicker(detailId);
  if (act === 'img-web') return openImageChooser(detailId);
  if (act === 'img-url') {
    const url = $('#imgUrl').value.trim();
    if (!url) throw new Error(t('err.pasteLinkFirst'));
    return setImage(detailId, { url });
  }
  if (act === 'img-remove') {
    const updated = await api(`/models/${detailId}/image`, { method: 'DELETE' });
    Object.assign(modelById(detailId), updated);
    await refresh();
    return openDetail(detailId);
  }
  if (act === 'delete') {
    const m = modelById(detailId);
    if (!confirm(t('confirm.deleteModel', { brand: m.brand, name: m.name }))) return;
    await api(`/models/${detailId}`, { method: 'DELETE' });
    detailDlg.close();
    await refresh();
    return toast(t('toast.deleted'));
  }

  const card = e.target.closest('.card, .chip');
  if (card) {
    if (card.matches('.card')) {
      forgiveMiss().catch(() => {});
      api('/search-hit', { json: { modelId: card.dataset.id } }).catch(() => {});
    }
    return openDetail(card.dataset.id);
  }
}));

document.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && e.target.matches('.card')) openDetail(e.target.dataset.id);
  if (e.key === '/' && !e.target.matches('input, textarea, select')) {
    e.preventDefault();
    input.focus();
    input.select();
  }
});

input.addEventListener('input', () => {
  state.shown = PAGE;
  if (route().name === 'box') history.replaceState(null, '', '#/');
  render();
});
input.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { input.value = ''; render(); }
});
$('#clear').addEventListener('click', () => { input.value = ''; render(); input.focus(); });
window.addEventListener('hashchange', () => { boxFilter = ''; render(); window.scrollTo(0, 0); });

view.addEventListener('input', (e) => {
  if (e.target.id === 'boxFilter') {
    boxFilter = e.target.value;
    const pos = e.target.selectionStart;
    render();
    const el = $('#boxFilter');
    el.focus();
    el.setSelectionRange(pos, pos);
  }
});
view.addEventListener('change', guard(async (e) => {
  if (e.target.id === 'boxNote') {
    const n = route().box;
    await api(`/boxes/${n}`, { method: 'PUT', json: { note: e.target.value.trim() } });
    (state.boxes[n] ||= {}).note = e.target.value.trim() || undefined;
    toast(t('toast.noteSaved'));
  }
  if (e.target.id === 'boxStock') {
    const n = route().box;
    const stock = e.target.value.trim();
    await api(`/boxes/${n}`, { method: 'PUT', json: { stock: stock === '' ? null : Number(stock) } });
    if (stock === '') delete (state.boxes[n] ||= {}).stock;
    else (state.boxes[n] ||= {}).stock = Number(stock);
    const sellBtn = view.querySelector('[data-act="sell"]');
    if (sellBtn) sellBtn.disabled = stock === '';
    toast(stock === '' ? t('toast.stockCleared') : t('toast.stockSaved'));
  }
}));

$('#addBtn').addEventListener('click', () => openAdd({ box: route().name === 'box' ? route().box : '' }));

/* menu */
const menu = $('#menu');
$('#menuBtn').addEventListener('click', (e) => {
  e.stopPropagation();
  menu.hidden = !menu.hidden;
  $('#menuBtn').setAttribute('aria-expanded', String(!menu.hidden));
});
document.addEventListener('click', (e) => { if (!menu.contains(e.target)) menu.hidden = true; });
$('#mMisses').addEventListener('click', () => { menu.hidden = true; openMisses(); });
$('#mDeletedBoxes').addEventListener('click', () => { menu.hidden = true; openDeletedBoxes(); });
$('#mLowStock').addEventListener('click', () => { menu.hidden = true; openLowStock(); });
$('#mReorder').addEventListener('click', () => { menu.hidden = true; openReorder(); });
$('#mUnknownBrand').addEventListener('click', () => { menu.hidden = true; openUnknownBrand(); });
$('#mFetch').addEventListener('click', guard(async () => {
  menu.hidden = true;
  const r = await api('/images/queue', { json: { missing: true } });
  toast(r.queued ? t('toast.photosQueued', { n: r.queued }) : t('toast.allHavePhotos'));
  pollImages();
}));
$('#mRetry').addEventListener('click', guard(async () => {
  menu.hidden = true;
  const r = await api('/images/queue', { json: { missing: true, retryFailed: true } });
  toast(t('toast.retryingPhotos', { n: r.queued }));
  pollImages();
}));
$('#mRestore').addEventListener('click', () => { menu.hidden = true; $('#restoreFile').click(); });
$('#restoreFile').addEventListener('change', guard(async (e) => {
  const file = e.target.files[0];
  e.target.value = '';
  if (!file) return;
  if (!confirm(t('confirm.restoreBackup'))) return;
  const snap = await api('/restore', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: await file.text() });
  applySnapshot(snap);
  render();
  toast(t('toast.backupRestored'));
}));

async function refresh() {
  applySnapshot(await api('/data'));
  render();
}

/* ---------------- access gate (activation + login) ---------------- */
const gate = $('#gate');
let me = null; // { username } once logged in

function renderGate(auth, mode) {
  gate.hidden = false;
  document.body.style.overflow = 'hidden';
  gate.innerHTML = `
    <div class="gate-card">
      <img src="logo-full.png" alt="${esc(t('app.name'))}" class="gate-logo">
      ${mode === 'activate' ? `
        <p class="help">${t('gate.notActivatedYet')}</p>
        <p class="device-id" dir="ltr">${esc(auth.deviceId)}</p>
        <form id="gateForm">
          <label class="lab">${t('gate.activationCodeLabel')}<textarea name="code" dir="ltr" required placeholder="${esc(t('gate.pasteCodeGiven'))}" rows="3"></textarea></label>
          <label class="lab">${t('gate.chooseUsername')}<input name="username" required autocomplete="username"></label>
          <label class="lab">${t('gate.choosePassword')}<input name="password" type="password" required autocomplete="new-password" minlength="4"></label>
          <p class="help err" id="gateHelp"></p>
          <button class="btn primary">${esc(t('gate.activateBtn'))}</button>
        </form>` : ''}
      ${mode === 'relock' ? `
        <p class="help err">${t('gate.lockedDifferentPc')}</p>
        <p class="help">${t('gate.relockExplain')}</p>
        <p class="device-id" dir="ltr">${esc(auth.deviceId)}</p>
        <form id="gateForm">
          <label class="lab">${t('gate.activationCodeLabel')}<textarea name="code" dir="ltr" required placeholder="${esc(t('gate.pasteNewCodeGiven'))}" rows="3"></textarea></label>
          <p class="help err" id="gateHelp"></p>
          <button class="btn primary">${esc(t('gate.unlockBtn'))}</button>
        </form>` : ''}
      ${mode === 'login' ? `
        <p class="help">${t('gate.signInToContinue')}</p>
        <form id="gateForm">
          <label class="lab">${t('gate.usernameLabel')}<input name="username" required autocomplete="username"></label>
          <label class="lab">${t('gate.passwordLabel')}<input name="password" type="password" required autocomplete="current-password"></label>
          <label class="remember-me"><input type="checkbox" name="remember"> ${t('gate.rememberMe')}</label>
          <p class="help err" id="gateHelp"></p>
          <button class="btn primary">${esc(t('gate.signInBtn'))}</button>
        </form>` : ''}
      ${mode !== 'login' ? `
        <details class="gate-alt">
          <summary>${t('gate.orRestoreBackup')}</summary>
          <label class="lab">${t('gate.backupFileLabel')}<input type="file" id="gateRestoreFile" accept=".json,application/json"></label>
          <p class="help err" id="gateRestoreHelp"></p>
        </details>` : ''}
    </div>`;
  gate.querySelector('#gateForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    const help = $('#gateHelp');
    help.textContent = '';
    try {
      if (mode === 'activate') await api('/activate', { json: { code: f.get('code'), username: f.get('username'), password: f.get('password') } });
      else if (mode === 'relock') await api('/renew', { json: { code: f.get('code') } });
      else await api('/login', { json: { username: f.get('username'), password: f.get('password'), remember: f.get('remember') === 'on' } });
      await boot();
    } catch (err) {
      help.textContent = err.message;
    }
  });
  gate.querySelector('#gateRestoreFile')?.addEventListener('change', async (e) => {
    const file = e.target.files[0];
    e.target.value = '';
    if (!file) return;
    const help = $('#gateRestoreHelp');
    help.textContent = '';
    try {
      const data = JSON.parse(await file.text());
      await api('/restore-license', { json: data });
      await boot();
    } catch (err) {
      help.textContent = err instanceof SyntaxError ? t('err.invalidJsonFile') : err.message;
    }
  });
  gate.querySelector('input, textarea')?.focus();
}

/** Checks activation + login state. Shows the gate and returns false if either is missing. */
async function checkAuth() {
  let auth;
  try {
    auth = await fetch('/api/auth').then((r) => r.json());
  } catch {
    return false; // server not reachable yet; the next user action will explain why
  }
  if (auth.status !== 'ok') {
    renderGate(auth, auth.status === 'never' ? 'activate' : 'relock');
    return false;
  }
  if (!auth.loggedIn) {
    renderGate(auth, 'login');
    return false;
  }
  me = { username: auth.username, role: auth.role };
  $('#menuUser').textContent = t('menu.signedInAs', { username: auth.username });
  const isAdmin = auth.role === 'admin';
  $('#mAdminHr').hidden = !isAdmin;
  $('#mUsers').hidden = !isAdmin;
  $('#mActivity').hidden = !isAdmin;
  gate.hidden = true;
  document.body.style.overflow = '';
  return true;
}

$('#mLogout').addEventListener('click', guard(async () => {
  menu.hidden = true;
  await api('/logout', { method: 'POST' });
  await checkAuth();
}));

const pwDlg = $('#pwDlg');
$('#mChangePw').addEventListener('click', () => {
  menu.hidden = true;
  pwDlg.innerHTML = `
    <form class="dlg" id="pwForm">
      <div class="dlg-head"><h2>${t('pw.title')}</h2><button type="button" class="x" data-close aria-label="${esc(t('detail.close'))}">&times;</button></div>
      <label class="lab">${t('pw.currentPassword')}<input name="currentPassword" type="password" required autocomplete="current-password"></label>
      <label class="lab">${t('pw.newPassword')}<input name="newPassword" type="password" required autocomplete="new-password" minlength="4"></label>
      <p class="help err" id="pwHelp"></p>
      <div class="actions"><button type="button" class="btn" data-close>${esc(t('add.cancel'))}</button><button class="btn primary">${esc(t('pw.save'))}</button></div>
    </form>`;
  pwDlg.showModal();
  pwDlg.querySelector('#pwForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    try {
      await api('/change-password', { json: { currentPassword: f.get('currentPassword'), newPassword: f.get('newPassword') } });
      pwDlg.close();
      toast(t('toast.passwordChanged'));
    } catch (err) {
      $('#pwHelp').textContent = err.message;
    }
  });
});

const photoDlg = $('#photoSettingsDlg');
async function openPhotoSettings() {
  menu.hidden = true;
  const s = await api('/settings');
  photoDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head"><h2>${t('photoSettings.title')}</h2><button class="x" data-close aria-label="${esc(t('detail.close'))}">&times;</button></div>
      ${s.serperApiKey
        ? `<p class="help ok">${esc(t('photoSettings.usingSerper', { last4: s.serperApiKey.slice(-4) }))}</p>
           <div class="actions"><button class="btn danger" id="removeKey">${esc(t('photoSettings.removeKeyBtn'))}</button></div>`
        : `<p class="help">${t('photoSettings.freeSearchExplain')}</p>
           <p class="help">${t('photoSettings.serperPitch', { link: '<a href="https://serper.dev" target="_blank" rel="noopener">serper.dev</a>' })}</p>`}
      <form id="keyForm">
        <label class="lab">${s.serperApiKey ? t('photoSettings.replaceKeyLabel') : t('photoSettings.serperApiKeyLabel')}<input name="key" dir="ltr" placeholder="${esc(t('photoSettings.pasteApiKey'))}" autocomplete="off" spellcheck="false"></label>
        <p class="help err" id="keyHelp"></p>
        <div class="actions"><button class="btn primary" id="keySave">${esc(t('photoSettings.saveAndTest'))}</button></div>
      </form>
    </div>`;
  photoDlg.showModal();
  $('#removeKey')?.addEventListener('click', guard(async () => {
    await api('/settings/serper-key', { method: 'DELETE' });
    toast(t('toast.keyRemoved'));
    openPhotoSettings();
  }));
  photoDlg.querySelector('#keyForm').addEventListener('submit', guard(async (e) => {
    e.preventDefault();
    const key = new FormData(e.target).get('key').trim();
    if (!key) return;
    const btn = $('#keySave');
    const help = $('#keyHelp');
    btn.disabled = true;
    help.textContent = '';
    help.className = 'help';
    try {
      await api('/settings/serper-key', { json: { key } });
      toast(t('toast.keySaved'));
      openPhotoSettings();
    } catch (err) {
      help.textContent = err.message;
      help.className = 'help err';
      btn.disabled = false;
    }
  }));
}
$('#mPhotoSettings').addEventListener('click', guard(openPhotoSettings));

/* ---------------- staff accounts (admin only) ---------------- */
const usersDlg = $('#usersDlg');
async function openUsers() {
  menu.hidden = true;
  const list = await api('/users');
  usersDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head"><h2>${t('users.title')}</h2><button class="x" data-close aria-label="${esc(t('misses.close'))}">&times;</button></div>
      <div class="misslist">
        ${list.map((u) => `
          <div class="miss">
            <b>${esc(u.username)}</b>
            <span class="when">${u.role === 'admin' ? esc(t('users.roleAdmin')) : esc(t('users.roleStaff'))}</span>
            <span class="acts">
              <button type="button" class="btn small" data-reset="${esc(u.username)}">${esc(t('users.resetPassword'))}</button>
              ${u.username !== me?.username ? `<button type="button" class="btn small danger" data-remove="${esc(u.username)}">${esc(t('users.remove'))}</button>` : ''}
            </span>
          </div>`).join('')}
      </div>
      <form id="addUserForm">
        <div class="section-title">${t('users.addTitle')}</div>
        <div class="row">
          <label class="lab">${t('users.usernameLabel')}<input name="username" required autocomplete="off"></label>
          <label class="lab">${t('users.passwordLabel')}<input name="password" type="password" required minlength="4" autocomplete="new-password"></label>
        </div>
        <label class="lab">${t('users.roleLabel')}<select name="role"><option value="staff">${t('users.roleStaff')}</option><option value="admin">${t('users.roleAdmin')}</option></select></label>
        <p class="help err" id="addUserHelp"></p>
        <div class="actions"><button class="btn primary">${esc(t('users.addBtn'))}</button></div>
      </form>
      <div class="actions"><button class="btn" data-close>${esc(t('misses.close'))}</button></div>
    </div>`;
  if (!usersDlg.open) usersDlg.showModal();
}
usersDlg.addEventListener('submit', guard(async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const help = $('#addUserHelp');
  help.textContent = '';
  try {
    await api('/users', { json: { username: f.get('username'), password: f.get('password'), role: f.get('role') } });
    toast(t('toast.userAdded', { username: f.get('username') }));
    openUsers();
  } catch (err) {
    help.textContent = err.message;
  }
}));
usersDlg.addEventListener('click', guard(async (e) => {
  const reset = e.target.closest('[data-reset]');
  const remove = e.target.closest('[data-remove]');
  if (reset) {
    const pw = prompt(t('users.newPasswordPrompt', { username: reset.dataset.reset }));
    if (!pw) return;
    if (pw.length < 4) throw new Error(t('users.passwordTooShort'));
    await api(`/users/${encodeURIComponent(reset.dataset.reset)}/password`, { json: { newPassword: pw } });
    toast(t('toast.passwordReset', { username: reset.dataset.reset }));
    return;
  }
  if (remove) {
    if (!confirm(t('confirm.removeUser', { username: remove.dataset.remove }))) return;
    await api(`/users/${encodeURIComponent(remove.dataset.remove)}`, { method: 'DELETE' });
    toast(t('toast.userRemoved', { username: remove.dataset.remove }));
    openUsers();
  }
}));
$('#mUsers').addEventListener('click', guard(openUsers));

const activityDlg = $('#activityDlg');
async function openActivity() {
  menu.hidden = true;
  const rows = await api('/activity');
  activityDlg.innerHTML = `
    <div class="dlg">
      <div class="dlg-head"><h2>${t('activity.title')}</h2><button class="x" data-close aria-label="${esc(t('misses.close'))}">&times;</button></div>
      <p class="help">${rows.length ? t('activity.bodyNonEmpty') : t('activity.bodyEmpty')}</p>
      <div class="misslist">
        ${rows.map((a) => `
          <div class="miss">
            <b>${esc(a.username)}</b>
            <span class="when">${esc(when(a.at))} · ${esc(a.detail)}</span>
          </div>`).join('')}
      </div>
      <div class="actions"><button class="btn" data-close>${esc(t('misses.close'))}</button></div>
    </div>`;
  if (!activityDlg.open) activityDlg.showModal();
}
$('#mActivity').addEventListener('click', guard(openActivity));

/* ---------------- language ---------------- */
function renderLangLabel() {
  $('#mLang').textContent = t('menu.language');
}
$('#mLang').addEventListener('click', () => {
  menu.hidden = true;
  setLang(getLang() === 'ar' ? 'en' : 'ar');
  translateStaticText();
  render();
});

/* ---------------- theme ---------------- */
// The button always names what tapping it switches TO next (same convention as the language button).
const THEME_LABEL_KEY = { system: 'menu.themeToLight', light: 'menu.themeToDark', dark: 'menu.themeToSystem' };
function renderThemeLabel(theme) {
  $('#mTheme').textContent = t(THEME_LABEL_KEY[theme]);
}
$('#mTheme').addEventListener('click', () => {
  menu.hidden = true;
  renderThemeLabel(cycleTheme());
});

// Static markup (the search box, top bar) that index.html already renders once at load, so it
// needs its own pass whenever the language changes - everything else goes through render().
function translateStaticText() {
  input.placeholder = t('search.placeholder');
  $('#clear').setAttribute('aria-label', t('search.clearAria'));
  $('#menuBtn').setAttribute('aria-label', t('menu.moreOptions'));
  $('#addLabel').textContent = t('shelf.addModels');
  $('#mMisses').firstChild.textContent = t('menu.searchesNoResult') + ' ';
  $('#mDeletedBoxes').firstChild.textContent = t('menu.deletedBoxes') + ' ';
  $('#mLowStock').firstChild.textContent = t('menu.lowStock') + ' ';
  $('#mReorder').textContent = t('menu.reorderReport');
  $('#mUnknownBrand').firstChild.textContent = t('menu.unknownBrand') + ' ';
  $('#mUsers').textContent = t('menu.manageStaff');
  $('#mActivity').textContent = t('menu.activityLog');
  $('#mFetch').textContent = t('menu.getMissingPhotos');
  $('#mRetry').textContent = t('menu.retryFailedPhotos');
  $('#mPhotoSettings').textContent = t('menu.photoSettings');
  $('#mDownloadCsv').textContent = t('menu.downloadCsv');
  $('#mDownloadBackup').textContent = t('menu.downloadBackup');
  $('#mRestore').textContent = t('menu.restoreBackup');
  $('#mDownloadActivation').textContent = t('menu.downloadActivationBackup');
  $('#mChangePw').textContent = t('menu.changePassword');
  $('#mLogout').textContent = t('menu.logout');
  renderLangLabel();
  renderThemeLabel(getTheme());
  if (me) $('#menuUser').textContent = t('menu.signedInAs', { username: me.username });
}

/* ---------------- start ---------------- */
async function boot() {
  applyDirection();
  applyTheme();
  translateStaticText();
  if (!(await checkAuth())) return;
  await refresh();
  input.focus();
  pollImages();
}
guard(boot)();
