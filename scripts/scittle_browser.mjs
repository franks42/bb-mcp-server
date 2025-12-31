#!/usr/bin/env node
// Playwright script to keep Scittle browser active for nREPL testing
// Usage: node scripts/scittle_browser.mjs [--headless]

import { chromium } from 'playwright';

const SCITTLE_URL = 'http://localhost:8091';
const headless = process.argv.includes('--headless');

async function main() {
  console.log(`Launching browser (headless: ${headless})...`);

  const browser = await chromium.launch({ headless });
  const context = await browser.newContext();
  const page = await context.newPage();

  // Log console messages from the page
  page.on('console', msg => {
    if (msg.type() !== 'log') return;
    console.log(`[browser] ${msg.text()}`);
  });

  console.log(`Navigating to ${SCITTLE_URL}...`);
  await page.goto(SCITTLE_URL);

  // Wait for Scittle to initialize (look for nREPL connection)
  await page.waitForTimeout(2000);

  console.log('Browser ready. Press Ctrl+C to close.');
  console.log('nREPL commands will be executed in this browser context.');

  // Keep alive - Playwright won't throttle like a background tab
  await new Promise(() => {}); // Run forever until Ctrl+C
}

main().catch(err => {
  console.error('Error:', err);
  process.exit(1);
});
