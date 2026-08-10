import axios from 'axios';
import { useAuthStore } from '../stores/auth';
import type { LoginResult } from '../stores/auth';

export type { LoginResult };

export interface ApiEnvelope<T> {
  data: T;
  timestamp: string;
}

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 8000,
  headers: { Accept: 'application/json' },
  withCredentials: true,
});

let refreshPromise: Promise<LoginResult | null> | null = null;

function requestRefresh() {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      const gen = useAuthStore().getGeneration();
      try {
        const base = import.meta.env.VITE_API_BASE_URL || '/api/v1';
        const res = await axios.post<ApiEnvelope<LoginResult>>(`${base}/auth/refresh`, null, {
          withCredentials: true,
          timeout: 8000,
        });
        const result = res.data.data;
        if (useAuthStore().isCurrentGeneration(gen)) {
          useAuthStore().saveSession(result);
          return result;
        }
        return null;
      } catch {
        if (useAuthStore().isCurrentGeneration(gen)) useAuthStore().clearSession();
        return null;
      } finally {
        refreshPromise = null;
      }
    })();
  }
  return refreshPromise;
}

api.interceptors.request.use((config) => {
  const auth = useAuthStore();
  if (auth.token) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = `Bearer ${auth.token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (!axios.isAxiosError(error) || error.response?.status !== 401) return Promise.reject(error);
    const url = error.config?.url ?? '';
    if (
      url.includes('/auth/login') ||
      url.includes('/auth/challenge') ||
      url.includes('/auth/refresh') ||
      url.includes('/auth/logout') ||
      url.includes('/auth/totp/')
    ) {
      return Promise.reject(error);
    }
    const cfg = error.config as Record<string, unknown> | undefined;
    if (cfg?._retry) {
      useAuthStore().clearSession();
      return Promise.reject(error);
    }
    const result = await requestRefresh();
    if (!result) return Promise.reject(error);
    cfg!._retry = true;
    return api.request(error.config!);
  },
);

export async function refreshSession(): Promise<boolean> {
  return (await requestRefresh()) !== null;
}

export function clearAdminSession() {
  useAuthStore().clearSession();
}

export function logout() {
  axios
    .post(`${import.meta.env.VITE_API_BASE_URL || '/api/v1'}/auth/logout`, null, { withCredentials: true })
    .catch(() => {});
  useAuthStore().clearSession();
}

export function saveAdminSession(result: LoginResult) {
  useAuthStore().saveSession(result);
}

export function getAdminSessionName() {
  return useAuthStore().username;
}

export function hasValidAdminSession() {
  return useAuthStore().isAuthenticated;
}

export function tokenHeader() {
  const token = useAuthStore().token;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function unwrap<T>(request: Promise<{ data: ApiEnvelope<T> }>): Promise<T> {
  return (await request).data.data;
}
