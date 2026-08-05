"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const {
  ALL_SERVICES,
  classifyChangeScope,
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
  const result = classifyChangeScope([".github/workflows/ci.yml"]);

  assert.equal(result.runAll, true);
  assert.equal(result.lightweightOnly, false);
  assert.deepEqual(result.affectedServices, ALL_SERVICES);
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
