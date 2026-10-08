// Run with: node landing/validate.test.mjs
import assert from 'node:assert/strict';
import { parseGalleryLink, isPrivateIPv4 } from './validate.js';

const ok = [
  'http://192.168.1.23:8080/?t=abc',
  'http://10.0.0.5:8081/',
  '  http://172.16.5.9:8080/?t=x_y-z  ',
  'http://172.31.255.1:8090',
];
const bad = [
  '', 'hello', 'https://192.168.1.23:8080/?t=a', 'http://192.168.1.23/?t=a', 'http://8.8.8.8:8080/',
  'http://172.15.0.1:8080/', 'http://172.32.0.1:8080/', 'http://192.169.0.1:8080/', 'http://localhost:8080/',
  'http://192.168.1.23.evil.com:8080/', 'http://user:pw@192.168.1.5:8080/', 'javascript:alert(1)', 'http://192.168.1.300:8080/',
  'http://[::1]:8080/', 'ftp://192.168.1.2:21/',
];
for (const s of ok) assert.ok(parseGalleryLink(s), `should accept ${JSON.stringify(s)}`);
for (const s of bad) assert.equal(parseGalleryLink(s), null, `should reject ${JSON.stringify(s)}`);
assert.ok(isPrivateIPv4('192.168.0.1') && !isPrivateIPv4('192.169.0.1'));
console.log(`landing validation: ${ok.length} accepted, ${bad.length} rejected, all as expected`);
