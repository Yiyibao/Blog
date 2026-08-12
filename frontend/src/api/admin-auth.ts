import type { LoginResult } from '../stores/auth';
import { api, tokenHeader, unwrap } from './admin-client';
import type { ApiEnvelope } from './admin-client';

/** L-7：登录人机验证 challenge；type 为 IMAGE 时需展示 captchaImage 并提交答案。 */
export interface LoginChallenge {
  challengeId: string;
  type: 'POW' | 'IMAGE';
  salt: string;
  difficulty: number;
  captchaImage: string | null;
}

export interface LoginVerification {
  challengeId: string;
  nonce: string;
  captchaAnswer?: string;
}

export function fetchLoginChallenge(username?: string) {
  return unwrap<LoginChallenge>(api.get('/auth/challenge', { params: username ? { username } : undefined }));
}

// FD-25：自助改密——成功后服务端推进 sessions_valid_from，本端应清会话重登
export function changePassword(currentPassword: string, newPassword: string) {
  return api.put('/auth/password', { currentPassword, newPassword });
}

// 6C-3：TOTP 两步验证
export interface TotpStatus {
  enabled: boolean;
}

export interface TotpSetupResult {
  secret: string;
  otpauthUri: string;
}

export interface TotpLoginChallenge {
  totpRequired: true;
  challengeId: string;
}

export function fetchTotpStatus() {
  return unwrap<TotpStatus>(api.get('/auth/totp/status', { headers: tokenHeader() }));
}

export function setupTotp(currentPassword: string) {
  return unwrap<TotpSetupResult>(
    api.post('/auth/totp/setup', { currentPassword }, { headers: tokenHeader() }),
  );
}

export function enableTotp(code: string) {
  return api.post('/auth/totp/enable', { code }, { headers: tokenHeader() });
}

export function disableTotp(currentPassword: string, code: string) {
  return api.post('/auth/totp/disable', { currentPassword, code }, { headers: tokenHeader() });
}

export function verifyTotp(challengeId: string, code: string) {
  return unwrap<LoginResult>(api.post('/auth/totp/verify', { challengeId, code }));
}

// FD-9：remember=true 请求 24h 长 refresh token（HttpOnly cookie），跨会话由 cookie 恢复
export async function login(
  username: string,
  password: string,
  verification: LoginVerification,
  remember = false,
): Promise<LoginResult | TotpLoginChallenge> {
  const response = await api.post<ApiEnvelope<LoginResult | { challengeId: string }>>('/auth/login', {
    username,
    password,
    remember,
    ...verification,
  });
  if (response.status === 202) {
    const challenge = response.data.data as { challengeId: string };
    return { totpRequired: true, challengeId: challenge.challengeId };
  }
  return response.data.data as LoginResult;
}
