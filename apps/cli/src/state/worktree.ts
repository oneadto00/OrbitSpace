import { randomUUID } from 'node:crypto';
import { request, retry, ApiError } from '../transport/api.js';
import { write, worktreeSchema, type Credential, type State } from './local.js';

export const projectRoute = (projectId: string) => '/api/v1/cli/projects/' + encodeURIComponent(projectId);

export async function initialize(file: string, instance: string, credential: Credential, previous?: State) {
  const {grant, origin} = credential;
  const same = previous?.projectId === grant.projectId && previous.origin === origin && previous.credentialId === grant.credentialId;
  const state: State = same ? previous : {origin, projectId:grant.projectId, credentialId:grant.credentialId};
  const registration = state.registration ?? {key:randomUUID(), advanceKey:randomUUID(), expected:undefined};
  state.registration = registration;
  await write(file, state);
  const base = projectRoute(grant.projectId) + '/worktrees';
  // A stable localInstanceId locates the server binding even if local state was lost.
  // Persist the observed epoch before advancing it. Reusing the advance key covers a lost ACK.
  if (!registration.expected) {
    registration.expected = worktreeSchema.parse(await retry(() =>
      request(origin, base, 'POST', JSON.stringify({localInstanceId:instance}), credential, registration.key)));
    await write(file, state);
  }
  const binding = registration.expected;
  if (binding.projectId !== grant.projectId) throw new Error('服务端返回了其他工程的工作树');
  try {
    const next = worktreeSchema.parse(await retry(() => request(origin, base + '/' + encodeURIComponent(binding.worktreeId) + '/epoch',
      'POST', JSON.stringify({expectedWorktreeEpoch:binding.worktreeEpoch}), credential, registration.advanceKey)));
    if (next.projectId !== grant.projectId || next.worktreeId !== binding.worktreeId || next.worktreeEpoch <= binding.worktreeEpoch) throw new Error('服务端返回了不一致的工作树代次');
    state.worktree = next;
    delete state.pending;
    delete state.registration;
    await write(file, state);
    console.log('工程 %s 已绑定，工作树代次 %d。', JSON.stringify(grant.projectId), next.worktreeEpoch);
  } catch (e) {
    if (e instanceof ApiError && e.code === 'WORKTREE_EPOCH_STALE') {
      delete state.registration;
      await write(file, state);
      throw new Error('服务器代次已变化，请重新执行 orbit init --project。');
    }
    throw e;
  }
}
