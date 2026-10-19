// Boot: passcode lock, session handling, then hand over to the shell.

import { onUnauthorized, onLocked, onNetworkError, getSession, login, logout, getJSON, ApiError } from './api.js';
import { shell } from './shell.js';
import { plural } from './ui.js';
import { app as photos } from './apps/photos.js';
import { app as notifications } from './apps/notifications.js';
import { app as messages } from './apps/messages.js';
import { app as contacts } from './apps/contacts.js';
import { app as files } from './apps/files.js';
import { app as phone } from './apps/phone.js';

const $ = (id) => document.getElementById(id);

const state = { expired: false, booted: false, session: null, helpShownAt: 0, statusTimer: 0 };

for (const a of [photos, notifications, messages, contacts, files, phone]) shell.register(a);

// ---------------------------------------------------------------- overlays

onUnauthorized(() => {
  state.expired = true;
  $('expired').hidden = false;
});

function showLock(message) {
  const lock = $('lock');
  lock.hidden = false;
  const err = $('lockError');
  err.hidden = !message;
  err.textContent = message || '';
  $('passcode').value = '';
  $('passcode').focus();
}

onLocked(() => { if (!state.expired) showLock(); });

function openHelp() {
  const dlg = $('helpDialog');
  if (!dlg.open) dlg.showModal();
}

onNetworkError(() => {
  if (state.expired) return;
  shell.setConnected(false);
  const now = Date.now();
  if (now - state.helpShownAt > 60_000) {
    state.helpShownAt = now;
    openHelp();
  }
});

$('helpClose').addEventListener('click', () => $('helpDialog').close());
$('helpRetry').addEventListener('click', () => {
  $('helpDialog').close();
  loadStatusBar();
  shell.active?.refresh?.();
});

$('lockForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = $('lockBtn');
  btn.disabled = true;
  try {
    await login($('passcode').value);
    $('lock').hidden = true;
    state.session = await getSession();
    start(state.session);
  } catch (err) {
    if (err instanceof ApiError && err.status === 429) {
      showLock(`Too many attempts. Try again in ${err.body?.retryAfterSec ?? 30} seconds.`);
    } else if (err instanceof ApiError && err.status === 403) {
      const left = err.body?.attemptsLeft;
      showLock(left != null ? `Wrong passcode. ${plural(left, 'attempt', 'attempts')} left.` : 'Wrong passcode.');
    } else if (!state.expired) {
      showLock("Couldn't reach your phone.");
    }
  } finally {
    btn.disabled = false;
  }
});

// ---------------------------------------------------------------- status bar

async function loadStatusBar() {
  if (shell.features.status === false) return;
  try {
    const s = await getJSON('/api/status');
    shell.setDevice(s.name, s.batteryPercent, s.charging);
    shell.setConnected(true);
  } catch (err) {
    if (err instanceof ApiError) shell.setConnected(true); // the phone answered, just not with data
  }
}

// ---------------------------------------------------------------- boot

function start(session) {
  if (state.booted) {
    shell.active?.refresh?.();
    return;
  }
  state.booted = true;
  shell.init(session.features, {
    help: openHelp,
    lock: async () => {
      try { await logout(); } catch { /* the lock screen still shows */ }
      showLock();
    },
  });
  shell.setDevice(session.device);
  const wanted = location.hash.replace('#', '');
  const first = shell.byId.get(wanted) && shell.enabled(shell.byId.get(wanted)) ? shell.byId.get(wanted) : shell.firstEnabled();
  if (first) shell.showApp(first.id);
  loadStatusBar();
  state.statusTimer = setInterval(loadStatusBar, 30_000);
}

(async function boot() {
  let session;
  try {
    session = await getSession();
  } catch {
    return; // a 401 already showed "Session ended"; a network error showed the help dialog
  }
  state.session = session;
  if (session.passcodeRequired && !session.unlocked) {
    showLock();
    return;
  }
  start(session);
})();
