'use strict';
/* RetroStream for Windows — the extra Settings pages and game options (the same ones as the phone app). */

// ------------------------------------------------------------------ look: accent color, dark mode, logos
const PALETTES = {
  blue: ['Blue', '#2F6DF6', '#7FA6FF'], purple: ['Purple', '#7A4DFF', '#B39BFF'], green: ['Green', '#16A34A', '#6EE7A0'],
  red: ['Red', '#E0384B', '#FF8A95'], orange: ['Orange', '#EA7A12', '#FFB86B'], pink: ['Pink', '#D9468F', '#FF9CCB'], teal: ['Teal', '#0EA5A4', '#5EEAD4']
};
function applyLook() {
  const p = PALETTES[pref('palette')] || PALETTES.blue;
  document.documentElement.style.setProperty('--primary', p[1]);
  document.documentElement.style.setProperty('--ring', p[2]);
  document.body.classList.toggle('light', pref('dark') === false);
}
function logoStyle() { return pref('logoStyle') || 'color'; }
const MONO = new Set(['NES', 'SNES', 'MD', 'GB', 'GBC', 'GBA', 'N64', 'NDS', 'MAME', 'PSP', 'PS2', 'GC', 'WII']);
function visibleConsoles() { const off = pref('shownConsoles') || {}; const list = S.consoles.filter((c) => off[c.id] !== false); return list.length ? list : S.consoles; }
const SECTIONS = [['continue', 'Continue card'], ['recent', 'Recently played'], ['new', 'New in your channels'], ['friends', 'Friends'], ['consoles', 'Consoles']];
function sectionOn(k) { return (pref('sections') || {})[k] !== false; }
function onOff(v) { return v ? t('On') : t('Off'); }

// ------------------------------------------------------------------ a page with a back button
function page(title, ...kids) {
  return h('main', { class: 'scroll' },
    h('div', { class: 'pagehead' }, h('button', { class: 'iconbtn', 'aria-label': t('Back'), 'data-key': 'back', onClick: () => go('settings') }, svg(ICON.back, 20, '#E9ECF5')), h('h1', {}, title)),
    ...kids);
}
function note(text) { return h('p', { style: 'padding:0 32px;color:var(--muted);max-width:980px;line-height:1.5' }, text); }
function toggleRow(title, sub, on, color, fn, key) { const r = srow(title, sub, color, fn, key); r.append(h('span', { class: 'switch' + (on ? ' on' : ''), 'aria-hidden': 'true' })); r.setAttribute('aria-pressed', String(!!on)); return r; }

// ------------------------------------------------------------------ resolution, per console
const RES_LABEL = { low: 'Low', medium: 'Medium', high: 'High' };
const RES_HELP = {
  N64: ['640×480', '1280×960', '1920×1440'], PSP: ['960×544 (1×)', '1440×816 (1.5×)', '1920×1088 (2×)'], PS2: ['1× (480p)', '2× (~720p)', '3× (~1080p)'],
  GC: ['2× (~720p)', '3× (~1080p)', '4× (~1440p)'], WII: ['2× (~720p)', '3× (~1080p)', '4× (~1440p)']
};
const FLAT = new Set(['NES', 'SNES', 'MD', 'GB', 'GBC', 'GBA']);
function resText(id, v) {
  const i = ['low', 'medium', 'high'].indexOf(v);
  if (RES_HELP[id]) return t(RES_LABEL[v]) + ' · ' + RES_HELP[id][i];
  if (FLAT.has(id)) return t(RES_LABEL[v]) + ' · ' + (v === 'high' ? t('smooth upscale') : t('crisp pixels'));
  return t(RES_LABEL[v]);
}
function resolutionPage() {
  const res = pref('resolution') || {};
  const pick = (title, cur, done) => choose(title, ['low', 'medium', 'high'].map((v) => ({ label: t(RES_LABEL[v]), on: cur === v, pick: () => done(v) })));
  const rows = visibleConsoles().filter((c) => c.id !== 'MAME' && c.id !== 'PS3' && c.id !== 'SWITCH' && c.id !== 'NDS')
    .map((c) => srow(c.title, resText(c.id, res[c.id] || 'medium'), c.color, () => pick(c.title, res[c.id] || 'medium', (v) => { res[c.id] = v; setPref('resolution', res); render(); }), 'res_' + c.id));
  return page(t('Resolution'),
    note(t('Choose the resolution for each console. On 3D consoles it changes the drawing size (higher needs a stronger graphics card); on 2D consoles it changes how the picture is scaled. A game can have its own in its menu › This game’s settings.')),
    h('div', { class: 'settings' },
      h('section', { class: 'group' }, h('h2', {}, t('All consoles')), srow(t('Change all at once'), '', '#2F6DF6', () => pick(t('All consoles'), '', (v) => { for (const c of S.consoles) res[c.id] = v; setPref('resolution', res); render(); }), 'res_all')),
      h('section', { class: 'group' }, h('h2', {}, t('By console')), rows),
      h('section', { class: 'group' }, h('h2', {}, 'PS3 · Switch · DS'), h('p', { style: 'padding:6px 10px;color:var(--muted);font-size:13px;line-height:1.5' },
        t('PS3 and Switch use their own programs: change their resolution with Open RPCS3 / Open Eden in Settings › GameCube, Wii, PS3 and Switch. DS games are drawn at their own size.')))));
}
/** A game's own resolution and screen filter. */
function gameSettings(g) {
  const all = pref('gameSettings') || {}; const own = all[g.id] || {};
  const save = () => { if (!own.res && !own.filter) delete all[g.id]; else all[g.id] = own; setPref('gameSettings', all); };
  const consoleRes = (pref('resolution') || {})[g.console] || 'medium';
  choose(t('This game’s settings') + ' · ' + g.title, [
    { label: t('Resolution') + ': ' + (own.res ? t(RES_LABEL[own.res]) : t('Same as the console') + ' (' + t(RES_LABEL[consoleRes]) + ')'), pick: () => choose(t('Resolution'), [
      { label: t('Same as the console'), on: !own.res, pick: () => { delete own.res; save(); toast(t('Saved')); } },
      ...['low', 'medium', 'high'].map((v) => ({ label: resText(g.console, v), on: own.res === v, pick: () => { own.res = v; save(); toast(t('Used the next time you open the game')); } }))]) },
    { label: t('Screen filter') + ': ' + (own.filter === 'sharp' ? t('Sharp pixels') : own.filter === 'smooth' ? t('Smooth') : t('Same as the console')), pick: () => choose(t('Screen filter'), [
      { label: t('Same as the console'), on: !own.filter, pick: () => { delete own.filter; save(); } },
      { label: t('Sharp pixels'), on: own.filter === 'sharp', pick: () => { own.filter = 'sharp'; save(); } },
      { label: t('Smooth'), on: own.filter === 'smooth', pick: () => { own.filter = 'smooth'; save(); } }]) }
  ]);
}

// ------------------------------------------------------------------ consoles shown, home sections
function consolesPage() {
  const off = pref('shownConsoles') || {};
  return page(t('Consoles'), note(t('Hidden consoles leave the home screen and the library tabs. Their games are not deleted.')),
    h('div', { class: 'settings' }, h('section', { class: 'group' }, h('h2', {}, t('Visible consoles')),
      S.consoles.map((c) => toggleRow(c.title, count(S.games.filter((g) => g.console === c.id).length, 'game', 'games'), off[c.id] !== false, c.color, () => { off[c.id] = off[c.id] === false; setPref('shownConsoles', off); render(); }, 'vis_' + c.id)))));
}
function homePage() {
  const sec = pref('sections') || {};
  return page(t('Home screen'), h('div', { class: 'settings' },
    h('section', { class: 'group' }, h('h2', {}, t('Style')),
      srow(t('Home screen style'), homeTheme() === 'cinematic' ? t('Cinematic') : t('Classic (tiles and rows)'), '#7A4DFF', () => { setPref('homeTheme', homeTheme() === 'cinematic' ? 'classic' : 'cinematic'); render(); }, 'hs_theme')),
    h('section', { class: 'group' }, h('h2', {}, t('Sections (classic style)')),
      SECTIONS.map(([k, label]) => toggleRow(t(label), '', sectionOn(k), '#14C9AA', () => { sec[k] = !sectionOn(k); setPref('sections', sec); render(); }, 'hs_' + k)))));
}

// ------------------------------------------------------------------ games already on this PC
function pcPage() {
  if (!S.pcInfo) S.pcInfo = { folders: S.folders || [], unsorted: S.unsortedCount || 0 };
  const info = S.pcInfo; const local = S.games.filter((g) => g.local).length; const linked = Object.keys(pref('pcLinked') || {}).length;
  const run = async (action, a, b) => {
    if (action === 'scan' || action === 'add') toast(t('Searching your folders…'));
    const r = await window.rs.local(action, a, b); if (!r) return; if (r.error) { toast(r.error); return; }
    S.pcInfo = { folders: r.folders, unsorted: r.unsorted }; S.prefs.pcLinked = r.links;
    if (r.result) toast(r.result.files + ' ' + t('game files found') + ' · ' + r.result.linked + ' ' + t('matched to Telegram') + ' · ' + r.result.local + ' ' + t('only on this PC'));
    render();
  };
  return page(t('Games on this PC'),
    note(t('Add the folders where you already keep ROMs. RetroStream looks inside them and their subfolders: games that are also in your Telegram channels show as downloaded (no need to download them again), and the rest appear in their console as "This PC" games. Your files are never moved or deleted.')),
    h('div', { class: 'settings' },
      h('section', { class: 'group' }, h('h2', {}, t('Folders')),
        info.folders.length ? info.folders.map((f, i) => srow(f, t('Remove from the list'), '#8C95AA', () => confirmBox(f, t('RetroStream stops looking in this folder. Nothing is deleted.'), t('Remove'), () => run('remove', f)), 'pcf_' + i)) : h('p', { style: 'padding:6px 10px;color:var(--muted)' }, t('No folders yet.')),
        srow(t('Add a folder'), t('Its subfolders are searched too'), '#22C55E', () => run('add'), 'pc_add')),
      h('section', { class: 'group' }, h('h2', {}, t('Scan')),
        srow(t('Scan now'), linked + ' ' + t('matched to Telegram') + ' · ' + local + ' ' + t('only on this PC'), '#2F6DF6', () => run('scan'), 'pc_scan'),
        srow(t('Files without a console'), info.unsorted + ' ' + t('files') + ' · ' + t('choose their console'), '#F0B455', unsortedFiles, 'pc_unsorted'),
        h('p', { style: 'padding:6px 10px;color:var(--muted);font-size:12px;line-height:1.5' }, t('Tip: files like .iso, .chd or .zip are placed by the folder name (for example a folder called "PS2" or "GameCube"). Others can be placed by hand above.')))));
}
async function unsortedFiles() {
  const r = await window.rs.local('unsorted'); const list = (r && r.list) || [];
  if (!list.length) { toast(t('Every file found has a console.')); return; }
  choose(t('Files without a console'), list.slice(0, 300).map((f) => ({ label: f.name + ' · ' + size(f.size), pick: () => choose(f.name, S.consoles.map((c) => ({ label: c.title, pick: async () => {
    toast(t('Searching your folders…')); const x = await window.rs.local('assign', f.path, c.id); if (x && x.error) toast(x.error); else { S.pcInfo = { folders: x.folders, unsorted: x.unsorted }; S.prefs.pcLinked = x.links; toast(f.name + ' → ' + c.title); render(); }
  } }))) })));
}

// ------------------------------------------------------------------ history, stats, games without info
function hm(ms) { const m = Math.floor((ms || 0) / 60000); return m < 60 ? m + ' min' : Math.floor(m / 60) + ' h ' + (m % 60) + ' min'; }
function historyPage() {
  const list = plays().slice(0, 300);
  const day = (at) => new Date(at).toLocaleDateString(pref('lang') === 'es' ? 'es' : 'en', { weekday: 'long', day: 'numeric', month: 'long' });
  const out = []; let last = '';
  for (const p of list) {
    const d = day(p.at); if (d !== last) { out.push(h('h2', { style: 'margin:18px 4px 6px;font-size:12px;letter-spacing:.14em;color:var(--muted);text-transform:uppercase;font-family:Inter' }, d)); last = d; }
    out.push(h('button', { class: 'srow', 'data-key': 'hist_' + p.at, 'data-game': p.game.id, onClick: () => playGame(p.game), onContextmenu: (e) => { e.preventDefault(); gameMenu(p.game, e, true); } },
      cover(p.game, 44, 56, false), h('span', { class: 'tx' }, h('b', {}, p.game.title), h('span', {}, byId[p.game.console].title + ' · ' + new Date(p.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + (playtime(p.game) ? ' · ' + playtime(p.game) + ' ' + t('in total') : '')))));
  }
  return page(t('History'),
    h('div', { style: 'padding:0 32px 30px;max-width:900px' }, list.length ? out : h('p', { class: 'empty' }, t('You have not played anything yet.')),
      list.length ? h('div', { class: 'buttons', style: 'margin-top:14px' }, h('button', { class: 'btn ghost', 'data-key': 'hist_clear', onClick: clearRecents }, t('Clear all recents'))) : null));
}
function clearRecents() { confirmBox(t('Clear all recents'), t('The "Recently played" row and the history are emptied. Play time and saves are not touched.'), t('Clear'), () => { setPref('plays', []); render(); }); }
function statsDialog() {
  const pt = pref('playtime') || {}; const total = Object.values(pt).reduce((a, b) => a + b, 0);
  if (!total) { toast(t('No play time recorded yet.')); return; }
  const per = {}; for (const [id, ms] of Object.entries(pt)) { const g = S.games.find((x) => x.id === id); if (g) per[g.console] = (per[g.console] || 0) + ms; }
  const top = Object.entries(pt).map(([id, ms]) => ({ g: S.games.find((x) => x.id === id), ms })).filter((x) => x.g).sort((a, b) => b.ms - a.ms).slice(0, 10);
  const bar = (label, ms, color) => h('div', { style: 'margin:6px 0' }, h('div', { style: 'display:flex;font-size:13px' }, h('span', { style: 'flex:1' }, label), h('b', {}, hm(ms))),
    h('div', { class: 'progress', style: 'margin-top:4px' }, h('div', { style: `width:${Math.max(2, Math.round(ms * 100 / top[0].ms))}%;background:${color || 'var(--primary)'}` })));
  modal((box, close) => box.append(h('h2', {}, t('Play stats')),
    h('p', {}, t('Total:') + ' ' + hm(total) + ' · ' + t('Games played:') + ' ' + Object.keys(pt).length + ' · ' + t('Completed:') + ' ' + listPref('completed').size),
    h('h3', { style: 'font-size:12px;letter-spacing:.14em;color:var(--muted)' }, t('MOST PLAYED')), top.map((x) => bar(x.g.title, x.ms, byId[x.g.console].color)),
    h('h3', { style: 'font-size:12px;letter-spacing:.14em;color:var(--muted);margin-top:16px' }, t('BY CONSOLE')),
    Object.entries(per).sort((a, b) => b[1] - a[1]).map(([c, ms]) => h('div', { style: 'display:flex;font-size:13px;margin:4px 0' }, h('span', { style: 'flex:1' }, byId[c].title), h('b', {}, hm(ms)))),
    h('div', { class: 'buttons' }, h('button', { class: 'btn primary', onClick: close }, t('Close')))));
}
function missingPage() {
  const list = visibleGames().filter((g) => !g.desc).sort((a, b) => a.title.localeCompare(b.title));
  return page(t('Games without information'), note(t('These games have no description yet. Choose one to search for its information and cover by hand.')),
    h('div', { style: 'padding:6px 32px 30px;max-width:900px' }, list.length ? list.slice(0, 1500).map((g) => h('button', { class: 'srow', 'data-key': 'miss_' + g.id, 'data-game': g.id, onClick: () => searchInfo(g), onContextmenu: (e) => { e.preventDefault(); gameMenu(g, e, false); } },
      cover(g, 40, 52, false), h('span', { class: 'tx' }, h('b', {}, g.title), h('span', {}, byId[g.console].title)))) : h('p', { class: 'empty' }, t('Every game has its information.'))));
}

// ------------------------------------------------------------------ space used
function spacePage() {
  if (!S.space) window.rs.tools('space').then((x) => { S.space = x || {}; if (S.page === 'space') render(); });
  const sp = S.space || {};
  const row = (k, title, sub, color, free, warn) => srow(title, (S.space ? size(sp[k]) || '0 B' : t('Calculating…')) + ' · ' + sub, color, () => free ? confirmBox(title, warn, t('Delete'), async () => { const r = await window.rs.tools('free', k); toast(r && r.error ? r.error : t('Done')); S.space = null; render(); }) : null, 'sp_' + k);
  return page(t('Space used'), h('div', { class: 'settings' },
    h('section', { class: 'group' }, h('h2', {}, t('On this PC')),
      row('downloads', t('Downloaded games'), t('tap to delete them all'), '#2F6DF6', true, t('Every game downloaded from Telegram is deleted from this PC. They stay in Telegram and in your library. Games in your own PC folders are not touched.')),
      row('unpacked', t('Unpacked games'), t('zip and 7z games ready to play'), '#7A4DFF', true, t('They are unpacked again the next time you play them.')),
      row('parts', t('Split archive parts'), t('PS3 and Switch'), '#5B3E9E', true, t('The parts are downloaded again the next time you play those games.')),
      row('covers', t('Covers and pictures'), t('downloaded again when you see them'), '#FF9A2E', true, t('Covers are downloaded again when you see them.')),
      row('cheats', t('Game data and cheats'), t('downloaded again when needed'), '#4DB36B', true, t('Cheat lists are downloaded again when you open a game.')),
      row('saves', t('Saves'), t('never deleted from here'), '#2DE2C8', false),
      row('engines', t('Game programs'), 'Dolphin · RPCS3 · Eden', '#B23A3A', false),
      row('shots', t('Screenshots waiting to upload'), t('sent to Telegram after playing'), '#38BDF8', false)),
    h('section', { class: 'group' }, h('h2', {}, t('Folders')),
      srow(t('Open the downloads folder'), t('Where the games from Telegram are kept'), '#22C55E', openDownloads, 'sp_open'),
      srow(t('Open the saves folder'), '', '#2DE2C8', () => window.rs.tools('openFolder', 'saves'), 'sp_saves'),
      srow(t('Open the screenshots folder'), t('Pictures › RetroStream'), '#38BDF8', () => window.rs.tools('openFolder', 'pictures'), 'sp_pics'))));
}
async function openDownloads() { const r = await window.rs.tools('openDownloads'); if (r && r.error) toast(r.error); }

// ------------------------------------------------------------------ controllers
function controllersPage() {
  const sw = !!pref('swapAB');
  return page(t('Controllers'), h('div', { class: 'settings' },
    h('section', { class: 'group' }, h('h2', {}, t('Buttons')),
      toggleRow(t('Swap A/B and X/Y'), sw ? t('By the letters on the buttons (Nintendo style)') : t('By position (Xbox style, like the original consoles)'), sw, '#FF4D73', () => { setPref('swapAB', !sw); render(); }, 'ctl_swap'),
      toggleRow(t('Play in a window'), t('Games open in a window instead of full screen'), !!pref('playerWindowed'), '#38BDF8', () => { setPref('playerWindowed', !pref('playerWindowed')); render(); }, 'ctl_win')),
    h('section', { class: 'group' }, h('h2', {}, t('While playing')),
      h('div', { style: 'padding:8px 12px;font-size:13px;line-height:1.9;color:var(--soft)' },
        [['Home / Guide · Esc · F1', t('Game menu: save, load, cheats, screenshot, exit')], ['Select + Start', t('Game menu (controllers without a Home button)')], ['Select + RB · F12', t('Screenshot')], ['Select + RT', t('Fast forward on / off')], ['Select + LB · Backspace', t('Rewind while held')], ['Tab', t('Fast forward while held')], ['F5 / F7', t('Quick save / quick load (slot 1)')], ['F11', t('Full screen or window')]]
          .map(([k, v]) => h('div', { style: 'display:flex;gap:12px' }, h('b', { style: 'min-width:150px;color:var(--text)' }, k), h('span', {}, v))))),
    h('section', { class: 'group' }, h('h2', {}, t('Keyboard')),
      h('div', { style: 'padding:8px 12px;font-size:13px;line-height:1.9;color:var(--soft)' },
        [['↑ ↓ ← →', t('D-pad')], ['Z · X', 'B · A'], ['A · S', 'Y · X'], ['Q · W', 'L · R'], ['E · R', 'L2 · R2'], ['Enter · Right Shift', 'Start · Select']]
          .map(([k, v]) => h('div', { style: 'display:flex;gap:12px' }, h('b', { style: 'min-width:150px;color:var(--text)' }, k), h('span', {}, v))))),
    h('section', { class: 'group' }, h('h2', {}, t('Help')), h('p', { style: 'padding:6px 12px;color:var(--muted);font-size:13px;line-height:1.6' },
      t('Xbox, PlayStation, Switch Pro and most USB controllers work as soon as they are plugged in or paired. Each controller is one player, in the order they were connected. GameCube, Wii, PS3 and Switch use the controls of their own programs (Settings › GameCube, Wii, PS3 and Switch › Open).')))));
}

// ------------------------------------------------------------------ friends
function friendsPage() {
  const f = S.friends || { friends: [] };
  const act = async (a, arg, ok) => { const r = await window.rs.friends(a, arg); if (r && r.error) { toast(r.error); if (r.friends) S.friends = r.friends; } else { if (r) S.friends = r; if (ok) toast(ok); } render(); };
  const people = f.friends.filter((x) => x.userId !== f.me).sort((a, b) => (b.playing - a.playing) || (b.online - a.online) || b.ts - a.ts);
  const ago = (ts) => { const m = Math.round((Date.now() - ts) / 60000); return m < 60 ? m + ' min' : m < 1440 ? Math.round(m / 60) + ' h' : Math.round(m / 1440) + ' d'; };
  if (!f.chat) return page(t('Friends'),
    note(t('Friends share a private Telegram group. Everyone in it sees who is online and what they are playing, on the phone, TV and PC. Create the group and send the invite link, or join with a link a friend sent you.')),
    h('div', { class: 'settings' }, h('section', { class: 'group' }, h('h2', {}, t('Start')),
      srow(t('Create the friends group'), t('You get an invite link to send'), '#22C55E', () => act('create', null, t('Group ready. Copy the link and send it to your friends.')), 'fr_create'),
      srow(t('Join with a link'), t('Paste the t.me link a friend sent you'), '#2F6DF6', () => ask(t('Invite link'), '', (v) => act('join', v, t('You joined the friends group'))), 'fr_join'))));
  return page(t('Friends'), h('div', { class: 'settings' },
    h('section', { class: 'group' }, h('h2', {}, t('Friends') + ' · ' + people.filter((x) => x.online).length + ' ' + t('online')),
      people.length ? people.map((x) => h('div', { class: 'srow' }, h('span', { class: 'ic', style: `background:${x.playing ? '#22C55E' : x.online ? '#2F6DF6' : '#4A5168'};border-radius:15px` }),
        h('span', { class: 'tx' }, h('b', {}, x.name || t('Friend')), h('span', {}, x.playing ? t('Playing') + ' ' + x.game + (x.console ? ' · ' + ((byId[x.console] || {}).title || x.console) : '') + ' · ' + ago(x.since)
          : x.online ? t('Online · in the menu') : t('Last seen') + ' ' + ago(x.ts))))) : h('p', { style: 'padding:6px 10px;color:var(--muted)' }, t('Nobody else is in the group yet. Send them the invite link.'))),
    h('section', { class: 'group' }, h('h2', {}, t('Options')),
      toggleRow(t('Show my activity'), t('Your friends see if you are online and what you play'), f.share, '#22C55E', () => act('share', !f.share), 'fr_share'),
      f.link ? srow(t('Copy invite link'), f.link, '#2AA8FF', async () => { await window.rs.tools('copy', f.link); toast(t('Link copied')); }, 'fr_copy') : null,
      srow(t('Refresh'), '', '#7A4DFF', () => act('refresh', null, t('Updated')), 'fr_refresh'),
      srow(t('Leave the group'), t('You can join again with the link'), '#FF4D73', () => confirmBox(t('Leave the group'), t('RetroStream stops using this group on this PC. You stay a member in Telegram.'), t('Leave'), () => act('leave')), 'fr_leave'))));
}
function friendsRow() {
  const f = S.friends; if (!f || !f.chat) return null;
  const on = f.friends.filter((x) => x.userId !== f.me && x.online); if (!on.length) return null;
  return [h('div', { class: 'rowhead' }, h('h2', {}, t('Friends online') + ' · ' + on.length), h('button', { class: 'link', 'data-key': 'friends_more', onClick: () => go('friends') }, t('See all'))),
    h('div', { class: 'strip' }, on.map((x) => h('button', { class: 'friend', 'data-key': 'fr_' + x.userId, onClick: () => go('friends') }, h('span', { class: 'av', style: `background:${x.playing ? '#22C55E' : '#2F6DF6'}` }, (x.name || '?').substring(0, 1).toUpperCase()),
      h('span', { class: 'tx' }, h('b', {}, x.name || t('Friend')), h('small', {}, x.playing ? x.game : t('In the menu'))))))];
}

// ------------------------------------------------------------------ game: info, screenshots, sharing
function gameInfo(g) {
  modal((box, close) => box.append(
    h('div', { style: 'display:flex;gap:20px;align-items:flex-start' }, cover(g, 170, Math.min(260, Math.round(170 / (RATIO[g.console] || 1)))),
      h('div', { style: 'flex:1;min-width:0' }, h('h2', {}, g.title),
        h('table', { class: 'facts' }, [[t('Console'), byId[g.console].title], [t('Year'), g.year], [t('Genre'), g.genre], [t('Developer'), g.dev], [t('Publisher'), g.pub],
          [t('File'), g.fileName], [t('Size'), size(g.size)], [t('Where'), g.local ? t('This PC') + ' · ' + g.path : kept(g) ? t('On this PC') : 'Telegram'], [t('Played'), playtime(g) || t('Never')],
          [t('Favorite'), listPref('favorites').has(g.id) ? '★' : ''], [t('Completed'), listPref('completed').has(g.id) ? '✓' : '']].filter((r) => r[1]).map(([k, v]) => h('tr', {}, h('th', {}, k), h('td', {}, v)))))),
    g.desc ? h('p', { style: 'white-space:pre-line' }, g.desc) : h('p', { style: 'color:var(--muted)' }, t('No description yet.')),
    h('div', { class: 'buttons' }, h('button', { class: 'btn ghost', onClick: () => { close(); searchInfo(g); } }, t('Search game info')), h('button', { class: 'btn primary', onClick: () => { close(); playGame(g); } }, t('Play')), h('button', { class: 'btn ghost', onClick: close }, t('Close')))));
}
function screenshots(g) {
  if (S.drive.stage !== 'ready') { toast(t('Connect Telegram first (Settings > TirsoDrive).')); return; }
  modal((box, close) => {
    const grid = h('div', { class: 'shots' }, h('p', {}, t('Loading…')));
    box.classList.add('wide');
    box.append(h('h2', {}, t('Screenshots') + ' · ' + g.title), grid, h('div', { class: 'buttons' },
      h('button', { class: 'btn ghost', onClick: () => window.rs.tools('openFolder', 'pictures') }, t('Open the screenshots folder')), h('button', { class: 'btn primary', onClick: close }, t('Close'))));
    window.rs.tools('shots', g.id).then((r) => {
      if (r.error) { grid.replaceChildren(h('p', { class: 'error' }, r.error)); return; }
      if (!r.list.length) { grid.replaceChildren(h('p', {}, t('No screenshots of this game yet. Take one in the game menu (or F12).'))); return; }
      grid.replaceChildren(...r.list.map((s) => h('button', { class: 'shot', onClick: () => modal((b2, c2) => {
        const info = { path: s.path, w: s.w, h: s.h, date: s.date, title: g.title };
        const run = async (what, ok) => { const x = await window.rs.tools(what, info); if (x && x.error) toast(x.error); else if (x) toast(ok); };
        b2.classList.add('wide');
        b2.append(h('img', { src: s.url, alt: '', style: 'width:100%;border-radius:10px' }), h('p', {}, new Date(s.date * 1000).toLocaleString()),
          h('div', { class: 'buttons' },
            h('button', { class: 'btn ghost', onClick: () => run('shotCopy', t('Picture copied: paste it in WhatsApp, Discord or any chat')) }, t('Copy picture')),
            h('button', { class: 'btn ghost', onClick: () => run('shotSave', t('Picture saved')) }, t('Save as…')),
            (S.friends && S.friends.chat) ? h('button', { class: 'btn ghost', onClick: () => run('shotFriends', t('Sent to the friends group')) }, t('Send to friends')) : null,
            h('button', { class: 'btn primary', onClick: c2 }, t('Close'))));
      }) },
        h('img', { src: s.url, alt: t('Screenshot') + ' ' + new Date(s.date * 1000).toLocaleString() }))));
    });
  });
}
async function shareGame(g) {
  const f = S.friends || {};
  const opts = [];
  if (f.chat) opts.push({ label: t('Send to the friends group'), pick: async () => { const r = await window.rs.tools('shareFriend', g); toast(r.error || t('Sent to the friends group')); } });
  if (!g.local) opts.push({ label: t('Copy the Telegram link'), pick: async () => { const r = await window.rs.tools('link', g); if (r.error || !r.link) { toast(r.error || t('The link could not be made.')); return; } await window.rs.tools('copy', r.link); toast(t('Link copied')); } });
  opts.push({ label: t('Copy the name'), pick: async () => { await window.rs.tools('copy', g.title + ' (' + byId[g.console].title + ')'); toast(t('Copied')); } });
  if (!f.chat) opts.push({ label: t('Set up friends…'), pick: () => go('friends') });
  choose(t('Share with a friend') + ' · ' + g.title, opts);
}
async function desktopShortcut(g) { const r = await window.rs.tools('shortcut', g); toast(r.error || t('Shortcut added to the desktop')); }
async function backupNow(g) { toast(t('Backing up…')); const r = await window.rs.tools('backupNow', g.id); toast(r.error || t('Save backed up to Telegram')); }

// ------------------------------------------------------------------ library tools
function downloadConsole() {
  if (S.drive.stage !== 'ready') { toast(t('Connect Telegram first (Settings > TirsoDrive).')); return; }
  choose(t('Download a whole console'), visibleConsoles().map((c) => {
    const left = visibleGames().filter((g) => g.console === c.id && !kept(g) && !g.local);
    const total = left.reduce((a, g) => a + (g.size || 0), 0);
    return { label: c.title + ' · ' + count(left.length, 'game', 'games') + (total ? ' · ' + size(total) : ''), pick: () => {
      if (!left.length) { toast(t('Every game of this console is already on this PC.')); return; }
      confirmBox(c.title, count(left.length, 'game', 'games') + ' · ' + size(total) + '\n' + t('They go into the download queue and download one after another. You can keep playing meanwhile.'), t('Download'), async () => {
        const r = await window.rs.tools('downloadMany', left); toast((r.n || 0) + ' ' + t('games added to the download queue'));
      });
    } };
  }));
}
function downloadCovers() {
  const list = visibleGames(); let i = 0, done = 0, found = 0; const total = list.length; if (!total) return;
  toast(t('Downloading covers…') + ' 0 / ' + total);
  const next = () => {
    if (i >= total) return; const g = list[i++]; const urls = coverUrls(g).filter((u) => !missing.has(u)); let k = 0;
    if (!urls.length) { done++; next(); return; }
    const img = new Image();
    img.onload = () => { found++; finish(); };
    img.onerror = () => { missing.add(urls[k]); k++; if (k < urls.length) img.src = urls[k]; else finish(); };
    const finish = () => { done++; if (done % 25 === 0 || done === total) { toast(t('Downloading covers…') + ' ' + done + ' / ' + total); saveMissing(); } if (done === total) toast(found + ' ' + t('covers ready')); next(); };
    img.src = urls[0];
  };
  for (let n = 0; n < 6; n++) next();
}
async function cleanGroups() {
  if (S.drive.stage !== 'ready') { toast(t('Connect Telegram first (Settings > TirsoDrive).')); return; }
  toast(t('Looking for empty groups…'));
  const r = await window.rs.tools('emptyGroups'); if (r.error) { toast(r.error); return; }
  if (!r.list.length) { toast(t('There are no empty groups to delete.')); return; }
  confirmBox(t('Delete') + ' ' + r.list.length + ' ' + t('empty groups'), t('You created these groups and they have no games, saves or screenshots. They will be deleted from Telegram:') + '\n\n' + r.list.map((x) => '• ' + x.title).join('\n'), t('Delete'),
    async () => { const x = await window.rs.tools('deleteGroups', r.list.map((c) => c.id)); toast(x.error || (x.n + ' ' + t('groups deleted.'))); });
}

// ------------------------------------------------------------------ app: updates, problem report, licenses
async function checkUpdates() {
  toast(t('Checking for updates…'));
  const r = await window.rs.tools('updates'); if (r.error) { toast(r.error); return; }
  if (!r.newer) { dialog(t('Updates'), t('You have the latest version.') + '\nRetroStream ' + S.version, []); return; }
  dialog(t('Update available'), t('Build') + ' ' + r.latest + ' (' + t('you have') + ' ' + r.mine + ')\n' + t('RetroStream closes, the installer opens and the app starts again when it finishes. Your games and saves stay.'),
    [{ label: t('Download and install'), primary: true, pick: async () => { const x = await window.rs.tools('install', r.url); if (x && x.error) toast(x.error); } },
      { label: t('Open the downloads page'), pick: () => window.rs.open('https://github.com/dioniciogarcia/retrostream/releases/tag/windows-latest') }]);
}
async function problemReport() {
  const r = await window.rs.tools('report'); if (r.error) { toast(r.error); return; }
  modal((box, close) => { box.classList.add('wide'); box.append(h('h2', {}, t('Problem report')),
    h('p', {}, t('What the game player wrote the last time a game ran. It was copied to the clipboard and saved on your desktop as RetroStream-report.txt, so you can send it.')),
    h('pre', { class: 'log' }, r.text), h('div', { class: 'buttons' }, h('button', { class: 'btn ghost', onClick: () => window.rs.tools('showFile', r.file) }, t('Show the file')), h('button', { class: 'btn primary', onClick: close }, t('Close')))); });
}
function licenses() {
  dialog(t('Licenses and credits'), [
    'RetroStream · ' + t('made for Tirso'),
    '', t('Game engines (libretro):') + ' FCEUmm (GPLv2), Snes9x (non-commercial), Genesis Plus GX (non-commercial), mGBA (MPL 2.0), Mupen64Plus-Next (GPLv2), melonDS DS (GPLv3), MAME 2003-Plus (MAME license), PPSSPP (GPLv2), LRPS2 / PCSX2 (GPLv3), Play! (BSD).',
    t('Programs:') + ' Dolphin (GPLv2+), RPCS3 (GPLv2), Eden (GPLv3).',
    t('App:') + ' Electron (MIT), TDLib (Boost), tdl (MIT), SDL2 (zlib), Dear ImGui (MIT), stb (MIT/public domain), 7-Zip (LGPL), sql.js (MIT), qrcode (MIT).',
    t('Data:') + ' libretro-thumbnails, libretro-database, OpenVGDB, Wikipedia (CC BY-SA).',
    t('Fonts:') + ' Inter, Space Grotesk, Bebas Neue (SIL OFL).',
    '', t('RetroStream does not include games, BIOS files, firmware or keys: use your own.')].join('\n'), []);
}
function about() { dialog('RetroStream ' + S.version, t('Your retro library from your Telegram channels, on the phone, TV and PC. 15 consoles: 11 built in and 4 through Dolphin, RPCS3 and Eden.'), [{ label: t('Licenses and credits'), pick: licenses }]); }

// ------------------------------------------------------------------ phone remote (the phone app finds this PC on the Wi-Fi)
let codeBox = null;
function remoteCode(c) {
  if (codeBox) { codeBox(); codeBox = null; }
  if (!c) return;
  modal((box, close) => {
    codeBox = close;
    box.append(h('h2', {}, t('Pair your phone')),
      h('p', {}, (c.phone ? c.phone + ' ' : '') + t('wants to control RetroStream on this PC. Type this code on the phone:')),
      h('div', { class: 'paircode' }, c.code.split('').map((d) => h('span', {}, d))),
      h('p', { style: 'color:var(--muted);font-size:13px' }, t('The code works for 2 minutes. If you did not ask for this, close this window.')),
      h('div', { class: 'buttons' }, h('button', { class: 'btn ghost', onClick: () => { close(); codeBox = null; } }, t('Close'))));
  });
}
function remotePage() {
  if (!S.remoteInfo) window.rs.remote('get').then((r) => { S.remoteInfo = r; if (S.page === 'remote') render(); });
  const r = S.remoteInfo || { on: true, name: '', phones: [] };
  const act = async (a, v) => { S.remoteInfo = await window.rs.remote(a, v); render(); };
  return page(t('Phone remote'),
    note(t('With the RetroStream phone app on the same Wi-Fi, tap the Cast button to start games on this PC, see what is playing, pause, save, load, take screenshots, back up and exit. The first time, this PC shows a code to type on the phone.')),
    h('div', { class: 'settings' },
      h('section', { class: 'group' }, h('h2', {}, t('This PC')),
        toggleRow(t('Allow the phone remote'), r.on ? t('Phones on this Wi-Fi can find this PC') : t('Off: phones can not find or control this PC'), r.on, '#22C55E', () => act('on', !r.on), 'rm_on'),
        r.ips && r.ips.length ? srow(t('Address of this PC'), r.ips.join(' · '), '#38BDF8', () => {}, 'rm_ip') : null,
        srow(t('Name shown on the phone'), r.name, '#2F6DF6', () => ask(t('Name shown on the phone'), r.name, (v) => act('name', v)), 'rm_name'),
        r.error ? h('p', { class: 'error', style: 'padding:6px 10px' }, t('The remote could not start:') + ' ' + r.error) : null),
      h('section', { class: 'group' }, h('h2', {}, t('Paired phones')),
        r.phones.length ? r.phones.map((n, i) => h('div', { class: 'srow', 'data-key': 'rm_p' + i }, h('span', { class: 'ic', style: 'background:#7A4DFF' }), h('span', { class: 'tx' }, h('b', {}, n)))) : h('p', { style: 'padding:6px 10px;color:var(--muted)' }, t('No phones yet.')),
        r.phones.length ? srow(t('Forget all phones'), t('They will need a new code'), '#FF4D73', () => confirmBox(t('Forget all phones'), t('Every phone must pair again with a new code.'), t('Forget'), () => act('forget')), 'rm_forget') : null),
      h('section', { class: 'group' }, h('h2', {}, t('Help')), h('p', { style: 'padding:6px 12px;color:var(--muted);font-size:13px;line-height:1.6' },
        t('If the phone does not find this PC: both must be on the same Wi-Fi (not a guest network), and Windows must allow RetroStream in the firewall (it asks the first time; choose "Private networks"). You can also type this PC\'s address on the phone.')))));
}

const PAGES = { resolution: resolutionPage, consoles: consolesPage, homeLayout: homePage, pc: pcPage, history: historyPage, missing: missingPage, space: spacePage, controllers: controllersPage, friends: friendsPage, remote: remotePage };
