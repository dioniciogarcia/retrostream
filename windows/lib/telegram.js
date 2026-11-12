'use strict';
// The Telegram side (same behaviour as the Android app's TirsoDrive): sign-in, finding the console channels,
// reading their game files, downloads.
const fs = require('fs');
const path = require('path');
const EventEmitter = require('events');
const tdl = require('tdl');
const catalog = require('./catalog');

function tdjsonPath() {
  const { getTdjson } = require('prebuilt-tdlib');
  // Inside the installed app the library is unpacked next to the archive.
  return getTdjson().replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep);
}

class Drive extends EventEmitter {
  constructor({ dir, store, apiId, apiHash, version, t }) {
    super();
    Object.assign(this, { dir, store, apiId, apiHash, version, t });
    this.stage = 'connecting';       // connecting | signin | qr | code | password | ready | error
    this.qrLink = ''; this.hint = ''; this.error = ''; this.status = '';
    this.account = 0; this.me = '';
    this.chats = new Map();
    this.files = new Map();            // file id -> TDLib file
    this.counts = {};                  // chat id -> games counted by the last scan
    this.busy = false;                 // scanning or reading channels
    this.sources = {};
    for (const c of catalog.CONSOLES) this.sources[c.id] = { chatId: 0, title: '', candidates: [] };
    this.client = null; this.restarting = false;
    this.net = 'connecting';          // waiting | proxy | connecting | updating | ready (Telegram's own connection state)
    this.proxy = (store.get('proxy') || '');
  }

  // ------------------------------------------------------------------ connection and sign-in
  start() {
    if (!this.apiHash) { this.stage = 'error'; this.error = this.t('This build has no Telegram key. Download the official RetroStream installer.'); this.publish(); return; }
    tdl.configure({ tdjson: tdjsonPath(), verbosityLevel: 1 });
    this.client = tdl.createClient({
      apiId: this.apiId, apiHash: this.apiHash,
      databaseDirectory: path.join(this.dir, 'telegram', 'database'),
      filesDirectory: path.join(this.dir, 'telegram', 'files'),
      tdlibParameters: { device_model: 'Windows PC', system_language_code: 'es', application_version: this.version, use_file_database: true, use_chat_info_database: true, use_message_database: true }
    });
    this.client.on('error', (e) => { this.error = String((e && e.message) || e); this.log('error ' + this.error); this.publish(); });
    // TDLib's own log, for Settings > Problem report and the sign-in "Copy report" button.
    this.client.invoke({ _: 'setLogStream', log_stream: { _: 'logStreamFile', path: path.join(this.dir, 'telegram.log'), max_file_size: 4 * 1024 * 1024, redirect_stderr: false } }).catch(() => {});
    this.client.invoke({ _: 'setLogVerbosityLevel', new_verbosity_level: 2 }).catch(() => {});
    if (this.proxy) this.applyProxy(this.proxy).catch((e) => { this.error = this.t('The proxy could not be used:') + ' ' + (e.message || e); this.publish(); });
    this.client.on('update', (u) => { try { this.onUpdate(u); } catch (e) { this.error = String(e.message || e); this.publish(); } });
  }
  invoke(q) { return this.client.invoke(q); }
  publish(catalogChanged) { this.emit('state', catalogChanged === true); }

  onUpdate(u) {
    if (u._ === 'updateNewMessage' || u._ === 'updateMessageContent' || u._ === 'updateUser') this.emit('td', u);
    switch (u._) {
      case 'updateAuthorizationState': this.auth(u.authorization_state); break;
      case 'updateConnectionState': {
        const m = { connectionStateWaitingForNetwork: 'waiting', connectionStateConnectingToProxy: 'proxy', connectionStateConnecting: 'connecting', connectionStateUpdating: 'updating', connectionStateReady: 'ready' };
        this.net = m[u.state._] || 'connecting'; this.log('connection ' + this.net); this.publish(); break;
      }
      case 'updateNewChat': this.chats.set(u.chat.id, u.chat); break;
      case 'updateChatTitle': { const c = this.chats.get(u.chat_id); if (c) c.title = u.title; break; }
      case 'updateFile': this.fileUpdated(u.file); break;
      case 'updateMessageSendSucceeded': case 'updateMessageSendFailed': this.emit('sent', u); break;
      default: break;
    }
  }
  auth(s) {
    switch (s._) {
      case 'authorizationStateWaitPhoneNumber':
        this.stage = 'signin'; this.qrLink = '';
        if (this.pendingPhone) {
          const n = this.pendingPhone; const done = this.pendingDone || (() => {}); this.pendingPhone = null; this.pendingDone = null;
          this.timed({ _: 'setAuthenticationPhoneNumber', phone_number: n }).then(() => done(''), (e) => done(this.fail(e)));
        }
        else if (this.wantQr !== false) this.requestQr();   // the QR tab is the first thing shown
        break;
      case 'authorizationStateWaitOtherDeviceConfirmation': this.stage = 'qr'; this.qrLink = s.link; break;
      case 'authorizationStateWaitCode': this.stage = 'code'; break;
      case 'authorizationStateWaitPassword': this.stage = 'password'; this.hint = s.password_hint || ''; break;
      case 'authorizationStateReady': this.stage = 'ready'; this.error = ''; this.ready(); break;
      case 'authorizationStateClosed':
        if (this.restarting) { this.restarting = false; this.stage = 'connecting'; this.start(); }
        break;
      default: break;
    }
    this.publish();
  }
  /** QR sign-in (Telegram on the phone: Settings > Devices > Link Desktop Device). */
  requestQr() {
    this.wantQr = true;
    if (this.stage === 'qr') return;
    if (this.stage !== 'signin') { this.restart(); return; }
    this.timed({ _: 'requestQrCodeAuthentication', other_user_ids: [] }).catch((e) => this.fail(e));
  }
  /** A sign-in request that gives up after [ms] instead of waiting forever when Telegram can't be reached. */
  timed(q, ms) {
    return Promise.race([this.invoke(q), new Promise((_, reject) => setTimeout(() => reject(new Error('TIMEOUT')), ms || 30000))]);
  }
  log(line) { try { fs.appendFileSync(path.join(this.dir, 'retrostream-telegram.log'), new Date().toISOString() + ' ' + line + '\n'); } catch (_) { /* best effort */ } }
  /** Proxy for networks where Telegram is blocked: a t.me/proxy or t.me/socks link, or socks5://user:pass@host:port. Empty turns it off. */
  async setProxy(text) {
    text = String(text || '').trim();
    this.store.set('proxy', text); this.proxy = text;
    if (!text) { try { await this.invoke({ _: 'disableProxy' }); } catch (_) { /* none */ } this.publish(); return ''; }
    try { await this.applyProxy(text); this.error = ''; this.publish(); return ''; }
    catch (e) { this.store.set('proxy', ''); this.proxy = ''; return this.t('The proxy could not be used:') + ' ' + (e.message || e); }
  }
  async applyProxy(text) {
    let server = '', port = 0, type = null;
    const link = /^(?:https?:\/\/)?(?:t\.me|telegram\.me)\/(proxy|socks)\?(.+)$/i.exec(text) || /^tg:\/\/(proxy|socks)\?(.+)$/i.exec(text);
    if (link) {
      const q = new URLSearchParams(link[2]); server = q.get('server') || ''; port = Number(q.get('port')) || 0;
      type = link[1].toLowerCase() === 'proxy' ? { _: 'proxyTypeMtproto', secret: q.get('secret') || '' } : { _: 'proxyTypeSocks5', username: q.get('user') || '', password: q.get('pass') || '' };
    } else {
      const m = /^(?:(socks5|http):\/\/)?(?:([^:@]+):([^@]*)@)?([^:/\s]+):(\d+)\/?$/i.exec(text);
      if (!m) throw new Error(this.t('Paste a t.me/proxy link or write server:port.'));
      server = m[4]; port = Number(m[5]);
      type = (m[1] || '').toLowerCase() === 'http' ? { _: 'proxyTypeHttp', username: m[2] || '', password: m[3] || '', http_only: false } : { _: 'proxyTypeSocks5', username: m[2] || '', password: m[3] || '' };
    }
    if (!server || !port) throw new Error(this.t('Paste a t.me/proxy link or write server:port.'));
    await this.invoke({ _: 'addProxy', proxy: { _: 'proxy', server, port, type }, enable: true, comment: 'RetroStream' });
  }
  /** Phone number sign-in: the number with its country code, e.g. +591 70000000. */
  /** Returns '' when Telegram accepted it, or the reason it failed (shown in a popup). */
  async phone(number) {
    this.wantQr = false; this.error = '';
    const n = '+' + String(number || '').replace(/\D/g, '');
    if (n.length < 9) { this.error = this.t('Write the number with its country code, for example +591 70000000.'); this.publish(); return this.error; }
    this.log('phone sign-in, stage ' + this.stage + ', connection ' + this.net);
    if (this.stage !== 'signin') {
      // From the QR screen TDLib has to start over first; the number is sent as soon as it's ready.
      return new Promise((resolve) => {
        this.pendingPhone = n; this.pendingDone = resolve; if (this.stage === 'qr') this.restart();
        setTimeout(() => { if (this.pendingDone === resolve) { this.pendingDone = null; this.pendingPhone = null; resolve(this.fail(new Error('TIMEOUT'))); } }, 40000);
      });
    }
    try { await this.timed({ _: 'setAuthenticationPhoneNumber', phone_number: n }); return ''; } catch (e) { return this.fail(e); }
  }
  async code(c) { this.error = ''; try { await this.timed({ _: 'checkAuthenticationCode', code: String(c || '').replace(/\D/g, '') }); return ''; } catch (e) { return this.fail(e); } }
  async password(p) { this.error = ''; try { await this.timed({ _: 'checkAuthenticationPassword', password: String(p || '') }); return ''; } catch (e) { return this.fail(e); } }
  /** Leaves the QR screen (TDLib can't switch from QR to a phone number without starting over). */
  restart() {
    if (!this.client || this.restarting) return;
    this.restarting = true;
    this.client.close().catch(() => {});
  }
  async logout() {
    try { await this.invoke({ _: 'logOut' }); } catch (_) { /* ignore */ }
    this.store.replaceGames([]); this.account = 0; this.restarting = true;
  }
  fail(e) {
    const m = String((e && e.message) || e);
    const known = {
      PHONE_NUMBER_INVALID: 'That phone number is not valid. Check the country code.',
      PHONE_CODE_INVALID: 'That code is not right. Check the latest code Telegram sent.',
      PHONE_CODE_EXPIRED: 'That code expired. Ask for a new one.',
      PASSWORD_HASH_INVALID: 'That password is not right.',
      FLOOD_WAIT: 'Telegram asks to wait a moment before trying again.',
      PHONE_NUMBER_BANNED: 'Telegram blocked this phone number.',
      PHONE_NUMBER_FLOOD: 'Too many attempts with this number. Wait a few hours and try again.',
      PHONE_PASSWORD_FLOOD: 'Too many attempts. Wait a few hours and try again.',
      API_ID: 'This build has no valid Telegram key. Download the official RetroStream installer.',
      TIMEOUT: 'Telegram did not answer. Check that this PC has internet, that the date and time of Windows are right, and that the firewall or antivirus is not blocking RetroStream. If Telegram is blocked where you are, add a proxy (Connection button).'
    };
    this.log('failed: ' + m + ' (connection ' + this.net + ')');
    const key = Object.keys(known).find((k) => m.includes(k));
    this.error = key ? this.t(known[key]) : m;
    this.publish();
    return this.error;
  }

  // ------------------------------------------------------------------ after sign-in
  async ready() {
    try {
      const me = await this.invoke({ _: 'getMe' });
      this.account = me.id; this.me = [me.first_name, me.last_name].filter(Boolean).join(' ');
      this.publish();
      await this.discover();
    } catch (e) { this.fail(e); }
  }
  async loadChats() {
    for (const list of [{ _: 'chatListMain' }, { _: 'chatListArchive' }]) {
      for (let i = 0; i < 1000; i++) {
        try { await this.invoke({ _: 'loadChats', chat_list: list, limit: 100 }); } catch (e) { if (e && (e.code === 404 || String(e.message).includes('404'))) break; throw e; }
      }
      const res = await this.invoke({ _: 'getChats', chat_list: list, limit: 10000 });
      for (const id of res.chat_ids) if (!this.chats.has(id)) { try { this.chats.set(id, await this.invoke({ _: 'getChat', chat_id: id })); } catch (_) { /* skip */ } }
    }
  }
  isGroup(c) { return c && c.type && (c.type._ === 'chatTypeBasicGroup' || c.type._ === 'chatTypeSupergroup'); }
  bindings() { return (this.store.get('bindings') || {})[this.account] || {}; }
  saveBindings() {
    const all = this.store.get('bindings') || {}; const mine = {};
    for (const [id, s] of Object.entries(this.sources)) if (s.chatId) mine[id] = s.chatId;
    all[this.account] = mine; this.store.set('bindings', all);
  }
  /** Finds the console channels by name (keeps the ones you picked before), then reads them. */
  async discover() {
    if (this.busy) return;
    this.busy = true; this.status = this.t('Looking for your TD - ROMs channels…'); this.publish();
    try {
      await this.loadChats();
      const saved = this.bindings(); let found = 0;
      for (const [id, s] of Object.entries(this.sources)) {
        s.candidates = [...this.chats.values()].filter((c) => this.isGroup(c) && catalog.consoleFor(c.title) === id);
        const keep = saved[id] && this.chats.get(saved[id]);
        if (keep) { s.chatId = keep.id; s.title = keep.title; }
        else if (s.candidates.length === 1) { s.chatId = s.candidates[0].id; s.title = s.candidates[0].title; }
        else { s.chatId = 0; s.title = ''; }
        if (s.chatId) found++;
      }
      this.saveBindings();
      this.busy = false;
      if (found === 0 && Object.values(this.sources).some((s) => s.candidates.length > 1)) await this.scanAll();
      else await this.sync();
    } catch (e) { this.busy = false; this.fail(e); }
  }
  /** Counts the games in every channel named after a console and gives each console the fullest one. */
  async scanAll() {
    if (this.busy || this.stage !== 'ready') return;
    this.busy = true; this.error = '';
    try {
      await this.loadChats();
      const named = [...this.chats.values()].filter((c) => this.isGroup(c) && catalog.consoleFor(c.title));
      let i = 0;
      for (const c of named) {
        i++; this.status = this.t('Scanning') + ' ' + c.title + ` (${i}/${named.length})`; this.publish();
        const id = catalog.consoleFor(c.title); let n = 0;
        try {
          const page = await this.invoke({ _: 'searchChatMessages', chat_id: c.id, query: '', from_message_id: 0, offset: 0, limit: 100, filter: { _: 'searchMessagesFilterDocument' } });
          for (const m of page.messages) if (catalog.game(this.account, id, m)) n++;
          if (page.messages.length > 0 && page.total_count > page.messages.length) n = Math.round(n * page.total_count / page.messages.length);
        } catch (_) { /* unreadable channel */ }
        this.counts[c.id] = n;
      }
      for (const [id, s] of Object.entries(this.sources)) {
        s.candidates = named.filter((c) => catalog.consoleFor(c.title) === id);
        let best = null;
        for (const c of s.candidates) {
          const n = this.counts[c.id] || 0; const b = best ? this.counts[best.id] || 0 : -1;
          const channel = c.type._ === 'chatTypeSupergroup' && c.type.is_channel;
          if (n > 0 && (!best || n > b || (n === b && channel))) best = c;
        }
        s.chatId = best ? best.id : 0; s.title = best ? best.title : '';
      }
      this.saveBindings();
    } catch (e) { this.fail(e); }
    this.busy = false;
    await this.sync();
  }
  bind(consoleId, chatId) {
    const chat = this.chats.get(chatId); if (!chat) return;
    for (const [id, s] of Object.entries(this.sources)) if (id !== consoleId && s.chatId === chatId) { this.error = this.t('That channel is already assigned to') + ' ' + catalog.BY_ID[id].title; this.publish(); return; }
    Object.assign(this.sources[consoleId], { chatId, title: chat.title }); this.saveBindings(); this.publish();
  }
  unbind(consoleId) { Object.assign(this.sources[consoleId], { chatId: 0, title: '' }); this.saveBindings(); this.publish(); }
  clearAll() {
    for (const s of Object.values(this.sources)) Object.assign(s, { chatId: 0, title: '', candidates: [] });
    this.saveBindings(); this.store.replaceGames([]); this.status = this.t('Assignments cleared. Tap Scan all channels to assign them again.'); this.publish(true);
  }
  choices(consoleId) {
    const s = this.sources[consoleId];
    const list = s.candidates.length > 1 ? s.candidates : [...this.chats.values()].filter((c) => this.isGroup(c) && catalog.consoleFor(c.title));
    return list.map((c) => ({ id: c.id, title: c.title, channel: !!(c.type && c.type.is_channel), files: this.counts[c.id] })).sort((a, b) => a.title.localeCompare(b.title));
  }
  /** Reads every assigned channel's files from Telegram's servers, page by page. */
  async sync() {
    if (this.busy || this.stage !== 'ready') return;
    const bound = Object.entries(this.sources).filter(([, s]) => s.chatId);
    if (bound.length === 0) { this.status = this.t('No channels assigned. Choose your consoles’ channels in Settings > TirsoDrive.'); this.publish(); return; }
    this.busy = true; this.error = '';
    const games = [];
    try {
      for (const [id, s] of bound) {
        let from = 0; const seen = new Set();
        for (let pages = 0; pages < 2000; pages++) {
          this.status = this.t('Reading') + ' ' + catalog.BY_ID[id].title + ' · ' + games.length + ' ' + this.t('games found'); this.publish();
          const page = await this.invoke({ _: 'searchChatMessages', chat_id: s.chatId, query: '', from_message_id: from, offset: 0, limit: 100, filter: { _: 'searchMessagesFilterDocument' } });
          for (const m of page.messages) { if (seen.has(m.id)) continue; seen.add(m.id); const g = catalog.game(this.account, id, m); if (g) games.push(g); }
          if (page.messages.length === 0 || !page.next_from_message_id) break;
          from = page.next_from_message_id;
        }
        this.counts[s.chatId] = games.filter((g) => g.chatId === s.chatId).length;
      }
      this.store.replaceGames(games);
      this.status = this.t('Catalog updated:') + ' ' + games.length + ' ' + this.t('games');
      this.busy = false; this.publish(true);
    } catch (e) { this.busy = false; this.fail(e); }
  }

  // ------------------------------------------------------------------ downloads
  fileUpdated(f) {
    this.files.set(f.id, f);
    this.emit('file', this.fileInfo(f.id));
  }
  fileInfo(id) {
    const f = this.files.get(id); if (!f) return { id, state: 'none' };
    const done = f.local.is_downloading_completed && f.local.path && fs.existsSync(f.local.path);
    return { id, state: done ? 'done' : f.local.is_downloading_active ? 'downloading' : 'none', have: f.local.downloaded_size, size: f.expected_size || f.size, path: done ? f.local.path : '' };
  }
  async inspect(fileId) { try { this.fileUpdated(await this.invoke({ _: 'getFile', file_id: fileId })); } catch (_) { /* unknown */ } return this.fileInfo(fileId); }
  async download(game) {
    if (this.stage !== 'ready' || game.account !== this.account) { this.error = this.t('Connect the Telegram account of this game first.'); this.publish(); return null; }
    const info = await this.inspect(game.fileId);
    if (info.state === 'done') return info;
    try { this.fileUpdated(await this.invoke({ _: 'downloadFile', file_id: game.fileId, priority: 16, offset: 0, limit: 0, synchronous: false })); }
    catch (e) { this.fail(e); return null; }
    return this.fileInfo(game.fileId);
  }
  async cancel(game) { try { await this.invoke({ _: 'cancelDownloadFile', file_id: game.fileId, only_if_pending: false }); } catch (_) { /* ignore */ } }
  async remove(game) { try { await this.invoke({ _: 'deleteFile', file_id: game.fileId }); } catch (_) { /* ignore */ } this.files.delete(game.fileId); this.emit('file', { id: game.fileId, state: 'none' }); }

  // ------------------------------------------------------------------ RetroStream's own channels (save backups, screenshots)
  norm(s) { return String(s || '').toLowerCase().replace(/[^a-z0-9]/g, ''); }
  /** Finds a channel by its exact name (in the chat lists or by search); creates it when [create] and it doesn't exist. */
  async ownChannel(title, description, create) {
    if (this.stage !== 'ready') throw new Error(this.t('Connect Telegram first (Settings > TirsoDrive).'));
    const want = this.norm(title);
    const find = () => [...this.chats.values()].find((c) => this.isGroup(c) && this.norm(c.title) === want);
    let c = find(); if (c) return c.id;
    await this.loadChats(); c = find(); if (c) return c.id;
    try {
      const r = await this.invoke({ _: 'searchChats', query: title, limit: 20 });
      for (const id of r.chat_ids) { const x = await this.invoke({ _: 'getChat', chat_id: id }); this.chats.set(x.id, x); if (this.isGroup(x) && this.norm(x.title) === want) return x.id; }
    } catch (_) { /* fall through */ }
    if (!create) return 0;
    const made = await this.invoke({ _: 'createNewSupergroupChat', title, is_forum: false, is_channel: true, description, message_auto_delete_time: 0, for_import: false });
    this.chats.set(made.id, made); return made.id;
  }
  /** Sends a message and waits until Telegram has really stored it (uploads included). */
  sendAndWait(chatId, content) {
    return new Promise((resolve, reject) => {
      let tempId = 0; const early = new Map();
      const done = (u) => {
        if (u._ === 'updateMessageSendSucceeded' && u.old_message_id === tempId) { this.off('sent', listen); clearTimeout(timer); resolve(u.message); }
        else if (u._ === 'updateMessageSendFailed' && u.old_message_id === tempId) { this.off('sent', listen); clearTimeout(timer); reject(new Error(u.error ? u.error.message : 'send failed')); }
      };
      const listen = (u) => { if (!tempId) early.set(u.old_message_id, u); else done(u); };
      const timer = setTimeout(() => { this.off('sent', listen); reject(new Error(this.t('Telegram took too long to answer. Try again.'))); }, 10 * 60 * 1000);
      this.on('sent', listen);
      this.invoke({ _: 'sendMessage', chat_id: chatId, input_message_content: content }).then((m) => {
        tempId = m.id; if (early.has(tempId)) done(early.get(tempId));
      }).catch((e) => { this.off('sent', listen); clearTimeout(timer); reject(e); });
    });
  }
  /** Every document message in a chat, newest first (from Telegram's servers). */
  async documents(chatId, max) {
    const out = []; let from = 0;
    for (let i = 0; i < 200; i++) {
      const page = await this.invoke({ _: 'searchChatMessages', chat_id: chatId, query: '', from_message_id: from, offset: 0, limit: 100, filter: { _: 'searchMessagesFilterDocument' } });
      out.push(...page.messages);
      if (!page.messages.length || !page.next_from_message_id || out.length >= (max || 20000)) break;
      from = page.next_from_message_id;
    }
    return out;
  }
  async history(chatId, pages) {
    const out = []; let from = 0;
    for (let i = 0; i < (pages || 3); i++) {
      const r = await this.invoke({ _: 'getChatHistory', chat_id: chatId, from_message_id: from, offset: 0, limit: 100, only_local: false });
      if (!r.messages.length) break; out.push(...r.messages); from = r.messages[r.messages.length - 1].id;
    }
    return out;
  }
  async downloadNow(fileId) { const f = await this.invoke({ _: 'downloadFile', file_id: fileId, priority: 32, offset: 0, limit: 0, synchronous: true }); return f.local.is_downloading_completed ? f.local.path : null; }

  /** Messages of one kind ('Photo', 'Document'…) in a chat, newest first. */
  async search(chatId, kind, max) {
    const out = []; let from = 0;
    for (let i = 0; i < 50; i++) {
      const page = await this.invoke({ _: 'searchChatMessages', chat_id: chatId, query: '', from_message_id: from, offset: 0, limit: 100, filter: { _: 'searchMessagesFilter' + kind } });
      out.push(...page.messages);
      if (!page.messages.length || !page.next_from_message_id || out.length >= (max || 2000)) break;
      from = page.next_from_message_id;
    }
    return out;
  }
  /** "TD - " groups and channels this account created that hold no files, photos, videos or audio (duplicates). */
  async emptyGroups(keep) {
    if (this.stage !== 'ready') throw new Error(this.t('Connect Telegram first (Settings > TirsoDrive).'));
    await this.loadChats();
    const bound = new Set(Object.values(this.sources).map((s) => s.chatId).filter(Boolean));
    const out = [];
    for (const c of this.chats.values()) {
      if (!this.isGroup(c) || !this.norm(c.title).startsWith('td') || !/^\s*td\s*-/i.test(c.title || '') || bound.has(c.id) || (keep || []).includes(c.id)) continue;
      try {
        const m = await this.invoke({ _: 'getChatMember', chat_id: c.id, member_id: { _: 'messageSenderUser', user_id: this.account } });
        if (!m.status || m.status._ !== 'chatMemberStatusCreator') continue;
        let total = 0;
        for (const k of ['Document', 'Photo', 'Video', 'Audio']) {
          // An unknown answer counts as not empty, so nothing is deleted by mistake.
          try { const r = await this.invoke({ _: 'searchChatMessages', chat_id: c.id, query: '', from_message_id: 0, offset: 0, limit: 1, filter: { _: 'searchMessagesFilter' + k } }); total += Math.max(r.total_count || 0, r.messages.length); } catch (_) { total += 1; }
          if (total) break;
        }
        if (!total) out.push({ id: c.id, title: c.title });
      } catch (_) { /* not ours */ }
    }
    return out;
  }
  async deleteChats(ids) {
    let n = 0;
    for (const id of ids) {
      try { await this.invoke({ _: 'deleteChat', chat_id: id }); n++; }
      catch (_) { try { await this.invoke({ _: 'leaveChat', chat_id: id }); n++; } catch (__) { /* skip */ } }
      this.chats.delete(id);
    }
    return n;
  }
  async messageLink(chatId, messageId) {
    try { return (await this.invoke({ _: 'getMessageLink', chat_id: chatId, message_id: messageId, media_timestamp: 0, for_album: false, in_message_thread: false })).link; } catch (_) { return ''; }
  }
  async forward(toChat, fromChat, messageId) {
    await this.invoke({ _: 'forwardMessages', chat_id: toChat, from_chat_id: fromChat, message_ids: [messageId], send_copy: false, remove_caption: false });
  }

  snapshot() {
    const sources = {};
    for (const [id, s] of Object.entries(this.sources)) sources[id] = { chatId: s.chatId, title: s.title, files: s.chatId ? this.counts[s.chatId] : undefined, choices: s.candidates.length };
    return { net: this.net, proxy: this.proxy, stage: this.stage, qrLink: this.qrLink, hint: this.hint, error: this.error, status: this.status, busy: this.busy, me: this.me, account: this.account, sources };
  }
  async close() { if (this.client) { try { await this.client.close(); } catch (_) { /* ignore */ } } }
}

module.exports = { Drive };
