#!/usr/bin/env node
/**
 * Integration test: Concurrent browser connections through nREPL proxy
 *
 * Tests:
 * 1. Multiple browsers connect via sente-browser
 * 2. nREPL proxy lists all browsers via (browser/list)
 * 3. Can switch between browsers with (browser/repl :browser-N)
 * 4. Eval in each browser context works correctly
 * 5. :cljs/quit returns to bb context
 *
 * Prerequisites:
 *   bb server --http 3000   (with sente-browser and nrepl-proxy-server enabled)
 */

import { chromium } from 'playwright';
import * as net from 'net';

const BOOTSTRAP_URL = 'http://127.0.0.1:8091';
const NREPL_PROXY_PORT = 1667;

// =============================================================================
// Minimal Bencode Implementation
// =============================================================================

function encodeString(s) {
  const bytes = Buffer.from(s, 'utf8');
  return Buffer.concat([Buffer.from(`${bytes.length}:`), bytes]);
}

function encodeInt(n) {
  return Buffer.from(`i${n}e`);
}

function encodeDict(obj) {
  const parts = [Buffer.from('d')];
  // Keys must be sorted
  const keys = Object.keys(obj).sort();
  for (const key of keys) {
    parts.push(encodeString(key));
    parts.push(bencode(obj[key]));
  }
  parts.push(Buffer.from('e'));
  return Buffer.concat(parts);
}

function encodeList(arr) {
  const parts = [Buffer.from('l')];
  for (const item of arr) {
    parts.push(bencode(item));
  }
  parts.push(Buffer.from('e'));
  return Buffer.concat(parts);
}

function bencode(value) {
  if (typeof value === 'string') return encodeString(value);
  if (typeof value === 'number') return encodeInt(value);
  if (Array.isArray(value)) return encodeList(value);
  if (typeof value === 'object' && value !== null) return encodeDict(value);
  throw new Error(`Cannot bencode: ${typeof value}`);
}

class BencodeDecoder {
  constructor(buffer) {
    this.buffer = buffer;
    this.pos = 0;
  }

  decode() {
    const char = String.fromCharCode(this.buffer[this.pos]);
    if (char === 'i') return this.decodeInt();
    if (char === 'l') return this.decodeList();
    if (char === 'd') return this.decodeDict();
    if (char >= '0' && char <= '9') return this.decodeString();
    throw new Error(`Unknown bencode type at pos ${this.pos}: ${char}`);
  }

  decodeInt() {
    this.pos++; // skip 'i'
    let end = this.pos;
    while (String.fromCharCode(this.buffer[end]) !== 'e') end++;
    const num = parseInt(this.buffer.slice(this.pos, end).toString(), 10);
    this.pos = end + 1;
    return num;
  }

  decodeString() {
    let colonPos = this.pos;
    while (String.fromCharCode(this.buffer[colonPos]) !== ':') colonPos++;
    const len = parseInt(this.buffer.slice(this.pos, colonPos).toString(), 10);
    this.pos = colonPos + 1;
    const str = this.buffer.slice(this.pos, this.pos + len).toString('utf8');
    this.pos += len;
    return str;
  }

  decodeList() {
    this.pos++; // skip 'l'
    const list = [];
    while (String.fromCharCode(this.buffer[this.pos]) !== 'e') {
      list.push(this.decode());
    }
    this.pos++; // skip 'e'
    return list;
  }

  decodeDict() {
    this.pos++; // skip 'd'
    const dict = {};
    while (String.fromCharCode(this.buffer[this.pos]) !== 'e') {
      const key = this.decodeString();
      const value = this.decode();
      dict[key] = value;
    }
    this.pos++; // skip 'e'
    return dict;
  }

  remaining() {
    return this.buffer.length - this.pos;
  }
}

function bdecode(buffer) {
  const decoder = new BencodeDecoder(buffer);
  return decoder.decode();
}

// =============================================================================
// nREPL Client
// =============================================================================

class NreplClient {
  constructor(port) {
    this.port = port;
    this.socket = null;
    this.buffer = Buffer.alloc(0);
    this.responseQueue = [];
    this.resolvers = [];
  }

  connect() {
    return new Promise((resolve, reject) => {
      this.socket = net.createConnection({ port: this.port }, () => {
        resolve();
      });
      this.socket.on('error', reject);
      this.socket.on('data', (data) => this.onData(data));
    });
  }

  onData(data) {
    this.buffer = Buffer.concat([this.buffer, data]);
    this.tryParse();
  }

  tryParse() {
    while (this.buffer.length > 0) {
      try {
        const decoder = new BencodeDecoder(this.buffer);
        const msg = decoder.decode();
        this.buffer = this.buffer.slice(decoder.pos);

        if (this.resolvers.length > 0) {
          const resolve = this.resolvers.shift();
          resolve(msg);
        } else {
          this.responseQueue.push(msg);
        }
      } catch (e) {
        // Incomplete message, wait for more data
        break;
      }
    }
  }

  send(msg) {
    const encoded = bencode(msg);
    this.socket.write(encoded);
  }

  receive(timeout = 5000) {
    return new Promise((resolve, reject) => {
      if (this.responseQueue.length > 0) {
        resolve(this.responseQueue.shift());
        return;
      }

      const timer = setTimeout(() => {
        const idx = this.resolvers.indexOf(resolve);
        if (idx >= 0) this.resolvers.splice(idx, 1);
        reject(new Error('Timeout waiting for response'));
      }, timeout);

      const wrappedResolve = (msg) => {
        clearTimeout(timer);
        resolve(msg);
      };

      this.resolvers.push(wrappedResolve);
    });
  }

  async request(msg) {
    this.send(msg);
    return this.receive();
  }

  close() {
    if (this.socket) {
      this.socket.destroy();
    }
  }
}

// =============================================================================
// Test Runner
// =============================================================================

let testResults = { passed: 0, failed: 0, tests: [] };

function test(name, passed, details = null) {
  testResults.tests.push({ name, passed, details });
  if (passed) {
    testResults.passed++;
    console.log(`  [PASS] ${name}${details ? ` - ${details}` : ''}`);
  } else {
    testResults.failed++;
    console.log(`  [FAIL] ${name}${details ? ` - ${details}` : ''}`);
  }
}

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function main() {
  console.log('='.repeat(60));
  console.log('nREPL Proxy Concurrent Browser Test');
  console.log('='.repeat(60));
  console.log();

  // 1. Launch multiple browsers
  console.log('1. Launching browsers...');
  const browser = await chromium.launch({ headless: true });

  const pages = [];
  const browserCount = 3;

  for (let i = 0; i < browserCount; i++) {
    const page = await browser.newPage();
    page.on('console', msg => {
      if (msg.type() === 'error') {
        console.log(`   [Browser ${i+1}] ${msg.text()}`);
      }
    });
    pages.push(page);
  }
  test('Browsers launched', pages.length === browserCount, `count=${browserCount}`);

  // 2. Navigate all browsers to bootstrap page
  console.log('\n2. Connecting browsers to sente-browser...');
  for (let i = 0; i < pages.length; i++) {
    await pages[i].goto(BOOTSTRAP_URL);
  }

  // Wait for WebSocket connections
  await sleep(3000);

  let connectedCount = 0;
  for (let i = 0; i < pages.length; i++) {
    try {
      const status = await pages[i].textContent('#status', { timeout: 2000 });
      if (status && status.includes('Connected')) {
        connectedCount++;
      }
    } catch (e) {
      // Ignore
    }
  }
  test('Browsers connected via WebSocket', connectedCount === browserCount,
       `${connectedCount}/${browserCount} connected`);

  // 3. Connect nREPL client to proxy
  console.log('\n3. Connecting nREPL client to proxy...');
  const nrepl = new NreplClient(NREPL_PROXY_PORT);

  try {
    await nrepl.connect();
    test('nREPL client connected', true, `port=${NREPL_PROXY_PORT}`);
  } catch (e) {
    test('nREPL client connected', false, e.message);
    await browser.close();
    process.exit(1);
  }

  // 4. Clone session
  console.log('\n4. Cloning nREPL session...');
  const cloneResp = await nrepl.request({ op: 'clone', id: 'clone-1' });
  const sessionId = cloneResp['new-session'];
  test('Session cloned', !!sessionId, `session=${sessionId?.slice(0, 8)}...`);

  // 5. Test browser/list
  console.log('\n5. Testing (browser/list)...');
  const listResp = await nrepl.request({
    op: 'eval',
    code: '(browser/list)',
    id: 'list-1',
    session: sessionId
  });

  const listValue = listResp.value || '';
  // Parse the EDN response to count browsers
  const browserMatches = listValue.match(/:browser-\d+/g) || [];
  test('browser/list returns browsers', browserMatches.length >= browserCount,
       `found ${browserMatches.length} browsers`);

  // Read done message
  await nrepl.receive();

  // 6. Test switching to each browser and eval
  console.log('\n6. Testing browser switching and eval...');

  // Extract browser IDs from the list response
  const browserIds = [];
  const idRegex = /:browser-(\d+)/g;
  let match;
  while ((match = idRegex.exec(listValue)) !== null) {
    browserIds.push(`:browser-${match[1]}`);
  }

  if (browserIds.length >= 2) {
    // Switch to first browser
    const browser1 = browserIds[0];
    console.log(`   Switching to ${browser1}...`);

    const switchResp = await nrepl.request({
      op: 'eval',
      code: `(browser/repl ${browser1})`,
      id: 'switch-1',
      session: sessionId
    });
    test(`Switch to ${browser1}`, switchResp.value?.includes('Switched') ||
         switchResp.value?.includes('browser'), switchResp.value?.slice(0, 50));
    await nrepl.receive(); // done

    // Eval in first browser
    console.log(`   Evaluating (+ 1 1) in ${browser1}...`);
    const eval1Resp = await nrepl.request({
      op: 'eval',
      code: '(+ 1 1)',
      id: 'eval-b1',
      session: sessionId
    });
    test(`Eval in ${browser1}`, eval1Resp.value === '2', `value=${eval1Resp.value}`);
    await nrepl.receive(); // done

    // Eval JS in browser to prove we're in browser context
    console.log(`   Evaluating js/navigator.userAgent in ${browser1}...`);
    const jsEvalResp = await nrepl.request({
      op: 'eval',
      code: '(.-userAgent js/navigator)',
      id: 'eval-js1',
      session: sessionId
    });
    const hasUserAgent = jsEvalResp.value?.includes('Mozilla') ||
                         jsEvalResp.value?.includes('Headless');
    test(`JS eval in ${browser1}`, hasUserAgent,
         jsEvalResp.value?.slice(0, 40) + '...');
    await nrepl.receive(); // done

    // Switch to second browser
    if (browserIds.length >= 2) {
      const browser2 = browserIds[1];
      console.log(`   Switching to ${browser2}...`);

      const switch2Resp = await nrepl.request({
        op: 'eval',
        code: `(browser/repl ${browser2})`,
        id: 'switch-2',
        session: sessionId
      });
      test(`Switch to ${browser2}`, switch2Resp.value?.includes('Switched') ||
           switch2Resp.value?.includes('browser'), switch2Resp.value?.slice(0, 50));
      await nrepl.receive(); // done

      // Eval in second browser
      console.log(`   Evaluating (* 3 3) in ${browser2}...`);
      const eval2Resp = await nrepl.request({
        op: 'eval',
        code: '(* 3 3)',
        id: 'eval-b2',
        session: sessionId
      });
      test(`Eval in ${browser2}`, eval2Resp.value === '9', `value=${eval2Resp.value}`);
      await nrepl.receive(); // done
    }

    // 7. Test :cljs/quit
    console.log('\n7. Testing :cljs/quit...');
    const quitResp = await nrepl.request({
      op: 'eval',
      code: ':cljs/quit',
      id: 'quit-1',
      session: sessionId
    });
    test(':cljs/quit returns to bb', true, quitResp.value);
    await nrepl.receive(); // done

    // Verify we're back in bb by evaluating bb-only code
    console.log('   Verifying bb context...');
    const bbEvalResp = await nrepl.request({
      op: 'eval',
      code: '(System/getProperty "java.version")',
      id: 'bb-check',
      session: sessionId
    });
    const isBb = bbEvalResp.value && !bbEvalResp.err;
    test('Back in bb context', isBb, `java.version=${bbEvalResp.value}`);
    await nrepl.receive(); // done

  } else {
    test('Browser switching', false, 'Not enough browsers detected');
  }

  // 8. Cleanup
  console.log('\n8. Cleanup...');
  nrepl.close();
  await browser.close();
  test('Cleanup complete', true);

  // Summary
  console.log('\n' + '='.repeat(60));
  console.log('Test Summary');
  console.log('='.repeat(60));
  console.log(`Passed: ${testResults.passed}`);
  console.log(`Failed: ${testResults.failed}`);
  console.log(`Total:  ${testResults.passed + testResults.failed}`);

  if (testResults.failed > 0) {
    console.log('\nFailed tests:');
    for (const t of testResults.tests.filter(t => !t.passed)) {
      console.log(`  - ${t.name}${t.details ? `: ${t.details}` : ''}`);
    }
  }

  console.log();
  if (testResults.failed === 0) {
    console.log('All tests passed!');
    process.exit(0);
  } else {
    console.log('Some tests failed!');
    process.exit(1);
  }
}

main().catch(err => {
  console.error('Test error:', err);
  process.exit(1);
});
