// Justified-row layout with date group headers. Pure functions: no DOM access.

const dayFmt = new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
const dayYearFmt = new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });

export function dayKey(ms) {
  const d = new Date(ms);
  return d.getFullYear() * 10000 + (d.getMonth() + 1) * 100 + d.getDate();
}

export function dayLabel(ms, now = new Date()) {
  const d = new Date(ms);
  const todayKey = dayKey(now.getTime());
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  const k = dayKey(ms);
  if (k === todayKey) return 'Today';
  if (k === dayKey(yesterday.getTime())) return 'Yesterday';
  return (d.getFullYear() === now.getFullYear() ? dayFmt : dayYearFmt).format(d);
}

export function aspect(item) {
  const r = item.width > 0 && item.height > 0 ? item.width / item.height : 4 / 3;
  return Math.min(3, Math.max(0.4, r));
}

/**
 * @returns {{blocks: object[], tiles: object[], height: number}}
 *  blocks: headers and tiles sorted by y (tiles in a row share the same y)
 *  tiles:  tile block for each item index
 */
export function buildLayout(items, width, { gap = 4, targetH = 220, headerH = 48, groupGap = 18 } = {}) {
  const blocks = [];
  const tiles = new Array(items.length);
  let y = 0;
  const n = items.length;

  const pushRow = (from, to, h) => {
    let x = 0;
    for (let k = from; k < to; k++) {
      const w = aspect(items[k]) * h;
      const t = { kind: 't', idx: k, x, y, w, h };
      blocks.push(t);
      tiles[k] = t;
      x += w + gap;
    }
    y += h + gap;
  };

  let i = 0;
  while (i < n) {
    const key = items[i].day;
    let j = i;
    while (j < n && items[j].day === key) j++;

    blocks.push({ kind: 'h', y, h: headerH, label: dayLabel(items[i].dateTaken), from: i, to: j, count: j - i });
    y += headerH;

    let rowStart = i;
    let sum = 0;
    for (let k = i; k < j; k++) {
      sum += aspect(items[k]);
      const count = k - rowStart + 1;
      if (sum * targetH + gap * (count - 1) >= width) {
        pushRow(rowStart, k + 1, (width - gap * (count - 1)) / sum);
        rowStart = k + 1;
        sum = 0;
      }
    }
    if (rowStart < j) pushRow(rowStart, j, targetH); // last row keeps natural height
    y += groupGap;
    i = j;
  }
  return { blocks, tiles, height: Math.max(0, y) };
}
