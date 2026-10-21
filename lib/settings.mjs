// App-wide settings (the optional photo-search API key, the low-stock threshold). Kept next to
// license.json, not inside data/, so it's never swept into a backup file or the installer's starter data.
import fs from 'node:fs';
import path from 'node:path';

const DEFAULT_LOW_STOCK = 2;

export function createSettingsStore(appDir) {
  const file = path.join(appDir, 'settings.json');
  let state = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {};

  function save() {
    fs.writeFileSync(file, JSON.stringify(state, null, 2));
  }

  return {
    get serperApiKey() {
      return state.serperApiKey || null;
    },
    setSerperApiKey(key) {
      const k = String(key || '').trim();
      if (k) state.serperApiKey = k;
      else delete state.serperApiKey;
      save();
    },
    get lowStockThreshold() {
      return Number.isInteger(state.lowStockThreshold) ? state.lowStockThreshold : DEFAULT_LOW_STOCK;
    },
    setLowStockThreshold(n) {
      const v = Math.round(Number(n));
      if (!Number.isFinite(v) || v < 0) throw new Error('Enter a number of 0 or more.');
      state.lowStockThreshold = v;
      save();
    },
  };
}
