import { extractFolderId, randomSecret, buildScript } from './backup-lib.js';

const $ = (id) => document.getElementById(id);

const state = { source: '', manifest: '', secret: randomSecret() };

async function load() {
  const [code, manifest] = await Promise.all([
    fetch('backup/Code.gs').then((r) => r.text()),
    fetch('backup/appsscript.json').then((r) => r.text()),
  ]);
  state.source = code;
  state.manifest = manifest;
  refresh();
}

function folderId() {
  return extractFolderId($('folder').value);
}

function refresh() {
  const id = folderId();
  const typed = $('folder').value.trim() !== '';
  $('folderError').hidden = !typed || id !== '';
  $('folder').toggleAttribute('aria-invalid', typed && id === '');
  $('secret').textContent = state.secret;
  const ready = id !== '' && state.source !== '';
  $('copyScript').disabled = !ready;
  $('scriptHint').hidden = ready;
  if (ready) {
    $('source').textContent = buildScript(state.source, { secret: state.secret, folderId: id, albums: $('albums').checked });
  } else {
    $('source').textContent = state.source;
  }
}

async function copy(button, text) {
  const label = button.textContent;
  try {
    await navigator.clipboard.writeText(text);
    button.textContent = 'Copied';
  } catch {
    button.textContent = 'Select and copy it below';
  }
  setTimeout(() => { button.textContent = label; }, 2000);
}

$('folder').addEventListener('input', refresh);
$('albums').addEventListener('change', refresh);
$('newSecret').addEventListener('click', () => { state.secret = randomSecret(); refresh(); });
$('copyScript').addEventListener('click', () => copy($('copyScript'), $('source').textContent));
$('copyManifest').addEventListener('click', () => copy($('copyManifest'), state.manifest));
$('copySecret').addEventListener('click', () => copy($('copySecret'), state.secret));

load().catch(() => { $('scriptHint').textContent = 'Could not load the script. Reload this page.'; });
