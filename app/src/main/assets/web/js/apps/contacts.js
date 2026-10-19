// Contacts: A to Z list in the pane, a contact card in the content area.

import { fetchContacts, ApiError } from '../api.js';
import { el, svgIcon, emptyState, initials, colorFor, copyText, plural } from '../ui.js';
import { shell } from '../shell.js';
import { askOpen } from '../openphone.js';
import { app as messages } from './messages.js';

const $ = (id) => document.getElementById(id);

const S = { items: [], permission: true, loaded: false, selected: null, query: '' };

const visible = () => {
  const q = S.query.toLowerCase();
  if (!q) return S.items;
  const digits = q.replace(/[^0-9+]/g, '');
  return S.items.filter((c) =>
    c.name.toLowerCase().includes(q) ||
    c.emails.some((m) => m.toLowerCase().includes(q)) ||
    (digits.length >= 3 && c.numbers.some((n) => n.replace(/[^0-9+]/g, '').includes(digits))));
};

async function load() {
  try {
    const data = await fetchContacts();
    S.permission = data.permission;
    S.items = data.items;
    S.loaded = true;
    $('contactsHint').textContent = data.permission ? plural(data.items.length, 'contact', 'contacts') : '';
    if (!S.selected || !S.items.some((c) => c.id === S.selected)) S.selected = S.items[0]?.id ?? null;
    if (shell.active === app) {
      shell.refreshPane();
      renderCard();
      shell.refreshRibbon();
      shell.setInfo(app.statusInfo());
    }
  } catch (err) {
    if (!(err instanceof ApiError)) return;
  }
}

const current = () => S.items.find((c) => c.id === S.selected) || null;

function renderCard() {
  const box = $('contactCard');
  box.replaceChildren();
  if (!S.permission) {
    box.append(emptyState('Contacts are off', 'On the phone, open R3U | Phone Link, then Features and passcode, switch on Contacts and allow access.'));
    return;
  }
  const c = current();
  if (!c) {
    box.append(emptyState(S.items.length ? 'Pick a contact' : 'No contacts', S.items.length ? 'Choose someone from the list.' : 'Contacts saved on the phone appear here.'));
    return;
  }
  const card = el('section', 'ccard');
  const hero = el('div', 'chero');
  const av = el('span', 'cavatar', initials(c.name));
  av.style.background = colorFor(c.name);
  const who = el('div');
  who.append(el('h2', 'cname', c.name));
  hero.append(av, who);

  const actions = el('div', 'cactions');
  const first = c.numbers[0];
  if (first) {
    const msg = el('button', 'btn');
    msg.type = 'button';
    msg.append(svgIcon('send'), document.createTextNode('Send message'));
    msg.addEventListener('click', () => messages.compose(first));
    const call = el('button', 'btn ghost');
    call.type = 'button';
    call.append(svgIcon('call'), document.createTextNode('Call on phone'));
    call.addEventListener('click', () => askOpen('dial', first, true));
    actions.append(msg, call);
  }
  hero.append(actions);
  card.append(hero);

  const row = (label, value, extras = []) => {
    const r = el('div', 'crow');
    r.append(el('span', 'k', label), el('span', 'v', value));
    for (const x of extras) r.append(x);
    return r;
  };
  const copyBtn = (text) => {
    const b = el('button', 'btn ghost', 'Copy');
    b.type = 'button';
    b.addEventListener('click', () => copyText(text));
    return b;
  };
  c.numbers.forEach((n, i) => card.append(row(i === 0 ? 'Phone' : '', n, [copyBtn(n)])));
  c.emails.forEach((m, i) => card.append(row(i === 0 ? 'Email' : '', m, [copyBtn(m)])));
  if (!c.numbers.length && !c.emails.length) card.append(row('', 'No number or email saved.'));
  box.append(card);
}

export const app = {
  id: 'contacts',
  label: 'Contacts',
  icon: 'user',
  feature: 'contacts',
  paneTitle: 'Contacts',
  searchPlaceholder: 'Search name or number',

  async show() {
    renderCard();
    await load();
  },

  refresh: load,

  renderPane(c) {
    if (!S.permission) {
      c.append(el('div', 'pane-note', 'Contacts permission is off.'));
      return;
    }
    const list = visible();
    if (!list.length) {
      c.append(el('div', 'pane-note', S.query ? 'No contact matches.' : 'No contacts yet.'));
      return;
    }
    let letter = '';
    for (const ct of list) {
      const l = (ct.name[0] || '#').toUpperCase();
      const key = /[A-Z]/.test(l) ? l : '#';
      if (key !== letter) { letter = key; c.append(el('div', 'letter', key)); }
      const row = el('button', `trow${S.selected === ct.id ? ' on' : ''}`);
      row.type = 'button';
      const av = el('span', 'av', initials(ct.name));
      av.style.background = colorFor(ct.name);
      const meta = el('div', 'tmeta');
      meta.append(el('div', 'tname', ''), el('div', 'tsnip', ct.numbers[0] || ct.emails[0] || ''));
      meta.firstChild.append(el('span', 'nm', ct.name));
      row.append(av, meta);
      row.addEventListener('click', () => { S.selected = ct.id; shell.refreshPane(); renderCard(); shell.refreshRibbon(); shell.togglePane(false); });
      c.append(row);
    }
  },

  commands() {
    const c = current();
    const number = c?.numbers[0];
    return [
      {
        label: 'Contact',
        items: [
          { type: 'btn', label: 'Send message', icon: 'send', disabled: !number, run: () => messages.compose(number) },
          { type: 'btn', label: 'Call on phone', icon: 'call', disabled: !number, run: () => askOpen('dial', number, true) },
          { type: 'btn', label: 'Copy number', icon: 'copy', disabled: !number, run: () => copyText(number) },
        ],
      },
      { label: 'Data', items: [{ type: 'btn', label: 'Refresh', icon: 'refresh', run: load }] },
    ];
  },

  search(q) {
    S.query = q;
    shell.refreshPane();
  },

  statusInfo() {
    return S.permission ? plural(visible().length, 'contact', 'contacts') : 'Contacts access is off';
  },
};
