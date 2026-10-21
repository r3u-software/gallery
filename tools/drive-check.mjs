#!/usr/bin/env node
// Real-Google check of the deployed Apps Script, using the repository secrets (nothing is printed that could leak them).
//
//   GALLERY_BACKUP_URL=... GALLERY_BACKUP_SECRET=... node tools/drive-check.mjs [--upload]
//
// Without --upload it only asks for an upload slot (nothing lands in Drive). With --upload it also sends one tiny
// text file named r3u-ci-check.txt into the Camera album folder, which proves the whole path.
// Run it in CI with the "Drive check" workflow (Actions > Drive check > Run workflow).

const url = process.env.GALLERY_BACKUP_URL;
const secret = process.env.GALLERY_BACKUP_SECRET;
const upload = process.argv.includes('--upload');
if (!url || !secret) {
  console.error('GALLERY_BACKUP_URL and GALLERY_BACKUP_SECRET must be set.');
  process.exit(2);
}

let failed = false;
const check = (ok, what) => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} - ${what}`);
  if (!ok) failed = true;
};

// Like the phone: POST text, follow the redirect Apps Script answers with, read the JSON.
const post = async (obj) => {
  const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify(obj), redirect: 'follow' });
  const text = await res.text();
  try { return JSON.parse(text); } catch { return { ok: false, error: `not json (http ${res.status})` }; }
};

const ping = await post({ secret, action: 'ping' });
check(ping.ok === true, `the script answers and can reach the folder${ping.ok ? '' : ` (${ping.error})`}`);

const wrong = await post({ secret: `${secret}-wrong`, action: 'ping' });
check(wrong.ok === false && wrong.error === 'unauthorized', 'a wrong secret is refused');

const body = Buffer.from(`R3U Phone Link CI check ${new Date().toISOString()}\n`);
const init = await post({ secret, action: 'init', name: 'r3u-ci-check.txt', mime: 'text/plain', size: body.length, album: 'Camera', modified: Date.now() });
const host = init.session ? new URL(init.session).host : '';
check(init.ok === true && host === 'www.googleapis.com', `an upload slot comes from Google${init.ok ? '' : ` (${init.error})`}`);

if (upload && init.session) {
  const put = await fetch(init.session, { method: 'PUT', headers: { 'Content-Range': `bytes 0-${body.length - 1}/${body.length}` }, body });
  check(put.status === 200 || put.status === 201, `the file reaches Drive (http ${put.status})`);
}

process.exit(failed ? 1 : 0);
