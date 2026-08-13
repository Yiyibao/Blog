import type { ShoppingList, ShoppingListUpdate } from '../api/kitchen';

const QUEUE_KEY = 'yubai:kitchen:offline-queue';
const SNAPSHOT_KEY = 'yubai:kitchen:snapshots';
export const MAX_KITCHEN_QUEUE = 50;

export interface KitchenQueuePayload {
  idempotencyKey: string;
  owner: string;
  listId: string;
  update: ShoppingListUpdate;
  createdAt: string;
}

function read<T>(key: string, fallback: T): T {
  try {
    const value = window.localStorage.getItem(key);
    return value ? (JSON.parse(value) as T) : fallback;
  } catch {
    return fallback;
  }
}

function write(key: string, value: unknown) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Private browsing/storage quota failures must not break kitchen reads.
  }
}

export function readKitchenQueue(owner: string): KitchenQueuePayload[] {
  return read<KitchenQueuePayload[]>(QUEUE_KEY, [])
    .filter((item) => item.owner === owner)
    .slice(-MAX_KITCHEN_QUEUE);
}

export function enqueueKitchenMutation(payload: KitchenQueuePayload) {
  const all = read<KitchenQueuePayload[]>(QUEUE_KEY, []).filter(
    (item) => !(item.owner === payload.owner && item.idempotencyKey === payload.idempotencyKey),
  );
  all.push(payload);
  const owners = [...new Set(all.map((item) => item.owner))];
  write(
    QUEUE_KEY,
    owners.flatMap((owner) => all.filter((item) => item.owner === owner).slice(-MAX_KITCHEN_QUEUE)),
  );
}

export function removeKitchenMutation(owner: string, idempotencyKey: string) {
  const all = read<KitchenQueuePayload[]>(QUEUE_KEY, []).filter(
    (item) => !(item.owner === owner && item.idempotencyKey === idempotencyKey),
  );
  write(QUEUE_KEY, all);
}

export function clearKitchenOfflineQueue() {
  try {
    window.localStorage.removeItem(QUEUE_KEY);
    window.localStorage.removeItem(SNAPSHOT_KEY);
  } catch {
    // Ignore storage failures during logout.
  }
}

export function saveKitchenSnapshot(owner: string, weekStart: string, snapshot: ShoppingList) {
  const all = read<Record<string, ShoppingList>>(SNAPSHOT_KEY, {});
  all[`${owner}:${weekStart}`] = snapshot;
  const entries = Object.entries(all).slice(-20);
  write(SNAPSHOT_KEY, Object.fromEntries(entries));
}

export function readKitchenSnapshot(owner: string, weekStart: string): ShoppingList | null {
  return read<Record<string, ShoppingList>>(SNAPSHOT_KEY, {})[`${owner}:${weekStart}`] ?? null;
}

export function kitchenQueueSize(owner: string) {
  return readKitchenQueue(owner).length;
}
