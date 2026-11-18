#!/usr/bin/env node
import { Command } from 'commander';
import { hostname } from 'node:os';
import { sep } from 'node:path';
import { ZodError } from 'zod';
import { repository } from './git/repository.js';
import { credentialFor, locked, readState, statePath } from './state/local.js';
import { initialize } from './state/worktree.js';
import { login, serverOrigin } from './transport/api.js';
import { backfill } from './transport/backfill.js';
import { share } from './transport/share.js';

const cli = new Command().name('orbit').description('OrbitSpace 主动共享 CLI');
cli.option('--server <origin>', '服务 origin（也可设置 ORBITSPACE_SERVER）', process.env.ORBITSPACE_SERVER || 'https://localhost:8443');
const origin = () => serverOrigin(cli.opts().server);

async function inWorktree(run: (root:string, key:string, file:string) => Promise<void>) {
  const {root, key} = await repository();
  const file = statePath(key);
  if (file.startsWith(root + sep)) throw new Error('OrbitSpace 本地状态目录必须位于 Git 工作树之外。');
  await locked(file, () => run(root, key, file));
}

cli.command('login').description('通过浏览器授权当前 CLI 设备')
  .option('--device-label <name>', '浏览器中显示的设备名', hostname())
  .option('--metadata-only', '仅申请元信息共享权限')
  .action(opts => login(origin(), opts.deviceLabel, !!opts.metadataOnly));

cli.command('init').description('注册工作树或申请新的工作树代次')
  .requiredOption('--project <project-id>', '工程 ID')
  .action(opts => inWorktree(async (_root, key, file) => {
    const grant = await credentialFor(origin(), opts.project);
    await initialize(file, key, grant, await readState(file));
  }));

cli.command('push-state').description('预览或主动共享当前工作树状态')
  .option('--dry-run', '仅在本地预览本次采集').option('--with-diff', '包括通过隐私过滤的 Patch')
  .option('-m, --message <note>', '附上讨论说明')
  .action(opts => inWorktree((root, key, file) => share(root, key, file, opts)));

cli.command('backfill').description('检查缺失内容并在预览确认后受控补传对象')
  .requiredOption('--target <commit-or-snapshot>', '目标提交 OID/引用或 WIP 快照 ID')
  .option('--base <commit-or-snapshot>', '基准提交 OID/引用或 WIP 快照 ID（可选）')
  .option('--project <project-id>', '工程 ID（默认使用当前工作树绑定的工程）')
  .option('--dry-run', '仅在本地预览本次补传范围')
  .action(opts => inWorktree((root, key, file) => backfill(root, key, file, origin(), opts)));

try { await cli.parseAsync(); }
catch (e) {
  // System and validation errors can contain request bodies, tokens, or private paths.
  const safe = e instanceof Error && e.constructor !== TypeError && !(e instanceof ZodError) && !('errno' in e);
  console.error('orbit: %s', safe ? e.message : '请求或本地 I/O 失败，请检查服务地址、证书、仓库和文件权限。');
  process.exitCode = 1;
}
