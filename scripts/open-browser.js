#!/usr/bin/env node
// Launch a headless Playwright browser connecting to the Scittle dev page.
// Usage: node scripts/open-browser.js [url] [timeout-minutes]
//   url: defaults to http://localhost:8091
//   timeout-minutes: defaults to 10

const { chromium } = require('playwright');

const url = process.argv[2] || 'http://localhost:8091';
const timeoutMin = parseInt(process.argv[3] || '10', 10);

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.on('console', msg => console.log('[browser]', msg.text()));
  page.on('pageerror', err => console.error('[browser-error]', err.message));
  await page.goto(url);
  console.log(`[open-browser] Connected to ${url}, staying open for ${timeoutMin} minutes...`);
  await page.waitForTimeout(timeoutMin * 60 * 1000);
  await browser.close();
  console.log('[open-browser] Browser closed.');
})();
