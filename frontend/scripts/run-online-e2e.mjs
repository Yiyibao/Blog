import { spawnSync } from 'node:child_process'

if (!process.env.E2E_BASE_URL) {
  console.error('E2E_BASE_URL is required for online E2E; refusing to run against the offline preview shell.')
  process.exit(2)
}

const command = process.platform === 'win32' ? 'npx.cmd' : 'npx'
const result = spawnSync(command, ['playwright', 'test', '--project=online-chromium'], {
  stdio: 'inherit',
  env: process.env,
})
process.exit(result.status ?? 1)
