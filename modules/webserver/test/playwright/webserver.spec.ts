/**
 * Playwright end-to-end tests for the webserver module.
 *
 * Prerequisites:
 *   npm install -D @playwright/test
 *   npx playwright install
 *
 * Running:
 *   1. Start the webserver: bb -e '(require "[webserver.core :as ws]) (ws/start! {:port 9876 :root "modules/webserver/test/fixtures"})'
 *   2. Run tests: npx playwright test modules/webserver/test/playwright/
 */

import { test, expect } from '@playwright/test';

const BASE_URL = 'http://localhost:9876';

test.describe('Webserver Module', () => {

  test.describe('Static File Serving', () => {

    test('serves index.html at root', async ({ page }) => {
      await page.goto(BASE_URL);

      // Check title
      await expect(page).toHaveTitle('Test Page');

      // Check content
      await expect(page.locator('#title')).toHaveText('Hello from Webserver');
      await expect(page.locator('#content')).toHaveText('This is a test page.');
    });

    test('serves CSS with correct styles', async ({ page }) => {
      await page.goto(BASE_URL);

      // Check that CSS is loaded and applied
      const body = page.locator('body');
      const bgColor = await body.evaluate(el =>
        window.getComputedStyle(el).backgroundColor
      );

      // RGB(240, 240, 240) from style.css
      expect(bgColor).toBe('rgb(240, 240, 240)');
    });

    test('serves JavaScript and executes', async ({ page }) => {
      await page.goto(BASE_URL);

      // Wait for JavaScript to execute
      await page.waitForFunction(() => {
        const el = document.getElementById('content');
        return el && el.textContent === 'JavaScript loaded successfully!';
      }, { timeout: 5000 });

      await expect(page.locator('#content')).toHaveText('JavaScript loaded successfully!');
    });

    test('returns 404 for missing files', async ({ page }) => {
      const response = await page.goto(`${BASE_URL}/nonexistent.html`);
      expect(response?.status()).toBe(404);
    });

  });

  test.describe('Health Endpoint', () => {

    test('returns health status', async ({ request }) => {
      const response = await request.get(`${BASE_URL}/_health`);
      expect(response.status()).toBe(200);

      const body = await response.json();
      expect(body.status).toBe('ok');
    });

  });

  test.describe('MIME Types', () => {

    test('serves CSS with correct content-type', async ({ request }) => {
      const response = await request.get(`${BASE_URL}/css/style.css`);
      expect(response.status()).toBe(200);
      expect(response.headers()['content-type']).toContain('text/css');
    });

    test('serves JavaScript with correct content-type', async ({ request }) => {
      const response = await request.get(`${BASE_URL}/js/app.js`);
      expect(response.status()).toBe(200);
      expect(response.headers()['content-type']).toContain('javascript');
    });

    test('serves HTML with correct content-type', async ({ request }) => {
      const response = await request.get(`${BASE_URL}/index.html`);
      expect(response.status()).toBe(200);
      expect(response.headers()['content-type']).toContain('text/html');
    });

  });

});

test.describe('Webserver with Hiccup', () => {
  // These tests require the server to be started with :hiccup true
  // bb -e '(require "[webserver.core :as ws]) (ws/start! {:port 9877 :root "modules/webserver/test/fixtures" :hiccup true})'

  const HICCUP_URL = 'http://localhost:9877';

  test.skip('serves .hiccup files as HTML', async ({ page }) => {
    await page.goto(`${HICCUP_URL}/page.hiccup`);

    await expect(page.locator('#hiccup-title')).toHaveText('Rendered from Hiccup');
    await expect(page.locator('#hiccup-content')).toContainText('rendered from a .hiccup file');
  });

});

test.describe('Webserver with Live Reload', () => {
  // These tests require the server to be started with :reload true
  // bb -e '(require "[webserver.core :as ws]) (ws/start! {:port 9878 :root "modules/webserver/test/fixtures" :reload true})'

  const RELOAD_URL = 'http://localhost:9878';

  test.skip('injects reload script into HTML', async ({ page }) => {
    await page.goto(RELOAD_URL);

    // Check that reload script is present
    const html = await page.content();
    expect(html).toContain('WebSocket');
    expect(html).toContain('_reload');
  });

  test.skip('establishes WebSocket connection', async ({ page }) => {
    await page.goto(RELOAD_URL);

    // Wait for WebSocket connection
    const wsConnected = await page.evaluate(() => {
      return new Promise<boolean>((resolve) => {
        const ws = new WebSocket('ws://localhost:9878/_reload');
        ws.onopen = () => {
          ws.close();
          resolve(true);
        };
        ws.onerror = () => resolve(false);
        setTimeout(() => resolve(false), 3000);
      });
    });

    expect(wsConnected).toBe(true);
  });

});
