'use strict';
// Save backups and screenshots in the user's own Telegram channels (same format as the Android app),
// and cheat lists from the libretro database.
const fs = require('fs');
const path = require('path');
const https = require('https');
const { execFile } = require('child_process');
const catalog = require('./catalog');

const BACKUP_TITLE = 'TD - RetroStream - Save file back-ups';
const SHOTS_TITLE = 'TD - RetroStream - Screenshots';
const KEEP = 10;
const SKIP = new Set(['Cache', 'ShaderCache', 'Logs', 'Dump', 'ScreenShots', 'Load', 'ResourcePacks',
  // PS2: only the memory cards are saves; the rest is shared files (the BIOS) and caches.
  'resources', 'bios', 'inis', 'cache', 'covers', 'logs', 'snaps', 'textures', 'videos', 'gamesettings', 'sstates', 'patches', 'cheats', 'inputprofiles', 'upscale']);

function seven() { return require('7zip-bin').path7za.replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep); }
function run7(args, cwd) { return new Promise((res, rej) => execFile(seven(), args, { cwd, windowsHide: true }, (e) => (e ? rej(e) : res()))); }
function walk(dir, cb) {
  if (!fs.existsSync(dir)) return;
  for (const f of fs.readdirSync(dir, { withFileTypes: true })) { const p = path.join(dir, f.name); if (f.isDirectory()) { if (!SKIP.has(f.name)) walk(p, cb); } else cb(p); }
}

class Cloud {
  constructor({ dir, drive, store, t }) { Object.assign(this, { dir, drive, store, t }); this.busy = false; }

  // ------------------------------------------------------------------ saves on this PC
  entries() {
    const out = []; const root = path.join(this.dir, 'saves');
    if (!fs.existsSync(root)) return out;
    for (const key of fs.readdirSync(root)) for (const sha of fs.readdirSync(path.join(root, key))) {
      const r = path.join(root, key, sha);
      try { const m = JSON.parse(fs.readFileSync(path.join(r, 'meta.json'), 'utf8')); out.push({ root: r, sha, title: m.title, console: m.console }); } catch (_) { /* no meta */ }
    }
    return out;
  }
  resumable(e) { return e.console !== 'NDS'; }
  lastChange(e, withAuto) {
    let last = 0;
    for (const d of ['saves', 'states']) walk(path.join(e.root, d), (p) => { if (!withAuto && p.endsWith(path.sep + 'auto.state')) return; last = Math.max(last, fs.statSync(p).mtimeMs); });
    return last;
  }
  marks() { return this.store.get('backupMarks') || {}; }
  mark(sha, data) { const m = this.marks(); m[sha] = Object.assign(m[sha] || {}, data); this.store.set('backupMarks', m); }
  changed(e) { const last = this.lastChange(e, this.resumable(e)); return last > 0 && last > ((this.marks()[e.sha] || {}).done || 0); }

  // ------------------------------------------------------------------ backups in Telegram
  async channel(create) { return this.drive.ownChannel(BACKUP_TITLE, 'RetroStream · copias automáticas de partidas guardadas', create); }
  parse(m) {
    const c = m.content; if (!c || c._ !== 'messageDocument' || !c.caption || !c.caption.text.startsWith('#RSSAVE')) return null;
    const b = { messageId: m.id, date: m.date, fileId: c.document.document.id, size: c.document.document.size, sha: '', title: '', console: '' };
    for (const line of c.caption.text.split('\n')) { const i = line.indexOf(':'); if (i < 0) continue; const k = line.substring(0, i), v = line.substring(i + 1).trim(); if (k in b) b[k] = v; }
    return b.sha && b.title ? b : null;
  }
  async list(sha) {
    const chat = await this.channel(false); if (!chat) return [];
    const all = (await this.drive.documents(chat)).map((m) => this.parse(m)).filter(Boolean).sort((a, b) => b.date - a.date);
    return sha ? all.filter((b) => b.sha === sha) : all;
  }
  latestPerGame(all) { const by = new Map(); for (const b of all) if (!by.has(b.sha)) by.set(b.sha, b); return [...by.values()].sort((a, b) => a.title.localeCompare(b.title)); }

  async zip(e) {
    const tmp = path.join(this.dir, 'backup-tmp'); fs.mkdirSync(tmp, { recursive: true });
    const safe = e.title.replace(/[^A-Za-z0-9 ._()-]/g, '_').substring(0, 80);
    const stamp = new Date().toISOString().replace(/[:T]/g, '-').substring(0, 16);
    const out = path.join(tmp, `${safe} (${e.console}) - saves ${stamp}.zip`);
    // The two marker files the Android app expects, next to saves/ and states/ while zipping.
    fs.writeFileSync(path.join(e.root, 'tirso-saves.json'), JSON.stringify({ format: 1, package: 'com.tirso.retrostream.windows', title: e.title, console: e.console, sha: e.sha, created: new Date().toISOString() }, null, 1));
    fs.writeFileSync(path.join(e.root, 'tirso-saves.txt'), 'Tirso game save backup\n');
    const args = ['a', '-tzip', '-y', out, 'tirso-saves.json', 'tirso-saves.txt'];
    for (const d of ['saves', 'states']) if (fs.existsSync(path.join(e.root, d))) args.push(d);
    if (!this.resumable(e)) args.push('-x!states' + path.sep + 'auto.state');
    for (const s of SKIP) args.push('-xr!' + s);
    args.push('-xr!*.tmp');
    try { await run7(args, e.root); } finally { for (const f of ['tirso-saves.json', 'tirso-saves.txt']) try { fs.unlinkSync(path.join(e.root, f)); } catch (_) { /* ok */ } }
    return out;
  }
  /** Zips one game's saves and sends the zip to the backups channel. */
  async upload(e, prune) {
    const stamp = this.lastChange(e, this.resumable(e));
    if (!stamp) return this.t('This game has no saves yet.');
    const chat = await this.channel(true);
    const zip = await this.zip(e);
    try {
      const caption = `#RSSAVE\nsha:${e.sha}\ntitle:${e.title}\nconsole:${e.console}\nv:1`;
      const m = await this.drive.sendAndWait(chat, { _: 'inputMessageDocument', document: { _: 'inputDocument', document: { _: 'inputFileLocal', path: zip }, disable_content_type_detection: true }, caption: { _: 'formattedText', text: caption, entities: [] } });
      this.mark(e.sha, { done: stamp, seen: m.id });
    } finally { try { fs.unlinkSync(zip); } catch (_) { /* ok */ } }
    if (prune) this.prune().catch(() => {});
    return '';
  }
  async uploadAll(progress) {
    const todo = this.entries().filter((e) => this.changed(e));
    let ok = 0; let failed = '';
    for (let i = 0; i < todo.length; i++) {
      progress && progress(`${i + 1}/${todo.length} · ${todo[i].title}`);
      try { const err = await this.upload(todo[i], false); if (err) failed = err; else ok++; } catch (e) { failed = String(e.message || e); }
    }
    this.prune().catch(() => {});
    return { ok, total: todo.length, failed };
  }
  async prune() {
    const chat = await this.channel(false); if (!chat) return;
    const all = await this.list(); const count = {}; const old = [];
    for (const b of all) { count[b.sha] = (count[b.sha] || 0) + 1; if (count[b.sha] > KEEP) old.push(b.messageId); }
    if (old.length) await this.drive.invoke({ _: 'deleteMessages', chat_id: chat, message_ids: old, revoke: true });
  }
  rootFor(consoleId, sha) { return path.join(this.dir, 'saves', consoleId === 'GB' || consoleId === 'GBC' ? 'gb' : consoleId.toLowerCase(), sha); }
  /** A zip of any save folder (Friend assist), same format as the backups. */
  async zipRoot(root, title, consoleId, sha) { return this.zip({ root, title, console: consoleId, sha: sha || '' }); }
  /** Puts the saves from a backup-format zip into a save folder (Friend assist). */
  async extractTo(file, root) {
    const tmp = path.join(this.dir, 'restore-tmp', String(Date.now()));
    try {
      await run7(['x', '-y', '-o' + tmp, file]);
      if (!fs.existsSync(path.join(tmp, 'tirso-saves.txt'))) throw new Error(this.t('That file is not a valid save backup.'));
      fs.mkdirSync(root, { recursive: true });
      for (const d of ['saves', 'states']) if (fs.existsSync(path.join(tmp, d))) fs.cpSync(path.join(tmp, d), path.join(root, d), { recursive: true, force: true });
    } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
  }
  /** Downloads a backup and puts its saves back where the game keeps them. */
  async restore(b) {
    const file = await this.drive.downloadNow(b.fileId);
    if (!file) throw new Error(this.t('The backup could not be downloaded.'));
    const tmp = path.join(this.dir, 'restore-tmp', String(Date.now()));
    try {
      await run7(['x', '-y', '-o' + tmp, file]);
      if (!fs.existsSync(path.join(tmp, 'tirso-saves.txt'))) throw new Error(this.t('That file is not a valid save backup.'));
      const root = this.rootFor(b.console, b.sha);
      for (const d of ['saves', 'states']) if (fs.existsSync(path.join(tmp, d))) fs.cpSync(path.join(tmp, d), path.join(root, d), { recursive: true, force: true });
      fs.writeFileSync(path.join(root, 'meta.json'), JSON.stringify({ title: b.title, console: b.console }));
      const e = { root, sha: b.sha, title: b.title, console: b.console };
      this.mark(b.sha, { seen: b.messageId, done: this.lastChange(e, this.resumable(e)) });
    } finally { fs.rmSync(tmp, { recursive: true, force: true }); this.drive.invoke({ _: 'deleteFile', file_id: b.fileId }).catch(() => {}); }
  }
  /** Before a game starts: restores a newer backup made on the phone or TV. Returns a message when it did. */
  async pullNewest(e) {
    const b = (await this.list(e.sha))[0];
    if (!b || b.messageId === (this.marks()[e.sha] || {}).seen) return '';
    if (this.changed(e) && this.lastChange(e, this.resumable(e)) > b.date * 1000) return '';   // newer progress here, not backed up yet
    await this.restore(b);
    return this.t('Loaded your newest save from Telegram');
  }

  // ------------------------------------------------------------------ screenshots
  async uploadShots() {
    const q = path.join(this.dir, 'screenshots', 'pending');
    if (this.busy) { this.again = true; return; }   // a screenshot taken while sending goes right after
    if (this.drive.stage !== 'ready' || !fs.existsSync(q)) return;
    const items = fs.readdirSync(q).filter((f) => f.endsWith('.json')).sort(); if (!items.length) return;
    this.busy = true;
    try {
      const chat = await this.drive.ownChannel(SHOTS_TITLE, 'RetroStream · capturas de pantalla de tus juegos', true);
      for (const f of items) {
        const meta = path.join(q, f), pic = meta.replace(/\.json$/, '.jpg');
        let j = null; try { j = JSON.parse(fs.readFileSync(meta, 'utf8')); } catch (_) { /* bad */ }
        if (!j || !fs.existsSync(pic)) { try { fs.unlinkSync(meta); fs.unlinkSync(pic); } catch (_) { /* ok */ } continue; }
        const caption = `#RSSHOT\nid:${j.id}\ntitle:${j.title}\nconsole:${j.console}\nv:1`;
        await this.drive.sendAndWait(chat, { _: 'inputMessagePhoto', photo: { _: 'inputPhoto', photo: { _: 'inputFileLocal', path: pic }, added_sticker_file_ids: [], width: j.w || 0, height: j.h || 0 }, caption: { _: 'formattedText', text: caption, entities: [] }, has_spoiler: false });
        try { fs.unlinkSync(meta); fs.unlinkSync(pic); } catch (_) { /* ok */ }
      }
    } catch (_) { /* try again next time */ }
    this.busy = false;
    if (this.again) { this.again = false; return this.uploadShots(); }
  }

  /** Media page: every screenshot (phone, TV and PC), newest first; small pictures for [from, from + count). */
  async allShots(from, count) {
    const chat = await this.drive.ownChannel(SHOTS_TITLE, '', false); if (!chat) return { list: [], total: 0 };
    if (!this.shotIndex || from === 0) {
      this.shotIndex = [];
      for (const m of await this.drive.search(chat, 'Photo', 3000)) {
        const c = m.content; if (!c || c._ !== 'messagePhoto' || !c.caption || !c.caption.text.startsWith('#RSSHOT')) continue;
        const info = {}; for (const line of c.caption.text.split('\n')) { const i = line.indexOf(':'); if (i > 0) info[line.substring(0, i)] = line.substring(i + 1).trim(); }
        const sizes = c.photo.sizes || []; if (!sizes.length) continue;
        const small = sizes.find((z) => z.width >= 320) || sizes[0]; const big = sizes[sizes.length - 1];
        this.shotIndex.push({ id: info.id || '', title: info.title || '', console: info.console || '', date: m.date, thumb: small.photo.id, full: big.photo.id, w: big.width, h: big.height });
      }
    }
    const page = this.shotIndex.slice(from, from + count);
    const list = [];
    for (const s of page) {
      let url = '';
      try { const p = await this.drive.downloadNow(s.thumb); if (p) url = 'data:image/jpeg;base64,' + fs.readFileSync(p).toString('base64'); } catch (_) { /* no picture */ }
      list.push(Object.assign({}, s, { url }));
    }
    return { list, total: this.shotIndex.length };
  }
  async fullShot(fileId) {
    const p = await this.drive.downloadNow(fileId); if (!p) throw new Error(this.t('The picture could not be downloaded.'));
    return { path: p, url: 'data:image/jpeg;base64,' + fs.readFileSync(p).toString('base64') };
  }

  /** This game's screenshots in the Screenshots channel, newest first, as pictures the page can show. */
  async shots(gameId) {
    const chat = await this.drive.ownChannel(SHOTS_TITLE, '', false); if (!chat) return [];
    const out = [];
    for (const m of await this.drive.search(chat, 'Photo', 1500)) {
      const c = m.content; if (!c || c._ !== 'messagePhoto' || !c.caption) continue;
      const text = c.caption.text; if (!text.startsWith('#RSSHOT') || !text.split('\n').includes('id:' + gameId)) continue;
      const sizes = c.photo.sizes || []; const big = sizes[sizes.length - 1]; if (!big) continue;
      try {
        const p = await this.drive.downloadNow(big.photo.id);
        if (p) out.push({ date: m.date, url: 'data:image/jpeg;base64,' + fs.readFileSync(p).toString('base64'), path: p, w: big.width, h: big.height, chatId: m.chat_id, messageId: m.id });
      } catch (_) { /* skip */ }
      if (out.length >= 30) break;
    }
    return out;
  }

  // ------------------------------------------------------------------ cheats (libretro database), downloaded once per game
  http(url) {
    return new Promise((resolve) => {
      const req = https.get(url, { headers: { 'User-Agent': 'RetroStream' }, timeout: 12000 }, (res) => {
        if (res.statusCode !== 200) { res.resume(); resolve(null); return; }
        let data = ''; res.setEncoding('utf8'); res.on('data', (d) => { data += d; }); res.on('end', () => resolve(data));
      });
      req.on('error', () => resolve(null)); req.on('timeout', () => { req.destroy(); resolve(null); });
    });
  }
  async cheats(game) {
    const c = catalog.BY_ID[game.console]; if (!c || game.console === 'MAME') return null;
    const dir = path.join(this.dir, 'cheats', 'db'); fs.mkdirSync(dir, { recursive: true });
    const key = game.id.replace(/[^A-Za-z0-9._-]/g, '_');
    const file = path.join(dir, key + '.cht'), miss = path.join(dir, key + '.none2');
    if (fs.existsSync(file)) return file;
    if (fs.existsSync(miss) && Date.now() - fs.statSync(miss).mtimeMs < 7 * 86400000) return null;
    const norm = (n) => n.split(' (')[0].toLowerCase().replace(/[^a-z0-9]/g, '');
    const region = (n) => { const m = /\(([^)]*)\)/.exec(n); const tag = m ? m[1] : ''; return tag.includes('USA') ? 'usa' : tag.includes('Europe') ? 'eur' : tag.includes('World') ? 'world' : tag.includes('Japan') ? 'jpn' : tag ? tag.toLowerCase() : ''; };
    const idx = path.join(dir, 'index-' + norm(c.thumbs) + '.txt');
    let names = [];
    if (fs.existsSync(idx) && Date.now() - fs.statSync(idx).mtimeMs < 30 * 86400000) names = fs.readFileSync(idx, 'utf8').split('\n').filter(Boolean);
    else {
      const json = await this.http('https://api.github.com/repos/libretro/libretro-database/git/trees/master:cht%2F' + encodeURIComponent(c.thumbs));
      if (json) { try { names = JSON.parse(json).tree.map((x) => x.path).filter((p) => p.endsWith('.cht')); fs.writeFileSync(idx, names.join('\n')); } catch (_) { /* bad answer */ } }
      else if (fs.existsSync(idx)) names = fs.readFileSync(idx, 'utf8').split('\n').filter(Boolean);
    }
    if (!names.length) return null;
    // File names like "Beyblade_V_Force_Ultimate_Blader_Jam_Europe_En,Fr,De" or "Zelda (USA) (Rev 1)": try the cleaned file name, the game's title and its database name.
    const loose = (n) => norm(String(n || '').replace(/_/g, ' ').replace(/\b(usa|europe|japan|world|en|fr|de|es|it|nl|sv|rev ?\d+|v\d+(\.\d+)?)\b/gi, ' '));
    const title = catalog.stemOf(game.fileName) || game.title;
    const wants = [...new Set([norm(title), norm(game.title || ''), norm(game.base || ''), loose(title), loose(game.title)].filter((w) => w.length > 2))];
    const key2 = (n) => norm(n.replace(/\.cht$/, ''));
    let same = names.filter((n) => wants.includes(key2(n)));
    if (!same.length) same = names.filter((n) => { const k = key2(n); return wants.some((w) => w.length > 6 && (k.startsWith(w) || w.startsWith(k)) && Math.min(k.length, w.length) / Math.max(k.length, w.length) > 0.8); });
    if (!same.length) { fs.writeFileSync(miss, title); return null; }
    const groups = {}; for (const n of same) (groups[region(n.replace(/\.cht$/, ''))] = groups[region(n.replace(/\.cht$/, ''))] || []).push(n);
    const pick = groups[region(title)] || groups.usa || groups.world || groups.eur || groups[''] || Object.values(groups)[0];
    let out = ''; let n = 0;
    for (const name of pick.sort((a, b) => (a.match(/\(/g) || []).length - (b.match(/\(/g) || []).length).slice(0, 4)) {
      const text = await this.http('https://raw.githubusercontent.com/libretro/libretro-database/master/cht/' + encodeURIComponent(c.thumbs) + '/' + encodeURIComponent(name));
      if (!text) continue;
      const dev = /\((Game Genie|GameShark|Game Shark|Action Replay|Code Breaker|Rumbles|Pro Action Replay)\)/.exec(name);
      const fields = {};
      for (const line of text.split(/\r?\n/)) { const m = /^\s*cheat(\d+)_(desc|code)\s*=\s*"?(.*?)"?\s*$/.exec(line); if (m) (fields[m[1]] = fields[m[1]] || {})[m[2]] = m[3]; }
      for (const k of Object.keys(fields).sort((a, b) => a - b)) { const f = fields[k]; if (!f.desc || !f.code) continue;
        out += `cheat${n}_desc = "${dev ? f.desc + ' [' + dev[1] + ']' : f.desc}"\ncheat${n}_code = "${f.code}"\n`; n++; }
    }
    if (!n) { fs.writeFileSync(miss, title); return null; }
    fs.writeFileSync(file, out); try { fs.unlinkSync(miss); } catch (_) { /* ok */ }
    return file;
  }
}

module.exports = { Cloud };
