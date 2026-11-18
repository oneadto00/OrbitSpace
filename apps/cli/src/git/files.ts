import { constants } from 'node:fs';
import { chmod, lstat, mkdir, open, realpath, writeFile } from 'node:fs/promises';
import { dirname, join, resolve, sep } from 'node:path';
import { digest, maxFile } from '../privacy/policy.js';
import { git, type Entry } from './repository.js';

export type WorkingFile = {
  state: 'regular' | 'absent' | 'missing' | 'unsupported';
  fingerprint: string;
  bytes?: Buffer;
  mode?: string;
  reason?: string;
};

export async function inspectFile(root: string, path: string): Promise<WorkingFile> {
  const absolute = resolve(root, path);
  if (!absolute.startsWith(root + sep) || path.split('/').some(p => p === '..' || p.toLowerCase() === '.git')) {
    throw new Error('Git 返回了不安全的文件路径');
  }
  try {
    // Inspect each ancestor before treating ENOENT as evidence of a deletion.
    let parent = root;
    for (const dir of path.split('/').slice(0, -1)) {
      parent = join(parent, dir);
      const st = await lstat(parent);
      if (st.isSymbolicLink()) return {state:'unsupported', fingerprint:'linked-parent', reason:'上级路径为符号链接'};
      if (!st.isDirectory()) return {state:'absent', fingerprint:'absent'};
    }
    const st = await lstat(absolute, {bigint:true});
    const stamp = [st.dev, st.ino, st.mode, st.size, st.mtimeNs, st.ctimeNs].join('/');
    if (!st.isFile() || st.size > BigInt(maxFile)) {
      return {state:'unsupported', fingerprint:stamp, reason:st.isSymbolicLink() ? '符号链接' : st.isFile() ? '超过单文件上限' : '特殊文件或目录'};
    }
    const fd = await open(absolute, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      const opened = await fd.stat({bigint:true});
      if (opened.dev !== st.dev || opened.ino !== st.ino || !opened.isFile() || await realpath(dirname(absolute)) !== dirname(absolute)) {
        throw new Error('采集期间路径发生变化，请重新采集');
      }
      const data = Buffer.alloc(Number(st.size) + 1);
      let used = 0;
      while (used < data.length) {
        const {bytesRead} = await fd.read(data, used, data.length - used);
        if (!bytesRead) break;
        used += bytesRead;
      }
      const after = await fd.stat({bigint:true});
      if (used !== Number(st.size) || after.ctimeNs !== st.ctimeNs) throw new Error('采集期间文件发生变化，请重新采集');
      const bytes = data.subarray(0, used);
      return {state:'regular', mode: st.mode & 0o100n ? '100755' : '100644', bytes, fingerprint:stamp + '/' + digest(bytes)};
    } finally { await fd.close(); }
  } catch (e) {
    const code = (e as NodeJS.ErrnoException).code;
    if (code === 'ENOENT') return {state:'absent', fingerprint:'absent'};
    if (code === 'EACCES' || code === 'EPERM' || code === 'EIO') return {state:'missing', fingerprint:'unreadable', reason:'无法读取文件'};
    throw e;
  }
}

export async function baseFile(root: string, entry?: Entry): Promise<WorkingFile> {
  if (!entry) return {state:'absent', fingerprint:'absent'};
  if (!['100644','100755'].includes(entry.mode)) return {state:'unsupported', fingerprint:entry.oid, reason:'基准为链接或子模块'};
  try {
    if (Number(await git(root, ['cat-file','-s',entry.oid])) > maxFile) return {state:'unsupported', fingerprint:entry.oid, reason:'基准超过单文件上限'};
    return {state:'regular', bytes:await git(root, ['cat-file','blob',entry.oid]), mode:entry.mode, fingerprint:entry.oid};
  } catch {
    return {state:'missing', fingerprint:entry.oid, reason:'本地缺少可读的基准对象'};
  }
}

export function unsupported(bytes?: Buffer): string | undefined {
  if (!bytes) return;
  if (bytes.includes(0)) return '二进制文件';
  try { new TextDecoder('utf-8', {fatal:true}).decode(bytes); } catch { return '非 UTF-8 文本或二进制文件'; }
  if (bytes.subarray(0, 150).toString().startsWith('version https://git-lfs.github.com/spec/v1')) return 'Git LFS 指针';
}

// Render the two captured byte sequences in a private, attribute-free directory.
// Git never dereferences a worktree link or invokes a repository diff/textconv driver here.
export async function patchFor(scratch: string, path: string, before: WorkingFile, after: WorkingFile) {
  if (!before.bytes && !after.bytes) return Buffer.alloc(0);
  async function side(prefix: string, file: WorkingFile) {
    if (!file.bytes) return '/dev/null';
    const name = prefix + '/' + path;
    const output = join(scratch, name);
    await mkdir(dirname(output), {recursive:true});
    await writeFile(output, file.bytes);
    await chmod(output, file.mode === '100755' ? 0o700 : 0o600);
    return name;
  }
  const names = await Promise.all([side('a', before), side('b', after)]);
  return git(scratch, ['-c','core.filemode=true', '-c','core.attributesFile=/dev/null','diff','--no-index','--no-prefix','--no-color','--no-ext-diff','--no-textconv','--text','--', ...names], [1], true);
}
