/**
 * R3U | Phone Link: Google Drive backup receiver.
 *
 * Your phone asks this script for an upload slot in your Drive folder, then sends each photo or video
 * straight to Google. The files never pass through this script. Nothing is kept here except a short cache.
 *
 * Set the two values below, then deploy it as a web app (Execute as: Me, Who has access: Anyone).
 * The page at https://r3u-software.github.io/gallery/backup.html fills them in for you.
 */

// A long random word. Type the same word into the dashboard. Anyone who knows it AND the web app address
// can add files to your folder, so keep it private.
const SECRET = 'CHANGE-THIS-TO-A-LONG-RANDOM-WORD';

// The Drive folder that receives the files: the part after /folders/ in the folder's address.
const FOLDER_ID = 'PASTE-YOUR-FOLDER-ID';

// false: all files go straight into the folder. true: one sub-folder per phone album (Camera, WhatsApp, ...).
const ALBUM_SUBFOLDERS = false;

const DRIVE_FILES = 'https://www.googleapis.com/drive/v3/files';
const DRIVE_UPLOAD = 'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable';
const FOLDER_MIME = 'application/vnd.google-apps.folder';

function doGet() {
  return ContentService.createTextOutput('R3U Phone Link backup script is running.');
}

function doPost(e) {
  try {
    const req = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    if (SECRET.length < 12 || SECRET === 'CHANGE-THIS-TO-A-LONG-RANDOM-WORD') return reply({ ok: false, error: 'set_secret' });
    if (!sameText(String(req.secret || ''), SECRET)) return reply({ ok: false, error: 'unauthorized' });
    if (!/^[A-Za-z0-9_-]{10,}$/.test(FOLDER_ID)) return reply({ ok: false, error: 'set_folder' });
    if (req.action === 'ping') {
      // Proves the script can really reach the folder (this fails until the Drive service is added).
      driveGet(DRIVE_FILES + '/' + FOLDER_ID + '?fields=id&supportsAllDrives=true');
      return reply({ ok: true });
    }
    if (req.action === 'init') return init(req);
    return reply({ ok: false, error: 'bad_request' });
  } catch (err) {
    const text = String(err && err.message ? err.message : err);
    if (text.indexOf('too many times') >= 0 || text.indexOf('Quota') >= 0) return reply({ ok: false, error: 'quota' });
    return reply({ ok: false, error: 'server_error' });
  }
}

// Gives the phone a one-time upload address for one file, or says the file is already there.
function init(req) {
  const name = cleanName(req.name);
  const size = Number(req.size);
  const mime = String(req.mime || 'application/octet-stream');
  if (!name || !(size > 0)) return reply({ ok: false, error: 'bad_request' });

  const parent = ALBUM_SUBFOLDERS ? albumFolder(cleanName(req.album) || 'Other') : FOLDER_ID;
  const taken = Number(req.modified);
  if (alreadyThere(parent, name, size, taken)) return reply({ ok: true, exists: true });

  const meta = { name: name, parents: [parent], mimeType: mime };
  if (taken > 0) meta.modifiedTime = new Date(taken).toISOString();

  const resp = UrlFetchApp.fetch(DRIVE_UPLOAD, {
    method: 'post',
    contentType: 'application/json; charset=UTF-8',
    headers: {
      Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
      'X-Upload-Content-Type': mime,
      'X-Upload-Content-Length': String(size),
    },
    payload: JSON.stringify(meta),
    muteHttpExceptions: true,
  });
  const code = resp.getResponseCode();
  if (code === 200) {
    const headers = resp.getAllHeaders();
    const slot = headers.Location || headers.location;
    if (slot) return reply({ ok: true, session: slot });
  }
  if (code === 404) return reply({ ok: false, error: 'set_folder' });
  const body = resp.getContentText();
  if (code === 403 && body.indexOf('storageQuotaExceeded') >= 0) return reply({ ok: false, error: 'drive_full' });
  if (code === 403 || code === 429) return reply({ ok: false, error: 'quota' });
  return reply({ ok: false, error: 'server_error' });
}

// Same name, same size and (when the phone says when the photo was taken) the same time. Matching on the time
// too means two different photos that happen to share a name and size are both kept.
function alreadyThere(parent, name, size, taken) {
  const q = "name = '" + esc(name) + "' and '" + parent + "' in parents and trashed = false";
  const found = driveGet(DRIVE_FILES + '?q=' + encodeURIComponent(q) + '&fields=' + encodeURIComponent('files(id,size,modifiedTime)') + '&pageSize=20');
  return (found.files || []).some(function (f) {
    if (Number(f.size) !== size) return false;
    return !(taken > 0) || Math.abs(Date.parse(f.modifiedTime) - taken) < 2000;
  });
}

function albumFolder(album) {
  const cache = CacheService.getScriptCache();
  const key = 'album:' + digest(album);
  const hit = cache.get(key);
  if (hit) return hit;
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const q = "mimeType = '" + FOLDER_MIME + "' and name = '" + esc(album) + "' and '" + FOLDER_ID + "' in parents and trashed = false";
    const found = driveGet(DRIVE_FILES + '?q=' + encodeURIComponent(q) + '&fields=' + encodeURIComponent('files(id)') + '&pageSize=1');
    let id = found.files && found.files.length ? found.files[0].id : '';
    if (!id) {
      const made = UrlFetchApp.fetch(DRIVE_FILES, {
        method: 'post',
        contentType: 'application/json; charset=UTF-8',
        headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
        payload: JSON.stringify({ name: album, mimeType: FOLDER_MIME, parents: [FOLDER_ID] }),
        muteHttpExceptions: true,
      });
      if (made.getResponseCode() !== 200) throw new Error('could not create the album folder');
      id = JSON.parse(made.getContentText()).id;
    }
    cache.put(key, id, 21600);
    return id;
  } finally {
    lock.releaseLock();
  }
}

function driveGet(url) {
  const resp = UrlFetchApp.fetch(url, {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
    muteHttpExceptions: true,
  });
  if (resp.getResponseCode() !== 200) throw new Error('Drive answered ' + resp.getResponseCode());
  return JSON.parse(resp.getContentText());
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function cleanName(value) {
  return String(value || '').replace(/[\u0000-\u001f]/g, '').trim().slice(0, 200);
}

function esc(text) {
  return text.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

function digest(text) {
  return Utilities.base64EncodeWebSafe(Utilities.computeDigest(Utilities.DigestAlgorithm.MD5, text));
}

function sameText(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
