"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const { publishPreviewComment } = require("../hosted/preview/publish-preview-comment.js");

const context = {
  repo: { owner: "example", repo: "firemud" },
};
const BASE_SHA = "a".repeat(40);
const MERGE_SHA = "b".repeat(40);

function previewComment(id, body, updatedAt) {
  return {
    id,
    body,
    user: { login: "github-actions[bot]" },
    created_at: updatedAt,
    updated_at: updatedAt,
  };
}

function makeGithub({
  pullRequests,
  comments,
  deletedCommentStatuses = {},
  liveBaseShas = [],
  mergeCommitParents = [],
}) {
  const calls = {
    get: [],
    refs: [],
    commits: [],
    paginate: [],
    deleted: [],
    updates: [],
    creates: [],
  };
  const effectivePullRequests = pullRequests.map((pullRequest) => ({
    ...pullRequest,
    ...(pullRequest.base
      ? { base: { ref: "develop", ...pullRequest.base } }
      : {}),
  }));
  let pullRequestIndex = 0;
  let refIndex = 0;
  let commitIndex = 0;
  const github = {
    rest: {
      pulls: {
        get: async (params) => {
          calls.get.push(params);
          const index = Math.min(pullRequestIndex++, effectivePullRequests.length - 1);
          return { data: effectivePullRequests[index] };
        },
      },
      git: {
        getRef: async (params) => {
          calls.refs.push(params);
          const index = Math.min(refIndex++, effectivePullRequests.length - 1);
          const sha = liveBaseShas[index] ?? effectivePullRequests[index]?.base?.sha;
          return { data: { object: { sha } } };
        },
      },
      repos: {
        getCommit: async (params) => {
          calls.commits.push(params);
          const index = Math.min(commitIndex++, effectivePullRequests.length - 1);
          const pullRequest = effectivePullRequests[index];
          const parents =
            mergeCommitParents[index] ?? [
              { sha: pullRequest?.base?.sha },
              { sha: pullRequest?.head?.sha },
            ];
          return {
            data: {
              sha: pullRequest?.merge_commit_sha,
              parents,
            },
          };
        },
      },
      issues: {
        listComments: () => {},
        deleteComment: async (params) => {
          calls.deleted.push(params.comment_id);
          const status = deletedCommentStatuses[params.comment_id];
          if (status !== undefined) {
            const error = new Error(`delete failed with status ${status}`);
            error.status = status;
            throw error;
          }
        },
        updateComment: async (params) => {
          calls.updates.push(params);
        },
        createComment: async (params) => {
          calls.creates.push(params);
        },
      },
    },
    paginate: async (method, params) => {
      calls.paginate.push({ method, params });
      return comments;
    },
  };
  return { github, calls };
}

async function withEnvironment(values, callback) {
  const previous = {};
  for (const [name, value] of Object.entries(values)) {
    previous[name] = process.env[name];
    if (value === undefined) {
      delete process.env[name];
    } else {
      process.env[name] = value;
    }
  }
  try {
    return await callback();
  } finally {
    for (const [name, value] of Object.entries(previous)) {
      if (value === undefined) {
        delete process.env[name];
      } else {
        process.env[name] = value;
      }
    }
  }
}

async function publish(options = {}) {
  const {
    pullRequests = [{ state: "open", head: { sha: "head-123" } }],
    comments = [],
    deletedCommentStatuses,
    summaryText = "generated preview summary",
    previewBaseSha,
    previewMergeSha,
    previewImageTag = "image-123",
    previewHeadSha = "head-123",
    liveBaseShas,
    mergeCommitParents,
    environment = {},
    ...publisherOptions
  } = options;
  const { github, calls } = makeGithub({
    pullRequests,
    comments,
    deletedCommentStatuses,
    liveBaseShas,
    mergeCommitParents,
  });
  const infos = [];
  const core = { info: (message) => infos.push(message) };
  const summaryCalls = [];
  const summaryExecutor = (...args) => {
    summaryCalls.push(args);
    return summaryText;
  };

  await withEnvironment(
    {
      PREVIEW_PR_NUMBER: "123",
      PREVIEW_HEAD_SHA: previewHeadSha,
      PREVIEW_BASE_SHA: previewBaseSha,
      PREVIEW_MERGE_SHA: previewMergeSha,
      PREVIEW_IMAGE_TAG: previewImageTag,
      PREVIEW_HOSTNAME: "pr-123.preview.firedevops.net",
      DEMO_SMOKE_USERNAME: "demo-user",
      DEMO_SMOKE_EMAIL: "demo@example.test",
      DEMO_SMOKE_PASSWORD: "demo-password",
      ...environment,
    },
    async () => {
      await publishPreviewComment({
        github,
        context,
        core,
        mode: "success",
        summaryExecutor,
        ...publisherOptions,
      });
    },
  );

  return { calls, infos, summaryCalls };
}

test("selects the oldest bot preview comment by creation time, then id", async () => {
  const firstPageNoise = Array.from({ length: 35 }, (_, index) => ({
    ...previewComment(
      String(1000 + index),
      `### Unrelated Summary ${index + 1}`,
      "2026-08-01T00:00:00Z",
    ),
  }));
  const oldGenerated = previewComment(
    "100",
    "<!-- firemud-preview-summary -->\nold generated",
    "2026-09-01T00:00:00Z",
  );
  const duplicateOldGenerated = { ...oldGenerated };
  const newerLegacy = previewComment(
    "101",
    "### Preview Summary\nnewer legacy",
    "2026-09-02T00:00:00Z",
  );
  const newestLowerId = previewComment(
    "102",
    "<!-- firemud-preview-summary -->\nnewest lower id",
    "2026-09-03T00:00:00Z",
  );
  const newestHigherId = previewComment(
    "103",
    "### Preview Summary\nnewest higher id",
    "2026-09-03T00:00:00Z",
  );
  const userComment = {
    ...previewComment("999", "<!-- firemud-preview-summary -->\nuser", "2026-09-04T00:00:00Z"),
    user: { login: "contributor" },
  };

  const result = await publish({
    comments: [
      ...firstPageNoise,
      oldGenerated,
      duplicateOldGenerated,
      newerLegacy,
      newestLowerId,
      newestHigherId,
      userComment,
    ],
    includeDemoCredentials: true,
  });

  assert.equal(result.calls.paginate.length, 1);
  assert.equal(result.calls.paginate[0].params.per_page, 100);
  assert.deepEqual(result.calls.deleted, ["101", "102", "103"]);
  assert.equal(result.calls.updates.length, 1);
  assert.equal(result.calls.updates[0].comment_id, "100");
  assert.match(result.calls.updates[0].body, /Demo login username: demo-user/);
  assert.match(result.calls.updates[0].body, /Demo login email: demo@example\.test/);
  assert.match(result.calls.updates[0].body, /Demo login password: demo-password/);
});

test("ignores null or missing bot comment bodies while publishing the canonical summary", async () => {
  const nullBody = {
    ...previewComment("90", "ignored", "2026-09-04T00:00:00Z"),
    body: null,
  };
  const missingBody = {
    ...previewComment("91", "ignored", "2026-09-05T00:00:00Z"),
  };
  delete missingBody.body;
  const canonical = previewComment(
    "92",
    "<!-- firemud-preview-summary -->\ncanonical",
    "2026-09-03T00:00:00Z",
  );

  const result = await publish({ comments: [nullBody, missingBody, canonical] });

  assert.deepEqual(result.calls.deleted, []);
  assert.equal(result.calls.updates.length, 1);
  assert.equal(result.calls.updates[0].comment_id, "92");
  assert.equal(result.calls.creates.length, 0);
});

test("uses the lower comment id when bot summaries share a creation timestamp", async () => {
  const result = await publish({
    comments: [
      previewComment("18", "### Preview Summary\nlater id", "2026-09-03T00:00:00Z"),
      previewComment("17", "<!-- firemud-preview-summary -->\nlower id", "2026-09-03T00:00:00Z"),
    ],
  });

  assert.deepEqual(result.calls.deleted, ["18"]);
  assert.equal(result.calls.updates[0].comment_id, "17");
});

test("rejects an initially stale expected-open or expected-closed target", async () => {
  const expectedOpen = await publish({
    pullRequests: [{ state: "closed", head: { sha: "head-123" } }],
    statePolicy: "expected-open",
  });
  assert.equal(expectedOpen.calls.get.length, 1);
  assert.equal(expectedOpen.summaryCalls.length, 0);
  assert.equal(expectedOpen.calls.paginate.length, 0);
  assert.equal(expectedOpen.calls.creates.length, 0);

  const expectedClosed = await publish({
    pullRequests: [{ state: "open", head: { sha: "head-123" } }],
    statePolicy: "expected-closed",
  });
  assert.equal(expectedClosed.calls.get.length, 1);
  assert.equal(expectedClosed.summaryCalls.length, 0);
  assert.equal(expectedClosed.calls.paginate.length, 0);
  assert.equal(expectedClosed.calls.creates.length, 0);
});

test("rejects an initially stale parent or merge tuple", async () => {
  for (const pullRequest of [
    {
      state: "open",
      head: { sha: "head-123" },
      base: { sha: "c".repeat(40) },
      merge_commit_sha: MERGE_SHA,
    },
    {
      state: "open",
      head: { sha: "head-123" },
      base: { sha: BASE_SHA },
      merge_commit_sha: "d".repeat(40),
    },
  ]) {
    const result = await publish({
      pullRequests: [pullRequest],
      previewBaseSha: BASE_SHA,
      previewMergeSha: MERGE_SHA,
      previewImageTag: BASE_SHA,
    });

    assert.equal(result.calls.get.length, 1);
    assert.equal(result.summaryCalls.length, 0);
    assert.equal(result.calls.paginate.length, 0);
    assert.equal(result.calls.creates.length, 0);
  }
});

test("accepts the matching expected state and manual-any policy", async () => {
  for (const [statePolicy, state] of [
    ["expected-open", "open"],
    ["expected-closed", "closed"],
    ["manual-any", "open"],
    ["manual-any", "closed"],
  ]) {
    const result = await publish({
      pullRequests: [{ state, head: { sha: "head-123" } }],
      mode: "cleanup",
      statePolicy,
    });
    assert.equal(result.calls.creates.length, 1, `${statePolicy}/${state}`);
    assert.equal(result.calls.get.length, 2, `${statePolicy}/${state}`);
  }
});

test("final stale check prevents a changed head from deleting or updating comments", async () => {
  const result = await publish({
    pullRequests: [
      { state: "open", head: { sha: "head-123" } },
      { state: "open", head: { sha: "head-new" } },
    ],
    comments: [
      previewComment("201", "<!-- firemud-preview-summary -->\nold", "2026-09-01T00:00:00Z"),
    ],
    statePolicy: "expected-open",
  });

  assert.equal(result.calls.get.length, 2);
  assert.equal(result.summaryCalls.length, 1);
  assert.equal(result.calls.paginate.length, 1);
  assert.deepEqual(result.calls.deleted, []);
  assert.deepEqual(result.calls.updates, []);
  assert.deepEqual(result.calls.creates, []);
});

test("final stale check prevents a changed parent or merge tuple from publishing", async () => {
  for (const changedPullRequest of [
    {
      state: "open",
      head: { sha: "head-123" },
      base: { sha: "c".repeat(40) },
      merge_commit_sha: MERGE_SHA,
    },
    {
      state: "open",
      head: { sha: "head-123" },
      base: { sha: BASE_SHA },
      merge_commit_sha: "d".repeat(40),
    },
  ]) {
    const result = await publish({
      pullRequests: [
        {
          state: "open",
          head: { sha: "head-123" },
          base: { sha: BASE_SHA },
          merge_commit_sha: MERGE_SHA,
        },
        changedPullRequest,
      ],
      comments: [
        previewComment("203", "<!-- firemud-preview-summary -->\nold", "2026-09-01T00:00:00Z"),
      ],
      previewBaseSha: BASE_SHA,
      previewMergeSha: MERGE_SHA,
      previewImageTag: `pr-merge-${MERGE_SHA}`,
    });

    assert.equal(result.calls.get.length, 2);
    assert.equal(result.summaryCalls.length, 1);
    assert.equal(result.calls.paginate.length, 1);
    assert.deepEqual(result.calls.deleted, []);
    assert.deepEqual(result.calls.updates, []);
    assert.deepEqual(result.calls.creates, []);
  }
});

test("rejects malformed or incomplete optional tuple inputs before reading the pull request", async () => {
  for (const options of [
    { previewBaseSha: BASE_SHA },
    { previewMergeSha: MERGE_SHA },
    { previewBaseSha: BASE_SHA.toUpperCase(), previewMergeSha: MERGE_SHA },
    { previewBaseSha: "not-a-sha", previewMergeSha: MERGE_SHA },
    { previewBaseSha: BASE_SHA, previewMergeSha: MERGE_SHA, previewImageTag: "latest" },
  ]) {
    const result = await publish(options);

    assert.equal(result.calls.get.length, 0);
    assert.equal(result.summaryCalls.length, 0);
    assert.equal(result.calls.paginate.length, 0);
  }
});

test("accepts base-commit and merge-scoped image tags for a complete tuple", async () => {
  for (const previewImageTag of [BASE_SHA, `pr-merge-${MERGE_SHA}`]) {
    const result = await publish({
      pullRequests: [
        {
          state: "open",
          head: { sha: "head-123" },
          base: { sha: BASE_SHA },
          merge_commit_sha: MERGE_SHA,
        },
      ],
      previewBaseSha: BASE_SHA,
      previewMergeSha: MERGE_SHA,
      previewImageTag,
    });

    assert.equal(result.calls.get.length, 2);
    assert.equal(result.summaryCalls.length, 1);
    assert.equal(result.calls.creates.length, 1);
  }
});

test("rejects a lagging PR API when the live base ref has advanced", async () => {
  const advancedBaseSha = "c".repeat(40);
  const result = await publish({
    pullRequests: [
      {
        state: "open",
        head: { sha: "head-123" },
        base: { sha: BASE_SHA, ref: "develop" },
        merge_commit_sha: MERGE_SHA,
      },
      {
        state: "open",
        head: { sha: "head-123" },
        base: { sha: BASE_SHA, ref: "develop" },
        merge_commit_sha: MERGE_SHA,
      },
    ],
    previewBaseSha: BASE_SHA,
    previewMergeSha: MERGE_SHA,
    previewImageTag: BASE_SHA,
    liveBaseShas: [advancedBaseSha, advancedBaseSha],
  });

  assert.equal(result.calls.get.length, 1);
  assert.equal(result.calls.refs.length, 1);
  assert.equal(result.calls.commits.length, 0);
  assert.equal(result.summaryCalls.length, 0);
  assert.equal(result.calls.paginate.length, 0);
  assert.deepEqual(result.calls.deleted, []);
  assert.deepEqual(result.calls.updates, []);
  assert.deepEqual(result.calls.creates, []);
});

test("rejects an exact-head publication when live merge parents are not ordered base then head", async () => {
  const result = await publish({
    pullRequests: [
      {
        state: "open",
        head: { sha: "head-123" },
        base: { sha: BASE_SHA, ref: "develop" },
        merge_commit_sha: MERGE_SHA,
      },
      {
        state: "open",
        head: { sha: "head-123" },
        base: { sha: BASE_SHA, ref: "develop" },
        merge_commit_sha: MERGE_SHA,
      },
    ],
    previewBaseSha: BASE_SHA,
    previewMergeSha: MERGE_SHA,
    previewImageTag: BASE_SHA,
    mergeCommitParents: [
      [{ sha: BASE_SHA }, { sha: "head-123" }],
      [{ sha: "head-123" }, { sha: BASE_SHA }],
    ],
    comments: [
      previewComment("203", "<!-- firemud-preview-summary -->\nold", "2026-09-01T00:00:00Z"),
    ],
  });

  assert.equal(result.calls.get.length, 2);
  assert.equal(result.calls.refs.length, 2);
  assert.equal(result.calls.commits.length, 2);
  assert.equal(result.summaryCalls.length, 1);
  assert.equal(result.calls.paginate.length, 1);
  assert.deepEqual(result.calls.deleted, []);
  assert.deepEqual(result.calls.updates, []);
  assert.deepEqual(result.calls.creates, []);
});

test("preserves head/state-only compatibility when no tuple inputs are supplied", async () => {
  const result = await publish({
    pullRequests: [{ state: "open", head: { sha: "head-123" } }],
    mode: "cleanup",
    statePolicy: "expected-open",
  });

  assert.equal(result.calls.get.length, 2);
  assert.equal(result.summaryCalls.length, 1);
  assert.equal(result.calls.creates.length, 1);
});

test("final expected-closed check ignores a closed-event cleanup after same-head reopen", async () => {
  const result = await publish({
    pullRequests: [
      { state: "closed", head: { sha: "head-123" } },
      { state: "open", head: { sha: "head-123" } },
    ],
    comments: [
      previewComment("202", "<!-- firemud-preview-summary -->\ncurrent preview", "2026-09-02T00:00:00Z"),
    ],
    mode: "failure",
    statePolicy: "expected-closed",
    failureStage: "cleanup",
  });

  assert.equal(result.calls.get.length, 2);
  assert.equal(result.summaryCalls.length, 1);
  assert.deepEqual(result.calls.deleted, []);
  assert.deepEqual(result.calls.updates, []);
  assert.deepEqual(result.calls.creates, []);
});

test("preserves a reclaimed marker after both freshness checks pass", async () => {
  const result = await publish({
    comments: [
      previewComment(
        "300",
        "<!-- firemud-preview-summary -->\nold canonical",
        "2026-08-31T00:00:00Z",
      ),
      previewComment(
        "301",
        "<!-- firemud-preview-summary -->\n<!-- firemud-preview-reclaimed -->\nreclaimed",
        "2026-09-01T00:00:00Z",
      ),
      previewComment(
        "302",
        "### Preview Summary\nlater duplicate",
        "2026-09-02T00:00:00Z",
      ),
    ],
    markerPolicy: "preserve-reclaimed",
    statePolicy: "expected-open",
  });

  assert.equal(result.calls.get.length, 2);
  assert.equal(result.calls.paginate.length, 1);
  assert.deepEqual(result.calls.deleted, ["301", "302"]);
  assert.equal(result.calls.updates.length, 1);
  assert.equal(result.calls.updates[0].comment_id, "300");
  assert.match(result.calls.updates[0].body, /firemud-preview-reclaimed/);
  assert.deepEqual(result.calls.creates, []);
  assert.match(result.infos.join("\n"), /Preserving the reclaimed preview status/);
});

test("deletes later duplicate summaries, tolerates a concurrent 404, and updates the oldest one", async () => {
  const duplicate = previewComment(
    "401",
    "<!-- firemud-preview-summary -->\nold duplicate",
    "2026-09-01T00:00:00Z",
  );
  const result = await publish({
    comments: [
      duplicate,
      { ...duplicate },
      previewComment("402", "### Preview Summary\nold and already gone", "2026-09-02T00:00:00Z"),
      previewComment("403", "<!-- firemud-preview-summary -->\ncanonical", "2026-09-03T00:00:00Z"),
    ],
    deletedCommentStatuses: { "402": 404 },
  });

  assert.deepEqual(result.calls.deleted, ["402", "403"]);
  assert.equal(result.calls.updates.length, 1);
  assert.equal(result.calls.updates[0].comment_id, "401");
});

test("publishes the canonical update before propagating a duplicate deletion error", async () => {
  const { github, calls } = makeGithub({
    pullRequests: [{ state: "open", head: { sha: "head-123" } }],
    comments: [
      previewComment("501", "<!-- firemud-preview-summary -->\nduplicate", "2026-09-01T00:00:00Z"),
      previewComment("502", "### Preview Summary\ncanonical", "2026-09-02T00:00:00Z"),
    ],
    deletedCommentStatuses: { "502": 500 },
  });
  const core = { info: () => {} };

  await withEnvironment(
    { PREVIEW_PR_NUMBER: "123", PREVIEW_HEAD_SHA: "head-123" },
    async () => {
      await assert.rejects(
        publishPreviewComment({
          github,
          context,
          core,
          mode: "success",
          summaryExecutor: () => "generated preview summary",
        }),
        /delete failed with status 500/,
      );
    },
  );

  assert.deepEqual(calls.deleted, ["502"]);
  assert.equal(calls.updates.length, 1);
  assert.equal(calls.updates[0].comment_id, "501");
  assert.equal(calls.creates.length, 0);
});

test("passes a trimmed failure stage only to failure summaries", async () => {
  const result = await publish({
    mode: "failure",
    failureStage: "  rollout  ",
    statePolicy: "expected-open",
  });

  assert.deepEqual(result.summaryCalls[0][0], "bash");
  assert.deepEqual(result.summaryCalls[0][1], [
    "./dev-tools/hosted/preview/write-preview-summary.sh",
    "failure",
    "123",
    "head-123",
    "image-123",
    "pr-123.preview.firedevops.net",
    "unavailable",
    "rollout",
  ]);

  const unavailable = await publish({
    mode: "unavailable",
    failureStage: "  capacity  ",
    statePolicy: "expected-open",
  });
  assert.equal(unavailable.summaryCalls[0][1].at(-1), "capacity");

  const success = await publish({
    mode: "success",
    failureStage: "should-not-be-appended",
    statePolicy: "expected-open",
  });
  assert.equal(success.summaryCalls[0][1].length, 7);
});
