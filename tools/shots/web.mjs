// Captures documentation screenshots of the web dashboard against the mock phone (fake data only).
//   node tools/shots/web.mjs <outDir>
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const OUT = path.resolve(process.argv[2] || 'shots/web');
fs.mkdirSync(OUT, { recursive: true });
const PORT = 8110;
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function startServer(env) {
  const proc = spawn('node', ['tools/mock-server.mjs'], { env: { ...process.env, PORT: String(PORT), COUNT: '1200', LATENCY: '0', ...env }, stdio: 'inherit' });
  for (let i = 0; i < 50; i++) {
    try { const r = await fetch(`${BASE}/`); if (r) break; } catch { /* not up yet */ }
    await sleep(200);
  }
  return proc;
}

async function settle(page) {
  await page.waitForFunction(() => [...document.images].every((i) => i.complete), null, { timeout: 15000 }).catch(() => {});
  await sleep(500);
}

async function shot(page, name) {
  await settle(page);
  await page.screenshot({ path: path.join(OUT, `${name}.png`) });
  console.log('shot', name);
}

const cmd = (page, label) => page.locator('#cmdrow .cmd', { hasText: label }).first();
const rail = (page, app) => page.locator(`#rail [data-app="${app}"]`);

async function login(page) {
  await page.goto(`${BASE}/?t=dev`);
  await page.waitForSelector('#lock:not([hidden])', { timeout: 10000 });
}

async function unlock(page) {
  await page.fill('#passcode', 'letmein');
  await page.click('#lockBtn');
  await page.waitForSelector('#lock', { state: 'hidden' });
  await page.waitForSelector('.tile');
}

async function desktopTour(browser, scheme) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: scheme });
  const page = await ctx.newPage();
  const sfx = scheme === 'dark' ? '-dark' : '';

  await login(page);
  if (scheme === 'light') await shot(page, '01-lock');
  await unlock(page);
  await shot(page, `02-photos${sfx}`);
  if (scheme === 'dark') { await ctx.close(); return; }

  // selection and the ribbon
  const checks = page.locator('.tile .check');
  await checks.nth(1).click({ force: true });
  await checks.nth(3).click({ force: true });
  await checks.nth(4).click({ force: true });
  await shot(page, '03-selection');

  // trash flow
  await cmd(page, 'Move to trash').click();
  await page.waitForSelector('#confirm[open]');
  await shot(page, '04-trash-confirm');
  await page.click('#confirmOk');
  await page.waitForSelector('#waiting[open]');
  await shot(page, '05-trash-waiting');
  await page.waitForSelector('#waiting', { state: 'hidden', timeout: 15000 });
  await page.waitForSelector('.toast');
  await shot(page, '06-trash-done');

  // viewer
  await page.keyboard.press('Escape');
  await sleep(300);
  await page.locator('.tile').nth(6).click({ position: { x: 40, y: 40 } });
  await page.waitForSelector('#viewer:not([hidden])');
  await sleep(900);
  await shot(page, '07-viewer');
  await page.keyboard.press('+');
  await page.keyboard.press('+');
  await sleep(500);
  await shot(page, '08-viewer-zoom');
  await page.keyboard.press('Escape');
  await sleep(300);

  // an album
  await page.locator('#paneBody .pitem').nth(2).click();
  await sleep(900);
  await shot(page, '09-album');

  // the other apps
  await rail(page, 'notifications').click();
  await page.waitForSelector('.notif');
  await shot(page, '10-notifications');
  await rail(page, 'messages').click();
  await page.waitForSelector('#paneBody .trow');
  await page.locator('#paneBody .trow').first().click();
  await page.waitForSelector('.bubble');
  await shot(page, '11-messages');
  await rail(page, 'contacts').click();
  await page.waitForSelector('.cname');
  await shot(page, '12-contacts');
  await rail(page, 'files').click();
  await page.waitForSelector('.file');
  await shot(page, '13-files');
  await rail(page, 'phone').click();
  await page.waitForSelector('.meter');
  await shot(page, '14-phone');
  await cmd(page, 'Open link').click();
  await page.waitForSelector('#openDialog[open]');
  await page.fill('#openValue', 'https://example.com/welcome');
  await shot(page, '15-open-on-phone');
  await ctx.close();
}

async function mobileTour(browser) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  await login(page);
  await unlock(page);
  await shot(page, '16-mobile-photos');
  await page.click('#paneBtn');
  await sleep(500);
  await shot(page, '17-mobile-albums');
  await page.keyboard.press('Escape');
  await sleep(500);
  await rail(page, 'notifications').click({ force: true });
  await page.waitForSelector('.notif');
  await shot(page, '18-mobile-notifications');
  await rail(page, 'messages').click({ force: true });
  await page.waitForSelector('#paneBody .trow', { state: 'attached' });
  await page.click('#paneBtn', { force: true });
  await sleep(500);
  await shot(page, '19-mobile-messages');
  await ctx.close();
}

const browser = await chromium.launch();
const server = await startServer({ PASSCODE: 'letmein' });
try {
  await desktopTour(browser, 'light');
  await desktopTour(browser, 'dark');
  await mobileTour(browser);
} finally {
  server.kill();
  await browser.close();
}
