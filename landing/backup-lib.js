// Pure helpers for the backup setup page (tested with: node landing/backup.test.mjs).

/** Pulls the folder id out of a Drive folder address (or accepts a bare id). Returns '' when it can't. */
export function extractFolderId(text) {
  const s = String(text || '').trim();
  if (!s) return '';
  const m = s.match(/\/folders\/([A-Za-z0-9_-]{10,})/) || s.match(/[?&]id=([A-Za-z0-9_-]{10,})/);
  if (m) return m[1];
  return /^[A-Za-z0-9_-]{10,}$/.test(s) ? s : '';
}

/** A random word with 160 bits of entropy, made of letters and digits so it is easy to paste anywhere. */
export function randomSecret(cryptoObj = globalThis.crypto) {
  const alphabet = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const bytes = new Uint8Array(32);
  cryptoObj.getRandomValues(bytes);
  let out = '';
  for (const b of bytes) out += alphabet[b % alphabet.length];
  return out;
}

/** Fills the three settings into the script text. Throws if the script no longer has the expected lines. */
export function buildScript(source, { secret, folderId, albums }) {
  const set = (text, name, value) => {
    const re = new RegExp(`^const ${name} = .*;$`, 'm');
    if (!re.test(text)) throw new Error(`script has no ${name} line`);
    return text.replace(re, () => `const ${name} = ${value};`);
  };
  let out = set(source, 'SECRET', JSON.stringify(secret));
  out = set(out, 'FOLDER_ID', JSON.stringify(folderId));
  out = set(out, 'ALBUM_SUBFOLDERS', albums ? 'true' : 'false');
  return out;
}
