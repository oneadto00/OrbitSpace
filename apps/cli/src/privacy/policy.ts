import { createHash } from 'node:crypto';

export const maxFile = 1024 * 1024;
export const maxPatch = 8 * 1024 * 1024;
export const maxSharedFiles = 512;
export const digest = (value: string | Buffer) => createHash('sha256').update(value).digest('hex');

const secret = /-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----|\b(?:AKIA|ASIA)[A-Z0-9]{16}\b|\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-(?:proj-)?[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{12,})|\b(?:aws_secret_access_key|api[_-]?key|access[_-]?token|client[_-]?secret|password)\s*[:=]\s*["']?[^\s"']{8,}/i;

const issuedToken = /\b(?:od_[A-Za-z0-9_-]{43}|glpat-[A-Za-z0-9_-]{20,}|npm_[A-Za-z0-9]{36,}|eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{16,})\b/;

export function secretLine(text: string): number | undefined {
  const i = text.split('\n').findIndex(line => secret.test(line) || issuedToken.test(line));
  return i < 0 ? undefined : i + 1;
}

export function privatePath(path: string): boolean {
  return /(?:^|\/)(?:\.env[^/]*|\.ssh|\.aws|\.gnupg|\.npmrc|\.netrc|\.gitcredentials|\.git-credentials|\.docker\/config\.json|credentials(?:\.[^/]+)?|id_(?:rsa|dsa|ed25519|ecdsa)(?:\.[^/]+)?|[^/]*\.(?:pem|key|p12|pfx|keystore))(?:\/|$)/i.test(path) || !!secretLine(path);
}

export function printable(value: string): string {
  return JSON.stringify(value).replace(/[\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, ch => '\\u' + ch.charCodeAt(0).toString(16).padStart(4, '0'));
}

// Wire values contain only JSON primitives, arrays and records. RFC 8785 orders
// object keys by UTF-16 code units and uses ECMAScript's primitive serialization.
export function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    return '{' + Object.keys(record).sort().map(k => JSON.stringify(k) + ':' + canonical(record[k])).join(',') + '}';
  }
  if (value === undefined || (typeof value === 'number' && !Number.isFinite(value))) throw new Error('无效的载荷值');
  return JSON.stringify(value);
}
