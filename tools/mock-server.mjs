#!/usr/bin/env node
// Dev-only stand-in for the phone: serves the real web UI from app/src/main/assets/web and
// fakes the API with generated SVG "photos", so the UI can be built and tested without a device.
//
//   node tools/mock-server.mjs                 -> http://localhost:8099/?t=dev
//   COUNT=12000 node tools/mock-server.mjs     -> stress test the grid
//   TRASH=deny node tools/mock-server.mjs      -> phone declines trash requests
//   TRASH=hang node tools/mock-server.mjs      -> phone never answers (tests the waiting state)

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { handlePhase1 } from './mock-phase1.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../app/src/main/assets/web');
const PORT = Number(process.env.PORT || 8099);
const TOKEN = process.env.TOKEN || 'dev';
const COUNT = Number(process.env.COUNT || 1500);
const TRASH = process.env.TRASH || 'allow';
const COOKIE = 'gallery_session';

const SHAPES = [[4000, 3000], [3000, 4000], [4000, 2250], [3000, 3000], [6000, 2000], [1080, 2340]];
const now = Date.now();
let photos = Array.from({ length: COUNT }, (_, i) => {
  const [w, h] = SHAPES[(i * 7 + (i >> 3)) % SHAPES.length];
  const shot = i % 6 === 0;
  return {
    id: i + 1,
    name: shot ? `Screenshot_${i + 1}.png` : `IMG_${String(i + 1).padStart(5, '0')}.jpg`,
    dateTaken: now - Math.floor(i / 9) * 86_400_000 - (i % 9) * 3_600_000,
    size: i % 11 === 0 ? 6_500_000 : 2_300_000,
    width: shot ? 1080 : w,
    height: shot ? 2340 : h,
    bucket: shot ? 'Screenshots' : 'Camera',
    mime: shot ? 'image/png' : 'image/jpeg',
    isShot: shot,
  };
});

const svg = (p, big) => {
  const hue = (p.id * 47) % 360;
  const w = big ? 1600 : 400;
  const h = Math.round(w * (p.height / p.width));
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="hsl(${hue},70%,62%)"/><stop offset="1" stop-color="hsl(${(hue + 50) % 360},65%,38%)"/></linearGradient></defs>
<rect width="100%" height="100%" fill="url(#g)"/><circle cx="${w * 0.72}" cy="${h * 0.28}" r="${w * 0.07}" fill="rgba(255,255,255,.55)"/>
<path d="M0 ${h} L${w * 0.35} ${h * 0.55} L${w * 0.55} ${h * 0.78} L${w * 0.75} ${h * 0.5} L${w} ${h * 0.8} V${h}Z" fill="rgba(0,0,0,.25)"/>
<text x="50%" y="52%" font-family="sans-serif" font-size="${w * 0.11}" fill="#fff" text-anchor="middle" opacity=".9">#${p.id}</text></svg>`;
};

// minimal STORED zip writer for the multi-download path
function zipStored(files) {
  const parts = [];
  const central = [];
  let offset = 0;
  for (const f of files) {
    const name = Buffer.from(f.name);
    const crc = zlib.crc32(f.data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(f.data.length, 18); local.writeUInt32LE(f.data.length, 22); local.writeUInt16LE(name.length, 26);
    parts.push(local, name, f.data);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt32LE(crc, 16);
    c.writeUInt32LE(f.data.length, 20); c.writeUInt32LE(f.data.length, 24); c.writeUInt16LE(name.length, 28); c.writeUInt32LE(offset, 42);
    central.push(c, name);
    offset += 30 + name.length + f.data.length;
  }
  const cd = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...parts, cd, end]);
}

let trash = { status: 'idle', ids: [] };
const cmp = (a, b) => b.dateTaken - a.dateTaken || b.id - a.id;
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };

const readBody = (req) => new Promise((res) => { let b = ''; req.on('data', (d) => (b += d)); req.on('end', () => res(b)); });
const parseIds = (raw) => [...new Set(((raw.match(/\[([^\]]*)\]/) || [null, raw])[1].match(/\d+/g) || []).map(Number))];

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const send = (code, body, headers = {}) => { res.writeHead(code, headers); res.end(body); };
  const json = (code, obj) => send(code, JSON.stringify(obj), { 'Content-Type': 'application/json' });

  if (url.pathname === '/' && url.searchParams.has('t')) {
    if (url.searchParams.get('t') !== TOKEN) return send(401, 'Open the link from the app');
    return send(303, '', { Location: '/', 'Set-Cookie': `${COOKIE}=${TOKEN}; Path=/; HttpOnly; SameSite=Strict` });
  }
  if (!(req.headers.cookie || '').split(';').some((c) => c.trim() === `${COOKIE}=${TOKEN}`)) {
    return send(401, 'Open the link shown in the R3U | Phone Link app.', { 'Content-Type': 'text/html' });
  }

  if (url.pathname.startsWith('/api/') && (await handlePhase1(req, url, send, json))) return;

  if (url.pathname === '/api/photos') {
    const q = url.searchParams;
    const filter = q.get('filter') || 'all';
    let list = photos.filter((p) => filter === 'all' || (filter === 'screenshots' ? p.isShot : p.size > 5 * 1024 * 1024)).sort(cmp);
    if (q.get('sort') === 'oldest') list.reverse();
    let start = 0;
    const c = q.get('cursor');
    if (c) { const i = list.findIndex((p) => `${p.dateTaken}_${p.id}` === c); start = i < 0 ? 0 : i + 1; }
    const limit = Math.min(2000, Math.max(1, Number(q.get('limit')) || 500));
    const items = list.slice(start, start + limit).map(({ isShot, ...p }) => p);
    const next = start + limit < list.length ? `${items.at(-1).dateTaken}_${items.at(-1).id}` : null;
    await new Promise((r) => setTimeout(r, Number(process.env.LATENCY || 60)));
    return json(200, { items, nextCursor: next, total: list.length });
  }

  let m = url.pathname.match(/^\/api\/photos\/(\d+)\/(thumb|full|download)$/);
  if (m) {
    const p = photos.find((x) => x.id === Number(m[1]));
    if (!p) return send(404, 'nf');
    const h = { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'private, max-age=60' };
    if (m[2] === 'download') h['Content-Disposition'] = `attachment; filename="${p.name}"`;
    await new Promise((r) => setTimeout(r, m[2] === 'full' ? 250 : 30));
    return send(200, svg(p, m[2] !== 'thumb'), h);
  }

  if (url.pathname === '/api/download' && req.method === 'POST') {
    const ids = parseIds(await readBody(req));
    const found = ids.map((id) => photos.find((p) => p.id === id)).filter(Boolean);
    if (!found.length) return send(404, 'nf');
    if (found.length === 1) return send(200, svg(found[0], true), { 'Content-Type': 'image/svg+xml', 'Content-Disposition': `attachment; filename="${found[0].name}"` });
    const zip = zipStored(found.map((p) => ({ name: p.name, data: Buffer.from(svg(p, true)) })));
    return send(200, zip, { 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="gallery-mock.zip"' });
  }

  if (url.pathname === '/api/trash' && req.method === 'POST') {
    const ids = parseIds(await readBody(req));
    trash = { status: 'pending', ids };
    if (TRASH !== 'hang') {
      setTimeout(() => {
        if (TRASH === 'deny') trash.status = 'denied';
        else { photos = photos.filter((p) => !ids.includes(p.id)); trash.status = 'done'; }
      }, 3500);
    }
    return json(202, { status: 'pending', count: ids.length });
  }
  if (url.pathname === '/api/trash/status') return json(200, { status: trash.status });

  const rel = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
  const file = path.join(root, rel);
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) return send(404, 'nf');
  send(200, fs.readFileSync(file), { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
});

server.listen(PORT, () => console.log(`Mock phone on http://localhost:${PORT}/?t=${TOKEN}  (${COUNT} photos, trash=${TRASH})`));
