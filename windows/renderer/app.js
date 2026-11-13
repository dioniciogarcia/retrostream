'use strict';
/* RetroStream for Windows — the screens (home, library, settings, sign-in), same layout as the mockups. */
let t = (s) => s;
const S = {
  prefs: {}, games: [], consoles: [], drive: {}, files: {}, version: '',
  page: 'home', console: null, source: 'all', collection: '', query: '', filter: 'all', selected: null, signTab: 'qr'
};
const byId = {}; // console id -> console

// ------------------------------------------------------------------ small helpers
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style') el.style.cssText = v;
    else if (k.startsWith('on')) el.addEventListener(k.substring(2).toLowerCase(), v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) if (kid !== null && kid !== undefined && kid !== false) el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  return el;
}
function svg(d, size = 20, color = 'currentColor', fill = 'none') {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('width', size); s.setAttribute('height', size); s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('aria-hidden', 'true');
  s.setAttribute('fill', fill); s.setAttribute('stroke', fill === 'none' ? color : 'none'); s.setAttribute('stroke-width', '2'); s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round');
  for (const part of d.split('|')) { const p = document.createElementNS('http://www.w3.org/2000/svg', 'path'); p.setAttribute('d', part); s.append(p); }
  return s;
}
const ICON = {
  pad: 'M7 7h10a5 5 0 0 1 0 10c-1.6 0-2.4-1.2-3.4-2h-3.2c-1 .8-1.8 2-3.4 2A5 5 0 0 1 7 7z|M7 11v3|M5.5 12.5h3|M16 11.5h.01|M18 13.5h.01',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z|M20 20l-4-4', back: 'M15 5l-7 7 7 7', down: 'M12 4v11|M7 10l5 5 5-5|M4 20h16',
  play: 'M6 4l14 8-14 8z', star: 'M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z',
  dice: 'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z|M8 8h.01|M12 12h.01|M16 16h.01', more: 'M5 12h.01|M12 12h.01|M19 12h.01'
};
function toast(msg) { const el = document.getElementById('toast'); el.textContent = msg; el.classList.add('show'); clearTimeout(toast.tm); toast.tm = setTimeout(() => el.classList.remove('show'), 3500); }
function count(n, one, many) { return n + ' ' + (n === 1 ? t(one) : t(many)); }
function size(b) { if (!b) return ''; const u = ['B', 'KB', 'MB', 'GB']; let i = 0; while (b >= 1024 && i < 3) { b /= 1024; i++; } return b.toFixed(i > 1 ? 1 : 0) + ' ' + u[i]; }
function pref(k) { return S.prefs[k]; }
function setPref(k, v) { S.prefs[k] = v; window.rs.setPref(k, v); }
const listPref = (k) => new Set(pref(k) || []);
function toggleIn(k, id) { const s = listPref(k); if (s.has(id)) s.delete(id); else s.add(id); setPref(k, [...s]); return s.has(id); }

// ------------------------------------------------------------------ the library
function visibleGames() { const hidden = listPref('hidden'); const off = pref('shownConsoles') || {}; return S.games.filter((g) => !hidden.has(g.id) && off[g.console] !== false); }
function playtime(g) { const m = Math.floor(((pref('playtime') || {})[g.id] || 0) / 60000); return m < 1 ? '' : m < 60 ? m + ' min' : Math.floor(m / 60) + ' h ' + (m % 60) + ' min'; }
function kept(g) { return !!g.local || !!(pref('kept') || {})[g.id]; }
function plays() { return (pref('plays') || []).map((p) => ({ game: S.games.find((g) => g.id === p.id), at: p.at })).filter((p) => p.game); }
function recent() { const seen = new Set(); const out = []; for (const p of plays()) if (!seen.has(p.game.id)) { seen.add(p.game.id); out.push(p.game); if (out.length >= 12) break; } return out; }
function newGames() { const added = pref('addedAt') || {}; const since = Date.now() - 14 * 86400000; return visibleGames().filter((g) => (added[g.id] || 0) > since).sort((a, b) => (added[b.id] - added[a.id]) || (b.messageId - a.messageId)); }
function sorted(list) {
  const s = pref('sort') || 'name';
  const out = [...list];
  if (s === 'added') out.sort((a, b) => b.messageId - a.messageId);
  else if (s === 'most') { const pt = pref('playtime') || {}; out.sort((a, b) => ((pt[b.id] || 0) - (pt[a.id] || 0)) || a.title.localeCompare(b.title)); }
  else if (s === 'recent') { const r = recent().map((g) => g.id); out.sort((a, b) => (r.indexOf(a.id) + 1 || 1e9) - (r.indexOf(b.id) + 1 || 1e9)); }
  else out.sort((a, b) => a.title.localeCompare(b.title) * (s === 'name_desc' ? -1 : 1));
  return out;
}
function shownGames() {
  let list = visibleGames();
  if (S.source === 'fav') { const f = listPref('favorites'); list = list.filter((g) => f.has(g.id)); }
  else if (S.source === 'col') { const c = new Set((pref('collections') || {})[S.collection] || []); list = list.filter((g) => c.has(g.id)); }
  else if (S.console) list = list.filter((g) => g.console === S.console);
  if (S.query) { const q = S.query.toLowerCase(); list = list.filter((g) => g.title.toLowerCase().includes(q)); }
  if (S.filter === 'downloaded') list = list.filter(kept);
  if (S.filter === 'pc') list = list.filter((g) => g.local);
  return sorted(list);
}

// ------------------------------------------------------------------ covers (libretro thumbnails, tried name by name)
function thumbName(n) { return n.replace(/[&*/:`<>?\\|"]/g, '_'); }
function coverUrls(g) {
  const c = byId[g.console]; if (!c) return [];
  let stem = g.fileName || g.title; const dot = stem.lastIndexOf('.'); if (dot > 0) stem = stem.substring(0, dot);
  const names = []; if (g.cover) names.push(g.cover);
  names.push(thumbName(stem));
  const base = g.base || g.title;
  for (const r of ['USA', 'Europe', 'World', 'Japan']) names.push(thumbName(base + ' (' + r + ')'));
  names.push(thumbName(base));
  return [...new Set(names)].map((n) => 'https://thumbnails.libretro.com/' + encodeURIComponent(c.thumbs) + '/Named_Boxarts/' + encodeURIComponent(n) + '.png');
}
const missing = new Set(JSON.parse(localStorage.getItem('missingCovers') || '[]'));
function saveMissing() { try { localStorage.setItem('missingCovers', JSON.stringify([...missing].slice(-20000))); } catch (_) { /* full */ } }
function cover(g, w, hgt, extras) {
  const box = h('div', { class: 'cover', style: `width:${w}px;height:${hgt}px` }, g.title);
  const urls = coverUrls(g).filter((u) => !missing.has(u)); let i = 0;
  if (urls.length) {
    const img = h('img', { alt: '', loading: 'lazy', decoding: 'async' });
    img.onerror = () => { missing.add(urls[i]); saveMissing(); i++; if (i < urls.length) img.src = urls[i]; else img.remove(); };
    img.src = urls[0]; box.append(img);
  }
  if (extras !== false) {
    if (listPref('favorites').has(g.id)) box.append(h('span', { class: 'fav', 'aria-label': t('Favorite') }, '★'));
    if (kept(g)) box.append(h('span', { class: 'got', title: t('On this PC') }, '✓'));
    if (listPref('completed').has(g.id)) box.append(h('span', { class: 'done' }, t('Completed')));
    const f = S.files[g.fileId];
    if (f && f.state === 'downloading') box.append(h('div', { class: 'bar', 'data-file': g.fileId }, h('div', { style: `width:${pct(f)}%` })));
  }
  return box;
}
const RATIO = { NES: 0.72, SNES: 1.38, MD: 0.72, GB: 1, GBC: 1, GBA: 1, N64: 1.38, NDS: 1.1, MAME: 0.75, PSP: 0.58, PS2: 0.7, GC: 0.7, WII: 0.7, PS3: 0.86, SWITCH: 0.62 };

// ------------------------------------------------------------------ screens
function render() {
  const app = document.getElementById('app'); const keep = document.activeElement && document.activeElement.dataset.key;
  const caret = document.activeElement && document.activeElement.tagName === 'INPUT' ? document.activeElement.selectionStart : null;
  const scrolls = [...app.querySelectorAll('.scroll')].map((el) => el.scrollTop); const samePage = render.page === S.page; render.page = S.page;
  app.replaceChildren();
  const d = S.drive;
  const signingIn = d.stage !== 'ready' && !pref('skipped');
  if (signingIn && !['settings', 'sources', ...Object.keys(PAGES)].includes(S.page)) app.append(signIn());
  else if (S.page === 'home' && homeTheme() === 'cinematic') app.append(cinematic());
  else {
    app.append(header());
    if (S.page === 'library') app.append(library());
    else if (S.page === 'settings') app.append(settings());
    else if (S.page === 'sources') app.append(sources());
    else if (S.page === 'engines') app.append(engines());
    else if (PAGES[S.page]) app.append(PAGES[S.page]());
    else app.append(home());
    const hb = hints(); if (hb) app.append(hb);
  }
  if (samePage) app.querySelectorAll('.scroll').forEach((el, i) => { if (scrolls[i]) el.scrollTop = scrolls[i]; });
  if (keep) { const el = app.querySelector(`[data-key="${CSS.escape(keep)}"]`); if (el) { el.focus({ preventScroll: true }); if (caret != null && el.setSelectionRange) try { el.setSelectionRange(caret, caret); } catch (_) { /* not text */ } } }
}
function go(page, opts) { Object.assign(S, opts || {}); S.page = page; render(); const f = document.querySelector('#app [data-key]'); if (f && !opts?.keepFocus) f.focus({ preventScroll: true }); }

function hints() {
  if (!S.padSeen) return null;
  const items = S.page === 'library' ? [['A', t('Play')], ['Y', t('Options')], ['X', t('Favorite')], ['LB RB', t('Console')], ['B', t('Back')]]
    : S.page === 'home' ? [['A', t('Open')], ['Y', t('Options')], ['X', t('Favorite')], ['B / ⌂', t('Menu')]] : [['A', t('Choose')], ['B', t('Back')]];
  return h('footer', { class: 'hints' }, items.map(([k, v]) => h('span', {}, h('b', {}, k), v)));
}
function header() {
  const d = S.drive; const nav = [['home', t('Home')], ['library', t('Library')], ['fav', t('Favorites & collections')], ['media', t('Screenshots')], ['settings', t('Settings')]];
  const on = (k) => (k === 'fav' ? S.page === 'library' && (S.source === 'fav' || S.source === 'col') : k === 'library' ? S.page === 'library' && S.source !== 'fav' && S.source !== 'col' : S.page === k || (k === 'settings' && S.page !== 'media' && (S.page === 'sources' || S.page === 'engines' || !!PAGES[S.page])));
  // What is being typed survives the screen redrawing itself (downloads, friends and Telegram updates redraw it).
  const input = h('input', { placeholder: t('Search all games  (Ctrl+F)'), value: S.searchDraft != null ? S.searchDraft : S.query, 'aria-label': t('Search games'), 'data-key': 'search',
    onInput: (e) => { S.searchDraft = e.target.value; },
    onKeydown: (e) => {
      if (e.key === 'Enter') {
        // preventDefault: otherwise the same Enter press also "clicks" the button that gets the focus next (Home).
        e.preventDefault(); e.stopPropagation();
        const q = e.target.value.trim(); S.searchDraft = null;
        go('library', { query: q, console: q ? null : (S.console || firstConsole()), source: 'all', keepFocus: true });
        const box = document.querySelector('[data-key="search"]'); if (box) { box.focus(); box.setSelectionRange(box.value.length, box.value.length); }
      } else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); S.searchDraft = null; e.target.value = ''; if (S.query) go('library', { query: '', console: S.console || firstConsole(), keepFocus: true }); e.target.blur(); }
    } });

  return h('header', { class: 'top' },
    h('div', { class: 'brand' }, h('div', { class: 'logo' }, svg(ICON.pad, 22, '#fff')), 'RetroStream'),
    h('nav', { class: 'nav', 'aria-label': t('Main') }, nav.map(([k, label]) => h('button', { class: on(k) ? 'on' : '', 'data-key': 'nav_' + k,
      onClick: () => (S.searchDraft = null, k === 'fav') ? go('library', { source: 'fav', console: null, query: '' }) : k === 'media' ? (S.media = null, go('media')) : k === 'library' ? go('library', { source: 'all', console: S.console || firstConsole(), query: '' }) : go(k) }, label))),
    h('label', { class: 'search' }, svg(ICON.search, 16, '#9AA3B8'), input),
    h('button', { class: 'iconbtn', 'aria-label': t('Downloads'), 'data-key': 'downloads', onClick: showDownloads }, svg(ICON.down, 20, '#E9ECF5'),
      activeDownloads().length ? h('span', { class: 'badge' }, activeDownloads().length) : null),
    h('div', { class: 'status', id: 'status' }, h('span', { class: 'dot' + (d.stage === 'ready' ? ' ok' : '') }), h('span', { id: 'statustext' }, statusText())));
}
function statusText() { const d = S.drive; return d.stage === 'ready' ? (d.busy ? d.status : t('TirsoDrive connected')) : t('Not connected');
}
function firstConsole() { const list = visibleConsoles(); const withGames = list.find((c) => S.games.some((g) => g.console === c.id)); return (withGames || list[0]).id; }

function home() {
  const rec = recent(); const last = rec[0]; const c = last ? byId[last.console] : byId[firstConsole()];
  const left = h('button', { class: 'continue', style: `background:${c.color}`, 'data-key': 'continue', 'data-game': last ? last.id : null, 'data-recent': '1',
    onClick: () => last ? playGame(last) : go('library', { console: c.id, source: 'all' }), onContextmenu: (e) => { if (last) { e.preventDefault(); gameMenu(last, e, true); } } },
  h('span', { class: 'kicker' }, last ? t('CONTINUE') + ' · ' + c.title.toUpperCase() : t('START PLAYING')),
  h('h1', { class: 'display' }, last ? last.title : t('Pick a console')),
  last && playtime(last) ? h('span', { class: 'pill' }, playtime(last) + ' ' + t('played')) : null,
  last ? h('div', { class: 'art' }, cover(last, 230, Math.round(230 / (RATIO[last.console] || 1)), false)) : null,
  h('div', { class: 'actions' }, h('span', { class: 'btn white' }, svg(ICON.play, 14, '#14161E', '#14161E'), last ? t('Resume') : t('Open library'))));
  const rows = h('div', { class: 'rows' });
  if (sectionOn('friends')) { const fr = friendsRow(); if (fr) rows.append(...fr); }
  const recRow = sectionOn('continue') ? rec.slice(1) : rec;
  if (recRow.length && sectionOn('recent')) {
    rows.append(h('div', { class: 'rowhead' }, h('h2', {}, t('Recently played')), h('button', { class: 'link', 'data-key': 'history', onClick: () => go('history') }, t('History'))));
    rows.append(h('div', { class: 'strip', onWheel: sideways }, recRow.map((g) => miniCard(g, 'rec', true))));
  }
  const fresh = newGames().slice(0, 15);
  if (fresh.length && sectionOn('new')) {
    rows.append(h('div', { class: 'rowhead' }, h('h2', {}, t('New in your channels') + ' · ' + fresh.length)));
    rows.append(h('div', { class: 'strip', onWheel: sideways }, fresh.map((g) => { const m = miniCard(g, 'new', false); m.querySelector('.cover').append(h('span', { class: 'tag', style: 'background:#C9363B' }, t('NEW'))); return m; })));
  }
  if (sectionOn('consoles') || !sectionOn('continue')) rows.append(h('div', { class: 'rowhead' }, h('h2', {}, t('Consoles')),
    h('button', { class: 'link', 'data-key': 'surprise', onClick: surprise }, svg(ICON.dice, 16, 'var(--ring)'), t('Surprise me'))));
  if (sectionOn('consoles') || !sectionOn('continue')) rows.append(h('div', { class: 'consoles' }, visibleConsoles().map((cn) => {
    const n = S.games.filter((g) => g.console === cn.id).length;
    return h('button', { class: 'ctile' + (cn.id === 'SWITCH' ? ' light' : ''), style: `background:${cn.color}`, 'data-key': 'con_' + cn.id, 'aria-label': cn.title, onClick: () => go('library', { console: cn.id, source: 'all', query: '', filter: 'all' }) },
      consoleLogo(cn), h('span', {}, cn.big && !n ? t('New · PC only') : count(n, 'game', 'games')));
  })));
  if (S.drive.stage === 'ready' && S.games.length === 0 && !S.drive.busy) rows.append(h('p', { class: 'empty' }, t('No games yet. Open Settings > TirsoDrive and tap Scan all channels.')));
  return h('main', { class: 'scroll' }, h('div', { class: 'home' }, sectionOn('continue') ? left : null, rows));
}
/** The console's logo in white (the same pictures as the phone and TV apps); its name for PS3 and Switch. */
const LOGOS = new Set(['NES', 'SNES', 'MD', 'GB', 'GBC', 'GBA', 'N64', 'NDS', 'MAME', 'PSP', 'PS2', 'GC', 'WII', 'PS3', 'SWITCH']);
function consoleLogo(cn) {
  const style = logoStyle();
  if (style === 'mono' && MONO.has(cn.id)) return h('span', { class: 'clogo-plate' }, h('img', { class: 'clogo', src: 'logos/' + cn.id + '.png', alt: '' }));
  if (style === 'text' || style === 'mono') return h('b', { class: 'wordmark' }, cn.id === 'PS3' ? 'PlayStation 3' : cn.id === 'SWITCH' ? 'Nintendo Switch' : cn.title);
  if (!LOGOS.has(cn.id)) return h('b', { class: 'wordmark' }, cn.id === 'PS3' ? 'PlayStation 3' : cn.id === 'SWITCH' ? 'Nintendo Switch' : cn.title);
  return h('span', { class: 'clogo-plate' }, h('img', { class: 'clogo', src: 'logos/' + cn.id + '-color.png', alt: '' }));
}
// ------------------------------------------------------------------ home theme C: cinematic (one console fills the screen)
function homeTheme() { return pref('homeTheme') || 'classic'; }
function cineActive() { return S.page === 'home' && homeTheme() === 'cinematic' && document.querySelector('.cine'); }
const CONSOLE_INFO = {
  NES: ['1983', 'Home console', 'Nintendo', 'The 8-bit console that brought home video games back to life and introduced Mario, Zelda and Metroid.'],
  SNES: ['1990', 'Home console', 'Nintendo', 'Nintendo\u2019s 16-bit console, home of Super Mario World, A Link to the Past and Super Metroid.'],
  MD: ['1988', 'Home console', 'Sega', 'Sega\u2019s 16-bit console, called Genesis in North America, and the home of Sonic the Hedgehog.'],
  GB: ['1989', 'Handheld', 'Nintendo', 'The handheld that made gaming portable. It launched with Tetris.'],
  GBC: ['1998', 'Handheld', 'Nintendo', 'The Game Boy in color, still able to play the original Game Boy cartridges.'],
  GBA: ['2001', 'Handheld', 'Nintendo', 'Nintendo\u2019s 32-bit handheld, with a wide screen and a huge library of 2D classics.'],
  N64: ['1996', 'Home console', 'Nintendo', 'Nintendo\u2019s first console with an analog stick, home of Super Mario 64 and Ocarina of Time.'],
  NDS: ['2004', 'Handheld', 'Nintendo', 'Two screens, one of them a touch screen, in a clamshell you can take anywhere.'],
  MAME: ['1970s – 2000s', 'Arcade', 'Many makers', 'Arcade machines from the golden age, played through MAME.'],
  PSP: ['2004', 'Handheld', 'Sony', 'Sony\u2019s first handheld, with its games on small UMD discs.'],
  PS2: ['2000', 'Home console', 'Sony', 'The best-selling home console of all time, which could also play DVDs.'],
  GC: ['2001', 'Home console', 'Nintendo', 'Nintendo\u2019s console with small 8 cm discs and a handle on the back.'],
  WII: ['2006', 'Home console', 'Nintendo', 'The Wii Remote\u2019s motion controls brought the whole family in front of the TV.'],
  PS3: ['2006', 'Home console', 'Sony', 'Sony\u2019s HD console, with its games on Blu-ray discs.'],
  SWITCH: ['2017', 'Hybrid console', 'Nintendo', 'A console you can play on the TV or take with you as a handheld.']
};
function cineConsoles() { return visibleConsoles(); }
function cineIndex() { const list = cineConsoles(); const i = list.findIndex((c) => c.id === S.cineConsole); return i < 0 ? Math.max(0, list.findIndex((c) => S.games.some((g) => g.console === c.id))) : i; }
function cineStep(step) {
  const list = cineConsoles(); const i = (cineIndex() + step + list.length) % list.length;
  S.cineConsole = list[i].id; render();
  const f = document.querySelector('[data-key="cine_enter"]'); if (f) f.focus({ preventScroll: true });
}
/** A box art from the console's games, changing every few seconds (games without a cover are skipped). */
function cineArt(consoleId, img, tries) {
  const pool = visibleGames().filter((g) => g.console === consoleId);
  if (!pool.length || (tries || 0) > 6) { img.style.opacity = '0'; return; }
  const g = pool[Math.floor(Math.random() * pool.length)]; const urls = coverUrls(g).filter((u) => !missing.has(u)); let i = 0;
  if (!urls.length) { cineArt(consoleId, img, (tries || 0) + 1); return; }
  const probe = new Image();
  probe.onload = () => { if (!img.isConnected) return; img.style.opacity = '0'; setTimeout(() => { img.src = probe.src; img.alt = g.title; img.style.opacity = '1'; }, 250); };
  probe.onerror = () => { missing.add(urls[i]); saveMissing(); i++; if (i < urls.length) probe.src = urls[i]; else cineArt(consoleId, img, (tries || 0) + 1); };
  probe.src = urls[0];
}
setInterval(() => { const img = document.querySelector('.cine-art img'); if (img && cineActive() && !document.querySelector('#layer .shade') && !document.hidden) cineArt(S.cineConsole, img); }, 7000);
function cinematic() {
  const list = cineConsoles(); const c = list[cineIndex()]; S.cineConsole = c.id;
  const info = CONSOLE_INFO[c.id] || ['', '', '', ''];
  const n = S.games.filter((g) => g.console === c.id && !listPref('hidden').has(g.id)).length;
  const last = plays().find((p) => p.game.console === c.id);
  const img = h('img', { alt: '', style: 'opacity:0' });
  setTimeout(() => cineArt(c.id, img), 0);
  const dots = h('nav', { class: 'cine-dots', 'aria-label': t('Consoles') }, list.map((x, i) => h('button', { class: 'dot' + (x.id === c.id ? ' on' : ''), 'aria-label': x.title, tabindex: '-1',
    onClick: () => { S.cineConsole = x.id; render(); } })));
  const enter = h('button', { class: 'cine-enter', 'data-key': 'cine_enter', onClick: () => go('library', { console: c.id, source: 'all', query: '', filter: 'all' }) }, h('span', {}, 'A'), t('ENTER'));
  const d = S.drive;
  return h('main', { class: 'cine', style: `--tone:${c.color}`, onWheel: (e) => { if (Math.abs(e.deltaY) > 20 && !cineActive.lock) { cineActive.lock = true; setTimeout(() => { cineActive.lock = false; }, 280); cineStep(e.deltaY > 0 ? 1 : -1); } } },
    h('div', { class: 'cine-art' }, img),
    h('div', { class: 'cine-fade' }),
    dots,
    h('section', { class: 'cine-text' },
      h('span', { class: 'cine-meta' }, [info[0], t(info[1]), t(info[2])].filter(Boolean).join(' · ')),
      h('h1', {}, c.title.toUpperCase()),
      h('span', { class: 'cine-count' }, c.big && !n ? t('NEW · PC ONLY') : n + ' ' + (n === 1 ? t('GAME AVAILABLE') : t('GAMES AVAILABLE'))),
      h('p', {}, t(info[3])),
      h('div', { class: 'cine-actions' }, enter, last ? h('span', { class: 'cine-last' }, t('Last played:') + ' ' + last.game.title + (playtime(last.game) ? ' · ' + playtime(last.game) : '')) : null)),
    h('div', { class: 'cine-top' },
      h('span', { class: 'status' }, h('span', { class: 'dot' + (d.stage === 'ready' ? ' ok' : '') }), h('span', { id: 'statustext' }, statusText())),
      h('button', { class: 'iconbtn', 'aria-label': t('Search games'), onClick: askSearch }, svg(ICON.search, 18, '#E9ECF5')),
      h('button', { class: 'iconbtn', 'aria-label': t('Favorites & collections'), onClick: () => go('library', { source: 'fav', console: null, query: '' }) }, svg(ICON.star, 18, '#E9ECF5')),
      h('button', { class: 'iconbtn', 'aria-label': t('Downloads'), onClick: showDownloads }, svg(ICON.down, 18, '#E9ECF5')),
      h('button', { class: 'iconbtn', 'aria-label': t('Settings'), onClick: () => go('settings') }, svg('M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z|M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z', 18, '#E9ECF5'))),
    h('div', { class: 'cine-hints' },
      h('span', {}, h('b', {}, '▲ ▼'), t('Console')), h('span', {}, h('b', {}, 'A'), t('Open')), h('span', {}, h('b', {}, 'Y'), t('Surprise me')), h('span', {}, h('b', {}, 'B / ⌂'), t('Menu'))));
}
function askSearch() { ask(t('Search games'), '', (q) => go('library', { query: q, console: null, source: 'all' })); }
function miniCard(g, prefix, recentMenu) {
  const w = 128; const hh = Math.round(w / (RATIO[g.console] || 1)) > 170 ? 170 : Math.round(w / (RATIO[g.console] || 1));
  return h('button', { class: 'mini', 'data-key': prefix + '_' + g.id, 'data-game': g.id, 'data-recent': prefix === 'rec' ? '1' : null, onClick: () => prefix === 'rec' ? playGame(g) : openGame(g), onContextmenu: (e) => { e.preventDefault(); gameMenu(g, e, recentMenu); } },
    cover(g, w, hh), h('div', { class: 't' }, g.title), h('div', { class: 's' }, byId[g.console].title + (playtime(g) ? ' · ' + playtime(g) : '')));
}
function sideways(e) { if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) { e.currentTarget.scrollLeft += e.deltaY; e.preventDefault(); } }
function openGame(g) { go('library', { console: g.console, source: 'all', query: '', selected: g.id }); }

function library() {
  const wrap = h('main', { style: 'flex:1;min-height:0;display:flex;flex-direction:column' });
  const tabs = h('div', { class: 'tabs' });
  const cols = Object.keys(pref('collections') || {});
  if (S.source === 'fav' || S.source === 'col') {
    tabs.append(h('button', { class: 'chip' + (S.source === 'fav' ? ' on' : ''), 'data-key': 'tab_fav', onClick: () => go('library', { source: 'fav', keepFocus: true }) }, '★ ' + t('Favorites')));
    for (const n of cols) tabs.append(h('button', { class: 'chip' + (S.source === 'col' && S.collection === n ? ' on' : ''), 'data-key': 'tab_col_' + n, onClick: () => go('library', { source: 'col', collection: n, keepFocus: true }) }, n));
  } else for (const c of visibleConsoles()) tabs.append(h('button', { class: 'chip' + (S.console === c.id ? ' on' : ''), 'data-key': 'tab_' + c.id, onClick: () => go('library', { console: c.id, query: '', keepFocus: true }) }, c.id === 'MD' ? 'Mega Drive' : c.id === 'SWITCH' ? 'Switch' : c.id === 'WII' ? 'Wii' : c.id === 'GC' ? 'GameCube' : c.id));
  wrap.append(h('div', { class: 'libtop' }, h('button', { class: 'iconbtn', 'aria-label': t('Back to Home'), 'data-key': 'back', onClick: () => go('home') }, svg(ICON.back, 20, '#E9ECF5')), tabs));
  const games = shownGames();
  const title = S.source === 'fav' ? t('Favorites') : S.source === 'col' ? S.collection : S.query && !S.console ? '"' + S.query + '"' : (byId[S.console] || {}).title || t('Library');
  const sortNames = { name: t('Name A–Z'), name_desc: t('Name Z–A'), added: t('Recently added'), recent: t('Recently played'), most: t('Most played') };
  wrap.append(h('div', { class: 'libhead' }, h('h1', {}, title), h('span', { class: 'n' }, count(games.length, 'game', 'games')),
    h('div', { class: 'filters' },
      h('button', { class: 'chip' + (S.filter === 'all' ? ' on' : ''), 'data-key': 'f_all', onClick: () => go('library', { filter: 'all', keepFocus: true }) }, t('All')),
      h('button', { class: 'chip' + (S.filter === 'downloaded' ? ' on' : ''), 'data-key': 'f_dl', onClick: () => go('library', { filter: 'downloaded', keepFocus: true }) }, '✓ ' + t('On this PC')),
      S.games.some((g) => g.local) ? h('button', { class: 'chip' + (S.filter === 'pc' ? ' on' : ''), 'data-key': 'f_pc', onClick: () => go('library', { filter: 'pc', keepFocus: true }) }, t('Only in my folders')) : null,
      h('button', { class: 'chip', 'data-key': 'f_sort', onClick: () => choose(t('Sort by'), Object.entries(sortNames).map(([k, v]) => ({ label: v, on: (pref('sort') || 'name') === k, pick: () => { setPref('sort', k); render(); } }))) }, t('Sort:') + ' ' + sortNames[pref('sort') || 'name']))));
  const grid = h('div', { class: 'grid scroll', role: 'list' });
  if (!games.length) grid.append(h('p', { class: 'empty' }, S.drive.busy ? S.drive.status : t('No games here yet.')));
  const sel = games.find((g) => g.id === S.selected) || games[0];
  for (const g of games.slice(0, 3000)) {
    const r = RATIO[g.console] || 1; const w = 150; const hh = Math.round(w / r);
    const card = h('button', { class: 'card', role: 'listitem', 'data-key': 'g_' + g.id, 'data-game': g.id, onClick: () => { S.selected = g.id; playGame(g); },
      onMouseenter: () => { if (S.selected !== g.id) { S.selected = g.id; renderPanel(g); } }, onFocus: () => { S.selected = g.id; renderPanel(g); }, onContextmenu: (e) => { e.preventDefault(); gameMenu(g, e, false); } },
    cover(g, w, Math.min(hh, 220)), h('div', { class: 't' }, g.title), h('div', { class: 's' }, gameLine(g)));
    card.style.width = '100%'; card.querySelector('.cover').style.width = '100%';
    grid.append(card);
  }
  const panel = h('aside', { class: 'panel', id: 'panel', 'aria-label': t('Selected game') });
  wrap.append(h('div', { class: 'libbody' }, grid, panel));
  setTimeout(() => { if (sel) renderPanel(sel); }, 0);
  return wrap;
}
function gameLine(g) { return g.local ? t('This PC') + (g.size ? ' · ' + size(g.size) : '') : kept(g) ? t('On this PC') : 'Telegram' + (g.size ? ' · ' + size(g.size) : ''); }
function renderPanel(g) {
  const p = document.getElementById('panel'); if (!p) return;
  const info = S.files[g.fileId] || {};
  const r = RATIO[g.console] || 1;
  p.replaceChildren(...[
    cover(g, 300, Math.min(Math.round(300 / r), 300)),
    h('h2', {}, g.title),
    h('div', { class: 'meta' }, [byId[g.console].title, g.year || '', g.genre, g.dev || g.pub].filter(Boolean).join(' · ')),
    g.desc ? h('p', { class: 'desc' }, g.desc) : h('p', { class: 'desc', style: 'color:#9AA3B8' }, t('No description yet. Right-click (or Y) › Search game info to find it.')),
    h('div', { class: 'meta', style: 'font-size:12px' }, [g.fileName, size(g.size)].filter(Boolean).join(' · ')),
    info.state === 'downloading' ? h('div', {}, h('div', { class: 'progress' }, h('div', { style: `width:${info.size ? Math.round(info.have * 100 / info.size) : 0}%` })), h('div', { class: 'meta', style: 'margin-top:6px' }, t('Downloading') + ' ' + size(info.have) + ' / ' + size(info.size))) : null,
    h('div', { class: 'actions' },
      h('button', { class: 'btn primary', style: 'flex:1;justify-content:center', 'data-key': 'p_play', onClick: () => playGame(g) }, svg(ICON.play, 14, '#fff', '#fff'), kept(g) ? t('Play') : t('Download and play')),
      h('button', { class: 'iconbtn', 'aria-label': t('Favorite'), 'data-key': 'p_fav', onClick: () => { toggleIn('favorites', g.id); render(); } }, svg(ICON.star, 18, '#FFC94D', listPref('favorites').has(g.id) ? '#FFC94D' : 'none')),
      h('button', { class: 'iconbtn', 'aria-label': t('More options'), 'data-key': 'p_more', onClick: (e) => gameMenu(g, e, false) }, svg(ICON.more, 22, '#E9ECF5')))].filter(Boolean));
  if (g.fileId && !S.files[g.fileId]) window.rs.game('inspect', g).then((i) => { S.files[g.fileId] = i; });
}

// ------------------------------------------------------------------ playing and downloading
function pct(i) { return i && i.size ? Math.min(100, Math.round(i.have * 100 / i.size)) : 0; }
function activeDownloads() { return S.games.filter((g) => (S.files[g.fileId] || {}).state === 'downloading'); }
async function playGame(g, fresh) {
  if (!kept(g)) {
    if (S.drive.stage !== 'ready') { toast(t('Connect Telegram first (Settings > TirsoDrive).')); return; }
    const i = await window.rs.game('download', g); if (i) S.files[g.fileId] = i;
    toast(t('Downloading') + ' ' + g.title + '…'); S.pendingPlay = g.id; render(); return;
  }
  toast(t('Starting') + ' ' + g.title + '…');
  const r = await window.rs.play(g, { fresh: !!fresh });
  if (r && r.error) { toast(r.error); return; }
  const p = (pref('plays') || []).filter((x) => Date.now() - x.at < 400 * 86400000); p.unshift({ id: g.id, at: Date.now() }); setPref('plays', p.slice(0, 300));
}
/** Downloads window: updates itself while it is open. */
function showDownloads() {
  modal((box, close) => {
    const body = h('div', { class: 'list' });
    const fill = () => {
      const list = activeDownloads();
      body.replaceChildren(...(list.length ? list.map((g) => { const i = S.files[g.fileId];
        return h('div', { style: 'padding:10px 12px;border-radius:10px;background:#1C2130' }, h('div', { style: 'display:flex;gap:8px' }, h('b', { style: 'flex:1' }, g.title), h('span', {}, pct(i) + '%')),
          h('div', { class: 'progress', style: 'margin-top:8px' }, h('div', { style: `width:${pct(i)}%` })),
          h('div', { style: 'font-size:12px;color:#9AA3B8;margin-top:6px' }, size(i.have) + ' / ' + size(i.size))); })
        : [h('p', {}, t('Nothing is downloading.'))]));
    };
    fill(); S.liveDownloads = fill;
    box.append(h('h2', {}, t('Downloads')), body, h('div', { class: 'buttons' },
      h('button', { class: 'btn ghost', onClick: () => { close(); downloadConsole(); } }, t('Download a whole console')),
      h('button', { class: 'btn ghost', onClick: openDownloads }, t('Open folder')),
      h('button', { class: 'btn ghost', onClick: () => activeDownloads().forEach((g) => window.rs.game('cancel', g)) }, t('Stop all')),
      h('button', { class: 'btn primary', onClick: close }, t('Close'))));
  });
}
function surprise() {
  const pool = visibleGames(); if (!pool.length) { toast(t('Your library is empty.')); return; }
  const played = new Set(recent().map((g) => g.id)); const fresh = pool.filter((g) => !played.has(g.id) && !listPref('completed').has(g.id));
  const src = fresh.length && Math.random() < 0.8 ? fresh : pool; const g = src[Math.floor(Math.random() * src.length)];
  dialog(g.title, byId[g.console].title, [{ label: t('Another'), pick: surprise }, { label: t('View game'), pick: () => openGame(g) }, { label: t('Play'), primary: true, pick: () => playGame(g) }]);
}

// ------------------------------------------------------------------ right-click menu
function gameMenu(g, e, recentMenu) {
  const items = [];
  items.push([kept(g) ? t('Play') : t('Download and play'), 'Enter', () => playGame(g)]);
  if (recentMenu) items.push([t('Play from the start'), '', () => confirmBox(g.title, t("The game starts without loading where you left off. Your saves are not deleted."), t('Play'), () => playGame(g, true))]);
  items.push([t('View game'), '', () => openGame(g)]);
  items.push([t('View game information'), 'I', () => gameInfo(g)]);
  items.push([t('Search game info'), '', () => searchInfo(g)]);
  items.push([t('Rename'), '', () => ask(t('Rename'), g.title, async (v) => { await window.rs.meta('rename', g, v); toast(t('Name changed')); })]);
  items.push([t('Reset game info'), '', () => confirmBox(g.title, t('The name, cover and description you chose are removed and looked up again.'), t('Reset'), async () => { await window.rs.meta('reset', g); toast(t('Looking it up again…')); })]);
  items.push([listPref('favorites').has(g.id) ? t('Remove from favorites') : t('Add to favorites'), 'F', () => { toggleIn('favorites', g.id); render(); }]);
  items.push([t('Add to a collection'), '', () => collectionPicker(g)]);
  items.push([listPref('completed').has(g.id) ? t('Remove "Completed"') : t('Mark as completed'), '', () => { toggleIn('completed', g.id); render(); }]);
  if (!kept(g) && (S.files[g.fileId] || {}).state !== 'downloading') items.push([t('Download to play offline'), '', async () => { const i = await window.rs.game('download', g); if (i) S.files[g.fileId] = i; toast(t('Downloading') + ' ' + g.title + '…'); }]);
  if ((S.files[g.fileId] || {}).state === 'downloading') items.push([t('Stop download'), '', () => window.rs.game('cancel', g)]);
  items.push([t('This game’s settings'), '', () => gameSettings(g)]);
  items.push([t('Screenshots'), '', () => screenshots(g)]);
  items.push([t('Back up the save now'), '', () => backupNow(g)]);
  items.push([t('Share with a friend'), '', () => shareGame(g)]);
  items.push([t('Add to the desktop'), '', () => desktopShortcut(g)]);
  items.push([t('Walkthrough'), '', () => window.rs.tools('walkthrough', g.title + ' ' + byId[g.console].title)]);
  if (!recentMenu) items.push([t('Play from the start'), '', () => confirmBox(g.title, t("The game starts without loading where you left off. Your saves are not deleted."), t('Play'), () => playGame(g, true))]);
  if (kept(g)) items.push([t('Show the file in its folder'), '', () => window.rs.tools('showFile', g.local ? g.path : (pref('kept') || {})[g.id])]);
  if (kept(g) && !g.local && !(pref('pcLinked') || {})[g.id]) items.push([t('Delete downloaded file'), '', () => confirmBox(g.title, t('It is deleted from this PC to free up space. The game stays in Telegram and in your library.'), t('Delete'), async () => { const r = await window.rs.game('remove', g); toast(r && r.error ? r.error : t('File deleted')); })]);
  items.push([t('Hide game'), '', () => confirmBox(g.title, t("It won't show in your library. You can show it again in Settings > Hidden games."), t('Hide'), () => { toggleIn('hidden', g.id); render(); })]);
  if (recentMenu) {
    items.push([t('Remove from recents'), '', () => { setPref('plays', (pref('plays') || []).filter((p) => p.id !== g.id)); render(); }]);
    items.push([t('Clear all recents'), '', clearRecents]);
  }
  const layer = document.getElementById('layer'); const shade = h('div', { class: 'shade', style: 'background:transparent;display:block', onMousedown: (ev) => { if (ev.target === shade) close(); } });
  const menu = h('div', { class: 'menu', role: 'menu', 'aria-label': g.title }, items.map(([label, key, fn]) => h('button', { role: 'menuitem', onClick: () => { close(); fn(); } }, h('span', {}, label), h('small', {}, key))));
  const prev = document.activeElement;
  function close() { shade.remove(); if (prev && prev.isConnected) prev.focus(); }
  menu.addEventListener('keydown', (ev) => { if (ev.key === 'Escape') { ev.stopPropagation(); close(); } });
  shade.append(menu); layer.append(shade);
  const x = e && e.clientX ? e.clientX : innerWidth / 2 - 150, y = e && e.clientY ? e.clientY : innerHeight / 2 - 200;
  menu.style.maxHeight = (innerHeight - 20) + 'px';
  menu.style.left = Math.min(x, innerWidth - 320) + 'px'; menu.style.top = Math.max(10, Math.min(y, innerHeight - menu.offsetHeight - 10)) + 'px';
  menu.querySelector('button').focus();
}
/** Type a name, pick the right game: its name, cover and description are used from then on. */
function searchInfo(g) {
  modal((box, close) => {
    const input = h('input', { value: g.base || g.title, 'aria-label': t('Game name'), onKeydown: (e) => { if (e.key === 'Enter') run(); } });
    const list = h('div', { class: 'list', style: 'max-height:52vh;overflow:auto' });
    const status = h('p', {}, '');
    async function run() {
      const q = input.value.trim(); if (!q) return; status.textContent = t('Searching…'); list.replaceChildren();
      const r = await window.rs.meta('search', g, q);
      if (r.error) { status.textContent = r.error; return; }
      status.textContent = r.list.length ? r.list.length + ' ' + t('results') : t('Nothing found. Try fewer words or the English name.');
      list.replaceChildren(...r.list.map((x) => {
        const fake = { id: 'pick', console: g.console, fileName: x.c + '.x', title: x.t, cover: x.c };
        return h('button', { style: 'display:flex;gap:12px;align-items:center', onClick: async () => { status.textContent = t('Saving…'); await window.rs.meta('choose', g, x); close(); toast(t('Game info updated')); } },
          cover(fake, 54, 70, false), h('span', { style: 'flex:1' }, h('b', { style: 'display:block' }, x.t), h('small', { style: 'color:#9AA3B8' }, [x.y, x.r, x.c !== x.t ? x.c : '', x.d ? t('with description') : ''].filter(Boolean).join(' · '))));
      }));
      const first = list.querySelector('button'); if (first && document.body.classList.contains('kbd')) first.focus();
    }
    box.append(h('h2', {}, t('Search game info')), h('div', { style: 'display:flex;gap:8px' }, h('div', { class: 'field', style: 'flex:1;margin:0' }, input), h('button', { class: 'btn primary', onClick: run }, t('Search'))), status, list,
      h('div', { class: 'buttons' }, h('button', { class: 'btn ghost', onClick: close }, t('Close'))));
    setTimeout(run, 0);
  });
}
function collectionPicker(g) {
  const cols = pref('collections') || {};
  const opts = Object.keys(cols).map((n) => ({ label: (cols[n].includes(g.id) ? '✓ ' : '') + n, pick: () => { const s = new Set(cols[n]); if (s.has(g.id)) s.delete(g.id); else s.add(g.id); cols[n] = [...s]; setPref('collections', cols); render(); } }));
  opts.push({ label: '+ ' + t('New collection'), pick: () => ask(t('New collection'), '', (name) => { cols[name] = [...new Set([...(cols[name] || []), g.id])]; setPref('collections', cols); toast(g.title + ' ' + t('added to') + ' ' + name); render(); }) });
  choose(t('Collections') + ' · ' + g.title, opts);
}

// ------------------------------------------------------------------ dialogs
function modal(build) {
  const layer = document.getElementById('layer'); const prev = document.activeElement;
  const box = h('div', { class: 'dialog', role: 'dialog', 'aria-modal': 'true' });
  const shade = h('div', { class: 'shade', onMousedown: (e) => { if (e.target === shade) close(); } }, box);
  function close() { shade.remove(); if (prev && prev.isConnected) prev.focus(); }
  box.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.stopPropagation(); close(); } });
  build(box, close); layer.append(shade);
  const f = box.querySelector('input, .list button, .buttons button'); if (f) f.focus();
  return close;
}
function dialog(title, text, buttons) {
  modal((box, close) => {
    box.append(h('h2', {}, title), h('p', { style: 'white-space:pre-line' }, text),
      h('div', { class: 'buttons' }, [...(buttons || []).map((b) => h('button', { class: 'btn ' + (b.primary ? 'primary' : 'ghost'), onClick: () => { close(); b.pick(); } }, b.label)),
        h('button', { class: 'btn ghost', onClick: close }, t('Close'))]));
  });
}
function confirmBox(title, text, ok, fn) { dialog(title, text, [{ label: ok, primary: true, pick: fn }]); }
function choose(title, options) {
  modal((box, close) => {
    box.append(h('h2', {}, title), h('div', { class: 'list' }, options.map((o) => h('button', { class: o.on ? 'on' : '', onClick: () => { close(); o.pick(); } }, o.label))),
      h('div', { class: 'buttons' }, h('button', { class: 'btn ghost', onClick: close }, t('Close'))));
  });
}
function ask(title, start, done) {
  modal((box, close) => {
    const input = h('input', { value: start, 'aria-label': title, onKeydown: (e) => { if (e.key === 'Enter') ok(); } });
    function ok() { const v = input.value.trim(); if (v) { close(); done(v); } }
    box.append(h('h2', {}, title), h('div', { class: 'field' }, input), h('div', { class: 'buttons' }, h('button', { class: 'btn ghost', onClick: close }, t('Cancel')), h('button', { class: 'btn primary', onClick: ok }, t('Save'))));
  });
}

// ------------------------------------------------------------------ settings
function srow(title, value, color, fn, key) {
  return h('button', { class: 'srow', 'data-key': key || 's_' + title, onClick: fn }, h('span', { class: 'ic', style: `background:${color}` }), h('span', { class: 'tx' }, h('b', {}, title), value ? h('span', {}, value) : null));
}
function settings() {
  const d = S.drive; const hidden = listPref('hidden'); const cols = Object.keys(pref('collections') || {});
  const res = pref('resolution') || {}; const resVals = [...new Set(S.consoles.filter((c) => !c.big && c.id !== 'MAME' && c.id !== 'NDS').map((c) => res[c.id] || 'medium'))];
  const shown = visibleConsoles().length; const fr = S.friends || {};
  const pal = PALETTES[pref('palette')] || PALETTES.blue;
  return h('main', { class: 'scroll' },
    h('div', { class: 'pagehead' }, h('button', { class: 'iconbtn', 'aria-label': t('Back to Home'), 'data-key': 'back', onClick: () => go('home') }, svg(ICON.back, 20, '#E9ECF5')), h('h1', {}, t('Settings'))),
    h('div', { class: 'settings' },
      h('section', { class: 'group' }, h('h2', {}, t('Appearance')),
        srow(t('Home screen'), (homeTheme() === 'cinematic' ? t('Cinematic') : t('Classic (tiles and rows)')) + ' · ' + t('which sections show'), '#7A4DFF', () => go('homeLayout'), 's_theme'),
        srow(t('Accent color'), t(pal[0]), pal[1], () => choose(t('Accent color'), Object.entries(PALETTES).map(([k, v]) => ({ label: t(v[0]), on: (pref('palette') || 'blue') === k, pick: () => { setPref('palette', k); applyLook(); render(); } }))), 's_palette'),
        toggleRow(t('Dark mode'), pref('dark') === false ? t('Light') : t('Dark'), pref('dark') !== false, '#4DB36B', () => { setPref('dark', pref('dark') === false); applyLook(); render(); }, 's_dark'),
        srow(t('Console logos'), logoStyle() === 'mono' ? t('Monochrome logos') : logoStyle() === 'text' ? t('Text') : t('Color logos'), '#FF9A2E', () => choose(t('Console logos'), [['color', t('Color logos')], ['mono', t('Monochrome logos')], ['text', t('Text')]].map(([k, v]) => ({ label: v, on: logoStyle() === k, pick: () => { setPref('logoStyle', k); render(); } }))), 's_logos'),
        srow('Idioma / Language', pref('lang') === 'es' ? 'Español' : 'English', '#2AA8FF', () => choose('Idioma / Language', [['es', 'Español'], ['en', 'English']].map(([k, v]) => ({ label: v, on: pref('lang') === k, pick: () => { setPref('lang', k); t = I18N.make(k); render(); } }))))),
      h('section', { class: 'group' }, h('h2', {}, t('Library')),
        srow('TirsoDrive', d.stage === 'ready' ? t('Connected as') + ' ' + d.me + ' · ' + t('manage channels') : t('Not connected · sign in'), '#14C9AA', () => d.stage === 'ready' ? go('sources') : (setPref('skipped', false), go('home'))),
        srow(t('Games on this PC'), ((S.pcInfo || {}).folders || S.folders || []).length + ' ' + t('folders') + ' · ' + t('ROMs you already have show as downloaded'), '#22C55E', () => go('pc'), 's_pc'),
        srow(t('Open the downloads folder'), t('Where the games from Telegram are kept'), '#2AA8FF', openDownloads, 's_dlfolder'),
        srow(t('Consoles'), shown + ' ' + t('of') + ' ' + S.consoles.length + ' ' + t('visible'), '#2F6DF6', () => go('consoles'), 's_consoles'),
        srow(t('Download a whole console'), t('Every game of a console, one after another'), '#7A4DFF', downloadConsole, 's_dlconsole'),
        srow(t('Download covers'), t('All the box art, for browsing without waiting'), '#FF9A2E', downloadCovers, 's_covers'),
        toggleRow(t('Keep downloaded games'), pref('keep') !== false ? t('Kept on this PC to play offline') : t('Deleted after playing (they stay in Telegram)'), pref('keep') !== false, '#4DB36B', () => { setPref('keep', pref('keep') === false); render(); }, 's_keep'),
        srow(t('Collections'), cols.length + ' ' + t('lists'), '#7A4DFF', () => choose(t('Collections'), cols.length ? cols.map((n) => ({ label: n, pick: () => go('library', { source: 'col', collection: n }) })) : [{ label: t('Long-press or right-click a game and choose "Add to a collection".'), pick: () => {} }])),
        srow(t('History'), t('What you played, day by day'), '#38BDF8', () => go('history'), 's_history'),
        srow(t('Play stats'), t('Hours per console and most played'), '#2AA8FF', statsDialog, 's_stats'),
        srow(t('Games without information'), t('Search by hand for the missing information or cover'), '#F0B455', () => go('missing'), 's_missing'),
        srow(t('Hidden games'), hidden.size + ' ' + t('hidden'), '#8C95AA', () => {
          const list = S.games.filter((g) => hidden.has(g.id));
          if (!list.length) { toast(t('You have no hidden games.')); return; }
          choose(t('Hidden games'), list.map((g) => ({ label: g.title + ' · ' + byId[g.console].title, pick: () => { toggleIn('hidden', g.id); toast(t('Showing again:') + ' ' + g.title); render(); } })));
        }),
        srow(t('Space used'), t('Downloads, covers, saves · free up space'), '#22C55E', () => { S.space = null; go('space'); }, 's_space'),
        srow(t('Clean empty groups'), t('Deletes duplicate TD groups with no files'), '#FF4D73', cleanGroups, 's_clean')),
      h('section', { class: 'group' }, h('h2', {}, t('Games')),
        srow(t('Resolution'), (resVals.length === 1 ? t(RES_LABEL[resVals[0]]) : t('Mixed')) + ' · ' + t('per console'), '#2F6DF6', () => go('resolution'), 's_res'),
        srow(t('Controllers'), (pref('swapAB') ? t('A/B swapped') : t('Xbox layout')) + ' · ' + t('buttons and shortcuts'), '#FF4D73', () => go('controllers'), 's_ctl'),
        srow(t('Save backups'), t('In your Telegram channel · shared with the phone and TV'), '#2DE2C8', backupsMenu),
        srow(t('Phone remote'), t('Start and control games from your phone'), '#7A4DFF', () => { S.remoteInfo = null; go('remote'); }, 's_remote'),
        srow(t('Friends'), fr.chat ? (fr.friends || []).filter((x) => x.userId !== fr.me && x.online).length + ' ' + t('online') : t('See who is online and what they play'), '#22C55E', () => go('friends'), 's_friends'),
        srow(t('System files (BIOS)'), S.bios && S.bios.length ? 'PS2: ' + S.bios.join(', ') : t('Add your own PS2 BIOS file (needed for PS2 games)'), '#4DB36B', () => choose(t('System files (BIOS)'), [
          { label: t('Add a PS2 BIOS file'), pick: async () => { const r = await window.rs.bios('add'); if (!r) return; if (r.error) { toast(r.error); return; } toast(t('BIOS added:') + ' ' + r.ok); S.bios = await window.rs.bios('list'); render(); } },
          { label: t('Open the system files folder'), pick: () => window.rs.tools('openFolder', 'bios') },
          { label: t('PS3 firmware, Switch keys and firmware'), pick: () => go('engines') }]), 's_bios'),
        srow(t('GameCube, Wii, PS3 and Switch'), t('Dolphin, RPCS3 and Eden: install, firmware and keys'), '#B23A3A', () => go('engines'), 's_engines')),
      h('section', { class: 'group' }, h('h2', {}, t('App')),
        srow(t('Updates'), t('Check for and install the latest version'), '#7A4DFF', checkUpdates, 's_updates'),
        srow(t('Problem report'), t('What was recorded if a game failed'), '#FF9A2E', problemReport, 's_report'),
        srow(t('Licenses and credits'), t('Engines, fonts and databases'), '#4DB36B', licenses, 's_lic'),
        srow('RetroStream', S.version, '#2AA8FF', about, 's_about'),
        toggleRow(t('Open in full screen'), t('F11 switches at any time'), pref('fullscreen') !== false, '#38BDF8', () => { setPref('fullscreen', pref('fullscreen') === false); render(); }, 's_full'),
        srow(t('Exit RetroStream'), t('Closes the app'), '#8C95AA', () => window.rs.quit(), 's_exit'),
        d.stage === 'ready' ? srow(t('Sign out of Telegram'), d.me, '#FF4D73', () => confirmBox(t('Sign out of Telegram'), t('The library from your channels is removed from this PC until you sign in again.'), t('Sign out'), () => window.rs.drive('logout'))) : null)));
}
function backupsMenu() {
  if (S.drive.stage !== 'ready') { toast(t('Connect Telegram first (Settings > TirsoDrive).')); return; }
  choose(t('Save backups'), [
    { label: t('Back up all saves now'), pick: async () => { toast(t('Backing up…')); const r = await window.rs.backups('all'); toast(r.error || (r.total ? r.ok + ' / ' + r.total + ' ' + t('backed up') + (r.failed ? ' · ' + r.failed : '') : t('Everything is up to date.'))); } },
    { label: t('Restore a game from Telegram'), pick: async () => {
      toast(t('Loading…')); const r = await window.rs.backups('list');
      if (r.error) { toast(r.error); return; }
      if (!r.list.length) { toast(t('There are no backups in Telegram yet.')); return; }
      choose(t('Restore a game from Telegram'), r.list.map((b) => ({ label: b.title + ' · ' + b.console + ' · ' + new Date(b.date * 1000).toLocaleString(), pick: () => confirmBox(b.title, t('The saves on this PC for this game are replaced by this backup.'), t('Restore'), async () => { const x = await window.rs.backups('restore', b); toast(x.error || t('Saves restored')); }) })));
    } },
    { label: t('Restore everything from Telegram'), pick: () => confirmBox(t('Restore everything from Telegram'), t('Every game gets its newest backup. Saves on this PC are replaced.'), t('Restore'), async () => { toast(t('Restoring…')); const x = await window.rs.backups('restoreAll'); toast(x.error || x.n + ' ' + t('games restored')); }) }
  ]);
}
/** GameCube/Wii (Dolphin), PS3 (RPCS3) and Switch (Eden): their own programs, installed and set up from here. */
function engines() {
  const st = S.ext || {};
  if (!S.ext) window.rs.ext('status').then((x) => { S.ext = x || {}; if (S.page === 'engines') render(); });
  const act = async (action, arg, done) => { const r = await window.rs.ext(action, arg); if (!r) return; if (r.error) { toast(r.error); return; } if (r.status) S.ext = r.status; toast(done); render(); };
  const card = (name, title, consoles, color, rows, note) => {
    const e = st[name] || {};
    return h('section', { class: 'group' }, h('h2', {}, title),
      h('p', { style: 'padding:0 10px;color:#9AA3B8;font-size:13px;margin:4px 0 8px' }, consoles),
      srow(t('Program'), e.installing ? t('Installing…') : e.installed ? t('Installed') + ' · ' + e.version : t('Not installed yet · installs by itself the first time you play'), color,
        () => act('install', name, e.title + ' ' + t('is ready')), 'e_' + name),
      ...rows,
      e.installed ? srow(t('Open') + ' ' + e.title, t('To change its controls, graphics or language'), '#8C95AA', () => act('open', name, e.title + ' ' + t('opened')), 'eo_' + name) : null,
      note ? h('p', { style: 'padding:6px 10px 10px;color:#9AA3B8;font-size:12px;line-height:1.5' }, note) : null);
  };
  return h('main', { class: 'scroll' },
    h('div', { class: 'pagehead' }, h('button', { class: 'iconbtn', 'aria-label': t('Back'), 'data-key': 'back', onClick: () => go('settings') }, svg(ICON.back, 20, '#E9ECF5')), h('h1', {}, t('GameCube, Wii, PS3 and Switch'))),
    h('p', { style: 'padding:0 32px;color:#9AA3B8;max-width:980px' }, t('These consoles play in their own programs. RetroStream installs and updates them, opens your games in them and backs up their saves to Telegram. Inside the game you use the program\u2019s own menu (Esc closes the game).')),
    h('div', { class: 'settings' },
      card('dolphin', 'Dolphin', 'GameCube · Wii', '#5B3E9E', [], t('Controller: Xbox layout set up for you. Wii: the Wii Remote is on the right stick (pointer) with the Nunchuk on the left stick. Saves go in the same backups as on the phone and TV.')),
      card('rpcs3', 'RPCS3', 'PlayStation 3', '#3A404E', [
        srow(t('PS3 firmware'), st.ps3fw ? t('Installed') : t('Needed: your own PS3UPDAT.PUP'), st.ps3fw ? '#22C55E' : '#F0B455', () => act('ps3fw', null, t('PS3 firmware installed')), 'e_ps3fw')],
        t('Needs a fairly strong PC. Games: ISO, or the game folder in a .zip/.7z (split parts .7z.001, .002… are joined by themselves). Set your controller once with Open RPCS3.')),
      card('eden', 'Eden', 'Nintendo Switch', '#B23A3A', [
        srow(t('Keys (prod.keys)'), st.switchKeys ? t('Added') : t('Needed: from your own Switch'), st.switchKeys ? '#22C55E' : '#F0B455', () => act('keys', null, t('Keys added')), 'e_keys'),
        srow(t('Firmware'), st.switchFw ? t('Installed') : t('Choose the firmware .zip from your own Switch'), st.switchFw ? '#22C55E' : '#F0B455', () => act('switchfw', 'zip', t('Firmware installed')), 'e_fw'),
        srow(t('Firmware from a folder'), t('If your firmware is already unpacked'), '#8C95AA', () => act('switchfw', 'folder', t('Firmware installed')), 'e_fwdir')],
        t('Games: NSP, XCI, NSZ, XCZ (split .7z.001 parts too). Keys and firmware stay on this PC and are never uploaded. Set your controller once with Open Eden.'))));
}
function sources() {
  const d = S.drive;
  const list = h('section', { class: 'group' }, h('h2', {}, t('Channel for each console')));
  for (const c of S.consoles) {
    const s = d.sources[c.id] || {};
    list.append(srow(c.title, s.chatId ? s.title + (s.files !== undefined ? ' · ' + s.files + ' ' + t('games') : '') : t('No channel assigned'), c.color, async () => {
      const opts = await window.rs.drive('choices', c.id);
      choose(c.title, [{ label: t('No channel'), pick: () => window.rs.drive('unbind', c.id) },
        ...opts.map((o) => ({ label: o.title + ' · ' + (o.channel ? t('Channel') : t('Group')) + (o.files !== undefined ? ' · ' + o.files + ' ' + t('games') : ''), on: o.id === s.chatId, pick: () => window.rs.drive('bind', c.id, o.id) }))]);
    }, 'src_' + c.id));
  }
  const actions = h('section', { class: 'group' }, h('h2', {}, t('Actions')),
    d.busy ? h('p', { class: 'meta', style: 'padding:10px;color:#9AA3B8' }, d.status) : null,
    srow(t('Scan all channels'), t('Finds each console’s channel and picks the one with the most games'), '#22C55E', () => window.rs.drive('scan'), 'src_scan'),
    srow(t('Refresh catalog'), t('Reads your channels again'), '#2F6DF6', () => window.rs.drive('sync'), 'src_sync'),
    srow(t('Clear all assignments'), t('Removes every console’s channel and the Telegram games list'), '#FF4D73', () => confirmBox(t('Clear all assignments'), t('This removes the channel from every console and clears the Telegram games list. Your saves are not touched.'), t('Clear'), () => window.rs.drive('clear')), 'src_clear'),
    d.error ? h('p', { class: 'error', style: 'padding:0 10px' }, d.error) : null);
  return h('main', { class: 'scroll' },
    h('div', { class: 'pagehead' }, h('button', { class: 'iconbtn', 'aria-label': t('Back'), 'data-key': 'back', onClick: () => go('settings') }, svg(ICON.back, 20, '#E9ECF5')), h('h1', {}, 'TirsoDrive')),
    h('div', { class: 'settings' }, list, actions));
}

// ------------------------------------------------------------------ sign in
function netText(d) {
  return { waiting: t('No internet connection: waiting for the network…'), proxy: t('Connecting to the proxy…'), connecting: t('Connecting to Telegram…'), updating: t('Connected to Telegram'), ready: t('Connected to Telegram') }[d.net] || t('Connecting to Telegram…');
}
/** Sign-in failures as a popup (with what to try next), not only as small red text. */
function signInProblem(text) {
  dialog(t('Could not sign in'), text, [
    { label: t('Try again'), primary: true, pick: () => { S.qrWarned = false; if (S.signTab === 'qr') window.rs.drive('qr'); } },
    { label: t('Connection'), pick: proxyDialog },
    { label: t('Problem report'), pick: problemReport }]);
}
function proxyDialog() {
  const d = S.drive;
  modal((box, close) => {
    const input = h('input', { type: 'text', value: d.proxy || '', placeholder: 'https://t.me/proxy?server=…&port=…&secret=…', 'aria-label': t('Proxy') });
    const save = async (v) => { const r = await window.rs.drive('proxy', v); close(); if (r && r.drive) { S.drive = r.drive; render(); } if (r && r.result) dialog(t('Proxy'), r.result, []); else toast(v ? t('Proxy on') : t('Proxy off')); S.qrWarned = false; };
    box.append(h('h2', {}, t('Connection')),
      h('p', {}, t('Status:') + ' ' + netText(d)),
      h('p', {}, t('Only needed if Telegram is blocked on this network. Paste a Telegram proxy link (t.me/proxy… or t.me/socks…) or write server:port. Leave it empty to connect directly.')),
      h('div', { class: 'field' }, input),
      h('div', { class: 'buttons' }, h('button', { class: 'btn primary', onClick: () => save(input.value) }, t('Save')), d.proxy ? h('button', { class: 'btn ghost', onClick: () => save('') }, t('Turn off the proxy')) : null, h('button', { class: 'btn ghost', onClick: close }, t('Cancel'))));
    setTimeout(() => input.focus(), 50);
  });
}
function signIn() {
  const d = S.drive;
  const box = h('section', { class: 'box', 'aria-label': t('Sign in') });
  if (d.stage === 'code' || d.stage === 'password') {
    const pw = d.stage === 'password';
    const input = h('input', { type: pw ? 'password' : 'text', inputmode: pw ? undefined : 'numeric', value: S.authDraft || '', 'aria-label': pw ? t('Password') : t('Code'), 'data-key': 'auth_input', onInput: (e) => { S.authDraft = e.target.value; }, onKeydown: (e) => { if (e.key === 'Enter') submit(); } });
    const submit = async () => {
      if (!input.value.trim()) return; const btn = box.querySelector('[data-key="auth_go"]'); btn.disabled = true; btn.textContent = t('Checking…');
      const r = await window.rs.drive(pw ? 'password' : 'code', input.value); input.value = ''; S.authDraft = '';
      btn.disabled = false; btn.textContent = t('Continue');
      if (r && r.drive) { S.drive = r.drive; render(); }
      if (r && r.result) signInProblem(r.result);
    };
    box.append(h('h2', {}, pw ? t('Two-step verification') : t('Telegram code')),
      h('p', { style: 'color:#B7BFD2' }, pw ? t('Your account has a password.') + (d.hint ? ' ' + t('Hint:') + ' ' + d.hint : '') : t('Telegram sent you a code in the app (or by SMS). Write it here.')),
      h('div', { class: 'field' }, input), h('button', { class: 'btn primary', 'data-key': 'auth_go', onClick: submit }, t('Continue')));
  } else {
    box.append(h('div', { class: 'seg', role: 'tablist' },
      h('button', { role: 'tab', 'aria-selected': String(S.signTab === 'qr'), class: S.signTab === 'qr' ? 'on' : '', 'data-key': 'tab_qr', onClick: () => { S.signTab = 'qr'; window.rs.drive('qr'); render(); } }, t('QR code')),
      h('button', { role: 'tab', 'aria-selected': String(S.signTab === 'phone'), class: S.signTab === 'phone' ? 'on' : '', 'data-key': 'tab_phone', onClick: () => { S.signTab = 'phone'; render(); } }, t('Phone number'))));
    if (S.signTab === 'qr') {
      const qr = h('div', { class: 'qr' }, d.qrLink ? '' : t('Preparing…'));
      if (d.qrLink) window.rs.qr(d.qrLink).then((url) => qr.replaceChildren(h('img', { src: url, alt: t('QR code to sign in') })));
      box.append(h('div', { style: 'display:flex;gap:22px;align-items:center;flex-wrap:wrap' }, qr,
        h('ol', { style: 'margin:0;padding-left:18px;font-size:14px;line-height:1.8;color:#C9CEDC' }, h('li', {}, t('Open Telegram on your phone')), h('li', {}, t('Settings › Devices › Link Desktop Device')), h('li', {}, t('Point the camera at this code')))));
      box.append(h('div', { class: 'status', style: 'margin-top:18px' }, h('span', { class: 'dot' }), d.qrLink ? t('Waiting for Telegram… the code refreshes by itself') : netText(d)));
      // No code after a while: say so in a popup instead of "Preparing…" forever.
      if (!d.qrLink && !S.qrWarned && !S.qrTimer) { S.qrTimer = setTimeout(() => { S.qrTimer = null; if (S.drive.qrLink || S.drive.stage === 'ready' || S.signTab !== 'qr' || !document.querySelector('.signin')) return; S.qrWarned = true; signInProblem(t('Telegram did not send the QR code.') + ' ' + t('Telegram did not answer. Check that this PC has internet, that the date and time of Windows are right, and that the firewall or antivirus is not blocking RetroStream. If Telegram is blocked where you are, add a proxy (Connection button).')); }, 25000); }
    } else {
      const input = h('input', { type: 'tel', placeholder: '+591 70000000', value: S.phoneDraft || '', 'aria-label': t('Phone number with country code'), 'data-key': 'phone_input', onInput: (e) => { S.phoneDraft = e.target.value; }, onKeydown: (e) => { if (e.key === 'Enter') go2(); } });
      const go2 = async () => {
        if (!input.value.trim()) { input.focus(); return; }
        const btn = box.querySelector('[data-key="phone_go"]'); btn.disabled = true; btn.textContent = t('Sending…');
        const r = await window.rs.drive('phone', input.value);
        btn.disabled = false; btn.textContent = t('Send code');
        if (r && r.drive) { S.drive = r.drive; render(); }
        if (r && r.result) signInProblem(r.result);
        else dialog(t('Code sent'), t('Telegram sent you a code. Look in the Telegram app on your phone (a message from "Telegram") or in your SMS, and write it on the next screen.'), []);
      };
      box.append(h('div', { class: 'field' }, h('label', {}, t('Phone number with country code')), input), h('button', { class: 'btn primary', 'data-key': 'phone_go', onClick: go2 }, t('Send code')));
    }
  }
  if (d.error) box.append(h('p', { class: 'error' }, d.error));
  if (S.signTab !== 'qr' || d.stage === 'code' || d.stage === 'password') box.append(h('div', { class: 'status', style: 'margin-top:14px' }, h('span', { class: 'dot' }), netText(d)));
  box.append(h('div', { class: 'buttons', style: 'margin-top:14px;justify-content:flex-start' },
    h('button', { class: 'btn ghost', 'data-key': 'proxy', onClick: proxyDialog }, t('Connection') + (d.proxy ? ' · ' + t('proxy on') : '')),
    h('button', { class: 'btn ghost', 'data-key': 'signreport', onClick: problemReport }, t('Problem report'))));
  return h('main', { class: 'signin' }, h('div', { class: 'wrap' },
    h('div', { class: 'intro' }, h('div', { class: 'logo', style: 'width:64px;height:64px;border-radius:16px;margin-bottom:22px' }, svg(ICON.pad, 36, '#fff')),
      h('h1', {}, t('Your retro library,'), h('br'), t('now on your PC')),
      h('p', {}, t('Sign in with the same Telegram account as your phone and TV. Your channels, favorites and saves come with you.')),
      h('button', { class: 'btn ghost', 'data-key': 'skip', onClick: () => { setPref('skipped', true); go('home'); } }, t('Skip for now'))),
    box));
}

// ------------------------------------------------------------------ keyboard and controller: move between items with arrows / stick
function moveFocus(dir) {
  // Cinematic home: the arrows / stick move between consoles (the small top buttons are reached with Tab or the mouse).
  if (cineActive() && !document.querySelector('#layer .shade') && !(document.activeElement && document.activeElement.closest('.cine-top'))) { cineStep(dir === 'up' || dir === 'left' ? -1 : 1); return; }
  const all = [...document.querySelectorAll('#layer .shade:last-child button, #layer .shade:last-child input')];
  const pool = all.length ? all : [...document.querySelectorAll('#app button, #app input')].filter((el) => el.offsetParent !== null);
  const cur = document.activeElement; if (!pool.includes(cur)) { if (pool[0]) pool[0].focus(); return; }
  const a = cur.getBoundingClientRect(); const ax = a.left + a.width / 2, ay = a.top + a.height / 2;
  let best = null, bestScore = Infinity;
  for (const el of pool) {
    if (el === cur) continue; const b = el.getBoundingClientRect(); const bx = b.left + b.width / 2, by = b.top + b.height / 2;
    const dx = bx - ax, dy = by - ay;
    const ok = dir === 'left' ? dx < -4 : dir === 'right' ? dx > 4 : dir === 'up' ? dy < -4 : dy > 4; if (!ok) continue;
    const main = dir === 'left' || dir === 'right' ? Math.abs(dx) : Math.abs(dy); const side = dir === 'left' || dir === 'right' ? Math.abs(dy) : Math.abs(dx);
    const score = main + side * 2.5; if (score < bestScore) { bestScore = score; best = el; }
  }
  if (best) { best.focus(); best.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
}
function kbd(on) { document.body.classList.toggle('kbd', on); }
document.addEventListener('mousedown', () => kbd(false), true);
document.addEventListener('keydown', (e) => {
  if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Tab', 'Enter'].includes(e.key)) kbd(true);
  const typing = e.target && e.target.tagName === 'INPUT';
  const map = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
  if (map[e.key] && !(typing && (e.key === 'ArrowLeft' || e.key === 'ArrowRight'))) { e.preventDefault(); moveFocus(map[e.key]); }
  else if (e.key === 'f' && e.ctrlKey) { e.preventDefault(); const s = document.querySelector('[data-key="search"]'); if (s) s.focus(); }
  else if (e.key === 'Escape' && !document.querySelector('#layer .shade')) goBack();
  else if (e.key === 'i' && !typing && !document.querySelector('#layer .shade') && focusedGame()) gameInfo(focusedGame());
  else if ((e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10')) && S.page === 'library' && S.selected) { e.preventDefault(); const g = S.games.find((x) => x.id === S.selected); if (g) gameMenu(g, null, false); }
});
function focusedGame() { const el = document.activeElement; const id = el && el.dataset && el.dataset.game; return id ? S.games.find((g) => g.id === id) : null; }
function optionsForFocus() { const g = focusedGame() || (S.page === 'library' && S.games.find((x) => x.id === S.selected)); if (g) { const el = document.activeElement; const r = el && el.getBoundingClientRect ? el.getBoundingClientRect() : null; gameMenu(g, r ? { clientX: r.left + 20, clientY: r.top + 20 } : null, !!(el && el.dataset && el.dataset.recent)); } }
/** LB / RB: the previous / next console (or collection) in the library. */
function switchTab(step) {
  if (S.page !== 'library') return;
  if (S.source === 'fav' || S.source === 'col') {
    const tabs = ['*fav', ...Object.keys(pref('collections') || {})]; const cur = S.source === 'fav' ? 0 : tabs.indexOf(S.collection);
    const n = tabs[(cur + step + tabs.length) % tabs.length]; go('library', n === '*fav' ? { source: 'fav' } : { source: 'col', collection: n });
  } else {
    const ids = visibleConsoles().map((c) => c.id); const cur = Math.max(0, ids.indexOf(S.console));
    go('library', { console: ids[(cur + step + ids.length) % ids.length], query: '', selected: null });
  }
}
/** Home button / Esc on the home screen: quick options for the whole app. */
async function quickMenu() {
  if (document.querySelector('#layer .shade')) return;
  const full = await window.rs.win('isFull');
  const d = S.drive;
  choose('RetroStream', [
    { label: t('Library'), pick: () => go('library', { source: 'all', console: S.cineConsole || S.console || firstConsole(), query: '' }) },
    { label: t('Favorites & collections'), pick: () => go('library', { source: 'fav', console: null, query: '' }) },
    { label: t('Search games'), pick: askSearch },
    { label: full ? t('Leave full screen') : t('Full screen'), pick: () => window.rs.fullscreen() },
    { label: t('Surprise me'), pick: surprise },
    { label: t('Downloads'), pick: showDownloads },
    { label: t('History'), pick: () => go('history') },
    { label: t('Friends'), pick: () => go('friends') },
    ...(d.stage === 'ready' ? [
      { label: t('Back up all saves now'), pick: async () => { toast(t('Backing up…')); const r = await window.rs.backups('all'); toast(r.error || (r.total ? r.ok + ' / ' + r.total + ' ' + t('backed up') : t('Everything is up to date.'))); } },
      { label: t('Restore saves from Telegram'), pick: backupsMenu },
      { label: t('Refresh catalog'), pick: () => { window.rs.drive('sync'); toast(t('Reading your channels…')); } }] : []),
    { label: t('Settings'), pick: () => go('settings') },
    { label: t('Minimize'), pick: () => window.rs.win('minimize') },
    { label: t('Exit RetroStream'), pick: () => window.rs.quit() }
  ]);
}
function goBack() { if (S.page === 'media') go('home'); else if (S.page === 'sources' || S.page === 'engines' || PAGES[S.page]) go('settings'); else if (S.page !== 'home') go('home'); else quickMenu(); }
const pad = { last: {}, at: {}, first: {} };
function pollPads() {
  // Every connected controller counts (a PS5 / PS4 controller is often not the first one Windows lists, e.g. next to a Steam or virtual pad).
  const pads = [...(navigator.getGamepads ? navigator.getGamepads() : [])].filter((p) => p && p.connected);
  if (pads.length || S.sdlPad) {
    const now = performance.now();
    const st = { up: false, down: false, left: false, right: false, a: false, b: false, x: false, y: false, lb: false, rb: false, lt: false, rt: false, view: false, menu: false, home: false };
    let any = false;
    for (const gp of pads) {
      const b = (i) => !!(gp.buttons[i] && (gp.buttons[i].pressed || gp.buttons[i].value > 0.5));
      const ax = (i) => gp.axes[i] || 0;
      let s;
      if (gp.mapping === 'standard') {
        s = { up: b(12) || ax(1) < -0.6, down: b(13) || ax(1) > 0.6, left: b(14) || ax(0) < -0.6, right: b(15) || ax(0) > 0.6,
          a: b(0), b: b(1), x: b(2), y: b(3), lb: b(4), rb: b(5), lt: b(6), rt: b(7), view: b(8), menu: b(9), home: b(16) };
      } else {
        // PlayStation layout as Windows reports it without a driver: □ ✕ ○ △ = 0 1 2 3, PS = 12, the d-pad is one "hat" axis.
        const hat = gp.axes.length > 9 ? ax(9) : 9; const dir = hat <= 1.01 ? Math.round((hat + 1) * 3.5) % 8 : -1;
        s = { up: [7, 0, 1].includes(dir) || ax(1) < -0.6, right: [1, 2, 3].includes(dir) || ax(0) > 0.6, down: [3, 4, 5].includes(dir) || ax(1) > 0.6, left: [5, 6, 7].includes(dir) || ax(0) < -0.6,
          a: b(1), b: b(2), x: b(0), y: b(3), lb: b(4), rb: b(5), lt: b(6), rt: b(7), view: b(8), menu: b(9), home: b(12) };
      }
      if (pref('swapAB')) { [s.a, s.b] = [s.b, s.a]; [s.x, s.y] = [s.y, s.x]; }
      for (const k of Object.keys(st)) st[k] = st[k] || !!s[k];
      if (gp.buttons.some((x) => x.pressed) || [0, 1].some((i) => Math.abs(ax(i)) > 0.6)) any = true;
    }
    // Buttons read by the app's controller helper (any controller the games understand, Bluetooth PS5 included).
    if (S.sdlHeld && S.sdlHeld.length) { for (const k of S.sdlHeld) if (k in st) st[k] = true; any = true; }
    if (any) kbd(true);
    if (!document.querySelector('#app :focus') && !document.querySelector('#layer :focus') && Object.values(st).some(Boolean)) { const f = document.querySelector('#app [data-key="cine_enter"]') || document.querySelector('#app [data-key="continue"], #app .card, #app button'); if (f) f.focus(); }
    // Held directions repeat: a short pause first, then quickly.
    for (const k of ['up', 'down', 'left', 'right']) {
      if (!st[k]) continue;
      if (!pad.last[k]) { moveFocus(k); pad.first[k] = now; pad.at[k] = now; }
      else if (now - pad.first[k] > 380 && now - pad.at[k] > 110) { moveFocus(k); pad.at[k] = now; }
    }
    const press = (k) => st[k] && !pad.last[k];
    const layerOpen = document.querySelector('#layer .shade');
    if (press('a') && document.activeElement && document.activeElement !== document.body) document.activeElement.click();
    if (press('b')) { const top = document.querySelector('#layer .shade:last-child'); if (top) top.remove(); else goBack(); }
    if (press('home')) { const top = document.querySelector('#layer .shade:last-child'); if (top) top.remove(); else quickMenu(); }
    if (!layerOpen) {
      if (press('x')) { const g = focusedGame(); if (g) { toggleIn('favorites', g.id); render(); toast(listPref('favorites').has(g.id) ? t('Added to favorites') : t('Removed from favorites')); } }
      if (press('y') && cineActive()) surprise();
      else if (press('y') || press('menu')) optionsForFocus();
      if (press('lb')) switchTab(-1);
      if (press('rb')) switchTab(1);
      if (press('lt') || press('rt')) { for (let i = 0; i < 3; i++) moveFocus(press('lt') ? 'up' : 'down'); }
      if (press('view')) { const s2 = document.querySelector('[data-key="search"]'); if (s2) s2.focus(); }
    }
    pad.last = st;
    if (!S.padSeen) { S.padSeen = true; render(); }
  }
  requestAnimationFrame(pollPads);
}

// ------------------------------------------------------------------ start
(async () => {
  const init = await window.rs.init();
  Object.assign(S, { prefs: init.prefs, games: init.games, consoles: init.consoles, drive: init.drive, version: init.version, friends: init.friends, folders: init.folders, unsortedCount: init.unsorted });
  applyLook();
  for (const c of S.consoles) byId[c.id] = c;
  t = I18N.make(S.prefs.lang);
  document.documentElement.lang = S.prefs.lang || 'en';
  window.rs.on('state', (s) => {
    const was = S.drive; S.drive = s.drive; if (s.games) S.games = s.games;
    if (s.drive.stage === 'ready' && was.stage !== 'ready') { setPref('skipped', false); }
    const big = s.games || was.stage !== s.drive.stage || was.qrLink !== s.drive.qrLink || was.error !== s.drive.error || was.busy !== s.drive.busy || was.net !== s.drive.net || was.proxy !== s.drive.proxy || S.page === 'sources';
    if (big && !(document.querySelector('#layer .shade') && !s.games)) render();
    else { const st = document.getElementById('statustext'); if (st) st.textContent = statusText(); }
  });
  window.rs.on('file', (i) => {
    const wasActive = (S.files[i.id] || {}).state === 'downloading';
    S.files[i.id] = i;
    if (S.liveDownloads && document.querySelector('#layer .shade')) S.liveDownloads(); else S.liveDownloads = null;
    document.querySelectorAll(`.bar[data-file="${i.id}"] > div`).forEach((b) => { b.style.width = pct(i) + '%'; });
    if (wasActive !== (i.state === 'downloading')) { const badge = document.querySelector('[data-key="downloads"] .badge'); const n = activeDownloads().length;
      const btn = document.querySelector('[data-key="downloads"]'); if (badge) badge.remove(); if (btn && n) btn.append(h('span', { class: 'badge' }, n)); }
    const g = S.games.find((x) => x.id === S.selected);
    if (g && g.fileId === i.id) renderPanel(g);
    if (i.state === 'done') { const p = S.games.find((x) => x.fileId === i.id && x.id === S.pendingPlay); if (p) { S.pendingPlay = null; setTimeout(() => playGame(p), 300); } }
  });
  window.rs.on('kept', (k) => { S.prefs.kept = k; render(); });
  window.rs.on('toast', (text) => toast(text));
  window.rs.on('pad', (held) => {
    let h = held || [];
    if (pref('swapAB')) h = h.map((k) => ({ a: 'b', b: 'a', x: 'y', y: 'x' }[k] || k));
    S.sdlHeld = h; S.sdlPad = true;
  });
  window.rs.on('friends', (f) => { S.friends = f; if (S.page === 'friends' || (S.page === 'home' && homeTheme() === 'classic')) { if (!document.querySelector('#layer .shade')) render(); } });
  const launch = (x) => { const id = typeof x === 'string' ? x : x.id; const g = S.games.find((y) => y.id === id); if (g) { document.getElementById('layer').replaceChildren(); playGame(g, typeof x === 'object' && x.fresh); } else toast(t('That game is not in your library any more.')); };
  window.rs.on('remote', (r) => remoteCode(r && r.code));
  window.rs.on('launch', launch);
  if (init.launch) setTimeout(() => launch(init.launch), 1500);
  window.rs.on('played', (i) => { S.prefs.playtime = i.playtime; if (i.error) dialog(t('The game closed'), i.error + (i.details ? '\n\n' + t('Details for support:') + '\n' + i.details : ''), []); render(); });
  S.bios = await window.rs.bios('list');
  render();
  requestAnimationFrame(pollPads);
})();
