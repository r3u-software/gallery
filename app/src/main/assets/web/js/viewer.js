// Full-screen photo viewer: zoom (50-400%), pan, swipe, keyboard, wheel/pinch, neighbour preloading.

import { fullUrl, thumbUrl, downloadUrl } from './api.js';

const MIN = 0.5;
const MAX = 4;
const STEP = 1.25;
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

export class Viewer {
  /**
   * @param {{ getItems(): object[], onClose(idx:number): void, onIndexChange?(idx:number): void }} hooks
   */
  constructor(hooks) {
    this.hooks = hooks;
    this.root = document.getElementById('viewer');
    this.stage = document.getElementById('vStage');
    this.img = document.getElementById('vImg');
    this.spinner = document.getElementById('vSpinner');
    this.nameEl = document.getElementById('vName');
    this.posEl = document.getElementById('vPos');
    this.zoomEl = document.getElementById('vZoom');
    this.prevBtn = document.getElementById('vPrev');
    this.nextBtn = document.getElementById('vNext');
    this.dlLink = document.getElementById('vDownload');

    this.idx = 0;
    this.s = 1;
    this.tx = 0;
    this.ty = 0;
    this.isOpen = false;
    this.loadToken = 0;
    this.pointers = new Map();
    this.gesture = null;

    this.bind();
  }

  get items() { return this.hooks.getItems(); }

  bind() {
    document.getElementById('vClose').addEventListener('click', () => this.close());
    document.getElementById('vZoomIn').addEventListener('click', () => this.zoomBy(STEP));
    document.getElementById('vZoomOut').addEventListener('click', () => this.zoomBy(1 / STEP));
    document.getElementById('vFit').addEventListener('click', () => this.fit());
    this.prevBtn.addEventListener('click', () => this.go(-1));
    this.nextBtn.addEventListener('click', () => this.go(1));
    this.img.addEventListener('dblclick', (e) => this.toggleZoom(e));

    this.stage.addEventListener('wheel', (e) => {
      e.preventDefault();
      const factor = Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0018));
      this.zoomAt(this.s * factor, e.clientX, e.clientY);
    }, { passive: false });

    this.stage.addEventListener('pointerdown', (e) => this.onPointerDown(e));
    this.stage.addEventListener('pointermove', (e) => this.onPointerMove(e));
    const end = (e) => this.onPointerUp(e);
    this.stage.addEventListener('pointerup', end);
    this.stage.addEventListener('pointercancel', end);

    // Clicking the dark backdrop (not the image) closes, like most lightboxes.
    this.stage.addEventListener('click', (e) => {
      if (this.suppressClick) { this.suppressClick = false; return; }
      if (e.target.closest('button, a')) return;
      if (!this.hitsPicture(e.clientX, e.clientY)) this.close();
    });

    document.addEventListener('keydown', (e) => this.onKey(e));
    window.addEventListener('resize', () => this.isOpen && this.apply());
  }

  // ------------------------------------------------------------ open / close / navigate

  open(idx) {
    if (!this.items.length) return;
    this.isOpen = true;
    this.root.hidden = false;
    document.body.classList.add('no-scroll');
    this.show(idx);
    document.getElementById('vClose').focus();
  }

  close() {
    if (!this.isOpen) return;
    this.isOpen = false;
    this.loadToken++;
    this.root.hidden = true;
    this.img.removeAttribute('src');
    document.body.classList.remove('no-scroll');
    this.pointers.clear();
    this.hooks.onClose(this.idx);
  }

  go(delta) {
    const next = this.idx + delta;
    if (next < 0 || next >= this.items.length) return;
    this.show(next);
  }

  show(idx) {
    const items = this.items;
    this.idx = clamp(idx, 0, items.length - 1);
    const item = items[this.idx];
    const token = ++this.loadToken;

    this.nameEl.textContent = item.name;
    this.posEl.textContent = `${(this.idx + 1).toLocaleString()} of ${items.length.toLocaleString()}`;
    this.dlLink.href = downloadUrl(item.id);
    this.dlLink.setAttribute('download', item.name);
    this.prevBtn.disabled = this.idx === 0;
    this.nextBtn.disabled = this.idx === items.length - 1;

    this.resetTransform();
    this.img.alt = item.name;

    // Show the (already cached) thumbnail instantly, then swap in the full image.
    this.img.classList.add('placeholder');
    this.img.src = thumbUrl(item.id);
    this.spinner.classList.add('on');

    const full = new Image();
    full.decoding = 'async';
    full.onload = () => {
      if (token !== this.loadToken) return;
      this.img.src = full.src;
      this.img.classList.remove('placeholder');
      this.spinner.classList.remove('on');
      this.apply();
    };
    full.onerror = () => {
      if (token !== this.loadToken) return;
      this.spinner.classList.remove('on');
    };
    full.src = fullUrl(item.id);

    this.preloadNeighbours();
    this.hooks.onIndexChange?.(this.idx);
  }

  preloadNeighbours() {
    const items = this.items;
    for (const d of [1, -1, 2]) {
      const n = items[this.idx + d];
      if (n) new Image().src = fullUrl(n.id);
    }
  }

  hitsPicture(clientX, clientY) {
    const r = this.stage.getBoundingClientRect();
    const base = this.fitSize();
    const cx = r.left + r.width / 2 + this.tx;
    const cy = r.top + r.height / 2 + this.ty;
    return Math.abs(clientX - cx) <= (base.w * this.s) / 2 && Math.abs(clientY - cy) <= (base.h * this.s) / 2;
  }

  // ------------------------------------------------------------ zoom / pan

  resetTransform() {
    this.s = 1;
    this.tx = 0;
    this.ty = 0;
    this.apply();
  }

  fit() { this.resetTransform(); }

  zoomBy(factor) {
    const r = this.stage.getBoundingClientRect();
    this.zoomAt(this.s * factor, r.left + r.width / 2, r.top + r.height / 2);
  }

  /** Zoom keeping the point under (clientX, clientY) fixed. */
  zoomAt(next, clientX, clientY) {
    next = clamp(next, MIN, MAX);
    const r = this.stage.getBoundingClientRect();
    const px = clientX - (r.left + r.width / 2);
    const py = clientY - (r.top + r.height / 2);
    const ratio = next / this.s;
    this.tx = px - (px - this.tx) * ratio;
    this.ty = py - (py - this.ty) * ratio;
    this.s = next;
    this.apply();
  }

  toggleZoom(e) {
    if (this.s > 1.01) this.fit();
    else this.zoomAt(2.5, e.clientX, e.clientY);
  }

  /** On-screen size of the picture at scale 1 (the img element fills the stage; the picture is contained in it). */
  fitSize() {
    const nw = this.img.naturalWidth || 1;
    const nh = this.img.naturalHeight || 1;
    const k = Math.min(this.stage.clientWidth / nw, this.stage.clientHeight / nh);
    return { w: nw * k, h: nh * k };
  }

  clampPan() {
    const base = this.fitSize();
    const w = base.w * this.s;
    const h = base.h * this.s;
    const maxX = Math.max(0, (w - this.stage.clientWidth) / 2);
    const maxY = Math.max(0, (h - this.stage.clientHeight) / 2);
    this.tx = clamp(this.tx, -maxX, maxX);
    this.ty = clamp(this.ty, -maxY, maxY);
  }

  apply(extraX = 0) {
    this.clampPan();
    this.img.style.transform = `translate(${this.tx + extraX}px, ${this.ty}px) scale(${this.s})`;
    this.zoomEl.textContent = `${Math.round(this.s * 100)}%`;
    this.stage.classList.toggle('zoomed', this.s > 1.01);
  }

  // ------------------------------------------------------------ pointer gestures (pan, pinch, swipe)

  onPointerDown(e) {
    if (e.target.closest('button, a')) return;
    this.stage.setPointerCapture(e.pointerId);
    this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    this.stage.classList.add('dragging');
    if (this.pointers.size === 1) {
      this.gesture = { type: 'drag', startX: e.clientX, startY: e.clientY, tx: this.tx, ty: this.ty, moved: false };
    } else if (this.pointers.size === 2) {
      const [a, b] = [...this.pointers.values()];
      this.gesture = { type: 'pinch', dist: Math.hypot(a.x - b.x, a.y - b.y) || 1 };
    }
  }

  onPointerMove(e) {
    const p = this.pointers.get(e.pointerId);
    if (!p || !this.gesture) return;
    p.x = e.clientX;
    p.y = e.clientY;

    if (this.gesture.type === 'pinch' && this.pointers.size >= 2) {
      const [a, b] = [...this.pointers.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y) || 1;
      this.zoomAt(this.s * (dist / this.gesture.dist), (a.x + b.x) / 2, (a.y + b.y) / 2);
      this.gesture.dist = dist;
      this.suppressClick = true;
      return;
    }
    if (this.gesture.type !== 'drag') return;

    const dx = e.clientX - this.gesture.startX;
    const dy = e.clientY - this.gesture.startY;
    if (Math.abs(dx) + Math.abs(dy) > 6) this.gesture.moved = true;

    if (this.s > 1.01) {
      this.tx = this.gesture.tx + dx;
      this.ty = this.gesture.ty + dy;
      this.apply();
    } else {
      // Not zoomed: follow the finger horizontally; release decides whether it was a swipe.
      this.apply(dx * 0.6);
    }
  }

  onPointerUp(e) {
    const g = this.gesture;
    this.pointers.delete(e.pointerId);
    if (this.pointers.size > 0) {
      // Pinch ended with one finger still down: continue as a drag from here.
      const rest = [...this.pointers.values()][0];
      this.gesture = { type: 'drag', startX: rest.x, startY: rest.y, tx: this.tx, ty: this.ty, moved: true };
      return;
    }
    this.stage.classList.remove('dragging');
    this.gesture = null;
    if (!g || g.type !== 'drag') return;
    if (g.moved) this.suppressClick = true;

    if (this.s <= 1.01) {
      const dx = e.clientX - g.startX;
      const dy = e.clientY - g.startY;
      if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.4) {
        this.apply();
        this.go(dx < 0 ? 1 : -1);
        return;
      }
      this.apply();
    }
  }

  // ------------------------------------------------------------ keyboard

  onKey(e) {
    if (!this.isOpen) return;
    switch (e.key) {
      case 'Escape': e.preventDefault(); e.stopPropagation(); this.close(); return;
      case 'ArrowLeft': e.preventDefault(); this.go(-1); return;
      case 'ArrowRight': e.preventDefault(); this.go(1); return;
      case '+': case '=': e.preventDefault(); this.zoomBy(STEP); return;
      case '-': case '_': e.preventDefault(); this.zoomBy(1 / STEP); return;
      case '0': e.preventDefault(); this.fit(); return;
      case 'Tab': this.trapFocus(e); return;
      default:
    }
  }

  trapFocus(e) {
    const focusable = [...this.root.querySelectorAll('button:not([disabled]), a[href]')];
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    else if (!this.root.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
  }
}
