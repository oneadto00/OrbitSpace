import { randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { chmod, mkdir, open, rename, unlink } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { z } from 'zod';
import type { DeviceGrant } from '@orbitspace/shared-types';

const home = join(homedir(), '.orbitspace');
const credentials = join(home, 'credentials.json');
export const statePath = (key: string) => join(home, 'worktrees', key + '.json');
const id = z.string().min(1).max(160);
export const seq = z.number().int().safe().nonnegative();
export const grantSchema = z.object({
  credentialId:id, userId:id, deviceId:id, deviceLabel:z.string(), spaceId:id, projectId:id,
  membershipEpoch:seq.positive(), scopes:z.array(z.enum(['metadata:write','patch:write'])), expiresAt:z.iso.datetime({offset:true}),
}) satisfies z.ZodType<DeviceGrant>;
export const credentialSchema = z.object({origin:z.url(), token:z.string().min(1), grant:grantSchema});
export type Credential = z.infer<typeof credentialSchema>;
export const worktreeSchema = z.object({
  projectId:id, worktreeId:id, worktreeEpoch:seq.positive(), currentCaptureSeq:seq, activeSnapshotId:id.nullable(),
});
const stateSchema = z.object({
  origin:z.url(), projectId:id, credentialId:id,
  worktree:worktreeSchema.optional(),
  // Persist operation keys before POST so a lost response can be recovered.
  registration:z.object({key:id, advanceKey:id, expected:worktreeSchema.optional()}).optional(),
  pending:z.object({captureSeq:seq.positive(), idempotencyKey:id, capturedAt:z.string(), payloadDigest:z.string(), fingerprint:z.string()}).optional(),
});
export type State = z.infer<typeof stateSchema>;

async function ensureDirectory(file: string) {
  await mkdir(dirname(file), {recursive:true, mode:0o700});
}

async function read<T>(path: string, schema: z.ZodType<T>): Promise<T | undefined> {
  try {
    const fd = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      if (process.platform !== 'win32' && ((await fd.stat()).mode & 0o077)) throw new Error('本地凭证或状态权限过宽，请将该文件权限调整为 0600。');
      return schema.parse(JSON.parse(await fd.readFile('utf8')));
    } finally { await fd.close(); }
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === 'ENOENT') return undefined;
    throw new Error('无法读取本地状态，请检查文件格式与 0600 权限。');
  }
}

export async function write(path: string, value: unknown) {
  await ensureDirectory(path);
  const temporary = path + '.' + randomUUID();
  const fd = await open(temporary, 'wx', 0o600);
  try {
    await fd.writeFile(JSON.stringify(value) + '\n');
    await fd.sync();
  } finally { await fd.close(); }
  try {
    await rename(temporary, path);
    if (process.platform !== 'win32') {
      const dir = await open(dirname(path), 'r');
      try { await dir.sync(); } finally { await dir.close(); }
    }
  } finally { await unlink(temporary).catch(() => {}); }
}

// SQLite's OS file lock is released even on SIGKILL. Never unlink the lock file
// while another process may hold or wait for its inode.
export async function locked<T>(path: string, run: () => Promise<T>): Promise<T> {
  await ensureDirectory(path);
  const {DatabaseSync} = process.getBuiltinModule('node:sqlite') as typeof import('node:sqlite');
  const lock = new DatabaseSync(path + '.lock');
  try {
    await chmod(path + '.lock', 0o600);
    try { lock.exec('PRAGMA busy_timeout=10000; BEGIN IMMEDIATE'); }
    catch { throw new Error('另一个 orbit 命令正在操作此工作树或凭证，请稍后重试。'); }
    return await run();
  } finally { lock.close(); }
}

export const readState = (path: string) => read(path, stateSchema);
const readGrants = async () => (await read(credentials, z.array(credentialSchema))) ?? [];
export async function credentialFor(origin: string, projectId: string) {
  const item = (await readGrants()).find(c => c.origin === origin && c.grant.projectId === projectId);
  if (!item || Date.parse(item.grant.expiresAt) <= Date.now()) throw new Error('此工程需要重新运行 orbit login。');
  return item;
}
export async function saveCredential(credential: Credential) {
  await locked(credentials, async () => {
    const all = await readGrants();
    await write(credentials, [...all.filter(c => !(c.origin === credential.origin && c.grant.projectId === credential.grant.projectId)), credential]);
  });
}
export async function forgetCredential(credential: Credential) {
  await locked(credentials, async () => {
    const all = await readGrants();
    await write(credentials, all.filter(c => !(c.origin === credential.origin && c.grant.credentialId === credential.grant.credentialId)));
  });
}
