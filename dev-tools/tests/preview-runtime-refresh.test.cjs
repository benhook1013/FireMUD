const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {createHash} = require('node:crypto');
const {test} = require('node:test');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const admitRuntimeRefresh = require('../../.github/scripts/admit-pr-runtime-refresh.cjs');
const E = 'e'.repeat(40);
const A = 'a'.repeat(40), B = 'b'.repeat(40), C = 'c'.repeat(40), D = 'd'.repeat(40);

function admissionFixture() {
  const input = {pr_number: '7', base_ref: 'develop', base_sha: B, head_sha: C, merge_sha: A};
  const repository = 'example/FireMUD';
  const runtimePath = '.github/workflows/runtime-images.yml';
  const admissionPath = '.github/workflows/admit-pr-runtime-refresh.yml';
  const makeRun = (overrides = {}) => ({
    id: 300, run_attempt: 1, workflow_id: 42, event: 'repository_dispatch', status: 'queued', head_sha: D,
    conclusion: null, path: runtimePath, head_branch: 'develop', repository: {full_name: repository},
    head_repository: {full_name: repository}, created_at: new Date().toISOString(),
    display_title: `Build Runtime Images secure-pr-artifact pr-7 base-${B} head-${C} merge-${A} mode-required`,
    ...overrides,
  });
  const makePullRequestRun = (overrides = {}) => makeRun({
    event: 'pull_request', head_sha: C,
    pull_requests: [{number: 7, head: {sha: C}, base: {sha: B}}],
    ...overrides,
  });
  const sent = [];
  const warnings = [];
  const messages = [];
  const outputs = new Map();
  const runs = [];
  const admissionRuns = new Map();
  const claimRefs = new Map();
  const claimTags = new Map();
  const receiverJobs = new Map();
  const state = {base: B, mergeParents: [B, C], keepHidden: false, afterInventory: null,
    labels: [{name: 'preview:priority'}], nextId: 301, lookups: [], pullObservations: [],
    baseReads: 0, commitRefs: [], missingExactCommitReads: 0, waits: [], producerReads: []};
  const github = {rest: {
    pulls: {get: async ({pull_number}) => {
      assert.equal(pull_number, 7);
      return {data: {number: 7, state: 'open', head: {sha: C, repo: {full_name: repository}},
        base: {ref: input.base_ref, repo: {full_name: repository}}, mergeable: true,
        mergeable_state: 'clean', merge_commit_sha: A, labels: state.labels, user: {login: 'developer'},
        ...state.pullObservations.shift()}};
    }},
    git: {
      listMatchingRefs: async ({ref}) => ({status: 200, headers: {}, data: [...claimRefs.values()]
        .filter(entry => entry.ref.startsWith(`refs/${ref}`))}),
      getTag: async ({tag_sha}) => ({data: claimTags.get(tag_sha)}),
      createTag: async ({tag, message, object, type, tagger}) => {
        const sha = String(claimTags.size + 1).padStart(40, '0');
        const record = {sha, tag, message, object: {type, sha: object}, tagger};
        claimTags.set(sha, record);
        return {status: 201, data: record};
      },
      createRef: async ({ref, sha}) => {
        assert.ok(claimTags.has(sha));
        if (claimRefs.has(ref)) throw Object.assign(new Error('ref already exists'), {status: 422});
        const record = {ref, object: {type: 'tag', sha}};
        claimRefs.set(ref, record);
        if (/^refs\/tags\/firemud-runtime-refresh-(unsent|rejected)\//.test(ref)) {
          if (state.loseLocatorResponse) throw new Error('response lost after locator creation');
          return {status: 201, data: record};
        }
        state.afterClaim?.();
        if (state.loseClaimResponse) throw new Error('response lost after ref creation');
        return {status: 201, data: record};
      },
      getRef: async ({ref}) => {
        if (ref.startsWith('tags/')) {
          if (!claimRefs.has(`refs/${ref}`)) throw Object.assign(new Error('ref not found'), {status: 404});
          return {data: claimRefs.get(`refs/${ref}`)};
        }
        assert.equal(ref, `heads/${input.base_ref}`);
        state.baseReads++;
        return {data: {ref: `refs/heads/${input.base_ref}`, object: {sha: state.base}}};
      },
    },
    actions: {
      getWorkflow: async ({workflow_id}) => {
        if (workflow_id === 'admit-pr-runtime-refresh.yml') return {data: {id: 43, path: admissionPath}};
        assert.equal(workflow_id, 'runtime-images.yml');
        return {data: {id: 42, path: runtimePath}};
      },
      getWorkflowRun: async ({run_id}) => {
        if (admissionRuns.has(run_id)) return {data: admissionRuns.get(run_id)};
        state.producerReads.push(run_id);
        const run = runs.find(run => run.id === run_id);
        return {data: state.refreshProducer ? state.refreshProducer(run) : run};
      },
      listJobsForWorkflowRunAttempt: async ({run_id, attempt_number}) => {
        const jobs = receiverJobs.get(`${run_id}:${attempt_number}`) || [];
        return {data: {total_count: jobs.length, jobs}};
      },
      listWorkflowRuns: async ({workflow_id, status, event, per_page, page, created}) => {
        assert.equal(workflow_id, 42);
        assert.equal(per_page, 100);
        assert.ok(Number.isSafeInteger(page) && page > 0 && page <= 3);
        state.lookups.push({status, event, per_page, page, created});
        state.afterInventory?.();
        const matching = runs.filter(run => (!status || run.status === status) && run.event === event &&
          (!created || Date.parse(run.created_at) >= Date.parse(created.split('..')[0])));
        return {data: {workflow_runs: matching.slice((page - 1) * per_page, page * per_page),
          total_count: matching.length}};
      },
    },
    repos: {
      getCommit: async ({ref}) => {
        state.commitRefs.push(ref);
        if (state.alternateCommit?.sha === ref) return {data: state.alternateCommit};
        assert.equal(ref, A);
        if (state.missingExactCommitReads > 0) {
          state.missingExactCommitReads--;
          throw Object.assign(new Error('merge not yet visible'), {status: 404});
        }
        return {data: {sha: A, parents: state.mergeParents.map(sha => ({sha}))}};
      },
      createDispatchEvent: async payload => {
        sent.push(payload);
        if (state.dispatchError) throw state.dispatchError;
        // Registration becomes visible after the fixture's first two-second wait.
        state.newRun = makeRun({id: state.nextId++, created_at: state.dispatchCreatedAt ??
          new Date(Date.now() + 2000).toISOString()});
        if (state.loseDispatchResponse) throw new Error('response lost after dispatch acceptance');
        return {status: state.dispatchStatus ?? 204};
      },
    },
  }};
  const admission = {github, input, admissionAttempt: '1',
    context: {repo: {owner: 'example', repo: 'FireMUD'}, runId: 600, ref: 'refs/heads/develop', sha: D,
      eventName: 'repository_dispatch',
      payload: {action: 'pr-runtime-base-refresh-request', repository: {full_name: repository, default_branch: 'develop'}}},
  core: {warning: value => warnings.push(value), info: value => messages.push(value),
    setOutput: (name, value) => outputs.set(name, value)},
  sleep: async milliseconds => {
    state.waits.push(milliseconds);
    state.afterWait?.();
    if (!state.keepHidden && state.newRun && !runs.includes(state.newRun)) runs.push(state.newRun);
  }};
  return {admission, makeRun, makePullRequestRun, runs, admissionRuns, sent, state,
    warnings, messages, outputs, claimRefs, claimTags, receiverJobs};
}

function preparePreviewRefusal(f) {
  f.admission.input.base_ref = 'stacked';
  f.admission.input.preview_only = true;
  f.state.afterClaim = () => { f.state.labels = []; };
}

function completeOwnerReceiptAttempt(f, kind = 'unsent') {
  const run = f.makeRun({id: 600, workflow_id: 43, path: '.github/workflows/admit-pr-runtime-refresh.yml',
    status: 'completed', conclusion: 'success'});
  const job = {id: 2, run_id: run.id, head_sha: run.head_sha, name: 'Admit Exact Runtime Tuple',
    status: 'completed', conclusion: 'success',
    steps: [{name: `Runtime refresh ${kind} claim ${f.outputs.get(`${kind}_claim`)}`,
      status: 'completed', conclusion: 'success'}]};
  f.admissionRuns.set(run.id, run);
  f.receiverJobs.set(`${run.id}:${run.run_attempt}`, [job]);
  return {run, job};
}

function dispatchValidationError() {
  const url = 'https://api.github.com/repos/example/FireMUD/dispatches';
  return Object.assign(new Error('Validation Failed'), {
    status: 422,
    request: {method: 'POST', url},
    response: {status: 422, url, headers: {'x-github-request-id': 'A1:B2:C3:D4', 'x-ratelimit-remaining': '42'},
      data: {message: 'Validation Failed',
        documentation_url: 'https://docs.github.com/rest/repos/repos#create-a-repository-dispatch-event',
        errors: [{code: 'invalid', field: 'client_payload', message: 'provider response detail'}]}},
  });
}

async function admitOrdinaryRefresh(f) {
  delete f.admission.input.preview_only;
  delete f.state.afterClaim;
  f.admission.context.runId = 601;
  return admitRuntimeRefresh(f.admission);
}

function proveFixtureDispatchBase(f, run) {
  const {input} = f.admission;
  const base = createHash('sha256').update(input.base_ref).digest('hex');
  const tag = `firemud-runtime-refresh-intent/v1/pr-${input.pr_number}/${input.merge_sha}/${base}/g1`;
  const ref = `refs/tags/${tag}`;
  let sha = f.claimRefs.get(ref)?.object.sha;
  if (!sha) {
    sha = E;
    f.claimTags.set(sha, {
      sha, tag, message: JSON.stringify({...input, generation: '1', witness_sha256: 'a'.repeat(64)}) + '\n',
      object: {type: 'commit', sha: input.merge_sha},
      tagger: {name: 'FireMUD Runtime Refresh', email: 'runtime-refresh@firemud.dev',
        date: new Date(Date.parse(run.created_at) - 1000).toISOString().replace(/\.\d{3}Z$/, 'Z')},
    });
    f.claimRefs.set(ref, {ref, object: {type: 'tag', sha}});
  }
  f.receiverJobs.set(`${run.id}:${run.run_attempt}`, [{
    id: 1, run_id: run.id, run_attempt: run.run_attempt, head_sha: run.head_sha,
    name: 'Derive Runtime Image Metadata', status: 'completed',
    steps: [{
      name: `Runtime refresh claim generation 1 attempt ${run.run_attempt} tag ${sha}`,
      status: 'completed', conclusion: 'success',
    }],
  }]);
}

test('serialized admission waits for the first exact receiver before releasing the permit', async () => {
  const f = admissionFixture();
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  proveFixtureDispatchBase(f, f.runs[0]);
  assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
  const [tag] = f.claimTags.values();
  const {runtime_refresh_claim_witness: witness} = f.sent[0].client_payload;
  assert.match(witness, /^[0-9a-f]{64}$/);
  assert.deepEqual(tag.object, {type: 'commit', sha: D});
  assert.equal(tag.message, JSON.stringify({
    ...f.admission.input,
    generation: '1',
    witness_sha256: createHash('sha256').update(witness).digest('hex'),
    owner_revision: D,
  }) + '\n');
  assert.deepEqual(f.sent, [{owner: 'example', repo: 'FireMUD', event_type: 'pr-runtime-base-refresh',
    client_payload: {...f.admission.input, image_tag: `pr-merge-${A}`, runtime_refresh_claim: '1',
      runtime_refresh_claim_witness: witness},
    request: {retries: 0, redirect: 'error'}}]);
});

test('a non-native admission context cannot create a tag or dispatch', async t => {
  for (const overrides of [
    {sha: undefined}, {sha: 'invalid'}, {ref: 'refs/heads/feature'}, {eventName: 'workflow_dispatch'},
  ]) {
    await t.test(JSON.stringify(overrides), async () => {
      const f = admissionFixture();
      Object.assign(f.admission.context, overrides);
      assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
      assert.equal(f.claimTags.size, 0);
      assert.equal(f.sent.length, 0);
    });
  }
});

test('read-only identity retries require a fresh exact tuple', async () => {
  for (const [group, method] of [['pulls', 'get'], ['git', 'getRef'], ['repos', 'getCommit']]) {
    const f = admissionFixture();
    const api = f.admission.github.rest[group];
    const original = api[method];
    let reads = 0;
    api[method] = async (...args) => {
      if (++reads === 1) throw Object.assign(new Error('unavailable'), {response: {status: 503}});
      return original(...args);
    };
    f.runs.push(f.makePullRequestRun());
    assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
    assert.deepEqual(f.state.waits, [2000]);
    assert.equal(f.sent.length, 0);
  }

  const changed = admissionFixture();
  const api = changed.admission.github.rest.repos;
  api.getCommit = async () => {
    changed.state.base = D;
    throw Object.assign(new Error('unavailable'), {status: 503});
  };
  assert.equal(await admitRuntimeRefresh(changed.admission), 'obsolete');
  assert.deepEqual(changed.state.waits, [2000]);
  assert.equal(changed.sent.length, 0);
});

test('a newer exact failure visible during live identity lookup defeats stale active suppression', async () => {
  const f = admissionFixture();
  const old = f.makePullRequestRun({id: 220, created_at: '2024-01-01T00:00:00Z'});
  const failed = f.makePullRequestRun({
    id: 221, created_at: '2024-01-01T00:00:01Z', status: 'completed', conclusion: 'failure',
  });
  f.runs.push(old);
  const api = f.admission.github.rest.pulls;
  const readPull = api.get;
  let identityReads = 0;
  api.get = async (...args) => {
    if (++identityReads === 2) {
      assert.ok(f.state.producerReads.includes(old.id), 'old active proof must precede live tuple lookup');
      f.runs.push(failed);
    }
    return readPull(...args);
  };
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 1);
  assert.ok(identityReads >= 4, 'changed inventory must recheck tuple before claim and dispatch');
});

test('identity retries are bounded and do not retry authorization failure', async () => {
  for (const [status, observations] of [[503, 6], [403, 1]]) {
    const f = admissionFixture();
    let reads = 0;
    const error = Object.assign(new Error('cannot observe identity'), {status});
    f.admission.github.rest.pulls.get = async () => {
      reads++;
      throw error;
    };
    await assert.rejects(admitRuntimeRefresh(f.admission), value => value === error);
    assert.equal(reads, observations);
    assert.deepEqual(f.state.waits, Array(observations - 1).fill(2000));
    assert.equal(f.sent.length, 0);
  }
});

test('completed producers still permit an exact repair', async () => {
  for (const conclusion of ['success', 'failure']) {
    const f = admissionFixture();
    f.runs.push(f.makeRun({status: 'completed', conclusion}));
    assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
    assert.equal(f.sent.length, 1);
  }
});

test('a newer failed exact producer keeps the tuple eligible despite an older active run', async () => {
  const f = admissionFixture();
  f.runs.push(f.makeRun({id: 299, event: 'pull_request', head_sha: C,
    pull_requests: [{number: 7, head: {sha: C}, base: {sha: B}}],
    created_at: new Date(Date.now() - 70 * 60 * 1000).toISOString()}));
  f.runs.push(f.makeRun({id: 300, status: 'completed', conclusion: 'failure',
    created_at: new Date(Date.now() - 60000).toISOString()}));
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 1);
});

test('a still-active exact producer is not excluded by age or pagination', async () => {
  const f = admissionFixture();
  f.runs.push(...Array.from({length: 100}, (_, i) => f.makePullRequestRun({
    id: 400 + i, display_title: 'unrelated work',
  })));
  f.runs.push(f.makePullRequestRun({created_at: '2024-01-01T00:00:00Z'}));
  assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
  assert.deepEqual(f.sent, []);
  assert.ok(f.state.lookups.some(call => call.status === 'queued' && call.page === 2 && !call.created));
});

test('serialized admission reapplies preview policy after the inventory read only for preview requests', async () => {
  for (const previewOnly of [false, true]) {
    const f = admissionFixture();
    f.admission.input.base_ref = 'feature/stack';
    if (previewOnly) f.admission.input.preview_only = true;
    f.state.afterInventory = () => { f.state.labels = []; };
    assert.equal(await admitRuntimeRefresh(f.admission), previewOnly ? 'obsolete' : 'dispatched-observed');
    assert.equal(f.sent.length, previewOnly ? 0 : 1);
  }
});

test('preview marker stays within the admission request and the schema stays closed', async () => {
  const f = admissionFixture();
  f.admission.input.preview_only = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(Object.hasOwn(f.sent[0].client_payload, 'preview_only'), false);
  for (const invalid of [{preview_only: 'true'}, {preview_only: false}, {preview_only: true, extra: true}]) {
    const bad = admissionFixture();
    Object.assign(bad.admission.input, invalid);
    await assert.rejects(admitRuntimeRefresh(bad.admission), /Invalid runtime refresh input/);
    assert.deepEqual(bad.sent, []);
  }
});

test('admission refuses a base that moves after the inventory read', async () => {
  const f = admissionFixture();
  f.state.afterInventory = () => { f.state.base = D; };
  assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete');
  assert.deepEqual(f.sent, []);
});

test('admission refuses the exact merge SHA when its ordered parents disagree', async () => {
  const f = admissionFixture();
  f.state.mergeParents = [C, B];
  assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete');
  assert.deepEqual(f.sent, []);
});

test('admission rereads PR and base through pending mergeability, a stale base parent and missing test merge', async () => {
  const f = admissionFixture();
  f.state.pullObservations = [
    {mergeable: null, merge_commit_sha: null},
    {mergeable_state: 'unknown'},
    {merge_commit_sha: E},
  ];
  f.state.alternateCommit = {sha: E, parents: [{sha: D}, {sha: C}]};
  f.state.missingExactCommitReads = 1;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.deepEqual(f.state.commitRefs, [E, A, A, A, A]);
  assert.equal(f.state.baseReads, 7);
  assert.deepEqual(f.state.waits, [2000, 2000, 2000, 2000, 2000]);
  assert.equal(f.sent[0].client_payload.merge_sha, A);
});

test('admission bounds waiting for merge computation and aborts immediately on definitive drift', async () => {
  const pending = {mergeable: null, merge_commit_sha: null};
  for (const [scenario, configure, expectedWaits, expectedRefs] of [
    ['persistent calculation', f => { f.state.pullObservations = Array(6).fill(pending); }, 5, 6],
    ['conflict', f => { f.state.pullObservations = [pending, {mergeable: false, mergeable_state: 'dirty'}]; }, 1, 1],
    ['changed head', f => { f.state.pullObservations = [pending,
      {head: {sha: D, repo: {full_name: 'example/FireMUD'}}}]; }, 1, 1],
    ['moved base', f => {
      f.state.pullObservations = [pending];
      f.state.afterWait = () => { f.state.base = D; };
    }, 1, 2],
    ['different merge for exact parents', f => {
      f.state.pullObservations = [{merge_commit_sha: E}];
      f.state.alternateCommit = {sha: E, parents: [{sha: B}, {sha: C}]};
    }, 0, 1],
  ]) {
    const f = admissionFixture();
    configure(f);
    assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete', scenario);
    assert.deepEqual(f.sent, [], scenario);
    assert.deepEqual(f.state.lookups, [], scenario);
    assert.equal(f.state.waits.length, expectedWaits, scenario);
    assert.equal(f.state.baseReads, expectedRefs, scenario);
  }
});

test('admission suppresses only when the selected active producer still proves the exact attempt', async () => {
  const f = admissionFixture();
  f.runs.push(f.makePullRequestRun());
  f.state.refreshProducer = run => ({...run, status: 'in_progress'});
  assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
  assert.deepEqual(f.state.producerReads, [300, 300]);
  assert.deepEqual(f.sent, []);
});

test('a stale or unavailable selected producer leaves an exact refresh eligible', async () => {
  const invalidProducers = [
    () => { throw Object.assign(new Error('missing run'), {status: 404}); },
    () => null,
    run => ({...run, id: run.id + 1}),
    run => ({...run, run_attempt: run.run_attempt + 1}),
    ...[undefined, null, 0, -1, '1'].map(run_attempt => run => ({...run, run_attempt})),
    run => ({...run, event: 'repository_dispatch'}),
    run => ({...run, display_title: run.display_title.replace(A, E)}),
    run => ({...run, head_sha: E}),
    run => ({...run, created_at: new Date(Date.parse(run.created_at) + 1000).toISOString()}),
    run => ({...run, status: 'completed', conclusion: 'success'}),
    run => ({...run, status: 'completed', conclusion: 'failure'}),
    run => ({...run, status: 'completed', conclusion: 'cancelled'}),
  ];
  for (const afterFinalTuple of [false, true]) {
    for (const refreshProducer of invalidProducers) {
      const f = admissionFixture();
      f.runs.push(f.makePullRequestRun());
      f.state.refreshProducer = run => !afterFinalTuple || f.state.commitRefs.length >= 2 ?
        refreshProducer(run) : run;
      assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
      assert.deepEqual(f.state.producerReads, afterFinalTuple ? [300, 300] : [300]);
      assert.deepEqual(f.state.commitRefs, afterFinalTuple ? [A, A, A, A] : [A, A, A]);
      assert.equal(f.sent.length, 1);
    }
  }
});

test('a producer that completes while the final live tuple is pending cannot suppress repair', async () => {
  const f = admissionFixture();
  f.runs.push(f.makePullRequestRun());
  f.state.pullObservations = [{}, {mergeable: null, merge_commit_sha: null}];
  f.state.afterWait = () => { f.runs[0] = {...f.runs[0], status: 'completed', conclusion: 'failure'}; };
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.deepEqual(f.state.producerReads, [300]);
  assert.ok(f.state.lookups.some(call => call.event === 'pull_request' && call.status === 'completed'));
  assert.deepEqual(f.state.commitRefs, [A, A, A, A]);
  assert.deepEqual(f.state.waits, [2000, 2000]);
  assert.equal(f.sent.length, 1);
});

test('admission rereads the pinned comparison after the producer lookup before fallback', async () => {
  for (const afterFinalTuple of [false, true]) {
    const f = admissionFixture();
    f.runs.push(f.makePullRequestRun());
    f.state.refreshProducer = run => {
      if (afterFinalTuple && f.state.commitRefs.length < 2) return run;
      f.state.base = D;
      return {...run, status: 'completed', conclusion: 'failure'};
    };
    assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete');
    assert.deepEqual(f.state.producerReads, afterFinalTuple ? [300, 300] : [300]);
    assert.deepEqual(f.sent, []);
  }
});

test('only a same-repository exact native producer can suppress the repair', async () => {
  const matched = admissionFixture();
  matched.runs.push(matched.makePullRequestRun());
  assert.equal(await admitRuntimeRefresh(matched.admission), 'already-active');
  assert.deepEqual(matched.state.producerReads, [300, 300]);
  assert.deepEqual(matched.sent, []);

  const foreign = admissionFixture();
  foreign.runs.push(foreign.makePullRequestRun({
    head_repository: {full_name: 'example/Other'},
  }));
  assert.equal(await admitRuntimeRefresh(foreign.admission), 'dispatched-observed');
  assert.deepEqual(foreign.state.producerReads, []);
  assert.equal(foreign.sent.length, 1);
});

test('indeterminate dispatch visibility does not silently consume a repair or cause an immediate duplicate', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  assert.equal(f.sent.length, 1);
  assert.ok(f.warnings.length > 0);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
});

test('a lost dispatch acknowledgement keeps the exact tuple held across callbacks', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  f.state.loseDispatchResponse = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
  f.state.loseDispatchResponse = false;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
  assert.equal(f.claimRefs.size, 1);
});

test('a lost atomic claim acknowledgement does not authorize either callback to send', async () => {
  const f = admissionFixture();
  f.state.loseClaimResponse = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.claimRefs.size, 1);
  f.state.loseClaimResponse = false;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.deepEqual(f.sent, []);
});

test('an unexpected dispatch response status cannot be described as accepted', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  f.state.dispatchStatus = 202;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
});

test('an exact native provider rejection receipt allows one currently valid successor', async () => {
  const f = admissionFixture();
  f.state.dispatchError = dispatchValidationError();
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatch-rejected');
  assert.equal(f.sent.length, 1);
  assert.deepEqual(f.state.waits, []);
  const [claim] = f.claimTags.values();
  const ref = `refs/tags/firemud-runtime-refresh-rejected/v1/${claim.sha}/600/1`;
  assert.deepEqual(f.claimRefs.get(ref), {ref, object: {type: 'tag', sha: claim.sha}});
  assert.deepEqual([...f.outputs], [['rejected_claim', `generation 1 run 600 attempt 1 tag ${claim.sha}`]]);
  const witness = f.sent[0].client_payload.runtime_refresh_claim_witness;
  const outputText = [...f.warnings, ...f.messages, ...f.outputs.values()].join('\n');
  assert.ok(!outputText.includes(witness));
  assert.ok(!outputText.includes('provider response detail'));

  delete f.state.dispatchError;
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
  completeOwnerReceiptAttempt(f, 'rejected');
  assert.equal(await admitOrdinaryRefresh(f), 'dispatched-observed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['1', '2']);
});

test('ambiguous dispatch responses never produce rejection or no-send receipts', async t => {
  const variants = {
    'bare 422': e => { delete e.response; },
    'response status mismatch': e => { e.response.status = 429; },
    'thrown status mismatch': e => { e.status = 503; },
    'different response endpoint': e => { e.response.url += '/unexpected'; },
    'different request method': e => { e.request.method = 'GET'; },
    'missing provider request ID': e => { delete e.response.headers['x-github-request-id']; },
    'retry-after header': e => { e.response.headers['Retry-After'] = '60'; },
    'rate limit exhausted': e => { e.response.headers['x-ratelimit-remaining'] = '0'; },
    'secondary throttle header': e => { e.response.headers['X-GitHub-Throttle'] = 'on'; },
    'secondary limit message': e => { e.message = 'secondary rate limit'; },
    'spam message': e => { e.response.data.message = 'The endpoint has been spammed'; },
    'abuse detail': e => { e.response.data.errors[0].message = 'abuse prevention'; },
    'wrong documentation link': e => { e.response.data.documentation_url = 'https://docs.github.com/rest'; },
    'malformed body': e => { e.response.data = 'Validation Failed'; },
    'no field errors': e => { e.response.data.errors = []; },
    'unknown validation code': e => { e.response.data.errors[0].code = 'custom'; },
    'unrelated validation field': e => { e.response.data.errors[0].field = 'other'; },
  };
  for (const [name, change] of Object.entries(variants)) {
    await t.test(name, async () => {
      const f = admissionFixture();
      f.state.dispatchError = dispatchValidationError();
      change(f.state.dispatchError);
      assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
      assert.equal(f.outputs.size, 0);
      assert.equal(f.claimRefs.size, 1);
      delete f.state.dispatchError;
      assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
      assert.equal(f.sent.length, 1);
    });
  }
});

test('a rejected claim stays held if the locator acknowledgment or the marker write fails', async t => {
  await t.test('locator response lost', async () => {
    const f = admissionFixture();
    f.state.dispatchError = dispatchValidationError();
    f.state.loseLocatorResponse = true;
    assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
    assert.equal(f.claimRefs.size, 2);
    assert.equal(f.outputs.size, 0);
    completeOwnerReceiptAttempt(f, 'rejected');
    assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
    assert.equal(f.sent.length, 1);
  });
  await t.test('marker output write failed', async () => {
    const f = admissionFixture();
    f.state.dispatchError = dispatchValidationError();
    f.admission.core.setOutput = () => { throw new Error('output unavailable'); };
    await assert.rejects(admitRuntimeRefresh(f.admission), /output unavailable/);
    assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
    assert.equal(f.sent.length, 1);
  });
});

test('a comparison that moves during rejection receipt lookup cannot reserve a successor generation', async () => {
  const f = admissionFixture();
  f.state.dispatchError = dispatchValidationError();
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatch-rejected');
  completeOwnerReceiptAttempt(f, 'rejected');
  const readJobs = f.admission.github.rest.actions.listJobsForWorkflowRunAttempt;
  f.admission.github.rest.actions.listJobsForWorkflowRunAttempt = async request => {
    f.state.base = D;
    return readJobs(request);
  };
  assert.equal(await admitOrdinaryRefresh(f), 'obsolete');
  assert.equal(f.claimTags.size, 1);
  assert.equal(f.sent.length, 1);
});

test('admission retries a transient identity read after acknowledging the claim', async () => {
  const f = admissionFixture();
  const exactPull = f.admission.github.rest.pulls.get;
  let pending = false;
  let transientReads = 0;
  f.state.afterClaim = () => { pending = true; };
  f.admission.github.rest.pulls.get = async request => {
    if (pending) {
      pending = false;
      transientReads++;
      throw Object.assign(new Error('temporarily unavailable'), {status: 502});
    }
    return exactPull(request);
  };
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(transientReads, 1);
  assert.equal(f.claimRefs.size, 1);
  assert.equal(f.sent.length, 1);
  const dispatch = f.sent[0].client_payload;
  assert.equal(dispatch.runtime_refresh_claim, '1');
  assert.equal(dispatch.merge_sha, A);
});

test('a comparison moving during the claim is never dispatched', async () => {
  const f = admissionFixture();
  f.state.afterClaim = () => { f.state.base = D; };
  assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete');
  assert.equal(f.claimRefs.size, 1);
  assert.deepEqual(f.sent, []);
});

test('an exact successful native preview refusal allows ordinary CI to claim the next generation', async () => {
  const f = admissionFixture();
  preparePreviewRefusal(f);
  assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete');
  assert.deepEqual(f.sent, []);
  const [claim] = f.claimTags.values();
  const locatorRef = `refs/tags/firemud-runtime-refresh-unsent/v1/${claim.sha}/600/1`;
  assert.deepEqual(f.claimRefs.get(locatorRef), {ref: locatorRef, object: {type: 'tag', sha: claim.sha}});
  assert.equal(f.outputs.get('unsent_claim'), `generation 1 run 600 attempt 1 tag ${claim.sha}`);
  completeOwnerReceiptAttempt(f);

  assert.equal(await admitOrdinaryRefresh(f), 'dispatched-observed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['2']);
});

test('only the successful current admission attempt can prove an unsent or rejected claim', async t => {
  const mismatches = {
    'duplicate locator': (f, {kind}) => {
      const locator = [...f.claimRefs.values()].find(ref => ref.ref.includes(`/firemud-runtime-refresh-${kind}/`));
      const ref = locator.ref.replace('/600/1', '/600/2');
      f.claimRefs.set(ref, {...locator, ref});
    },
    'missing native run': f => f.admissionRuns.clear(),
    'wrong workflow': (f, {run}) => { run.workflow_id = 42; },
    'wrong event': (f, {run}) => { run.event = 'workflow_dispatch'; },
    'wrong branch': (f, {run}) => { run.head_branch = 'stacked'; },
    'wrong qualified path': (f, {run}) => { run.path += '@refs/heads/stacked'; },
    'wrong repository': (f, {run}) => { run.head_repository.full_name = 'fork/FireMUD'; },
    'wrong owner revision': (f, {run, job}) => { run.head_sha = E; job.head_sha = E; },
    'superseded attempt': (f, {run}) => { run.run_attempt++; },
    'failed run': (f, {run}) => { run.conclusion = 'failure'; },
    'wrong job head': (f, {job}) => { job.head_sha = E; },
    'skipped marker': (f, {job}) => { job.steps[0].conclusion = 'skipped'; },
    'duplicate marker': (f, {job}) => { job.steps.push(job.steps[0]); },
    'wrong tag': (f, {job}) => { job.steps[0].name = job.steps[0].name.replace(/[0-9a-f]{40}$/, E); },
    'wrong marker kind': (f, {job, kind}) => {
      job.steps[0].name = job.steps[0].name.replace(kind, kind === 'unsent' ? 'rejected' : 'unsent');
    },
    'attempt moves during marker read': (f, {run}) => {
      const read = f.admission.github.rest.actions.listJobsForWorkflowRunAttempt;
      f.admission.github.rest.actions.listJobsForWorkflowRunAttempt = async request => {
        run.run_attempt++;
        return read(request);
      };
    },
  };
  for (const kind of ['unsent', 'rejected']) {
    for (const [name, change] of Object.entries(mismatches)) {
      await t.test(`${kind}: ${name}`, async () => {
        const f = admissionFixture();
        if (kind === 'unsent') preparePreviewRefusal(f);
        else f.state.dispatchError = dispatchValidationError();
        assert.equal(await admitRuntimeRefresh(f.admission), kind === 'unsent' ? 'obsolete' : 'dispatch-rejected');
        change(f, {...completeOwnerReceiptAttempt(f, kind), kind});
        delete f.state.dispatchError;
        assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
        assert.equal(f.sent.length, kind === 'unsent' ? 0 : 1);
      });
    }
  }
});

test('a lost locator acknowledgement does not produce a preview refusal marker', async () => {
  const f = admissionFixture();
  preparePreviewRefusal(f);
  f.state.loseLocatorResponse = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.claimRefs.size, 2);
  assert.equal(f.outputs.size, 0);
  completeOwnerReceiptAttempt(f);
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
  assert.deepEqual(f.sent, []);
});

test('losing preview claim ownership acknowledgement never creates a refusal locator', async () => {
  const f = admissionFixture();
  preparePreviewRefusal(f);
  f.state.loseClaimResponse = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.claimRefs.size, 1);
  assert.equal(f.outputs.size, 0);
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
  assert.deepEqual(f.sent, []);
});

test('a failed preview refusal output write fails the owning step', async () => {
  const f = admissionFixture();
  preparePreviewRefusal(f);
  f.admission.core.setOutput = () => { throw new Error('output unavailable'); };
  await assert.rejects(admitRuntimeRefresh(f.admission), /output unavailable/);
  assert.deepEqual(f.sent, []);
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
});

test('a preview that invokes dispatch with ambiguous acceptance cannot record a refusal', async () => {
  const f = admissionFixture();
  preparePreviewRefusal(f);
  delete f.state.afterClaim;
  f.state.loseDispatchResponse = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.claimRefs.size, 1);
  assert.equal(f.outputs.size, 0);
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
  assert.equal(f.sent.length, 1);
});

test('advancing a generation requires one exact terminal receiver with a successful claim marker', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  const run = {...f.state.newRun, status: 'completed', conclusion: 'failure'};
  f.runs.push(run);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
  const [tag] = f.claimTags.values();
  const jobs = [{id: 1, run_id: run.id, run_attempt: run.run_attempt, head_sha: run.head_sha,
    name: 'Derive Runtime Image Metadata', status: 'completed', conclusion: 'success',
    steps: [{name: `Runtime refresh claim generation 1 attempt 1 tag ${tag.sha}`,
      status: 'completed', conclusion: 'success'}]}];
  const legacy = f.makeRun({id: 299, created_at: run.created_at, status: 'completed', conclusion: 'success'});
  f.runs.push(legacy);
  f.receiverJobs.set(`${legacy.id}:${legacy.run_attempt}`, [{...jobs[0], id: 2, run_id: legacy.id,
    steps: [{name: 'Runtime refresh claim generation  attempt  tag ', status: 'completed', conclusion: 'skipped'}]}]);
  f.receiverJobs.set(`${run.id}:${run.run_attempt}`, [{...jobs[0], run_attempt: run.run_attempt + 1}]);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
  delete jobs[0].run_attempt;
  f.receiverJobs.set(`${run.id}:${run.run_attempt}`, jobs);
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['1', '2']);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 2);
});

test('attempt-scoped receiver accepts a sibling job without a redundant attempt field', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  const [tag] = f.claimTags.values();
  const run = {...f.state.newRun, status: 'completed', conclusion: 'failure', run_attempt: 2};
  f.runs.push(run);
  f.receiverJobs.set(`${run.id}:2`, [
    {id: 1, run_id: run.id, run_attempt: 2, head_sha: run.head_sha,
      name: 'Derive Runtime Image Metadata', status: 'completed', conclusion: 'success',
      steps: [{name: `Runtime refresh claim generation 1 attempt 2 tag ${tag.sha}`,
        status: 'completed', conclusion: 'success'}]},
    {id: 2, run_id: run.id, head_sha: run.head_sha, name: 'Build Runtime Images',
      status: 'completed', conclusion: 'failure'},
  ]);
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['1', '2']);
});

test('a successful marker carried from another attempt cannot advance the claim', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  const [tag] = f.claimTags.values();
  const run = {...f.state.newRun, status: 'completed', conclusion: 'failure', run_attempt: 2};
  f.runs.push(run);
  f.receiverJobs.set(`${run.id}:2`, [{id: 1, run_id: run.id, run_attempt: 2, head_sha: run.head_sha,
    name: 'Derive Runtime Image Metadata', status: 'completed', conclusion: 'success',
    steps: [{name: `Runtime refresh claim generation 1 attempt 1 tag ${tag.sha}`,
      status: 'completed', conclusion: 'success'}]}]);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
});

test('an equal commit on a different base ref gets an independent exact claim', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  f.admission.input.base_ref = 'feature/stack';
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  assert.equal(f.claimRefs.size, 2);
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['1', '1']);
});

test('a claim with inconsistent annotation or tag target stays held despite a native terminal receiver', async t => {
  const variants = {
    'different target': record => { record.object.sha = A; },
    'malformed owner revision': record => {
      record.message = JSON.stringify({...JSON.parse(record.message), owner_revision: null}) + '\n';
      record.object.sha = A;
    },
    'noncanonical annotation bytes': record => { record.message += '\n'; },
  };
  for (const [name, change] of Object.entries(variants)) {
    await t.test(name, async () => {
      const f = admissionFixture();
      assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
      const [run] = f.runs;
      run.status = 'completed';
      run.conclusion = 'success';
      proveFixtureDispatchBase(f, run);
      const [record] = f.claimTags.values();
      change(record);
      assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
      assert.equal(f.sent.length, 1);
    });
  }
});

test('an ambiguous legacy merge-target claim retains its generation until a terminal receiver proves it', async () => {
  const f = admissionFixture();
  const legacyRun = f.makeRun({status: 'completed', conclusion: 'success'});
  proveFixtureDispatchBase(f, legacyRun);
  const prior = structuredClone(f.claimTags.get(E));
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 0);

  f.runs.push(legacyRun);
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent[0].client_payload.runtime_refresh_claim, '2');
  assert.deepEqual(f.claimTags.get(E), prior);
  const successor = [...f.claimTags.values()].find(tag => tag.tag.endsWith('/g2'));
  assert.deepEqual(successor.object, {type: 'commit', sha: D});
  assert.equal(JSON.parse(successor.message).owner_revision, D);
});

test('unseen same-second activity is ambiguous dispatch attribution and never triggers a retry', async t => {
  t.mock.method(Date, 'now', () => Date.parse('2026-11-22T12:00:00.750Z'));
  for (const milliseconds of ['000', '749', '750', '999']) {
    const f = admissionFixture();
    f.state.keepHidden = true;
    const unseen = f.makeRun({created_at: `2026-11-22T12:00:00.${milliseconds}Z`});
    f.state.afterWait = () => { if (!f.runs.includes(unseen)) f.runs.push(unseen); };
    assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
    assert.equal(f.sent.length, 1);
    assert.equal(f.state.waits.length, 7);
    assert.ok(f.warnings.some(warning => warning.includes('No exact active runtime producer became visible')));
  }
});

test('unseen exact activity in the next second can confirm bounded dispatch observation', async t => {
  t.mock.method(Date, 'now', () => Date.parse('2026-11-22T12:00:00.750Z'));
  const f = admissionFixture();
  f.state.dispatchCreatedAt = '2026-11-22T12:00:01Z';
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 1);
  assert.deepEqual(f.state.waits, [2000]);
});

test('active dispatch suppression proves the exact base-ref claim and current attempt', async () => {
  const f = admissionFixture();
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  const run = f.runs[0];
  const [ref] = f.claimRefs.values();
  const [delivery] = f.sent;
  const generation = delivery.client_payload.runtime_refresh_claim;
  f.receiverJobs.set(`${run.id}:${run.run_attempt}`, [{
    id: 1, name: 'Derive Runtime Image Metadata', status: 'completed',
    run_id: run.id, run_attempt: run.run_attempt, head_sha: run.head_sha,
    steps: [{
      name: `Runtime refresh claim generation ${generation} attempt ${run.run_attempt} tag ${ref.object.sha}`,
      status: 'completed', conclusion: 'success',
    }],
  }]);
  assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
  assert.equal(f.sent.length, 1);

  f.admission.input.base_ref = 'stack/retargeted';
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 2);
  assert.equal(f.sent[1].client_payload.base_ref, 'stack/retargeted');
  assert.equal(f.claimRefs.size, 2);
});

test('an unmarked native producer without the exact-base claim cannot suppress claim admission', async () => {
  const f = admissionFixture();
  f.runs.push(f.makeRun());
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 1);
});

test('both wake inputs retain intent in one non-replacing exact tuple queue on the default branch', () => {
  const admissionWorkflow = JSON.parse(execFileSync('python3', ['-c',
    'import yaml,json,sys; print(json.dumps(yaml.load(open(sys.argv[1]), Loader=yaml.BaseLoader)))',
    path.join(root, '.github/workflows/admit-pr-runtime-refresh.yml')], {encoding: 'utf8'}));
  assert.deepEqual(admissionWorkflow.on, {repository_dispatch: {types: ['pr-runtime-base-refresh-request']}});
  const job = admissionWorkflow.jobs.dispatch;
  assert.equal(job.if, "${{ github.event.action == 'pr-runtime-base-refresh-request' && github.ref == format('refs/heads/{0}', github.event.repository.default_branch) }}");
  assert.deepEqual(job.concurrency, {
    group: 'admit-pr-runtime-refresh-${{ github.event.client_payload.pr_number }}-${{ github.event.client_payload.base_sha }}-${{ github.event.client_payload.head_sha }}-${{ github.event.client_payload.merge_sha }}',
    'cancel-in-progress': 'false',
    queue: 'max',
  });
  assert.deepEqual(job.permissions, {actions: 'read', contents: 'write', 'pull-requests': 'read'});
  assert.deepEqual(job.steps.find(step => step.uses.startsWith('actions/checkout@')).with,
    {ref: '${{ github.workflow_sha }}', 'persist-credentials': 'false'});
  assert.match(job.steps.find(step => step.id === 'admit').with.script, /input: context.payload.client_payload/);
  for (const [marker, kind] of [[job.steps.at(-2), 'unsent'], [job.steps.at(-1), 'rejected']]) {
    assert.equal(marker.name, `Runtime refresh ${kind} claim \${{ steps.admit.outputs.${kind}_claim }}`);
    assert.equal(marker.if, `\${{ success() && steps.admit.outputs.${kind}_claim != '' }}`);
    assert.equal(marker['continue-on-error'], undefined);
  }
});
