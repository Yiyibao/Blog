import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = resolve(fileURLToPath(new URL('..', import.meta.url)));
const migrationsDir = resolve(repositoryRoot, 'backend/src/main/resources/db/migration');
const manifestPath = resolve(repositoryRoot, 'docs/migrations/flyway-content-manifest.sha256');
const compatibilityPath = resolve(repositoryRoot, 'deploy/release-compatibility.env');

const manifestText = await readFile(manifestPath, 'utf8');
const expected = new Map();
for (const line of manifestText.split(/\r?\n/)) {
  if (!line.trim() || line.trim().startsWith('#')) continue;
  const match = line.match(/^([a-f0-9]{64})\s{2}(.+)$/i);
  if (!match) throw new Error(`Invalid migration manifest line: ${line}`);
  expected.set(match[2], match[1].toLowerCase());
}

const names = (await readdir(migrationsDir))
  .filter((name) => /^V\d+__.+\.sql$/.test(name))
  .sort((left, right) => Number(left.match(/^V(\d+)/)[1]) - Number(right.match(/^V(\d+)/)[1]));
const actualPaths = new Set(names.map((name) => relative(repositoryRoot, resolve(migrationsDir, name)).replaceAll('\\', '/')));
const expectedPaths = new Set(expected.keys());

const missing = [...expectedPaths].filter((path) => !actualPaths.has(path));
const unexpected = [...actualPaths].filter((path) => !expectedPaths.has(path));
if (missing.length || unexpected.length) {
  throw new Error(`Migration manifest set mismatch. Missing: ${missing.join(', ')}; unexpected: ${unexpected.join(', ')}`);
}

for (const path of actualPaths) {
  const bytes = await readFile(resolve(repositoryRoot, path));
  const actualHash = createHash('sha256').update(bytes).digest('hex');
  if (actualHash !== expected.get(path)) {
    throw new Error(`Migration content changed without manifest update: ${path}`);
  }
}

const versions = names.map((name) => Number(name.match(/^V(\d+)/)[1]));
const compatibility = Object.fromEntries(
  (await readFile(compatibilityPath, 'utf8'))
    .split(/\r?\n/)
    .filter((line) => line.trim() && !line.trim().startsWith('#'))
    .map((line) => {
      const index = line.indexOf('=');
      if (index <= 0) throw new Error(`Invalid compatibility contract line: ${line}`);
      return [line.slice(0, index), line.slice(index + 1)];
    }),
);
const targetVersion = Number(compatibility.SCHEMA_TARGET);
if (versions.at(-1) !== targetVersion) {
  throw new Error(`Compatibility target V${targetVersion} differs from latest migration V${versions.at(-1)}`);
}
if (compatibility.MIGRATION_MODE !== 'expand-only') {
  throw new Error('Current release must declare an expand-only rollback window');
}

console.log(`Migration manifest verified: ${names.length} immutable files through V${targetVersion}.`);
