// One-off import: data/excel_rows.json (from dump_excel.py) -> data/db.json.
// Refuses to overwrite an existing database unless --force is given.
import fs from 'node:fs';
import { parseModels, keyOf, inferBrands } from '../public/parse.js';

const force = process.argv.includes('--force');
if (fs.existsSync('data/db.json') && !force) {
  console.error('data/db.json already exists. Re-run with --force to replace it (a backup is kept).');
  process.exit(1);
}
if (fs.existsSync('data/db.json')) fs.copyFileSync('data/db.json', `data/db.before-import-${Date.now()}.json`);

const rows = JSON.parse(fs.readFileSync('data/excel_rows.json', 'utf8'));
const perBox = rows.map((r) => ({ box: r.box, models: parseModels(r.text) }));
// Unbranded names take the brand they have elsewhere, when that is unambiguous.
inferBrands(perBox.flatMap((b) => b.models), perBox.flatMap((b) => b.models));

const models = new Map();
let n = 0;
for (const { box, models: list } of perBox) {
  for (const p of list) {
    const key = keyOf(p.brand, p.name);
    let m = models.get(key);
    if (!m) {
      m = { id: (0x10000000 + n++).toString(16), brand: p.brand, name: p.name, boxes: [], image: null, imgState: 'none', added: new Date().toISOString() };
      models.set(key, m);
    }
    if (!m.boxes.includes(box)) m.boxes.push(box);
  }
}
const boxes = {};
for (const { box } of perBox) boxes[box] = {};
const db = { version: 1, models: [...models.values()], boxes };
fs.writeFileSync('data/db.json', JSON.stringify(db));
console.log(`${db.models.length} models across ${Object.keys(boxes).length} boxes`);
console.log('unbranded:', db.models.filter((m) => !m.brand).length, ' in several boxes:', db.models.filter((m) => m.boxes.length > 1).length);
