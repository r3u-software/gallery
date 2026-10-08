import { fetchPage, requestTrash, trashStatus, downloadIds, onUnauthorized } from './api.js';
import { dayKey } from './layout.js';
import { Grid } from './grid.js';
import { Viewer } from './viewer.js';
import { toast, confirmDialog, plural, fmtCount } from './ui.js';

const $ = (id) => document.getElementById(id);

const state = {
  filter: 'all',
  sort: 'newest',
  items: [],
  selected: new Set(),
  anchor: null,
  abort: null,
  trashBusy: false,
  expired: false,
};

const topbar = $('topbar');
const grid = new Grid($('grid'), {
  onActivate: handleActivate,
  onSelectRange: (from, to) => selectRange(from, to - 1, true),
  getTopInset: () => topbar.offsetHeight,
});

const viewer = new Viewer({
  getItems: () => state.items,
  onClose: (idx) => {
    if (state.items[idx]) grid.focusIndex(idx);
  },
});

// Keep a CSS variable in sync with the real (wrapping) top bar height.
new ResizeObserver(() => {
  document.documentElement.style.setProperty('--topbar-h', `${topbar.offsetHeight}px`);
}).observe(topbar);

onUnauthorized(() => {
  state.expired = true;
  $('expired').hidden = false;
});

// ---------------------------------------------------------------- loading

function showSkeleton(on) {
  const sk = $('skeleton');
  if (on && !sk.childElementCount) {
    for (let i = 0; i < 28; i++) {
      const t = document.createElement('div');
      t.className = 'skel-tile';
      t.style.flexGrow = String(0.6 + ((i * 37) % 10) / 10);
      sk.appendChild(t);
    }
  }
  sk.hidden = !on;
}

function updateCount() {
  const n = state.items.length;
  $('count').textContent = n === 0 ? '' : plural(n, 'photo', 'photos');
  const empty = n === 0 && !state.loading;
  $('empty').hidden = !empty;
  $('emptyText').textContent =
    state.filter === 'screenshots' ? 'No screenshots found.' :
    state.filter === 'large' ? 'No photos larger than 5 MB.' :
    'This phone has no photos yet.';
  $('selectAll').disabled = n === 0;
}

async function load() {
  state.abort?.abort();
  const ctrl = new AbortController();
  state.abort = ctrl;

  state.items = [];
  state.loading = true;
  clearSelection();
  grid.setItems(state.items);
  $('count').textContent = 'Loading…';
  $('empty').hidden = true;
  showSkeleton(true);

  let cursor = null;
  try {
    do {
      const page = await fetchPage({ cursor, sort: state.sort, filter: state.filter, signal: ctrl.signal });
      if (ctrl.signal.aborted) return;
      for (const it of page.items) it.day = dayKey(it.dateTaken);
      state.items.push(...page.items);
      grid.setItems(state.items);
      showSkeleton(false);
      cursor = page.nextCursor;
      $('loadingMore').hidden = !cursor;
      $('count').textContent = plural(page.total, 'photo', 'photos');
    } while (cursor);
  } catch (err) {
    if (ctrl.signal.aborted || state.expired) return;
    toast("Couldn't load your photos. Check that the app is still running on your phone.", { kind: 'error', timeout: 8000 });
  } finally {
    if (state.abort === ctrl) {
      state.loading = false;
      $('loadingMore').hidden = true;
      showSkeleton(false);
      updateCount();
    }
  }
}

// ---------------------------------------------------------------- selection

function setSelection(set) {
  state.selected = set;
  grid.setSelected(set);
  const n = set.size;
  const bar = $('selbar');
  bar.classList.toggle('show', n > 0);
  bar.setAttribute('aria-hidden', String(n === 0));
  bar.inert = n === 0;
  if (n > 0) $('selCount').textContent = `${fmtCount(n)} selected`;
}

function clearSelection() {
  state.anchor = null;
  setSelection(new Set());
}

function toggleIndex(idx) {
  const id = state.items[idx].id;
  const next = new Set(state.selected);
  if (!next.delete(id)) next.add(id);
  state.anchor = idx;
  setSelection(next);
}

function selectRange(from, to, additive = true) {
  const lo = Math.max(0, Math.min(from, to));
  const hi = Math.min(state.items.length - 1, Math.max(from, to));
  const next = additive ? new Set(state.selected) : new Set();
  for (let i = lo; i <= hi; i++) next.add(state.items[i].id);
  setSelection(next);
}

function handleActivate({ idx, shift, meta, onCheck }) {
  if (idx === undefined) return;
  if (shift) {
    if (state.anchor === null) toggleIndex(idx);
    else selectRange(state.anchor, idx);
    return;
  }
  if (meta || onCheck || state.selected.size > 0) {
    toggleIndex(idx);
    return;
  }
  state.anchor = idx;
  viewer.open(idx);
}

// ---------------------------------------------------------------- download

function downloadSelected() {
  const ids = [...state.selected];
  if (!ids.length) return;
  downloadIds(ids);
  toast(ids.length === 1 ? 'Downloading 1 photo…' : `Preparing a zip of ${fmtCount(ids.length)} photos…`, { timeout: 5000 });
}

// ---------------------------------------------------------------- trash

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Polls the phone until the user answers. Resolves 'done' | 'denied' | 'timeout' | 'cancelled'. */
async function waitForPhone(cancelToken, maxMs = 150_000) {
  const started = Date.now();
  while (Date.now() - started < maxMs) {
    if (cancelToken.cancelled || state.expired) return 'cancelled';
    try {
      const { status } = await trashStatus();
      if (status === 'done' || status === 'denied' || status === 'timeout') return status;
    } catch {
      // transient network hiccup: keep trying until the deadline
    }
    await sleep(1000);
  }
  return 'timeout';
}

async function trashSelected() {
  const ids = [...state.selected];
  if (!ids.length || state.trashBusy) return;

  const ok = await confirmDialog({
    title: `Move ${plural(ids.length, 'photo', 'photos')} to trash?`,
    text: "You'll be asked to confirm on your phone next. Android keeps trashed photos for 30 days, so they can be restored there.",
    okLabel: 'Move to trash',
  });
  if (!ok) return;

  state.trashBusy = true;
  try {
    try {
      await requestTrash(ids);
    } catch {
      if (!state.expired) toast("Couldn't reach your phone. Nothing was moved.", { kind: 'error' });
      return;
    }

    const dlg = $('waiting');
    const cancelToken = { cancelled: false };
    $('waitingCount').textContent = plural(ids.length, 'photo', 'photos');
    $('waitingStop').onclick = () => { cancelToken.cancelled = true; };
    dlg.addEventListener('cancel', (e) => e.preventDefault(), { once: true }); // Esc shouldn't hide it silently
    dlg.showModal();
    const result = await waitForPhone(cancelToken);
    dlg.close();

    if (result === 'done') {
      clearSelection();
      grid.removeIds(ids, (next) => {
        state.items = next;
        updateCount();
        $('count').textContent = plural(next.length, 'photo', 'photos');
      });
      toast(`Moved ${plural(ids.length, 'photo', 'photos')} to trash`);
    } else if (result === 'denied') {
      toast('Nothing was moved: the request was declined on your phone.');
    } else if (result === 'timeout') {
      toast('No answer from your phone, so nothing was moved. Try again.', { kind: 'error', timeout: 7000 });
    } else {
      toast('Stopped waiting. If you confirm on the phone later, reload this page to refresh.', { timeout: 7000 });
    }
  } finally {
    state.trashBusy = false;
  }
}

// ---------------------------------------------------------------- controls

$('chips').addEventListener('click', (e) => {
  const chip = e.target.closest('.chip');
  if (!chip || chip.dataset.filter === state.filter) return;
  state.filter = chip.dataset.filter;
  for (const c of $('chips').children) c.setAttribute('aria-pressed', String(c === chip));
  load();
});

$('sort').addEventListener('change', (e) => {
  state.sort = e.target.value;
  load();
});

$('selectAll').addEventListener('click', () => {
  if (state.items.length) selectRange(0, state.items.length - 1, false);
});
$('selClear').addEventListener('click', clearSelection);
$('selDownload').addEventListener('click', downloadSelected);
$('selTrash').addEventListener('click', trashSelected);

document.addEventListener('keydown', (e) => {
  if (viewer.isOpen || $('confirm').open || $('waiting').open) return;
  const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName);
  if (e.key === 'Escape' && state.selected.size) {
    clearSelection();
  } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a' && !typing && state.items.length) {
    e.preventDefault();
    selectRange(0, state.items.length - 1, false);
  }
});

load();
