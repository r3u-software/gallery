// The application shell: app bar (left), list pane, content area, ribbon (top) and status bar.
// Each app (Photos, Notifications, ...) registers itself here and supplies its list, content and commands.

import { svgIcon, el, debounce } from './ui.js';

const $ = (id) => document.getElementById(id);
const mobile = window.matchMedia('(max-width: 860px)');

/**
 * App contract (all optional except id, label, icon, paneTitle):
 *   feature        key in /api/session features; the app is disabled when it is false
 *   bottom         place the app button at the bottom of the app bar
 *   mount()        called once, before the first show
 *   show() / hide()
 *   renderPane(container)   fill the list pane
 *   commands(tab)  ribbon groups for the Home tab: [{ label, items: [item] }]
 *   viewCommands() extra groups for the View tab
 *   search(query)  filter what is shown
 *   refresh()      reload data
 *   statusInfo()   text for the right side of the status bar
 * Ribbon item: { type: 'btn', label, icon, run, disabled, danger, selected, title }
 *            | { type: 'select', label, value, options: [{ value, label }], run(value) }
 */
export const shell = {
  apps: [],
  byId: new Map(),
  active: null,
  rtab: 'home',
  features: {},
  hooks: { lock: () => {}, help: () => {} },

  register(app) {
    this.apps.push(app);
    this.byId.set(app.id, app);
  },

  enabled(app) {
    return !app.feature || this.features[app.feature] !== false;
  },

  init(features, hooks) {
    this.features = features || {};
    Object.assign(this.hooks, hooks || {});
    this.buildRail();

    $('rtabs').addEventListener('click', (e) => {
      const b = e.target.closest('.rtab');
      if (!b) return;
      this.rtab = b.dataset.rtab;
      for (const t of $('rtabs').children) t.classList.toggle('on', t === b);
      this.refreshRibbon();
    });

    $('paneBtn').addEventListener('click', () => this.togglePane());
    $('paneCollapse').addEventListener('click', () => this.togglePane(false));
    $('paneScrim').addEventListener('click', () => this.togglePane(false));

    const onSearch = debounce(() => {
      this.active?.search?.($('search').value.trim());
      this.refreshRibbon();
    }, 150);
    $('search').addEventListener('input', onSearch);

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && mobile.matches && $('body').classList.contains('pane-open')) {
        this.togglePane(false);
        return;
      }
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName) || e.target.isContentEditable;
      if (e.key === '/' && !typing && !e.ctrlKey && !e.metaKey) {
        e.preventDefault();
        $('search').focus();
      }
    });

    mobile.addEventListener('change', () => {
      $('body').classList.remove('pane-open');
      $('paneScrim').hidden = true;
    });
  },

  // ---------------------------------------------------------------- app bar

  buildRail() {
    const rail = $('rail');
    rail.replaceChildren();
    for (const app of this.apps) {
      if (app.bottom && !rail.querySelector('.spacer')) rail.append(el('div', 'spacer'));
      const b = el('button', 'rbtn');
      b.type = 'button';
      b.dataset.app = app.id;
      b.append(svgIcon(app.icon), document.createTextNode(app.label));
      const badge = el('span', 'badge');
      badge.hidden = true;
      b.append(badge);
      if (!this.enabled(app)) {
        b.disabled = true;
        b.title = 'Turned off in the phone app';
      }
      b.addEventListener('click', () => this.showApp(app.id));
      rail.append(b);
    }
  },

  setBadge(appId, count) {
    const b = $('rail').querySelector(`[data-app="${appId}"] .badge`);
    if (!b) return;
    b.hidden = !count;
    b.textContent = String(count);
  },

  firstEnabled() {
    return this.apps.find((a) => this.enabled(a));
  },

  // ---------------------------------------------------------------- navigation

  async showApp(id) {
    const app = this.byId.get(id);
    if (!app || !this.enabled(app)) return;
    if (this.active === app) {
      if (mobile.matches) this.togglePane(false);
      return;
    }
    this.active?.hide?.();
    this.active = app;
    if (!app.mounted) {
      app.mounted = true;
      app.mount?.();
    }
    for (const b of $('rail').querySelectorAll('.rbtn')) b.classList.toggle('on', b.dataset.app === id);
    for (const v of document.querySelectorAll('.view')) v.hidden = v.id !== `view-${id}`;
    $('paneTitle').textContent = app.paneTitle;
    $('search').value = '';
    $('search').placeholder = app.searchPlaceholder || 'Search';
    $('contentArea').scrollTop = 0;
    try { history.replaceState(null, '', `#${id}`); } catch { /* file:// */ }
    this.togglePane(false, true);
    await app.show?.();
    this.refreshPane();
    this.refreshRibbon();
    this.setInfo(app.statusInfo?.() || '');
  },

  refreshPane() {
    const app = this.active;
    if (!app) return;
    const body = $('paneBody');
    const top = body.scrollTop;
    body.replaceChildren();
    app.renderPane?.(body);
    body.scrollTop = top;
  },

  togglePane(force, quiet = false) {
    const body = $('body');
    if (mobile.matches) {
      const open = force === undefined ? !body.classList.contains('pane-open') : force;
      body.classList.toggle('pane-open', open);
      $('paneScrim').hidden = !open;
      $('paneBtn').setAttribute('aria-expanded', String(open));
      return;
    }
    if (quiet && force === false) return; // switching apps keeps the pane as the user left it
    const hide = force === undefined ? !body.classList.contains('nopane') : !force;
    body.classList.toggle('nopane', hide);
    this.refreshRibbon();
    window.dispatchEvent(new Event('resize'));
  },

  paneVisible() {
    return !$('body').classList.contains('nopane');
  },

  // ---------------------------------------------------------------- ribbon

  refreshRibbon() {
    const app = this.active;
    const row = $('cmdrow');
    row.replaceChildren();
    if (!app) return;
    let groups = [];
    if (this.rtab === 'home') {
      groups = app.commands?.('home') || [];
    } else if (this.rtab === 'view') {
      groups = [...(app.viewCommands?.() || []), this.layoutGroup()];
    } else {
      groups = this.toolsGroups();
    }
    for (const g of groups) row.append(this.renderGroup(g));
  },

  renderGroup(g) {
    const grp = el('div', 'grp');
    const btns = el('div', 'btns');
    grp.setAttribute('role', 'group');
    grp.setAttribute('aria-label', g.label);
    for (const item of g.items) btns.append(this.renderItem(item));
    grp.append(btns, el('div', 'glabel', g.label));
    return grp;
  },

  renderItem(item) {
    if (item.type === 'select') {
      const wrap = el('label', 'cmdsel');
      wrap.append(document.createTextNode(item.label));
      const sel = el('select');
      for (const o of item.options) {
        const opt = el('option', null, o.label);
        opt.value = o.value;
        sel.append(opt);
      }
      sel.value = item.value;
      sel.addEventListener('change', () => item.run(sel.value));
      wrap.append(sel);
      return wrap;
    }
    const b = el('button', `cmd${item.danger ? ' danger' : ''}${item.selected ? ' sel' : ''}`);
    b.type = 'button';
    b.disabled = !!item.disabled;
    if (item.title) b.title = item.title;
    if (item.selected !== undefined) b.setAttribute('aria-pressed', String(!!item.selected));
    b.append(svgIcon(item.icon), document.createTextNode(item.label));
    b.addEventListener('click', () => item.run?.());
    return b;
  },

  layoutGroup() {
    return {
      label: 'Layout',
      items: [
        { type: 'btn', label: 'List', icon: 'panel', selected: this.paneVisible(), title: 'Show or hide the list', run: () => this.togglePane() },
        {
          type: 'btn', label: 'Full screen', icon: 'zoomin', title: 'Browser full screen',
          run: () => {
            if (document.fullscreenElement) document.exitFullscreen();
            else document.documentElement.requestFullscreen?.();
          },
        },
      ],
    };
  },

  toolsGroups() {
    return [
      {
        label: 'Session',
        items: [
          { type: 'btn', label: 'Lock now', icon: 'lock', title: 'Ask for the passcode again', run: () => this.hooks.lock() },
          { type: 'btn', label: 'Connection help', icon: 'help', run: () => this.hooks.help() },
        ],
      },
      {
        label: 'Data',
        items: [{ type: 'btn', label: 'Refresh', icon: 'refresh', run: () => this.active?.refresh?.() }],
      },
    ];
  },

  // ---------------------------------------------------------------- status bar

  setInfo(text) {
    $('stInfo').textContent = text || '';
  },

  setDevice(name, batteryPercent, charging) {
    if (name) {
      $('devName').textContent = name;
      $('stDevice').textContent = name;
    }
    const b = $('stBattery');
    if (batteryPercent != null) {
      b.hidden = false;
      b.textContent = `Battery ${batteryPercent}%${charging ? ' (charging)' : ''}`;
    }
  },

  setConnected(ok) {
    $('stConn').classList.toggle('off', !ok);
    $('stConn').lastChild.textContent = ok ? 'Connected' : 'Not reachable';
    $('devpill').classList.toggle('off', !ok);
  },
};
