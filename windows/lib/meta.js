'use strict';
// Game names, covers and descriptions (the Android app's GameMeta.kt, for Windows):
// OpenVGDB for names, dates, genres and descriptions; libretro's list of box art names for covers; Wikipedia when the
// database has no description. Everything found is kept in gamemeta.json; your own choices ("manual") are never replaced.
const fs = require('fs');
const path = require('path');
const https = require('https');
const { execFile } = require('child_process');
const catalog = require('./catalog');

const VERSION = 2;
const SYSTEM = { N64: 23, GBA: 20, GBC: 21, GB: 19, NDS: 24, SNES: 26, NES: 25, MD: 33, MAME: 2, PSP: 39, GC: 22 };
const PLATFORM = { N64: 'Nintendo 64', NDS: 'Nintendo DS', GBA: 'Game Boy Advance', GBC: 'Game Boy Color', GB: 'Game Boy', SNES: 'Super Nintendo', NES: 'Nintendo Entertainment System',
  MD: 'Mega Drive', PSP: 'PlayStation Portable', PS2: 'PlayStation 2', GC: 'GameCube', WII: 'Wii', PS3: 'PlayStation 3', SWITCH: 'Nintendo Switch', MAME: 'arcade' };

function get(url, file, depth = 0) {
  return new Promise((resolve) => {
    if (depth > 6) { resolve(null); return; }
    const req = https.get(url, { headers: { 'User-Agent': 'RetroStream/1.0 (retro game launcher)' }, timeout: 30000 }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) { res.resume(); resolve(get(new URL(res.headers.location, url).toString(), file, depth + 1)); return; }
      if (res.statusCode !== 200) { res.resume(); resolve(null); return; }
      if (file) { const out = fs.createWriteStream(file); res.pipe(out); out.on('finish', () => out.close(() => resolve(file))); out.on('error', () => resolve(null)); return; }
      let d = ''; res.setEncoding('utf8'); res.on('data', (c) => { d += c; }); res.on('end', () => resolve(d));
    });
    req.on('error', () => resolve(null)); req.on('timeout', () => { req.destroy(); resolve(null); });
  });
}

// ------------------------------------------------------------------ names (same rules as Android)
const REGIONS = 'USA|Europe|Japan|World|Korea|Australia|Asia|Germany|France|Spain|Italy|Brazil|China|Netherlands|Sweden|Canada|Russia|UK';
const ascii = (s) => s.normalize('NFKD').replace(/[^\x00-\x7F]/g, '').toLowerCase().replace(/&/g, 'and');
function tails(s) { let t = s.trim().replace(/(\s+[A-Za-z]{2}(,[A-Za-z]{2})+)+$/, '').trim(); t = t.replace(new RegExp('(\\s+(' + REGIONS + '))+$', 'i'), '').trim(); return t || s; }
function key(s) {
  let t = tails(ascii(s).replace(/[([].*?[)\]]/g, '').trim());
  t = t.replace(/^(.*), (the|a|an)$/, '$2 $1').replace(/^(.*?), (the|a|an)( - .*)$/, '$2 $1$3').replace(/^(the|a|an) /, '');
  return t.replace(/[^a-z0-9]/g, '');
}
function words(s) { return new Set((tails(ascii(s).replace(/[([].*?[)\]]/g, '')).match(/[a-z0-9]+/g) || []).filter((w) => !['the', 'a', 'an', 'of'].includes(w))); }
function jaccard(a, b) { let i = 0; for (const x of a) if (b.has(x)) i++; return i / (a.size + b.size - i || 1); }
function regionOf(stem) {
  const t = (stem.match(/[([]([^)\]]*)[)\]]/g) || []).join(' ').toLowerCase();
  if (/\busa\b|\(u\)/.test(t)) return 'USA'; if (/europe|\(e\)/.test(t)) return 'Europe'; if (/japan|\(j\)/.test(t)) return 'Japan'; if (/world/.test(t)) return 'World'; return '';
}
const thumbName = (n) => n.replace(/[&*/:`<>?\\|"]/g, '_');

class Meta {
  constructor({ dir, t }) {
    this.dir = dir; this.t = t; this.file = path.join(dir, 'gamemeta.json');
    this.cache = {}; try { const j = JSON.parse(fs.readFileSync(this.file, 'utf8')); if (j._v === VERSION) this.cache = j; } catch (_) { /* fresh */ }
    this.running = false; this.db = null; this.names = {};
  }
  save() { try { this.cache._v = VERSION; const tmp = this.file + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(this.cache)); fs.renameSync(tmp, this.file); } catch (_) { /* best effort */ } }

  /** The game as the screens show it: clean name, cover name, description and details. */
  apply(g) {
    const m = this.cache[g.id]; if (!m) return g;
    return Object.assign({}, g, { base: g.title, title: m.t || g.title, cover: m.c || '', desc: m.d || '', year: m.y || 0, genre: m.g || '', dev: m.dev || '', pub: m.pub || '', region: m.r || '' });
  }

  // ------------------------------------------------------------------ the databases
  async database() {
    if (this.db) return this.db;
    const file = path.join(this.dir, 'openvgdb.sqlite');
    if (!fs.existsSync(file) || fs.statSync(file).size < 1000000) {
      const zip = path.join(this.dir, 'openvgdb.zip');
      if (!(await get('https://github.com/OpenVGDB/OpenVGDB/releases/latest/download/openvgdb.zip', zip))) return null;
      const seven = require('7zip-bin').path7za.replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep);
      const tmp = path.join(this.dir, 'openvgdb-tmp');
      await new Promise((res) => execFile(seven, ['x', '-y', '-o' + tmp, zip], { windowsHide: true }, () => res()));
      const found = (function find(d) { for (const f of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, f.name); if (f.isDirectory()) { if (f.name !== '__MACOSX') { const r = find(p); if (r) return r; } } else if (f.name === 'openvgdb.sqlite') return p; } return null; })(tmp);
      if (!found) return null;
      fs.renameSync(found, file); fs.rmSync(tmp, { recursive: true, force: true }); fs.rmSync(zip, { force: true });
    }
    const initSqlJs = require('sql.js');
    const SQL = await initSqlJs({ locateFile: (f) => path.join(path.dirname(require.resolve('sql.js')), f) });
    this.db = new SQL.Database(fs.readFileSync(file));
    return this.db;
  }
  rows(consoleId) {
    const sid = SYSTEM[consoleId]; if (!sid || !this.db) return [];
    const st = this.db.prepare('select r.romExtensionlessFileName, e.releaseTitleName, e.TEMPregionLocalizedName, e.releaseGenre, e.releaseDate, e.releaseDescription, e.releaseDeveloper, e.releasePublisher from ROMs r left join RELEASES e on e.romID = r.romID where r.systemID = ?');
    st.bind([sid]); const out = [];
    while (st.step()) { const v = st.get(); out.push({ rom: v[0] || '', title: v[1] || '', region: v[2] || '', genre: v[3] || '', date: v[4] || '', desc: v[5] || '', dev: v[6] || '', pub: v[7] || '' }); }
    st.free(); return out;
  }
  index(consoleId) {
    const rows = this.rows(consoleId); const byRom = new Map(), byKey = new Map();
    for (const r of rows) {
      const k1 = r.rom.toLowerCase(); if (!byRom.has(k1)) byRom.set(k1, []); byRom.get(k1).push(r);
      for (const k of new Set([key(r.rom), key(r.title)])) if (k) { if (!byKey.has(k)) byKey.set(k, []); byKey.get(k).push(r); }
    }
    return { rows, byRom, byKey, wl: rows.map((r) => [words(r.title || r.rom), r]) };
  }
  /** Every box art name libretro has for a console (kept two weeks). */
  async artNames(consoleId) {
    if (this.names[consoleId]) return this.names[consoleId];
    const c = catalog.BY_ID[consoleId]; if (!c) return [];
    const f = path.join(this.dir, 'thumbs', consoleId + '.txt');
    if (fs.existsSync(f) && Date.now() - fs.statSync(f).mtimeMs < 14 * 86400000) return (this.names[consoleId] = fs.readFileSync(f, 'utf8').split('\n').filter(Boolean));
    const html = await get('https://thumbnails.libretro.com/' + encodeURIComponent(c.thumbs) + '/Named_Boxarts/');
    let list = [];
    if (html) {
      const dec = (s) => s.replace(/&amp;/g, '&').replace(/&#39;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
      list = [...new Set([...html.matchAll(/<a href="[^"]+">([^<]+)\.png<\/a>/g)].map((m) => dec(m[1]).trim()).filter(Boolean))];
      if (list.length) { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, list.join('\n')); }
    }
    if (!list.length && fs.existsSync(f)) list = fs.readFileSync(f, 'utf8').split('\n').filter(Boolean);
    return (this.names[consoleId] = list);
  }

  // ------------------------------------------------------------------ matching
  pick(c, reg) { return c.slice().sort((a, b) => ((b.desc ? 4 : 0) + (b.region === reg ? 2 : 0) + (b.region === 'USA' ? 1 : 0)) - ((a.desc ? 4 : 0) + (a.region === reg ? 2 : 0) + (a.region === 'USA' ? 1 : 0)))[0]; }
  match(idx, stem) {
    const reg = regionOf(stem);
    let c = idx.byRom.get(stem.toLowerCase()); if (c) return this.pick(c, reg);
    c = idx.byKey.get(key(stem)); if (c) return this.pick(c, reg);
    const w = words(stem.replace(/_/g, ' ')); if (w.size < 2) return null;
    let best = null, bs = 0; for (const [ws, r] of idx.wl) { if (!ws.size) continue; const j = jaccard(w, ws); if (j > bs) { bs = j; best = r; } }
    return bs >= 0.75 ? best : null;
  }
  artMatch(names, stem) {
    if (!names.length) return null;
    const k = key(stem), reg = regionOf(stem);
    const score = (n) => (reg && n.includes('(' + reg + ')') ? 2 : 0) + (n.includes('(USA)') ? 1 : 0) - (/\((Beta|Demo|Proto)/.test(n) ? 3 : 0);
    const same = names.filter((n) => key(n) === k); if (same.length) return same.sort((a, b) => score(b) - score(a))[0];
    const w = words(stem.replace(/_/g, ' ')); if (!w.size) return null;
    let best = null, bs = 0; for (const n of names) { const ws = words(n); if (!ws.size) continue; const j = jaccard(w, ws) + score(n) * 0.01; if (j > bs) { bs = j; best = n; } }
    return bs >= 0.6 ? best : null;
  }
  entry(consoleId, stem, r) {
    const o = {}; if (!r) return o;
    const mame = consoleId === 'MAME';
    o.t = (r.title || r.rom).replace(mame ? /\s*\([^)]*\)\s*$/ : /$^/, '').trim();
    o.c = thumbName(mame ? (r.title || r.rom) : (r.rom || r.title));
    if (r.desc) o.d = r.desc.trim(); if (r.genre) o.g = r.genre.split(',').map((x) => x.trim()).join(', '); if (r.region) o.r = r.region;
    const m = /([A-Za-z]{3})[a-z]*\s*\d*,?\s*(\d{4})/.exec(r.date) || /(\d{4})/.exec(r.date); if (m) o.y = Number(m[2] || m[1]);
    if (r.dev) o.dev = r.dev.trim(); if (r.pub) o.pub = r.pub.trim();
    return o;
  }
  async wiki(title, consoleId) {
    const q = encodeURIComponent(title + ' ' + (PLATFORM[consoleId] || '') + ' video game');
    const txt = await get('https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=' + q + '&gsrlimit=4&prop=extracts&exintro=1&explaintext=1&exsentences=4&exlimit=4');
    if (!txt) return null;
    try {
      const pages = (JSON.parse(txt).query || {}).pages || {}; const want = words(title); let best = null;
      for (const p of Object.values(pages)) {
        const have = words(String(p.title || '').replace(/\s*\([^)]*\)/g, '')); const ex = String(p.extract || '').trim();
        if (!want.size || !have.size || ex.length < 60 || !/game/i.test(ex)) continue;
        if (jaccard(want, have) >= 0.6 && (!best || p.index < best.index)) best = { index: p.index, ex };
      }
      return best ? best.ex : null;
    } catch (_) { return null; }
  }

  /** Looks up every game not looked up yet (in the background); [changed] runs when something new was found. */
  async refresh(games, changed) {
    if (this.running) return; const todo = games.filter((g) => !this.cache[g.id]); if (!todo.length) return;
    this.running = true;
    try {
      await this.database();
      const by = {}; for (const g of todo) (by[g.console] = by[g.console] || []).push(g);
      for (const [consoleId, list] of Object.entries(by)) {
        const idx = this.db ? this.index(consoleId) : null; let art = null; let n = 0;
        for (const g of list) {
          const stem = catalog.stemOf(g.fileName) || g.title;
          const row = idx ? this.match(idx, stem) : null;
          const e = this.entry(consoleId, stem, row);
          if (consoleId !== 'MAME') {
            // The cover name must be one libretro really has: check it against its list.
            if (!art) { art = await this.artNames(consoleId); art.set = new Set(art.map((x) => x.toLowerCase())); }
            if (!e.c || (art.length && !art.set.has(e.c.toLowerCase()))) { const name = this.artMatch(art, stem) || (e.t ? this.artMatch(art, e.t) : null); if (name) { e.c = name; if (!e.t) e.t = catalog.clean(name); } }
          }
          if (!e.d && consoleId !== 'MAME') { const w = await this.wiki(e.t || g.title, consoleId); if (w) e.d = w; }
          this.cache[g.id] = e;
          if (++n % 25 === 0) { this.save(); changed(); }
        }
        this.save(); changed();
      }
    } catch (_) { /* try again next start */ }
    this.running = false;
  }

  // ------------------------------------------------------------------ manual search and fixes
  async search(consoleId, query) {
    const want = words(query); if (!want.size) return [];
    await this.database();
    const out = []; const seen = new Set();
    if (this.db && SYSTEM[consoleId]) for (const r of this.rows(consoleId)) {
      const ws = words(r.title || r.rom); let all = true; for (const x of want) if (!ws.has(x)) { all = false; break; }
      if (!all) continue; const e = this.entry(consoleId, r.rom, r); if (!e.c || seen.has(e.c.toLowerCase())) continue; seen.add(e.c.toLowerCase());
      out.push([want.size / ws.size + (r.desc ? 0.2 : 0), e]);
    }
    for (const n of await this.artNames(consoleId)) {
      const ws = words(n); let all = true; for (const x of want) if (!ws.has(x)) { all = false; break; }
      if (!all || seen.has(n.toLowerCase())) continue; seen.add(n.toLowerCase()); out.push([want.size / ws.size, { t: catalog.clean(n), c: n }]);
    }
    return out.sort((a, b) => b[0] - a[0]).slice(0, 60).map((x) => x[1]);
  }
  async choose(game, pick) {
    const e = Object.assign({}, pick, { manual: true });
    if (!e.d && game.console !== 'MAME') { const w = await this.wiki(e.t || game.title, game.console); if (w) e.d = w; }
    this.cache[game.id] = e; this.save();
  }
  rename(game, title) { const e = this.cache[game.id] || {}; e.t = title; e.manual = true; this.cache[game.id] = e; this.save(); }
  reset(game) { delete this.cache[game.id]; this.save(); }
}

module.exports = { Meta };
