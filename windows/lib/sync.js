'use strict';
// Library sync: favorites, collections, hidden and completed games, "Play later", the played history and play time are
// the same on every device of the same Telegram account (phone, TV, PC). They travel as one small JSON file in a private
// channel, "TD - RetroStream - Library sync" (same format as the Android app):
//   {rs:"sync", v:1, device, items:{ key: {v: value or null, t: time changed} }}
// Keys: f:<game> favorite · h:<game> hidden · c:<game> completed · l:<game> play later · col:<name> collection (ids)
//       pl:<time>:<game> one play · ur:<game> / ur:* removed from recents at · pt:<device>:<game file name> play time on that device
// The newest change wins; plays and play time only add up.
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

const SYNC_TITLE = 'TD - RetroStream - Library sync';
const TAG = '#RSSYNC';
const KEEP_PLAYS = 300;
const TOMB_MS = 60 * 86400000;

const ptName = (id) => String(id).replace(/[^A-Za-z0-9._-]/g, '_');
const same = (a, b) => JSON.stringify(a === undefined ? null : a) === JSON.stringify(b === undefined ? null : b);
const numeric = (k) => k.startsWith('ur:') || k.startsWith('pt:');
const tomb = (k) => /^(f|h|c|l|col):/.test(k);

class LibrarySync {
  constructor({ dir, drive, store, onChange }) {
    Object.assign(this, { dir, drive, store, onChange });
    this.busy = false; this.again = false; this.timer = null;
    drive.on('td', (u) => {
      if (u._ !== 'updateNewMessage') return;
      const m = u.message; const chat = (this.store.get('syncChat') || {})[this.drive.account];
      if (!m || !chat || m.chat_id !== chat) return;
      const d = this.parse(m); if (d && d.device !== this.device()) this.soon(1500);
    });
    setInterval(() => this.soon(500), 180000);
  }
  device() {
    let id = this.store.get('syncDevice');
    if (!id) { id = 'pc' + crypto.randomBytes(5).toString('hex'); this.store.set('syncDevice', id); }
    return id;
  }
  soon(ms) { clearTimeout(this.timer); this.timer = setTimeout(() => this.cycle().catch(() => { this.busy = false; }), ms); }
  stateFile() { return path.join(this.dir, 'sync', 'state_' + this.drive.account + '.json'); }
  loadState() { try { return JSON.parse(fs.readFileSync(this.stateFile(), 'utf8')); } catch (_) { return {}; } }
  saveState(st) {
    try { fs.mkdirSync(path.dirname(this.stateFile()), { recursive: true }); const tmp = this.stateFile() + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(st)); fs.renameSync(tmp, this.stateFile()); } catch (_) { /* next time */ }
  }
  parse(m) {
    if (!m || !m.content || m.content._ !== 'messageDocument') return null;
    const c = m.content; const cap = c.caption && c.caption.text || '';
    if (!cap.startsWith(TAG) || !c.document || !c.document.document) return null;
    const dev = (cap.split('\n').find((l) => l.startsWith('device:')) || '').substring(7).trim();
    return { messageId: m.id, fileId: c.document.document.id, device: dev };
  }
  async channel() {
    const all = this.store.get('syncChat') || {}; const acc = this.drive.account;
    if (all[acc] && this.drive.chats.has(all[acc])) return all[acc];
    const id = await this.drive.ownChannel(SYNC_TITLE, 'RetroStream · favoritos, colecciones e historial de todos tus dispositivos. No borres este canal.', true);
    if (id) { all[acc] = id; this.store.set('syncChat', all); }
    return id;
  }
  async docs(chat) {
    const seen = new Set(); const out = [];
    const add = (list) => { for (const m of list) { const d = this.parse(m); if (d && !seen.has(d.messageId)) { seen.add(d.messageId); out.push(d); } } };
    try { add(await this.drive.history(chat, 1)); } catch (_) { /* search below */ }
    try { add(await this.drive.search(chat, 'Document', 30)); } catch (_) { /* what we have */ }
    return out.sort((a, b) => b.messageId - a.messageId);
  }

  /** This PC's library as sync items. */
  snapshot() {
    const o = {}; const g = (k) => this.store.get(k);
    for (const [pref, pre] of [['favorites', 'f:'], ['hidden', 'h:'], ['completed', 'c:'], ['later', 'l:']]) for (const id of g(pref) || []) if (id) o[pre + id] = true;
    const cols = g('collections') || {};
    for (const n of Object.keys(cols)) o['col:' + n] = [...new Set((cols[n] || []).filter(Boolean))].sort();
    for (const p of g('plays') || []) if (p && p.id && p.at) o['pl:' + p.at + ':' + p.id] = true;
    const ur = g('unrecent') || {};
    for (const k of Object.keys(ur)) if (ur[k] > 0) o['ur:' + k] = ur[k];
    const pt = g('playtime') || {}; const me = this.device();
    for (const id of Object.keys(pt)) if (pt[id] >= 60000) o['pt:' + me + ':' + ptName(id)] = Math.round(pt[id]);
    return o;
  }
  /** Writes the merged items into this PC's settings and tells the screens. */
  materialize(items) {
    const me = this.device(); const sets = { 'f:': [], 'h:': [], 'c:': [], 'l:': [] };
    const cols = {}; const plays = []; const ur = Object.assign({}, this.store.get('unrecent') || {}); const others = {};
    for (const k of Object.keys(items)) {
      const v = items[k].v; if (v === null || v === undefined) continue;
      if (k.startsWith('col:')) cols[k.substring(4)] = Array.isArray(v) ? v : [];
      else if (k.startsWith('pl:')) { const rest = k.substring(3); const c = rest.indexOf(':'); const at = Number(rest.substring(0, c)); if (c > 0 && at) plays.push({ id: rest.substring(c + 1), at }); }
      else if (k.startsWith('ur:')) { const id = k.substring(3); if (v > (ur[id] || 0)) ur[id] = v; }
      else if (k.startsWith('pt:')) { const rest = k.substring(3); const c = rest.indexOf(':'); if (c > 0 && rest.substring(0, c) !== me) { const n = rest.substring(c + 1); others[n] = (others[n] || 0) + (Number(v) || 0); } }
      else if (sets[k.substring(0, 2)]) sets[k.substring(0, 2)].push(k.substring(2));
    }
    const current = this.store.get('collections') || {}; const outCols = {};
    for (const n of Object.keys(current)) if (cols[n]) outCols[n] = cols[n];
    for (const n of Object.keys(cols)) if (!outCols[n]) outCols[n] = cols[n];
    plays.sort((a, b) => b.at - a.at);
    const p = { favorites: sets['f:'], hidden: sets['h:'], completed: sets['c:'], later: sets['l:'], collections: outCols, plays: plays.slice(0, KEEP_PLAYS), unrecent: ur, playtimeOthers: others };
    for (const k of Object.keys(p)) this.store.set(k, p[k]);
    if (this.onChange) this.onChange(p);
  }

  async cycle() {
    if (this.drive.stage !== 'ready' || !this.drive.account) return;
    if (this.busy) { this.again = true; return; }
    this.busy = true;
    try { await this.run(); } finally {
      this.busy = false;
      if (this.again) { this.again = false; this.soon(1000); }
    }
  }
  async run() {
    const account = this.drive.account;
    const chat = await this.channel(); if (!chat || account !== this.drive.account) return;
    const docs = await this.docs(chat);
    const st = this.loadState();
    const newest = docs[0];
    let remote = null;
    if (newest && newest.messageId > (st.seen || 0)) {
      const file = await this.drive.downloadNow(newest.fileId); if (!file) return;
      try { remote = JSON.parse(fs.readFileSync(file, 'utf8')).items || null; } catch (_) { remote = null; }
      if (!remote) return;
    }
    const items = st.items || {}; const first = !st.synced; const now = Date.now();
    let dirty = !!st.dirty;
    // 1. What changed here since the last sync.
    const local = this.snapshot();
    for (const k of Object.keys(local)) {
      const v = local[k]; const s = items[k];
      if (s && same(s.v, v)) continue;
      if (numeric(k) && s && (s.v || 0) >= v) continue;
      items[k] = { v, t: first ? 1 : now }; dirty = true;
    }
    for (const k of Object.keys(items)) if (tomb(k) && !(k in local) && items[k].v !== null) { items[k] = { v: null, t: now }; dirty = true; }
    // 2. What changed on the other devices.
    let changed = false;
    if (remote) {
      for (const k of Object.keys(remote)) {
        const r = remote[k]; const s = items[k]; if (!r) continue;
        if (numeric(k)) { if (!s || (r.v || 0) > (s.v || 0)) { items[k] = r; changed = true; } continue; }
        if (!s || (r.t || 0) > (s.t || 0)) { if (!s || !same(s.v, r.v)) changed = true; items[k] = r; }
      }
      st.seen = newest.messageId;
    }
    // 3. Old removals and very old plays are dropped.
    const plays = Object.keys(items).filter((k) => k.startsWith('pl:')).sort((a, b) => Number(b.substring(3).split(':')[0]) - Number(a.substring(3).split(':')[0]));
    for (const k of plays.slice(KEEP_PLAYS)) delete items[k];
    for (const k of Object.keys(items)) if (items[k].v === null && now - (items[k].t || 0) > TOMB_MS) delete items[k];
    if (remote) dirty = !this.sameItems(items, remote);
    else if (!docs.length && Object.keys(items).length) dirty = true;
    // 4. This PC now shows the merged library.
    if (changed || first) this.materialize(items);
    Object.assign(st, { items, synced: true, dirty }); this.saveState(st);
    if (!dirty) return;
    // 5. Send the merged file; older copies are removed (the newest two are kept).
    const file = path.join(os.tmpdir(), 'retrostream-sync-' + now + '.json');
    fs.writeFileSync(file, JSON.stringify({ rs: 'sync', v: 1, device: this.device(), items }));
    try {
      const m = await this.drive.sendAndWait(chat, { _: 'inputMessageDocument', document: { _: 'inputDocument', document: { _: 'inputFileLocal', path: file }, disable_content_type_detection: true }, caption: { _: 'formattedText', text: TAG + '\ndevice:' + this.device(), entities: [] } });
      st.seen = m.id; st.dirty = false; this.saveState(st);
      const old = docs.slice(1).map((d) => d.messageId).filter((id) => id !== m.id);
      if (old.length) this.drive.invoke({ _: 'deleteMessages', chat_id: chat, message_ids: old, revoke: true }).catch(() => {});
    } finally { try { fs.unlinkSync(file); } catch (_) { /* ok */ } }
  }
  sameItems(a, b) {
    const ka = Object.keys(a); if (ka.length !== Object.keys(b).length) return false;
    for (const k of ka) { const y = b[k]; if (!y || !same(a[k].v, y.v) || (a[k].t || 0) !== (y.t || 0)) return false; }
    return true;
  }
}

module.exports = { LibrarySync, SYNC_TITLE };
