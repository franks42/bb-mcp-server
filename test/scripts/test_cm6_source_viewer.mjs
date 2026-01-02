// Test CM6 source viewer rendering
// Tests the fix for @codemirror/state multiple instance error
import { chromium } from 'playwright';

async function main() {
  const errors = [];

  console.log('[test] Launching browser...');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  // Collect console errors
  page.on('console', msg => {
    const text = msg.text();
    console.log('[browser]', text);
    // Check for CM6 error
    if (text.includes('Unrecognized extension value') ||
        text.includes('@codemirror/state') ||
        text.includes('multiple instances')) {
      errors.push(text);
    }
  });

  page.on('pageerror', err => {
    console.log('[page-error]', err.message);
    errors.push(err.message);
  });

  try {
    console.log('[test] Navigating to code browser...');
    await page.goto('http://localhost:8091');
    await page.waitForTimeout(2000);

    // Wait for "Load Code Browser" button to be enabled
    console.log('[test] Waiting for Load button to be enabled...');
    await page.waitForSelector('#load-ui-btn:not([disabled])', { timeout: 15000 });

    console.log('[test] Clicking Load Code Browser button...');
    await page.click('#load-ui-btn');
    await page.waitForTimeout(3000);

    // Wait for namespaces panel to have items
    console.log('[test] Waiting for namespaces to load...');
    await page.waitForSelector('.namespace-panel .list-item', { timeout: 30000 });

    // Count namespaces
    const nsCount = await page.locator('.namespace-panel .list-item').count();
    console.log(`[test] Found ${nsCount} namespaces`);

    if (nsCount === 0) {
      throw new Error('No namespaces loaded - is clojure-lsp initialized?');
    }

    // Click first namespace
    console.log('[test] Clicking first namespace...');
    await page.click('.namespace-panel .list-item:first-child');
    await page.waitForTimeout(2000);

    // Wait for symbols to load
    console.log('[test] Waiting for symbols...');
    await page.waitForSelector('.symbols-panel .list-item', { timeout: 15000 });

    const symCount = await page.locator('.symbols-panel .list-item').count();
    console.log(`[test] Found ${symCount} symbols`);

    if (symCount === 0) {
      throw new Error('No symbols loaded');
    }

    // Click first symbol to trigger CM6
    console.log('[test] Clicking first symbol (triggers CM6)...');
    await page.click('.symbols-panel .list-item:first-child');
    await page.waitForTimeout(3000);

    // Check if CM6 editor rendered
    const hasEditor = await page.locator('.cm-editor').count() > 0;
    console.log(`[test] CM6 editor rendered: ${hasEditor}`);

    // Check for CM6 content
    const hasContent = await page.locator('.cm-content').count() > 0;
    console.log(`[test] CM6 content present: ${hasContent}`);

    // Final error check
    if (errors.length > 0) {
      console.log('\n[test] ✗ FAILED - CM6 errors detected:');
      errors.forEach(e => console.log('  -', e));
      await browser.close();
      process.exit(1);
    }

    if (!hasEditor || !hasContent) {
      console.log('\n[test] ✗ FAILED - CM6 editor did not render');
      await browser.close();
      process.exit(1);
    }

    console.log('\n[test] ✓ SUCCESS - CM6 source viewer works!');

  } catch (err) {
    console.log('\n[test] ✗ ERROR:', err.message);
    await browser.close();
    process.exit(1);
  }

  await browser.close();
  process.exit(0);
}

main();
