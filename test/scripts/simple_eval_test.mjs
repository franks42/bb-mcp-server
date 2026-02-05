// Simple browser eval test
import { chromium } from 'playwright';
import { exec } from 'child_process';
import { promisify } from 'util';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const execAsync = promisify(exec);

// Get the project directory (parent of test/scripts)
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const projectDir = join(__dirname, '..', '..');

async function main() {
  console.log('[test] Project dir:', projectDir);
  console.log('[test] Starting browser...');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  page.on('console', msg => console.log('[browser]', msg.text()));

  console.log('[test] Navigating to bootstrap page...');
  await page.goto('http://localhost:8091');

  // Wait for connection to establish
  console.log('[test] Waiting 3s for connection to establish...');
  await page.waitForTimeout(3000);

  // Query for the active connection
  console.log('[test] Checking for active connection...');
  const { stdout: listOut } = await execAsync(
    `bb mcp call mcp-nrepl.nrepl-connection '{"op":"list"}' --mcp code-browser-dev`,
    { cwd: projectDir }
  );

  // Parse to find active browser
  let activeNickname = null;
  try {
    const jsonMatch = listOut.match(/\{[\s\S]*\}/);
    if (jsonMatch) {
      const json = JSON.parse(jsonMatch[0]);
      const conns = json.connections || [];
      const active = conns.find(c => c.type === 'browser' && c.status === 'connected');
      if (active) {
        activeNickname = active.nickname;
      }
      console.log('[test] All connections:', conns.map(c => `${c.nickname}:${c.status}`).join(', '));
    }
  } catch (e) {
    console.log('[test] Parse error:', e.message);
    console.log('[test] Raw output:', listOut);
  }

  if (!activeNickname) {
    console.log('[test] ERROR: No active browser connection found');
    await browser.close();
    process.exit(1);
  }

  console.log('[test] Found active browser:', activeNickname);

  // Now try eval
  console.log('[test] Attempting eval (+ 1 2 3)...');
  const evalCmd = `bb mcp call mcp-nrepl.nrepl-eval '{"code":"(+ 1 2 3)","connection":"${activeNickname}","timeout":5000}' --mcp code-browser-dev`;
  console.log('[test] Command:', evalCmd);

  const { stdout: evalOut } = await execAsync(evalCmd, { cwd: projectDir });
  console.log('[test] Raw eval result:');
  console.log(evalOut);

  // Parse result
  try {
    const jsonMatch = evalOut.match(/\{[\s\S]*\}/);
    if (jsonMatch) {
      const result = JSON.parse(jsonMatch[0]);
      if (result.response && result.response.value === '6') {
        console.log('\n[test] ✓ SUCCESS! Browser eval works! Got: 6');
      } else if (result.status === 'timeout') {
        console.log('\n[test] ✗ FAILED: Timeout - browser did not respond');
      } else {
        console.log('\n[test] ? UNEXPECTED:', JSON.stringify(result, null, 2));
      }
    }
  } catch (e) {
    console.log('[test] Could not parse eval result:', e.message);
  }

  // Brief wait then close
  await page.waitForTimeout(1000);
  await browser.close();
  console.log('[test] Done');
}

main().catch(err => {
  console.error('[test] Error:', err.message);
  process.exit(1);
});
