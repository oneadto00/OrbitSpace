import { randomUUID } from 'node:crypto';
import ignore from 'ignore';
import { z } from 'zod';
import type {
  BackfillCheckRequest,
  BackfillCheckResponse,
  BackfillManifestItem,
  BackfillMissingItem,
  BackfillMissingReason,
  BackfillReceiptDto,
  BackfillSessionDto,
  CompleteBackfillRequest,
  CreateBackfillSessionRequest,
  DiffEndpointKind,
  DiffEndpointRef,
} from '@orbitspace/shared-types';
import { baseFile, inspectFile, unsupported, type WorkingFile } from '../git/files.js';
import {
  commitOidFor,
  commitTree,
  decode,
  firstParentOid,
  headCommitOid,
  readIndex,
  type Entry,
} from '../git/repository.js';
import {
  canonical,
  digest,
  maxPatch,
  maxSharedFiles,
  printable,
  privatePath,
  safeRelativePath,
  secretLine,
} from '../privacy/policy.js';
import { credentialFor, readState, seq, type Credential } from '../state/local.js';
import { projectRoute } from '../state/worktree.js';
import { confirmConsent, request, retry } from './api.js';

export interface BackfillOptions {
  target: string;
  base?: string;
  project?: string;
  dryRun?: boolean;
}

interface CollectedItem {
  item: BackfillManifestItem;
  bytes: Buffer;
  reason: BackfillMissingReason;
}

interface EndpointPlan {
  endpoint: DiffEndpointRef;
  sessionPayload: CreateBackfillSessionRequest;
  sessionBody: string;
  payloadDigest: string;
  collected: CollectedItem[];
  bytesByPath: Map<string, CollectedItem>;
  skippedCount: number;
  totalBytes: number;
}

interface FrozenBackfill {
  plans: EndpointPlan[];
  unsupportedPaths: string[];
  hiddenUnsupportedCount: number;
  warnings: string[];
  fingerprint: string;
  overallDigest: string;
}

const endpointKindSchema = z.enum(['COMMIT', 'WIP']) satisfies z.ZodType<DiffEndpointKind>;
const gitOidSchema = z.string().regex(/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/);
const backfillCheckResponseSchema = z.object({
  projectId: z.string().min(1),
  missingItems: z.array(
    z.object({
      path: z.string().min(1),
      endpointKind: endpointKindSchema,
      endpointId: z.string().min(1),
      expectedBlobOid: gitOidSchema.nullable(),
      reason: z.enum(['MISSING', 'NOT_SHARED']),
    }),
  ),
  unsupportedPaths: z.array(z.string().min(1)),
}) satisfies z.ZodType<BackfillCheckResponse>;

const backfillSessionSchema = z.object({
  backfillId: z.string().min(1),
  requiredPaths: z.array(z.string().min(1)),
  expiresAt: z.string().min(1),
}) satisfies z.ZodType<BackfillSessionDto>;

const backfillReceiptSchema = z.object({
  backfillId: z.string().min(1),
  projectId: z.string().min(1),
  targetKind: endpointKindSchema,
  targetId: z.string().min(1),
  acceptedCount: seq,
  completedAt: z.string().min(1),
}) satisfies z.ZodType<BackfillReceiptDto>;

const endpointKey = (endpoint: DiffEndpointRef) => `${endpoint.kind}:${endpoint.id}`;
const shortId = (id: string) => (id.length <= 12 ? id : id.slice(0, 12));

/** Resolve a CLI commit OID/ref or WIP snapshot identifier into a DiffEndpointRef. */
async function resolveEndpoint(root: string, raw: string): Promise<DiffEndpointRef> {
  if (!raw || /\s/.test(raw)) throw new Error('端点标识不能为空或包含空白字符');
  const prefixed = raw.match(/^(commit|wip|snapshot):(.+)$/i);
  if (prefixed) {
    const kind = prefixed[1]!.toLowerCase();
    const value = prefixed[2]!;
    if (kind === 'commit') {
      const oid = await commitOidFor(root, value);
      if (!oid) throw new Error('无法解析提交引用 ' + printable(value));
      return { kind: 'COMMIT', id: oid };
    }
    if (!value || value.length > 160 || value.startsWith('-')) throw new Error('快照 ID 格式不合法');
    return { kind: 'WIP', id: value };
  }
  if (raw.startsWith('-')) throw new Error('端点标识格式不合法');
  const oid = await commitOidFor(root, raw);
  if (oid) return { kind: 'COMMIT', id: oid };
  if (/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i.test(raw)) {
    throw new Error('本地 Git 仓库中不存在提交 ' + printable(raw));
  }
  if (raw.length > 160) throw new Error('端点标识长度超出限制');
  return { kind: 'WIP', id: raw };
}

/** Derive the comparison base endpoint when --base is omitted. */
async function resolveBaseEndpoint(root: string, target: DiffEndpointRef, rawBase?: string): Promise<DiffEndpointRef> {
  if (rawBase !== undefined) return resolveEndpoint(root, rawBase);
  if (target.kind === 'COMMIT') {
    const parent = await firstParentOid(root, target.id);
    return { kind: 'COMMIT', id: parent ?? target.id };
  }
  const head = await headCommitOid(root);
  return head ? { kind: 'COMMIT', id: head } : target;
}

/** Group server-reported missing items by endpoint and verify safety/scope invariants. */
function groupMissingItems(
  items: BackfillMissingItem[],
  baseEndpoint: DiffEndpointRef,
  targetEndpoint: DiffEndpointRef,
): Array<{ endpoint: DiffEndpointRef; items: BackfillMissingItem[] }> {
  const allowedKeys = new Set([endpointKey(baseEndpoint), endpointKey(targetEndpoint)]);
  const grouped = new Map<string, { endpoint: DiffEndpointRef; itemsByPath: Map<string, BackfillMissingItem> }>();

  for (const item of items) {
    if (!safeRelativePath(item.path)) throw new Error('服务端返回了不安全的文件路径');
    const ref: DiffEndpointRef = { kind: item.endpointKind, id: item.endpointId };
    const key = endpointKey(ref);
    if (!allowedKeys.has(key)) throw new Error('服务端返回了未请求端点的补传清单');

    let bucket = grouped.get(key);
    if (!bucket) {
      bucket = { endpoint: ref, itemsByPath: new Map() };
      grouped.set(key, bucket);
    }
    const existing = bucket.itemsByPath.get(item.path);
    if (existing && existing.expectedBlobOid !== item.expectedBlobOid) {
      throw new Error('服务端返回了冲突的补传对象清单');
    }
    if (!existing) bucket.itemsByPath.set(item.path, item);
  }

  const orderedKeys = [...new Set([endpointKey(baseEndpoint), endpointKey(targetEndpoint)])];
  return orderedKeys
    .map(key => grouped.get(key))
    .filter((g): g is NonNullable<typeof g> => !!g)
    .map(({ endpoint, itemsByPath }) => ({
      endpoint,
      items: [...itemsByPath.values()].sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0)),
    }));
}

interface CollectContext {
  root: string;
  index: Map<string, Entry>;
  sensitive: (path: string) => boolean;
  activeSnapshotId: string | null;
  fingerprints: string[];
  warnings: string[];
}

/** Filter and collect allowed missing objects for one endpoint under P1 privacy rules. */
async function collectEndpointPlan(
  ctx: CollectContext,
  endpoint: DiffEndpointRef,
  missingItems: BackfillMissingItem[],
): Promise<EndpointPlan> {
  const { root, index, sensitive, activeSnapshotId, fingerprints, warnings } = ctx;
  let treeEntries: Map<string, Entry> | undefined;
  if (endpoint.kind === 'COMMIT') {
    const tree = await commitTree(root, endpoint.id);
    treeEntries = tree.entries;
    fingerprints.push(endpoint.id, tree.hash);
  } else if (activeSnapshotId && activeSnapshotId !== endpoint.id) {
    warnings.push('注意：端点 WIP ' + printable(endpoint.id) + ' 不是当前工作树最新快照，将按当前工作区内容预览与校验。');
  }

  const collected: CollectedItem[] = [];
  const bytesByPath = new Map<string, CollectedItem>();
  let hiddenCount = 0;
  let excludedCount = 0;
  let skippedCount = 0;
  let totalBytes = 0;

  for (const missing of missingItems) {
    const path = missing.path;
    const treeEntry = treeEntries?.get(path);
    const file: WorkingFile = endpoint.kind === 'COMMIT'
      ? await baseFile(root, treeEntry)
      : await inspectFile(root, path);
    fingerprints.push(file.fingerprint);

    if (sensitive(path)) {
      hiddenCount++;
      excludedCount++;
      continue;
    }
    if (collected.length >= maxSharedFiles) {
      skippedCount++;
      excludedCount++;
      continue;
    }

    let rejectReason: string | undefined;
    if (endpoint.kind === 'COMMIT' && missing.expectedBlobOid && treeEntry?.oid !== missing.expectedBlobOid) {
      rejectReason = '本地 Blob OID 与服务端预期不一致';
    } else if (endpoint.kind === 'WIP' && index.get(path)?.mode === '160000') {
      rejectReason = '子模块指针';
    } else if (file.state !== 'regular' || !file.bytes || !file.mode) {
      rejectReason = file.state === 'absent'
        ? (endpoint.kind === 'COMMIT' ? '目标提交中不存在此文件' : '工作区中不存在此文件')
        : (file.reason ?? '无法读取文件');
    } else {
      const contentIssue = unsupported(file.bytes);
      if (contentIssue) {
        rejectReason = contentIssue;
      } else {
        const line = secretLine(decode(file.bytes));
        if (line) {
          rejectReason = '敏感内容，第 ' + line + ' 行';
        } else if (totalBytes + file.bytes.length > maxPatch) {
          rejectReason = '超过总内容上限';
        }
      }
    }

    if (rejectReason) {
      excludedCount++;
      warnings.push(printable(path) + ' · ' + rejectReason);
      continue;
    }

    const bytes = file.bytes!;
    const sha256 = digest(bytes);
    const manifestItem: BackfillManifestItem = {
      path,
      mode: file.mode!,
      blobOid: endpoint.kind === 'COMMIT' ? treeEntry!.oid : null,
      sizeBytes: bytes.length,
      sha256,
    };
    const entry: CollectedItem = { item: manifestItem, bytes, reason: missing.reason };
    collected.push(entry);
    bytesByPath.set(path, entry);
    totalBytes += bytes.length;
    fingerprints.push(sha256);
  }

  const sessionPayload: CreateBackfillSessionRequest = {
    targetKind: endpoint.kind,
    targetId: endpoint.id,
    scopeDeclaration: {
      includedPaths: collected.map(c => c.item.path),
      hiddenItemCount: hiddenCount,
      excludedItemCount: excludedCount,
    },
    items: collected.map(c => c.item),
  };
  const sessionBody = canonical(sessionPayload);
  return {
    endpoint,
    sessionPayload,
    sessionBody,
    payloadDigest: digest(sessionBody),
    collected,
    bytesByPath,
    skippedCount,
    totalBytes,
  };
}

/** Freeze the full backfill manifest, object bytes, and workspace fingerprint. */
async function collectBackfill(
  root: string,
  baseEndpoint: DiffEndpointRef,
  targetEndpoint: DiffEndpointRef,
  checkResp: BackfillCheckResponse,
  activeSnapshotId: string | null,
): Promise<FrozenBackfill> {
  const policy = await inspectFile(root, '.orbitignore');
  if (policy.state !== 'absent' && policy.state !== 'regular') {
    throw new Error('无法安全读取 .orbitignore');
  }
  const excluded = ignore().add(policy.bytes ? decode(policy.bytes) : '');
  const sensitive = (path: string) => path === '.orbitignore' || privatePath(path) || excluded.ignores(path);

  const [{ index, indexHash }, headOid] = await Promise.all([readIndex(root), headCommitOid(root)]);
  const fingerprints: string[] = [indexHash, policy.fingerprint, headOid ?? 'EMPTY_TREE', canonical(checkResp.missingItems)];
  const warnings: string[] = [];

  for (const path of index.keys()) {
    if (!privatePath(path)) continue;
    const file = await inspectFile(root, path);
    const line = file.bytes && secretLine(file.bytes.toString());
    warnings.push('已隐藏受跟踪的敏感路径' + (line ? '，敏感内容位于第 ' + line + ' 行' : ''));
    fingerprints.push(file.fingerprint);
  }

  const groups = groupMissingItems(checkResp.missingItems, baseEndpoint, targetEndpoint);
  const plans: EndpointPlan[] = [];
  const ctx: CollectContext = { root, index, sensitive, activeSnapshotId, fingerprints, warnings };
  for (const group of groups) {
    plans.push(await collectEndpointPlan(ctx, group.endpoint, group.items));
  }

  const unsupportedPaths: string[] = [];
  let hiddenUnsupportedCount = 0;
  for (const path of checkResp.unsupportedPaths) {
    if (!safeRelativePath(path) || sensitive(path)) {
      hiddenUnsupportedCount++;
      continue;
    }
    unsupportedPaths.push(path);
  }

  const overallDigest = digest(
    canonical(
      plans.map(p => ({
        targetKind: p.sessionPayload.targetKind,
        targetId: p.sessionPayload.targetId,
        payloadDigest: p.payloadDigest,
      })),
    ),
  );
  return {
    plans,
    unsupportedPaths,
    hiddenUnsupportedCount,
    warnings,
    fingerprint: digest(canonical(fingerprints)),
    overallDigest,
  };
}

/** Print the final terminal preview for the frozen backfill payload. */
function previewBackfill(
  frozen: FrozenBackfill,
  projectId: string,
  baseEndpoint: DiffEndpointRef,
  targetEndpoint: DiffEndpointRef,
) {
  console.log(
    '补传预览 · 工程 %s · 目标 %s %s · 基准 %s %s',
    printable(projectId),
    targetEndpoint.kind,
    printable(shortId(targetEndpoint.id)),
    baseEndpoint.kind,
    printable(shortId(baseEndpoint.id)),
  );
  let totalFiles = 0;
  let totalBytes = 0;
  let hiddenItems = 0;
  let excludedItems = 0;
  let skippedItems = 0;

  for (const plan of frozen.plans) {
    const scope = plan.sessionPayload.scopeDeclaration;
    hiddenItems += scope.hiddenItemCount;
    excludedItems += scope.excludedItemCount;
    skippedItems += plan.skippedCount;
    totalFiles += plan.collected.length;
    totalBytes += plan.totalBytes;

    for (const entry of plan.collected) {
      console.log(
        '补传对象 · %s %s · %s · %s · %d 字节 · %s · %s',
        plan.endpoint.kind,
        printable(shortId(plan.endpoint.id)),
        entry.reason,
        entry.item.mode,
        entry.item.sizeBytes,
        entry.item.sha256.slice(0, 12),
        printable(entry.item.path),
      );
    }
  }

  for (const path of frozen.unsupportedPaths) {
    console.log('服务端标记不支持 · %s', printable(path));
  }
  if (frozen.hiddenUnsupportedCount > 0) {
    console.log('已隐藏服务端标记的敏感或忽略路径 %d 项。', frozen.hiddenUnsupportedCount);
  }
  console.log(
    '隐藏路径 %d 项，排除/不可用 %d 项，超出文件数上限 %d 项。',
    hiddenItems,
    excludedItems,
    skippedItems,
  );
  for (const warning of frozen.warnings) console.log('%s', warning);
  console.log(
    '共 %d 个端点，%d 个待补传文件，总计 %d 字节。总摘要 %s',
    frozen.plans.filter(p => p.collected.length > 0).length,
    totalFiles,
    totalBytes,
    frozen.overallDigest,
  );
}

/** Execute one idempotent backfill session (create -> upload required objects -> complete). */
async function uploadEndpointSession(
  origin: string,
  projectId: string,
  plan: EndpointPlan,
  credential: Credential,
  validate: () => Promise<void>,
) {
  const base = projectRoute(projectId);
  const createKey = randomUUID();
  const completeKey = randomUUID();

  const session = await retry(async attempt => {
    if (attempt > 0) await validate();
    return backfillSessionSchema.parse(
      await request(origin, `${base}/backfill/sessions`, 'POST', plan.sessionBody, credential, createKey),
    );
  });

  if (Date.parse(session.expiresAt) <= Date.now()) {
    throw new Error('补传会话已过期，请重新运行命令。');
  }

  const seen = new Set<string>();
  const toUpload: string[] = [];
  for (const path of session.requiredPaths) {
    if (!plan.bytesByPath.has(path)) {
      throw new Error('服务端声明的补传路径与最终预览不一致，已中止。');
    }
    if (!seen.has(path)) {
      seen.add(path);
      toUpload.push(path);
    }
  }

  for (const path of toUpload) {
    const entry = plan.bytesByPath.get(path)!;
    await retry(async attempt => {
      if (attempt > 0) await validate();
      if (Date.parse(session.expiresAt) <= Date.now()) {
        throw new Error('补传会话已过期，请重新运行命令。');
      }
      await request(
        origin,
        `${base}/backfill/sessions/${encodeURIComponent(session.backfillId)}/objects?path=${encodeURIComponent(path)}`,
        'PUT',
        entry.bytes,
        credential,
        undefined,
        { 'X-Backfill-Sha256': entry.item.sha256 },
      );
    });
  }

  const completePayload: CompleteBackfillRequest = {
    payloadDigest: plan.payloadDigest,
    uploadedPaths: toUpload,
  };
  const receipt = await retry(async attempt => {
    if (attempt > 0) await validate();
    return backfillReceiptSchema.parse(
      await request(
        origin,
        `${base}/backfill/sessions/${encodeURIComponent(session.backfillId)}/complete`,
        'POST',
        canonical(completePayload),
        credential,
        completeKey,
      ),
    );
  });

  if (
    receipt.backfillId !== session.backfillId ||
    receipt.projectId !== projectId ||
    receipt.targetKind !== plan.sessionPayload.targetKind ||
    receipt.targetId !== plan.sessionPayload.targetId
  ) {
    throw new Error('补传回执与当前会话不匹配');
  }
  return { receipt, uploadedCount: toUpload.length };
}

/** Verify workspace state/fingerprint and live credential remain unchanged since preview. */
function assertFrozenMatch(frozen: FrozenBackfill, now: FrozenBackfill) {
  if (
    now.fingerprint !== frozen.fingerprint ||
    now.overallDigest !== frozen.overallDigest ||
    now.plans.length !== frozen.plans.length
  ) {
    throw new Error('确认期间工作区或载荷发生变化，已停止补传。请重新预览并确认。');
  }
  for (let i = 0; i < frozen.plans.length; i++) {
    const a = frozen.plans[i]!;
    const b = now.plans[i]!;
    if (a.sessionBody !== b.sessionBody || a.collected.length !== b.collected.length) {
      throw new Error('确认期间工作区或载荷发生变化，已停止补传。请重新预览并确认。');
    }
    for (let j = 0; j < a.collected.length; j++) {
      if (!a.collected[j]!.bytes.equals(b.collected[j]!.bytes)) {
        throw new Error('确认期间文件内容发生变化，已停止补传。请重新预览并确认。');
      }
    }
  }
}

/** Run the P2 controlled content backfill and incremental object sync flow. */
export async function backfill(
  root: string,
  _instance: string,
  file: string,
  cliOrigin: string,
  options: BackfillOptions,
) {
  const state = await readState(file);
  if (options.project && state?.worktree && state.projectId !== options.project) {
    throw new Error('指定的工程 ID 与当前工作树绑定的工程不一致。');
  }
  const projectId = options.project ?? state?.projectId;
  const targetOrigin = options.project ? cliOrigin : state?.origin;
  if (!projectId || !targetOrigin) {
    throw new Error('请先运行 orbit init --project <project-id> 完成工作树绑定，或指定 --project。');
  }
  if (!options.project && (!state?.worktree || state.registration) && !options.dryRun) {
    throw new Error('请先运行 orbit init --project <project-id> 完成工作树绑定。');
  }

  const credential = await credentialFor(targetOrigin, projectId);
  if (state && state.origin === targetOrigin && state.projectId === projectId && state.credentialId !== credential.grant.credentialId) {
    throw new Error('设备授权已变更，请重新运行 orbit init。');
  }
  if (!credential.grant.scopes.includes('metadata:write') || (!options.dryRun && !credential.grant.scopes.includes('patch:write'))) {
    throw new Error('此凭证未获所需的补传权限');
  }

  const targetEndpoint = await resolveEndpoint(root, options.target);
  const baseEndpoint = await resolveBaseEndpoint(root, targetEndpoint, options.base);
  const checkReq: BackfillCheckRequest = { baseEndpoint, targetEndpoint };
  const checkResp = await retry(async () =>
    backfillCheckResponseSchema.parse(
      await request(
        targetOrigin,
        `${projectRoute(projectId)}/backfill/check`,
        'POST',
        canonical(checkReq),
        credential,
      ),
    ),
  );
  if (checkResp.projectId !== projectId) {
    throw new Error('服务端返回的工程 ID 与当前工程不匹配');
  }

  const activeSnapshotId = state?.worktree?.activeSnapshotId ?? null;
  const collect = () => collectBackfill(root, baseEndpoint, targetEndpoint, checkResp, activeSnapshotId);
  const frozen = await collect();
  previewBackfill(frozen, projectId, baseEndpoint, targetEndpoint);

  if (options.dryRun && !credential.grant.scopes.includes('patch:write')) {
    console.log('注意：当前凭证缺少 patch:write 权限，正式补传前请重新运行 orbit login。');
  }
  if (options.dryRun) return;

  const uploadPlans = frozen.plans.filter(p => p.collected.length > 0);
  if (uploadPlans.length === 0) {
    console.log(checkResp.missingItems.length === 0 ? '服务端已持有比较范围内的全部获准对象，无需补传。' : '没有可补传的获准文件。');
    return;
  }

  const agreed = await confirmConsent(
    '正式补传需要交互终端确认。使用 --dry-run 可仅预览。',
    '确认补传以上范围及所列文件内容？输入 yes 继续：',
  );
  if (!agreed) {
    console.log('已取消补传。');
    return;
  }

  const validate = async () => {
    const live = await credentialFor(targetOrigin, projectId);
    if (
      live.grant.credentialId !== credential.grant.credentialId ||
      live.grant.membershipEpoch !== credential.grant.membershipEpoch ||
      !live.grant.scopes.includes('metadata:write') ||
      !live.grant.scopes.includes('patch:write')
    ) {
      throw new Error('设备授权或成员周期已变更，已中止补传。');
    }
    assertFrozenMatch(frozen, await collect());
  };

  await validate();
  for (const plan of uploadPlans) {
    const { receipt, uploadedCount } = await uploadEndpointSession(targetOrigin, projectId, plan, credential, validate);
    console.log(
      '补传完成：%s %s · 会话 %s · 已接收 %d 个对象（本次上传 %d 个）',
      receipt.targetKind,
      printable(shortId(receipt.targetId)),
      printable(receipt.backfillId),
      receipt.acceptedCount,
      uploadedCount,
    );
  }
}
