import { createHash } from 'node:crypto';

import AxeBuilder from '@axe-core/playwright';
import { expect, test, type APIRequestContext } from '@playwright/test';

const enabled = process.env.E2E_FULL_STACK === 'true';

test.describe('full-stack deterministic provider contract', () => {
  test.skip(!enabled, 'Full-stack E2E runs only in the isolated CI environment.');

  test('auth, private AI lifecycle, owner isolation, and real public rendering', async ({
    page,
    request,
  }, testInfo) => {
    const clientIp = testClientIp(testInfo.project.name);
    const admin = await login(request, 'admin', 'admin-pass-12345', clientIp);
    const partner = await login(request, 'partner', 'partner-pass-12345', clientIp);

    const refresh = await request.post('/api/v1/auth/refresh');
    expect(refresh.ok()).toBe(true);
    const logout = await request.post('/api/v1/auth/logout');
    expect(logout.status()).toBe(204);

    const unauthenticatedAdminResource = await request.get('/api/v1/admin/posts');
    expect(unauthenticatedAdminResource.status()).toBe(401);

    const provider = await request.post('/api/v1/admin/ai/providers', {
      headers: admin.headers,
      data: {
        name: `fake-e2e-${Date.now()}`,
        baseUrl: 'http://127.0.0.1:8787',
        apiKey: 'fake-only',
        models: ['fake-e2e'],
        defaultModel: 'fake-e2e',
        enabled: true,
        dailyRequestLimit: 20,
        dailyTokenLimit: 10000,
        providerType: 'OPENAI_RESPONSES',
        modelCapabilities: [
          {
            model: 'fake-e2e',
            capabilities: ['TEXT', 'FILE_INPUT', 'VISION', 'STRUCTURED_OUTPUT', 'TOOL_CALLING'],
            reasoningEfforts: ['none'],
            enabled: true,
          },
        ],
      },
    });
    expect(provider.ok()).toBe(true);
    const providerId = (await provider.json()).data.id;

    const upload = await request.post('/api/v1/ai/files', {
      headers: admin.headers,
      multipart: {
        file: {
          name: 'e2e-note.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('deterministic file input for the isolated E2E run', 'utf8'),
        },
      },
    });
    const uploadBody = await upload.text();
    if (!upload.ok()) {
      throw new Error(`file upload failed (${upload.status()}): ${uploadBody}`);
    }
    const fileId = JSON.parse(uploadBody).data.id;

    const idempotencyKey = `e2e-${Date.now()}`;
    const taskCreate = await request.post('/api/v1/ai/tasks', {
      headers: admin.headers,
      data: {
        providerId,
        taskType: 'CHAT',
        idempotencyKey,
        parts: [
          { kind: 'TEXT', text: '请读取附件并给出确定性回答。' },
          { kind: 'FILE_REF', fileId },
        ],
      },
    });
    expect(taskCreate.ok()).toBe(true);
    const task = (await taskCreate.json()).data;

    const idempotentRetry = await request.post('/api/v1/ai/tasks', {
      headers: admin.headers,
      data: {
        providerId,
        taskType: 'CHAT',
        idempotencyKey,
        parts: [{ kind: 'TEXT', text: 'must not create a second task' }],
      },
    });
    expect(idempotentRetry.ok()).toBe(true);
    expect((await idempotentRetry.json()).data.id).toBe(task.id);

    const run = await request.post(`/api/v1/ai/tasks/${task.id}/run`, { headers: admin.headers });
    expect(run.ok()).toBe(true);
    await expect
      .poll(async () => (await request.get(`/api/v1/ai/tasks/${task.id}`, { headers: admin.headers })).json())
      .toMatchObject({
        data: { status: 'COMPLETED' },
      });

    const events = await request.get(`/api/v1/ai/tasks/${task.id}/events?afterSequence=0`, {
      headers: admin.headers,
    });
    expect(events.ok()).toBe(true);
    expect((await events.json()).data.map((event: { eventType: string }) => event.eventType)).toEqual(
      expect.arrayContaining(['task.queued', 'task.started', 'task.completed']),
    );

    const proposedMemory = await request.post('/api/v1/ai/memories', {
      headers: admin.headers,
      data: {
        scope: 'USER',
        kind: 'PREFERENCE',
        content: '用户偏好简洁回答',
        sourceTaskId: task.id,
      },
    });
    expect(proposedMemory.ok()).toBe(true);
    const memoryId = (await proposedMemory.json()).data.id;
    expect(
      (await request.post(`/api/v1/ai/memories/${memoryId}/confirm`, { headers: admin.headers })).ok(),
    ).toBe(true);

    const artifact = await request.post(`/api/v1/ai/tasks/${task.id}/artifacts`, {
      headers: admin.headers,
      data: { name: 'e2e-answer.md', format: 'MARKDOWN' },
    });
    expect(artifact.ok()).toBe(true);
    const artifactId = (await artifact.json()).data.id;
    const download = await request.get(`/api/v1/ai/artifacts/${artifactId}/download`, {
      headers: admin.headers,
    });
    expect(download.ok()).toBe(true);
    expect(await download.text()).toContain('deterministic fake provider answer');

    const partnerCannotReadFile = await request.get(`/api/v1/ai/files/${fileId}`, {
      headers: partner.headers,
    });
    expect(partnerCannotReadFile.status()).toBe(404);
    const partnerCannotReadTask = await request.get(`/api/v1/ai/tasks/${task.id}`, {
      headers: partner.headers,
    });
    expect(partnerCannotReadTask.status()).toBe(404);

    await page.goto('/articles');
    await expect(page.locator('.offline-reading-banner')).toHaveCount(0);
    await expect(page.locator('.content-unavailable')).toHaveCount(0);
  });

  test('authenticated admin, editor, AI, media, and graph routes have no severe axe violations', async ({
    page,
    request,
  }, testInfo) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const admin = await login(request, 'admin', 'admin-pass-12345', testClientIp(testInfo.project.name));
    await page.addInitScript((session) => {
      window.sessionStorage.setItem('yubai-admin-token', session.token);
      window.sessionStorage.setItem('yubai-admin-name', session.username);
      window.sessionStorage.setItem('yubai-admin-expiry', session.expiresAt);
      window.sessionStorage.setItem('yubai-admin-role', session.role ?? 'ADMIN');
      window.sessionStorage.setItem('yubai-admin-display', session.displayName ?? 'Full-stack E2E admin');
      window.sessionStorage.setItem('yubai-admin-capabilities', JSON.stringify(session.capabilities ?? []));
    }, admin.session);

    const routes = [
      '/admin',
      '/admin/notes',
      '/admin/ai',
      '/admin/attachments',
      '/admin/media',
      '/admin/graph',
    ];
    const failures: string[] = [];
    for (const route of routes) {
      await test.step(`audit ${route}`, async () => {
        await page.goto(route, { waitUntil: 'domcontentloaded' });
        const rootSelector =
          route === '/admin/notes'
            ? '.notes-studio'
            : route === '/admin/ai'
              ? '.ai-workspace'
              : '.admin-console';
        await expect(page.locator('.site-shell.admin-mode')).toHaveCount(1);
        await expect(page.locator(rootSelector)).toHaveCount(1, { timeout: 10_000 });
        await page.keyboard.press('Tab');
        await expect(page.locator(':focus')).toHaveCount(1);

        const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
        const severe = results.violations.filter((violation) =>
          ['serious', 'critical'].includes(violation.impact ?? ''),
        );
        if (severe.length) {
          failures.push(
            `${route}: ${JSON.stringify({
              violations: severe.map((violation) => ({
                id: violation.id,
                nodes: violation.nodes.map((node) => node.target),
              })),
            })}`,
          );
        }
      });
    }
    expect(failures.join('\n')).toBe('');
  });
});

function testClientIp(projectName: string) {
  return projectName.includes('firefox') ? '198.51.100.12' : '198.51.100.11';
}

async function login(request: APIRequestContext, username: string, password: string, clientIp: string) {
  const headers = { 'X-Forwarded-For': clientIp };
  const challengeResponse = await request.get(
    `/api/v1/auth/challenge?username=${encodeURIComponent(username)}`,
    { headers },
  );
  expect(challengeResponse.ok()).toBe(true);
  const challenge = (await challengeResponse.json()).data;
  const nonce = solvePow(challenge.salt, challenge.difficulty);
  const response = await request.post('/api/v1/auth/login', {
    headers,
    data: {
      username,
      password,
      challengeId: challenge.challengeId,
      nonce,
    },
  });
  const body = await response.text();
  if (!response.ok()) {
    throw new Error(`${username} login failed (${response.status()}): ${body}`);
  }
  const session = JSON.parse(body).data as {
    token: string;
    username: string;
    expiresAt: string;
    role?: string;
    displayName?: string;
    capabilities?: string[];
  };
  return { token: session.token, headers: { Authorization: `Bearer ${session.token}` }, session };
}

function solvePow(salt: string, difficulty: number) {
  const prefix = '0'.repeat(difficulty);
  for (let nonce = 0; ; nonce += 1) {
    const digest = createHash('sha256').update(`${salt}${nonce}`).digest('hex');
    if (digest.startsWith(prefix)) return String(nonce);
  }
}
