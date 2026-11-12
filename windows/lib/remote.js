'use strict';
// Phone remote: the RetroStream phone app finds this PC on the Wi-Fi, pairs with a 4-digit code and then can start games,
// see what is playing and pause / save / load / take screenshots / back up / exit. Nothing goes through the internet.
//
// Protocol (the same on the TV app):
//  - Finding: the phone sends "RSDISCOVER1" by UDP broadcast to port 47111; this PC answers with one JSON line {rs, id, name, kind, port}.
//  - Talking: TCP port 47110, one JSON line in, one JSON line out, then the connection closes.
//    {cmd:'hello'} · {cmd:'pair'} (shows a code) · {cmd:'pair', code, phone} → {token} · then every other command carries {token}:
//    {cmd:'status'} · {cmd:'play', game:{id, chatId, messageId, console, fileName, title}, fresh} · {cmd:'control', action, slot}
const net = require('net');
const dgram = require('dgram');
const os = require('os');
const crypto = require('crypto');
const EventEmitter = require('events');

const PORT = 47110, FIND_PORT = 47111;

class Remote extends EventEmitter {
  constructor({ store, version }) {
    super(); Object.assign(this, { store, version });
    this.code = null; this.codeUntil = 0; this.tries = 0;
    this.now = null;          // { game, since } while a game is running
    this.pending = null;      // { game, at } asked by the phone, still downloading / preparing
    this.state = { paused: false, ff: false };
  }
  enabled() { return this.store.get('remoteOn') !== false; }
  id() { let id = this.store.get('remoteId'); if (!id) { id = crypto.randomBytes(8).toString('hex'); this.store.set('remoteId', id); } return id; }
  name() { return this.store.get('remoteName') || os.hostname() || 'PC'; }
  phones() { return this.store.get('remotePhones') || {}; }
  forget() { this.store.set('remotePhones', {}); }

  start() {
    try {
      this.server = net.createServer((sock) => this.serve(sock));
      this.server.on('error', (e) => { this.error = String(e.message || e); });
      this.server.listen(PORT, '0.0.0.0');
    } catch (e) { this.error = String(e.message || e); }
    try {
      this.udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
      this.udp.on('error', () => {});
      this.udp.on('message', (msg, from) => {
        if (!this.enabled() || msg.toString().trim() !== 'RSDISCOVER1') return;
        const reply = Buffer.from(JSON.stringify({ rs: 1, id: this.id(), name: this.name(), kind: 'pc', port: PORT }) + '\n');
        this.udp.send(reply, from.port, from.address, () => {});
      });
      this.udp.bind(FIND_PORT, '0.0.0.0');
    } catch (_) { /* no discovery: the phone can still type the address */ }
  }

  serve(sock) {
    sock.setTimeout(15000, () => sock.destroy());
    sock.on('error', () => {});
    let buf = '';
    sock.on('data', async (d) => {
      buf += d.toString(); if (buf.length > 65536) { sock.destroy(); return; }
      const nl = buf.indexOf('\n'); if (nl < 0) return;
      let req; try { req = JSON.parse(buf.substring(0, nl)); } catch (_) { sock.end(JSON.stringify({ error: 'bad request' }) + '\n'); return; }
      let res; try { res = await this.handle(req, sock.remoteAddress); } catch (e) { res = { error: String(e.message || e) }; }
      sock.end(JSON.stringify(res || {}) + '\n');
    });
  }

  async handle(req, from) {
    if (!this.enabled()) return { error: 'off' };
    const base = { id: this.id(), name: this.name(), kind: 'pc', version: this.version };
    if (req.cmd === 'hello') return Object.assign({ ok: true, paired: !!this.phones()[req.token] }, base);
    if (req.cmd === 'pair') {
      if (!req.code) {
        this.code = String(crypto.randomInt(0, 10000)).padStart(4, '0'); this.codeUntil = Date.now() + 120000; this.tries = 0;
        this.emit('code', { code: this.code, phone: String(req.phone || '').substring(0, 40) });
        return Object.assign({ ok: true, needCode: true }, base);
      }
      if (!this.code || Date.now() > this.codeUntil) return { error: 'expired' };
      if (++this.tries > 5) { this.code = null; return { error: 'expired' }; }
      if (String(req.code) !== this.code) return { error: 'wrong code' };
      this.code = null; this.emit('code', null);
      const token = crypto.randomBytes(16).toString('hex');
      const phones = this.phones(); phones[token] = { name: String(req.phone || 'Phone').substring(0, 40), at: Date.now() }; this.store.set('remotePhones', phones);
      this.emit('paired', phones[token].name);
      return Object.assign({ ok: true, token }, base);
    }
    if (!req.token || !this.phones()[req.token]) return { error: 'unpaired' };
    switch (req.cmd) {
      case 'status': return Object.assign({ ok: true }, base, this.status());
      case 'play': return this.emitAsk('play', req);
      case 'control': return this.emitAsk('control', req);
      default: return { error: 'unknown command' };
    }
  }
  /** Lets main.js answer (it knows the library and the player). */
  emitAsk(kind, req) {
    return new Promise((resolve) => {
      if (!this.listenerCount(kind)) { resolve({ error: 'not ready' }); return; }
      this.emit(kind, req, resolve);
      setTimeout(() => resolve({ error: 'timeout' }), 30000);
    });
  }
  status() {
    if (this.pending && Date.now() - this.pending.at > 15 * 60000) this.pending = null;
    if (this.now) return { state: 'playing', game: this.now.game, since: this.now.since, paused: this.state.paused, ff: this.state.ff, controls: this.now.controls };
    if (this.pending) return { state: 'loading', game: this.pending.game, since: this.pending.at, progress: this.pending.progress || 0 };
    return { state: 'menu' };
  }
  playing(game, controls) { this.pending = null; this.state = { paused: false, ff: false }; this.now = { game: brief(game), since: Date.now(), controls }; }
  stopped() { this.now = null; this.state = { paused: false, ff: false }; }
  close() { try { this.server && this.server.close(); } catch (_) { /* gone */ } try { this.udp && this.udp.close(); } catch (_) { /* gone */ } }
}
function brief(g) { return { id: g.id, title: g.title, console: g.console, chatId: g.chatId || 0, messageId: g.messageId || 0, fileName: g.fileName || '', cover: g.cover || '' }; }

module.exports = { Remote, brief };
