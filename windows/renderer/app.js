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
function visibleGames() { const hidden = listPref('hidden'); return S.games.filter((g) => !hidden.has(g.id)); }
function playtime(g) { const m = Math.floor(((pref('playtime') || {})[g.id] || 0) / 60000); return m < 1 ? '' : m < 60 ? m + ' min' : Math.floor(m / 60) + ' h ' + (m % 60) + ' min'; }
function kept(g) { return !!(pref('kept') || {})[g.id]; }
function plays() { return (pref('plays') || []).map((p) => ({ game: S.games.find((g) => g.id === p.id), at: p.at })).filter((p) => p.game); }
function recent() { const seen = new Set(); const out = []; for (const p of plays()) if (!seen.has(p.game.id)) { seen.add(p.game.id); out.push(p.game); if (out.length >= 12) break; } return out; }
function newGames() { const added = pref('addedAt') || {}; const since = Date.now() - 14 * 86400000; return visibleGames().filter((g) => (added[g.id] || 0) > since).sort((a, b) => (added[b.id] - added[a.id]) || (b.messageId - a.messageId)); }
function sorted(list) {
  const s = pref('sort') || 'name';
  const out = [...list];
  if (s === 'added') out.sort((a, b) => b.messageId - a.messageId);
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
  return sorted(list);
}

// ------------------------------------------------------------------ covers (libretro thumbnails, tried name by name)
function thumbName(n) { return n.replace(/[&*/:`<>?\\|"]/g, '_'); }
function coverUrls(g) {
  const c = byId[g.console]; if (!c) return [];
  let stem = g.fileName || g.title; const dot = stem.lastIndexOf('.'); if (dot > 0) stem = stem.substring(0, dot);
  const names = [thumbName(stem)];
  for (const r of ['USA', 'Europe', 'World', 'Japan']) names.push(thumbName(g.title + ' (' + r + ')'));
  names.push(thumbName(g.title));
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
  const scrolls = [...app.querySelectorAll('.scroll')].map((el) => el.scrollTop); const samePage = render.page === S.page; render.page = S.page;
  app.replaceChildren();
  const d = S.drive;
  const signingIn = d.stage !== 'ready' && !pref('skipped');
  if (signingIn && !['settings', 'sources'].includes(S.page)) app.append(signIn());
  else {
    app.append(header());
    if (S.page === 'library') app.append(library());
    else if (S.page === 'settings') app.append(settings());
    else if (S.page === 'sources') app.append(sources());
    else app.append(home());
  }
  if (samePage) app.querySelectorAll('.scroll').forEach((el, i) => { if (scrolls[i]) el.scrollTop = scrolls[i]; });
  if (keep) { const el = app.querySelector(`[data-key="${CSS.escape(keep)}"]`); if (el) el.focus({ preventScroll: true }); }
}
function go(page, opts) { Object.assign(S, opts || {}); S.page = page; render(); const f = document.querySelector('#app [data-key]'); if (f && !opts?.keepFocus) f.focus({ preventScroll: true }); }

function header() {
  const d = S.drive; const nav = [['home', t('Home')], ['library', t('Library')], ['fav', t('Favorites & collections')], ['settings', t('Settings')]];
  const on = (k) => (k === 'fav' ? S.page === 'library' && (S.source === 'fav' || S.source === 'col') : k === 'library' ? S.page === 'library' && S.source !== 'fav' && S.source !== 'col' : S.page === k || (k === 'settings' && S.page === 'sources'));
  const input = h('input', { placeholder: t('Search all games  (Ctrl+F)'), value: S.query, 'aria-label': t('Search games'), 'data-key': 'search',
    onKeydown: (e) => { if (e.key === 'Enter') { go('library', { query: e.target.value.trim(), console: null, source: 'all' }); } } });
  return h('header', { class: 'top' },
    h('div', { class: 'brand' }, h('div', { class: 'logo' }, svg(ICON.pad, 22, '#fff')), 'RetroStream'),
    h('nav', { class: 'nav', 'aria-label': t('Main') }, nav.map(([k, label]) => h('button', { class: on(k) ? 'on' : '', 'data-key': 'nav_' + k,
      onClick: () => k === 'fav' ? go('library', { source: 'fav', console: null, query: '' }) : k === 'library' ? go('library', { source: 'all', console: S.console || firstConsole(), query: '' }) : go(k) }, label))),
    h('label', { class: 'search' }, svg(ICON.search, 16, '#9AA3B8'), input),
    h('button', { class: 'iconbtn', 'aria-label': t('Downloads'), 'data-key': 'downloads', onClick: showDownloads }, svg(ICON.down, 20, '#E9ECF5'),
      activeDownloads().length ? h('span', { class: 'badge' }, activeDownloads().length) : null),
    h('div', { class: 'status', id: 'status' }, h('span', { class: 'dot' + (d.stage === 'ready' ? ' ok' : '') }), h('span', { id: 'statustext' }, statusText())));
}
function statusText() { const d = S.drive; return d.stage === 'ready' ? (d.busy ? d.status : t('TirsoDrive connected')) : t('Not connected');
}
function firstConsole() { const withGames = S.consoles.find((c) => S.games.some((g) => g.console === c.id)); return (withGames || S.consoles[0]).id; }

function home() {
  const rec = recent(); const last = rec[0]; const c = last ? byId[last.console] : byId[firstConsole()];
  const left = h('button', { class: 'continue', style: `background:${c.color}`, 'data-key': 'continue',
    onClick: () => last ? playGame(last) : go('library', { console: c.id, source: 'all' }), onContextmenu: (e) => { if (last) { e.preventDefault(); gameMenu(last, e, true); } } },
  h('span', { class: 'kicker' }, last ? t('CONTINUE') + ' · ' + c.title.toUpperCase() : t('START PLAYING')),
  h('h1', { class: 'display' }, last ? last.title : t('Pick a console')),
  last && playtime(last) ? h('span', { class: 'pill' }, playtime(last) + ' ' + t('played')) : null,
  last ? h('div', { class: 'art' }, cover(last, 230, Math.round(230 / (RATIO[last.console] || 1)), false)) : null,
  h('div', { class: 'actions' }, h('span', { class: 'btn white' }, svg(ICON.play, 14, '#14161E', '#14161E'), last ? t('Resume') : t('Open library'))));
  const rows = h('div', { class: 'rows' });
  if (rec.length > 1) {
    rows.append(h('div', { class: 'rowhead' }, h('h2', {}, t('Recently played'))));
    rows.append(h('div', { class: 'strip' }, rec.slice(1).map((g) => miniCard(g, 'rec', true))));
  }
  const fresh = newGames().slice(0, 15);
  if (fresh.length) {
    rows.append(h('div', { class: 'rowhead' }, h('h2', {}, t('New in your channels') + ' · ' + fresh.length)));
    rows.append(h('div', { class: 'strip' }, fresh.map((g) => { const m = miniCard(g, 'new', false); m.querySelector('.cover').append(h('span', { class: 'tag', style: 'background:#C9363B' }, t('NEW'))); return m; })));
  }
  rows.append(h('div', { class: 'rowhead' }, h('h2', {}, t('Consoles')),
    h('button', { class: 'link', 'data-key': 'surprise', onClick: surprise }, svg(ICON.dice, 16, '#7FA6FF'), t('Surprise me'))));
  rows.append(h('div', { class: 'consoles' }, S.consoles.map((cn) => {
    const n = S.games.filter((g) => g.console === cn.id).length;
    return h('button', { class: 'ctile', style: `background:${cn.color}`, 'data-key': 'con_' + cn.id, onClick: () => go('library', { console: cn.id, source: 'all', query: '', filter: 'all' }) },
      h('b', {}, cn.title), h('span', {}, cn.big ? t('New · PC only') : count(n, 'game', 'games')));
  })));
  if (S.drive.stage === 'ready' && S.games.length === 0 && !S.drive.busy) rows.append(h('p', { class: 'empty' }, t('No games yet. Open Settings > TirsoDrive and tap Scan all channels.')));
  return h('main', { class: 'scroll' }, h('div', { class: 'home' }, left, rows));
}
function miniCard(g, prefix, recentMenu) {
  const w = 128; const hh = Math.round(w / (RATIO[g.console] || 1)) > 170 ? 170 : Math.round(w / (RATIO[g.console] || 1));
  return h('button', { class: 'mini', 'data-key': prefix + '_' + g.id, onClick: () => prefix === 'rec' ? playGame(g) : openGame(g), onContextmenu: (e) => { e.preventDefault(); gameMenu(g, e, recentMenu); } },
    cover(g, w, hh), h('div', { class: 't' }, g.title), h('div', { class: 's' }, byId[g.console].title + (playtime(g) ? ' · ' + playtime(g) : '')));
}
function openGame(g) { go('library', { console: g.console, source: 'all', query: '', selected: g.id }); }

function library() {
  const wrap = h('main', { style: 'flex:1;min-height:0;display:flex;flex-direction:column' });
  const tabs = h('div', { class: 'tabs' });
  const cols = Object.keys(pref('collections') || {});
  if (S.source === 'fav' || S.source === 'col') {
    tabs.append(h('button', { class: 'chip' + (S.source === 'fav' ? ' on' : ''), 'data-key': 'tab_fav', onClick: () => go('library', { source: 'fav', keepFocus: true }) }, '★ ' + t('Favorites')));
    for (const n of cols) tabs.append(h('button', { class: 'chip' + (S.source === 'col' && S.collection === n ? ' on' : ''), 'data-key': 'tab_col_' + n, onClick: () => go('library', { source: 'col', collection: n, keepFocus: true }) }, n));
  } else for (const c of S.consoles) tabs.append(h('button', { class: 'chip' + (S.console === c.id ? ' on' : ''), 'data-key': 'tab_' + c.id, onClick: () => go('library', { console: c.id, query: '', keepFocus: true }) }, c.id === 'MD' ? 'Mega Drive' : c.id === 'SWITCH' ? 'Switch' : c.id === 'WII' ? 'Wii' : c.id === 'GC' ? 'GameCube' : c.id));
  wrap.append(h('div', { class: 'libtop' }, h('button', { class: 'iconbtn', 'aria-label': t('Back to Home'), 'data-key': 'back', onClick: () => go('home') }, svg(ICON.back, 20, '#E9ECF5')), tabs));
  const games = shownGames();
  const title = S.source === 'fav' ? t('Favorites') : S.source === 'col' ? S.collection : S.query && !S.console ? '"' + S.query + '"' : (byId[S.console] || {}).title || t('Library');
  const sortNames = { name: t('Name A–Z'), name_desc: t('Name Z–A'), added: t('Recently added'), recent: t('Recently played') };
  wrap.append(h('div', { class: 'libhead' }, h('h1', {}, title), h('span', { class: 'n' }, count(games.length, 'game', 'games')),
    h('div', { class: 'filters' },
      h('button', { class: 'chip' + (S.filter === 'all' ? ' on' : ''), 'data-key': 'f_all', onClick: () => go('library', { filter: 'all', keepFocus: true }) }, t('All')),
      h('button', { class: 'chip' + (S.filter === 'downloaded' ? ' on' : ''), 'data-key': 'f_dl', onClick: () => go('library', { filter: 'downloaded', keepFocus: true }) }, '✓ ' + t('On this PC')),
      h('button', { class: 'chip', 'data-key': 'f_sort', onClick: () => choose(t('Sort by'), Object.entries(sortNames).map(([k, v]) => ({ label: v, on: (pref('sort') || 'name') === k, pick: () => { setPref('sort', k); render(); } }))) }, t('Sort:') + ' ' + sortNames[pref('sort') || 'name']))));
  const grid = h('div', { class: 'grid scroll', role: 'list' });
  if (!games.length) grid.append(h('p', { class: 'empty' }, S.drive.busy ? S.drive.status : t('No games here yet.')));
  const sel = games.find((g) => g.id === S.selected) || games[0];
  for (const g of games.slice(0, 3000)) {
    const r = RATIO[g.console] || 1; const w = 150; const hh = Math.round(w / r);
    const card = h('button', { class: 'card', role: 'listitem', 'data-key': 'g_' + g.id, onClick: () => { S.selected = g.id; renderPanel(g); }, onDblclick: () => playGame(g),
      onKeydown: (e) => { if (e.key === 'Enter') { e.preventDefault(); playGame(g); } }, onFocus: () => { S.selected = g.id; renderPanel(g); }, onContextmenu: (e) => { e.preventDefault(); gameMenu(g, e, false); } },
    cover(g, w, Math.min(hh, 220)), h('div', { class: 't' }, g.title), h('div', { class: 's' }, gameLine(g)));
    card.style.width = '100%'; card.querySelector('.cover').style.width = '100%';
    grid.append(card);
  }
  const panel = h('aside', { class: 'panel', id: 'panel', 'aria-label': t('Selected game') });
  wrap.append(h('div', { class: 'libbody' }, grid, panel));
  setTimeout(() => { if (sel) renderPanel(sel); }, 0);
  return wrap;
}
function gameLine(g) { return kept(g) ? t('On this PC') : 'Telegram' + (g.size ? ' · ' + size(g.size) : ''); }
function renderPanel(g) {
  const p = document.getElementById('panel'); if (!p) return;
  const info = S.files[g.fileId] || {};
  const r = RATIO[g.console] || 1;
  p.replaceChildren(
    cover(g, 300, Math.min(Math.round(300 / r), 300)),
    h('h2', {}, g.title),
    h('div', { class: 'meta' }, [byId[g.console].title, g.fileName, size(g.size)].filter(Boolean).join(' · ')),
    info.state === 'downloading' ? h('div', {}, h('div', { class: 'progress' }, h('div', { style: `width:${info.size ? Math.round(info.have * 100 / info.size) : 0}%` })), h('div', { class: 'meta', style: 'margin-top:6px' }, t('Downloading') + ' ' + size(info.have) + ' / ' + size(info.size))) : null,
    h('div', { class: 'actions' },
      h('button', { class: 'btn primary', style: 'flex:1;justify-content:center', 'data-key': 'p_play', onClick: () => playGame(g) }, svg(ICON.play, 14, '#fff', '#fff'), kept(g) ? t('Play') : t('Download and play')),
      h('button', { class: 'iconbtn', 'aria-label': t('Favorite'), 'data-key': 'p_fav', onClick: () => { toggleIn('favorites', g.id); render(); } }, svg(ICON.star, 18, '#FFC94D', listPref('favorites').has(g.id) ? '#FFC94D' : 'none')),
      h('button', { class: 'iconbtn', 'aria-label': t('More options'), 'data-key': 'p_more', onClick: (e) => gameMenu(g, e, false) }, svg(ICON.more, 22, '#E9ECF5'))));
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
  items.push([t('View game'), '', () => openGame(g)]);
  items.push([listPref('favorites').has(g.id) ? t('Remove from favorites') : t('Add to favorites'), 'F', () => { toggleIn('favorites', g.id); render(); }]);
  items.push([t('Add to a collection'), '', () => collectionPicker(g)]);
  items.push([listPref('completed').has(g.id) ? t('Remove "Completed"') : t('Mark as completed'), '', () => { toggleIn('completed', g.id); render(); }]);
  if (!kept(g) && (S.files[g.fileId] || {}).state !== 'downloading') items.push([t('Download to play offline'), '', async () => { const i = await window.rs.game('download', g); if (i) S.files[g.fileId] = i; toast(t('Downloading') + ' ' + g.title + '…'); }]);
  if ((S.files[g.fileId] || {}).state === 'downloading') items.push([t('Stop download'), '', () => window.rs.game('cancel', g)]);
  if (kept(g)) items.push([t('Delete downloaded file'), '', () => confirmBox(g.title, t('It is deleted from this PC to free up space. The game stays in Telegram and in your library.'), t('Delete'), async () => { await window.rs.game('remove', g); toast(t('File deleted')); })]);
  items.push([t('Hide game'), '', () => confirmBox(g.title, t("It won't show in your library. You can show it again in Settings > Hidden games."), t('Hide'), () => { toggleIn('hidden', g.id); render(); })]);
  if (recentMenu) {
    items.push([t('Play from the start'), '', () => confirmBox(g.title, t("The game starts without loading where you left off. Your saves are not deleted."), t('Play'), () => playGame(g, true))]);
    items.push([t('Remove from recents'), '', () => { setPref('plays', (pref('plays') || []).filter((p) => p.id !== g.id)); render(); }]);
  }
  const layer = document.getElementById('layer'); const shade = h('div', { class: 'shade', style: 'background:transparent;display:block', onMousedown: (ev) => { if (ev.target === shade) close(); } });
  const menu = h('div', { class: 'menu', role: 'menu', 'aria-label': g.title }, items.map(([label, key, fn]) => h('button', { role: 'menuitem', onClick: () => { close(); fn(); } }, h('span', {}, label), h('small', {}, key))));
  const prev = document.activeElement;
  function close() { shade.remove(); if (prev && prev.isConnected) prev.focus(); }
  menu.addEventListener('keydown', (ev) => { if (ev.key === 'Escape') { ev.stopPropagation(); close(); } });
  shade.append(menu); layer.append(shade);
  const x = e && e.clientX ? e.clientX : innerWidth / 2 - 150, y = e && e.clientY ? e.clientY : innerHeight / 2 - 200;
  menu.style.left = Math.min(x, innerWidth - 320) + 'px'; menu.style.top = Math.max(10, Math.min(y, innerHeight - menu.offsetHeight - 10)) + 'px';
  menu.querySelector('button').focus();
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
  return h('main', { class: 'scroll' },
    h('div', { class: 'pagehead' }, h('button', { class: 'iconbtn', 'aria-label': t('Back to Home'), 'data-key': 'back', onClick: () => go('home') }, svg(ICON.back, 20, '#E9ECF5')), h('h1', {}, t('Settings'))),
    h('div', { class: 'settings' },
      h('section', { class: 'group' }, h('h2', {}, t('Appearance')),
        srow('Idioma / Language', pref('lang') === 'es' ? 'Español' : 'English', '#2AA8FF', () => choose('Idioma / Language', [['es', 'Español'], ['en', 'English']].map(([k, v]) => ({ label: v, on: pref('lang') === k, pick: () => { setPref('lang', k); t = I18N.make(k); render(); } }))))),
      h('section', { class: 'group' }, h('h2', {}, t('Library')),
        srow('TirsoDrive', d.stage === 'ready' ? t('Connected as') + ' ' + d.me + ' · ' + t('manage channels') : t('Not connected · sign in'), '#14C9AA', () => d.stage === 'ready' ? go('sources') : (setPref('skipped', false), go('home'))),
        srow(t('Collections'), cols.length + ' ' + t('lists'), '#7A4DFF', () => choose(t('Collections'), cols.length ? cols.map((n) => ({ label: n, pick: () => go('library', { source: 'col', collection: n }) })) : [{ label: t('Long-press or right-click a game and choose "Add to a collection".'), pick: () => {} }])),
        srow(t('Hidden games'), hidden.size + ' ' + t('hidden'), '#8C95AA', () => {
          const list = S.games.filter((g) => hidden.has(g.id));
          if (!list.length) { toast(t('You have no hidden games.')); return; }
          choose(t('Hidden games'), list.map((g) => ({ label: g.title + ' · ' + byId[g.console].title, pick: () => { toggleIn('hidden', g.id); toast(t('Showing again:') + ' ' + g.title); render(); } })));
        })),
      h('section', { class: 'group' }, h('h2', {}, t('Coming next')),
        srow(t('PSP, PS2, GameCube and Wii'), t('Next update'), '#2F6DF6', () => {}),
        srow(t('PS3 and Switch'), t('RPCS3 and Eden setup · later update'), '#B23A3A', () => {})),
      h('section', { class: 'group' }, h('h2', {}, t('App')),
        srow(t('Updates'), 'RetroStream ' + S.version + ' · ' + t('open the downloads page'), '#7A4DFF', () => window.rs.open('https://github.com/dioniciogarcia/retrostream/releases/tag/windows-latest')),
        srow(t('Open in full screen'), (pref('fullscreen') !== false ? t('On') : t('Off')) + ' · ' + t('F11 switches at any time'), '#38BDF8', () => { setPref('fullscreen', pref('fullscreen') === false); render(); }, 's_full'),
        srow(t('Exit RetroStream'), t('Closes the app'), '#8C95AA', () => window.rs.quit(), 's_exit'),
        d.stage === 'ready' ? srow(t('Sign out of Telegram'), d.me, '#FF4D73', () => confirmBox(t('Sign out of Telegram'), t('The library from your channels is removed from this PC until you sign in again.'), t('Sign out'), () => window.rs.drive('logout'))) : null)));
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
function signIn() {
  const d = S.drive;
  const box = h('section', { class: 'box', 'aria-label': t('Sign in') });
  if (d.stage === 'code' || d.stage === 'password') {
    const pw = d.stage === 'password';
    const input = h('input', { type: pw ? 'password' : 'text', inputmode: pw ? undefined : 'numeric', 'aria-label': pw ? t('Password') : t('Code'), 'data-key': 'auth_input', onKeydown: (e) => { if (e.key === 'Enter') submit(); } });
    const submit = () => { window.rs.drive(pw ? 'password' : 'code', input.value); input.value = ''; };
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
      box.append(h('div', { class: 'status', style: 'margin-top:18px' }, h('span', { class: 'dot' }), d.stage === 'connecting' ? t('Connecting to Telegram…') : t('Waiting for Telegram… the code refreshes by itself')));
    } else {
      const input = h('input', { type: 'tel', placeholder: '+591 70000000', 'aria-label': t('Phone number with country code'), 'data-key': 'phone_input', onKeydown: (e) => { if (e.key === 'Enter') go2(); } });
      const go2 = () => window.rs.drive('phone', input.value);
      box.append(h('div', { class: 'field' }, h('label', {}, t('Phone number with country code')), input), h('button', { class: 'btn primary', 'data-key': 'phone_go', onClick: go2 }, t('Send code')));
    }
  }
  if (d.error) box.append(h('p', { class: 'error' }, d.error));
  return h('main', { class: 'signin' }, h('div', { class: 'wrap' },
    h('div', { class: 'intro' }, h('div', { class: 'logo', style: 'width:64px;height:64px;border-radius:16px;margin-bottom:22px' }, svg(ICON.pad, 36, '#fff')),
      h('h1', {}, t('Your retro library,'), h('br'), t('now on your PC')),
      h('p', {}, t('Sign in with the same Telegram account as your phone and TV. Your channels, favorites and saves come with you.')),
      h('button', { class: 'btn ghost', 'data-key': 'skip', onClick: () => { setPref('skipped', true); go('home'); } }, t('Skip for now'))),
    box));
}

// ------------------------------------------------------------------ keyboard and controller: move between items with arrows / stick
function moveFocus(dir) {
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
  else if ((e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10')) && S.page === 'library' && S.selected) { e.preventDefault(); const g = S.games.find((x) => x.id === S.selected); if (g) gameMenu(g, null, false); }
});
function goBack() { if (S.page === 'sources') go('settings'); else if (S.page !== 'home') go('home'); }
const pad = { last: {}, at: 0 };
function pollPads() {
  const gp = [...(navigator.getGamepads ? navigator.getGamepads() : [])].find(Boolean);
  if (gp) {
    const b = (i) => gp.buttons[i] && gp.buttons[i].pressed;
    const now = performance.now();
    if (gp.buttons.some((x) => x.pressed) || gp.axes.some((v) => Math.abs(v) > 0.6)) kbd(true);
    const st = { up: b(12) || gp.axes[1] < -0.6, down: b(13) || gp.axes[1] > 0.6, left: b(14) || gp.axes[0] < -0.6, right: b(15) || gp.axes[0] > 0.6, a: b(0), b: b(1), x: b(2), y: b(3), menu: b(9) };
    for (const k of ['up', 'down', 'left', 'right']) if (st[k] && (!pad.last[k] || now - pad.at > 180)) { moveFocus(k); pad.at = now; }
    if (st.a && !pad.last.a && document.activeElement) document.activeElement.click();
    if (st.b && !pad.last.b) { const top = document.querySelector('#layer .shade:last-child'); if (top) top.remove(); else goBack(); }
    if (st.x && !pad.last.x && S.page === 'library' && S.selected) { toggleIn('favorites', S.selected); render(); }
    if (st.menu && !pad.last.menu && S.page === 'library' && S.selected) { const g = S.games.find((x) => x.id === S.selected); if (g) gameMenu(g, null, false); }
    pad.last = st;
  }
  requestAnimationFrame(pollPads);
}

// ------------------------------------------------------------------ start
(async () => {
  const init = await window.rs.init();
  Object.assign(S, { prefs: init.prefs, games: init.games, consoles: init.consoles, drive: init.drive, version: init.version });
  for (const c of S.consoles) byId[c.id] = c;
  t = I18N.make(S.prefs.lang);
  document.documentElement.lang = S.prefs.lang || 'en';
  window.rs.on('state', (s) => {
    const was = S.drive; S.drive = s.drive; if (s.games) S.games = s.games;
    if (s.drive.stage === 'ready' && was.stage !== 'ready') { setPref('skipped', false); }
    const big = s.games || was.stage !== s.drive.stage || was.qrLink !== s.drive.qrLink || was.error !== s.drive.error || was.busy !== s.drive.busy || S.page === 'sources';
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
  window.rs.on('played', (i) => { S.prefs.playtime = i.playtime; if (i.error) dialog(t('The game closed'), i.error, []); render(); });
  render();
  requestAnimationFrame(pollPads);
})();
