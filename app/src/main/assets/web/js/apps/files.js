// Files and clipboard: send files to the phone, get files shared from the phone, move text both ways.

import { getJSON, sendJSON, ApiError } from '../api.js';
import { toast, plural, el, svgIcon, timeAgo, formatBytes, emptyState, copyText, fmtCount } from '../ui.js';
import { shell } from '../shell.js';

const $ = (id) => document.getElementById(id);

const S = { files: [], clip: null, mode: 'files', query: '', timer: 0 };

const shown = () => {
  const q = S.query.toLowerCase();
  return q ? S.files.filter((f) => f.name.toLowerCase().includes(q)) : S.files;
};

async function load() {
  try {
    const [list, clip] = await Promise.all([getJSON('/api/files'), getJSON('/api/clipboard')]);
    S.files = list.items;
    S.clip = clip;
    renderFiles();
    renderClip();
    if (shell.active === app) {
      shell.refreshPane();
      shell.setInfo(app.statusInfo());
    }
  } catch (err) {
    if (!(err instanceof ApiError)) return;
  }
}

function renderFiles() {
  const box = $('fileList');
  box.replaceChildren();
  const items = shown();
  if (!items.length) {
    box.append(emptyState(S.query ? 'No matches' : 'No files yet', S.query ? 'No file name matches your search.' : 'Files you send, or share from the phone, show up here.'));
    return;
  }
  for (const f of items) {
    const row = el('div', 'file');
    const info = el('div', 'file-info');
    info.append(el('strong', null, f.name), el('span', 'muted', `${formatBytes(f.size)} · ${timeAgo(f.modified)}`));
    const dl = el('a', 'btn', 'Download');
    dl.href = `/api/files/${f.id}/download`;
    dl.setAttribute('download', f.name);
    const del = el('button', 'btn ghost', 'Delete');
    del.type = 'button';
    del.addEventListener('click', async () => {
      if (del.dataset.armed !== '1') {
        del.dataset.armed = '1';
        del.textContent = 'Confirm delete';
        setTimeout(() => { del.dataset.armed = ''; del.textContent = 'Delete'; }, 3000);
        return;
      }
      try { await sendJSON(`/api/files/${f.id}`, {}, 'DELETE'); } catch { /* gone already */ }
      load();
    });
    row.append(info, dl, del);
    box.append(row);
  }
}

function renderClip() {
  const p = $('clipFromPhone');
  const copy = $('clipCopy');
  const text = S.clip?.text;
  if (text) {
    p.textContent = text;
    copy.hidden = false;
    copy.onclick = () => copyText(text);
  } else {
    p.textContent = 'Nothing shared yet. Share text to R3U | Phone Link on the phone.';
    copy.hidden = true;
  }
}

function setMode(mode) {
  S.mode = mode;
  $('filesBox').hidden = mode !== 'files';
  $('clipBox').hidden = mode !== 'clip';
  $('filesTitle').textContent = mode === 'files' ? 'Files' : 'Clipboard';
  shell.refreshPane();
  shell.refreshRibbon();
}

function upload(fileList) {
  const bar = $('uploadBar');
  const wrap = $('uploadProgress');
  const form = new FormData();
  for (const f of fileList) form.append('file', f, f.name);
  wrap.hidden = false;
  bar.style.setProperty('--p', '0');
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/files/upload');
  xhr.upload.onprogress = (e) => { if (e.lengthComputable) bar.style.setProperty('--p', String(e.loaded / e.total)); };
  xhr.onload = () => {
    wrap.hidden = true;
    if (xhr.status === 200) { toast(`Sent ${plural(fileList.length, 'file', 'files')} to the phone`); load(); }
    else toast(xhr.status === 423 ? 'Enter the passcode first' : "Couldn't send the files", { kind: 'error' });
  };
  xhr.onerror = () => { wrap.hidden = true; toast("Couldn't reach the phone", { kind: 'error' }); };
  xhr.send(form);
}

export const app = {
  id: 'files',
  label: 'Files',
  icon: 'folder',
  feature: 'files',
  paneTitle: 'Files',
  searchPlaceholder: 'Search file names',

  mount() {
    const drop = $('drop');
    const input = $('fileInput');
    input.addEventListener('change', () => { if (input.files.length) upload([...input.files]); input.value = ''; });
    for (const ev of ['dragenter', 'dragover']) drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add('over'); });
    for (const ev of ['dragleave', 'drop']) drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove('over'); });
    drop.addEventListener('drop', (e) => { if (e.dataTransfer?.files?.length) upload([...e.dataTransfer.files]); });
    $('clipSend').addEventListener('click', async () => {
      const text = $('clipToPhone').value;
      if (!text.trim()) return;
      try { await sendJSON('/api/clipboard', { text }); toast('Copied to the phone'); } catch (err) { if (err instanceof ApiError && ![401, 423].includes(err.status)) toast("Couldn't send", { kind: 'error' }); }
    });
  },

  async show() {
    setMode(S.mode);
    await load();
    S.timer = setInterval(load, 8000);
  },

  hide() { clearInterval(S.timer); },

  refresh: load,

  renderPane(c) {
    const item = (label, icon, count, mode) => {
      const b = el('button', `pitem${S.mode === mode ? ' on' : ''}`);
      b.type = 'button';
      const t = el('span', 'athumb soft');
      t.append(svgIcon(icon));
      b.append(t, el('span', 'pname', label));
      if (count != null) b.append(el('span', 'pcount', fmtCount(count)));
      b.addEventListener('click', () => { setMode(mode); shell.togglePane(false); });
      return b;
    };
    c.append(item('Files', 'folder', S.files.length, 'files'), item('Clipboard', 'copy', null, 'clip'));
    c.append(el('div', 'pane-note', 'Drag files onto the page to put them on the phone. Share a file or text to R3U | Phone Link on the phone to get it here.'));
  },

  commands() {
    return [
      {
        label: 'Send',
        items: [{ type: 'btn', label: 'Upload files', icon: 'upload', disabled: S.mode !== 'files', run: () => $('fileInput').click() }],
      },
      { label: 'Data', items: [{ type: 'btn', label: 'Refresh', icon: 'refresh', run: load }] },
    ];
  },

  search(q) {
    S.query = q;
    renderFiles();
  },

  statusInfo() {
    return S.mode === 'files' ? plural(shown().length, 'file', 'files') : 'Clipboard';
  },
};
