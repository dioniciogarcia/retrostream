'use strict';
// RetroStream for Windows: the app window, settings, and the Telegram connection.
const { app, BrowserWindow, ipcMain, shell, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const { Store } = require('./lib/store');
const { Drive } = require('./lib/telegram');
const { Player } = require('./lib/player');
const { Cloud } = require('./lib/cloud');
const { External } = require('./lib/external');
const { Meta } = require('./lib/meta');
const catalog = require('./lib/catalog');
const I18N = require('./renderer/i18n');

const API_ID = 38798734;
let config = {};
try { config = JSON.parse(fs.readFileSync(path.join(__dirname, 'config.json'), 'utf8')); } catch (_) { /* local build without key */ }

if (!app.requestSingleInstanceLock()) { app.quit(); }

let win = null, store = null, drive = null, player = null, cloud = null, external = null, meta = null, t = (s) => s;
/** The games as the screens show them (names, covers and descriptions from the lookup). */
function shownGames() { return store.games().map((g) => meta.apply(g)); }
let metaTimer = null;
function lookUp() { meta.refresh(store.games(), () => { clearTimeout(metaTimer); metaTimer = setTimeout(() => send('state', { drive: drive.snapshot(), games: shownGames() }), 400); }); }
function toast(text) { send('toast', text); }

function send(channel, data) { if (win && !win.isDestroyed()) win.webContents.send(channel, data); }

function createWindow() {
  win = new BrowserWindow({
    width: 1440, height: 900, minWidth: 1100, minHeight: 700, backgroundColor: '#0B0E15', autoHideMenuBar: true, show: false,
    fullscreen: store.get('fullscreen') !== false,   // opens full screen unless turned off in Settings
    title: 'RetroStream', icon: path.join(__dirname, 'renderer', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true }
  });
  win.removeMenu();
  win.once('ready-to-show', () => win.show());
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.on('before-input-event', (e, input) => {
    if (input.type === 'keyDown' && input.key === 'F11') { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
  });
  // Links open in the normal browser, never inside the app.
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https:\/\//.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e) => e.preventDefault());
}

/** Remembers which games are on this PC once their download finishes. */
function noteFile(info) {
  if (info.state !== 'done' && info.state !== 'none') return;
  const kept = store.get('kept') || {}; let changed = false;
  for (const g of store.games()) if (g.fileId === info.id && g.account === drive.account) {
    if (info.state === 'done' && kept[g.id] !== info.path) { kept[g.id] = info.path; changed = true; }
    if (info.state === 'none' && kept[g.id]) { delete kept[g.id]; changed = true; }
  }
  if (changed) { store.set('kept', kept); send('kept', kept); }
}

/** PS2 BIN/CUE: the .bin tracks the .cue names are separate files in the same channel; download them and put them next to it. */
const docsCache = new Map();
async function withTracks(game, cue) {
  const text = fs.readFileSync(cue, 'utf8');
  const names = [...text.matchAll(/FILE\s+"?([^"\r\n]+?)"?\s+(BINARY|MOTOROLA|WAVE|MP3)/gi)].map((m) => m[1]);
  if (!names.length) return cue;
  const dir = path.join(app.getPath('userData'), 'play', 'disc-' + Date.now()); fs.mkdirSync(dir, { recursive: true });
  const norm = (s) => s.toLowerCase().replace(/\.[a-z0-9]+$/, '').replace(/[^a-z0-9]/g, '');
  let docs = docsCache.get(game.chatId);
  if (!docs) { docs = await drive.documents(game.chatId); docsCache.set(game.chatId, docs); }
  for (const name of names) {
    const exact = docs.find((m) => m.content.document.file_name.toLowerCase() === name.toLowerCase());
    const loose = exact || docs.find((m) => norm(m.content.document.file_name) === norm(name) && /\.bin$/i.test(m.content.document.file_name));
    if (!loose) throw new Error(t('A track of this game is missing in the channel:') + ' ' + name);
    send('toast', t('Downloading') + ' ' + name + '…');
    const p = await drive.downloadNow(loose.content.document.document.id);
    if (!p) throw new Error(t('A track could not be downloaded:') + ' ' + name);
    const out = path.join(dir, name);
    try { fs.linkSync(p, out); } catch (_) { fs.copyFileSync(p, out); }
  }
  const out = path.join(dir, path.basename(cue)); fs.copyFileSync(cue, out);
  return out;
}

/** Split archives ("Game.7z.001", ".002"…): every part from the channel, side by side under their own names. */
async function withParts(game, first) {
  const base = path.basename(first).replace(/\.0*1$/, '');
  let docs = docsCache.get(game.chatId);
  if (!docs) { docs = await drive.documents(game.chatId); docsCache.set(game.chatId, docs); }
  const parts = docs.filter((m) => { const n = m.content.document.file_name; return n.toLowerCase().startsWith(base.toLowerCase() + '.') && /\.\d{2,3}$/.test(n); })
    .sort((a, b) => a.content.document.file_name.localeCompare(b.content.document.file_name));
  const dir = path.join(app.getPath('userData'), 'parts', game.id.replace(/[^A-Za-z0-9._-]/g, '_')); fs.mkdirSync(dir, { recursive: true });
  let firstOut = null;
  for (const m of parts) {
    const n = m.content.document.file_name; const out = path.join(dir, n);
    if (!fs.existsSync(out)) {
      send('toast', t('Downloading') + ' ' + n + '…');
      const p = await drive.downloadNow(m.content.document.document.id);
      if (!p) throw new Error(t('A part could not be downloaded:') + ' ' + n);
      try { fs.linkSync(p, out); } catch (_) { fs.copyFileSync(p, out); }
    }
    if (/\.0*1$/.test(n)) firstOut = out;
  }
  return firstOut || first;
}

const PREF_KEYS = new Set(['fullscreen', 'lang', 'theme', 'favorites', 'hidden', 'completed', 'collections', 'recent', 'plays', 'onboarded', 'skipped', 'sort', 'keep']);

app.whenReady().then(() => {
  const dir = app.getPath('userData');
  store = new Store(dir);
  if (!store.get('lang')) store.set('lang', app.getLocale().toLowerCase().startsWith('es') ? 'es' : 'en');
  t = I18N.make(store.get('lang'));
  drive = new Drive({ dir, store, apiId: API_ID, apiHash: String(config.apiHash || '').trim(), version: app.getVersion(), t });
  external = new External({ dir, store, t: (s) => t(s) });
  const afterGame = (info) => {
      if (info.played > 0) { const pt = store.get('playtime') || {}; pt[info.id] = (pt[info.id] || 0) + info.played; store.set('playtime', pt); }
      send('played', Object.assign(info, { playtime: store.get('playtime') || {} }));
      if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); }
      // Like the Exit button on Android: the save goes to Telegram right away, then any screenshots.
      if (drive.stage === 'ready') {
        (async () => {
          if (info.entry && cloud.changed(info.entry)) {
            try { const err = await cloud.upload(info.entry, true); toast(err || t('Save backed up to Telegram')); } catch (e) { toast(t('Save backup failed:') + ' ' + (e.message || e)); }
          }
          await cloud.uploadShots();
        })();
      }
    };
  player = new Player({ dir, resources: app.isPackaged ? process.resourcesPath : path.join(__dirname, 'res'), t: (s) => t(s), onExit: afterGame });
  cloud = new Cloud({ dir, drive, store, t: (s) => t(s) });
  meta = new Meta({ dir, t: (s) => t(s) });
  drive.on('state', (changed) => { send('state', { drive: drive.snapshot(), games: changed ? shownGames() : null }); if (changed) lookUp(); });
  drive.on('file', (info) => { noteFile(info); send('file', info); });

  ipcMain.handle('init', () => ({
    version: app.getVersion(), prefs: store.prefs.data, games: shownGames(), drive: drive.snapshot(),
    consoles: catalog.CONSOLES.map((c) => ({ id: c.id, title: c.title, thumbs: c.thumbs, color: c.color, big: !!c.big }))
  }));
  ipcMain.handle('prefs:set', (_e, k, v) => { if (PREF_KEYS.has(k)) { store.set(k, v); if (k === 'lang') t = drive.t = I18N.make(v); } return true; });
  ipcMain.handle('drive', async (_e, action, ...args) => {
    switch (action) {
      case 'qr': drive.requestQr(); break;
      case 'phone': await drive.phone(args[0]); break;
      case 'code': await drive.code(args[0]); break;
      case 'password': await drive.password(args[0]); break;
      case 'scan': drive.scanAll(); break;
      case 'sync': drive.sync(); break;
      case 'bind': drive.bind(args[0], args[1]); break;
      case 'unbind': drive.unbind(args[0]); break;
      case 'clear': drive.clearAll(); break;
      case 'choices': return drive.choices(args[0]);
      case 'logout': await drive.logout(); break;
      default: break;
    }
    return drive.snapshot();
  });
  ipcMain.handle('game', async (_e, action, game) => {
    switch (action) {
      case 'inspect': return drive.inspect(game.fileId);
      case 'download': return drive.download(game);
      case 'cancel': return drive.cancel(game);
      case 'remove': {
        await drive.remove(game);
        const kept = store.get('kept') || {}; const p = kept[game.id];
        if (p) { try { fs.unlinkSync(p); } catch (_) { /* already gone */ } delete kept[game.id]; store.set('kept', kept); send('kept', kept); }
        for (const d of ['unpacked', 'parts']) fs.rmSync(path.join(app.getPath('userData'), d, game.id.replace(/[^A-Za-z0-9._-]/g, '_')), { recursive: true, force: true });
        return true;
      }
      default: return null;
    }
  });
  ipcMain.handle('play', async (_e, game, opts) => {
    try {
      const ext = external.engineFor(game.console);
      if (!ext && !player.supported(game.console)) return { error: t('This console arrives in a later update.') };
      if (ext) await external.ensure(ext, (m) => toast(m));
      let file = (store.get('kept') || {})[game.id];
      if (!file || !fs.existsSync(file)) { const i = await drive.inspect(game.fileId); file = i.path; }
      if (!file || !fs.existsSync(file)) return { error: t('Download the game first.') };
      if (file.toLowerCase().endsWith('.cue')) file = await withTracks(game, file);
      if (/\.(7z|zip)\.0*1$/i.test(file)) file = await withParts(game, file);
      if (ext && (game.console === 'PS3' || game.console === 'SWITCH')) toast(t('Preparing the game… (big games take a while the first time)'));
      const prep = await player.prepare(game, file, !!ext);
      const entry = prep.entry || (ext ? external.sharedEntry(game.console) : null);
      if (drive.stage === 'ready' && entry) {
        // The newest save from the phone, TV or another PC first (but never wait more than ~8 seconds for it).
        const pulled = await Promise.race([cloud.pullNewest(entry).catch(() => ''), new Promise((r) => setTimeout(() => r(''), 8000))]);
        if (pulled) toast(pulled);
      }
      if (ext) { await external.launch(prep, Object.assign({}, store.prefs.data, opts || {}), afterGame); return { ok: true }; }
      const cheats = await Promise.race([cloud.cheats(game).catch(() => null), new Promise((r) => setTimeout(() => r(null), 6000))]);
      await player.launch(prep, Object.assign({}, store.prefs.data, opts || {}), cheats);
      return { ok: true };
    } catch (e) { return { error: String(e.message || e) }; }
  });
  ipcMain.handle('backups', async (_e, action, arg) => {
    try {
      if (action === 'list') return { list: cloud.latestPerGame(await cloud.list()) };
      if (action === 'all') return await cloud.uploadAll((p) => toast(t('Backing up') + ' ' + p));
      if (action === 'restore') { await cloud.restore(arg); return { ok: true }; }
      if (action === 'restoreAll') { const list = cloud.latestPerGame(await cloud.list()); let n = 0; for (const b of list) { try { await cloud.restore(b); n++; } catch (_) { /* next */ } } return { ok: true, n }; }
    } catch (e) { return { error: String(e.message || e) }; }
    return {};
  });
  ipcMain.handle('bios', async (_e, action) => {
    const dir = path.join(app.getPath('userData'), 'bios', 'pcsx2', 'bios');
    if (action === 'list') return fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => !f.startsWith('.')) : [];
    if (action === 'add') {
      const r = await dialog.showOpenDialog(win, { title: t('Choose your PS2 BIOS file'), properties: ['openFile'], filters: [{ name: 'PS2 BIOS', extensions: ['bin', 'rom', 'BIN', 'ROM'] }] });
      if (r.canceled || !r.filePaths.length) return null;
      const f = r.filePaths[0]; const size = fs.statSync(f).size;
      if (size < 2 * 1024 * 1024 || size > 16 * 1024 * 1024) return { error: t('That file does not look like a PS2 BIOS (it should be about 4 MB).') };
      fs.mkdirSync(dir, { recursive: true }); fs.copyFileSync(f, path.join(dir, path.basename(f)));
      return { ok: path.basename(f) };
    }
    return null;
  });
  ipcMain.handle('ext', async (_e, action, arg) => {
    try {
      switch (action) {
        case 'status': return external.status();
        case 'install': await external.install(arg, (m) => toast(m)); return { ok: true, status: external.status() };
        case 'open': external.openSettings(arg); return { ok: true };
        case 'ps3fw': {
          const r = await dialog.showOpenDialog(win, { title: t('Choose your PS3 firmware (PS3UPDAT.PUP)'), properties: ['openFile'], filters: [{ name: 'PS3 firmware', extensions: ['PUP', 'pup'] }] });
          if (r.canceled || !r.filePaths.length) return null;
          toast(t('Installing the PS3 firmware…')); await external.ps3Firmware(r.filePaths[0]); return { ok: true, status: external.status() };
        }
        case 'keys': {
          const r = await dialog.showOpenDialog(win, { title: t('Choose prod.keys (and title.keys if you have it)'), properties: ['openFile', 'multiSelections'], filters: [{ name: 'Keys', extensions: ['keys'] }] });
          if (r.canceled || !r.filePaths.length) return null;
          external.switchKeys(r.filePaths); return { ok: true, status: external.status() };
        }
        case 'switchfw': {
          const r = await dialog.showOpenDialog(win, { title: t('Choose the firmware .zip (or its folder)'), properties: arg === 'folder' ? ['openDirectory'] : ['openFile'], filters: arg === 'folder' ? [] : [{ name: 'Firmware', extensions: ['zip'] }] });
          if (r.canceled || !r.filePaths.length) return null;
          toast(t('Installing the Switch firmware…')); const n = await external.switchFirmware(r.filePaths[0]); return { ok: true, n, status: external.status() };
        }
        default: return null;
      }
    } catch (e) { return { error: String(e.message || e) }; }
  });
  ipcMain.handle('meta', async (_e, action, game, arg) => {
    try {
      if (action === 'search') return { list: await meta.search(game.console, arg) };
      if (action === 'choose') await meta.choose(game, arg);
      if (action === 'rename') meta.rename(game, arg);
      if (action === 'reset') { meta.reset(game); lookUp(); }
      send('state', { drive: drive.snapshot(), games: shownGames() });
      return { ok: true };
    } catch (e) { return { error: String(e.message || e) }; }
  });
  ipcMain.handle('window', (_e, action) => {
    if (!win) return null;
    if (action === 'minimize') win.minimize();
    if (action === 'isFull') return win.isFullScreen();
    if (action === 'reload') { drive.sync(); }
    return null;
  });
  ipcMain.handle('qr', async (_e, text) => require('qrcode').toDataURL(String(text || ''), { margin: 1, width: 400 }));
  ipcMain.handle('open', (_e, url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });
  ipcMain.handle('fullscreen', () => { if (win) win.setFullScreen(!win.isFullScreen()); });
  ipcMain.handle('quit', () => app.quit());

  createWindow();
  drive.start();
  setTimeout(lookUp, 3000);
});

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.on('window-all-closed', async () => { store && store.flush(); if (drive) await drive.close(); app.quit(); });
