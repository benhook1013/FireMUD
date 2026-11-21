module.exports = async function admitPrRuntimeRefresh({
  github,
  context,
  core,
  input,
  sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)),
}) {
  const runtimePath = '.github/workflows/runtime-images.yml';
  const requiredFields = ['pr_number', 'base_ref', 'base_sha', 'head_sha', 'merge_sha'];
  const shaPattern = /^[0-9a-f]{40}$/;
  const activeStatuses = new Set(['queued', 'waiting', 'requested', 'pending', 'in_progress']);
  const inventoryEvents = ['pull_request', 'repository_dispatch'];

  const fail = message => { throw new Error(message); };
  const isRecord = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const isPositiveInteger = value => Number.isSafeInteger(value) && value > 0;
  const isSafeBranchRef = value => {
    if (typeof value !== 'string' || value.length === 0 || value === '@' ||
        value.startsWith('/') || value.endsWith('/') || value.endsWith('.') ||
        value.includes('//') || value.includes('..') || value.includes('@{') ||
        /[\u0000-\u0020\u007f~^:?*\\\[]/.test(value)) return false;
    return value.split('/').every(part => part.length > 0 && part[0] !== '.' && !part.endsWith('.lock'));
  };
  const isNotFound = error => error?.status === 404 || error?.response?.status === 404;
  const dataOf = response => response && isRecord(response.data) ? response.data : null;

  if (!isRecord(input) || Reflect.ownKeys(input).length !== requiredFields.length ||
      requiredFields.some(field => !Object.prototype.hasOwnProperty.call(input, field))) {
    fail('Invalid runtime refresh input.');
  }
  if (requiredFields.some(field => typeof input[field] !== 'string')) fail('Invalid runtime refresh input.');
  if (!/^[1-9][0-9]*$/.test(input.pr_number) || !Number.isSafeInteger(Number(input.pr_number)) ||
      Number(input.pr_number) <= 0 || !isSafeBranchRef(input.base_ref) ||
      !shaPattern.test(input.base_sha) || !shaPattern.test(input.head_sha) || !shaPattern.test(input.merge_sha)) {
    fail('Invalid runtime refresh input.');
  }

  const repository = context?.payload?.repository;
  const contextRepo = context?.repo;
  if (!isRecord(repository) || !isRecord(contextRepo) ||
      typeof contextRepo.owner !== 'string' || contextRepo.owner.length === 0 ||
      typeof contextRepo.repo !== 'string' || contextRepo.repo.length === 0 ||
      repository.full_name !== `${contextRepo.owner}/${contextRepo.repo}` ||
      !isSafeBranchRef(repository.default_branch)) {
    fail('Repository identity or default branch is unavailable.');
  }
  const fullName = repository.full_name;
  const defaultBranch = repository.default_branch;
  const pullNumber = Number(input.pr_number);

  const currentIdentity = async () => {
    let pullResponse;
    try {
      pullResponse = await github.rest.pulls.get({...contextRepo, pull_number: pullNumber});
    } catch (error) {
      if (isNotFound(error)) return false;
      throw error;
    }
    const pull = dataOf(pullResponse);
    if (!pull || pull.number !== pullNumber || pull.state !== 'open' ||
        pull.head?.repo?.full_name !== fullName || pull.base?.repo?.full_name !== fullName ||
        pull.head?.sha !== input.head_sha || pull.base?.ref !== input.base_ref ||
        pull.mergeable !== true || typeof pull.mergeable_state !== 'string' ||
        ['dirty', 'conflicting', 'unknown'].includes(pull.mergeable_state) ||
        pull.merge_commit_sha !== input.merge_sha) return false;

    let refResponse;
    try {
      refResponse = await github.rest.git.getRef({...contextRepo, ref: `heads/${input.base_ref}`});
    } catch (error) {
      if (isNotFound(error)) return false;
      throw error;
    }
    const ref = dataOf(refResponse);
    if (ref?.ref !== `refs/heads/${input.base_ref}` || ref.object?.sha !== input.base_sha) return false;

    let commitResponse;
    try {
      commitResponse = await github.rest.repos.getCommit({...contextRepo, ref: input.merge_sha});
    } catch (error) {
      if (isNotFound(error)) return false;
      throw error;
    }
    const commit = dataOf(commitResponse);
    return commit?.sha === input.merge_sha && Array.isArray(commit.parents) &&
      commit.parents.length === 2 && commit.parents[0]?.sha === input.base_sha &&
      commit.parents[1]?.sha === input.head_sha;
  };

  if (!await currentIdentity()) {
    core.info('Runtime refresh tuple is obsolete; no dispatch.');
    return 'obsolete';
  }

  const {data: runtimeWorkflow} = await github.rest.actions.getWorkflow({
    ...contextRepo,
    workflow_id: 'runtime-images.yml',
  });
  if (runtimeWorkflow?.path !== runtimePath || !isPositiveInteger(runtimeWorkflow.id)) {
    fail('Runtime workflow identity is unproven.');
  }

  const expectedTitle = 'Build Runtime Images secure-pr-artifact ' +
    `pr-${input.pr_number} base-${input.base_sha} head-${input.head_sha} merge-${input.merge_sha} mode-required`;
  const exactNativeTuple = run => {
    if (!isRecord(run) || run.workflow_id !== runtimeWorkflow.id ||
        run.display_title !== expectedTitle || run.repository?.full_name !== fullName ||
        run.head_repository?.full_name !== fullName) return false;

    const barePath = run.path === runtimePath;
    if (run.event === 'repository_dispatch') {
      return (barePath || run.path === `${runtimePath}@${defaultBranch}` ||
        run.path === `${runtimePath}@refs/heads/${defaultBranch}`) &&
        run.head_branch === defaultBranch;
    }
    if (run.event === 'pull_request') {
      const associations = run.pull_requests;
      return (barePath || run.path === `${runtimePath}@refs/pull/${pullNumber}/merge`) &&
        run.head_sha === input.head_sha && Array.isArray(associations) && associations.length === 1 &&
        associations[0]?.number === pullNumber && associations[0]?.head?.sha === input.head_sha &&
        associations[0]?.base?.sha === input.base_sha;
    }
    return false;
  };
  const validChronology = value => typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) &&
    Number.isFinite(Date.parse(value));
  const isExactActiveRun = run => exactNativeTuple(run) &&
    isPositiveInteger(run.id) && isPositiveInteger(run.run_attempt) &&
    activeStatuses.has(run.status) && run.conclusion == null && validChronology(run.created_at);
  const secondsUtc = timestamp => new Date(timestamp).toISOString().replace(/\.\d{3}Z$/, 'Z');

  let inventoryComplete = true;
  let newestExact = null;
  const observedRunIds = new Set();
  const createdSince = secondsUtc(Date.now() - 30 * 60 * 1000);
  try {
    const exactRuns = [];
    for (const event of inventoryEvents) {
      const {data: inventory} = await github.rest.actions.listWorkflowRuns({
        ...contextRepo,
        workflow_id: runtimeWorkflow.id,
        event,
        created: `${createdSince}..*`,
        per_page: 100,
        page: 1,
      });
      if (!Number.isSafeInteger(inventory?.total_count) || inventory.total_count < 0 ||
          inventory.total_count > 100 || !Array.isArray(inventory.workflow_runs) ||
          inventory.workflow_runs.length !== inventory.total_count) {
        throw new Error('Incomplete runtime run inventory.');
      }
      for (const run of inventory.workflow_runs) {
        if (run?.event !== event) throw new Error('Runtime inventory contains an unexpected event.');
        if (!exactNativeTuple(run)) continue;
        if (!isPositiveInteger(run.id) || !isPositiveInteger(run.run_attempt) ||
            !validChronology(run.created_at) || Date.parse(run.created_at) < Date.parse(createdSince)) {
          throw new Error('Exact runtime run has invalid native chronology.');
        }
        const identity = `${run.event}:${run.id}:${run.run_attempt}`;
        if (observedRunIds.has(identity)) throw new Error('Duplicate exact runtime run identity.');
        observedRunIds.add(identity);
        if (activeStatuses.has(run.status)) {
          if (run.conclusion != null) throw new Error('Active runtime run has a conclusion.');
        } else if (run.status !== 'completed' || typeof run.conclusion !== 'string' || run.conclusion.length === 0) {
          throw new Error('Exact runtime run status is unclear.');
        }
        exactRuns.push(run);
      }
    }

    exactRuns.sort((left, right) => Date.parse(right.created_at) - Date.parse(left.created_at));
    if (exactRuns.length > 0) {
      if (exactRuns.length > 1 && Date.parse(exactRuns[0].created_at) === Date.parse(exactRuns[1].created_at)) {
        throw new Error('Newest exact runtime runs have equal chronology.');
      }
      newestExact = exactRuns[0];
    }
  } catch {
    inventoryComplete = false;
    core.warning('Runtime active-run inventory was incomplete or ambiguous; preserving the required refresh fallback.');
  }

  if (!await currentIdentity()) {
    core.info('Runtime refresh tuple changed during admission; no dispatch.');
    return 'obsolete';
  }
  if (inventoryComplete && newestExact && isExactActiveRun(newestExact)) {
    core.info('The unique newest exact runtime producer is already active; no dispatch.');
    return 'already-active';
  }

  const dispatchCreatedSince = secondsUtc(Date.now());
  await github.rest.repos.createDispatchEvent({
    ...contextRepo,
    event_type: 'pr-runtime-base-refresh',
    client_payload: {
      pr_number: input.pr_number,
      base_ref: input.base_ref,
      base_sha: input.base_sha,
      head_sha: input.head_sha,
      merge_sha: input.merge_sha,
      image_tag: `pr-merge-${input.merge_sha}`,
    },
  });

  for (let observation = 0; observation < 8; observation += 1) {
    try {
      const {data: inventory} = await github.rest.actions.listWorkflowRuns({
        ...contextRepo,
        workflow_id: runtimeWorkflow.id,
        event: 'repository_dispatch',
        created: `${dispatchCreatedSince}..*`,
        per_page: 100,
        page: 1,
      });
      if (Array.isArray(inventory?.workflow_runs) &&
          inventory.workflow_runs.some(run => isExactActiveRun(run) &&
            run.event === 'repository_dispatch' && Date.parse(run.created_at) >= Date.parse(dispatchCreatedSince) &&
            !observedRunIds.has(`${run.event}:${run.id}:${run.run_attempt}`))) {
        core.info('The dispatched runtime refresh is visible as an exact active producer.');
        return 'dispatched-observed';
      }
    } catch {
      core.warning('Runtime refresh registration is not yet observable.');
    }
    if (observation < 7) await sleep(2000);
  }

  core.warning('Runtime refresh was dispatched but remained unconfirmed after bounded polling.');
  return 'dispatched-unconfirmed';
};
