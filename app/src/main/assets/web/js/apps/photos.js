// Photos: albums in the list pane, the virtualized grid, selection, download, trash, and the viewer.

import { fetchPage, fetchAlbums, requestTrash, trashStatus, downloadIds, ApiError } from '../api.js';
import { dayKey } from '../layout.js';
import { Grid } from '../grid.js';
import { Viewer } from '../viewer.js';
import { toast, confirmDialog, plural, fmtCount, el, svgIcon, colorFor } from '../ui.js';
import { shell } from '../shell.js';

const $ = (id) => document.getElementById(id);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const S = {
  all: [],
  items: [],
  filter: 'all',
  sort: 'newest',
  bucket: '',
  query: '',
  selected: new Set(),
  anchor: null,
  abort: null,
  loading: false,
  loaded: false,
  trashBusy: false,
  albums: [],
  total: 0,
  zoom: 1,
};

let grid;
let viewer;

const FILTER_TITLES = { all: 'All photos', screenshots: 'Screenshots', large: 'Large files (over 5 MB)' };

function title() {
  return S.bucket || FILTER_TITLES[S.filter];
}

function updateHeader() {
  $('photosTitle').textContent = title();
  const n = S.items.length;
  const shown = S.query ? `${fmtCount(n)} of ${fmtCount(S.all.length)} match` : plural(n, 'photo', 'photos');
  $('photosSub').textContent = S.loading && !n ? 'Loading…' : `${shown}, ${S.sort === 'newest' ? 'newest first' : 'oldest first'}`;
  const empty = n === 0 && !S.loading;
  $('empty').hidden = !empty;
  $('emptyText').textContent = S.query ? 'No photo name matches your search.' :
    S.bucket ? 'This album is empty.' :
    S.filter === 'screenshots' ? 'No screenshots found.' :
    S.filter === 'large' ? 'No photos larger than 5 MB.' : 'This phone has no photos yet.';
  if (shell.active === app) shell.setInfo(app.statusInfo());
}

function showSkeleton(on) {
  const sk = $('skeleton');
  if (on && !sk.childElementCount) {
    for (let i = 0; i < 24; i++) {
      const t = el('div', 'skel-tile');
      t.style.flexGrow = String(0.6 + ((i * 37) % 10) / 10);
      sk.appendChild(t);
    }
  }
  sk.hidden = !on;
}

// ---------------------------------------------------------------- loading

function applyQuery() {
  const q = S.query.toLowerCase();
  S.items = q ? S.all.filter((p) => p.name.toLowerCase().includes(q)) : S.all;
  grid.setItems(S.items);
  updateHeader();
}

async function load() {
  S.abort?.abort();
  const ctrl = new AbortController();
  S.abort = ctrl;
  S.all = [];
  S.items = S.all;
  S.loading = true;
  S.loaded = true;
  clearSelection();
  grid.setItems(S.items);
  updateHeader();
  showSkeleton(true);

  let cursor = null;
  try {
    do {
      const page = await fetchPage({ cursor, sort: S.sort, filter: S.filter, bucket: S.bucket, signal: ctrl.signal });
      if (ctrl.signal.aborted) return;
      for (const it of page.items) it.day = dayKey(it.dateTaken);
      S.all.push(...page.items);
      applyQuery();
      showSkeleton(false);
      cursor = page.nextCursor;
      $('loadingMore').hidden = !cursor;
    } while (cursor);
  } catch (err) {
    if (ctrl.signal.aborted) return;
    if (!(err instanceof ApiError && [401, 423].includes(err.status))) {
      toast("Couldn't load your photos. Check that the app is still running on your phone.", { kind: 'error', timeout: 8000 });
    }
  } finally {
    if (S.abort === ctrl) {
      S.loading = false;
      $('loadingMore').hidden = true;
      showSkeleton(false);
      updateHeader();
      shell.refreshRibbon();
    }
  }
}

async function loadAlbums() {
  try {
    const data = await fetchAlbums();
    S.albums = data.items;
    S.total = data.total;
    if (shell.active === app) shell.refreshPane();
  } catch { /* the pane just stays empty */ }
}

function chooseView({ bucket = '', filter = 'all' }) {
  if (S.bucket === bucket && S.filter === filter) return;
  S.bucket = bucket;
  S.filter = filter;
  shell.refreshPane();
  load();
}

// ---------------------------------------------------------------- selection

function setSelection(set) {
  S.selected = set;
  grid.setSelected(set);
  shell.refreshRibbon();
  shell.setInfo(app.statusInfo());
}

function clearSelection() {
  S.anchor = null;
  if (grid) setSelection(new Set());
}

function selectAll() {
  if (S.items.length) selectRange(0, S.items.length - 1, false);
}

function toggleIndex(idx) {
  const id = S.items[idx].id;
  const next = new Set(S.selected);
  if (!next.delete(id)) next.add(id);
  S.anchor = idx;
  setSelection(next);
}

function selectRange(from, to, additive = true) {
  const lo = Math.max(0, Math.min(from, to));
  const hi = Math.min(S.items.length - 1, Math.max(from, to));
  const next = additive ? new Set(S.selected) : new Set();
  for (let i = lo; i <= hi; i++) next.add(S.items[i].id);
  setSelection(next);
}

function handleActivate({ idx, shift, meta, onCheck }) {
  if (idx === undefined) return;
  if (shift) {
    if (S.anchor === null) toggleIndex(idx);
    else selectRange(S.anchor, idx);
    return;
  }
  if (meta || onCheck || S.selected.size > 0) {
    toggleIndex(idx);
    return;
  }
  S.anchor = idx;
  viewer.open(idx);
}

// ---------------------------------------------------------------- download and trash

function downloadSelected() {
  const ids = [...S.selected];
  if (!ids.length) return;
  downloadIds(ids);
  toast(ids.length === 1 ? 'Downloading 1 photo…' : `Preparing a zip of ${fmtCount(ids.length)} photos…`, { timeout: 5000 });
}

async function waitForPhone(cancelToken, maxMs = 150_000) {
  const started = Date.now();
  while (Date.now() - started < maxMs) {
    if (cancelToken.cancelled) return 'cancelled';
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
  const ids = [...S.selected];
  if (!ids.length || S.trashBusy) return;

  const ok = await confirmDialog({
    title: `Move ${plural(ids.length, 'photo', 'photos')} to trash?`,
    text: "You'll be asked to confirm on your phone next. Android keeps trashed photos for 30 days, so they can be restored there.",
    okLabel: 'Move to trash',
  });
  if (!ok) return;

  S.trashBusy = true;
  try {
    try {
      await requestTrash(ids);
    } catch (err) {
      if (!(err instanceof ApiError && [401, 423].includes(err.status))) toast("Couldn't reach your phone. Nothing was moved.", { kind: 'error' });
      return;
    }

    const dlg = $('waiting');
    const cancelToken = { cancelled: false };
    $('waitingCount').textContent = plural(ids.length, 'photo', 'photos');
    $('waitingStop').onclick = () => { cancelToken.cancelled = true; };
    dlg.addEventListener('cancel', (e) => e.preventDefault(), { once: true });
    dlg.showModal();
    const result = await waitForPhone(cancelToken);
    dlg.close();

    if (result === 'done') {
      const gone = new Set(ids);
      clearSelection();
      grid.removeIds(ids, (next) => {
        S.all = S.all.filter((p) => !gone.has(p.id));
        S.items = S.query ? next : S.all;
        updateHeader();
        loadAlbums();
      });
      toast(`Moved ${plural(ids.length, 'photo', 'photos')} to trash`);
    } else if (result === 'denied') {
      toast('Nothing was moved: the request was declined on your phone.');
    } else if (result === 'timeout') {
      toast('No answer from your phone, so nothing was moved. Try again.', { kind: 'error', timeout: 7000 });
    } else {
      toast('Stopped waiting. If you confirm on the phone later, refresh to update.', { timeout: 7000 });
    }
  } finally {
    S.trashBusy = false;
  }
}

// ---------------------------------------------------------------- the app

export const app = {
  id: 'photos',
  label: 'Photos',
  icon: 'image',
  feature: 'photos',
  paneTitle: 'Albums',
  searchPlaceholder: 'Search photo names',

  mount() {
    grid = new Grid($('grid'), {
      onActivate: handleActivate,
      onSelectRange: (from, to) => selectRange(from, to - 1, true),
    }, $('contentArea'));

    viewer = new Viewer({
      getItems: () => S.items,
      onClose: (idx) => { if (S.items[idx]) grid.focusIndex(idx); },
    });
    app.viewer = viewer;

    document.addEventListener('keydown', (e) => {
      if (shell.active !== app || viewer.isOpen || $('confirm').open || $('waiting').open) return;
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName);
      if (e.key === 'Escape' && S.selected.size) {
        clearSelection();
      } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a' && !typing && S.items.length) {
        e.preventDefault();
        selectAll();
      }
    });
  },

  show() {
    if (!S.loaded) {
      loadAlbums();
      load();
    } else {
      grid.schedule();
      grid.relayout();
    }
  },

  refresh() {
    loadAlbums();
    load();
  },

  renderPane(c) {
    const item = (label, count, on, onClick, opts = {}) => {
      const b = el('button', `pitem${on ? ' on' : ''}`);
      b.type = 'button';
      const thumb = el('span', `athumb${opts.soft ? ' soft' : ''}`);
      if (opts.soft) thumb.append(svgIcon(opts.icon));
      else { thumb.style.background = colorFor(label); thumb.append(svgIcon('image')); }
      b.append(thumb, el('span', 'pname', label));
      if (count != null) b.append(el('span', 'pcount', fmtCount(count)));
      b.addEventListener('click', onClick);
      return b;
    };
    c.append(item('All photos', S.total || null, !S.bucket && S.filter === 'all', () => chooseView({}), { soft: true, icon: 'image' }));
    for (const a of S.albums) c.append(item(a.name, a.count, S.bucket === a.name, () => chooseView({ bucket: a.name })));
    c.append(el('div', 'psec', 'Smart filters'));
    c.append(item('Screenshots', null, !S.bucket && S.filter === 'screenshots', () => chooseView({ filter: 'screenshots' }), { soft: true, icon: 'filter' }));
    c.append(item('Large files (over 5 MB)', null, !S.bucket && S.filter === 'large', () => chooseView({ filter: 'large' }), { soft: true, icon: 'download' }));
  },

  commands() {
    const n = S.selected.size;
    return [
      {
        label: 'Select',
        items: [
          { type: 'btn', label: 'Select all', icon: 'checksq', disabled: !S.items.length, run: selectAll },
          { type: 'btn', label: 'Clear', icon: 'x', disabled: !n, run: clearSelection },
        ],
      },
      {
        label: n ? `${fmtCount(n)} selected` : 'Selected',
        items: [
          { type: 'btn', label: 'Download', icon: 'download', disabled: !n, run: downloadSelected },
          { type: 'btn', label: 'Move to trash', icon: 'trash', danger: true, disabled: !n, run: trashSelected },
        ],
      },
      {
        label: 'Arrange',
        items: [
          {
            type: 'select', label: 'Sort', value: S.sort,
            options: [{ value: 'newest', label: 'Newest first' }, { value: 'oldest', label: 'Oldest first' }],
            run: (v) => { S.sort = v; load(); },
          },
          {
            type: 'select', label: 'Show', value: S.filter,
            options: [{ value: 'all', label: 'All photos' }, { value: 'screenshots', label: 'Screenshots' }, { value: 'large', label: 'Large files' }],
            run: (v) => chooseView({ bucket: '', filter: v }),
          },
        ],
      },
      {
        label: 'Zoom',
        items: [
          { type: 'btn', label: 'Smaller', icon: 'zoomout', disabled: S.zoom <= 0.65, run: () => zoom(-0.2) },
          { type: 'btn', label: 'Larger', icon: 'zoomin', disabled: S.zoom >= 1.7, run: () => zoom(0.2) },
        ],
      },
    ];
  },

  search(q) {
    S.query = q;
    applyQuery();
  },

  statusInfo() {
    const n = S.items.length;
    const sel = S.selected.size;
    return `${plural(n, 'photo', 'photos')}${sel ? ` · ${fmtCount(sel)} selected` : ''}`;
  },
};

function zoom(delta) {
  S.zoom = Math.round((S.zoom + delta) * 100) / 100;
  grid.setScale(S.zoom);
  shell.refreshRibbon();
}
