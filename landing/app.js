import { CONFIG } from './config.js';

const $ = (id) => document.getElementById(id);

// Step 1: download button + version/size note
const dl = $('download');
dl.href = CONFIG.downloadUrl;
const meta = [CONFIG.version && `Version ${CONFIG.version}`, CONFIG.size].filter(Boolean).join(' · ');
$('dlMeta').textContent = meta || 'Latest release';
