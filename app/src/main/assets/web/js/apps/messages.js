// Messages: conversations in the list pane, the open conversation in the content area.

import { getJSON, sendJSON, ApiError } from '../api.js';
import { toast, plural, el, svgIcon, timeAgo, emptyState, colorFor, initials } from '../ui.js';
import { shell } from '../shell.js';

const $ = (id) => document.getElementById(id);

const S = { threads: [], permission: true, current: null, query: '', timer: 0 };

const visibleThreads = () => {
  const q = S.query.toLowerCase();
  return q ? S.threads.filter((t) => `${t.name} ${t.address} ${t.snippet}`.toLowerCase().includes(q)) : S.threads;
};

async function loadThreads() {
  try {
    const data = await getJSON('/api/sms/threads');
    S.permission = data.permission;
    S.threads = data.threads;
    $('msgHint').textContent = data.permission
      ? `${plural(data.threads.length, 'conversation', 'conversations')}, ${data.threads.reduce((n, t) => n + t.unread, 0)} unread`
      : '';
    if (shell.active === app) {
      shell.refreshPane();
      shell.setInfo(app.statusInfo());
    }
  } catch (err) {
    if (!(err instanceof ApiError)) return;
  }
}

function bubble(text, outgoing, date) {
  const b = el('div', `bubble ${outgoing ? 'out' : 'in'}`);
  b.append(el('p', null, text), el('time', null, new Date(date).toLocaleString(undefined, { hour: 'numeric', minute: '2-digit', day: 'numeric', month: 'short' })));
  return b;
}

async function open(thread) {
  S.current = thread;
  $('convoEmpty').hidden = true;
  const head = $('convoHead');
  head.hidden = false;
  head.replaceChildren();
  const av = el('span', 'av', initials(thread.name || thread.address));
  av.style.background = colorFor(thread.address);
  const who = el('div');
  who.append(document.createTextNode(thread.name || thread.address));
  if (thread.name && thread.name !== thread.address) who.append(el('span', 'sub', thread.address));
  head.append(av, who);
  $('bubbles').hidden = false;
  $('composer').hidden = false;
  shell.refreshPane();
  await loadMessages(false);
  $('composeText').focus();
}

async function loadMessages(keepScroll) {
  if (!S.current) return;
  try {
    const data = await getJSON(`/api/sms/threads/${S.current.id}?limit=200`);
    const box = $('bubbles');
    const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
    box.replaceChildren();
    for (const m of data.messages) box.append(bubble(m.body, m.outgoing, m.date));
    if (!keepScroll || nearBottom) box.scrollTop = box.scrollHeight;
  } catch { /* handled globally */ }
}

function showNewMessage(prefillTo = '') {
  const dlg = $('newMsgDialog');
  $('newMsgTo').value = prefillTo;
  $('newMsgBody').value = '';
  $('newMsgError').hidden = true;
  dlg.showModal();
  (prefillTo ? $('newMsgBody') : $('newMsgTo')).focus();
}

async function submitNewMessage(e) {
  e.preventDefault();
  const to = $('newMsgTo').value.trim();
  const body = $('newMsgBody').value.trim();
  const err = $('newMsgError');
  if (!/^[+0-9 ()-]{3,24}$/.test(to)) { err.textContent = 'Enter a valid phone number.'; err.hidden = false; return; }
  if (!body) { err.textContent = 'Type a message first.'; err.hidden = false; return; }
  $('newMsgSend').disabled = true;
  try {
    await sendJSON('/api/sms/send', { to, body });
    $('newMsgDialog').close();
    toast(`Text sent to ${to}`);
    setTimeout(loadThreads, 1500);
  } catch (error) {
    err.textContent = "Couldn't send the text. Check that SMS access is on in the phone app.";
    err.hidden = false;
    if (error instanceof ApiError && [401, 423].includes(error.status)) $('newMsgDialog').close();
  } finally {
    $('newMsgSend').disabled = false;
  }
}

export const app = {
  id: 'messages',
  label: 'Messages',
  icon: 'message',
  feature: 'messages',
  paneTitle: 'Conversations',
  searchPlaceholder: 'Search conversations',

  /** Used by Contacts: open the new-message dialog with a number filled in. */
  async compose(number) {
    await shell.showApp('messages');
    showNewMessage(number);
  },

  mount() {
    $('composer').addEventListener('submit', async (e) => {
      e.preventDefault();
      const input = $('composeText');
      const body = input.value.trim();
      if (!body || !S.current) return;
      const btn = $('composeSend');
      btn.disabled = true;
      try {
        await sendJSON('/api/sms/send', { to: S.current.address, body });
        input.value = '';
        // Android only logs sent texts for the default SMS app, so show it here directly.
        $('bubbles').append(bubble(body, true, Date.now()));
        $('bubbles').scrollTop = $('bubbles').scrollHeight;
      } catch (err) {
        if (err instanceof ApiError && ![401, 423].includes(err.status)) toast("Couldn't send the text", { kind: 'error' });
      } finally {
        btn.disabled = false;
      }
    });
    $('composeText').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('composer').requestSubmit(); }
    });
    $('newMsgForm').addEventListener('submit', submitNewMessage);
    $('newMsgCancel').addEventListener('click', () => $('newMsgDialog').close());
  },

  async show() {
    await loadThreads();
    S.timer = setInterval(() => { loadThreads(); if (S.current) loadMessages(true); }, 6000);
  },

  hide() {
    clearInterval(S.timer);
  },

  refresh() {
    loadThreads();
    loadMessages(true);
  },

  renderPane(c) {
    if (!S.permission) {
      c.append(emptyState('Messages are off', 'On the phone, open R3U | Phone Link, then Features and passcode, switch on Messages and allow SMS access.'));
      return;
    }
    const list = visibleThreads();
    if (!list.length) {
      c.append(el('div', 'pane-note', S.query ? 'No conversation matches.' : 'No conversations yet.'));
      return;
    }
    for (const t of list) {
      const row = el('button', `trow${S.current && S.current.id === t.id ? ' on' : ''}`);
      row.type = 'button';
      const av = el('span', 'av', initials(t.name || t.address));
      av.style.background = colorFor(t.address);
      const meta = el('div', 'tmeta');
      const name = el('div', 'tname');
      name.append(el('span', 'nm', t.name || t.address), el('span', 'when', timeAgo(t.date)));
      meta.append(name, el('div', 'tsnip', t.snippet));
      row.append(av, meta);
      if (t.unread) row.append(el('span', 'unread', String(t.unread)));
      row.addEventListener('click', () => { open(t); shell.togglePane(false); });
      c.append(row);
    }
  },

  commands() {
    return [
      {
        label: 'Compose',
        items: [{ type: 'btn', label: 'New message', icon: 'plus', disabled: !S.permission, run: () => showNewMessage() }],
      },
      { label: 'Data', items: [{ type: 'btn', label: 'Refresh', icon: 'refresh', run: () => app.refresh() }] },
    ];
  },

  search(q) {
    S.query = q;
    shell.refreshPane();
  },

  statusInfo() {
    return S.permission ? plural(S.threads.length, 'conversation', 'conversations') : 'SMS access is off';
  },
};
