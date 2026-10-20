// Phone: battery, storage, device info, Find my phone, and "Open on the phone".

import { getJSON, sendJSON, ApiError } from '../api.js';
import { toast, el, svgIcon, formatBytes, confirmDialog } from '../ui.js';
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

// ---- Backup to Google Drive: settings only. The phone never reports progress or errors.

const URL_PLACEHOLDER = 'https://script.google.com/macros/s/…/exec';
const SECRET_PLACEHOLDER = 'The secret from the setup page';

function backupMessage(text, bad = false) {
  const m = $('backupMsg');
  m.textContent = text;
  m.classList.toggle('bad', bad);
}

function backupOff(off) {
  $('backupOff').hidden = !off;
  $('backupForm').hidden = off;
}

async function loadBackup() {
  try {
    const s = await getJSON('/api/backup');
    backupOff(false);
    $('backupUrl').placeholder = s.configured ? `Saved (${s.urlHint}). Type to replace it.` : URL_PLACEHOLDER;
    $('backupSecret').placeholder = s.configured ? 'Saved. Type to replace it.' : SECRET_PLACEHOLDER;
    $('backupPhotos').checked = s.photos;
    $('backupVideos').checked = s.videos;
    $('backupWifi').checked = s.wifiOnly;
    $('backupClear').hidden = !s.configured || s.builtIn;
    $('backupAuto').hidden = !s.builtIn;
    if (s.builtIn) {
      $('backupUrl').placeholder = 'Built in. Type here only to use a different script.';
      $('backupSecret').placeholder = 'Built in.';
    }
  } catch (err) {
    if (err instanceof ApiError && err.status === 403) backupOff(true);
    else if (!(err instanceof ApiError)) throw err;
  }
}

async function saveBackup(event) {
  event.preventDefault();
  backupMessage('Saving…');
  try {
    const r = await sendJSON('/api/backup', {
      url: $('backupUrl').value.trim(),
      secret: $('backupSecret').value,
      photos: $('backupPhotos').checked,
      videos: $('backupVideos').checked,
      wifiOnly: $('backupWifi').checked,
    });
    $('backupUrl').value = '';
    $('backupSecret').value = '';
    backupMessage(
      r.reachable ? 'Saved. The script can reach your folder, so backup starts by itself.' : 'Saved, but the script did not confirm it can reach your folder. Check the address, the secret and that you added the Drive service.',
      !r.reachable,
    );
    await loadBackup();
  } catch (err) {
    if (!(err instanceof ApiError)) throw err;
    if (err.status === 403) backupOff(true);
    else backupMessage(err.status === 400 && err.body?.error ? err.body.error : "Couldn't save the backup settings", true);
  }
}

async function clearBackup() {
  const ok = await confirmDialog({
    title: 'Stop backing up?',
    text: 'The phone forgets the script address and the secret you entered. Files already in Drive stay there.',
    okLabel: 'Stop backup',
  });
  if (!ok) return;
  try {
    await sendJSON('/api/backup/clear', {});
    backupMessage('Backup removed.');
    await loadBackup();
  } catch (err) {
    if (!(err instanceof ApiError)) throw err;
    backupMessage("Couldn't remove it", true);
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
    $('backupForm').addEventListener('submit', saveBackup);
    $('backupClear').addEventListener('click', clearBackup);
  },

  async show() {
    loadBackup();
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
    c.append(item('Summary', 'phone', 'view-phone'), item('Battery', 'refresh', 'card-battery'), item('Storage', 'folder', 'card-storage'), item('Find my phone', 'volume', 'findCard'), item('Open on phone', 'link', 'openCard'), item('Backup to Drive', 'folder', 'backupCard'));
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
