'use strict';
// Starts a game: unpacks it if needed, finds its save folder (same layout as the Android app), and runs rs-player.exe.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn, execFile } = require('child_process');

// The same engines as the Android app.
const ENGINES = {
  NES: ['fceumm'], SNES: ['snes9x'], MD: ['genesis_plus_gx'], GB: ['mgba'], GBC: ['mgba'], GBA: ['mgba'],
  N64: ['mupen64plus_next'], NDS: ['melondsds', 'melonds'], MAME: ['mame2003_plus'],
  PSP: ['ppsspp'], PS2: ['pcsx2', 'play']
};
const BIG = new Set(['PS3', 'SWITCH']);   // their own programs; nothing to fingerprint per game
const DISC = new Set(['PSP', 'PS2', 'GC', 'WII']);
const RES = { N64: { low: '640x480', medium: '1280x960', high: '1920x1440' } };
function options(consoleId, res) {
  switch (consoleId) {
    case 'N64': return {
      'mupen64plus-rdp-plugin': 'gliden64', 'mupen64plus-cpucore': 'dynamic_recompiler', 'mupen64plus-aspect': '4:3', 'mupen64plus-pak1': 'memory',
      'mupen64plus-EnableCopyDepthToRDRAM': 'Off', 'mupen64plus-EnableCopyColorToRDRAM': 'Off', 'mupen64plus-ThreadedRenderer': 'False', 'mupen64plus-FrameDuping': 'True',
      'mupen64plus-EnableShadersStorage': 'True', 'mupen64plus-43screensize': RES.N64[res] || RES.N64.medium
    };
    case 'GB': case 'GBC': case 'GBA': return { mgba_use_bios: 'OFF', mgba_skip_bios: 'ON', mgba_idle_optimization: 'Remove Known', mgba_interframe_blending: 'mix_smart', mgba_color_correction: 'OFF', mgba_frameskip: 'disabled' };
    case 'NDS': return {
      melonds_console_mode: 'ds', melonds_sysfile_mode: 'builtin', melonds_boot_mode: 'direct', melonds_touch_mode: 'auto', melonds_show_cursor: 'timeout',
      melonds_render_mode: 'software', melonds_threaded_renderer: 'enabled', melonds_jit_enable: 'enabled', melonds_number_of_screen_layouts: '2',
      melonds_hybrid_ratio: '2', melonds_hybrid_small_screen: 'both', melonds_screen_gap: '0', melonds_screen_layout1: 'hybrid-top', melonds_screen_layout2: 'top-bottom'
    };
    case 'MAME': return { 'mame2003-plus_skip_disclaimer': 'enabled', 'mame2003-plus_skip_warnings': 'enabled' };
    case 'PSP': return { ppsspp_cpu_core: 'JIT', ppsspp_frame_duplication: 'enabled', ppsspp_auto_frameskip: 'disabled', ppsspp_cheats: 'enabled',
      ppsspp_internal_resolution: { low: '960x544', medium: '1440x816', high: '1920x1088' }[res] || '1440x816' };
    case 'GC': case 'WII': {
      const v = { dolphin_renderer: 'Hardware', dolphin_cpu_core: '4', dolphin_main_cpu_thread: 'disabled', dolphin_dsp_hle: 'enabled', dolphin_fast_disc_speed: 'enabled',
        dolphin_skip_gc_bios: 'enabled', dolphin_shader_compilation_mode: '0', dolphin_aspect_ratio: '3', dolphin_enable_rumble: 'enabled', dolphin_osd_enabled: 'disabled',
        dolphin_ir_mode: '1', dolphin_cheats_enabled: 'enabled', dolphin_widescreen_hack: 'enabled', dolphin_efb_scale: { low: '2', medium: '3', high: '4' }[res] || '3' };
      if (consoleId === 'WII') Object.assign(v, { dolphin_widescreen: 'enabled', dolphin_wiimote_default_device: 'sideways', dolphin_hotkey_sideways_toggle: 'Disabled' });
      return v;
    }
    default: return {};
  }
}
const KEY = (c) => (c === 'GB' || c === 'GBC' ? 'gb' : c.toLowerCase());   // Presets.key on Android
const GAME_EXT = { NES: 'nes,fds,unf,unif', SNES: 'sfc,smc,swc,fig', MD: 'md,gen,smd,bin', GB: 'gb', GBC: 'gbc,gb', GBA: 'gba', N64: 'n64,v64,z64', NDS: 'nds',
  PSP: 'iso,cso,chd,pbp', PS2: 'iso,chd,cso,zso,cue', GC: 'iso,gcm,gcz,rvz,ciso', WII: 'iso,gcz,rvz,wbfs,wia' };

function unpacked(p) { return p.replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep); }

class Player {
  constructor({ dir, resources, t, onExit }) { Object.assign(this, { dir, resources, t, onExit }); this.child = null; }
  engine(consoleId) {
    for (const name of ENGINES[consoleId] || []) {
      const p = path.join(this.resources, 'cores', name + '_libretro.dll');
      if (fs.existsSync(p)) return p;
    }
    return null;
  }
  /** Engine system files shipped with the app (PSP fonts, Dolphin data) go into the shared system folder once per version.
   *  PS2: its own system folder inside the game's saves (memory cards travel with the backups), with the shared BIOS linked in. */
  systemFor(consoleId, root) {
    const shared = path.join(this.dir, 'bios');
    const shipped = path.join(this.resources, 'system');
    const marker = path.join(shared, '.installed-' + require('electron').app.getVersion());
    if (fs.existsSync(shipped) && !fs.existsSync(marker)) { fs.cpSync(shipped, shared, { recursive: true, force: true }); fs.writeFileSync(marker, ''); }
    if (consoleId !== 'PS2') return shared;
    const own = path.join(root, 'saves', 'ps2');
    fs.mkdirSync(path.join(own, 'pcsx2'), { recursive: true });
    for (const name of ['bios', 'resources']) {
      const target = path.join(shared, 'pcsx2', name); fs.mkdirSync(target, { recursive: true });
      const link = path.join(own, 'pcsx2', name);
      if (!fs.existsSync(link)) { try { fs.symlinkSync(target, link, 'junction'); } catch (_) { fs.cpSync(target, link, { recursive: true }); } }
    }
    return own;
  }
  supported(consoleId) { return !!ENGINES[consoleId]; }
  running() { return !!this.child; }

  /** Zip / 7z games: the game file inside (MAME games stay zipped). */
  async unpack(game, file, work) {
    const ext = path.extname(file).toLowerCase();
    const split = /\.(7z|zip)\.0*1$/i.test(file);
    if (game.console === 'MAME' || (ext !== '.zip' && ext !== '.7z' && !split)) return file;
    if (BIG.has(game.console)) return this.unpackBig(game, file);
    const seven = unpacked(require('7zip-bin').path7za);
    fs.mkdirSync(work, { recursive: true });
    await new Promise((res, rej) => execFile(seven, ['x', '-y', '-o' + work, file], { windowsHide: true }, (e) => (e ? rej(e) : res())));
    const allowed = (GAME_EXT[game.console] || '').split(',');
    let best = null;
    const walk = (d) => { for (const f of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, f.name);
      if (f.isDirectory()) walk(p); else { const e = path.extname(f.name).substring(1).toLowerCase(); if (allowed.includes(e)) { const s = fs.statSync(p).size; if (!best || s > best.s) best = { p, s }; } } } };
    walk(work);
    if (!best) throw new Error(this.t('The zip file has no game for this console inside.'));
    return best.p;
  }
  /** Disc games: size + first and last 8 MB (as the Android app does, so saves match across devices). */
  quickHash(file) {
    const size = fs.statSync(file).size; const h = crypto.createHash('sha256'); h.update(String(size));
    const fd = fs.openSync(file, 'r'); const buf = Buffer.alloc(1 << 20);
    try {
      for (const start of [0, Math.max(0, size - 8 * 1024 * 1024)]) {
        let pos = start, left = Math.min(8 * 1024 * 1024, size - start);
        while (left > 0) { const n = fs.readSync(fd, buf, 0, Math.min(buf.length, left), pos); if (n <= 0) break; h.update(buf.subarray(0, n)); pos += n; left -= n; }
      }
    } finally { fs.closeSync(fd); }
    return h.digest('hex');
  }
  /** PS3 / Switch archives (often split in parts): unpacked once into a cache kept until the download is deleted. */
  async unpackBig(game, file) {
    const dir = path.join(this.dir, 'unpacked', game.id.replace(/[^A-Za-z0-9._-]/g, '_'));
    const done = path.join(dir, '.complete');
    if (!fs.existsSync(done)) {
      fs.rmSync(dir, { recursive: true, force: true }); fs.mkdirSync(dir, { recursive: true });
      const seven = unpacked(require('7zip-bin').path7za);
      await new Promise((res, rej) => execFile(seven, ['x', '-y', '-o' + dir, file], { windowsHide: true, maxBuffer: 64 * 1024 * 1024 }, (e) => (e ? rej(e) : res())));
      fs.writeFileSync(done, '');
    }
    let found = null;
    const walk = (d, depth) => {
      if (found || depth > 6) return;
      for (const f of fs.readdirSync(d, { withFileTypes: true })) {
        const p = path.join(d, f.name);
        if (game.console === 'PS3') {
          if (f.isDirectory() && (f.name === 'PS3_GAME' || fs.existsSync(path.join(p, 'PS3_GAME')))) { found = f.name === 'PS3_GAME' ? d : p; return; }
          if (f.isFile() && /\.iso$/i.test(f.name)) { found = p; return; }
          if (f.isFile() && f.name.toUpperCase() === 'EBOOT.BIN') { found = p; return; }
        } else if (f.isFile() && /\.(nsp|xci|nsz|xcz)$/i.test(f.name)) { found = p; return; }
      }
      for (const f of fs.readdirSync(d, { withFileTypes: true })) if (f.isDirectory()) walk(path.join(d, f.name), depth + 1);
    };
    walk(dir, 0);
    if (!found) throw new Error(this.t('The archive has no game for this console inside.'));
    return found;
  }
  sha256(file) {
    return new Promise((res, rej) => { const h = crypto.createHash('sha256'); fs.createReadStream(file).on('data', (d) => h.update(d)).on('end', () => res(h.digest('hex'))).on('error', rej); });
  }

  /** Unpacks the game if needed and finds its save folder (the saves themselves are restored by the caller before launch()). */
  async prepare(game, file, external) {
    if (this.child) throw new Error(this.t('A game is already running.'));
    const engine = external ? 'external' : this.engine(game.console);
    if (!engine) throw new Error(this.t('This console arrives in a later update.'));
    if (BIG.has(game.console)) { const rom = await this.unpack(game, file, null); return { game, engine, work: null, rom, sha: '', root: '', entry: null }; }
    const work = path.join(this.dir, 'play', String(Date.now()));
    const rom = await this.unpack(game, file, work);
    // PS2 BIN/CUE: the fingerprint is the first track's (what the Android app hashes).
    let hashed = rom;
    if (rom.toLowerCase().endsWith('.cue')) { const t1 = /FILE\s+"?([^"\r\n]+?)"?\s+BINARY/i.exec(fs.readFileSync(rom, 'utf8')); if (t1 && fs.existsSync(path.join(path.dirname(rom), t1[1]))) hashed = path.join(path.dirname(rom), t1[1]); }
    const sha = DISC.has(game.console) || game.console === 'GC' || game.console === 'WII' ? this.quickHash(hashed) : await this.sha256(hashed);
    const root = path.join(this.dir, 'saves', KEY(game.console), sha);
    fs.mkdirSync(root, { recursive: true });
    fs.writeFileSync(path.join(root, 'meta.json'), JSON.stringify({ title: game.title, console: game.console }));
    return { game, engine, work, rom, sha, root, entry: { root, sha, title: game.title, console: game.console } };
  }
  async launch(prep, prefs, cheats) {
    const { game, engine, work, rom, root } = prep;
    const system = this.systemFor(game.console, root);
    const optFile = path.join(this.dir, 'play', 'options-' + game.console + '.txt');
    fs.mkdirSync(path.dirname(optFile), { recursive: true });
    const opts = options(game.console, (prefs.resolution || {})[game.console] || 'medium');
    fs.writeFileSync(optFile, Object.entries(opts).map(([k, v]) => k + '=' + v).join('\n'));
    const pictures = path.join(require('electron').app.getPath('pictures'), 'RetroStream');
    const cheatsOn = path.join(this.dir, 'cheats', 'on', game.id.replace(/[^A-Za-z0-9._-]/g, '_') + '.txt');
    const args = ['--core', engine, '--rom', rom, '--title', game.title, '--console', game.console, '--gameid', game.id,
      '--saveroot', root, '--system', system, '--shots', path.join(this.dir, 'screenshots', 'pending'), '--pictures', pictures,
      '--options', optFile, '--lang', prefs.lang || 'en', '--fullscreen', prefs.playerWindowed ? '0' : '1',
      '--filter', ['NES', 'SNES', 'MD', 'GB', 'GBC', 'GBA'].includes(game.console) ? 'sharp' : 'smooth', '--fresh', prefs.fresh ? '1' : '0',
      '--cheats', cheats || '', '--cheatson', cheatsOn];
    const exe = path.join(this.resources, 'player', 'rs-player.exe');
    if (!fs.existsSync(exe)) throw new Error(this.t('The game player is missing. Reinstall RetroStream.'));
    const log = []; let played = 0; let error = '';
    const child = spawn(exe, args, { cwd: path.dirname(exe), windowsHide: false, stdio: ['ignore', 'pipe', 'pipe'] });
    this.child = child;
    let buf = '';
    child.stdout.on('data', (d) => {
      buf += d.toString();
      let nl; while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.substring(0, nl).trim(); buf = buf.substring(nl + 1);
        try { const m = JSON.parse(line); if (m.event === 'exit') played = m.played || 0; if (m.event === 'error') error = m.reason; } catch (_) { /* not ours */ }
      }
    });
    child.stderr.on('data', (d) => { log.push(d.toString()); if (log.length > 200) log.shift(); });
    child.on('exit', (code) => {
      this.child = null;
      try { fs.writeFileSync(path.join(this.dir, 'last-player.log'), log.join('')); } catch (_) { /* ignore */ }
      if (work && fs.existsSync(work)) fs.rmSync(work, { recursive: true, force: true });
      const reasons = { engine: 'The game engine could not be loaded.', file: 'The game file could not be read.', game: 'The engine could not open this game. The file may be damaged or for another region/console.', graphics: 'Your graphics card could not start OpenGL 3.3.' };
      const tail = log.join('').split(/\r?\n/).filter(Boolean).slice(-6).join('\n');
      this.onExit({ id: game.id, entry: prep.entry, played, details: (error || (code && !played)) ? 'code ' + code + (tail ? '\n' + tail : '') : '', error: error ? this.t(reasons[error] || 'The game closed unexpectedly.') : (code && !played ? this.t('The game closed unexpectedly.') : '') });
    });
    return true;
  }
}

module.exports = { Player };
