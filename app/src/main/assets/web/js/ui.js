// Small shared UI helpers: toasts and promise-based dialogs.

const toastRoot = () => document.getElementById('toasts');

/** Shows a toast. Returns a function that dismisses it early. */
export function toast(message, { kind = 'info', timeout = 4000 } = {}) {
  const el = document.createElement('div');
  el.className = `toast ${kind === 'error' ? 'error' : ''}`;
  el.textContent = message;
  toastRoot().appendChild(el);
  let closed = false;
  const dismiss = () => {
    if (closed) return;
    closed = true;
    el.classList.add('leaving');
    setTimeout(() => el.remove(), 260);
  };
  if (timeout > 0) setTimeout(dismiss, timeout);
  return dismiss;
}

/** Native <dialog> confirm. Resolves true when the primary action is chosen. */
export function confirmDialog({ title, text, okLabel }) {
  const dlg = document.getElementById('confirm');
  document.getElementById('confirmTitle').textContent = title;
  document.getElementById('confirmText').textContent = text;
  document.getElementById('confirmOk').textContent = okLabel;
  return new Promise((resolve) => {
    const onClose = () => {
      dlg.removeEventListener('close', onClose);
      resolve(dlg.returnValue === 'ok');
    };
    dlg.returnValue = 'cancel';
    dlg.addEventListener('close', onClose);
    dlg.showModal();
    document.getElementById('confirmCancel').focus();
  });
}

export const fmtCount = (n) => n.toLocaleString();
export const plural = (n, one, many) => `${fmtCount(n)} ${n === 1 ? one : many}`;

// ---------------------------------------------------------------- small shared helpers

const SVG_NS = 'http://www.w3.org/2000/svg';

/** An inline icon from the sprite in index.html. */
export function svgIcon(name) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('class', 'ic');
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS(SVG_NS, 'use');
  use.setAttribute('href', `#i-${name}`);
  svg.appendChild(use);
  return svg;
}

export function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

export function timeAgo(ms, now = Date.now()) {
  const s = Math.max(0, Math.round((now - ms) / 1000));
  if (s < 45) return 'now';
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

export function formatBytes(n) {
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
}

export function initials(name) {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return '#';
  const first = parts[0][0];
  const second = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + second).toUpperCase();
}

const AVATAR_COLORS = ['#3b55f2', '#8a5cf6', '#0f7f8c', '#a35f00', '#1d6f42', '#b4233f', '#566678'];
export function colorFor(text) {
  let h = 0;
  for (const ch of String(text)) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

export function debounce(fn, ms) {
  let t = 0;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

export async function copyText(text, okMessage = 'Copied') {
  try {
    await navigator.clipboard.writeText(text);
    toast(okMessage);
  } catch {
    toast('Select the text and copy it manually', { kind: 'error' });
  }
}

export function emptyState(title, text) {
  const box = el('div', 'empty-box');
  box.append(el('h3', null, title), el('p', 'muted', text));
  return box;
}
