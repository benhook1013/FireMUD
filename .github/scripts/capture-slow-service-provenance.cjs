"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { setTimeout: sleep } = require("node:timers/promises");

const SUPPORTED_MODULES = new Set(["account-service", "game-session-service", "world-management-service"]);
const CANONICAL_RUN_NAME = "CI — Validation";
const SHA_PATTERN = /^[0-9a-f]{40}$/;
const ARTIFACT_SNAPSHOT_ATTEMPTS = 4;
const ARTIFACT_SNAPSHOT_DELAY_MS = 1000;

async function captureSlowServiceProvenance({
  github,
  context,
  env = process.env,
  module,
  fileSystem = fs,
  wait = sleep,
}) {
  if (!SUPPORTED_MODULES.has(module)) {
    throw new Error(`Unsupported slow-service provenance module: ${module}`);
  }

  const runId = Number(env.CI_RUN_ID);
  const runAttempt = Number(env.CI_RUN_ATTEMPT);
  const eventName = env.CI_EVENT_NAME;
  const refName = env.CI_REF_NAME;
  const testedSourceSha = env.CI_SOURCE_SHA;
  const nativeHeadSha = env.CI_NATIVE_HEAD_SHA;
  const isPullRequest = eventName === "pull_request";
  if (
    context.eventName !== eventName ||
    context.sha !== testedSourceSha ||
    !SHA_PATTERN.test(testedSourceSha || "") ||
    !SHA_PATTERN.test(nativeHeadSha || "")
  ) {
    throw new Error("Workflow event or tested/native source identity is malformed.");
  }

  let pullRequest = null;
  let expectedRunTitle = null;
  if (isPullRequest) {
    const eventPullRequest = context.payload?.pull_request;
    const number = eventPullRequest?.number;
    const baseSha = eventPullRequest?.base?.sha;
    const headSha = eventPullRequest?.head?.sha;
    if (
      !Number.isSafeInteger(number) ||
      number <= 0 ||
      !SHA_PATTERN.test(baseSha || "") ||
      !SHA_PATTERN.test(headSha || "") ||
      headSha !== nativeHeadSha
    ) {
      throw new Error("Trusted pull-request event identity does not match the native head.");
    }
    expectedRunTitle = `CI — Validation pr-${number} base-${baseSha} head-${headSha}`;
    pullRequest = { number, base_sha: baseSha, head_sha: headSha };
  } else {
    if (
      !["push", "workflow_dispatch"].includes(eventName) ||
      nativeHeadSha !== testedSourceSha ||
      typeof refName !== "string" ||
      !refName
    ) {
      throw new Error("Non-pull-request runs must test their native workflow head and ref.");
    }
    expectedRunTitle = `CI — Validation ${refName}`;
  }

  const run = (
    await github.rest.actions.getWorkflowRun({ ...context.repo, run_id: runId })
  ).data;
  const repository = `${context.repo.owner}/${context.repo.repo}`;
  const acceptedRunNames = new Set([expectedRunTitle, CANONICAL_RUN_NAME]);
  if (
    run.id !== runId ||
    run.head_sha !== nativeHeadSha ||
    run.run_attempt !== runAttempt ||
    run.path !== ".github/workflows/ci.yml" ||
    !acceptedRunNames.has(run.name) ||
    run.event !== eventName ||
    run.repository?.full_name !== repository ||
    run.display_title !== expectedRunTitle
  ) {
    throw new Error("Current workflow run identity does not match the event source.");
  }

  let apiPullRequests = [];
  if (isPullRequest) {
    if (!Array.isArray(run.pull_requests) || run.pull_requests.length > 1) {
      throw new Error("Current workflow pull-request association is unavailable or ambiguous.");
    }
    apiPullRequests = run.pull_requests.map(association => ({
      number: association.number,
      base_sha: association.base?.sha,
      head_sha: association.head?.sha,
    }));
    if (
      apiPullRequests.length === 1 &&
      (apiPullRequests[0].number !== pullRequest.number ||
        apiPullRequests[0].base_sha !== pullRequest.base_sha ||
        apiPullRequests[0].head_sha !== pullRequest.head_sha)
    ) {
      throw new Error("Current workflow pull-request association does not match the event.");
    }
  }

  async function listCompletePages(request, params, rowsKey, description) {
    const items = [];
    let totalCount;
    for (let page = 1; page <= 100; page += 1) {
      const { data } = await request({ ...context.repo, ...params, page, per_page: 100 });
      const count = data?.total_count;
      const rows = data?.[rowsKey];
      if (
        !Number.isSafeInteger(count) ||
        count < 0 ||
        !Array.isArray(rows) ||
        (totalCount !== undefined && totalCount !== count) ||
        items.length > count
      ) {
        throw new Error(`Invalid ${description} pagination metadata.`);
      }
      totalCount = count;
      const expected = Math.min(100, count - items.length);
      if (rows.length !== expected) {
        throw new Error(`Incomplete ${description} page ${page}.`);
      }
      items.push(...rows);
      if (items.length === totalCount) return { total_count: totalCount, items };
    }
    throw new Error(`Exceeded the ${description} pagination bound.`);
  }

  function artifactMetadata(artifact) {
    return {
      id: artifact.id,
      name: artifact.name,
      expired: artifact.expired,
      size_in_bytes: artifact.size_in_bytes,
      created_at: artifact.created_at,
      workflow_run: artifact.workflow_run,
    };
  }

  function canonicalJson(value) {
    if (Array.isArray(value)) {
      return `[${value.map(canonicalJson).join(",")}]`;
    }
    if (value !== null && typeof value === "object") {
      return `{${Object.keys(value).sort().map(key =>
        `${JSON.stringify(key)}:${canonicalJson(value[key])}`,
      ).join(",")}}`;
    }
    return JSON.stringify(value);
  }

  async function listArtifactSnapshot() {
    const items = [];
    const artifactIds = new Set();
    let totalCount;
    for (let page = 1; page <= 100; page += 1) {
      const { data } = await github.rest.actions.listWorkflowRunArtifacts({
        ...context.repo,
        run_id: runId,
        page,
        per_page: 100,
      });
      const count = data?.total_count;
      const rows = data?.artifacts;
      if (
        !Number.isSafeInteger(count) ||
        count < 0 ||
        !Array.isArray(rows)
      ) {
        throw new Error("Invalid workflow artifact pagination metadata.");
      }
      if (totalCount !== undefined && totalCount !== count) return null;
      if (items.length > count) return null;
      totalCount = count;
      const expected = Math.min(100, count - items.length);
      if (rows.length !== expected) return null;
      for (const artifact of rows) {
        if (!Number.isSafeInteger(artifact?.id) || artifact.id <= 0) {
          throw new Error("Invalid workflow artifact identity.");
        }
        if (artifactIds.has(artifact.id)) return null;
        artifactIds.add(artifact.id);
      }
      items.push(...rows);
      if (items.length === totalCount) return { total_count: totalCount, items };
    }
    throw new Error("Exceeded the workflow artifact pagination bound.");
  }

  async function listStableArtifactSnapshot() {
    let previousFingerprint;
    for (let attempt = 1; attempt <= ARTIFACT_SNAPSHOT_ATTEMPTS; attempt += 1) {
      if (attempt > 1) await wait(ARTIFACT_SNAPSHOT_DELAY_MS);
      const snapshot = await listArtifactSnapshot();
      if (snapshot === null) {
        previousFingerprint = undefined;
        continue;
      }
      const fingerprint = canonicalJson({
        total_count: snapshot.total_count,
        items: snapshot.items.map(artifactMetadata),
      });
      if (previousFingerprint === fingerprint) return snapshot;
      previousFingerprint = fingerprint;
    }
    throw new Error(
      `Could not obtain two consecutive identical complete workflow artifact snapshots within ${ARTIFACT_SNAPSHOT_ATTEMPTS} listings.`,
    );
  }

  const attempts = [];
  for (let attempt = 1; attempt <= runAttempt; attempt += 1) {
    const params = { run_id: runId, attempt_number: attempt };
    const snapshot = await listCompletePages(
      request => github.rest.actions.listJobsForWorkflowRunAttempt(request),
      params,
      "jobs",
      `attempt ${attempt} job`,
    );
    attempts.push({
      attempt,
      total_count: snapshot.total_count,
      jobs: snapshot.items.map(job => ({
        id: job.id,
        run_id: job.run_id,
        head_sha: job.head_sha,
        run_attempt: job.run_attempt,
        name: job.name,
        status: job.status,
        conclusion: job.conclusion,
        started_at: job.started_at,
        completed_at: job.completed_at,
      })),
    });
  }

  const artifactSnapshot = await listStableArtifactSnapshot();
  const artifacts = artifactSnapshot.items;
  const provenancePath = env.CI_PROVENANCE_PATH;
  fileSystem.mkdirSync(path.dirname(provenancePath), { recursive: true });
  fileSystem.writeFileSync(
    provenancePath,
    JSON.stringify({
      run: {
        id: run.id,
        head_sha: run.head_sha,
        tested_source_sha: testedSourceSha,
        event: run.event,
        event_name: eventName,
        pull_request: pullRequest,
        api_pull_requests: apiPullRequests,
        display_title: run.display_title,
        api_name: run.name,
        repository: run.repository?.full_name,
        run_attempt: run.run_attempt,
        path: run.path,
        name: CANONICAL_RUN_NAME,
      },
      attempts,
      artifacts: {
        total_count: artifactSnapshot.total_count,
        items: artifacts.map(artifactMetadata),
      },
    }),
  );
}

module.exports = captureSlowServiceProvenance;
