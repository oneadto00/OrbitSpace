import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { capture, type CaptureKey, type FrozenCapture } from '../payload/capture.js';
import { printable } from '../privacy/policy.js';
import { readState, write, credentialFor, worktreeSchema, seq } from '../state/local.js';
import { initialize, projectRoute } from '../state/worktree.js';
import { ApiError, confirmConsent, request, retry, safeLink } from './api.js';

function preview(frozen: FrozenCapture) {
  const {report:r, patch, body, warnings, skipped} = frozen;
  console.log('%s · %s · 工程 %s · 工作树 %s / %d / %d', r.captureState, r.content.dataLevel, printable(r.projectId), printable(r.worktreeId), r.worktreeEpoch, r.captureSeq);
  console.log('基准 %s · 分支 %s · %d 个提交（至多最近 256 个）、%d 个引用', r.head.baseCommitOid ?? '空树', printable(r.head.branch ?? 'Detached HEAD'), r.commits.length, r.refs.length);
  console.log('提交元信息含 OID、有序父节点、作者姓名、时间与标题。');
  for (const ref of r.refs) console.log('引用 %s %s', printable(ref.name), ref.targetOid);
  for (const c of r.commits) console.log('提交 %s %s %s %s', c.oid, printable(c.authorName), c.authoredAt, printable(c.subject));
  const untracked = new Set(r.stagingSummary.untracked);
  const patchPaths = new Set(frozen.patchPaths);
  for (const m of r.contentManifest) {
    const source = untracked.has(m.path) ? ' · 未跟踪' : '';
    const sharing = patchPaths.has(m.path) ? '发送 Patch' : '仅元信息';
    console.log('%s · %s · %s%s', sharing, m.status, printable(m.path), source);
  }
  console.log('隐藏路径 %d 项，超出文件数上限 %d 项。', r.scopeDeclaration.hiddenItemCount, skipped);
  for (const warning of warnings) console.log('%s', warning);
  if (r.note) console.log('讨论说明 %s', printable(r.note));
  console.log('清单 %d 字节，Patch %d 字节，总计 %d 字节。摘要 %s', Buffer.byteLength(body), patch.length, Buffer.byteLength(body) + patch.length, r.payloadDigest);
}

export async function share(root: string, instance: string, file: string, options: {dryRun?: boolean; withDiff?: boolean; message?: string}) {
  const state = await readState(file);
  const withDiff = !!options.withDiff;
  if (options.message !== undefined && (!withDiff || !options.message.trim() || [...options.message].length > 4000)) throw new Error('-m 需要 --with-diff 和 1 至 4000 字的说明。');
  if ((!state?.worktree || state.registration) && !options.dryRun) throw new Error('请先运行 orbit init --project <project-id> 完成工作树绑定。');
  const credential = state ? await credentialFor(state.origin, state.projectId) : undefined;
  if (state && state.credentialId !== credential?.grant.credentialId) throw new Error('设备授权已变更，请重新运行 orbit init。');
  if (!options.dryRun && (!credential?.grant.scopes.includes('metadata:write') || (withDiff && !credential.grant.scopes.includes('patch:write')))) throw new Error('此凭证未获所需的共享权限');
  const binding = state?.worktree;
  const pending = options.dryRun ? undefined : state?.pending;
  const key: CaptureKey = {
    projectId:state?.projectId ?? 'local-preview', spaceId:credential?.grant.spaceId ?? 'local-preview',
    deviceId:credential?.grant.deviceId ?? 'local-preview', worktreeId:binding?.worktreeId ?? 'local-preview',
    worktreeEpoch:binding?.worktreeEpoch ?? 1, membershipEpoch:credential?.grant.membershipEpoch ?? 1,
    captureSeq:pending?.captureSeq ?? (binding?.currentCaptureSeq ?? 0) + 1,
    capturedAt:pending?.capturedAt ?? new Date().toISOString(), idempotencyKey:pending?.idempotencyKey ?? randomUUID(),
  };
  const collect = () => capture(root, key, withDiff, options.message);
  let frozen = await collect();
  if (pending && (frozen.report.payloadDigest !== pending.payloadDigest || frozen.fingerprint !== pending.fingerprint)) {
    // Different input is a new capture. Never reuse the possibly accepted sequence or request key.
    key.captureSeq = Math.max(key.captureSeq, binding!.currentCaptureSeq) + 1;
    key.idempotencyKey = randomUUID();
    key.capturedAt = new Date().toISOString();
    frozen = await collect();
  }
  preview(frozen);
  if (options.dryRun || !state || !credential || !binding) return;
  if (!(await confirmConsent('正式共享需要交互终端确认。使用 --dry-run 可仅预览。', '确认发送以上范围及所列未跟踪文件？输入 yes 继续：'))) { console.log('已取消共享。'); return; }
  const validate = async () => {
    const now = await collect();
    if (now.fingerprint !== frozen.fingerprint || now.body !== frozen.body || !now.patch.equals(frozen.patch)) {
      throw new Error('确认期间工作区或载荷发生变化，已停止共享。请重新预览并确认。');
    }
  };
  await validate();
  binding.currentCaptureSeq = key.captureSeq;
  state.pending = {captureSeq:key.captureSeq, capturedAt:key.capturedAt, idempotencyKey:key.idempotencyKey,
    payloadDigest:frozen.report.payloadDigest, fingerprint:frozen.fingerprint};
  await write(file, state);
  const base = projectRoute(state.projectId);
  try {
    const link = await retry(async attempt => {
      if (attempt > 0) await validate();
      if (frozen.report.captureState === 'CLEAN') {
        const result = worktreeSchema.parse(await request(state.origin, base + '/worktrees/' + encodeURIComponent(binding.worktreeId) + '/clean', 'POST', frozen.body, credential));
        if (result.worktreeId !== binding.worktreeId || result.worktreeEpoch !== key.worktreeEpoch || result.currentCaptureSeq !== key.captureSeq) throw new Error('clean 回执与当前采集不匹配');
        return undefined;
      }
      const session = z.object({reportId:z.string().min(1), patchRequired:z.boolean()}).parse(
        await request(state.origin, base + '/reports', 'POST', frozen.body, credential));
      const dest = base + '/reports/' + encodeURIComponent(session.reportId);
      if (session.patchRequired !== !!frozen.patch.length) throw new Error('服务端声明的 Patch 范围与预览不一致');
      if (session.patchRequired) await request(state.origin, dest + '/patch', 'PUT', frozen.patch, credential);
      const proof = {payloadDigest:frozen.report.payloadDigest, idempotencyKey:key.idempotencyKey};
      const receipt = z.object({snapshotUrl:z.string(), worktreeId:z.string(), worktreeEpoch:seq, captureSeq:seq}).parse(
        await request(state.origin, dest + '/complete', 'POST', JSON.stringify(proof), credential));
      if (receipt.worktreeId !== binding.worktreeId || receipt.worktreeEpoch !== key.worktreeEpoch || receipt.captureSeq !== key.captureSeq) throw new Error('快照回执与当前采集不匹配');
      return safeLink(state.origin, receipt.snapshotUrl);
    });
    delete state.pending;
    await write(file, state);
    console.log(link ? '不可变快照 ' + link : '已上报工作树干净。');
  } catch (e) {
    if (e instanceof ApiError && e.code === 'WORKTREE_EPOCH_STALE') {
      await initialize(file, instance, credential, state);
      throw new Error('工作树已申请新代次，请重新采集、预览并确认。');
    }
    throw e;
  }
}
