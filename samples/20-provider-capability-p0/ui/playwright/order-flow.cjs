// Run one real Chromium interaction in the CLI provider's private working directory.
const [library, browserExecutable, url, expected, artifacts] = process.argv.slice(2);
const { chromium } = require(library);
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

async function submitOrder(home) {
  const browser = await chromium.launch({
    executablePath: browserExecutable,
    timeout: 3500,
    env: { ...process.env, HOME: home },
  });
  try {
    const context = await browser.newContext();
    context.setDefaultTimeout(1500);
    await context.tracing.start({ screenshots: true, snapshots: true });
    try {
      const page = await context.newPage();
      await page.goto(url, { waitUntil: 'domcontentloaded' });
      const status = page.getByRole('status');
      const number = 'UI-1042';
      assert.equal(await status.textContent(), '', 'initial order state');
      await page.getByRole('textbox', { name: 'Order number' }).fill(number);
      await page.getByRole('button', { name: 'Submit order' }).click();
      await status.waitFor({ state: 'visible' });
      assert.equal(await status.innerText(), 'Order ' + number + ' confirmed', 'confirmation text');
      await page.reload({ waitUntil: 'domcontentloaded' });
      await status.waitFor({ state: 'visible' });
      const confirmation = await status.innerText();
      assert.equal(confirmation, expected, 'confirmation after reload');
      await context.tracing.stop();
      process.stdout.write(confirmation + ' after reload\n');
    } catch (error) {
      const traceDir = path.join(artifacts, path.basename(process.cwd()));
      await fs.mkdir(traceDir, { mode: 0o700 });
      await context.tracing.stop({ path: path.join(traceDir, 'failure-trace.zip') });
      throw error;
    }
  } finally {
    await browser.close();
  }
}

async function run() {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), 'order-ui-'));
  try {
    await submitOrder(home);
  } finally {
    await fs.rm(home, { recursive: true, force: true });
  }
}

run().catch(error => {
  // Stable, bounded diagnostics. Detailed DOM and network observations stay in the trace.
  if (error instanceof assert.AssertionError) {
    process.stderr.write('Browser assertion failed: ' + error.message.split('\n')[0] + '\n');
    process.exitCode = 1;
  } else {
    process.stderr.write('Browser flow could not complete (' + error.name + ').\n');
    process.exitCode = 2;
  }
});
