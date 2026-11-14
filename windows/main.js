'use strict';
// RetroStream for Windows: the app window, settings, and the Telegram connection.
const { app, BrowserWindow, ipcMain, shell, dialog, clipboard, net, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const { Store } = require('./lib/store');
const { Drive } = require('./lib/telegram');
const { Player } = require('./lib/player');
const { Cloud } = require('./lib/cloud');
const { External } = require('./lib/external');
const { Meta } = require('./lib/meta');
const { LocalGames } = require('./lib/localscan');
const { Friends } = require('./lib/social');
const { LibrarySync } = require('./lib/sync');
const { Remote, brief } = require('./lib/remote');
const catalog = require('./lib/catalog');
const I18N = require('./renderer/i18n');

const API_ID = 38798734;
let config = {};
try { config = JSON.parse(fs.readFileSync(path.join(__dirname, 'config.json'), 'utf8')); } catch (_) { /* local build without key */ }

if (!app.requestSingleInstanceLock()) { app.quit(); }

let librarySync = null, remote = null, win = null, store = null, drive = null, player = null, cloud = null, external = null, meta = null, local = null, friends = null, t = (s) => s;
/** Telegram games plus the ones found in the PC folders the user chose. */
function allGames() { return store.games().concat(local ? local.games() : []); }
/** The games as the screens show them (names, covers and descriptions from the lookup). */
function shownGames() { return allGames().map((g) => meta.apply(g)); }
let metaTimer = null;
/** A desktop shortcut starts RetroStream with "--play <game id>". */
let launchId = (() => { const i = process.argv.indexOf('--play'); return i > 0 ? process.argv[i + 1] : ''; })();
function lookUp() { meta.refresh(allGames(), () => { clearTimeout(metaTimer); metaTimer = setTimeout(() => send('state', { drive: drive.snapshot(), games: shownGames() }), 400); }); }
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
    if (info.state === 'none' && kept[g.id] && !(store.get('pcLinked') || {})[g.id]) { delete kept[g.id]; changed = true; }
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

const PREF_KEYS = new Set(['fullscreen', 'lang', 'theme', 'favorites', 'hidden', 'completed', 'collections', 'recent', 'plays', 'onboarded', 'skipped', 'sort', 'keep',
  'homeTheme', 'resolution', 'gameSettings', 'palette', 'dark', 'logoStyle', 'sections', 'shownConsoles', 'swapAB', 'playerWindowed', 'queue', 'later', 'unrecent']);
const SYNCED_KEYS = new Set(['favorites', 'hidden', 'completed', 'collections', 'plays', 'later', 'unrecent', 'gameSettings', 'shownConsoles', 'sections']);

/**
 * Controllers for the menus: rs-player.exe in "--pads 1" mode reads them with SDL (PS5 / PS4 over Bluetooth, Switch Pro…),
 * which works where the browser's own controller support doesn't. Paused while a game runs (the game reads them itself).
 */
let padHelper = null, padRestarts = 0;
function startPadHelper() {
  if (process.platform !== 'win32' || padHelper) return;
  const exe = path.join(process.resourcesPath || '', 'player', 'rs-player.exe');
  if (!fs.existsSync(exe)) return;
  try {
    const child = require('child_process').spawn(exe, ['--pads', '1'], { cwd: path.dirname(exe), windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] });
    padHelper = child; let buf = '';
    child.stdout.on('data', (d) => {
      buf += d.toString(); let nl;
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.substring(0, nl).trim(); buf = buf.substring(nl + 1);
        let m; try { m = JSON.parse(line); } catch (_) { continue; }
        if (!m.held) continue;
        const busy = (player && player.child) || (external && external.child) || !win || !win.isFocused();
        send('pad', busy ? [] : m.held);
      }
    });
    child.on('exit', () => { padHelper = null; if (padRestarts++ < 5 && !app.isQuitting) setTimeout(startPadHelper, 3000); });
    child.on('error', () => { padHelper = null; });
  } catch (_) { padHelper = null; }
}
/** Folder sizes for Settings > Space used. */
async function dirSize(d) {
  let n = 0; let list; try { list = await fs.promises.readdir(d, { withFileTypes: true }); } catch (_) { return 0; }
  for (const f of list) { const p = path.join(d, f.name); try { if (f.isDirectory()) n += await dirSize(p); else if (f.isFile()) n += (await fs.promises.stat(p)).size; } catch (_) { /* gone */ } }
  return n;
}
/** After a PC folder scan: the Telegram games found on the PC count as downloaded (their files are never deleted by RetroStream). */
function applyLinks(before) {
  const kept = store.get('kept') || {}; const now = store.get('pcLinked') || {};
  for (const [id, p] of Object.entries(before || {})) if (!now[id] && kept[id] === p) delete kept[id];
  for (const [id, p] of Object.entries(now)) kept[id] = p;
  store.set('kept', kept); send('kept', kept);
}
/** What the phone remote can change for this game while it plays (resolution restarts it for a moment). */
function remoteSettings(game, ext) {
  if (ext) return {};
  const own = (store.get('gameSettings') || {})[game.id] || {};
  const flat = ['NES', 'SNES', 'MD', 'GB', 'GBC', 'GBA'].includes(game.console);
  const res = own.res || (store.get('resolution') || {})[game.console] || 'medium';
  return {
    res: ['MAME', 'NDS'].includes(game.console) ? '' : res, resOptions: ['MAME', 'NDS'].includes(game.console) ? [] : ['low', 'medium', 'high'],
    filter: ({ sharp: 'sharp', lcd: 'sharp', crt: 'sharp', smooth: 'smooth', smooth2: 'smooth' })[own.filter] || (flat ? (res === 'high' ? 'smooth' : 'sharp') : 'smooth'), filters: ['sharp', 'smooth']
  };
}
function gameById(id) { return allGames().find((g) => g.id === id); }

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
      if (librarySync) librarySync.soon(5000);
      if (friends) friends.inMenu();
      if (remote) {
        remote.stopped();
        if (remote.relaunch && remote.relaunch === info.id) { const id = remote.relaunch; remote.relaunch = null; const g0 = gameById(id); if (g0) remote.pending = { game: brief(g0), at: Date.now() }; setTimeout(() => send('launch', { id }), 900); }
      }
      if (info.entry) { const m = store.get('saveOf') || {}; m[info.id] = info.entry; store.set('saveOf', m); }
      // "Keep downloaded games" off: the game file is deleted after playing (it stays in Telegram).
      const g = gameById(info.id);
      if (store.get('keep') === false && g && !g.local && !(remote && remote.pending && remote.pending.game.id === g.id) && !(store.get('pcLinked') || {})[g.id]) {
        setTimeout(async () => {
          await drive.remove(g); const kept = store.get('kept') || {};
          if (kept[g.id]) { try { fs.unlinkSync(kept[g.id]); } catch (_) { /* gone */ } delete kept[g.id]; store.set('kept', kept); send('kept', kept); }
          for (const d of ['unpacked', 'parts']) fs.rmSync(path.join(app.getPath('userData'), d, g.id.replace(/[^A-Za-z0-9._-]/g, '_')), { recursive: true, force: true });
        }, 1500);
      }
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
  local = new LocalGames({ dir, store });
  remote = new Remote({ store, version: app.getVersion() });
  remote.start();
  startPadHelper();
  player.onState = (st) => { remote.state = st; };
  player.onShot = () => { cloud.uploadShots().catch(() => {}); };
  remote.on('code', (c) => send('remote', { code: c }));
  remote.on('paired', (name) => toast(t('Phone paired:') + ' ' + name));
  remote.on('play', (req, done) => {
    const want = req.game || {}; const all = allGames();
    const g = all.find((x) => x.id === want.id)
      || all.find((x) => want.chatId && x.chatId === want.chatId && x.messageId === want.messageId)
      || all.find((x) => x.console === want.console && want.fileName && x.fileName === want.fileName);
    if (!g) { done({ error: 'not found' }); return; }
    // A game already running closes first (its save is kept), then the new one starts.
    const start = () => { remote.pending = { game: brief(g), at: Date.now() }; send('launch', { id: g.id, fresh: !!req.fresh }); if (win) { if (win.isMinimized()) win.restore(); win.show(); } done({ ok: true }); };
    if (player.child) { player.command('exit'); setTimeout(start, 2500); }
    else if (external.child) { try { external.child.kill(); } catch (_) { /* gone */ } setTimeout(start, 2500); }
    else start();
  });
  remote.on('control', async (req, done) => {
    const a = String(req.action || ''); const slot = Math.max(1, Math.min(3, Number(req.slot) || 1));
    if (!remote.now) { done({ error: 'not playing' }); return; }
    if (external.child) {
      if (a === 'exit') { try { external.child.kill(); } catch (_) { /* gone */ } done({ ok: true }); }
      else done({ error: 'not available' });
      return;
    }
    if (!player.child) { done({ error: 'not playing' }); return; }
    if (['pause', 'resume', 'shot', 'ff', 'reset', 'exit'].includes(a)) { await player.command(a); done({ ok: true }); return; }
    if (a === 'cheats') { const r = await player.command('cheats'); done(r.ok ? { ok: true, list: r.list || [] } : { error: 'failed' }); return; }
    if (a === 'cheat') { const r = await player.command('cheat ' + Math.max(0, Number(req.slot) || 0) + ' ' + (req.value === 'on' ? 'on' : 'off')); done(r.ok ? { ok: true } : { error: 'failed' }); return; }
    if (a === 'cheatsoff') { const r = await player.command('cheatsoff'); done(r.ok ? { ok: true } : { error: 'failed' }); return; }
    if (a === 'frame') {
      const f = path.join(app.getPath('temp'), 'retrostream-live.jpg');
      const r = await player.command('frame ' + f);
      if (!r.ok) { done({ error: 'no picture' }); return; }
      try { done({ ok: true, jpg: fs.readFileSync(f).toString('base64') }); } catch (_) { done({ error: 'no picture' }); }
      return;
    }
    if (a === 'filter' || a === 'res') {
      const v = String(req.value || ''); const id = remote.now.game.id;
      if (a === 'filter' && !['sharp', 'smooth'].includes(v)) { done({ error: 'unknown action' }); return; }
      if (a === 'res' && !['low', 'medium', 'high'].includes(v)) { done({ error: 'unknown action' }); return; }
      const all = store.get('gameSettings') || {}; all[id] = Object.assign({}, all[id], { [a]: v }); store.set('gameSettings', all); if (librarySync) librarySync.soon(8000);
      if (a === 'filter') { await player.command('filter ' + v); remote.now.settings.filter = v; done({ ok: true }); return; }
      // Resolution: the engine reads it when the game starts, so the game closes (saving where it is) and opens again.
      remote.now.settings.res = v; remote.relaunch = id;
      await player.command('exit'); done({ ok: true, restarting: true }); return;
    }
    if (a === 'save' || a === 'load') { const r = await player.command(a + ' ' + slot); done(r.ok ? { ok: true } : { error: a === 'load' ? 'empty slot' : 'failed' }); return; }
    if (a === 'backup') {
      await player.command('flush');
      const e = (store.get('saveOf') || {})[remote.now.game.id];
      if (!e || drive.stage !== 'ready') { done({ error: 'no telegram' }); return; }
      const err = await cloud.upload(e, true).catch((x) => String(x.message || x)); done(err ? { error: String(err) } : { ok: true }); return;
    }
    done({ error: 'unknown action' });
  });
  friends = new Friends({ drive, store, t: (s) => t(s) });
  friends.on('changed', () => send('friends', friends.snapshot()));
  librarySync = new LibrarySync({ dir: app.getPath('userData'), drive, store, meta, onChange: (p) => send('prefs', p), onGames: () => send('state', { drive: drive.snapshot(), games: shownGames() }) });
  let wasReady = false;
  drive.on('state', () => { if (drive.stage === 'ready' && !wasReady) { wasReady = true; setTimeout(() => { friends.discover().catch(() => {}).then(() => { friends.load(); friends.inMenu(); }); librarySync.soon(2000); }, 4000); } });
  drive.on('state', (changed) => { send('state', { drive: drive.snapshot(), games: changed ? shownGames() : null }); if (changed) lookUp(); });
  drive.on('file', (info) => { noteFile(info); send('file', info); });

  ipcMain.handle('init', () => ({
    version: app.getVersion(), prefs: store.prefs.data, games: shownGames(), drive: drive.snapshot(), launch: launchId, friends: friends.snapshot(),
    folders: local.folders(), unsorted: local.unsorted().length, paths: { downloads: path.join(dir, 'telegram', 'files'), pictures: path.join(app.getPath('pictures'), 'RetroStream') },
    consoles: catalog.CONSOLES.map((c) => ({ id: c.id, title: c.title, thumbs: c.thumbs, color: c.color, big: !!c.big }))
  }));
  ipcMain.handle('prefs:set', (_e, k, v) => { if (PREF_KEYS.has(k)) { store.set(k, v); if (SYNCED_KEYS.has(k) && librarySync) librarySync.soon(4000); if (k === 'lang') t = drive.t = I18N.make(v); } return true; });
  ipcMain.handle('drive', async (_e, action, ...args) => {
    switch (action) {
      case 'qr': drive.requestQr(); break;
      case 'phone': return { result: await drive.phone(args[0]), drive: drive.snapshot() };
      case 'code': return { result: await drive.code(args[0]), drive: drive.snapshot() };
      case 'password': return { result: await drive.password(args[0]), drive: drive.snapshot() };
      case 'proxy': return { result: await drive.setProxy(args[0]), drive: drive.snapshot() };
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
        const links = store.get('pcLinked') || {};
        if (game.local || links[game.id]) return { error: t('This game is your own file in a PC folder; RetroStream does not delete it.') };
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
      let file = game.local ? game.path : (store.get('kept') || {})[game.id];
      if (!game.local && (!file || !fs.existsSync(file))) { const i = await drive.inspect(game.fileId); file = i.path; }
      if (!file || !fs.existsSync(file)) return { error: t('Download the game first.') };
      const tracksHere = (cue) => { try { return [...fs.readFileSync(cue, 'utf8').matchAll(/FILE\s+"?([^"\r\n]+?)"?\s+(BINARY|MOTOROLA|WAVE|MP3)/gi)].every((m) => fs.existsSync(path.join(path.dirname(cue), m[1]))); } catch (_) { return false; } };
      if (file.toLowerCase().endsWith('.cue') && !tracksHere(file)) file = await withTracks(game, file);
      if (/\.(7z|zip)\.0*1$/i.test(file)) file = await withParts(game, file);
      if (ext && (game.console === 'PS3' || game.console === 'SWITCH')) toast(t('Preparing the game… (big games take a while the first time)'));
      const prep = await player.prepare(game, file, !!ext);
      const entry = prep.entry || (ext ? external.sharedEntry(game.console) : null);
      if (drive.stage === 'ready' && entry) {
        // The newest save from the phone, TV or another PC first (but never wait more than ~8 seconds for it).
        const pulled = await Promise.race([cloud.pullNewest(entry).catch(() => ''), new Promise((r) => setTimeout(() => r(''), 8000))]);
        if (pulled) toast(pulled);
      }
      if (entry) { const m = store.get('saveOf') || {}; m[game.id] = entry; store.set('saveOf', m); }
      friends.playing(game);
      remote.playing(game, ext ? ['exit'] : ['pause', 'resume', 'save', 'load', 'shot', 'ff', 'reset', 'backup', 'exit', 'frame', 'res', 'filter'].concat(game.console === 'MAME' ? [] : ['cheats']), remoteSettings(game, !!ext));
      if (ext) { await external.launch(prep, Object.assign({}, store.prefs.data, opts || {}), afterGame); return { ok: true }; }
      const cheats = await Promise.race([cloud.cheats(game).catch(() => null), new Promise((r) => setTimeout(() => r(null), 6000))]);
      await player.launch(prep, Object.assign({}, store.prefs.data, opts || {}), cheats);
      return { ok: true };
    } catch (e) { if (remote) { remote.pending = null; remote.stopped(); } return { error: String(e.message || e) }; }
  });
  ipcMain.handle('remote', async (_e, action, arg) => {
    switch (action) {
      case 'on': store.set('remoteOn', !!arg); break;
      case 'name': if (arg) store.set('remoteName', String(arg).substring(0, 40)); break;
      case 'forget': remote.forget(); break;
      default: break;
    }
    const ips = []; for (const list of Object.values(require('os').networkInterfaces())) for (const n of list || []) if (n.family === 'IPv4' && !n.internal) ips.push(n.address);
    return { on: remote.enabled(), name: remote.name(), phones: Object.values(remote.phones()).map((p) => p.name), error: remote.error || '', ips };
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
    if (librarySync && action !== 'get') librarySync.soon(6000);
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
  ipcMain.handle('local', async (_e, action, arg, arg2) => {
    try {
      switch (action) {
        case 'add': {
          const r = await dialog.showOpenDialog(win, { title: t('Choose a folder with games'), properties: ['openDirectory', 'multiSelections'] });
          if (r.canceled || !r.filePaths.length) return null;
          local.setFolders(local.folders().concat(r.filePaths)); break;
        }
        case 'remove': local.setFolders(local.folders().filter((f) => f !== arg)); break;
        case 'assign': local.assign(arg, arg2); break;
        case 'unsorted': return { list: local.unsorted() };
        case 'scan': break;
        default: return null;
      }
      if (action === 'unsorted') return null;
      const before = Object.assign({}, store.get('pcLinked') || {});
      const r = await local.scan(store.games(), (n) => toast(t('Searching your folders…') + ' ' + n));
      applyLinks(before);
      send('state', { drive: drive.snapshot(), games: shownGames() }); lookUp();
      return { ok: true, result: r, folders: local.folders(), unsorted: local.unsorted().length, links: store.get('pcLinked') || {} };
    } catch (e) { return { error: String(e.message || e) }; }
  });
  ipcMain.handle('friends', async (_e, action, arg) => {
    try {
      switch (action) {
        case 'get': break;
        case 'create': await friends.create(); break;
        case 'join': await friends.join(arg); break;
        case 'leave': friends.leave(); break;
        case 'share': friends.setShare(!!arg); break;
        case 'refresh': await friends.discover().catch(() => {}); await friends.load(); break;
        case 'tell': await friends.tell(arg); break;
        default: break;
      }
      return friends.snapshot();
    } catch (e) { return { error: String(e.message || e), friends: friends.snapshot() }; }
  });
  ipcMain.handle('tools', async (_e, action, arg) => {
    const dir = app.getPath('userData');
    try {
      switch (action) {
        case 'openDownloads': { const d = path.join(dir, 'telegram', 'files'); fs.mkdirSync(d, { recursive: true }); return { error: await shell.openPath(d) || undefined }; }
        case 'openFolder': {
          const where = { saves: path.join(dir, 'saves'), pictures: path.join(app.getPath('pictures'), 'RetroStream'), app: dir, bios: path.join(dir, 'bios') }[arg] || arg;
          if (!where || !fs.existsSync(where)) { if (where) fs.mkdirSync(where, { recursive: true }); else return { error: t('That folder does not exist.') }; }
          return { error: await shell.openPath(where) || undefined };
        }
        case 'showFile': if (arg && fs.existsSync(arg)) shell.showItemInFolder(arg); return { ok: true };
        case 'space': {
          const parts = { downloads: path.join(dir, 'telegram', 'files'), unpacked: path.join(dir, 'unpacked'), parts: path.join(dir, 'parts'), saves: path.join(dir, 'saves'), engines: path.join(dir, 'engines'), shots: path.join(dir, 'screenshots'), cheats: path.join(dir, 'cheats') };
          const out = {}; for (const [k, d] of Object.entries(parts)) out[k] = await dirSize(d);
          out.covers = 0; try { out.covers = await win.webContents.session.getCacheSize(); } catch (_) { /* unknown */ }
          return out;
        }
        case 'free': {
          if (arg === 'unpacked' || arg === 'parts' || arg === 'cheats') fs.rmSync(path.join(dir, arg), { recursive: true, force: true });
          if (arg === 'covers') await win.webContents.session.clearCache();
          if (arg === 'downloads') {
            const links = store.get('pcLinked') || {}; const kept = store.get('kept') || {};
            for (const g of store.games()) if (kept[g.id] && !links[g.id]) { await drive.remove(g); try { fs.unlinkSync(kept[g.id]); } catch (_) { /* gone */ } delete kept[g.id]; }
            fs.rmSync(path.join(dir, 'unpacked'), { recursive: true, force: true }); fs.rmSync(path.join(dir, 'parts'), { recursive: true, force: true });
            store.set('kept', kept); send('kept', kept);
          }
          return { ok: true };
        }
        case 'copy': clipboard.writeText(String(arg || '')); return { ok: true };
        case 'link': { const g = arg; if (!g || g.local) return { error: t('This game is only on this PC.') }; return { link: await drive.messageLink(g.chatId, g.messageId) }; }
        case 'shareFriend': {
          const g = arg; const chat = friends.chat(); if (!chat) return { error: t('First create or join the friends group.') };
          if (g.local) await friends.tell('🎮 ' + g.title + ' · ' + g.console);
          else { try { await drive.forward(chat, g.chatId, g.messageId); } catch (_) { const link = await drive.messageLink(g.chatId, g.messageId); await friends.tell('🎮 ' + g.title + ' · ' + g.console + (link ? '\n' + link : '')); } }
          return { ok: true };
        }
        case 'shortcut': {
          const g = arg; const name = g.title.replace(/[\\/:*?"<>|]/g, '') + '.lnk';
          const ok = shell.writeShortcutLink(path.join(app.getPath('desktop'), name), { target: process.execPath, args: '--play "' + g.id + '"', description: 'RetroStream · ' + g.title, icon: process.execPath, iconIndex: 0 });
          return ok ? { ok: true } : { error: t('The shortcut could not be made.') };
        }
        case 'shots': return { list: await cloud.shots(arg) };
        case 'backupNow': {
          const e = (store.get('saveOf') || {})[arg]; if (!e || !fs.existsSync(e.root)) return { error: t('Play the game once on this PC first.') };
          return { error: (await cloud.upload(e, true)) || undefined };
        }
        case 'emptyGroups': return { list: await drive.emptyGroups([friends.chat()]) };
        case 'deleteGroups': return { n: await drive.deleteChats(arg || []) };
        case 'downloadMany': { let n = 0; for (const g of arg || []) { try { await drive.download(g); n++; } catch (_) { /* skip */ } } return { n }; }
        case 'report': {
          let log = ''; try { log = fs.readFileSync(path.join(dir, 'last-player.log'), 'utf8').split(/\r?\n/).slice(-120).join('\n'); } catch (_) { log = t('No game has failed yet.'); }
          const tail = (f, n) => { try { return fs.readFileSync(path.join(dir, f), 'utf8').split(/\r?\n/).slice(-n).join('\n'); } catch (_) { return ''; } };
          log += '\n\n--- Telegram (' + drive.stage + ', ' + drive.net + (drive.proxy ? ', proxy' : '') + ', ' + new Date().toString() + ') ---\n' + tail('retrostream-telegram.log', 40) + '\n' + tail('telegram.log', 60);
          const os = require('os');
          const text = 'RetroStream ' + app.getVersion() + ' · Windows ' + os.release() + ' · ' + os.cpus()[0].model + ' · ' + Math.round(os.totalmem() / 1073741824) + ' GB\n'
            + 'GPU: ' + (await app.getGPUInfo('basic').then((g) => (g.gpuDevice || []).map((d) => d.deviceString || (d.vendorId + ':' + d.deviceId)).join(', ')).catch(() => '?')) + '\n\n' + log;
          const file = path.join(app.getPath('desktop'), 'RetroStream-report.txt'); fs.writeFileSync(file, text); clipboard.writeText(text);
          return { text, file };
        }
        case 'updates': {
          const r = await net.fetch('https://api.github.com/repos/dioniciogarcia/retrostream/releases/tags/windows-latest', { headers: { 'User-Agent': 'RetroStream' } });
          if (!r.ok) return { error: t('Could not reach GitHub. Check your internet connection.') };
          const rel = await r.json(); const m = /build:(\d+)/.exec(rel.body || ''); const latest = m ? Number(m[1]) : 0; const mine = Number(app.getVersion().split('.')[2] || 0);
          const asset = (rel.assets || []).find((a) => /Setup\.exe$/i.test(a.name));
          return { latest, mine, newer: latest > mine, url: asset ? asset.browser_download_url : '', date: rel.published_at };
        }
        case 'install': {
          const url = String(arg || ''); if (!/^https:\/\/github\.com\//.test(url)) return { error: 'bad link' };
          toast(t('Downloading the update…'));
          const r = await net.fetch(url); if (!r.ok) return { error: t('The update could not be downloaded.') };
          const out = path.join(app.getPath('temp'), 'RetroStream-Setup.exe');
          await require('stream/promises').pipeline(require('stream').Readable.fromWeb(r.body), fs.createWriteStream(out));
          await shell.openPath(out); setTimeout(() => app.quit(), 1500);
          return { ok: true };
        }
        case 'allShots': if (drive.stage !== 'ready') return { error: t('Connect Telegram first (Settings > TirsoDrive).') }; return await cloud.allShots(arg || 0, 48);
        case 'fullShot': return await cloud.fullShot(arg);
        case 'shotCopy': clipboard.writeImage(nativeImage.createFromPath(arg.path)); return { ok: true };
        case 'shotSave': {
          const r = await dialog.showSaveDialog(win, { title: t('Save picture as'), defaultPath: path.join(app.getPath('pictures'), (arg.title || 'RetroStream').replace(/[\\/:*?"<>|]/g, '') + ' ' + new Date((arg.date || 0) * 1000).toISOString().substring(0, 10) + '.jpg'), filters: [{ name: 'JPEG', extensions: ['jpg'] }] });
          if (r.canceled || !r.filePath) return null; fs.copyFileSync(arg.path, r.filePath); return { ok: true };
        }
        case 'shotFriends': await friends.sendPhoto(arg.path, arg.w, arg.h, '📸 ' + (arg.title || '')); return { ok: true };
        case 'walkthrough': shell.openExternal('https://www.google.com/search?q=' + encodeURIComponent(arg + ' walkthrough guide')); return { ok: true };
        default: return null;
      }
    } catch (e) { return { error: String(e.message || e) }; }
  });
  ipcMain.handle('qr', async (_e, text) => require('qrcode').toDataURL(String(text || ''), { margin: 1, width: 400 }));
  ipcMain.handle('open', (_e, url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });
  ipcMain.handle('fullscreen', () => { if (win) win.setFullScreen(!win.isFullScreen()); });
  ipcMain.handle('quit', () => app.quit());

  createWindow();
  drive.start();
  setTimeout(lookUp, 3000);
});

app.on('second-instance', (_e, argv) => {
  if (win) { if (win.isMinimized()) win.restore(); win.focus(); }
  const i = argv.indexOf('--play'); if (i > 0 && argv[i + 1]) send('launch', argv[i + 1]);
});
app.on('window-all-closed', async () => {
  app.isQuitting = true; if (padHelper) { try { padHelper.kill(); } catch (_) { /* gone */ } }
  store && store.flush();
  if (remote) remote.close();
  if (friends) await Promise.race([friends.offline().catch(() => {}), new Promise((r) => setTimeout(r, 2500))]);
  if (drive) await drive.close(); app.quit();
});
