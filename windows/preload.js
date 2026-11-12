'use strict';
// The bridge between the screens and the app: only these calls are available to the page.
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('rs', {
  init: () => ipcRenderer.invoke('init'),
  setPref: (k, v) => ipcRenderer.invoke('prefs:set', k, v),
  drive: (action, ...args) => ipcRenderer.invoke('drive', action, ...args),
  game: (action, game) => ipcRenderer.invoke('game', action, game),
  qr: (text) => ipcRenderer.invoke('qr', text),
  open: (url) => ipcRenderer.invoke('open', url),
  fullscreen: () => ipcRenderer.invoke('fullscreen'),
  quit: () => ipcRenderer.invoke('quit'),
  on: (channel, fn) => { if (['state', 'file', 'kept'].includes(channel)) ipcRenderer.on(channel, (_e, data) => fn(data)); }
});
