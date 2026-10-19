// Notifications: apps with notifications in the list pane, the notifications themselves in the content area.

import { getJSON, sendJSON, ApiError } from '../api.js';
import { toast, plural, el, svgIcon, timeAgo, emptyState, colorFor, fmtCount } from '../ui.js';
import { shell } from '../shell.js';

const $ = (id) => document.getElementById(id);

const S = { items: [], connected: false, loaded: false, app: '', query: '', timer: 0, busy: false };

const shown = () => {
  const q = S.query.toLowerCase();
  return S.items.filter((n) =>
    (!S.app || n.app === S.app) &&
    (!q || `${n.title} ${n.text} ${n.appName}`.toLowerCase().includes(q)));
};

async function load() {
  try {
    const data = await getJSON('/api/notifications');
    S.items = data.items;
    S.connected = data.connected;
    S.loaded = true;
    shell.setBadge('notifications', S.connected ? S.items.length : 0);
    if (shell.active === app) {
      render();
      shell.refreshPane();
      shell.refreshRibbon();
      shell.setInfo(app.statusInfo());
    }
  } catch (err) {
    if (!(err instanceof ApiError)) return; // network trouble is reported globally
  }
}

function render() {
  const list = $('notifList');
  const items = shown();
  $('notifHint').textContent = S.connected ? plural(items.length, 'notification', 'notifications') : '';
  list.replaceChildren();
  if (!S.connected) {
    list.append(emptyState('Notification access is off', 'On the phone, open R3U | Phone Link, then Features and passcode, switch on Notifications and allow Notification access.'));
    return;
  }
  if (!items.length) {
    list.append(emptyState(S.query ? 'No matches' : 'All clear', S.query ? 'No notification matches your search.' : 'New phone notifications appear here.'));
    return;
  }
  for (const n of items) list.append(card(n));
}

function card(n) {
  const c = el('article', 'notif');
  const top = el('div', 'nhead');
  const icon = el('span', 'appicon', (n.appName || '?')[0].toUpperCase());
  icon.style.background = colorFor(n.app);
  top.append(icon, el('span', 'grow', n.appName), el('span', 'muted', timeAgo(n.time)));
  const body = el('div', 'nbody');
  if (n.title) body.append(el('strong', null, n.title));
  if (n.text) body.append(el('p', null, n.text));

  const actions = el('div', 'nactions');
  const dismiss = el('button', 'btn ghost', 'Dismiss');
  dismiss.type = 'button';
  dismiss.addEventListener('click', async () => {
    c.classList.add('removing');
    await dismissKey(n.key);
    setTimeout(load, 200);
  });
  actions.append(dismiss);

  if (n.canReply) {
    const form = el('form', 'nreply');
    const input = el('input', 'field');
    input.type = 'text';
    input.placeholder = 'Reply';
    input.setAttribute('aria-label', `Reply to ${n.title || n.appName}`);
    const send = el('button', 'btn');
    send.type = 'submit';
    send.append(svgIcon('send'), document.createTextNode('Send'));
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
        if (err instanceof ApiError && ![401, 423].includes(err.status)) toast("Couldn't send the reply", { kind: 'error' });
      } finally {
        send.disabled = false;
      }
    });
    c.append(top, body, form, actions);
  } else {
    c.append(top, body, actions);
  }
  return c;
}

async function dismissKey(key) {
  try {
    await sendJSON('/api/notifications/dismiss', { key });
  } catch { /* already gone */ }
}

async function dismissAll() {
  const items = shown();
  if (!items.length || S.busy) return;
  S.busy = true;
  try {
    for (const n of items) await dismissKey(n.key);
    toast(`Dismissed ${plural(items.length, 'notification', 'notifications')}`);
  } finally {
    S.busy = false;
    load();
  }
}

export const app = {
  id: 'notifications',
  label: 'Alerts',
  icon: 'bell',
  feature: 'notifications',
  paneTitle: 'Apps',
  searchPlaceholder: 'Search notifications',

  mount() {
    // keep the badge on the app bar fresh even while another app is open
    S.timer = setInterval(() => { if (shell.active !== app) load(); }, 20000);
    load();
  },

  async show() {
    render();
    await load();
    clearInterval(S.timer);
    S.timer = setInterval(load, 4000);
  },

  hide() {
    clearInterval(S.timer);
    S.timer = setInterval(load, 20000);
  },

  refresh: load,

  renderPane(c) {
    const apps = new Map();
    for (const n of S.items) {
      const entry = apps.get(n.app) || { name: n.appName, count: 0 };
      entry.count += 1;
      apps.set(n.app, entry);
    }
    const row = (key, name, count) => {
      const b = el('button', `pitem${S.app === key ? ' on' : ''}`);
      b.type = 'button';
      const t = el('span', 'athumb', (name || '?')[0].toUpperCase());
      t.style.background = key ? colorFor(key) : 'var(--accent)';
      b.append(t, el('span', 'pname', name), el('span', 'pcount', fmtCount(count)));
      b.addEventListener('click', () => { S.app = key; render(); shell.refreshPane(); shell.refreshRibbon(); });
      return b;
    };
    c.append(row('', 'All apps', S.items.length));
    for (const [key, v] of apps) c.append(row(key, v.name, v.count));
    if (!S.items.length) c.append(el('div', 'pane-note', S.connected ? 'No notifications right now.' : 'Notification access is off.'));
  },

  commands() {
    const n = shown().length;
    return [
      {
        label: 'Clear',
        items: [{ type: 'btn', label: n > 1 ? 'Dismiss all' : 'Dismiss', icon: 'trash', danger: true, disabled: !n || !S.connected, run: dismissAll }],
      },
      { label: 'Data', items: [{ type: 'btn', label: 'Refresh', icon: 'refresh', run: load }] },
    ];
  },

  search(q) {
    S.query = q;
    render();
    shell.refreshRibbon();
  },

  statusInfo() {
    return S.connected ? `${plural(shown().length, 'notification', 'notifications')}` : 'Notification access is off';
  },
};
