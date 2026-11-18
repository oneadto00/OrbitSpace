import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import ignore from 'ignore';
import type { ContentManifestStatus, PushState } from '@orbitspace/shared-types';
import { baseFile, inspectFile, patchFor, unsupported } from '../git/files.js';
import { decode, inventory } from '../git/repository.js';
import { canonical, digest, maxPatch, maxSharedFiles, printable, privatePath, secretLine } from '../privacy/policy.js';

export type CaptureKey = Pick<PushState, 'spaceId' | 'deviceId' | 'projectId' | 'worktreeId' | 'worktreeEpoch' | 'membershipEpoch' | 'captureSeq' | 'capturedAt' | 'idempotencyKey'>;

export async function capture(root: string, key: CaptureKey, withDiff: boolean, note?: string) {
  const {head, refs, commits, objectFormat, changes, base, index, incompleteIndex, indexHash} = await inventory(root, key.capturedAt);
  const policy = await inspectFile(root, '.orbitignore');
  if (policy.state !== 'absent' && policy.state !== 'regular') throw new Error('无法安全读取 .orbitignore');
  const excluded = ignore().add(policy.bytes ? decode(policy.bytes) : '');
  const fingerprints: string[] = [indexHash, policy.fingerprint, canonical(changes)];
  const warnings: string[] = [];
  const manifest: PushState['contentManifest'] = [];
  const staging: PushState['stagingSummary'] = {staged:[], unstaged:[], untracked:[]};
  const patches: Buffer[] = [];
  let patchSize = 0;
  const patchPaths: string[] = [];
  let hidden = 0;
  let skipped = 0;
  const sensitive = (path: string) => path === '.orbitignore' || privatePath(path) || excluded.ignores(path);
  // This pass includes unchanged tracked keys that gitignore/status would miss.
  for (const path of index.keys()) {
    if (!privatePath(path)) continue;
    const file = await inspectFile(root, path);
    const line = file.bytes && secretLine(file.bytes.toString());
    warnings.push('已隐藏受跟踪的敏感路径' + (line ? '，敏感内容位于第 ' + line + ' 行' : ''));
    fingerprints.push(file.fingerprint);
  }
  const scratch = await mkdtemp(join(tmpdir(), 'orbit-capture-'));
  try {
    for (const change of changes) {
      const path = change.path.replace(/\/$/, '');
      const file = await inspectFile(root, path);
      fingerprints.push(file.fingerprint);
      if (sensitive(path)) { hidden++; continue; }
      if (manifest.length >= maxSharedFiles) { skipped++; continue; }
      if (change.untracked) staging.untracked.push(path);
      if (change.staged) staging.staged.push(path);
      if (change.unstaged) staging.unstaged.push(path);
      let status: ContentManifestStatus = 'NOT_SHARED';
      let reason: string | undefined;
      if (file.state === 'absent' && index.get(path)?.sparse) { status = 'MISSING'; reason = '稀疏工作树未检出此项'; }
      else if (file.state === 'missing') { status = 'MISSING'; reason = file.reason; }
      else if (index.get(path)?.mode === '160000' || base.get(path)?.mode === '160000') { status = 'UNSUPPORTED'; reason = '子模块指针'; }
      else if (file.state === 'unsupported' || unsupported(file.bytes)) { status = 'UNSUPPORTED'; reason = file.reason ?? unsupported(file.bytes); }
      else if (withDiff) {
        const before = await baseFile(root, base.get(path));
        reason = before.reason ?? unsupported(before.bytes);
        if (before.state === 'missing') status = 'MISSING';
        else if (reason) status = 'UNSUPPORTED';
        else {
          const bytes = await patchFor(scratch, path, before, file);
          const text = decode(bytes);
          const line = secretLine(text);
          if (line) reason = '敏感内容，Patch 第 ' + line + ' 行';
          else if (patchSize + bytes.length > maxPatch) reason = '超过总内容上限';
          else {
            patches.push(bytes);
            if (bytes.length) patchPaths.push(path);
            patchSize += bytes.length;
            if (file.state === 'absent') status = 'ABSENT_CONFIRMED';
            else if (bytes.length && (!before.bytes || containsEntireFinalFile(text, file.bytes!))) status = 'AVAILABLE';
            else reason = bytes.length ? '仅共享本次 Patch 片段' : '最终工作区与基准相同';
          }
        }
      } else if (file.state === 'absent') status = 'ABSENT_CONFIRMED';
      if (reason) warnings.push(printable(path) + ' · ' + reason);
      manifest.push(status === 'AVAILABLE' ? {path, status, sha256:digest(file.bytes!)} : {path, status});
    }
  } finally { await rm(scratch, {recursive:true, force:true}); }

  const patch = Buffer.concat(patches);
  const captureState = !changes.length && !incompleteIndex ? 'CLEAN' : (withDiff ? !!patch.length : !!manifest.length) ? 'CHANGES' : 'NO_SHARED_CHANGES';
  const content: PushState['content'] = patch.length ? {dataLevel:'PATCH_ONLY', patch:{sizeBytes:patch.length, sha256:digest(patch)}} : {dataLevel:'METADATA_ONLY'};
  const data = { ...key, protocolVersion:1 as const, objectFormat, head, commits, refs, captureState, stagingSummary:staging,
    scopeDeclaration:{includedPaths:manifest.map(m => m.path), hiddenItemCount:hidden, excludedItemCount:hidden + skipped + manifest.filter(m => m.status === 'UNSUPPORTED' || m.status === 'MISSING').length},
    contentManifest:manifest, content, ...(note ? {note} : {}) };
  const encoded = canonical(data);
  // Scan individual metadata strings (including notes and Git history) before showing or sending them.
  for (const value of [note ?? '', head.branch ?? '', ...refs.map(r => r.name), ...commits.flatMap(c => [c.subject,c.authorName])]) {
    if (secretLine(value)) throw new Error('元信息包含疑似密钥，已中止。请检查分支、提交说明或讨论说明。');
  }
  if (secretLine(decode(patch))) throw new Error('固定 Patch 出现敏感内容，已中止');
  const payloadDigest = digest(encoded);
  const report = { ...data, payloadDigest } as PushState;
  return {report, body:canonical(report), patch, fingerprint:digest(canonical(fingerprints)), warnings, skipped, patchPaths};
}

function containsEntireFinalFile(patch: string, bytes: Buffer): boolean {
  if (!bytes.length) return true;
  const lines = bytes.toString().split('\n').length - (bytes.at(-1) === 10 ? 1 : 0);
  const hunks = [...patch.matchAll(/^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/gm)];
  return hunks.length === 1 && hunks[0]![1] === '1' && Number(hunks[0]![2] ?? 1) === lines;
}

export type FrozenCapture = Awaited<ReturnType<typeof capture>>;
