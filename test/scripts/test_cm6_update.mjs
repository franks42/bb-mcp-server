// Test CM6 source viewer updates when clicking different vars
import { chromium } from 'playwright';

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function test() {
  console.log('[test] Starting CM6 update test...');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  // Capture console output
  page.on('console', msg => console.log('[browser]', msg.text()));

  try {
    // 1. Load the page
    console.log('[test] Loading page...');
    await page.goto('http://localhost:8091');
    await sleep(3000);

    // 2. Initialize MCP session
    console.log('[test] Initializing MCP session...');
    const initResult = await fetch('http://localhost:3000/mcp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        method: 'initialize',
        params: {
          protocolVersion: '2024-11-05',
          capabilities: {},
          clientInfo: { name: 'cm6-test', version: '1.0.0' }
        },
        id: 0
      })
    });
    const sessionId = initResult.headers.get('Mcp-Session-Id');
    const initData = await initResult.json();
    if (initData.error) {
      throw new Error(`MCP init failed: ${initData.error.message}`);
    }
    console.log('[test] MCP session initialized, sessionId:', sessionId);

    // Helper to make MCP requests with session
    const mcpCall = async (method, params, id) => {
      const res = await fetch('http://localhost:3000/mcp', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Mcp-Session-Id': sessionId
        },
        body: JSON.stringify({ jsonrpc: '2.0', method, params, id })
      });
      return res.json();
    };

    // 3. Find browser connection
    console.log('[test] Finding browser connection...');
    const listData = await mcpCall('tools/call', {
      name: 'mcp-nrepl.nrepl-connection',
      arguments: { op: 'list' }
    }, 1);
    const connections = JSON.parse(listData.result.content[0].text);
    const browserConn = connections.connections.find(c => c.type === 'browser' && c.status === 'connected');

    if (!browserConn) {
      throw new Error('No browser connection found');
    }
    console.log('[test] Found browser connection:', browserConn.nickname);

    // 4. Test nREPL with simple eval
    console.log('[test] Testing nREPL with (+ 1 2)...');
    const testData = await mcpCall('tools/call', {
      name: 'mcp-nrepl.nrepl-eval',
      arguments: {
        code: '(+ 1 2)',
        connection: browserConn.nickname,
        timeout: 5000
      }
    }, 2);
    const testResult = JSON.parse(testData.result.content[0].text);
    if (testResult.value !== '3') {
      throw new Error(`nREPL test failed: expected 3, got ${testResult.value}`);
    }
    console.log('[test] nREPL works! (+ 1 2) =', testResult.value);

    // 5. Load scittle_cm6.cljs into browser
    console.log('[test] Loading scittle_cm6.cljs...');
    await mcpCall('tools/call', {
      name: 'mcp-nrepl.nrepl-eval-local-file',
      arguments: {
        'file-path': '/Users/franksiebenlist/Development/bb-mcp-server/modules/sente-browser/src/browser/scittle_cm6.cljs',
        connection: browserConn.nickname,
        timeout: 30000
      }
    }, 3);
    await sleep(1000);

    // 6. Define a simple test component that uses the editor
    console.log('[test] Creating test component...');
    const testCode = `
      (require '[reagent.dom :as rdom])

      (def !selected-value (reagent.core/atom "First value"))

      (defn test-app []
        [:div
          [:div {:style {:display "flex" :gap "10px" :margin-bottom "10px"}}
            [:button {:on-click #(reset! !selected-value "First value - fn1 source code here")} "Select fn1"]
            [:button {:on-click #(reset! !selected-value "Second value - fn2 different source")} "Select fn2"]
            [:button {:on-click #(reset! !selected-value "Third value - fn3 yet another source")} "Select fn3"]]
          [:div {:style {:height "200px" :border "1px solid #ccc"}}
            [scittle-cm6/editor {:value @!selected-value :read-only true :language :clojure}]]])

      (rdom/render [test-app] (.getElementById js/document "app"))
    `;

    await mcpCall('tools/call', {
      name: 'mcp-nrepl.nrepl-eval',
      arguments: {
        code: testCode,
        connection: browserConn.nickname,
        timeout: 10000
      }
    }, 4);
    await sleep(2000);

    // 7. Get initial CM6 content
    console.log('[test] Getting initial editor content...');
    const getContent = async () => {
      const data = await mcpCall('tools/call', {
        name: 'mcp-nrepl.nrepl-eval',
        arguments: {
          code: '(let [editors @scittle-cm6/!editors] (when (seq editors) (.toString (.-doc (.-state (val (first editors)))))))',
          connection: browserConn.nickname,
          timeout: 5000
        }
      }, Math.floor(Math.random() * 10000));
      return JSON.parse(data.result.content[0].text);
    };

    const initial = await getContent();
    console.log('[test] Initial content:', initial.value?.substring(0, 50) + '...');

    // 8. Click button to change value
    console.log('[test] Clicking fn2 button...');
    await page.click('button:has-text("Select fn2")');
    await sleep(500);

    const afterFn2 = await getContent();
    console.log('[test] After fn2 click:', afterFn2.value?.substring(0, 50) + '...');

    // 9. Click button to change value again
    console.log('[test] Clicking fn3 button...');
    await page.click('button:has-text("Select fn3")');
    await sleep(500);

    const afterFn3 = await getContent();
    console.log('[test] After fn3 click:', afterFn3.value?.substring(0, 50) + '...');

    // 10. Verify values changed
    const v1 = initial.value || '';
    const v2 = afterFn2.value || '';
    const v3 = afterFn3.value || '';

    console.log('\n=== RESULTS ===');
    console.log('Initial contains "First":', v1.includes('First'));
    console.log('After fn2 contains "Second":', v2.includes('Second'));
    console.log('After fn3 contains "Third":', v3.includes('Third'));

    if (v1.includes('First') && v2.includes('Second') && v3.includes('Third')) {
      console.log('\n[test] SUCCESS: CM6 editor updates correctly when value prop changes!');
    } else {
      console.log('\n[test] FAILURE: CM6 editor did NOT update correctly');
      console.log('v1:', v1);
      console.log('v2:', v2);
      console.log('v3:', v3);
      process.exit(1);
    }

  } catch (err) {
    console.error('[test] ERROR:', err.message);
    process.exit(1);
  } finally {
    await browser.close();
  }
}

test();
