import { describe, expect, it, vi } from 'vitest';

const mockedClient = vi.hoisted(() => ({
  post: vi.fn(),
}));

vi.mock('../api/admin-client', () => ({
  api: mockedClient,
  tokenHeader: () => ({}),
  unwrap: async <T>(request: Promise<{ data: { data: T } }>) => (await request).data.data,
}));

import { AI_TASK_RUN_TIMEOUT_MS, runAiTask } from '../api/ai';

describe('AI API', () => {
  it('allows a task run to wait for the provider response', async () => {
    const task = { id: 'task-1', status: 'COMPLETED' };
    mockedClient.post.mockResolvedValueOnce({ data: { data: task } });

    await expect(runAiTask(task.id)).resolves.toEqual(task);
    expect(mockedClient.post).toHaveBeenCalledWith('/ai/tasks/task-1/run', undefined, {
      timeout: AI_TASK_RUN_TIMEOUT_MS,
    });
  });
});
