// Virtualized photo grid: lays out every photo, but only keeps DOM nodes for what is near the viewport.
// That keeps 10,000+ photos smooth, and browsers only fetch thumbnails for tiles that exist.

import { buildLayout } from './layout.js';
import { thumbUrl } from './api.js';

const CHECK_SVG =
  '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3.5 8.5l3 3 6-7"/></svg>';

export class Grid {
  /**
   * @param {HTMLElement} root
   * @param {{ onActivate(ev): void, onSelectRange(from:number,to:number): void, getTopInset(): number }} handlers
   */
  constructor(root, handlers) {
    this.root = root;
    this.h = handlers;
    this.items = [];
    this.indexById = new Map();
    this.layout = { blocks: [], tiles: [], height: 0 };
    this.width = 0;
    this.nodes = new Map();
    this.selected = new Set();
    this.focusId = null;
    this.raf = 0;

    window.addEventListener('scroll', () => this.schedule(), { passive: true });
    new ResizeObserver(() => {
      const w = this.root.clientWidth;
      if (w !== this.width) this.relayout();
      else this.schedule();
    }).observe(this.root);

    root.addEventListener('click', (e) => this.onClick(e));
    root.addEventListener('keydown', (e) => this.onKey(e));
    root.addEventListener('focusin', (e) => {
      const tile = e.target.closest?.('.tile');
      if (tile) this.focusId = Number(tile.dataset.id);
    });
  }

  // ------------------------------------------------------------ data

  setItems(items) {
    this.items = items;
    this.indexById = new Map(items.map((it, i) => [it.id, i]));
    this.relayout();
  }

  setSelected(set) {
    this.selected = set;
    this.root.classList.toggle('selecting', set.size > 0);
    for (const [key, node] of this.nodes) {
      if (key.startsWith('t')) this.paintSelection(node);
    }
  }

  paintSelection(node) {
    const on = this.selected.has(Number(node.dataset.id));
    node.classList.toggle('selected', on);
    node.setAttribute('aria-selected', String(on));
    node.querySelector('.check')?.setAttribute('aria-pressed', String(on));
  }

  /** Fades out the given photos, then reflows the grid around the gap. */
  removeIds(ids, done) {
    const gone = new Set(ids);
    for (const id of gone) this.nodes.get(`t${id}`)?.classList.add('removing');
    setTimeout(() => {
      this.root.classList.add('animate');
      const next = this.items.filter((it) => !gone.has(it.id));
      this.setItems(next);
      done?.(next);
      setTimeout(() => this.root.classList.remove('animate'), 400);
    }, 230);
  }

  // ------------------------------------------------------------ layout + render

  relayout() {
    this.width = this.root.clientWidth;
    const targetH = this.width < 560 ? 130 : this.width < 1100 ? 190 : 230;
    this.layout = buildLayout(this.items, this.width, { gap: 4, targetH });
    this.root.style.height = `${this.layout.height}px`;
    // Existing nodes are kept and repositioned by render(); stale ones are dropped there.
    this.render();
  }

  schedule() {
    if (this.raf) return;
    this.raf = requestAnimationFrame(() => {
      this.raf = 0;
      this.render();
    });
  }

  render() {
    const { blocks } = this.layout;
    const rootTop = this.root.getBoundingClientRect().top + window.scrollY;
    const viewTop = window.scrollY - rootTop;
    const lo = viewTop - window.innerHeight;
    const hi = viewTop + window.innerHeight * 2;

    // First block that ends below `lo` (blocks are sorted by y).
    let a = 0;
    let b = blocks.length;
    while (a < b) {
      const mid = (a + b) >> 1;
      if (blocks[mid].y + blocks[mid].h < lo) a = mid + 1;
      else b = mid;
    }

    const wanted = new Set();
    const frag = document.createDocumentFragment();
    for (let i = a; i < blocks.length && blocks[i].y <= hi; i++) {
      const blk = blocks[i];
      const key = blk.kind === 'h' ? `h${blk.from}` : `t${this.items[blk.idx].id}`;
      wanted.add(key);
      let node = this.nodes.get(key);
      if (!node) {
        node = blk.kind === 'h' ? this.makeHeader(blk) : this.makeTile(blk);
        this.nodes.set(key, node);
        frag.appendChild(node);
      }
      this.place(node, blk);
    }
    for (const [key, node] of this.nodes) {
      if (!wanted.has(key) && !(node === document.activeElement)) {
        const img = node.querySelector?.('img');
        if (img) img.removeAttribute('src'); // cancels a pending thumbnail request
        node.remove();
        this.nodes.delete(key);
      }
    }
    this.root.appendChild(frag);
    this.fixTabStop();
  }

  place(node, blk) {
    const s = node.style;
    s.top = `${blk.y}px`;
    if (blk.kind === 't') {
      s.left = `${blk.x}px`;
      s.width = `${blk.w}px`;
      s.height = `${blk.h}px`;
    } else {
      s.height = `${blk.h}px`;
      this.updateHeader(node, blk);
    }
  }

  updateHeader(node, blk) {
    const h = node.firstChild;
    const label = h.firstChild;
    if (label.nodeValue !== blk.label) label.nodeValue = blk.label;
    const meta = `${blk.count.toLocaleString()} ${blk.count === 1 ? 'photo' : 'photos'}`;
    if (h.lastChild.textContent !== meta) h.lastChild.textContent = meta;
    const btn = node.lastChild;
    btn.dataset.from = blk.from;
    btn.dataset.to = blk.to;
    btn.setAttribute('aria-label', `Select all photos from ${blk.label}`);
  }

  makeHeader(blk) {
    const el = document.createElement('div');
    el.className = 'group-head';
    el.setAttribute('role', 'presentation');
    const h = document.createElement('h2');
    h.append(document.createTextNode(blk.label));
    const meta = document.createElement('span');
    meta.className = 'group-meta';
    meta.textContent = `${blk.count.toLocaleString()} ${blk.count === 1 ? 'photo' : 'photos'}`;
    h.appendChild(meta);
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'group-select';
    btn.dataset.from = blk.from;
    btn.dataset.to = blk.to;
    btn.textContent = 'Select all';
    btn.setAttribute('aria-label', `Select all photos from ${blk.label}`);
    el.append(h, btn);
    return el;
  }

  makeTile(blk) {
    const item = this.items[blk.idx];
    const el = document.createElement('div');
    el.className = 'tile loading';
    el.dataset.id = item.id;
    el.setAttribute('role', 'gridcell');
    el.setAttribute('aria-label', item.name);
    el.tabIndex = -1;

    const img = new Image();
    img.alt = '';
    img.decoding = 'async';
    img.loading = 'lazy';
    img.draggable = false;
    img.addEventListener('load', () => el.classList.remove('loading'));
    img.addEventListener('error', () => {
      el.classList.remove('loading');
      el.classList.add('error');
    });
    img.src = thumbUrl(item.id);

    const chk = document.createElement('button');
    chk.type = 'button';
    chk.className = 'check';
    chk.tabIndex = -1;
    chk.setAttribute('aria-label', `Select ${item.name}`);
    chk.setAttribute('aria-pressed', 'false');
    chk.innerHTML = CHECK_SVG;

    el.append(img, chk);
    this.paintSelection(el);
    return el;
  }

  /** Roving tabindex: exactly one tile is a Tab stop. */
  fixTabStop() {
    let stop = null;
    for (const [key, node] of this.nodes) {
      if (!key.startsWith('t')) continue;
      const isFocus = Number(node.dataset.id) === this.focusId;
      node.tabIndex = isFocus ? 0 : -1;
      if (isFocus) stop = node;
    }
    if (!stop) {
      for (const [key, node] of this.nodes) {
        if (key.startsWith('t')) {
          node.tabIndex = 0;
          break;
        }
      }
    }
  }

  // ------------------------------------------------------------ interaction

  onClick(e) {
    const groupBtn = e.target.closest('.group-select');
    if (groupBtn) {
      this.h.onSelectRange(Number(groupBtn.dataset.from), Number(groupBtn.dataset.to));
      return;
    }
    const tile = e.target.closest('.tile');
    if (!tile) return;
    const id = Number(tile.dataset.id);
    this.focusId = id;
    this.h.onActivate({
      idx: this.indexById.get(id),
      id,
      shift: e.shiftKey,
      meta: e.ctrlKey || e.metaKey,
      onCheck: !!e.target.closest('.check'),
    });
  }

  onKey(e) {
    const tile = e.target.closest?.('.tile');
    if (!tile) return;
    const id = Number(tile.dataset.id);
    const idx = this.indexById.get(id);
    if (idx === undefined) return;

    let target = null;
    switch (e.key) {
      case 'Enter':
        e.preventDefault();
        this.h.onActivate({ idx, id, shift: e.shiftKey, meta: false, onCheck: false });
        return;
      case ' ':
        e.preventDefault();
        this.h.onActivate({ idx, id, shift: e.shiftKey, meta: false, onCheck: true });
        return;
      case 'ArrowRight': target = idx + 1; break;
      case 'ArrowLeft': target = idx - 1; break;
      case 'ArrowDown': target = this.neighborInRow(idx, 1); break;
      case 'ArrowUp': target = this.neighborInRow(idx, -1); break;
      case 'Home': target = 0; break;
      case 'End': target = this.items.length - 1; break;
      default: return;
    }
    e.preventDefault();
    if (target !== null && target >= 0 && target < this.items.length) {
      this.focusIndex(target);
      if (e.shiftKey) this.h.onActivate({ idx: target, id: this.items[target].id, shift: true, meta: false, onCheck: true });
    }
  }

  neighborInRow(idx, dir) {
    const tiles = this.layout.tiles;
    const cur = tiles[idx];
    let k = idx;
    while (k >= 0 && k < tiles.length && Math.abs(tiles[k].y - cur.y) < 0.5) k += dir;
    if (k < 0 || k >= tiles.length) return null;
    const rowY = tiles[k].y;
    const cx = cur.x + cur.w / 2;
    let best = k;
    let bestD = Infinity;
    for (let m = k; m >= 0 && m < tiles.length && Math.abs(tiles[m].y - rowY) < 0.5; m += dir) {
      const d = Math.abs(tiles[m].x + tiles[m].w / 2 - cx);
      if (d < bestD) { bestD = d; best = m; }
    }
    return best;
  }

  /** Scroll a tile into view (below the sticky bar), render it, and focus it. */
  focusIndex(idx) {
    this.scrollToIndex(idx);
    this.focusId = this.items[idx].id;
    this.render();
    this.nodes.get(`t${this.focusId}`)?.focus({ preventScroll: true });
  }

  scrollToIndex(idx, center = false) {
    const t = this.layout.tiles[idx];
    if (!t) return;
    const rootTop = this.root.getBoundingClientRect().top + window.scrollY;
    const top = rootTop + t.y;
    const inset = this.h.getTopInset() + 12;
    if (center) {
      window.scrollTo({ top: top - (window.innerHeight - t.h) / 2 });
    } else if (top < window.scrollY + inset) {
      window.scrollTo({ top: top - inset });
    } else if (top + t.h > window.scrollY + window.innerHeight - 12) {
      window.scrollTo({ top: top + t.h - window.innerHeight + 24 });
    }
  }
}
