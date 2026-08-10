// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { redact, redactAndTruncate, redactErrorChain, safeJsonSummary } from '../../scripts/lib/redact.mjs';

describe('统一日志脱敏（先脱敏、再截断）', () => {
  const SENTINELS = [
    'password=SUPER_SECRET_PASSWORD_123',
    'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U',
    'Basic dXNlcjpwYXNz',
    'token=SECRET_TOKEN_456',
    'api_key=APIKEY_VALUE_789',
    'Authorization: Bearer SECRET_BEARER_123',
    'Cookie: session=SECRET_SESSION_456',
  ];

  it('redact 后哨兵原值全部消失，敏感键保留键名', () => {
    const input = SENTINELS.join(' ; ');
    const out = redact(input);
    for (const sentinel of SENTINELS) {
      const valuePart = sentinel.split(/[:=]\s*/)[1] ?? sentinel;
      expect(out, `应脱敏: ${sentinel}`).not.toContain(valuePart);
    }
    expect(out).toContain('Authorization');
    expect(out).not.toContain('SECRET_BEARER_123');
    expect(out).not.toContain('SECRET_SESSION_456');
  });

  it('先脱敏再截断：截断发生在脱敏之后，敏感值不会因截断而残留', () => {
    const long = `message start password=SUPER_SECRET_PASSWORD_123 ${'x'.repeat(500)} end`;
    const out = redactAndTruncate(long, 100);
    expect(out.length).toBeLessThanOrEqual(103);
    expect(out).not.toContain('SUPER_SECRET_PASSWORD_123');
    expect(out).toContain('…');
  });

  it('JSON 摘要脱敏（嵌套敏感键）', () => {
    const value = {
      name: 'provider',
      apiKey: 'SUPER_SECRET_API_KEY_1',
      nested: { password: 'SUPER_SECRET_PASSWORD_2', url: 'https://x/api?token=TOK_9' },
    };
    const out = safeJsonSummary(value);
    expect(out).not.toContain('SUPER_SECRET_API_KEY_1');
    expect(out).not.toContain('SUPER_SECRET_PASSWORD_2');
    expect(out).not.toContain('TOK_9');
    expect(out).toContain('<redacted>');
  });

  it('异常 cause chain 脱敏（含 code/errno/syscall/port）', () => {
    const cause = Object.assign(new Error('connect ECONNREFUSED 127.0.0.1:9400'), {
      code: 'ECONNREFUSED',
      errno: -4078,
      syscall: 'connect',
      address: '127.0.0.1',
      port: 9400,
    });
    const outer = new Error('fetch failed', { cause });
    const out = redactErrorChain(outer);
    expect(out).toContain('ECONNREFUSED');
    expect(out).toContain('errno');
    expect(out).toContain('syscall');
    expect(out).toContain('connect');
    expect(out).toContain('9400');
  });

  it('异常消息中的凭据被脱敏', () => {
    const error = new Error(
      `auth failed with Authorization: Bearer SUPER_SECRET_BEARER_ABC token=SECRET_TOKEN_789`,
    );
    const out = redactErrorChain(error);
    expect(out).not.toContain('SUPER_SECRET_BEARER_ABC');
    expect(out).not.toContain('SECRET_TOKEN_789');
  });

  it('大小写与分隔符变体', () => {
    expect(redact('ApiKey=SECRET_A')).not.toContain('SECRET_A');
    expect(redact('API_KEY: SECRET_B')).not.toContain('SECRET_B');
    expect(redact('PASSWORD = SECRET_C')).not.toContain('SECRET_C');
    expect(redact('client_secret=SECRET_D')).not.toContain('SECRET_D');
  });

  it('URL 查询参数值脱敏（保留参数名）', () => {
    const out = redact('http://127.0.0.1:9223/json/new?token=SECRET_TOKEN_456&x=1');
    expect(out).not.toContain('SECRET_TOKEN_456');
    expect(out).toContain('token=<redacted>');
    expect(out).toContain('x=1');
  });
});
