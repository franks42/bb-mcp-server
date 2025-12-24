#!/usr/bin/env node
/**
 * Playwright integration test for sente-browser module
 *
 * Tests:
 * 1. Bootstrap page loads
 * 2. WebSocket connects
 * 3. Browser appears in nREPL connection list
 * 4. Can eval code in browser
 */

import { chromium } from 'playwright';

const BOOTSTRAP_URL = 'http://127.0.0.1:8091';
const MCP_URL = 'http://127.0.0.1:3000/mcp';

async function mcpRequest(method, params = {}) {
  const response = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      method,
      params,
      id: Date.now()
    })
  });
  return response.json();
}

async function initMcp() {
  const initResp = await mcpRequest('initialize', {
    protocolVersion: '2024-11-05',
    capabilities: {},
    clientInfo: { name: 'playwright-test', version: '1.0.0' }
  });
  console.log('MCP initialized:', initResp.result?.serverInfo?.name);
  return initResp;
}

async function listConnections() {
  const resp = await mcpRequest('tools/call', {
    name: 'nrepl.nrepl-connection',
    arguments: { op: 'list' }
  });
  // Response is double-wrapped: result.content[0].text contains EDN that wraps JSON
  const outerText = resp.result?.content?.[0]?.text || '{}';
  // Parse outer EDN-like structure to get inner text
  const match = outerText.match(/:text\s+"([^"]+)"/);
  if (match) {
    return JSON.parse(match[1].replace(/\\"/g, '"').replace(/\\n/g, '\n'));
  }
  return JSON.parse(outerText);
}

async function evalInBrowser(connectionId, code) {
  const resp = await mcpRequest('tools/call', {
    name: 'nrepl.nrepl-eval',
    arguments: { connection: connectionId, code }
  });
  const outerText = resp.result?.content?.[0]?.text || '{}';
  const match = outerText.match(/:text\s+"([^"]+)"/);
  if (match) {
    return JSON.parse(match[1].replace(/\\"/g, '"').replace(/\\n/g, '\n'));
  }
  return JSON.parse(outerText);
}

async function main() {
  console.log('='.repeat(60));
  console.log('Sente-Browser Playwright Integration Test');
  console.log('='.repeat(60));

  // Initialize MCP
  console.log('\n1. Initializing MCP connection...');
  await initMcp();

  // Check initial connections (should be empty)
  console.log('\n2. Checking initial connections...');
  let connections = await listConnections();
  console.log(`   Initial browser count: ${connections.connections?.filter(c => c.type === 'browser').length || 0}`);

  // Launch browser
  console.log('\n3. Launching headless browser...');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  // Capture console logs
  page.on('console', msg => console.log(`   [BROWSER] ${msg.type()}: ${msg.text()}`));
  page.on('pageerror', err => console.log(`   [BROWSER ERROR] ${err.message}`));

  // Navigate to bootstrap page
  console.log('\n4. Navigating to bootstrap page...');
  await page.goto(BOOTSTRAP_URL);

  // Wait for page title
  const title = await page.title();
  console.log(`   Page title: ${title}`);

  // Wait for scripts to load
  await new Promise(r => setTimeout(r, 3000));

  // Wait for WebSocket connection (status changes from "Connecting..." to "Connected")
  console.log('\n5. Waiting for WebSocket connection...');
  try {
    await page.waitForSelector('.status.connected', { timeout: 10000 });
    const statusText = await page.textContent('#status');
    console.log(`   Status: ${statusText}`);
  } catch (e) {
    const statusText = await page.textContent('#status');
    console.log(`   Status after timeout: ${statusText}`);
  }

  // Give time for connection to register
  await new Promise(r => setTimeout(r, 2000));

  // Check connections again
  console.log('\n6. Checking connections after browser connect...');
  connections = await listConnections();
  const browserConns = connections.connections?.filter(c => c.type === 'browser') || [];
  console.log(`   Browser connections: ${browserConns.length}`);

  if (browserConns.length > 0) {
    const browserConn = browserConns[0];
    console.log(`   Browser connection ID: ${browserConn['connection-id']}`);

    // Try to eval code in browser
    console.log('\n7. Evaluating code in browser...');
    const evalResult = await evalInBrowser(browserConn['connection-id'], '(+ 1 2 3)');
    console.log(`   Eval result:`, evalResult);

    // Check the log in the page
    const logContent = await page.textContent('#log');
    console.log(`   Browser log shows eval: ${logContent.includes('[') ? 'yes' : 'no'}`);
  } else {
    console.log('\n   WARNING: No browser connection detected');
    // Get page console logs for debugging
    console.log('\n   Checking page for errors...');
    const statusClass = await page.getAttribute('#status', 'class');
    console.log(`   Status class: ${statusClass}`);
  }

  // Cleanup
  console.log('\n8. Closing browser...');
  await browser.close();

  // Final connection check
  await new Promise(r => setTimeout(r, 1000));
  connections = await listConnections();
  const finalBrowserCount = connections.connections?.filter(c => c.type === 'browser').length || 0;
  console.log(`   Browser connections after close: ${finalBrowserCount}`);

  console.log('\n' + '='.repeat(60));
  console.log('Test complete!');
  console.log('='.repeat(60));
}

main().catch(console.error);
