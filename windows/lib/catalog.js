'use strict';
// Consoles, channel names and game files: the same rules as the Android app (TelegramCatalog.java, GameMeta.kt).

const CONSOLES = [
  { id: 'NES', title: 'NES', thumbs: 'Nintendo - Nintendo Entertainment System', color: '#B83B52', exts: 'nes,fds,unf,unif', group: 'TD - ROMs - NES' },
  { id: 'SNES', title: 'SNES', thumbs: 'Nintendo - Super Nintendo Entertainment System', color: '#7A55AD', exts: 'sfc,smc,swc,fig', group: 'TD - ROMs - SNES' },
  { id: 'MD', title: 'Sega Mega Drive', thumbs: 'Sega - Mega Drive - Genesis', color: '#2F64A3', exts: 'md,gen,smd,bin', group: 'TD - ROMs - Genesis' },
  { id: 'GB', title: 'Game Boy', thumbs: 'Nintendo - Game Boy', color: '#4A7A5E', exts: 'gb', group: 'TD - ROMs - Game Boy' },
  { id: 'GBC', title: 'Game Boy Color', thumbs: 'Nintendo - Game Boy Color', color: '#9A6A33', exts: 'gbc,gb', group: 'TD - ROMs - Game Boy Color' },
  { id: 'GBA', title: 'Game Boy Advance', thumbs: 'Nintendo - Game Boy Advance', color: '#6750A0', exts: 'gba', group: 'DD-ROMS-GBA' },
  { id: 'N64', title: 'Nintendo 64', thumbs: 'Nintendo - Nintendo 64', color: '#2E7189', exts: 'n64,v64,z64', group: 'TD - ROMs - N64' },
  { id: 'NDS', title: 'Nintendo DS', thumbs: 'Nintendo - Nintendo DS', color: '#A2445B', exts: 'nds', group: 'TD - ROMs - DS' },
  { id: 'MAME', title: 'MAME', thumbs: 'MAME', color: '#2F6A8F', exts: '', group: 'TD - ROMs - MAME' },
  { id: 'PSP', title: 'PSP', thumbs: 'Sony - PlayStation Portable', color: '#4A4F5C', exts: 'iso,cso,chd,pbp', group: 'TD - ROMs - PSP' },
  { id: 'PS2', title: 'PlayStation 2', thumbs: 'Sony - PlayStation 2', color: '#1F3E8A', exts: 'iso,cso,chd,zso,cue', group: 'TD - ROMs - PS2' },
  { id: 'GC', title: 'GameCube', thumbs: 'Nintendo - GameCube', color: '#5B3E9E', exts: 'iso,gcm,gcz,rvz,ciso', group: 'TD - ROMs - GameCube' },
  { id: 'WII', title: 'Wii', thumbs: 'Nintendo - Wii', color: '#2672A0', exts: 'iso,gcz,rvz,wbfs,wia', group: 'TD - ROMs - Wii' },
  { id: 'PS3', title: 'PlayStation 3', thumbs: 'Sony - PlayStation 3', color: '#3A404E', exts: 'iso,pkg', group: 'TD - ROMs - PS3', big: true },
  { id: 'SWITCH', title: 'Nintendo Switch', thumbs: 'Nintendo - Nintendo Switch', color: '#B23A3A', exts: 'nsp,xci,nsz,xcz', group: 'TD - ROMs - Switch', big: true }
];
const BY_ID = Object.fromEntries(CONSOLES.map((c) => [c.id, c]));

const ALIASES = {
  nes: 'NES', snes: 'SNES', supernintendo: 'SNES', genesis: 'MD', megadrive: 'MD', segamegadrive: 'MD',
  gb: 'GB', gameboy: 'GB', gbc: 'GBC', gameboycolor: 'GBC', gba: 'GBA', gameboyadvance: 'GBA', n64: 'N64', nintendo64: 'N64',
  ds: 'NDS', nds: 'NDS', nintendods: 'NDS', mame: 'MAME', arcade: 'MAME', psp: 'PSP', playstationportable: 'PSP',
  ps2: 'PS2', playstation2: 'PS2', gc: 'GC', gamecube: 'GC', ngc: 'GC', nintendogamecube: 'GC', wii: 'WII', nintendowii: 'WII',
  ps3: 'PS3', playstation3: 'PS3', switch: 'SWITCH', nintendoswitch: 'SWITCH', nsw: 'SWITCH'
};

/** "TD - ROMs - N64" -> "N64"; null when the name isn't a console channel. */
function consoleFor(title) {
  if (!title) return null;
  const n = String(title).toLowerCase().replace(/[^a-z0-9]/g, '');
  if (!n.startsWith('tdroms') && !n.startsWith('ddroms')) return null;
  return ALIASES[n.substring(6)] || null;
}

function extOf(name) { const i = String(name || '').lastIndexOf('.'); return i < 0 ? '' : name.substring(i + 1).toLowerCase(); }

function supported(consoleId, file) {
  const ext = extOf(file); if (!ext) return false;
  if (ext === 'zip' || ext === '7z') return true;
  const c = BY_ID[consoleId]; if (!c) return false;
  return c.exts.split(',').includes(ext);
}

// ------------------------------------------------------------------ names
const REGIONS = 'USA|Europe|Japan|World|Korea|Australia|Asia|Germany|France|Spain|Italy|Brazil|China|Netherlands|Sweden|Canada|Russia|UK';
function tails(s) {
  let t = s.trim().replace(/(\s+[A-Za-z]{2}(,[A-Za-z]{2})+)+$/, '').trim();
  t = t.replace(new RegExp('(\\s+(' + REGIONS + '))+$', 'i'), '').trim();
  return t || s;
}
function fix(s) { return s.replace(/([A-Za-z])_s\b/g, "$1's").replace(/_/g, ' '); }
/** "Mario_Kart_64 (Europe)" -> "Mario Kart 64"; "Legend of Zelda, The (U) [!]" -> "The Legend of Zelda". */
function clean(stem) {
  let s = tails(fix(stem).replace(/\s*[([][^)\]]*[)\]]/g, ''));
  s = s.trim().replace(/^(.*), (The|A|An)$/, '$2 $1').replace(/^(.*?), (The|A|An)( - .*)$/, '$2 $1$3');
  s = s.replace(/\s+/g, ' ').replace(/^[\s\-.]+|[\s\-.]+$/g, '');
  return s || stem;
}
function stemOf(fileName) {
  let s = String(fileName || ''); const i = s.lastIndexOf('.'); if (i > 0) s = s.substring(0, i);
  const inner = extOf(s);
  if (inner && 'nes,sfc,smc,gba,gb,gbc,nds,n64,z64,v64,md,gen,bin,iso'.split(',').includes(inner)) s = s.substring(0, s.lastIndexOf('.'));
  return s.trim();
}
/** libretro thumbnail file names replace these characters with "_". */
function thumbName(name) { return name.replace(/[&*/:`<>?\\|"]/g, '_'); }
function regionOf(stem) {
  const inside = (stem.match(/[([]([^)\]]*)[)\]]/g) || []).join(' ').toLowerCase();
  if (/\busa\b|\(u\)/.test(inside)) return 'USA';
  if (/europe|\(e\)/.test(inside)) return 'Europe';
  if (/japan|\(j\)/.test(inside)) return 'Japan';
  if (/world/.test(inside)) return 'World';
  return '';
}
/** Names libretro may know a game's cover by, in the order to try. */
function coverNames(game) {
  const stem = stemOf(game.fileName) || game.title; const base = clean(stem); const out = [];
  if (game.cover) out.push(game.cover);
  out.push(thumbName(stem));
  for (const r of [regionOf(stem), 'USA', 'Europe', 'World', 'Japan']) if (r) out.push(thumbName(base + ' (' + r + ')'));
  return [...new Set(out)];
}

/** A Telegram message with a game file -> a game (null for anything else). */
function game(account, consoleId, m) {
  if (!m || !m.content || m.content._ !== 'messageDocument') return null;
  const d = m.content.document; if (!d || !d.document) return null;
  const fileName = d.file_name || '';
  if (!supported(consoleId, fileName)) return null;
  const stem = stemOf(fileName); if (!stem) return null;
  return {
    id: `tg:${account}:${m.chat_id}:${m.id}`, title: clean(stem), console: consoleId, fileName,
    account, chatId: m.chat_id, messageId: m.id, fileId: d.document.id,
    size: Math.max(d.document.size || 0, d.document.expected_size || 0), date: m.date
  };
}

module.exports = { CONSOLES, BY_ID, consoleFor, supported, clean, stemOf, thumbName, coverNames, game };
