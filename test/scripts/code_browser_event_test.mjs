// Test code browser event dispatch
import { chromium } from 'playwright';
import { exec } from 'child_process';
import { promisify } from 'util';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const execAsync = promisify(exec);
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const projectDir = join(__dirname, '..', '..');

async function bbEval(conn, code) {
  // For complex code, use base64 encoding
  const base64Code = Buffer.from(code).toString('base64');
  const { stdout } = await execAsync(
    `bb mcp call mcp-nrepl.nrepl-eval '{"code":"${base64Code}","input-base64":true,"connection":"${conn}","timeout":30000}' --mcp code-browser-dev`,
    { cwd: projectDir }
  );
  const jsonMatch = stdout.match(/\{[\s\S]*\}/);
  if (jsonMatch) {
    const result = JSON.parse(jsonMatch[0]);
    return result.value || result.error || result;
  }
  return null;
}

async function loadFileInBrowser(conn, path) {
  // Use nrepl-eval-local-file - it reads file locally and evals via nrepl-eval
  console.log('[load]', path.split('/').pop());
  const { stdout } = await execAsync(
    `bb mcp call mcp-mcp-nrepl.nrepl-eval-local-file '{"file-path":"${path}","connection":"${conn}","timeout":30000}' --mcp code-browser-dev`,
    { cwd: projectDir }
  );
  const jsonMatch = stdout.match(/\{[\s\S]*\}/);
  if (jsonMatch) {
    const result = JSON.parse(jsonMatch[0]);
    console.log('[load] result:', String(result.value || result.error || '?').substring(0, 80));
    return result.value || result.error || result;
  }
  console.log('[load] no JSON result');
  return null;
}

async function getActiveConn() {
  const { stdout: listOut } = await execAsync(
    `bb mcp call mcp-nrepl.nrepl-connection '{"op":"list"}' --mcp code-browser-dev`,
    { cwd: projectDir }
  );
  const jsonMatch = listOut.match(/\{[\s\S]*\}/);
  if (jsonMatch) {
    const json = JSON.parse(jsonMatch[0]);
    const active = (json.connections || []).find(c => c.type === 'browser' && c.status === 'connected');
    return active?.nickname;
  }
  return null;
}

async function main() {
  console.log('[test] Starting browser...');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  page.on('console', msg => console.log('[browser]', msg.text()));

  console.log('[test] Navigating to bootstrap page...');
  await page.goto('http://localhost:8091');
  await page.waitForTimeout(3000);

  const conn = await getActiveConn();
  if (!conn) {
    console.log('[test] ERROR: No active browser');
    await browser.close();
    process.exit(1);
  }
  console.log('[test] Using connection:', conn);

  // Load required files - eval the code directly since Scittle doesn't have load-file
  console.log('[test] Loading code browser files...');
  await loadFileInBrowser(conn, join(projectDir, 'modules/sente-browser/src/browser/scittle_cm6.cljs'));
  await loadFileInBrowser(conn, join(projectDir, 'modules/sente-browser/src/browser/code_browser.cljs'));

  // Mount the code browser
  console.log('[test] Mounting code browser...');
  const mountResult = await bbEval(conn, "(code-browser/mount!)");
  console.log('[test] Mount result:', mountResult);

  // Check registered handlers
  console.log('[test] Checking event handlers...');
  const handlers = await bbEval(conn, "(keys @code-browser.bootstrap/!event-handlers)");
  console.log('[test] Registered handlers:', handlers);

  // Check initial state
  const initialState = await bbEval(conn, "(select-keys @code-browser/!state [:namespaces :loading? :error])");
  console.log('[test] Initial state:', initialState);

  // Wait for potential async response
  console.log('[test] Waiting 3s for namespaces response...');
  await page.waitForTimeout(3000);

  // Check state after waiting
  const afterState = await bbEval(conn, "(select-keys @code-browser/!state [:namespaces :loading? :error])");
  console.log('[test] State after wait:', afterState);

  // Check if namespaces were populated
  const nsCount = await bbEval(conn, "(count (:namespaces @code-browser/!state))");
  console.log('[test] Namespace count:', nsCount);

  if (parseInt(nsCount) > 0) {
    console.log('[test] ✓ SUCCESS! Code browser received namespaces!');
  } else {
    console.log('[test] ✗ Code browser did NOT receive namespaces');
    console.log('[test] Checking if the server is responding...');

    // Check if loading? is still true
    const stillLoading = await bbEval(conn, "(:loading? @code-browser/!state)");
    console.log('[test] Still loading?:', stillLoading);

    // Let's try sending a manual event
    console.log('[test] Trying manual namespace request...');
    const sendResult = await bbEval(conn, "(code-browser/request-namespaces!)");
    console.log('[test] Send result:', sendResult);

    await page.waitForTimeout(3000);

    const finalNsCount = await bbEval(conn, "(count (:namespaces @code-browser/!state))");
    console.log('[test] Final namespace count:', finalNsCount);
  }

  await page.waitForTimeout(1000);
  await browser.close();
  console.log('[test] Done');
}

main().catch(err => {
  console.error('[test] Error:', err.message);
  process.exit(1);
});
