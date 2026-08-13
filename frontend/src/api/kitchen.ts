import axios from 'axios';
import { useAuthStore } from '../stores/auth';
import type { KitchenQueuePayload } from '../utils/kitchenOfflineQueue';

/**
 * FD-12：kitchen（今日菜单/打卡）专用 API 实例——站内第三条语义线：
 * content.ts = 匿名读写；admin.ts = 管理写（401 后由后台流程处理）；
 * kitchen.ts = 已登录双角色写。与 admin.ts 的三点差异：
 * ① 过期会话不 reject axios.Cancel（形状污染调用方错误处理），reject 分类错误；
 * ② 401 清会话但不导航——美食页匿名也能看，降级为只读即可，跳登录页由交互层决定；
 * ③ 提供 classifyError / retryAfterSeconds，组件按类别给文案。
 */

export type MealSlot = 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK';
export type MenuStatus = 'DRAFT' | 'CONFIRMED';

export interface MenuItem {
  id: number;
  dishId: number | null;
  dishSlug: string | null;
  title: string;
  mealSlot: MealSlot;
  note: string;
  sortOrder: number;
  authorId: number;
  authorName: string;
  createdAt: string;
}

export interface DailyMenu {
  exists: boolean;
  date: string;
  status: MenuStatus;
  note: string;
  version: number | null;
  items: MenuItem[];
  updatedBy: number | null;
  updatedAt: string | null;
}

export interface MenuItemDraft {
  dishSlug?: string;
  title?: string;
  mealSlot: MealSlot;
  note?: string;
}

export interface MenuItemUpsert {
  id?: number;
  dishSlug?: string;
  title?: string;
  mealSlot: MealSlot;
  note?: string;
}

export interface DailyMenuPut {
  status: MenuStatus;
  note: string;
  expectedVersion: number;
  items: MenuItemUpsert[];
}

export interface MenuHistoryEntry {
  date: string;
  status: MenuStatus;
  note: string;
  itemCount: number;
  updatedAt: string;
}

export type KitchenErrorKind =
  'auth' | 'forbidden' | 'not-found' | 'conflict' | 'rate-limited' | 'validation' | 'network' | 'server';

export interface KitchenError {
  kind: KitchenErrorKind;
  message: string;
  retryAfterSeconds?: number;
}

interface ApiEnvelope<T> {
  data: T;
  timestamp: string;
}

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 8000,
  headers: { Accept: 'application/json' },
});

api.interceptors.request.use((config) => {
  const auth = useAuthStore();
  if (auth.token && auth.expiresAt && Date.parse(auth.expiresAt) <= Date.now()) {
    auth.clearSession();
    // 差异①：不抛 axios.Cancel（其形状会污染统一错误处理），抛已分类错误
    return Promise.reject<never>(classified('auth', '登录已过期，请重新登录'));
  }
  if (auth.token) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = `Bearer ${auth.token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    // 差异②：401 清会话但不导航（页面自会降级为匿名只读）
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      useAuthStore().clearSession();
    }
    return Promise.reject(error);
  },
);

function classified(kind: KitchenErrorKind, message: string, retryAfterSeconds?: number): KitchenError {
  return { kind, message, ...(retryAfterSeconds !== undefined ? { retryAfterSeconds } : {}) };
}

export function isKitchenError(value: unknown): value is KitchenError {
  return typeof value === 'object' && value !== null && 'kind' in value && 'message' in value;
}

/** 把任意抛出物归一为 KitchenError，组件据 kind 给文案。 */
export function classifyError(cause: unknown): KitchenError {
  if (isKitchenError(cause)) return cause;
  if (axios.isAxiosError(cause)) {
    const status = cause.response?.status;
    const serverMessage = (cause.response?.data as { message?: string } | undefined)?.message;
    if (status === 401) return classified('auth', '登录已失效，请重新登录');
    if (status === 403) return classified('forbidden', serverMessage || '没有权限做这个操作');
    if (status === 404) return classified('not-found', serverMessage || '内容不存在或已被移除');
    if (status === 409) return classified('conflict', serverMessage || '内容刚被对方更新过，请刷新后重试');
    if (status === 429) {
      const header = cause.response?.headers?.['retry-after'];
      const parsed = typeof header === 'string' ? Number.parseInt(header, 10) : NaN;
      return classified(
        'rate-limited',
        serverMessage || '操作太频繁啦，稍后再试',
        Number.isFinite(parsed) ? parsed : 60,
      );
    }
    if (status === 400) return classified('validation', serverMessage || '内容没通过校验，检查一下再提交');
    if (cause.response) return classified('server', `服务暂时不可用（${status}），稍后再试`);
    return classified('network', '连不上服务器，检查一下网络');
  }
  return classified('server', '出了点问题，稍后再试');
}

async function unwrap<T>(request: Promise<{ data: ApiEnvelope<T> }>): Promise<T> {
  return (await request).data.data;
}

export function fetchDailyMenu(date: string) {
  return unwrap<DailyMenu>(api.get('/kitchen/menus', { params: { date } }));
}

export interface MenuHistoryPage {
  items: MenuHistoryEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export function fetchMenuHistory(page = 0, size = 20, from?: string, to?: string) {
  return unwrap<MenuHistoryPage>(
    api.get('/kitchen/menus/history', {
      params: { page, size, ...(from ? { from } : {}), ...(to ? { to } : {}) },
    }),
  );
}

export function appendMenuItem(date: string, draft: MenuItemDraft) {
  return unwrap<DailyMenu>(api.post('/kitchen/menus/items', draft, { params: { date } }));
}

export function putDailyMenu(date: string, payload: DailyMenuPut) {
  return unwrap<DailyMenu>(api.put('/kitchen/menus', payload, { params: { date } }));
}

export function deleteMenuItem(id: number) {
  return unwrap<DailyMenu>(api.delete(`/kitchen/menus/items/${id}`));
}

// ---- FD-15/17/18/19：打卡与聚合 ----

export interface MealLog {
  id: number;
  logDate: string;
  dishId: number | null;
  dishSlug: string | null;
  title: string;
  mealSlot: MealSlot;
  rating: number | null;
  note: string;
  authorId: number;
  authorName: string;
  createdAt: string;
}

export interface MealLogDraft {
  dishSlug?: string;
  title?: string;
  mealSlot: MealSlot;
  logDate: string;
  rating?: number;
  note?: string;
}

export interface MealLogPage {
  items: MealLog[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface DishCookStat {
  dishId: number;
  slug: string;
  cookCount: number;
  lastCookedAt: string;
}

export function fetchMealLogs(
  page = 0,
  size = 20,
  filters: { from?: string; to?: string; dishSlug?: string } = {},
) {
  return unwrap<MealLogPage>(
    api.get('/kitchen/meal-logs', {
      params: {
        page,
        size,
        ...(filters.from ? { from: filters.from } : {}),
        ...(filters.to ? { to: filters.to } : {}),
        ...(filters.dishSlug ? { dishSlug: filters.dishSlug } : {}),
      },
    }),
  );
}

export function createMealLog(draft: MealLogDraft) {
  return unwrap<MealLog>(api.post('/kitchen/meal-logs', draft));
}

/** 一键打卡整桌菜（服务端按同日同名同餐次幂等去重），返回本次新记的条目。 */
export function checkInMenu(date: string) {
  return unwrap<MealLog[]>(api.post('/kitchen/menus/check-in', null, { params: { date } }));
}

export function deleteMealLog(id: number) {
  return api.delete(`/kitchen/meal-logs/${id}`);
}

/** "我们做过 N 次"聚合——FD-19 榜单主口径数据源。 */
export function fetchDishStats() {
  return unwrap<DishCookStat[]>(api.get('/kitchen/dish-stats'));
}

// ---- M11：持久化周购物清单 ----

export interface ShoppingListItem {
  id: string;
  displayName: string;
  normalizedName: string;
  quantity: number | null;
  unit: string;
  originalQuantity: string;
  sourceRecipe: string;
  category: string;
  checked: boolean;
  manual: boolean;
  note: string;
  sortOrder: number;
  createdAt: string;
}

export interface ShoppingList {
  id: string;
  weekStart: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  items: ShoppingListItem[];
}

export interface ShoppingListItemDraft {
  id?: string;
  displayName: string;
  normalizedName: string;
  quantity?: number | null;
  unit?: string;
  originalQuantity?: string;
  sourceRecipe?: string;
  category?: string;
  checked?: boolean;
  manual?: boolean;
  note?: string;
}

export interface ShoppingListUpdate {
  expectedVersion: number;
  note: string;
  items: ShoppingListItemDraft[];
}

export function fetchShoppingList(weekStart: string) {
  return unwrap<ShoppingList>(api.get('/kitchen/shopping-lists', { params: { weekStart } }));
}

export function generateShoppingList(weekStart: string, idempotencyKey: string = crypto.randomUUID()) {
  return unwrap<ShoppingList>(
    api.post('/kitchen/shopping-lists/generate', null, {
      params: { weekStart },
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  );
}

export function updateShoppingList(
  listId: string,
  payload: ShoppingListUpdate,
  idempotencyKey: string = crypto.randomUUID(),
) {
  return unwrap<ShoppingList>(
    api.put(`/kitchen/shopping-lists/${encodeURIComponent(listId)}`, payload, {
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  );
}

export function clearCheckedShoppingList(
  listId: string,
  expectedVersion: number,
  idempotencyKey: string = crypto.randomUUID(),
) {
  return unwrap<ShoppingList>(
    api.post(`/kitchen/shopping-lists/${encodeURIComponent(listId)}/clear-checked`, null, {
      params: { expectedVersion },
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  );
}

export async function replayShoppingListMutation(payload: KitchenQueuePayload) {
  return updateShoppingList(payload.listId, payload.update, payload.idempotencyKey);
}
