import { beforeEach, describe, expect, it, vi } from 'vitest';

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
  interceptors: {
    request: { use: vi.fn() },
    response: { use: vi.fn() },
  },
}));

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => http),
    isAxiosError: vi.fn(() => false),
  },
}));

import {
  clearCheckedShoppingList,
  fetchShoppingList,
  generateShoppingList,
  replayShoppingListMutation,
  updateShoppingList,
  type ShoppingListUpdate,
} from '../api/kitchen';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('M11 shopping-list API contract', () => {
  it('reads a week-scoped list', async () => {
    http.get.mockResolvedValue({ data: { data: { id: 'list-1' } } });

    await expect(fetchShoppingList('2026-08-10')).resolves.toEqual({ id: 'list-1' });
    expect(http.get).toHaveBeenCalledWith('/kitchen/shopping-lists', {
      params: { weekStart: '2026-08-10' },
    });
  });

  it('generates with an idempotency key', async () => {
    http.post.mockResolvedValue({ data: { data: { id: 'list-1', version: 1 } } });

    await generateShoppingList('2026-08-10', 'generate-1');
    expect(http.post).toHaveBeenCalledWith('/kitchen/shopping-lists/generate', null, {
      params: { weekStart: '2026-08-10' },
      headers: { 'Idempotency-Key': 'generate-1' },
    });
  });

  it('updates, clears checked items, and replays queued mutations', async () => {
    const update: ShoppingListUpdate = { expectedVersion: 2, note: 'buy soon', items: [] };
    http.put.mockResolvedValue({ data: { data: { id: 'list-1', version: 3 } } });
    http.post.mockResolvedValue({ data: { data: { id: 'list-1', version: 4 } } });

    await updateShoppingList('list-1', update, 'update-1');
    await clearCheckedShoppingList('list-1', 3, 'clear-1');
    await replayShoppingListMutation({
      owner: 'owner-1',
      listId: 'list-1',
      update,
      idempotencyKey: 'replay-1',
      createdAt: '2026-08-13T00:00:00Z',
    });

    expect(http.put).toHaveBeenNthCalledWith(1, '/kitchen/shopping-lists/list-1', update, {
      headers: { 'Idempotency-Key': 'update-1' },
    });
    expect(http.post).toHaveBeenCalledWith('/kitchen/shopping-lists/list-1/clear-checked', null, {
      params: { expectedVersion: 3 },
      headers: { 'Idempotency-Key': 'clear-1' },
    });
    expect(http.put).toHaveBeenNthCalledWith(2, '/kitchen/shopping-lists/list-1', update, {
      headers: { 'Idempotency-Key': 'replay-1' },
    });
  });
});
