// Thin client for the phone's API. Every call carries the session cookie automatically (same origin).

export class ApiError extends Error {
  constructor(status) {
    super(`HTTP ${status}`);
    this.status = status;
  }
}

const unauthorizedHandlers = new Set();
export const onUnauthorized = (fn) => unauthorizedHandlers.add(fn);
const lockedHandlers = new Set();
export const onLocked = (fn) => lockedHandlers.add(fn);
const networkHandlers = new Set();
export const onNetworkError = (fn) => networkHandlers.add(fn);

async function request(path, options = {}) {
  let res;
  try {
    res = await fetch(path, { credentials: 'same-origin', cache: 'no-store', ...options });
  } catch (err) {
    if (err.name !== 'AbortError') networkHandlers.forEach((fn) => fn());
    throw err;
  }
  if (res.status === 401) {
    unauthorizedHandlers.forEach((fn) => fn());
    throw new ApiError(401);
  }
  if (res.status === 423) {
    lockedHandlers.forEach((fn) => fn());
    throw new ApiError(423);
  }
  if (!res.ok) {
    const err = new ApiError(res.status);
    try { err.body = await res.clone().json(); } catch { /* not JSON */ }
    throw err;
  }
  return res;
}

export async function getJSON(path, options) {
  return (await request(path, options)).json();
}

export async function sendJSON(path, body, method = 'POST') {
  const init = method === 'DELETE'
    ? { method }
    : { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) };
  const res = await request(path, init);
  return res.json();
}

export const getSession = () => getJSON('/api/session');

/** Resolves {ok:true}. Rejects with ApiError: 403 {attemptsLeft} for a wrong passcode, 429 {retryAfterSec} when locked out. */
export const login = (passcode) => sendJSON('/api/login', { passcode });

export const thumbUrl = (id) => `/api/photos/${id}/thumb`;
export const fullUrl = (id) => `/api/photos/${id}/full`;
export const downloadUrl = (id) => `/api/photos/${id}/download`;

export async function fetchPage({ cursor, limit = 1000, sort, filter, bucket, signal }) {
  const q = new URLSearchParams({ limit: String(limit), sort, filter });
  if (cursor) q.set('cursor', cursor);
  if (bucket) q.set('bucket', bucket);
  return (await request(`/api/photos?${q}`, { signal })).json();
}

export async function requestTrash(ids) {
  const res = await request('/api/trash', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids }),
  });
  return res.json();
}

export async function trashStatus() {
  return (await request('/api/trash/status')).json();
}

/**
 * One photo downloads as a normal file; several stream back as a zip.
 * The zip goes through a real form POST into a hidden iframe so the browser
 * streams it to disk instead of buffering the archive in page memory.
 */
export function downloadIds(ids) {
  if (ids.length === 0) return;
  if (ids.length === 1) {
    const a = document.createElement('a');
    a.href = downloadUrl(ids[0]);
    a.download = '';
    document.body.appendChild(a);
    a.click();
    a.remove();
    return;
  }
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = '/api/download';
  form.target = 'dlframe';
  form.hidden = true;
  const input = document.createElement('input');
  input.type = 'hidden';
  input.name = 'ids';
  input.value = ids.join(',');
  form.appendChild(input);
  document.body.appendChild(form);
  form.submit();
  form.remove();
}

export const fetchAlbums = () => getJSON('/api/albums');
export const fetchContacts = () => getJSON('/api/contacts');
export const logout = () => sendJSON('/api/logout', {});
export const openOnPhone = (kind, value) => sendJSON('/api/open', { kind, value });
