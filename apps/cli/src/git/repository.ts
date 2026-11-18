import { execFile } from 'node:child_process';
import { realpath } from 'node:fs/promises';
import { promisify } from 'node:util';
import type { PushState } from '@orbitspace/shared-types';
import { digest } from '../privacy/policy.js';

const exec = promisify(execFile);
export const decode = (bytes: Buffer) => new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
export const records = (bytes: Buffer) => bytes.length ? decode(bytes).replace(/\0$/, '').split('\0') : [];

// Do not refresh/write the user's index, launch a pager, fsmonitor, or lazy-fetch objects.
export async function git(cwd: string, args: string[], allowed: number[] = [], isolated = false): Promise<Buffer> {
  const env = { ...process.env, GIT_OPTIONAL_LOCKS: '0', GIT_NO_LAZY_FETCH: '1', GIT_LITERAL_PATHSPECS: '1', GIT_TERMINAL_PROMPT: '0' };
  if (isolated) {
    for (const key of Object.keys(env)) if (key.startsWith('GIT_')) delete (env as NodeJS.ProcessEnv)[key];
    Object.assign(env, { GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null', GIT_CEILING_DIRECTORIES: cwd });
  }
  try {
    const { stdout } = await exec('git', ['--no-pager', '-c', 'core.fsmonitor=false', '-c', 'core.untrackedCache=false', ...args], {
      cwd, env, shell: false, encoding: 'buffer', maxBuffer: 48 * 1024 * 1024, timeout: 60000,
    });
    return stdout;
  } catch (e) {
    const failure = e as {code?: number; stdout?: Buffer};
    if (allowed.includes(failure.code ?? -1) && Buffer.isBuffer(failure.stdout)) return failure.stdout;
    throw new Error('Git 读取失败，请检查仓库完整性、对象可用性及采集大小后重试。');
  }
}

const oneLine = (bytes: Buffer) => decode(bytes).replace(/\n$/, '');

export async function repository() {
  const root = await realpath(oneLine(await git(process.cwd(), ['rev-parse', '--show-toplevel'])));
  const gitDir = await realpath(oneLine(await git(root, ['rev-parse', '--absolute-git-dir'])));
  return { root, key: digest(JSON.stringify([root, gitDir])) };
}

export interface Change { path: string; staged: boolean; unstaged: boolean; untracked: boolean }
export interface Entry { mode: string; oid: string; sparse?: boolean }

export function parseIndex(staged: Buffer) {
  const index = new Map<string, Entry>();
  let incompleteIndex = false;
  for (const row of records(staged)) {
    const sep = row.indexOf('\t');
    const [tag, mode, hash] = row.slice(0, sep).split(' ');
    index.set(row.slice(sep + 1), {mode: mode!, oid: hash!, sparse:tag?.toUpperCase() === 'S'});
    if (tag !== 'H') incompleteIndex = true; // assume-unchanged, skip-worktree, conflict, etc.
  }
  return {index, incompleteIndex, indexHash:digest(staged)};
}

export function parseTree(tree: Buffer) {
  const entries = new Map<string, Entry>();
  for (const row of records(tree)) {
    const sep = row.indexOf('\t');
    const [mode,,hash] = row.slice(0, sep).split(' ');
    entries.set(row.slice(sep + 1), {mode: mode!, oid: hash!});
  }
  return entries;
}

export async function readIndex(root: string) {
  return parseIndex(await git(root, ['ls-files', '-v', '--stage', '-z']));
}

export async function commitTree(root: string, commitOid: string) {
  const raw = await git(root, ['ls-tree', '-rz', '--full-tree', commitOid]);
  return {entries: parseTree(raw), hash: digest(raw)};
}

export async function commitOidFor(root: string, rev: string): Promise<string | undefined> {
  if (!rev || rev.startsWith('-') || rev.includes('\0')) return undefined;
  const oid = oneLine(await git(root, ['rev-parse', '-q', '--verify', rev + '^{commit}'], [1]));
  return /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(oid) ? oid : undefined;
}

export async function firstParentOid(root: string, commitOid: string): Promise<string | undefined> {
  const oid = oneLine(await git(root, ['rev-parse', '-q', '--verify', commitOid + '^1'], [1]));
  return /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(oid) ? oid : undefined;
}

export async function headCommitOid(root: string): Promise<string | undefined> {
  const oid = oneLine(await git(root, ['rev-parse', '-q', '--verify', 'HEAD^{commit}'], [1]));
  return /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(oid) ? oid : undefined;
}

export async function inventory(root: string, observedAt: string) {
  const [status, staged, symbolic, format, headBytes] = await Promise.all([
    git(root, ['status', '--porcelain=v1', '-z', '--no-renames', '--untracked-files=all', '--ignore-submodules=none']),
    git(root, ['ls-files', '-v', '--stage', '-z']),
    git(root, ['symbolic-ref', '-q', '--short', 'HEAD'], [1]),
    git(root, ['rev-parse', '--show-object-format']),
    git(root, ['rev-parse', '-q', '--verify', 'HEAD^{commit}'], [1]),
  ]);
  const objectFormat = oneLine(format);
  if (objectFormat !== 'sha1' && objectFormat !== 'sha256') throw new Error('不支持此 Git 对象格式');
  const oid = oneLine(headBytes);
  const branch = oneLine(symbolic) || null;
  if (!oid && !branch) throw new Error('无法确认 HEAD 基准');
  const head: PushState['head'] = oid ? {baseKind:'COMMIT', baseCommitOid:oid, branch} : {baseKind:'EMPTY_TREE', baseCommitOid:null, branch};
  // An index deletion and an untracked replacement produce two porcelain rows
  // for one final path. Keep both staging observations and emit one manifest entry.
  const byPath = new Map<string, Change>();
  for (const row of records(status)) {
    const path = row.slice(3).replace(/\/$/, '');
    const change = byPath.get(path) ?? {path, staged:false, unstaged:false, untracked:false};
    if (row.startsWith('??')) change.untracked = true;
    else {
      change.staged ||= row[0] !== ' ';
      change.unstaged ||= row[1] !== ' ';
    }
    byPath.set(path, change);
  }
  const changes = [...byPath.values()];
  const {index, incompleteIndex, indexHash} = parseIndex(staged);
  const [tree, log, refBytes] = await Promise.all([
    oid ? git(root, ['ls-tree', '-rz', '--full-tree', oid]) : Buffer.alloc(0),
    git(root, ['log', '--no-show-signature', '--max-count=256', '-z', '--format=%H%x00%P%x00%s%x00%an%x00%aI', '--branches', '--remotes', '--tags', ...(oid ? [oid] : []), '--']),
    git(root, ['for-each-ref', '--format=%(refname)%09%(objecttype)%09%(objectname)%09%(*objecttype)%09%(*objectname)', 'refs/heads', 'refs/remotes', 'refs/tags']),
  ]);
  const base = parseTree(tree);
  const fields = records(log);
  const commits: PushState['commits'] = [];
  for (let i = 0; i < fields.length; i += 5) {
    commits.push({oid:fields[i]!, parents:fields[i+1]!.split(' ').filter(Boolean), subject:fields[i+2]!, authorName:fields[i+3]!, authoredAt:fields[i+4]!});
  }
  // Pretty-format %P may hide the parents of a shallow boundary. The raw
  // commit still records their actual IDs and order even when objects are absent.
  if (oneLine(await git(root, ['rev-parse', '--is-shallow-repository'])) === 'true') {
    for (const commit of commits.filter(c => !c.parents.length)) {
      const headers = decode(await git(root, ['cat-file','commit',commit.oid])).split('\n\n', 1)[0]!;
      commit.parents = [...headers.matchAll(/^parent ([a-f0-9]{40,64})$/gm)].map(match => match[1]!);
    }
  }
  const refs: PushState['refs'] = [];
  for (const line of oneLine(refBytes).split('\n').filter(Boolean)) {
    const [name, type, target, peeledType, peeled] = line.split('\t');
    const targetOid = type === 'commit' ? target : peeledType === 'commit' ? peeled : undefined;
    if (targetOid) refs.push({name: name!, targetOid, observedAt});
  }
  return {changes, index, base, incompleteIndex, indexHash, objectFormat:objectFormat as PushState['objectFormat'], head, commits, refs};
}
