// JSON-file storage: one file, atomic writes, one automatic backup per day.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { keyOf, inferBrands } from '../public/parse.js';
import { buildIndex, search, squash } from '../public/search.js';

const BACKUPS_KEPT = 30;
const MISSES_KEPT = 300;

export function createStore(dataDir) {
  const file = path.join(dataDir, 'db.json');
  const backupDir = path.join(dataDir, 'backups');
  fs.mkdirSync(backupDir, { recursive: true });

  let db = { version: 1, models: [], boxes: {} };
  if (fs.existsSync(file)) db = JSON.parse(fs.readFileSync(file, 'utf8'));
  db.boxes ||= {};
  db.misses ||= {}; // searches that found nothing: { [squashed query]: { q, count, first, last } }
  db.deletedBoxes ||= []; // a snapshot of each deleted box, so it can be restored later: see removeBox
  db.activity ||= []; // who did what: [{ at, username, action, detail }], newest first
  db.hits ||= {}; // searches that DID find a model, for "frequently looked up": { [modelId]: { count, last } }

  // Written synchronously, on every change, via write-then-rename: the HTTP response for a change
  // only goes out once it is durably on disk, so closing the console window (or a taskkill) can
  // never lose an edit the way a debounced/background write could.
  function writeNow() {
    const tmp = `${file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(db));
    fs.renameSync(tmp, file);
  }
  const save = writeNow;
  function backupToday() {
    if (!fs.existsSync(file)) return;
    const dest = path.join(backupDir, `db-${new Date().toISOString().slice(0, 10)}.json`);
    if (!fs.existsSync(dest)) fs.copyFileSync(file, dest);
    const old = fs.readdirSync(backupDir).filter((f) => /^db-.*\.json$/.test(f)).sort();
    for (const f of old.slice(0, Math.max(0, old.length - BACKUPS_KEPT))) fs.unlinkSync(path.join(backupDir, f));
  }
  backupToday();

  const newId = () => crypto.randomBytes(4).toString('hex');
  const findByKey = (key) => db.models.find((m) => keyOf(m.brand, m.name) === key);
  const byId = (id) => db.models.find((m) => m.id === id);
  const sortBoxes = (m) => (m.boxes = [...new Set(m.boxes)].sort((a, b) => a - b));

  /**
   * Put models into a box. Existing models (same brand + name) just gain the box.
   * @returns {{ created: object[], joined: object[], already: object[] }}
   */
  function addToBox(box, parsed) {
    const res = { created: [], joined: [], already: [] };
    inferBrands(parsed, db.models);
    for (const p of parsed) {
      const name = String(p.name || '').trim();
      if (!name) continue;
      const brand = String(p.brand || '').trim();
      const existing = findByKey(keyOf(brand, name));
      if (existing) {
        if (existing.boxes.includes(box)) res.already.push(existing);
        else {
          existing.boxes.push(box);
          sortBoxes(existing);
          res.joined.push(existing);
        }
      } else {
        const m = { id: newId(), brand, name, boxes: [box], image: null, imgState: 'none', added: new Date().toISOString() };
        db.models.push(m);
        res.created.push(m);
      }
    }
    db.boxes[box] ||= {};
    pruneMisses();
    save();
    return res;
  }

  function update(id, patch) {
    const m = byId(id);
    if (!m) return null;
    if (typeof patch.name === 'string' && patch.name.trim()) m.name = patch.name.trim();
    if (typeof patch.brand === 'string') m.brand = patch.brand.trim();
    if (Array.isArray(patch.boxes)) {
      const boxes = patch.boxes.map(Number).filter((n) => Number.isInteger(n) && n > 0);
      if (boxes.length) {
        m.boxes = boxes;
        sortBoxes(m);
        for (const b of m.boxes) db.boxes[b] ||= {};
      }
    }
    pruneMisses();
    save();
    return m;
  }

  function remove(id) {
    const i = db.models.findIndex((m) => m.id === id);
    if (i < 0) return null;
    const [m] = db.models.splice(i, 1);
    delete db.hits[id];
    save();
    return m;
  }

  /**
   * Fold `absorbId` into `keepId`: their box lists are combined, `absorbId`'s photo is kept only if
   * `keepId` doesn't already have one, and `absorbId` is deleted outright. For when the same phone
   * ended up as two entries (an import quirk, a typo caught late).
   * @returns {{ keep: object, boxesAdded: number }}
   */
  function mergeModels(keepId, absorbId) {
    if (keepId === absorbId) throw new Error('Choose two different models to merge.');
    const keep = byId(keepId);
    const absorb = byId(absorbId);
    if (!keep || !absorb) throw new Error('One of those models no longer exists.');
    const before = keep.boxes.length;
    keep.boxes = [...new Set([...keep.boxes, ...absorb.boxes])];
    sortBoxes(keep);
    const inheritedImage = !keep.image && absorb.image ? absorb.image : null;
    if (inheritedImage) {
      keep.image = absorb.image;
      keep.imgState = absorb.imgState;
    }
    db.models = db.models.filter((m) => m.id !== absorbId);
    delete db.hits[absorbId];
    pruneMisses();
    save();
    return { keep, boxesAdded: keep.boxes.length - before, inheritedImage: !!inheritedImage, absorbedImage: !inheritedImage ? absorb.image : null };
  }

  /** Whichever model this box's glass fits, one fewer is left. Floors at 0; no-op if stock isn't tracked. */
  function decrementStock(box) {
    const info = db.boxes[box];
    if (!info || info.stock == null) throw new Error(`Box ${box} doesn’t have a stock count set.`);
    info.stock = Math.max(0, info.stock - 1);
    save();
    return info.stock;
  }

  const ACTIVITY_KEPT = 300;
  function logActivity(username, action, detail) {
    db.activity.unshift({ at: new Date().toISOString(), username, action, detail });
    db.activity.length = Math.min(db.activity.length, ACTIVITY_KEPT);
    save();
  }

  const HITS_KEPT = 500;
  /** A search actually found this model - tracked so the home screen can surface frequent lookups. */
  function logHit(modelId) {
    if (!byId(modelId)) return;
    const now = new Date().toISOString();
    const h = (db.hits[modelId] ||= { count: 0, first: now, last: now });
    h.count++;
    h.last = now;
    const keys = Object.keys(db.hits);
    if (keys.length > HITS_KEPT) {
      keys.sort((a, b) => db.hits[a].last.localeCompare(db.hits[b].last));
      for (const k of keys.slice(0, keys.length - HITS_KEPT)) delete db.hits[k];
    }
    save();
  }

  /** Remember a search that found no model. Repeats of the same search are counted. */
  function logMiss(query) {
    const q = String(query || '').trim().replace(/\s+/g, ' ').slice(0, 60);
    const key = squash(q);
    if (key.length < 2) return null;
    const now = new Date().toISOString();
    const m = (db.misses[key] ||= { q, count: 0, first: now, last: now });
    m.q = q;
    m.count++;
    m.last = now;
    const keys = Object.keys(db.misses);
    if (keys.length > MISSES_KEPT) {
      keys.sort((a, b) => db.misses[a].last.localeCompare(db.misses[b].last));
      for (const k of keys.slice(0, keys.length - MISSES_KEPT)) delete db.misses[k];
    }
    save();
    return key;
  }

  function dismissMiss(key) {
    const had = key in db.misses;
    delete db.misses[key];
    if (had) save();
    return had;
  }

  function clearMisses() {
    db.misses = {};
    save();
  }

  /** Drop logged searches that would now find something (after models were added or renamed). */
  function pruneMisses() {
    const keys = Object.keys(db.misses);
    if (!keys.length) return;
    const index = buildIndex(db.models);
    let changed = false;
    for (const k of keys) {
      if (search(index, db.misses[k].q).hits.length) {
        delete db.misses[k];
        changed = true;
      }
    }
    if (changed) save();
  }

  function setBoxNote(box, note) {
    db.boxes[box] ||= {};
    if (note) db.boxes[box].note = String(note).slice(0, 80);
    else delete db.boxes[box].note;
    save();
  }

  /** How many of the glass sheets for this box are left in stock. `null`/omitted clears it (not tracked). */
  function setBoxStock(box, stock) {
    db.boxes[box] ||= {};
    const n = Number(stock);
    if (stock === '' || stock == null || !Number.isFinite(n)) delete db.boxes[box].stock;
    else db.boxes[box].stock = Math.max(0, Math.round(n));
    save();
  }

  const DELETED_BOXES_KEPT = 200;

  /**
   * Delete a box. A model that only lived in this box is deleted outright; a model that's also in
   * other boxes just loses this one. A snapshot is kept so the box can be brought back with `restoreBox`,
   * and so a later search for one of its models can say where it used to live.
   * @returns {{ deleted: object[], updated: object[] }} `deleted` models the caller should also drop any photo for.
   */
  function removeBox(box) {
    const deleted = [];
    const updated = [];
    db.models = db.models.filter((m) => {
      if (!m.boxes.includes(box)) return true;
      m.boxes = m.boxes.filter((b) => b !== box);
      if (m.boxes.length === 0) {
        deleted.push(m);
        return false;
      }
      updated.push(m);
      return true;
    });
    const info = db.boxes[box] || {};
    db.deletedBoxes = db.deletedBoxes.filter((d) => d.box !== box);
    db.deletedBoxes.unshift({
      box,
      note: info.note ?? null,
      stock: info.stock ?? null,
      deletedAt: new Date().toISOString(),
      deletedModels: deleted.map((m) => ({ brand: m.brand, name: m.name })),
      updatedModels: updated.map((m) => ({ id: m.id, brand: m.brand, name: m.name })),
    });
    db.deletedBoxes.length = Math.min(db.deletedBoxes.length, DELETED_BOXES_KEPT);
    delete db.boxes[box];
    pruneMisses();
    save();
    return { deleted, updated };
  }

  /**
   * Bring a deleted box back: models that were deleted outright are re-created (or rejoined if a
   * model of the same name exists again some other way); models that just lost the tag get it back
   * if they still exist. Its note/stock come back too, without overwriting anything set since.
   * @returns {{ box: number, recreated: number, rejoined: number, reattached: number }}
   */
  function restoreBox(box) {
    const i = db.deletedBoxes.findIndex((d) => d.box === box);
    if (i < 0) throw new Error(`Box ${box} has no deleted record to restore.`);
    const rec = db.deletedBoxes[i];
    db.deletedBoxes.splice(i, 1);

    const added = addToBox(box, rec.deletedModels);
    let reattached = 0;
    for (const u of rec.updatedModels) {
      const m = byId(u.id);
      if (m && !m.boxes.includes(box)) {
        m.boxes.push(box);
        sortBoxes(m);
        reattached++;
      }
    }
    db.boxes[box] ||= {};
    if (rec.note && !db.boxes[box].note) db.boxes[box].note = rec.note;
    if (rec.stock != null && db.boxes[box].stock == null) db.boxes[box].stock = rec.stock;
    save();
    return { box, recreated: added.created.length, rejoined: added.joined.length, reattached };
  }

  function dismissDeletedBox(box) {
    const had = db.deletedBoxes.some((d) => d.box === box);
    db.deletedBoxes = db.deletedBoxes.filter((d) => d.box !== box);
    if (had) save();
    return had;
  }

  function clearDeletedBoxes() {
    const n = db.deletedBoxes.length;
    db.deletedBoxes = [];
    save();
    return n;
  }

  function replaceAll(next) {
    if (!next || !Array.isArray(next.models)) throw new Error('Not a valid backup file');
    fs.copyFileSync(file, path.join(backupDir, `before-restore-${Date.now()}.json`));
    db = {
      version: 1, models: next.models, boxes: next.boxes || {}, misses: next.misses || {},
      deletedBoxes: next.deletedBoxes || [], activity: next.activity || [], hits: next.hits || {},
    };
    writeNow();
  }

  return {
    get data() { return db; },
    byId, addToBox, update, remove, mergeModels, decrementStock, setBoxNote, setBoxStock,
    removeBox, restoreBox, dismissDeletedBox, clearDeletedBoxes, replaceAll, save, writeNow,
    logMiss, dismissMiss, clearMisses, logActivity, logHit,
  };
}
