// Validates the link pasted from the phone app: http, private IPv4 address, explicit port.

const IPV4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;

export function isPrivateIPv4(host) {
  const m = IPV4.exec(host);
  if (!m) return false;
  const o = m.slice(1).map(Number);
  if (o.some((n) => n > 255)) return false;
  return o[0] === 10 || (o[0] === 172 && o[1] >= 16 && o[1] <= 31) || (o[0] === 192 && o[1] === 168);
}

/** @returns {string|null} the normalized URL to open, or null if the text is not a valid gallery link */
export function parseGalleryLink(text) {
  let url;
  try {
    url = new URL(String(text).trim());
  } catch {
    return null;
  }
  if (url.protocol !== 'http:') return null;
  if (url.username || url.password) return null;
  if (!isPrivateIPv4(url.hostname)) return null;
  if (!url.port) return null; // the app always uses an explicit port
  return url.href;
}
