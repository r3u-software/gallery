// Phone: battery, storage, device info, Find my phone, and "Open on the phone".

import { getJSON, sendJSON, ApiError } from '../api.js';
import { toast, el, svgIcon, formatBytes } from '../ui.js';
import { shell } from '../shell.js';
import { askOpen } from '../openphone.js';

const $ = (id) => document.getElementById(id);

const S = { status: null, ringing: false, timer: 0, features: {} };

async function load() {
  const [s, f] = await Promise.all([
    getJSON('/api/status').catch((e) => { if (!(e instanceof ApiError)) throw e; return null; }),
    getJSON('/api/find').catch(() => null),
  ]);
  if (s) {
    S.status = s;
    render(s);
    shell.setDevice(s.name, s.batteryPercent, s.charging);
  }
  if (f) setRinging(f.ringing);
}

function meter(frac, low) {
  const m = el('div', `meter${low ? ' low' : ''}`);
  m.setAttribute('role', 'img');
  m.setAttribute('aria-label', `${Math.round(frac * 100)} percent`);
  const i = el('i');
  i.style.width = `${Math.round(Math.min(1, Math.max(0, frac)) * 100)}%`;
  m.append(i);
  return m;
}

function render(s) {
  $('deviceName').textContent = s.name;
  $('deviceModel').textContent = `${s.model} · Android ${s.androidVersion}`;
  const grid = $('statusGrid');
  grid.replaceChildren();

  const battery = el('div', 'meter-card');
  battery.id = 'card-battery';
  battery.append(el('span', 'eyebrow', 'Battery'), el('strong', 'big', `${s.batteryPercent}%`), meter(s.batteryPercent / 100, s.batteryPercent < 20), el('span', 'muted', s.charging ? 'Charging' : 'On battery'));

  const used = s.storageTotalBytes - s.storageFreeBytes;
  const storage = el('div', 'meter-card');
  storage.id = 'card-storage';
  storage.append(el('span', 'eyebrow', 'Storage'), el('strong', 'big', `${formatBytes(s.storageFreeBytes)} free`), meter(s.storageTotalBytes ? used / s.storageTotalBytes : 0, false), el('span', 'muted', `${formatBytes(used)} of ${formatBytes(s.storageTotalBytes)} used`));
  grid.append(battery, storage);
}

function setRinging(on) {
  S.ringing = on;
  const b = $('findBtn');
  b.textContent = on ? 'Stop ringing' : 'Ring phone';
  b.classList.toggle('danger', on);
  if (shell.active === app) shell.refreshRibbon();
}

async function toggleRing() {
  try {
    const r = await sendJSON(S.ringing ? '/api/find/stop' : '/api/find/ring', {});
    setRinging(!!r.ringing);
  } catch (err) {
    if (err instanceof ApiError && ![401, 423].includes(err.status)) toast(err.status === 403 ? 'Find my phone is off in the phone app' : "Couldn't ring the phone", { kind: 'error' });
  }
}

const jump = (id) => { $(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' }); shell.togglePane(false); };

export const app = {
  id: 'phone',
  label: 'Phone',
  icon: 'phone',
  feature: 'status',
  bottom: true,
  paneTitle: 'Phone',
  searchPlaceholder: 'Search',

  mount() {
    $('findBtn').addEventListener('click', toggleRing);
    for (const b of document.querySelectorAll('[data-open]')) b.addEventListener('click', () => askOpen(b.dataset.open));
  },

  async show() {
    await load();
    S.timer = setInterval(load, 10000);
  },

  hide() { clearInterval(S.timer); },

  refresh: load,

  renderPane(c) {
    const item = (label, icon, target) => {
      const b = el('button', 'pitem');
      b.type = 'button';
      const t = el('span', 'athumb soft');
      t.append(svgIcon(icon));
      b.append(t, el('span', 'pname', label));
      b.addEventListener('click', () => jump(target));
      return b;
    };
    c.append(item('Summary', 'phone', 'view-phone'), item('Battery', 'refresh', 'card-battery'), item('Storage', 'folder', 'card-storage'), item('Find my phone', 'volume', 'findCard'), item('Open on phone', 'link', 'openCard'));
    c.append(el('div', 'psec', 'Connection'));
    c.append(el('div', 'pane-note', 'Connected over your Wi-Fi. This page talks directly to the phone.'));
  },

  commands() {
    return [
      {
        label: 'Phone',
        items: [{ type: 'btn', label: S.ringing ? 'Stop ringing' : 'Ring phone', icon: 'volume', danger: S.ringing, run: toggleRing }],
      },
      {
        label: 'Open on phone',
        items: [
          { type: 'btn', label: 'Open link', icon: 'link', run: () => askOpen('link') },
          { type: 'btn', label: 'Dial number', icon: 'call', run: () => askOpen('dial') },
          { type: 'btn', label: 'Show on map', icon: 'map', run: () => askOpen('map') },
        ],
      },
      { label: 'Data', items: [{ type: 'btn', label: 'Refresh', icon: 'refresh', run: load }] },
    ];
  },

  statusInfo() {
    return S.status ? `${S.status.model}, Android ${S.status.androidVersion}` : '';
  },
};
