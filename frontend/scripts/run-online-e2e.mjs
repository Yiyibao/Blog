import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';

if (!process.env.E2E_BASE_URL) {
  console.error(
    'E2E_BASE_URL is required for online E2E; refusing to run against the offline preview shell.',
  );
  process.exit(2);
}

const command = process.platform === 'win32' ? process.execPath : 'npx';
const args =
  process.platform === 'win32'
    ? [resolve(dirname(process.execPath), 'node_modules', 'npm', 'bin', 'npx-cli.js')]
    : [];
const result = spawnSync(
  command,
  [
    ...args,
    'playwright',
    'test',
    '--project=online-chromium',
    '--project=online-firefox',
    '--project=online-mobile-chromium',
  ],
  {
    stdio: 'inherit',
    env: process.env,
  },
);
process.exit(result.status ?? 1);
