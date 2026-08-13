import { beforeEach, describe, expect, it } from 'vitest';
import {
  MAX_KITCHEN_QUEUE,
  clearKitchenOfflineQueue,
  enqueueKitchenMutation,
  kitchenQueueSize,
  readKitchenQueue,
  removeKitchenMutation,
  saveKitchenSnapshot,
  readKitchenSnapshot,
} from '../utils/kitchenOfflineQueue';

const update = { expectedVersion: 0, note: '', items: [] };

beforeEach(() => localStorage.clear());

describe('kitchen offline queue', () => {
  it('is bounded, owner-scoped and idempotency-key addressable', () => {
    for (let index = 0; index < MAX_KITCHEN_QUEUE + 5; index += 1) {
      enqueueKitchenMutation({
        idempotencyKey: `key-${index}`,
        owner: 'alice',
        listId: 'list-1',
        update,
        createdAt: new Date().toISOString(),
      });
    }
    enqueueKitchenMutation({
      idempotencyKey: 'bob-key',
      owner: 'bob',
      listId: 'list-2',
      update,
      createdAt: new Date().toISOString(),
    });
    expect(readKitchenQueue('alice')).toHaveLength(MAX_KITCHEN_QUEUE);
    expect(kitchenQueueSize('bob')).toBe(1);
    removeKitchenMutation('alice', 'key-54');
    expect(readKitchenQueue('alice').some((item) => item.idempotencyKey === 'key-54')).toBe(false);
  });

  it('stores only bounded recent snapshots and logout clears private data', () => {
    const list = {
      id: 'list',
      weekStart: '2026-08-10',
      note: '',
      version: 1,
      createdAt: '',
      updatedAt: '',
      items: [],
    };
    saveKitchenSnapshot('alice', list.weekStart, list);
    expect(readKitchenSnapshot('alice', list.weekStart)?.id).toBe('list');
    enqueueKitchenMutation({ idempotencyKey: 'key', owner: 'alice', listId: 'list', update, createdAt: '' });
    clearKitchenOfflineQueue();
    expect(readKitchenQueue('alice')).toEqual([]);
    expect(readKitchenSnapshot('alice', list.weekStart)).toBeNull();
  });
});
