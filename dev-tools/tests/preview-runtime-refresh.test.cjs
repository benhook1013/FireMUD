const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {test} = require('node:test');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const workflow = JSON.parse(execFileSync('python3', ['-c',
  'import yaml,json,sys; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))',
  path.join(root, '.github/workflows/preview-runtime-refresh.yml')], {encoding: 'utf8'}));
const script = workflow.jobs.refresh.steps[1].with.script;
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const E = 'e'.repeat(40);
const A = 'a'.repeat(40), B = 'b'.repeat(40), C = 'c'.repeat(40), D = 'd'.repeat(40);
const tupleStep = (kind, base, merge) => ({
  name: `Preview ${kind} tuple pr-7 base-${base} head-${C} merge-${merge}`,
  status: 'completed', conclusion: 'success',
});
function fixture() {
  return {
    run: {id: 99, run_attempt: 2, display_title: `PR preview 7 base-${D} head-${C} merge-${E}`, path: '.github/workflows/preview.yml', event: 'pull_request', status: 'completed', conclusion: 'success',
      repository: {full_name: 'example/FireMUD'}, head_repository: {full_name: 'example/FireMUD'},
      head_sha: C, pull_requests: [{number: 7, head: {sha: C}, base: {sha: D}}]},
    pr: {number: 7, state: 'open', head: {sha: C, repo: {full_name: 'example/FireMUD'}},
      base: {ref: 'develop', repo: {full_name: 'example/FireMUD'}},
      merge_commit_sha: A, labels: [], user: {login: 'developer'}},
    jobs: [{name: 'Preview Plan', run_id: 99, run_attempt: 2, head_sha: C, workflow_name: 'PR Preview Environment', status: 'completed', conclusion: 'success',
      steps: [tupleStep('event', D, E), tupleStep('resolved', D, E)]}],
    recordedMerge: {sha: E, parents: [{sha: D}, {sha: C}]},
    base: {object: {sha: B}}, merge: {sha: A, parents: [{sha: B}, {sha: C}]},
  };
}
async function execute(f, sent = []) {
  const github = {request: async (route, args) => {
    assert.equal(route, 'GET /repos/{owner}/{repo}/actions/runs/{run_id}/attempts/{attempt_number}/jobs');
    assert.equal(args.run_id, 99); assert.equal(args.attempt_number, 2);
    assert.equal(args.per_page, 100); assert.ok(args.page <= 3);
    return {data: f.inventory ?? {total_count: f.jobs.length, jobs: f.jobs}};
  }, rest: {
    actions: {
      getWorkflowRun: async ({run_id}) => {
        assert.equal(run_id, 99);
        f.runReads = (f.runReads ?? 0) + 1;
        return {data: f.runReads === 1 ? f.run : f.latestRun ?? f.run};
      },
      getWorkflow: async () => ({data: {id: 42, path: '.github/workflows/runtime-images.yml'}}),
      listWorkflowRuns: async args => {
        assert.equal(args.workflow_id, 42);
        assert.equal(args.per_page, 100);
        f.lookups = (f.lookups ?? 0) + 1;
        return {data: {workflow_runs: f.fullInventory ??
          (f.producers ?? []).filter(run => run.status === args.status)}};
      },
    },
    pulls: {get: async ({pull_number}) => {
      assert.equal(pull_number, f.pr.number);
      f.pullReads = (f.pullReads ?? 0) + 1;
      return {data: f.pullReads === 1 ? f.pr : f.latestPr ?? f.pr};
    }},
    git: {getRef: async ({ref}) => {
      assert.equal(ref, 'heads/' + f.pr.base.ref);
      f.baseReads = (f.baseReads ?? 0) + 1;
      return {data: f.baseReads === 1 ? f.base : f.latestBase ?? f.base};
    }},
    repos: {
      getCommit: async ({ref}) => {
        (f.mergeReads ??= []).push(ref);
        return {data: [f.recordedMerge, f.merge, f.resolvedMerge][f.mergeReads.length - 1]};
      },
      createDispatchEvent: async p => sent.push(p),
    },
  }};
  await new AsyncFunction('github', 'context', 'core', 'require', script)(github,
    {repo: {owner: 'example', repo: 'FireMUD'}, payload: {workflow_run: {id: 99, run_attempt: 2}, repository: {default_branch: 'develop'}}}, {info(message) {(f.messages ??= []).push(message);}}, name => {
      assert.equal(name, 'node:child_process');
      return {execFileSync(command, args, options) {
        if (command === 'python3') {
          assert.deepEqual(args, ['./dev-tools/hosted/preview/preview-eligibility.py',
            '--revalidate-deploy', '--expected-repository', 'example/FireMUD',
            '--expected-head-sha', f.run.head_sha]);
          assert.deepEqual(JSON.parse(options.input), f.pullReads === 1 ? f.pr : f.latestPr ?? f.pr);
          return execFileSync(command, args, {...options, cwd: root});
        }
        assert.equal(command, 'bash');
        assert.deepEqual(args, ['./dev-tools/hosted/preview/resolve-preview-image-tag.sh', A, '7', f.base.object.sha]);
        return f.imageTag ?? `pr-merge-${A}`;
      }};
    });
  return sent;
}
test('exact stale-base current tuple produces only the typed refresh', async () => {
  const f = fixture();
  assert.deepEqual(await execute(f), [{owner: 'example', repo: 'FireMUD',
    event_type: 'pr-runtime-base-refresh', client_payload: {pr_number: '7', base_ref: 'develop',
      base_sha: B, head_sha: C, merge_sha: A, image_tag: `pr-merge-${A}`}}]);
  assert.deepEqual(f.mergeReads, [E, A]);
});
test('a completed render failure permits repair when the plan was eligible', async () => {
  const f = fixture(); f.run.conclusion = 'failure';
  assert.equal((await execute(f)).length, 1);
});
test('the pinned Jobs endpoint identifies a plan without a row-level attempt number', async () => {
  const f = fixture(); delete f.jobs[0].run_attempt;
  assert.equal((await execute(f)).length, 1);
});
for (const [name, mutate] of Object.entries({
  wrongPath: f => f.run.path = '.github/workflows/attacker.yml',
  wrongQualifiedPath: f => f.run.path += '@refs/pull/8/merge',
  wrongEvent: f => f.run.event = 'repository_dispatch',
  unfinishedSource: f => f.run.status = 'in_progress',
  cancelledSource: f => f.run.conclusion = 'cancelled',
  failedPlan: f => { f.run.conclusion = 'failure'; f.jobs[0].conclusion = 'failure'; },
  wrongRepository: f => f.run.repository.full_name = 'fork/FireMUD',
  forkSource: f => f.run.head_repository.full_name = 'fork/FireMUD',
  absentAssociation: f => f.run.pull_requests = [],
  ambiguousAssociation: f => f.run.pull_requests.push(f.run.pull_requests[0]),
  staleHead: f => f.run.head_sha = D,
  wrongAssociatedHead: f => f.run.pull_requests[0].head.sha = D,
  closed: f => f.pr.state = 'closed',
  forkCurrent: f => f.pr.head.repo.full_name = 'fork/FireMUD',
  wrongBaseRepository: f => f.pr.base.repo.full_name = 'fork/FireMUD',
  unsupportedBase: f => f.pr.base.ref = 'feature/stack',
  missingLabels: f => delete f.pr.labels,
  malformedLabels: f => f.pr.labels = [{name: 3}],
  appBot: f => f.pr.user.login = 'app/renovate',
  bot: f => f.pr.user.login = 'renovate[bot]',
  legacyTitle: f => f.jobs[0].steps = [],
  missingTitle: f => f.jobs = [],
  spoofTitleHead: f => f.jobs[0].steps[0].name = `Preview event tuple pr-7 base-${D} head-${B} merge-${E}`,
  badRecordedParent: f => f.recordedMerge.parents[0].sha = B,
  missingRecordedParent: f => f.recordedMerge.parents.pop(),
  wrongRun: f => f.run.id = 100,
  wrongAttempt: f => f.run.run_attempt = 3,
  wrongJobAttempt: f => f.jobs[0].run_attempt = 3,
  nullJobAttempt: f => f.jobs[0].run_attempt = null,
  duplicatePlan: f => f.jobs.push(f.jobs[0]),
  duplicateTuple: f => f.jobs[0].steps.push(f.jobs[0].steps[0]),
  failedTuple: f => f.jobs[0].steps[0].conclusion = "failure",
  skippedResolvedTuple: f => f.jobs[0].steps[1].conclusion = 'skipped',
  missingResolvedTuple: f => f.jobs[0].steps.pop(),
  badResolvedParent: f => f.jobs[0].steps[1] = tupleStep('resolved', B, E),
  ambiguousResolvedTuple: f => f.jobs[0].steps.push(f.jobs[0].steps[1]),
  malformedTuple: f => f.jobs[0].steps[0].name = "Preview event tuple invalid",
  invalidBase: f => f.base.object.sha = 'invalid',
  invalidMerge: f => f.pr.merge_commit_sha = 'invalid',
  wrongMerge: f => f.merge.sha = D,
  missingParent: f => f.merge.parents.pop(),
  wrongBaseParent: f => f.merge.parents[0].sha = D,
  wrongHeadParent: f => f.merge.parents[1].sha = D,
})) test(`fails closed: ${name}`, async () => {
  const f = fixture(); mutate(f); assert.deepEqual(await execute(f), []);
});
test('a current integration event merge needs no lookup of the live merge', async () => {
  const f = fixture();
  f.jobs[0].steps = [tupleStep('event', B, E), tupleStep('resolved', B, E)];
  f.recordedMerge.parents[0].sha = B;
  assert.deepEqual(await execute(f), []);
  assert.deepEqual(f.mergeReads, [E]);
});

test('observer checks out only trusted policy and has no artifact or candidate execution', () => {
  assert.deepEqual(workflow.permissions, {});
  assert.deepEqual(workflow.jobs.refresh.permissions,
    {actions: 'read', contents: 'write', 'pull-requests': 'read'});
  assert.equal(workflow.jobs.refresh.steps.length, 2);
  assert.deepEqual(workflow.jobs.refresh.steps[0].with,
    {ref: '${{ github.event.repository.default_branch }}', 'persist-credentials': false});
  assert.match(workflow.jobs.refresh.steps[1].uses, /^actions\/github-script@[0-9a-f]{40}$/);
  assert.doesNotMatch(script, /downloadArtifact|downloadWorkflowRunArtifact|spawn\(/);
});

test('priority stacked base refreshes even when source base is current', async () => {
  const f = fixture(); f.pr.base.ref = 'feature/stack';
  f.pr.labels = [{name: 'preview:priority'}]; f.run.pull_requests[0].base.sha = D;
  f.base.object.sha = D; f.merge.parents[0].sha = D;
  const sent = await execute(f);
  assert.equal(sent.length, 1); assert.equal(sent[0].client_payload.base_ref, 'feature/stack');
});

test('canonical non-runtime base image selection does not dispatch', async () => {
  const f = fixture(); f.imageTag = B; assert.deepEqual(await execute(f), []);
});
test('unexpected canonical image selection fails closed', async () => {
  const f = fixture(); f.imageTag = 'untrusted';
  const sent = [];
  await assert.rejects(execute(f, sent), /Unexpected image/);
  assert.deepEqual(sent, []);
});

test('qualified canonical workflow path remains eligible', async () => {
  const f = fixture(); f.run.path += '@refs/pull/7/merge';
  assert.equal((await execute(f)).length, 1);
  f.run.path = '.github/workflows/preview.yml@';
  assert.deepEqual(await execute(f), []);
});
test('observer callbacks cannot replace a different source run or attempt', () => {
  const group = workflow.jobs.refresh.concurrency.group;
  assert.match(group, /workflow_run.id/);
  assert.match(group, /workflow_run.run_attempt/);
  assert.equal(workflow.jobs.refresh.concurrency['cancel-in-progress'], false);
});

test('older base classifiers cannot omit newly introduced validation modules', async () => {
  const ci = JSON.parse(execFileSync('python3', ['-c',
    'import yaml,json,sys; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))',
    path.join(root, '.github/workflows/ci.yml')], {encoding: 'utf8'}));
  const compute = ci.jobs.changes.steps.find(step => step.id === 'compute').with.script;
  const complete = JSON.parse(compute.match(/const completeModules = (\[[\s\S]*?\]);/)[1].replace(/,\s*\]/, "]"));
  for (const modules of [undefined, complete.filter(name => name !== 'recovery-controller-service'), complete]) {
    const outputs = {};
    await new AsyncFunction('require', 'github', 'context', 'core', compute)(
      name => name === 'node:fs' ? {existsSync: () => true} : {
        ALL_MODULES: modules,
        classifyGithubChangeScope: async () => ({
          runAll: false, affectedModules: ['account-service'], bootableModules: ['account-service'],
          affectedServices: ['account-service'], lightweightOnly: true,
        }),
      }, {}, {}, {setOutput: (name, value) => outputs[name] = value, warning() {}});
    if (modules === complete) {
      assert.equal(outputs.run_all, 'false');
      assert.deepEqual(JSON.parse(outputs.affected_modules), ['account-service']);
    } else {
      assert.equal(outputs.run_all, 'true');
      assert.equal(outputs.lightweight_only, 'false');
      assert.ok(JSON.parse(outputs.affected_modules).includes('recovery-controller-service'));
      assert.ok(JSON.parse(outputs.bootable_modules).includes('recovery-controller-service'));
    }
  }
});

function producer(f, event = 'repository_dispatch') {
  return {id: 100, workflow_id: 42, path: '.github/workflows/runtime-images.yml',
    event, head_branch: 'develop', head_sha: event === 'pull_request' ? C : D,
    repository: {full_name: 'example/FireMUD'}, head_repository: {full_name: 'example/FireMUD'},
    status: 'in_progress', conclusion: null,
    display_title: `Build Runtime Images secure-pr-artifact pr-7 base-${B} head-${C} merge-${A} mode-required`,
    pull_requests: [{number: 7, head: {sha: C}, base: {sha: B}}]};
}
for (const event of ['repository_dispatch', 'pull_request']) {
  for (const status of ['queued', 'waiting', 'requested', 'pending', 'in_progress']) {
    test(`active exact ${event} ${status} producer suppresses dispatch`, async () => {
      const f = fixture(); const run = producer(f, event); run.status = status; f.producers = [run];
      assert.deepEqual(await execute(f), []);
    });
  }
}
for (const [name, mutate] of Object.entries({
  otherPR: r => r.display_title = r.display_title.replace('pr-7 ', 'pr-8 '),
  otherBase: r => r.display_title = r.display_title.replace(B, D),
  otherHead: r => r.display_title = r.display_title.replace(C, D),
  otherMerge: r => r.display_title = r.display_title.replace(A, D),
  wrongWorkflow: r => r.workflow_id = 9,
  wrongPath: r => r.path = '.github/workflows/attacker.yml',
  wrongRepo: r => r.repository.full_name = 'fork/FireMUD',
  fork: r => r.head_repository.full_name = 'fork/FireMUD',
  wrongEvent: r => r.event = 'workflow_dispatch',
  wrongDispatchBranch: r => r.head_branch = 'attacker',
  completed: r => {r.status = 'completed'; r.conclusion = 'success';},
  inconsistentConclusion: r => r.conclusion = 'failure',
})) test(`unrelated/untrusted producer cannot suppress recovery: ${name}`, async () => {
  const f = fixture(); const r = producer(f); mutate(r); f.producers = [r];
  assert.equal((await execute(f)).length, 1);
});
for (const [name, mutate] of Object.entries({
  head: r => r.head_sha = D,
  associationMissing: r => r.pull_requests = [],
  associationPR: r => r.pull_requests[0].number = 8,
  associationBase: r => r.pull_requests[0].base.sha = D,
  associationHead: r => r.pull_requests[0].head.sha = D,
})) test(`PR title alone cannot suppress: ${name}`, async () => {
  const f = fixture(); const r = producer(f, 'pull_request'); mutate(r); f.producers = [r];
  assert.equal((await execute(f)).length, 1);
});
test('bounded inventory saturation fails without dispatch or polling', async () => {
  const f = fixture(); f.fullInventory = Array.from({length: 100}, () => ({}));
  const sent = [];
  await assert.rejects(execute(f, sent), /exceeds bounded lookup/);
  assert.deepEqual(sent, []);
  assert.equal(f.lookups, 3);
});
test('qualified trusted default workflow path suppresses exact dispatch', async () => {
  const f = fixture(); const r = producer(f);
  r.path += '@refs/heads/develop'; f.producers = [r];
  assert.deepEqual(await execute(f), []);
});
test('canonical eligibility refuses absent author metadata', async () => {
  const f = fixture(); delete f.pr.user; assert.deepEqual(await execute(f), []);
});

test('qualified exact PR merge workflow path suppresses native producer', async () => {
  const f = fixture(); const r = producer(f, 'pull_request');
  r.path += '@refs/pull/7/merge'; f.producers = [r];
  assert.deepEqual(await execute(f), []);
});
test('qualified other PR workflow path cannot suppress native producer', async () => {
  const f = fixture(); const r = producer(f, 'pull_request');
  r.path += '@refs/pull/8/merge'; f.producers = [r];
  assert.equal((await execute(f)).length, 1);
});

test('immutable title repairs base despite current mutable association', async () => {
  const f = fixture();
  f.run.pull_requests[0].base.sha = B;
  f.run.pull_requests.unshift({number: 99, head: {sha: C}, base: {sha: B}});
  assert.equal((await execute(f)).length, 1);
});

test('partial rerun without an exact-attempt plan gives safe full-rerun guidance', async () => {
  const f = fixture(); f.jobs = [];
  assert.deepEqual(await execute(f), []);
  assert.ok(f.messages.some(message => message.includes('Re-run all jobs')));
});

test('priority removed during active lookup refuses dispatch', async () => {
  const f = fixture();
  f.pr.base.ref = 'feature/stack';
  f.pr.labels = [{name: 'preview:priority'}];
  f.latestPr = {...f.pr, labels: []};
  assert.deepEqual(await execute(f), []);
  assert.equal(f.pullReads, 2);
});

test('event base drift can repair the independently revalidated current merge', async () => {
  const f = fixture();
  f.jobs[0].steps = [tupleStep('event', D, A), tupleStep('resolved', B, A)];
  f.recordedMerge = structuredClone(f.merge);
  assert.equal((await execute(f)).length, 1);
  assert.deepEqual(f.mergeReads, [A, A]);

  const moved = fixture();
  moved.jobs[0].steps = [tupleStep('event', D, A), tupleStep('resolved', B, A)];
  moved.recordedMerge = structuredClone(moved.merge);
  moved.merge.parents[0].sha = D;
  assert.deepEqual(await execute(moved), []);
  assert.deepEqual(moved.mergeReads, [A, A]);
});

test('a resolved historical merge can precede a second base advance', async () => {
  const intermediateBase = 'f'.repeat(40);
  const f = fixture();
  f.recordedMerge.parents[0].sha = intermediateBase;
  f.jobs[0].steps[1] = tupleStep('resolved', intermediateBase, E);
  const dispatches = await execute(f);
  assert.equal(dispatches.length, 1);
  assert.equal(dispatches[0].client_payload.base_sha, B);
  assert.equal(dispatches[0].client_payload.merge_sha, A);
  assert.deepEqual(f.mergeReads, [E, A]);
});

test('a third independently verified merge can be the resolved plan', async () => {
  const plannedMerge = 'f'.repeat(40);
  const plannedBase = '0'.repeat(40);
  for (const exact of [true, false]) {
    const f = fixture();
    f.recordedMerge.parents[0].sha = '1'.repeat(40);
    f.jobs[0].steps[1] = tupleStep('resolved', plannedBase, plannedMerge);
    f.resolvedMerge = {sha: plannedMerge, parents: [{sha: exact ? plannedBase : D}, {sha: C}]};
    const dispatches = await execute(f);
    assert.equal(dispatches.length, exact ? 1 : 0);
    if (exact) assert.equal(dispatches[0].client_payload.merge_sha, A);
    assert.deepEqual(f.mergeReads, [E, A, plannedMerge]);
  }
});

test('a branch ref advance during lookup invalidates the live merge', async () => {
  const f = fixture();
  f.latestBase = {object: {sha: 'f'.repeat(40)}};
  assert.deepEqual(await execute(f), []);
  assert.equal(f.baseReads, 2);
});

test('a newer source attempt during lookup invalidates the plan observation', async () => {
  const f = fixture(); f.latestRun = {...f.run, run_attempt: 3};
  assert.deepEqual(await execute(f), []);
  assert.equal(f.runReads, 2);
});

test('truncated or oversized attempt inventory is refused', async () => {
  for (const inventory of [{total_count: 301, jobs: []}, {total_count: 2, jobs: []}]) {
    const f = fixture(); f.inventory = inventory;
    const sent = [];
    await assert.rejects(execute(f, sent), /inventory/);
    assert.deepEqual(sent, []);
  }
});
