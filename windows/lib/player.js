'use strict';
// Starts a game: unpacks it if needed, finds its save folder (same layout as the Android app), and runs rs-player.exe.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn, execFile } = require('child_process');

// The same engines as the Android app.
const ENGINES = {
  NES: ['fceumm'], SNES: ['snes9x'], MD: ['genesis_plus_gx'], GB: ['mgba'], GBC: ['mgba'], GBA: ['mgba'],
  N64: ['mupen64plus_next'], NDS: ['melondsds', 'melonds'], MAME: ['mame2003_plus']
};
const RES = { N64: { low: '640x480', medium: '1280x960', high: '1920x1440' } };
function options(consoleId, res) {
  switch (consoleId) {
    case 'N64': return {
      'mupen64plus-rdp-plugin': 'gliden64', 'mupen64plus-cpucore': 'dynamic_recompiler', 'mupen64plus-aspect': '4:3', 'mupen64plus-pak1': 'memory',
      'mupen64plus-EnableCopyDepthToRDRAM': 'Off', 'mupen64plus-EnableCopyColorToRDRAM': 'Async', 'mupen64plus-ThreadedRenderer': 'True',
      'mupen64plus-EnableShadersStorage': 'True', 'mupen64plus-43screensize': RES.N64[res] || RES.N64.medium
    };
    case 'GB': case 'GBC': case 'GBA': return { mgba_use_bios: 'OFF', mgba_skip_bios: 'ON', mgba_idle_optimization: 'Remove Known', mgba_interframe_blending: 'mix_smart', mgba_color_correction: 'OFF', mgba_frameskip: 'disabled' };
    case 'NDS': return {
      melonds_console_mode: 'ds', melonds_sysfile_mode: 'builtin', melonds_boot_mode: 'direct', melonds_touch_mode: 'auto', melonds_show_cursor: 'timeout',
      melonds_render_mode: 'software', melonds_threaded_renderer: 'enabled', melonds_jit_enable: 'enabled', melonds_number_of_screen_layouts: '2',
      melonds_hybrid_ratio: '2', melonds_hybrid_small_screen: 'both', melonds_screen_gap: '0', melonds_screen_layout1: 'hybrid-top', melonds_screen_layout2: 'top-bottom'
    };
    case 'MAME': return { 'mame2003-plus_skip_disclaimer': 'enabled', 'mame2003-plus_skip_warnings': 'enabled' };
    default: return {};
  }
}
const KEY = (c) => (c === 'GB' || c === 'GBC' ? 'gb' : c.toLowerCase());   // Presets.key on Android
const GAME_EXT = { NES: 'nes,fds,unf,unif', SNES: 'sfc,smc,swc,fig', MD: 'md,gen,smd,bin', GB: 'gb', GBC: 'gbc,gb', GBA: 'gba', N64: 'n64,v64,z64', NDS: 'nds' };

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
  supported(consoleId) { return !!ENGINES[consoleId]; }
  running() { return !!this.child; }

  /** Zip / 7z games: the game file inside (MAME games stay zipped). */
  async prepare(game, file, work) {
    const ext = path.extname(file).toLowerCase();
    if (game.console === 'MAME' || (ext !== '.zip' && ext !== '.7z')) return file;
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
  sha256(file) {
    return new Promise((res, rej) => { const h = crypto.createHash('sha256'); fs.createReadStream(file).on('data', (d) => h.update(d)).on('end', () => res(h.digest('hex'))).on('error', rej); });
  }

  async play(game, file, prefs) {
    if (this.child) throw new Error(this.t('A game is already running.'));
    const engine = this.engine(game.console);
    if (!engine) throw new Error(this.t('This console arrives in a later update.'));
    const work = path.join(this.dir, 'play', String(Date.now()));
    const rom = await this.prepare(game, file, work);
    const sha = await this.sha256(rom);
    const saveRoot = path.join(this.dir, 'saves', KEY(game.console), sha);
    fs.mkdirSync(saveRoot, { recursive: true });
    fs.writeFileSync(path.join(saveRoot, 'meta.json'), JSON.stringify({ title: game.title, console: game.console }));
    const optFile = path.join(this.dir, 'play', 'options-' + game.console + '.txt');
    fs.mkdirSync(path.dirname(optFile), { recursive: true });
    const opts = options(game.console, (prefs.resolution || {})[game.console] || 'medium');
    fs.writeFileSync(optFile, Object.entries(opts).map(([k, v]) => k + '=' + v).join('\n'));
    const pictures = path.join(require('electron').app.getPath('pictures'), 'RetroStream');
    const args = ['--core', engine, '--rom', rom, '--title', game.title, '--console', game.console, '--gameid', game.id,
      '--saveroot', saveRoot, '--system', path.join(this.dir, 'bios'), '--shots', path.join(this.dir, 'screenshots', 'pending'), '--pictures', pictures,
      '--options', optFile, '--lang', prefs.lang || 'en', '--fullscreen', prefs.playerWindowed ? '0' : '1',
      '--filter', ['NES', 'SNES', 'MD', 'GB', 'GBC', 'GBA'].includes(game.console) ? 'sharp' : 'smooth', '--fresh', prefs.fresh ? '1' : '0'];
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
      if (fs.existsSync(work)) fs.rmSync(work, { recursive: true, force: true });
      const reasons = { engine: 'The game engine could not be loaded.', file: 'The game file could not be read.', game: 'The engine could not open this game. The file may be damaged or for another region/console.', graphics: 'Your graphics card could not start OpenGL 3.3.' };
      this.onExit({ id: game.id, played, error: error ? this.t(reasons[error] || 'The game closed unexpectedly.') : (code && !played ? this.t('The game closed unexpectedly.') : '') });
    });
    return { sha };
  }
}

module.exports = { Player };
