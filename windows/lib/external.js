'use strict';
// Consoles played in their own Windows programs: GameCube and Wii (Dolphin), PS3 (RPCS3), Switch (Eden).
// RetroStream installs and updates them, sets them up, opens the game in them and backs up their saves.
const fs = require('fs');
const path = require('path');
const https = require('https');
const { execFile, spawn } = require('child_process');

function seven() { return require('7zip-bin').path7za.replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep); }
function run7(args, cwd) { return new Promise((res, rej) => execFile(seven(), args, { cwd, windowsHide: true, maxBuffer: 64 * 1024 * 1024 }, (e) => (e ? rej(e) : res()))); }

/** GET with redirects; text, or a file when [file] is given. */
function get(url, file, progress, depth = 0) {
  return new Promise((resolve, reject) => {
    if (depth > 6) { reject(new Error('Too many redirects')); return; }
    const req = https.get(url, { headers: { 'User-Agent': 'RetroStream', Accept: file ? '*/*' : 'application/json, text/html' }, timeout: 30000 }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) { res.resume(); resolve(get(new URL(res.headers.location, url).toString(), file, progress, depth + 1)); return; }
      if (res.statusCode !== 200) { res.resume(); reject(new Error('HTTP ' + res.statusCode + ' · ' + url)); return; }
      if (!file) { let d = ''; res.setEncoding('utf8'); res.on('data', (c) => { d += c; }); res.on('end', () => resolve(d)); return; }
      const total = Number(res.headers['content-length'] || 0); let have = 0;
      const out = fs.createWriteStream(file);
      res.on('data', (c) => { have += c.length; if (progress) progress(have, total); });
      res.pipe(out); out.on('finish', () => out.close(() => resolve(file))); out.on('error', reject);
    });
    req.on('error', reject); req.on('timeout', () => req.destroy(new Error('timeout')));
  });
}
function findFile(dir, name) {
  if (!fs.existsSync(dir)) return null;
  for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, f.name);
    if (f.isFile() && f.name.toLowerCase() === name.toLowerCase()) return p;
  }
  for (const f of fs.readdirSync(dir, { withFileTypes: true })) if (f.isDirectory()) { const r = findFile(path.join(dir, f.name), name); if (r) return r; }
  return null;
}
function junction(link, target) {
  fs.mkdirSync(target, { recursive: true }); fs.mkdirSync(path.dirname(link), { recursive: true });
  try { const st = fs.lstatSync(link); if (st.isSymbolicLink()) return; fs.rmSync(link, { recursive: true, force: true }); } catch (_) { /* not there */ }
  try { fs.symlinkSync(target, link, 'junction'); } catch (_) { /* rare: leave it */ }
}

const ENGINES = {
  dolphin: {
    title: 'Dolphin', consoles: ['GC', 'WII'], exe: 'Dolphin.exe',
    async url() {
      try {
        const html = await get('https://dolphin-emu.org/download/');
        const m = /https:\/\/dl\.dolphin-emu\.org\/releases\/[0-9a-z]+\/dolphin-[0-9a-z]+-x64\.7z/i.exec(html);
        if (m) return m[0];
      } catch (_) { /* use the known release */ }
      return 'https://dl.dolphin-emu.org/releases/2609/dolphin-2609-x64.7z';
    }
  },
  rpcs3: {
    title: 'RPCS3', consoles: ['PS3'], exe: 'rpcs3.exe',
    async url() {
      const r = JSON.parse(await get('https://api.github.com/repos/RPCS3/rpcs3-binaries-win/releases/latest'));
      const a = r.assets.find((x) => /win64.*\.7z$/i.test(x.name)) || r.assets.find((x) => /\.7z$/i.test(x.name));
      if (!a) throw new Error('RPCS3 download not found');
      return a.browser_download_url;
    }
  },
  eden: {
    title: 'Eden', consoles: ['SWITCH'], exe: 'eden.exe',
    async url() {
      const r = JSON.parse(await get('https://git.eden-emu.dev/api/v1/repos/eden-emu/eden/releases/latest'));
      const assets = r.assets || [];
      const a = assets.find((x) => /Windows.*amd64-msvc-standard\.zip$/i.test(x.name)) || assets.find((x) => /Windows.*amd64.*\.zip$/i.test(x.name));
      if (!a) throw new Error('Eden download not found');
      return a.browser_download_url;
    }
  }
};
const FOR_CONSOLE = { GC: 'dolphin', WII: 'dolphin', PS3: 'rpcs3', SWITCH: 'eden' };

class External {
  constructor({ dir, store, t }) { Object.assign(this, { dir, store, t }); this.child = null; this.installing = {}; }
  engineFor(consoleId) { return FOR_CONSOLE[consoleId] || null; }
  home(name) { return path.join(this.dir, 'engines', name); }
  info(name) { return (this.store.get('engines') || {})[name] || null; }
  exe(name) { const i = this.info(name); return i && fs.existsSync(i.exe) ? i.exe : null; }
  status() {
    const out = {};
    for (const [name, e] of Object.entries(ENGINES)) {
      const exe = this.exe(name);
      out[name] = { title: e.title, installed: !!exe, version: exe ? this.info(name).version : '', installing: !!this.installing[name] };
    }
    const rp = this.exe('rpcs3'), ed = this.exe('eden');
    out.ps3fw = !!rp && fs.existsSync(path.join(path.dirname(rp), 'dev_flash', 'vsh', 'module', 'vsh.self'));
    const user = ed ? path.join(path.dirname(ed), 'user') : path.join(this.home('eden'), 'none');
    out.switchKeys = fs.existsSync(path.join(user, 'keys', 'prod.keys'));
    out.switchFw = fs.existsSync(path.join(user, 'nand', 'system', 'Contents', 'registered')) && fs.readdirSync(path.join(user, 'nand', 'system', 'Contents', 'registered')).length > 10;
    return out;
  }

  /** The Microsoft Visual C++ runtime these programs need (installed once, with Windows' own prompt). */
  async runtime(progress) {
    const sys = process.env.SystemRoot ? path.join(process.env.SystemRoot, 'System32') : 'C:\\Windows\\System32';
    if (['vcruntime140_1.dll', 'msvcp140.dll', 'msvcp140_atomic_wait.dll'].every((f) => fs.existsSync(path.join(sys, f)))) return;
    progress && progress(this.t('Installing the Microsoft Visual C++ runtime…'));
    const file = path.join(this.dir, 'engines', 'vc_redist.x64.exe'); fs.mkdirSync(path.dirname(file), { recursive: true });
    await get('https://aka.ms/vs/17/release/vc_redist.x64.exe', file);
    await new Promise((res) => execFile(file, ['/install', '/passive', '/norestart'], () => res()));
  }

  /** Downloads (or updates) one program into RetroStream's folder. */
  async install(name, progress) {
    const e = ENGINES[name]; if (!e) throw new Error('unknown engine');
    if (this.installing[name]) throw new Error(this.t('Already installing…'));
    this.installing[name] = true;
    try {
      await this.runtime(progress);
      const url = await e.url();
      const tmp = path.join(this.dir, 'engines', 'download-' + name + path.extname(url));
      fs.mkdirSync(path.dirname(tmp), { recursive: true });
      await get(url, tmp, (have, total) => progress && progress(e.title + ' · ' + Math.round(have / 1048576) + (total ? ' / ' + Math.round(total / 1048576) : '') + ' MB'));
      progress && progress(this.t('Unpacking') + ' ' + e.title + '…');
      const app = path.join(this.home(name), 'app'); fs.mkdirSync(app, { recursive: true });
      await run7(['x', '-y', '-o' + app, tmp]);
      fs.rmSync(tmp, { force: true });
      const exe = findFile(app, e.exe);
      if (!exe) throw new Error(e.title + ': ' + e.exe + ' ' + this.t('not found in the download'));
      // Portable mode: Dolphin and Eden keep their settings next to the program (RPCS3 always does).
      if (name === 'dolphin') fs.writeFileSync(path.join(path.dirname(exe), 'portable.txt'), '');
      if (name === 'eden') fs.mkdirSync(path.join(path.dirname(exe), 'user'), { recursive: true });
      const all = this.store.get('engines') || {}; all[name] = { exe, version: path.basename(url), at: Date.now() }; this.store.set('engines', all);
      return { ok: true };
    } finally { this.installing[name] = false; }
  }
  async ensure(name, progress) { if (!this.exe(name)) await this.install(name, progress); return this.exe(name); }

  // ------------------------------------------------------------------ your own system files
  async ps3Firmware(pup) {
    const exe = this.exe('rpcs3'); if (!exe) throw new Error(this.t('Install RPCS3 first.'));
    await new Promise((res) => execFile(exe, ['--installfw', pup], { cwd: path.dirname(exe) }, () => res()));
    if (!this.status().ps3fw) throw new Error(this.t('RPCS3 did not accept that firmware file. Use the official PS3UPDAT.PUP.'));
  }
  edenUser() { const exe = this.exe('eden'); if (!exe) throw new Error(this.t('Install Eden first.')); return path.join(path.dirname(exe), 'user'); }
  switchKeys(files) {
    const keys = path.join(this.edenUser(), 'keys'); fs.mkdirSync(keys, { recursive: true });
    for (const f of files) fs.copyFileSync(f, path.join(keys, path.basename(f).toLowerCase()));
  }
  async switchFirmware(source) {
    const dest = path.join(this.edenUser(), 'nand', 'system', 'Contents', 'registered'); fs.mkdirSync(dest, { recursive: true });
    let from = source;
    if (/\.zip$/i.test(source)) { from = path.join(this.dir, 'engines', 'fw-tmp'); fs.rmSync(from, { recursive: true, force: true }); await run7(['x', '-y', '-o' + from, source]); }
    let n = 0;
    const walk = (d) => { for (const f of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, f.name); if (f.isDirectory()) walk(p); else if (/\.nca$/i.test(f.name)) { fs.copyFileSync(p, path.join(dest, f.name)); n++; } } };
    walk(from);
    if (from !== source) fs.rmSync(from, { recursive: true, force: true });
    if (!n) throw new Error(this.t('No firmware files (.nca) were found there.'));
    return n;
  }
  /** Opens the program on its own (to change its controls or graphics). */
  openSettings(name) { const exe = this.exe(name); if (!exe) throw new Error(this.t('Install it first.')); spawn(exe, [], { cwd: path.dirname(exe), detached: true, stdio: 'ignore' }).unref(); }

  // ------------------------------------------------------------------ saves (backed up like the other consoles)
  /** PS3 and Switch keep every game's saves in one folder: one backup entry each, linked into RetroStream's saves. */
  sharedEntry(consoleId) {
    const root = path.join(this.dir, 'saves', consoleId.toLowerCase(), consoleId === 'PS3' ? 'ps3-all-games' : 'switch-all-games');
    const exe = this.exe(this.engineFor(consoleId)); if (!exe) return null;
    const target = consoleId === 'PS3' ? path.join(path.dirname(exe), 'dev_hdd0', 'home', '00000001', 'savedata') : path.join(path.dirname(exe), 'user', 'nand', 'user', 'save');
    junction(path.join(root, 'saves'), target);
    const title = consoleId === 'PS3' ? 'PlayStation 3 (all games)' : 'Nintendo Switch (all games)';
    fs.writeFileSync(path.join(root, 'meta.json'), JSON.stringify({ title, console: consoleId }));
    return { root, sha: path.basename(root), title, console: consoleId };
  }

  // ------------------------------------------------------------------ Dolphin's settings for each game's own user folder
  dolphinConfig(userDir, wii, lang, res) {
    const cfg = path.join(userDir, 'Config'); fs.mkdirSync(cfg, { recursive: true });
    const ini = (file, sections) => {
      const p = path.join(cfg, file); let text = fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : '';
      for (const [sec, values] of Object.entries(sections)) {
        for (const [k, v] of Object.entries(values)) {
          const head = '[' + sec + ']'; if (!text.includes(head)) text += (text && !text.endsWith('\n') ? '\n' : '') + head + '\n';
          const start = text.indexOf(head) + head.length; const next = text.indexOf('\n[', start); const end = next < 0 ? text.length : next;
          let block = text.substring(start, end); const re = new RegExp('^' + k.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&') + '\\s*=.*$', 'm');
          block = re.test(block) ? block.replace(re, k + ' = ' + v) : block.replace(/\n*$/, '\n') + k + ' = ' + v + '\n';
          text = text.substring(0, start) + block + text.substring(end);
        }
      }
      fs.writeFileSync(p, text);
    };
    ini('Dolphin.ini', {
      Display: { Fullscreen: 'True', RenderToMain: 'False' },
      Interface: { ConfirmStop: 'False', UsePanicHandlers: 'False', LanguageCode: lang === 'es' ? 'es' : 'en', ShowActiveTitle: 'False' },
      Core: { SIDevice0: '6', WiimoteControllerInterface: 'False' },
      Analytics: { Enabled: 'False', PermissionAsked: 'True' },
      AutoUpdate: { UpdateTrack: '' }
    });
    ini('GFX.ini', { Settings: { AspectRatio: '0', ShowFPS: 'False', InternalResolution: { low: '2', medium: '3', high: '4' }[res] || '3' } });
    const pad = (keys) => keys.map(([k, v]) => k + ' = ' + v).join('\n');
    const dev = 'XInput/0/Gamepad';
    fs.writeFileSync(path.join(cfg, 'GCPadNew.ini'), '[GCPad1]\nDevice = ' + dev + '\n' + pad([
      ['Buttons/A', '`Button A`'], ['Buttons/B', '`Button X`'], ['Buttons/X', '`Button B`'], ['Buttons/Y', '`Button Y`'], ['Buttons/Z', '`Shoulder R`'], ['Buttons/Start', 'Start'],
      ['Main Stick/Up', '`Left Y+`'], ['Main Stick/Down', '`Left Y-`'], ['Main Stick/Left', '`Left X-`'], ['Main Stick/Right', '`Left X+`'],
      ['C-Stick/Up', '`Right Y+`'], ['C-Stick/Down', '`Right Y-`'], ['C-Stick/Left', '`Right X-`'], ['C-Stick/Right', '`Right X+`'],
      ['Triggers/L', '`Trigger L`'], ['Triggers/R', '`Trigger R`'], ['Triggers/L-Analog', '`Trigger L`'], ['Triggers/R-Analog', '`Trigger R`'],
      ['D-Pad/Up', '`Pad N`'], ['D-Pad/Down', '`Pad S`'], ['D-Pad/Left', '`Pad W`'], ['D-Pad/Right', '`Pad E`'], ['Rumble/Motor', '`Motor L`']]) + '\n');
    if (wii) fs.writeFileSync(path.join(cfg, 'WiimoteNew.ini'), '[Wiimote1]\nDevice = ' + dev + '\nSource = 1\nExtension = Nunchuk\n' + pad([
      ['Buttons/A', '`Button A`'], ['Buttons/B', '`Trigger R`'], ['Buttons/1', '`Button X`'], ['Buttons/2', '`Button Y`'], ['Buttons/-', 'Back'], ['Buttons/+', 'Start'], ['Buttons/Home', 'Guide'],
      ['D-Pad/Up', '`Pad N`'], ['D-Pad/Down', '`Pad S`'], ['D-Pad/Left', '`Pad W`'], ['D-Pad/Right', '`Pad E`'],
      ['IR/Up', '`Right Y+`'], ['IR/Down', '`Right Y-`'], ['IR/Left', '`Right X-`'], ['IR/Right', '`Right X+`'], ['Shake/X', '`Shoulder R`'], ['Shake/Y', '`Shoulder R`'], ['Shake/Z', '`Shoulder R`'],
      ['Nunchuk/Stick/Up', '`Left Y+`'], ['Nunchuk/Stick/Down', '`Left Y-`'], ['Nunchuk/Stick/Left', '`Left X-`'], ['Nunchuk/Stick/Right', '`Left X+`'],
      ['Nunchuk/Buttons/C', '`Shoulder L`'], ['Nunchuk/Buttons/Z', '`Trigger L`'], ['Rumble/Motor', '`Motor L`']]) + '\n[Wiimote2]\nSource = 0\n[Wiimote3]\nSource = 0\n[Wiimote4]\nSource = 0\n[BalanceBoard]\nSource = 0\n');
  }

  /** Starts the game in its program and calls [onExit] with the time played when it closes. */
  async launch(prep, prefs, onExit) {
    if (this.child) throw new Error(this.t('A game is already running.'));
    const { game, rom } = prep; const name = this.engineFor(game.console);
    const exe = this.exe(name); if (!exe) throw new Error(this.t('The game program is not installed.'));
    let args;
    if (name === 'dolphin') {
      const user = path.join(prep.root, 'saves', 'User');   // the same folder the Android app's Dolphin uses, so backups match
      const own = (prefs.gameSettings || {})[game.id] || {};
      this.dolphinConfig(user, game.console === 'WII', prefs.lang, own.res || (prefs.resolution || {})[game.console] || 'medium');
      args = ['-b', '-u', user, '-e', rom];
    } else if (name === 'rpcs3') {
      if (!this.status().ps3fw) throw new Error(this.t('PS3 games need your own PS3 firmware. Add it in Settings > GameCube, Wii, PS3 and Switch.'));
      args = ['--no-gui', '--fullscreen', rom];
    } else {
      const s = this.status(); if (!s.switchKeys) throw new Error(this.t('Switch games need your own keys (prod.keys). Add them in Settings > GameCube, Wii, PS3 and Switch.'));
      args = ['-f', '-g', rom];
    }
    const started = Date.now();
    const child = spawn(exe, args, { cwd: path.dirname(exe), stdio: 'ignore', windowsHide: false });
    this.child = child;
    child.on('error', () => {});
    child.on('exit', (code) => {
      this.child = null;
      const played = Date.now() - started;
      onExit({ id: game.id, entry: name === 'dolphin' ? prep.entry : this.sharedEntry(game.console), played: played > 15000 ? played : 0,
        error: played < 5000 && code ? this.t('The game program closed right away. Open it from Settings > PS3 and Switch to check its setup.') : '' });
    });
    return true;
  }
}

module.exports = { External, FOR_CONSOLE, ENGINES };
