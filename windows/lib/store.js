'use strict';
// Small JSON files in the user's app-data folder: settings and the game catalog.
const fs = require('fs');
const path = require('path');

class JsonFile {
  constructor(file, fallback) {
    this.file = file;
    this.data = fallback;
    try { if (fs.existsSync(file)) this.data = Object.assign(fallback, JSON.parse(fs.readFileSync(file, 'utf8'))); } catch (_) { /* damaged: start fresh */ }
    this.timer = null;
  }
  save() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.flush(), 300);
  }
  flush() {
    clearTimeout(this.timer); this.timer = null;
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      const tmp = this.file + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(this.data));
      fs.renameSync(tmp, this.file);
    } catch (_) { /* best effort */ }
  }
}

class Store {
  constructor(dir) {
    this.dir = dir;
    this.prefs = new JsonFile(path.join(dir, 'prefs.json'), {
      lang: '', theme: 'console', bindings: {}, favorites: [], hidden: [], completed: [], collections: {},
      recent: [], plays: [], keep: true, onboarded: false, skipped: false, sort: 'name', addedAt: {}, kept: {}
    });
    this.catalog = new JsonFile(path.join(dir, 'catalog.json'), { games: [] });
  }
  get(k) { return this.prefs.data[k]; }
  set(k, v) { this.prefs.data[k] = v; this.prefs.save(); }
  flush() { this.prefs.flush(); this.catalog.flush(); }
  games() { return this.catalog.data.games || []; }
  /** Replaces the Telegram games; remembers when new ones first appeared (not on the first catalog). */
  replaceGames(games) {
    const before = new Set(this.games().map((g) => g.id));
    if (before.size > 0) {
      const fresh = games.filter((g) => !before.has(g.id));
      if (fresh.length > 0 && fresh.length <= 150) {
        const added = this.get('addedAt'); const now = Date.now();
        for (const g of fresh) if (!added[g.id]) added[g.id] = now;
        for (const k of Object.keys(added)) if (now - added[k] > 60 * 86400000) delete added[k];
        this.set('addedAt', added);
      }
    }
    this.catalog.data.games = games; this.catalog.save();
  }
}

module.exports = { Store };
