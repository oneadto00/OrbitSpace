import { hostname } from 'node:os';
import { createInterface } from 'node:readline/promises';
import { setTimeout as delay } from 'node:timers/promises';
import { z } from 'zod';
import { printable } from '../privacy/policy.js';
import { forgetCredential, grantSchema, saveCredential, seq, type Credential } from '../state/local.js';

export function serverOrigin(value: string): string {
  const url = new URL(value);
  const loopback = ['127.0.0.1','localhost','[::1]'].includes(url.hostname);
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback) || url.username || url.password || url.href !== url.origin + '/') {
    throw new Error('服务地址必须是 HTTPS origin；本机开发可使用 HTTP 回环地址。');
  }
  return url.origin;
}

export class ApiError extends Error {
  constructor(readonly code: string, readonly status: number) { super('服务端返回 ' + code + ' (HTTP ' + status + ')'); }
}
export async function request(
  origin: string,
  path: string,
  method: string,
  body: string | URLSearchParams | Buffer,
  credential?: Credential,
  key?: string,
  extraHeaders?: Record<string, string>,
) {
  const headers: Record<string,string> = {
    ...extraHeaders,
    'Content-Type': Buffer.isBuffer(body) ? 'application/octet-stream' : typeof body === 'string' ? 'application/json' : 'application/x-www-form-urlencoded',
  };
  if (credential) {
    if (credential.origin !== origin) throw new Error('凭证与服务地址不匹配');
    headers.Authorization = 'Bearer ' + credential.token;
  }
  if (key) headers['Idempotency-Key'] = key;
  const res = await fetch(origin + path, {method, headers, body:Buffer.isBuffer(body) ? new Uint8Array(body) : body, redirect:'error', signal:AbortSignal.timeout(30000)});
  if (res.status === 204) return null;
  const json: unknown = await res.json().catch(() => null);
  if (!res.ok) {
    const result = z.object({code:z.string().regex(/^[A-Z_]{2,64}$/)}).safeParse(json);
    const code = result.success ? result.data.code : 'HTTP_ERROR';
    if (credential && ['AUTH_REVOKED','MEMBERSHIP_EPOCH_INVALID'].includes(code)) await forgetCredential(credential);
    throw new ApiError(code, res.status);
  }
  return json;
}

export function safeLink(origin: string, link: string) {
  const url = new URL(link, origin);
  if (url.origin !== origin || url.username || url.password) throw new Error('服务端返回的链接与当前服务地址不匹配。');
  return url.href;
}

export async function login(origin: string, label: string, metadataOnly: boolean) {
  const scope = metadataOnly ? 'metadata:write' : 'metadata:write patch:write';
  const auth = z.object({device_code:z.string().min(1), user_code:z.string().min(1), verification_uri:z.url(),
    interval:seq.positive(), expires_in:seq.positive()}).parse(await request(origin, '/api/v1/auth/device/authorize', 'POST',
      new URLSearchParams({client_id:'orbit-cli', device_label:label || hostname(), scope})));
  console.log('在浏览器中打开 %s，输入设备短码 %s。', safeLink(origin, auth.verification_uri), printable(auth.user_code));
  const expiry = Date.now() + auth.expires_in * 1000;
  let interval = auth.interval;
  while (Date.now() < expiry) {
    await delay(Math.min(interval * 1000, expiry - Date.now()));
    if (Date.now() >= expiry) break;
    try {
      const result = z.object({access_token:z.string().min(1), token_type:z.literal('Bearer'), grant:grantSchema}).parse(
        await request(origin, '/api/v1/auth/device/token', 'POST', new URLSearchParams({
          client_id:'orbit-cli', device_code:auth.device_code, grant_type:'urn:ietf:params:oauth:grant-type:device_code',
        })));
      await saveCredential({origin, token:result.access_token, grant:result.grant});
      console.log('已授权工程 %s。', printable(result.grant.projectId));
      return;
    } catch (e) {
      if (e instanceof ApiError && e.code === 'DEVICE_AUTH_PENDING') continue;
      if (e instanceof ApiError && (e.code === 'DEVICE_SLOW_DOWN' || e.status === 429)) { interval += 5; continue; }
      if (e instanceof TypeError || (e as Error).name === 'TimeoutError') { interval = Math.min(interval * 2, 300); continue; }
      throw e;
    }
  }
  throw new Error('设备短码已过期，请重新登录。');
}

export async function retry<T>(operation: (attempt: number) => Promise<T>) {
  for (let attempt = 0; ; attempt++) {
    try { return await operation(attempt); }
    catch (e) {
      const transient = e instanceof ApiError ? e.status >= 500 : e instanceof TypeError || (e as Error).name === 'TimeoutError';
      if (attempt >= 2 || !transient) throw e;
      await delay(500 * (2 ** attempt));
    }
  }
}

export async function confirmConsent(ttyError: string, prompt: string): Promise<boolean> {
  if (!process.stdin.isTTY || !process.stdout.isTTY) throw new Error(ttyError);
  const reader = createInterface({ input: process.stdin, output: process.stdout });
  try { return (await reader.question(prompt)).trim() === 'yes'; }
  finally { reader.close(); }
}
