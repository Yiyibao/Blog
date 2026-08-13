import { createWriteStream } from 'node:fs';
import { mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const frontendDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repositoryRoot = resolve(frontendDir, '..');
const backendDir = resolve(repositoryRoot, 'backend');
const tempDir = process.env.RUNNER_TEMP || resolve(frontendDir, '.full-stack-e2e');
const nodeCommand = process.execPath;
const viteCli = resolve(frontendDir, 'node_modules', 'vite', 'bin', 'vite.js');
const playwrightCli = resolve(frontendDir, 'node_modules', '@playwright', 'test', 'cli.js');
const logs = new Map();
const children = [];

async function main() {
  await mkdir(tempDir, { recursive: true });
  const backendJar = (await readdir(resolve(backendDir, 'target')))
    .filter((name) => name.endsWith('.jar') && !name.endsWith('.jar.original'))
    .map((name) => resolve(backendDir, 'target', name))[0];
  if (!backendJar) throw new Error('No backend jar found; build the backend before full-stack E2E.');

  const stackEnv = {
    ...process.env,
    APP_JWT_SECRET: 'full-stack-e2e-jwt-secret-32-characters-long!',
    APP_ADMIN_USERNAME: 'admin',
    APP_ADMIN_PASSWORD: 'admin-pass-12345',
    APP_ADMIN_DISPLAY_NAME: 'Full-stack E2E admin',
    APP_PARTNER_USERNAME: 'partner',
    APP_PARTNER_PASSWORD: 'partner-pass-12345',
    APP_PARTNER_DISPLAY_NAME: 'Full-stack E2E partner',
    APP_AI_MASTER_KEY: 'full-stack-e2e-master-key-32-characters!',
    APP_JWT_COOKIE_SECURE: 'false',
    APP_CORS_ALLOWED_ORIGINS: 'http://127.0.0.1:4173',
    APP_SITE_URL: 'http://127.0.0.1:4173',
    APP_AI_ALLOW_LOCAL_ENDPOINTS: 'true',
    APP_AI_PLATFORM_TASKS_ENABLED: 'true',
    APP_AI_PLATFORM_MULTIMODAL_ENABLED: 'true',
    APP_AI_PLATFORM_MEMORY_ENABLED: 'true',
    APP_AI_PLATFORM_ARTIFACTS_ENABLED: 'true',
    APP_ATTACHMENT_STORAGE_DIR:
      process.env.APP_ATTACHMENT_STORAGE_DIR || resolve(tempDir, 'blogdemo-attachments'),
    FAKE_PROVIDER_PORT: '8787',
    FAKE_PROVIDER_LOG: resolve(tempDir, 'fake-provider.log'),
  };

  start(nodeCommand, ['e2e/fake-provider.mjs'], frontendDir, stackEnv, 'fake-provider');
  start('java', ['-jar', backendJar], backendDir, stackEnv, 'backend');
  start(nodeCommand, [viteCli, '--host', '127.0.0.1', '--port', '4173'], frontendDir, stackEnv, 'frontend');

  await waitFor('http://127.0.0.1:8080/actuator/health');
  await waitFor('http://127.0.0.1:8787/health');
  await waitFor('http://127.0.0.1:4173/');

  await run(
    nodeCommand,
    [
      playwrightCli,
      'test',
      '--project=online-chromium',
      '--project=online-firefox',
      '--project=online-mobile-chromium',
      '--reporter=line',
    ],
    frontendDir,
    { ...stackEnv, E2E_BASE_URL: 'http://127.0.0.1:4173', E2E_FULL_STACK: 'true' },
  );
}

function start(command, args, cwd, env, name) {
  const output = createWriteStream(resolve(tempDir, `${name}.log`), { flags: 'a' });
  const error = createWriteStream(resolve(tempDir, `${name}.stderr.log`), { flags: 'a' });
  logs.set(name, [output, error]);
  const child = spawn(command, args, {
    cwd,
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    shell: false,
  });
  child.stdout.pipe(output);
  child.stderr.pipe(error);
  children.push(child);
}

function run(command, args, cwd, env) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, {
      cwd,
      env,
      stdio: 'inherit',
      windowsHide: true,
      shell: false,
    });
    child.on('error', reject);
    child.on('exit', (code, signal) => {
      if (code === 0) resolvePromise();
      else reject(new Error(`full-stack E2E exited with code=${code} signal=${signal ?? 'none'}`));
    });
  });
}

async function waitFor(url) {
  for (let attempt = 0; attempt < 90; attempt += 1) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(2000) });
      if (response.ok) return;
    } catch {
      // The process is still starting; keep the bounded readiness window.
    }
    await delay(2000);
  }
  throw new Error(`Timed out waiting for ${url}`);
}

async function shutdown() {
  for (const child of children) child.kill('SIGTERM');
  await delay(500);
  for (const child of children) {
    if (process.platform === 'win32' && child.pid) {
      spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], {
        stdio: 'ignore',
        windowsHide: true,
      });
    } else if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGKILL');
    }
  }
  for (const pair of logs.values()) for (const stream of pair) stream.end();
}

try {
  await main();
} finally {
  await shutdown();
}
