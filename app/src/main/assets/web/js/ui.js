// Small shared UI helpers: toasts and promise-based dialogs.

const toastRoot = () => document.getElementById('toasts');

/** Shows a toast. Returns a function that dismisses it early. */
export function toast(message, { kind = 'info', timeout = 4000 } = {}) {
  const el = document.createElement('div');
  el.className = `toast ${kind === 'error' ? 'error' : ''}`;
  el.textContent = message;
  toastRoot().appendChild(el);
  let closed = false;
  const dismiss = () => {
    if (closed) return;
    closed = true;
    el.classList.add('leaving');
    setTimeout(() => el.remove(), 260);
  };
  if (timeout > 0) setTimeout(dismiss, timeout);
  return dismiss;
}

/** Native <dialog> confirm. Resolves true when the primary action is chosen. */
export function confirmDialog({ title, text, okLabel }) {
  const dlg = document.getElementById('confirm');
  document.getElementById('confirmTitle').textContent = title;
  document.getElementById('confirmText').textContent = text;
  document.getElementById('confirmOk').textContent = okLabel;
  return new Promise((resolve) => {
    const onClose = () => {
      dlg.removeEventListener('close', onClose);
      resolve(dlg.returnValue === 'ok');
    };
    dlg.returnValue = 'cancel';
    dlg.addEventListener('close', onClose);
    dlg.showModal();
    document.getElementById('confirmCancel').focus();
  });
}

export const fmtCount = (n) => n.toLocaleString();
export const plural = (n, one, many) => `${fmtCount(n)} ${n === 1 ? one : many}`;
