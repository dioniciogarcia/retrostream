'use strict';
// Friends: a private Telegram group where each RetroStream (phone, TV, PC) keeps one status message up to date:
// online, in the menu, or playing which game (same format as the Android app).
const EventEmitter = require('events');

const FRIENDS_TITLE = 'TD - RetroStream - Friends';
const TAG = '#RSPRESENCE';
const ONLINE = 150000;

class Friends extends EventEmitter {
  constructor({ drive, store, t }) {
    super(); Object.assign(this, { drive, store, t });
    this.list = new Map(); this.names = new Map(); this.myMsg = 0; this.state = 'menu'; this.game = ''; this.console = ''; this.since = Date.now();
    drive.on('td', (u) => {
      if (u._ === 'updateNewMessage') this.message(u.message);
      else if (u._ === 'updateMessageContent' && u.chat_id === this.chat()) this.drive.invoke({ _: 'getMessage', chat_id: u.chat_id, message_id: u.message_id }).then((m) => this.message(m)).catch(() => {});
    });
    setInterval(() => { if (this.chat() && this.drive.stage === 'ready') { this.post(); this.load(); } else if (this.drive.stage === 'ready') this.discover().catch(() => {}); }, 60000);
  }
  mine() { return (this.store.get('friends') || {})[this.drive.account] || {}; }
  setMine(v) { const all = this.store.get('friends') || {}; all[this.drive.account] = v; this.store.set('friends', all); }
  chat() { return this.mine().chat || 0; }
  link() { return this.mine().link || ''; }
  share() { return this.store.get('friendsShare') !== false; }
  setShare(on) { this.store.set('friendsShare', on); if (!on) { const s = this.state; this.state = 'offline'; this.post(); this.state = s; } else this.post(); this.emit('changed'); }
  snapshot() {
    const now = Date.now();
    return { chat: this.chat(), link: this.link(), share: this.share(), me: this.drive.account,
      friends: [...this.list.values()].map((f) => Object.assign({}, f, { online: f.state !== 'offline' && now - f.ts < ONLINE, playing: f.state === 'playing' && !!f.game && now - f.ts < ONLINE })) };
  }
  async create() {
    if (this.drive.stage !== 'ready') throw new Error(this.t('Connect Telegram first (Settings > TirsoDrive).'));
    const norm = (x) => String(x || '').toLowerCase().replace(/[^a-z0-9]/g, '');
    let chat = [...this.drive.chats.values()].find((c) => this.drive.isGroup(c) && norm(c.title) === norm(FRIENDS_TITLE));
    if (!chat) chat = await this.drive.invoke({ _: 'createNewSupergroupChat', title: FRIENDS_TITLE, is_forum: false, is_channel: false, description: 'RetroStream · amigos conectados. No borres este grupo.', message_auto_delete_time: 0, for_import: false });
    const inv = await this.drive.invoke({ _: 'createChatInviteLink', chat_id: chat.id, name: 'RetroStream', expiration_date: 0, member_limit: 0, creates_join_request: false });
    this.ready(chat.id, inv.invite_link); return inv.invite_link;
  }
  async join(link) {
    link = String(link || '').trim();
    if (!/t\.me\/|telegram\.me\//.test(link)) throw new Error(this.t('That link is not a Telegram link.'));
    const chat = await this.drive.invoke({ _: 'joinChatByInviteLink', invite_link: link });
    this.ready(chat.id, '');
  }
  /** Every device of the account finds the friends group by its name (no link needed on the second device). */
  async discover() {
    if (this.chat() || this.drive.stage !== 'ready' || this.store.get('friendsLeft')) return;
    let id = await this.drive.ownChannel(FRIENDS_TITLE, '', false);
    if (!id) {
      const norm = (x) => String(x || '').toLowerCase().replace(/[^a-z0-9]/g, '');
      try {
        const r = await this.drive.invoke({ _: 'searchChatsOnServer', query: FRIENDS_TITLE, limit: 50 });
        for (const cid of r.chat_ids) { const x = await this.drive.invoke({ _: 'getChat', chat_id: cid }); if (this.drive.isGroup(x) && norm(x.title) === norm(FRIENDS_TITLE)) { this.drive.chats.set(x.id, x); id = x.id; break; } }
      } catch (_) { /* not found */ }
    }
    if (id && !this.chat()) this.ready(id, '');
  }
  leave() { this.store.set('friendsLeft', true); this.setMine({}); this.list.clear(); this.myMsg = 0; this.emit('changed'); }
  ready(chat, link) { this.store.set('friendsLeft', false); this.setMine({ chat, link }); this.list.clear(); this.myMsg = 0; this.load(); this.post(); this.emit('changed'); }
  line(text, key) { for (const l of text.split('\n')) { const i = l.indexOf(':'); if (i > 0 && l.substring(0, i) === key) return l.substring(i + 1).trim(); } return ''; }
  message(m) {
    if (!m || m.chat_id !== this.chat() || !m.content || m.content._ !== 'messageText' || !m.sender_id || m.sender_id._ !== 'messageSenderUser') return;
    const text = m.content.text.text; if (!text.startsWith(TAG)) return;
    const user = m.sender_id.user_id; if (user === this.drive.account) this.myMsg = m.id;
    const f = this.list.get(user) || { userId: user, name: this.names.get(user) || '' };
    const ts = Number(this.line(text, 'ts')) || m.date * 1000;
    if (f.ts && ts < f.ts) return;
    Object.assign(f, { state: this.line(text, 'state') || 'offline', game: this.line(text, 'game'), console: this.line(text, 'console'), ts, since: Number(this.line(text, 'since')) || ts });
    this.list.set(user, f);
    if (!f.name) this.drive.invoke({ _: 'getUser', user_id: user }).then((u) => { f.name = [u.first_name, u.last_name].filter(Boolean).join(' '); this.names.set(user, f.name); this.emit('changed'); }).catch(() => {});
    this.emit('changed');
  }
  async load() {
    const chat = this.chat(); if (!chat || this.drive.stage !== 'ready') return;
    try { for (const m of await this.drive.history(chat, 3)) this.message(m); } catch (_) { /* later */ }
    this.emit('changed');
  }
  text() { return TAG + '\nstate:' + this.state + (this.game ? '\ngame:' + this.game + '\nconsole:' + this.console : '') + '\nsince:' + this.since + '\nts:' + Date.now(); }
  async post() {
    const chat = this.chat(); if (!chat || this.drive.stage !== 'ready' || !this.drive.account) return;
    if (!this.share() && this.state !== 'offline') return;
    const content = { _: 'inputMessageText', text: { _: 'formattedText', text: this.text(), entities: [] }, clear_draft: false };
    try {
      if (this.myMsg) { await this.drive.invoke({ _: 'editMessageText', chat_id: chat, message_id: this.myMsg, input_message_content: content }); return; }
    } catch (e) { if (String(e.message).includes('NOT_MODIFIED')) return; this.myMsg = 0; }
    if (this.sending) return; this.sending = true;
    try { const m = await this.drive.sendAndWait(chat, content); this.myMsg = m.id; } catch (_) { /* later */ }
    this.sending = false;
  }
  playing(game) { this.state = 'playing'; this.game = game.title; this.console = game.console; this.since = Date.now(); this.post(); }
  inMenu() { if (this.state !== 'menu') { this.state = 'menu'; this.game = ''; this.console = ''; this.since = Date.now(); } this.post(); }
  offline() { this.state = 'offline'; this.game = ''; this.console = ''; return this.post(); }
  /** Sends a picture (a screenshot) to the friends group. */
  async sendPhoto(file, w, h, caption) {
    const chat = this.chat(); if (!chat) throw new Error(this.t('First create or join the friends group.'));
    await this.drive.sendAndWait(chat, { _: 'inputMessagePhoto', photo: { _: 'inputPhoto', photo: { _: 'inputFileLocal', path: file }, added_sticker_file_ids: [], width: w || 0, height: h || 0 },
      caption: { _: 'formattedText', text: caption || '', entities: [] }, has_spoiler: false });
  }
  async tell(text) {
    const chat = this.chat(); if (!chat) throw new Error(this.t('First create or join the friends group.'));
    await this.drive.invoke({ _: 'sendMessage', chat_id: chat, input_message_content: { _: 'inputMessageText', text: { _: 'formattedText', text, entities: [] }, clear_draft: false } });
  }
}

module.exports = { Friends };
