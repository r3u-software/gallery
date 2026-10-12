// Dashboard panels beyond photos: notifications, messages, files + clipboard, phone status + find.
// Each panel is created once, loads when first shown, and refreshes while visible.

import { getJSON, sendJSON, ApiError } from './api.js';
import { toast, plural } from './ui.js';

const $ = (id) => document.getElementById(id);

function el(tag, cls, text) {
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

function emptyState(title, text) {
  const box = el('div', 'empty-box');
  box.append(el('h3', null, title), el('p', 'muted', text));
  return box;
}

async function safe(fn) {
  try {
    return await fn();
  } catch (err) {
    if (err instanceof ApiError && (err.status === 401 || err.status === 423)) return null; // handled globally
    if (err instanceof ApiError && err.status === 403 && err.body?.error === 'feature_disabled') return null;
    throw err;
  }
}

// ------------------------------------------------------------------ notifications

export const notifications = {
  timer: 0,
  async show() {
    await this.load();
    this.timer = setInterval(() => this.load(), 4000);
  },
  hide() { clearInterval(this.timer); this.timer = 0; },

  async load() {
    const data = await safe(() => getJSON('/api/notifications'));
    if (!data) return;
    const badge = $('notifBadge');
    badge.hidden = data.items.length === 0;
    badge.textContent = String(data.items.length);

    const list = $('notifList');
    $('notifHint').textContent = data.connected ? plural(data.items.length, 'notification', 'notifications') : '';
    list.replaceChildren();
    if (!data.connected) {
      list.append(emptyState('Notification access is off', 'On the phone, open R3U Gallery, then Features and passcode, switch on Notifications and allow Notification access.'));
      return;
    }
    if (!data.items.length) {
      list.append(emptyState('All clear', 'New phone notifications appear here.'));
      return;
    }
    for (const n of data.items) list.append(this.card(n));
  },

  card(n) {
    const c = el('article', 'notif');
    const top = el('div', 'notif-top');
    top.append(el('span', 'notif-app', n.appName), el('span', 'muted', timeAgo(n.time)));
    const body = el('div', 'notif-body');
    if (n.title) body.append(el('strong', null, n.title));
    if (n.text) body.append(el('p', null, n.text));
    const actions = el('div', 'notif-actions');
    const dismiss = el('button', 'btn ghost', 'Dismiss');
    dismiss.type = 'button';
    dismiss.addEventListener('click', async () => {
      c.classList.add('removing');
      await safe(() => sendJSON('/api/notifications/dismiss', { key: n.key }));
      setTimeout(() => this.load(), 200);
    });
    actions.append(dismiss);

    if (n.canReply) {
      const form = el('form', 'notif-reply');
      const input = el('input');
      input.type = 'text';
      input.placeholder = 'Reply';
      input.setAttribute('aria-label', `Reply to ${n.title || n.appName}`);
      const send = el('button', 'btn', 'Send');
      send.type = 'submit';
      form.append(input, send);
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const text = input.value.trim();
        if (!text) return;
        send.disabled = true;
        try {
          await sendJSON('/api/notifications/reply', { key: n.key, text });
          input.value = '';
          toast('Reply sent');
        } catch (err) {
          if (err instanceof ApiError && err.status !== 423) toast("Couldn't send the reply", { kind: 'error' });
        } finally {
          send.disabled = false;
        }
      });
      c.append(top, body, form, actions);
    } else {
      c.append(top, body, actions);
    }
    return c;
  },
};

// ------------------------------------------------------------------ messages

export const messages = {
  timer: 0,
  current: null,
  threads: [],

  async show() {
    await this.loadThreads();
    this.timer = setInterval(() => { this.loadThreads(); if (this.current) this.loadMessages(true); }, 6000);
  },
  hide() { clearInterval(this.timer); this.timer = 0; },

  async loadThreads() {
    const data = await safe(() => getJSON('/api/sms/threads'));
    if (!data) return;
    const list = $('threadList');
    list.replaceChildren();
    if (!data.permission) {
      list.append(emptyState('Messages are off', 'On the phone, open R3U Gallery, then Features and passcode, switch on Messages and allow SMS access.'));
      return;
    }
    this.threads = data.threads;
    if (!data.threads.length) {
      list.append(emptyState('No conversations', 'Text messages on the phone appear here.'));
      return;
    }
    for (const t of data.threads) {
      const row = el('button', 'thread');
      row.type = 'button';
      row.setAttribute('role', 'listitem');
      if (this.current && this.current.id === t.id) row.classList.add('active');
      const top = el('div', 'thread-top');
      top.append(el('strong', null, t.name || t.address), el('span', 'muted', timeAgo(t.date)));
      const snip = el('div', 'thread-snip');
      snip.append(el('span', null, t.snippet));
      if (t.unread) snip.append(el('span', 'badge', String(t.unread)));
      row.append(top, snip);
      row.addEventListener('click', () => this.open(t));
      list.append(row);
    }
  },

  async open(t) {
    this.current = t;
    $('convoEmpty').hidden = true;
    $('convoHead').hidden = false;
    $('convoHead').textContent = t.name && t.name !== t.address ? `${t.name} · ${t.address}` : t.address;
    $('bubbles').hidden = false;
    $('composer').hidden = false;
    for (const b of $('threadList').children) b.classList.toggle('active', b.querySelector('strong')?.textContent === (t.name || t.address));
    await this.loadMessages(false);
    $('composeText').focus();
  },

  async loadMessages(keepScroll) {
    if (!this.current) return;
    const data = await safe(() => getJSON(`/api/sms/threads/${this.current.id}?limit=200`));
    if (!data) return;
    const box = $('bubbles');
    const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
    box.replaceChildren();
    for (const m of data.messages) box.append(this.bubble(m.body, m.outgoing, m.date));
    if (!keepScroll || nearBottom) box.scrollTop = box.scrollHeight;
  },

  bubble(text, outgoing, date) {
    const b = el('div', `bubble ${outgoing ? 'out' : 'in'}`);
    b.append(el('p', null, text), el('time', 'muted', new Date(date).toLocaleString(undefined, { hour: 'numeric', minute: '2-digit', day: 'numeric', month: 'short' })));
    return b;
  },

  init() {
    $('composer').addEventListener('submit', async (e) => {
      e.preventDefault();
      const input = $('composeText');
      const body = input.value.trim();
      if (!body || !this.current) return;
      const btn = $('composeSend');
      btn.disabled = true;
      try {
        await sendJSON('/api/sms/send', { to: this.current.address, body });
        input.value = '';
        // Android only logs sent texts for the default SMS app, so show it locally.
        $('bubbles').append(this.bubble(body, true, Date.now()));
        $('bubbles').scrollTop = $('bubbles').scrollHeight;
      } catch (err) {
        if (err instanceof ApiError && err.status !== 423) toast("Couldn't send the text", { kind: 'error' });
      } finally {
        btn.disabled = false;
      }
    });
    $('composeText').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('composer').requestSubmit(); }
    });
  },
};

// ------------------------------------------------------------------ files + clipboard

export const files = {
  timer: 0,
  async show() {
    await this.load();
    this.timer = setInterval(() => this.load(), 8000);
  },
  hide() { clearInterval(this.timer); this.timer = 0; },

  async load() {
    const [list, clip] = await Promise.all([safe(() => getJSON('/api/files')), safe(() => getJSON('/api/clipboard'))]);
    if (list) this.renderList(list.items);
    if (clip) this.renderClip(clip);
  },

  renderList(items) {
    const box = $('fileList');
    box.replaceChildren();
    if (!items.length) {
      box.append(emptyState('No files yet', 'Files you send, or share from the phone, show up here.'));
      return;
    }
    for (const f of items) {
      const row = el('div', 'file');
      const info = el('div', 'file-info');
      info.append(el('strong', null, f.name), el('span', 'muted', `${formatBytes(f.size)} · ${timeAgo(f.modified)}`));
      const dl = el('a', 'btn', 'Download');
      dl.href = `/api/files/${f.id}/download`;
      dl.setAttribute('download', f.name);
      const del = el('button', 'btn ghost', 'Delete');
      del.type = 'button';
      del.addEventListener('click', async () => {
        if (del.dataset.armed !== '1') {
          del.dataset.armed = '1';
          del.textContent = 'Confirm delete';
          setTimeout(() => { del.dataset.armed = ''; del.textContent = 'Delete'; }, 3000);
          return;
        }
        await safe(() => sendJSON(`/api/files/${f.id}`, {}, 'DELETE'));
        this.load();
      });
      row.append(info, dl, del);
      box.append(row);
    }
  },

  renderClip(c) {
    const p = $('clipFromPhone');
    const copy = $('clipCopy');
    if (c.text) {
      p.textContent = c.text;
      copy.hidden = false;
      copy.onclick = async () => {
        try { await navigator.clipboard.writeText(c.text); toast('Copied'); } catch { toast('Select the text and copy it manually', { kind: 'error' }); }
      };
    } else {
      p.textContent = 'Nothing shared yet. Share text to R3U Gallery on the phone.';
      copy.hidden = true;
    }
  },

  upload(fileList) {
    const bar = $('uploadBar');
    const wrap = $('uploadProgress');
    const form = new FormData();
    for (const f of fileList) form.append('file', f, f.name);
    wrap.hidden = false;
    bar.style.setProperty('--p', '0');
    bar.classList.remove('done');
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/files/upload');
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) bar.style.setProperty('--p', String(e.loaded / e.total)); };
    xhr.onload = () => {
      wrap.hidden = true;
      if (xhr.status === 200) { toast(`Sent ${plural(fileList.length, 'file', 'files')} to the phone`); this.load(); }
      else toast(xhr.status === 423 ? 'Enter the passcode first' : "Couldn't send the files", { kind: 'error' });
    };
    xhr.onerror = () => { wrap.hidden = true; toast("Couldn't reach the phone", { kind: 'error' }); };
    xhr.send(form);
  },

  init() {
    const drop = $('drop');
    const input = $('fileInput');
    input.addEventListener('change', () => { if (input.files.length) this.upload([...input.files]); input.value = ''; });
    for (const ev of ['dragenter', 'dragover']) drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add('over'); });
    for (const ev of ['dragleave', 'drop']) drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove('over'); });
    drop.addEventListener('drop', (e) => { if (e.dataTransfer?.files?.length) this.upload([...e.dataTransfer.files]); });
    $('clipSend').addEventListener('click', async () => {
      const text = $('clipToPhone').value;
      if (!text.trim()) return;
      try { await sendJSON('/api/clipboard', { text }); toast('Copied to the phone'); } catch (err) { if (err instanceof ApiError && err.status !== 423) toast("Couldn't send", { kind: 'error' }); }
    });
  },
};

// ------------------------------------------------------------------ status + find

export const status = {
  timer: 0,
  ringing: false,
  async show() {
    await this.load();
    this.timer = setInterval(() => this.load(), 10000);
  },
  hide() { clearInterval(this.timer); this.timer = 0; },

  async load() {
    const [s, f] = await Promise.all([safe(() => getJSON('/api/status')), safe(() => getJSON('/api/find'))]);
    if (s) this.render(s);
    if (f) this.setRinging(f.ringing);
  },

  render(s) {
    $('deviceName').textContent = s.name;
    $('deviceModel').textContent = `${s.model} · Android ${s.androidVersion}`;
    const grid = $('statusGrid');
    grid.replaceChildren();

    const battery = el('div', 'meter-card');
    battery.append(el('span', 'eyebrow', 'Battery'));
    battery.append(el('strong', 'big', `${s.batteryPercent}%`));
    battery.append(this.meter(s.batteryPercent / 100, s.batteryPercent < 20 ? 'low' : ''));
    battery.append(el('span', 'muted', s.charging ? 'Charging' : 'On battery'));

    const used = s.storageTotalBytes - s.storageFreeBytes;
    const storage = el('div', 'meter-card');
    storage.append(el('span', 'eyebrow', 'Storage'));
    storage.append(el('strong', 'big', `${formatBytes(s.storageFreeBytes)} free`));
    storage.append(this.meter(s.storageTotalBytes ? used / s.storageTotalBytes : 0, ''));
    storage.append(el('span', 'muted', `${formatBytes(used)} of ${formatBytes(s.storageTotalBytes)} used`));
    grid.append(battery, storage);
  },

  meter(frac, cls) {
    const m = el('div', `meter ${cls}`);
    m.setAttribute('role', 'img');
    m.setAttribute('aria-label', `${Math.round(frac * 100)} percent`);
    const fill = el('div', 'meter-fill');
    fill.style.setProperty('--p', String(Math.min(1, Math.max(0, frac))));
    m.append(fill);
    return m;
  },

  setRinging(on) {
    this.ringing = on;
    const b = $('findBtn');
    b.textContent = on ? 'Stop ringing' : 'Ring phone';
    b.classList.toggle('danger', on);
  },

  init() {
    $('findBtn').addEventListener('click', async () => {
      try {
        const r = await sendJSON(this.ringing ? '/api/find/stop' : '/api/find/ring', {});
        this.setRinging(!!r.ringing);
      } catch (err) {
        if (err instanceof ApiError && err.status !== 423) toast("Couldn't ring the phone", { kind: 'error' });
      }
    });
  },
};
