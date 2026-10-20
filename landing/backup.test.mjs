// Run with: node landing/backup.test.mjs
// Checks the setup page helpers, then runs the real Apps Script (landing/backup/Code.gs) against a fake Google.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import crypto from 'node:crypto';
import { extractFolderId, randomSecret, buildScript } from './backup-lib.js';

// ------------------------------------------------------------ page helpers
const FOLDER = '1uVRLfJvL9Pzswe7LFlyYTV6qLY8qE2Ic';
assert.equal(extractFolderId(`https://drive.google.com/drive/folders/${FOLDER}?usp=sharing`), FOLDER);
assert.equal(extractFolderId(`https://drive.google.com/drive/u/1/folders/${FOLDER}`), FOLDER);
assert.equal(extractFolderId(`https://drive.google.com/open?id=${FOLDER}`), FOLDER);
assert.equal(extractFolderId(FOLDER), FOLDER);
assert.equal(extractFolderId('hello world'), '');
assert.equal(extractFolderId(''), '');
assert.equal(extractFolderId('https://example.com/folders/short'), '');
const s1 = randomSecret();
const s2 = randomSecret();
assert.ok(s1.length >= 32 && s1 !== s2 && /^[A-Za-z0-9]+$/.test(s1));

const source = fs.readFileSync(new URL('./backup/Code.gs', import.meta.url), 'utf8');
const manifest = JSON.parse(fs.readFileSync(new URL('./backup/appsscript.json', import.meta.url), 'utf8'));
assert.ok(manifest.oauthScopes.includes('https://www.googleapis.com/auth/script.external_request'));
assert.ok(manifest.oauthScopes.includes('https://www.googleapis.com/auth/drive'));
assert.equal(manifest.webapp.access, 'ANYONE_ANONYMOUS');
assert.equal(manifest.webapp.executeAs, 'USER_DEPLOYING');
assert.throws(() => buildScript('const SECRET = 1;', { secret: 'x', folderId: 'y', albums: false }), /FOLDER_ID/);

// ------------------------------------------------------------ run the real script against a fake Google
function load(settings) {
  const code = buildScript(source, settings);
  const drive = { files: [], nextId: 1, uploads: [], quota: false };
  const cacheStore = new Map();
  const respond = (status, obj, headers = {}) => ({
    getResponseCode: () => status,
    getContentText: () => (typeof obj === 'string' ? obj : JSON.stringify(obj)),
    getAllHeaders: () => headers,
  });
  const ctx = {
    console,
    ContentService: {
      MimeType: { JSON: 'json' },
      createTextOutput: (text) => ({ text, setMimeType() { return this; } }),
    },
    CacheService: { getScriptCache: () => ({ get: (k) => cacheStore.get(k) ?? null, put: (k, v) => cacheStore.set(k, v) }) },
    LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
    ScriptApp: { getOAuthToken: () => 'fake-token' },
    Utilities: {
      DigestAlgorithm: { MD5: 'md5' },
      computeDigest: (_a, text) => crypto.createHash('md5').update(text).digest(),
      base64EncodeWebSafe: (buf) => Buffer.from(buf).toString('base64url'),
    },
    UrlFetchApp: {
      fetch(url, opts = {}) {
        assert.equal(opts.headers.Authorization, 'Bearer fake-token');
        const u = new URL(url);
        if (u.pathname === '/upload/drive/v3/files') {
          const meta = JSON.parse(opts.payload);
          if (meta.parents[0] === 'missing-folder') return respond(404, { error: 'nf' });
          if (drive.quota) return respond(403, 'storageQuotaExceeded');
          const id = `up${drive.uploads.length + 1}`;
          drive.uploads.push({ meta, headers: opts.headers });
          return respond(200, '', { Location: `https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=${id}` });
        }
        if (u.pathname === '/drive/v3/files' && (opts.method || 'get') === 'get') {
          const q = u.searchParams.get('q');
          const name = /name = '((?:[^'\\]|\\.)*)'/.exec(q)[1].replace(/\\(.)/g, '$1');
          const parent = /'([^']+)' in parents/.exec(q)[1];
          const wantFolder = q.includes('mimeType');
          const items = drive.files.filter((f) => f.name === name && f.parents.includes(parent) && !!f.folder === wantFolder);
          return respond(200, { files: items.map((f) => ({ id: f.id, size: String(f.size) })) });
        }
        if (u.pathname === '/drive/v3/files' && opts.method === 'post') {
          const meta = JSON.parse(opts.payload);
          const f = { id: `folder${drive.nextId++}`, name: meta.name, parents: meta.parents, folder: true };
          drive.files.push(f);
          return respond(200, { id: f.id });
        }
        throw new Error(`unexpected fetch ${url}`);
      },
    },
  };
  vm.createContext(ctx);
  vm.runInContext(code, ctx);
  const call = (body) => JSON.parse(ctx.doPost({ postData: { contents: JSON.stringify(body) } }).text);
  return { drive, call, ctx };
}

const SECRET = 'abcdefghijklmnop-secret';
const base = { secret: SECRET, folderId: FOLDER, albums: false };
const photo = { secret: SECRET, action: 'init', name: "O'Brien\\IMG_1.jpg", mime: 'image/jpeg', size: 1234, album: 'Camera', modified: 1_700_000_000_000 };

{ // the secret guards everything
  const { call } = load(base);
  assert.deepEqual(call({ ...photo, secret: 'nope' }), { ok: false, error: 'unauthorized' });
  assert.deepEqual(call({ action: 'ping' }), { ok: false, error: 'unauthorized' });
  assert.deepEqual(call({ secret: SECRET, action: 'ping' }), { ok: true });
  assert.deepEqual(call({ secret: SECRET, action: 'what' }), { ok: false, error: 'bad_request' });
}
{ // unconfigured scripts refuse to run
  const unset = 'CHANGE-THIS-TO-A-LONG-RANDOM-WORD';
  const a = load({ ...base, secret: unset });
  assert.deepEqual(a.call({ secret: unset, action: 'ping' }), { ok: false, error: 'set_secret' });
  const b = load({ ...base, secret: 'short' });
  assert.deepEqual(b.call({ secret: 'short', action: 'ping' }), { ok: false, error: 'set_secret' });
  const c = load({ ...base, folderId: 'x' });
  assert.deepEqual(c.call({ secret: SECRET, action: 'ping' }), { ok: false, error: 'set_folder' });
}
{ // an upload slot goes into the chosen folder with the file's details
  const { drive, call } = load(base);
  const r = call(photo);
  assert.equal(r.ok, true);
  assert.match(r.session, /^https:\/\/www\.googleapis\.com\/upload\/drive\/v3\/files\?uploadType=resumable&upload_id=up1$/);
  const sent = drive.uploads[0];
  assert.deepEqual(sent.meta.parents, [FOLDER]);
  assert.equal(sent.meta.name, "O'Brien\\IMG_1.jpg");
  assert.equal(sent.meta.mimeType, 'image/jpeg');
  assert.equal(sent.meta.modifiedTime, new Date(1_700_000_000_000).toISOString());
  assert.equal(sent.headers['X-Upload-Content-Length'], '1234');
  assert.equal(sent.headers['X-Upload-Content-Type'], 'image/jpeg');
}
{ // a file already in the folder (same name and size) is not uploaded again; a different size is
  const { drive, call } = load(base);
  drive.files.push({ id: 'f1', name: "O'Brien\\IMG_1.jpg", size: 1234, parents: [FOLDER] });
  assert.deepEqual(call(photo), { ok: true, exists: true });
  assert.equal(drive.uploads.length, 0);
  assert.equal(call({ ...photo, size: 99 }).ok, true);
  assert.equal(drive.uploads.length, 1);
}
{ // album sub-folders are created once and reused
  const { drive, call } = load({ ...base, albums: true });
  assert.equal(call(photo).ok, true);
  assert.equal(call({ ...photo, name: 'IMG_2.jpg' }).ok, true);
  assert.equal(call({ ...photo, name: 'WA1.jpg', album: 'WhatsApp Images' }).ok, true);
  const folders = drive.files.filter((f) => f.folder);
  assert.deepEqual(folders.map((f) => f.name).sort(), ['Camera', 'WhatsApp Images']);
  assert.deepEqual(drive.uploads[0].meta.parents, [folders.find((f) => f.name === 'Camera').id]);
  assert.deepEqual(drive.uploads[1].meta.parents, drive.uploads[0].meta.parents);
}
{ // bad input and Drive problems map to codes the phone understands
  const { drive, call } = load(base);
  assert.deepEqual(call({ ...photo, name: '' }), { ok: false, error: 'bad_request' });
  assert.deepEqual(call({ ...photo, size: 0 }), { ok: false, error: 'bad_request' });
  drive.quota = true;
  assert.deepEqual(call(photo), { ok: false, error: 'drive_full' });
  const gone = load({ ...base, folderId: 'missing-folder' });
  assert.deepEqual(gone.call(photo), { ok: false, error: 'set_folder' });
}
{ // garbage in, no crash
  const { ctx } = load(base);
  assert.equal(JSON.parse(ctx.doPost({ postData: { contents: 'not json' } }).text).ok, false);
  assert.equal(JSON.parse(ctx.doPost({}).text).ok, false);
  assert.match(ctx.doGet().text, /running/);
}
console.log('backup page helpers and Apps Script: all checks passed');
