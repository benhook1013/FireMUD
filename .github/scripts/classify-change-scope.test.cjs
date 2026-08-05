"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const {
  ALL_SERVICES,
  classifyChangeScope,
  classifyGithubChangeScope,
} = require("./classify-change-scope.cjs");

test("documentation-only changes use the lightweight path", () => {
  const result = classifyChangeScope([
    "design/architecture/README.md",
    "mkdocs.yml",
  ]);

  assert.equal(result.lightweightOnly, true);
  assert.equal(result.docsChanged, true);
  assert.equal(result.designDocsChanged, true);
  assert.deepEqual(result.affectedServices, []);
});

test("validation Python remains lightweight but requests Python proof", () => {
  const result = classifyChangeScope([
    "dev-tools/validation/check-example.py",
    "dev-tools/validation/test_check_example.py",
  ]);

  assert.equal(result.lightweightOnly, true);
  assert.equal(result.pythonChanged, true);
  assert.equal(result.validationPythonChanged, true);
  assert.equal(result.docsChanged, false);
});

test("operational Python forces the normal validation path", () => {
  const result = classifyChangeScope([
    "dev-tools/hosted/preview/render-preview-values.py",
  ]);

  assert.equal(result.lightweightOnly, false);
  assert.equal(result.pythonChanged, true);
});

test("workflow changes force all service validation", () => {
  for (const path of [
    ".github/workflows/ci.yml",
    ".github/workflows/security.yml",
    ".github/workflows/preview.yml",
    ".github/workflows/zap-baseline.yml",
    ".github/scripts/classify-change-scope.cjs",
  ]) {
    const result = classifyChangeScope([path]);
    assert.equal(result.runAll, true, path);
    assert.equal(result.lightweightOnly, false, path);
    assert.deepEqual(result.affectedServices, ALL_SERVICES, path);
  }
});

test("mixed documentation and runtime changes never use the fast path", () => {
  const result = classifyChangeScope([
    "design/product/requirements.md",
    "services/account-service/src/main/java/example/Account.java",
  ]);

  assert.equal(result.lightweightOnly, false);
  assert.deepEqual(result.affectedServices, ["account-service"]);
});

test("non-PR runs always execute the complete path", () => {
  const result = classifyChangeScope([], { forceAll: true });

  assert.equal(result.runAll, true);
  assert.equal(result.lightweightOnly, false);
  assert.equal(result.pythonChanged, true);
  assert.deepEqual(result.affectedServices, ALL_SERVICES);
});

test("GitHub file-count mismatches fail closed to the complete path", async () => {
  const github = {
    paginate: async () => ["design/README.md"],
    rest: { pulls: { listFiles() {} } },
  };
  const context = {
    eventName: "pull_request",
    repo: { owner: "example", repo: "firemud" },
    payload: { pull_request: { number: 1, changed_files: 2 } },
  };

  const result = await classifyGithubChangeScope(github, context);

  assert.equal(result.runAll, true);
  assert.equal(result.lightweightOnly, false);
  assert.deepEqual(result.affectedServices, ALL_SERVICES);
});

test("GitHub complete documentation file lists retain the lightweight path", async () => {
  const github = {
    paginate: async () => ["design/README.md"],
    rest: { pulls: { listFiles() {} } },
  };
  const context = {
    eventName: "pull_request",
    repo: { owner: "example", repo: "firemud" },
    payload: { pull_request: { number: 1, changed_files: 1 } },
  };

  const result = await classifyGithubChangeScope(github, context);

  assert.equal(result.runAll, false);
  assert.equal(result.lightweightOnly, true);
  assert.deepEqual(result.affectedServices, []);
});
