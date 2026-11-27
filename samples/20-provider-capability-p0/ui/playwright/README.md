# Playwright order confirmation

This local regression test fills an order number, clicks **Submit order**, reads the visible confirmation, reloads the page and reads it again in headless Chromium. The included loopback fixture persists the order in browser local storage. Each test starts with a fresh browser context. The framework's existing `cli_process.v0.3 / run_command` executes a pinned Node binary and applies ordinary assertions to the exit code, stdout and stderr.

## Run both controls

Start at the repository root with a packaged framework JAR, Java, Python 3, jq, npm and a standalone Node 20+ distribution installed. The CLI provider stages the Node executable into a private directory. A Node build requiring sibling DLLs or dylibs may fail after staging. Use the actual standalone binary path and its lowercase SHA-256 from trusted runner provisioning.

```bash
sample=samples/20-provider-capability-p0/ui/playwright
mkdir -p target/playwright-tools
cp "$sample/package.json" "$sample/package-lock.json" target/playwright-tools/
npm ci --prefix target/playwright-tools --ignore-scripts --no-audit --no-fund
target/playwright-tools/node_modules/.bin/playwright-core install --no-shell chromium
export UI_NODE=/absolute/path/to/standalone/node
export UI_NODE_SHA256='reviewed-64-character-lowercase-digest'
UI_CHROMIUM=$(cd target/playwright-tools && node -p "require('playwright-core').chromium.executablePath()")
export UI_CHROMIUM
scripts/ci/verify-playwright-flow.sh
```

Replace the digest placeholder before running. On a fresh Linux CI image, use `install --with-deps --no-shell chromium` to provision browser system dependencies. The lockfile supplies the npm integrity hash and Playwright pins the Chromium revision. A company runner can preinstall these assets from its managed mirror. `UI_TOOLS` selects an alternate npm prefix, `UI_CHROMIUM` identifies the provisioned browser binary and `FRAMEWORK_JAR` selects a packaged JAR. The framework verifies the Node executable pin. Provision and review the browser, Playwright package and scenario script separately. [Playwright browser installation](https://playwright.dev/docs/browsers)

The second control changes the expected confirmation after reload to “declined”. The form and browser actions stay identical. The gate requires a framework `ASSERTION_FAILED`, the specific failed browser check, and a readable trace ZIP containing the click and reload. Both canonical results must pass `validate-evidence`. The script retains results and prints the absolute `failure_trace` under `target/playwright-runs/check.*/`. Inspect it locally with `target/playwright-tools/node_modules/.bin/playwright-core show-trace /path/to/failure-trace.zip`. Traces contain screenshots, DOM snapshots and network events. Restrict their retention and access if adapting the flow to company data. [Playwright tracing](https://playwright.dev/docs/api/class-tracing)

## Use a company test page

Keep the pinned runner tools and change the target URL, accessible labels, synthetic input and expected business outcome to match the application. The sample submits order `UI-1042`. Use an isolated test environment that permits this action. Configure a new suite directory, review its concrete arguments, then run it through the existing CLI.

```bash
python3 "$sample/configure.py" --node "$UI_NODE" --node-sha256 "$UI_NODE_SHA256" \
  --tools target/playwright-tools --chromium "$UI_CHROMIUM" --url https://orders.test.example/ \
  --out target/company-order-suite
java -jar "$FRAMEWORK_JAR" run --suite target/company-order-suite/suite_manifest.yaml --profile local_browser
java -jar "$FRAMEWORK_JAR" validate-evidence --result /path/printed/as/result_json
```

The CLI runs with a 10-second process limit, literal arguments, an empty owned working directory and fixed locale environment. Hosting the app and provisioning the browser happen before the timed action. A browser check failing after tracing starts saves `failure-trace.zip` under the configured suite's `browser-artifacts/cli-work-*/` directory. A launch failure or forced deadline can leave only the CLI provider evidence. The trace is an auxiliary debugging file. The canonical framework result and `provider-evidence/cli_process` record the verdict and process observations. [Provider contract and execution limits](../../../../docs/09-operations/reviewed_cli_process.md)
