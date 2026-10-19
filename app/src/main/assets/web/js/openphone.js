// "Open on the phone": ask for a link, number or place, then send it. The phone opens it (or shows a
// notification to tap when the app isn't on screen).

import { openOnPhone, ApiError } from './api.js';
import { toast } from './ui.js';

const $ = (id) => document.getElementById(id);

const KINDS = {
  link: { title: 'Open a link on the phone', help: 'The link opens in the phone\'s browser.', placeholder: 'https://example.com', go: 'Open on phone' },
  dial: { title: 'Dial a number on the phone', help: 'The phone opens its dialer with this number. You press call on the phone.', placeholder: '+63 917 555 0142', go: 'Open dialer' },
  map: { title: 'Show a place on the phone\'s map', help: 'The phone opens its maps app searching for this place or address.', placeholder: 'Ayala Avenue, Makati', go: 'Open map' },
};

let kind = 'link';
let wired = false;

function wire() {
  if (wired) return;
  wired = true;
  $('openCancel').addEventListener('click', () => $('openDialog').close());
  $('openForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const value = $('openValue').value.trim();
    const err = $('openError');
    err.hidden = true;
    $('openGo').disabled = true;
    try {
      await openOnPhone(kind, value);
      $('openDialog').close();
      toast('Sent to the phone. If the app is closed, tap the notification there.');
    } catch (error) {
      if (error instanceof ApiError && [401, 423].includes(error.status)) {
        $('openDialog').close();
      } else {
        err.textContent = error instanceof ApiError && error.body?.error ? error.body.error : "Couldn't reach the phone.";
        err.hidden = false;
      }
    } finally {
      $('openGo').disabled = false;
    }
  });
}

/** @param {'link'|'dial'|'map'} k */
export function askOpen(k, prefill = '', sendNow = false) {
  wire();
  kind = k;
  const cfg = KINDS[k];
  $('openTitle').textContent = cfg.title;
  $('openHelp').textContent = cfg.help;
  $('openValue').placeholder = cfg.placeholder;
  $('openValue').value = prefill;
  $('openGo').textContent = cfg.go;
  $('openError').hidden = true;
  $('openDialog').showModal();
  $('openValue').focus();
  $('openValue').select();
  if (sendNow) $('openGo').focus();
}
