// Phase 1 mock endpoints, mounted by mock-server.mjs. Dev tool only.
// PASSCODE=letmein  -> require the passcode (tests the lock screen)
// OFF=messages,find -> switch features off (tests disabled tabs)

const UNLOCK = 'gallery_unlock';
const PASSCODE = process.env.PASSCODE || '';
const FEATURES = { photos: true, notifications: true, messages: true, files: true, status: true, find: true };
for (const f of (process.env.OFF || '').split(',').filter(Boolean)) FEATURES[f] = false;
const fails = { n: 0, until: 0 };

let notifs = [
  { key: 'n1', app: 'com.chat', appName: 'Messenger', title: 'Ana', text: 'Lunch at 12? I found a new place near the office.', time: Date.now() - 120000, canReply: true },
  { key: 'n2', app: 'com.news', appName: 'News', title: 'Storm warning', text: 'Heavy rain expected tonight across the region.', time: Date.now() - 3600000, canReply: false },
  { key: 'n3', app: 'com.bank', appName: 'Bank', title: 'Payment received', text: 'You received 1,250.00 from R3U Technologies.', time: Date.now() - 7200000, canReply: false },
];
const threads = [
  { id: 1, address: '+639171234567', name: 'Ana Reyes', date: Date.now() - 90000, unread: 1 },
  { id: 2, address: '+639189876543', name: 'Mama', date: Date.now() - 86400000, unread: 0 },
  { id: 3, address: 'GCASH', name: 'GCASH', date: Date.now() - 172800000, unread: 0 },
];
const sms = {
  1: [
    { id: 1, body: 'Are you free later?', date: Date.now() - 200000, outgoing: false },
    { id: 2, body: 'Yes, after 3pm', date: Date.now() - 150000, outgoing: true },
    { id: 3, body: 'See you at the office', date: Date.now() - 90000, outgoing: false },
  ],
  2: [{ id: 4, body: 'Dinner at home tonight?', date: Date.now() - 86500000, outgoing: false }],
  3: [{ id: 5, body: 'You have received PHP 500.00', date: Date.now() - 172800000, outgoing: false }],
};
let phoneFiles = [{ id: 1, name: 'notes.txt', size: 1234, modified: Date.now() - 600000, mime: 'text/plain', data: Buffer.from('hello from the phone') }];
let fileId = 2;
const clip = { text: 'https://example.com/shared-from-phone', time: Date.now() };
let ringing = false;

const readBody = (req) => new Promise((res) => { let b = ''; req.on('data', (d) => (b += d)); req.on('end', () => res(b)); });
const field = (raw, k) => { try { return JSON.parse(raw)[k]; } catch { return undefined; } };

const featureOf = (p) =>
  p.startsWith('/api/notifications') ? 'notifications'
    : p.startsWith('/api/sms') ? 'messages'
      : p.startsWith('/api/files') || p.startsWith('/api/clipboard') ? 'files'
        : p === '/api/status' ? 'status'
          : p.startsWith('/api/find') ? 'find'
            : p.startsWith('/api/photos') || p === '/api/download' || p.startsWith('/api/trash') ? 'photos'
              : null;

/** Returns true when the request was handled. */
export async function handlePhase1(req, url, send, json) {
  const cookies = (req.headers.cookie || '').split(';').map((c) => c.trim());
  const unlocked = !PASSCODE || cookies.includes(`${UNLOCK}=ok`);

  if (url.pathname === '/api/session') {
    json(200, { passcodeRequired: !!PASSCODE, unlocked, device: 'Mock phone', features: FEATURES });
    return true;
  }
  if (url.pathname === '/api/login' && req.method === 'POST') {
    const raw = await readBody(req);
    if (Date.now() < fails.until) { json(429, { error: 'locked_out', retryAfterSec: Math.ceil((fails.until - Date.now()) / 1000) }); return true; }
    if (!PASSCODE || field(raw, 'passcode') === PASSCODE) {
      fails.n = 0;
      send(200, '{"ok":true}', { 'Content-Type': 'application/json', 'Set-Cookie': `${UNLOCK}=ok; Path=/; HttpOnly; SameSite=Strict` });
      return true;
    }
    fails.n++;
    if (fails.n >= 5) { fails.until = Date.now() + 30000; json(429, { error: 'locked_out', retryAfterSec: 30 }); return true; }
    json(403, { error: 'wrong_passcode', attemptsLeft: 5 - fails.n });
    return true;
  }
  if (url.pathname.startsWith('/api/') && !unlocked) { json(423, { error: 'locked' }); return true; }

  const feat = featureOf(url.pathname);
  if (feat && !FEATURES[feat]) { json(403, { error: 'feature_disabled', feature: feat }); return true; }

  const p = url.pathname;
  if (p === '/api/status') {
    json(200, { name: 'Mock phone', model: 'Pixel 8', androidVersion: '14', batteryPercent: 53, charging: true, storageFreeBytes: 41e9, storageTotalBytes: 128e9 });
    return true;
  }
  if (p === '/api/notifications' && req.method === 'GET') { json(200, { connected: true, items: notifs }); return true; }
  if (p === '/api/notifications/dismiss') { const k = field(await readBody(req), 'key'); notifs = notifs.filter((n) => n.key !== k); json(200, { ok: true }); return true; }
  if (p === '/api/notifications/reply') { await readBody(req); json(200, { ok: true }); return true; }
  if (p === '/api/sms/threads') {
    json(200, { permission: true, threads: threads.map((t) => ({ ...t, snippet: (sms[t.id].at(-1) || {}).body || '' })) });
    return true;
  }
  const sm = p.match(/^\/api\/sms\/threads\/(\d+)$/);
  if (sm) { json(200, { messages: sms[Number(sm[1])] || [] }); return true; }
  if (p === '/api/sms/send') {
    const raw = await readBody(req);
    const t = threads.find((x) => x.address === field(raw, 'to'));
    if (t) sms[t.id].push({ id: Date.now(), body: field(raw, 'body'), date: Date.now(), outgoing: true });
    json(200, { ok: true });
    return true;
  }
  if (p === '/api/files' && req.method === 'GET') { json(200, { items: phoneFiles.map(({ data, ...f }) => f) }); return true; }
  if (p === '/api/files/upload') {
    const chunks = [];
    await new Promise((r) => { req.on('data', (d) => chunks.push(d)); req.on('end', r); });
    const body = Buffer.concat(chunks).toString('latin1');
    const names = [...body.matchAll(/filename="([^"]+)"/g)].map((m) => m[1]);
    for (const n of names) phoneFiles.unshift({ id: fileId++, name: n, size: Math.floor(body.length / Math.max(1, names.length)), modified: Date.now(), mime: 'application/octet-stream', data: Buffer.from('mock') });
    json(200, { items: [] });
    return true;
  }
  const fm = p.match(/^\/api\/files\/(\d+)(\/download)?$/);
  if (fm) {
    const f = phoneFiles.find((x) => x.id === Number(fm[1]));
    if (!f) { send(404, 'nf'); return true; }
    if (req.method === 'DELETE') { phoneFiles = phoneFiles.filter((x) => x !== f); json(200, { ok: true }); return true; }
    send(200, f.data, { 'Content-Type': f.mime, 'Content-Disposition': `attachment; filename="${f.name}"` });
    return true;
  }
  if (p === '/api/clipboard' && req.method === 'GET') { json(200, clip); return true; }
  if (p === '/api/clipboard' && req.method === 'POST') { await readBody(req); json(200, { ok: true }); return true; }
  if (p === '/api/find' && req.method === 'GET') { json(200, { ringing }); return true; }
  if (p === '/api/find/ring') { await readBody(req); ringing = true; json(200, { ringing }); return true; }
  if (p === '/api/find/stop') { await readBody(req); ringing = false; json(200, { ringing }); return true; }
  return false;
}
