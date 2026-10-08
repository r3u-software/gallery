import { CONFIG } from './config.js';
import { parseGalleryLink } from './validate.js';

const $ = (id) => document.getElementById(id);

// Step 1: download button + version/size note
const dl = $('download');
dl.href = CONFIG.downloadUrl;
const meta = [CONFIG.version && `Version ${CONFIG.version}`, CONFIG.size].filter(Boolean).join(' · ');
$('dlMeta').textContent = meta || 'Latest release';

// Step 3: open the gallery. A plain link navigation to http://<phone>:<port>, so there is no
// mixed-content problem and this page never touches your photos.
const form = $('openForm');
const input = $('link');
const error = $('linkError');

input.addEventListener('input', () => {
  error.hidden = true;
  input.removeAttribute('aria-invalid');
});

form.addEventListener('submit', (e) => {
  e.preventDefault();
  const target = parseGalleryLink(input.value);
  if (!target) {
    error.hidden = false;
    input.setAttribute('aria-invalid', 'true');
    input.focus();
    return;
  }
  const a = document.createElement('a');
  a.href = target;
  a.target = '_blank';
  a.rel = 'noopener noreferrer';
  document.body.appendChild(a);
  a.click();
  a.remove();
});
