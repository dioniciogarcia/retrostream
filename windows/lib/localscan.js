'use strict';
// Games already on this PC: folders the user picks are searched (with their subfolders) for game files.
// A file that is also in a Telegram channel marks that game as downloaded; the rest show up as "This PC" games.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const catalog = require('./catalog');

// Extensions that belong to one console only.
const ONLY = {
  nes: 'NES', fds: 'NES', unf: 'NES', unif: 'NES', sfc: 'SNES', smc: 'SNES', swc: 'SNES', fig: 'SNES',
  md: 'MD', gen: 'MD', smd: 'MD', gb: 'GB', gbc: 'GBC', gba: 'GBA', n64: 'N64', v64: 'N64', z64: 'N64', nds: 'NDS',
  pbp: 'PSP', gcm: 'GC', ciso: 'GC', wbfs: 'WII', wia: 'WII', nsp: 'SWITCH', xci: 'SWITCH', nsz: 'SWITCH', xcz: 'SWITCH', pkg: 'PS3'
};
// Extensions several consoles share: the folder names decide (e.g. "...\PS2\game.iso").
const SHARED = new Set(['iso', 'chd', 'cso', 'zso', 'cue', 'rvz', 'gcz', 'zip', '7z', 'bin']);
const HINTS = [
  ['PS3', /\b(ps3|playstation ?3|rpcs3)\b/], ['PS2', /\b(ps2|playstation ?2|pcsx2)\b/], ['PSP', /\b(psp|playstation portable|ppsspp)\b/],
  ['WII', /\b(wii)\b/], ['GC', /\b(gc|ngc|gamecube|game cube)\b/], ['SWITCH', /\b(switch|nsw|yuzu|eden|ryujinx)\b/],
  ['GBA', /\b(gba|game ?boy advance)\b/], ['GBC', /\b(gbc|game ?boy colou?r)\b/], ['GB', /\b(gb|game ?boy)\b/],
  ['NDS', /\b(nds|ds|nintendo ds)\b/], ['N64', /\b(n64|nintendo 64)\b/], ['SNES', /\b(snes|super nintendo|super famicom|sfc)\b/],
  ['NES', /\b(nes|famicom)\b/], ['MD', /\b(genesis|mega ?drive|megadrive|md|sega)\b/], ['MAME', /\b(mame|arcade|fbneo|neo ?geo)\b/]
];
const SKIP_DIRS = /^(\$recycle\.bin|system volume information|windows|program files( \(x86\))?|programdata|appdata|node_modules|\.git)$/i;

function hintFor(dir) {
  const parts = dir.toLowerCase().replace(/[_\-.]+/g, ' ').split(/[\\/]/).reverse();
  for (const p of parts) for (const [id, re] of HINTS) if (re.test(p)) return id;
  return null;
}
function norm(s) { return String(s || '').toLowerCase().replace(/[^a-z0-9]/g, ''); }

class LocalGames {
  constructor({ dir, store }) {
    Object.assign(this, { dir, store });
    this.file = path.join(dir, 'local-games.json');
    this.data = { games: [], unsorted: [], scannedAt: 0 };
    try { if (fs.existsSync(this.file)) Object.assign(this.data, JSON.parse(fs.readFileSync(this.file, 'utf8'))); } catch (_) { /* start fresh */ }
    this.busy = false;
  }
  save() { try { fs.writeFileSync(this.file, JSON.stringify(this.data)); } catch (_) { /* best effort */ } }
  folders() { return this.store.get('scanFolders') || []; }
  setFolders(list) { this.store.set('scanFolders', [...new Set(list)]); }
  /** Consoles the user chose for files the scan could not place. */
  assigned() { return this.store.get('localAssign') || {}; }
  games() { return this.data.games.filter((g) => fs.existsSync(g.path)); }
  links() { return this.store.get('pcLinked') || {}; }

  /** Walks every chosen folder. [telegramGames] are matched by file name; returns a summary. */
  async scan(telegramGames, progress) {
    if (this.busy) return null; this.busy = true;
    try {
      const own = path.resolve(this.dir).toLowerCase();
      const files = []; let seen = 0;
      const walk = async (d, depth) => {
        if (depth > 14 || files.length > 60000) return;
        if (path.resolve(d).toLowerCase().startsWith(own)) return;   // never RetroStream's own downloads
        let list; try { list = await fs.promises.readdir(d, { withFileTypes: true }); } catch (_) { return; }
        // A PS3 game kept as a folder (PS3_GAME inside).
        if (list.some((f) => f.isDirectory() && f.name.toUpperCase() === 'PS3_GAME')) {
          const eboot = path.join(d, 'PS3_GAME', 'USRDIR', 'EBOOT.BIN');
          files.push({ path: fs.existsSync(eboot) ? eboot : d, name: path.basename(d), ext: 'ps3dir', dir: path.dirname(d), size: 0, console: 'PS3' });
          return;
        }
        const cues = list.filter((f) => f.isFile() && /\.cue$/i.test(f.name));
        const tracks = new Set();
        for (const c of cues) {
          try { for (const m of fs.readFileSync(path.join(d, c.name), 'utf8').matchAll(/FILE\s+"?([^"\r\n]+?)"?\s+(BINARY|MOTOROLA|WAVE|MP3)/gi)) tracks.add(m[1].toLowerCase()); } catch (_) { /* unreadable */ }
        }
        for (const f of list) {
          const p = path.join(d, f.name);
          if (f.isDirectory()) { if (!SKIP_DIRS.test(f.name) && !f.name.startsWith('.')) await walk(p, depth + 1); continue; }
          if (!f.isFile()) continue;
          if (++seen % 2000 === 0 && progress) progress(seen);
          if (tracks.has(f.name.toLowerCase())) continue;   // the .bin tracks of a .cue
          let ext = path.extname(f.name).substring(1).toLowerCase();
          if (/\.(7z|zip)\.0*1$/i.test(f.name)) ext = 'part1';
          else if (/\.(7z|zip)\.\d{2,3}$/i.test(f.name)) continue;
          if (!ONLY[ext] && !SHARED.has(ext) && ext !== 'part1') continue;
          let size = 0; try { size = (await fs.promises.stat(p)).size; } catch (_) { continue; }
          if (size < 1024 && ext !== 'cue') continue;
          files.push({ path: p, name: f.name, ext, dir: d, size });
        }
      };
      for (const root of this.folders()) await walk(root, 0);

      // Telegram games by console + name, to mark them as downloaded.
      const byName = new Map();
      for (const g of telegramGames) {
        const keys = [norm(g.fileName), norm(catalog.stemOf(g.fileName))];
        for (const k of keys) if (k && !byName.has(g.console + '|' + k)) byName.set(g.console + '|' + k, g);
      }
      const assigned = this.assigned();
      const links = {}; const games = []; const unsorted = [];
      for (const f of files) {
        let con = f.console || assigned[f.path] || ONLY[f.ext] || null;
        if (!con) {
          const hint = hintFor(f.dir);
          if (hint && (f.ext === 'part1' ? (hint === 'PS3' || hint === 'SWITCH') : f.ext === 'bin' ? hint === 'MD' : true)) con = hint;
        }
        if (con === 'GC' && f.ext === 'rvz' && hintFor(f.dir) === 'WII') con = 'WII';
        if (!con) { if (f.ext !== 'bin') unsorted.push({ path: f.path, name: f.name, size: f.size }); continue; }
        if (con !== 'MAME' && f.ext !== 'ps3dir' && f.ext !== 'part1' && !catalog.supported(con, f.name)) continue;
        if (con === 'MAME' && f.ext !== 'zip' && f.ext !== '7z') continue;
        const stem = f.ext === 'ps3dir' ? f.name : catalog.stemOf(f.name);
        const match = byName.get(con + '|' + norm(f.name)) || byName.get(con + '|' + norm(stem));
        if (match) { links[match.id] = f.path; continue; }
        games.push({
          id: 'pc:' + crypto.createHash('sha1').update(f.path.toLowerCase()).digest('hex').substring(0, 16),
          title: catalog.clean(stem), console: con, fileName: f.ext === 'ps3dir' ? f.name + '.iso' : f.name, path: f.path, size: f.size,
          local: true, messageId: 0, fileId: 0, date: 0
        });
      }
      this.store.set('pcLinked', links);
      this.data = { games, unsorted: unsorted.slice(0, 2000), scannedAt: Date.now() };
      this.save();
      return { files: files.length, linked: Object.keys(links).length, local: games.length, unsorted: unsorted.length };
    } finally { this.busy = false; }
  }
  assign(file, consoleId) { const a = this.assigned(); if (consoleId) a[file] = consoleId; else delete a[file]; this.store.set('localAssign', a); }
  unsorted() { return this.data.unsorted || []; }
}

module.exports = { LocalGames };
