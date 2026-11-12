'use strict';
// RetroStream for Windows: the app window, settings, and the Telegram connection.
const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const { Store } = require('./lib/store');
const { Drive } = require('./lib/telegram');
const { Player } = require('./lib/player');
const catalog = require('./lib/catalog');
const I18N = require('./renderer/i18n');

const API_ID = 38798734;
let config = {};
try { config = JSON.parse(fs.readFileSync(path.join(__dirname, 'config.json'), 'utf8')); } catch (_) { /* local build without key */ }

if (!app.requestSingleInstanceLock()) { app.quit(); }

let win = null, store = null, drive = null, player = null, t = (s) => s;

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

const PREF_KEYS = new Set(['fullscreen', 'lang', 'theme', 'favorites', 'hidden', 'completed', 'collections', 'recent', 'plays', 'onboarded', 'skipped', 'sort', 'keep']);

app.whenReady().then(() => {
  const dir = app.getPath('userData');
  store = new Store(dir);
  if (!store.get('lang')) store.set('lang', app.getLocale().toLowerCase().startsWith('es') ? 'es' : 'en');
  t = I18N.make(store.get('lang'));
  drive = new Drive({ dir, store, apiId: API_ID, apiHash: String(config.apiHash || '').trim(), version: app.getVersion(), t });
  player = new Player({ dir, resources: app.isPackaged ? process.resourcesPath : path.join(__dirname, 'res'), t: (s) => t(s),
    onExit: (info) => {
      if (info.played > 0) { const pt = store.get('playtime') || {}; pt[info.id] = (pt[info.id] || 0) + info.played; store.set('playtime', pt); }
      send('played', Object.assign(info, { playtime: store.get('playtime') || {} }));
      if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); }
    } });
  drive.on('state', (changed) => send('state', { drive: drive.snapshot(), games: changed ? store.games() : null }));
  drive.on('file', (info) => { noteFile(info); send('file', info); });

  ipcMain.handle('init', () => ({
    version: app.getVersion(), prefs: store.prefs.data, games: store.games(), drive: drive.snapshot(),
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
        return true;
      }
      default: return null;
    }
  });
  ipcMain.handle('play', async (_e, game, opts) => {
    try {
      if (!player.supported(game.console)) return { error: t('This console arrives in a later update.') };
      let file = (store.get('kept') || {})[game.id];
      if (!file || !fs.existsSync(file)) { const i = await drive.inspect(game.fileId); file = i.path; }
      if (!file || !fs.existsSync(file)) return { error: t('Download the game first.') };
      await player.play(game, file, Object.assign({}, store.prefs.data, opts || {}));
      return { ok: true };
    } catch (e) { return { error: String(e.message || e) }; }
  });
  ipcMain.handle('qr', async (_e, text) => require('qrcode').toDataURL(String(text || ''), { margin: 1, width: 400 }));
  ipcMain.handle('open', (_e, url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });
  ipcMain.handle('fullscreen', () => { if (win) win.setFullScreen(!win.isFullScreen()); });
  ipcMain.handle('quit', () => app.quit());

  createWindow();
  drive.start();
});

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.on('window-all-closed', async () => { store && store.flush(); if (drive) await drive.close(); app.quit(); });
