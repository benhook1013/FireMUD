"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const {
  ALL_SERVICES,
  ALL_MODULES,
  classifyChangeScope,
  classifyGithubChangeScope,
} = require("./classify-change-scope.cjs");

async function classifyGithubFiles(files, changedFiles) {
  const pullRequest = { number: 1 };
  if (changedFiles !== undefined) {
    pullRequest.changed_files = changedFiles;
  }
  const github = {
    paginate: async () =>
      files.map((file) => (typeof file === "string" ? { filename: file } : file)),
    rest: { pulls: { listFiles() {} } },
  };
  const context = {
    eventName: "pull_request",
    repo: { owner: "example", repo: "firemud" },
    payload: { pull_request: pullRequest },
  };

  return classifyGithubChangeScope(github, context);
}

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

test("ERD generation remains docs-affecting but never uses the lightweight path", () => {
  const result = classifyChangeScope(["dev-tools/docs/generate-erd.sh"]);

  assert.equal(result.docsChanged, true);
  assert.equal(result.lightweightOnly, false);
  assert.equal(result.runAll, false);
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

test("script tests avoid service validation but retain normal tooling proof", () => {
  const testResult = classifyChangeScope([
    ".github/scripts/classify-change-scope.test.cjs",
  ]);
  assert.equal(testResult.runAll, false);
  assert.equal(testResult.lightweightOnly, false);
  assert.deepEqual(testResult.affectedServices, []);

  const runtimeResult = classifyChangeScope([
    ".github/scripts/classify-change-scope.cjs",
  ]);
  assert.equal(runtimeResult.runAll, true);
  assert.equal(runtimeResult.lightweightOnly, false);
  assert.deepEqual(runtimeResult.affectedServices, ALL_SERVICES);
});

test("operational Python forces the normal validation path", () => {
  const result = classifyChangeScope([
    "dev-tools/hosted/preview/render-preview-values.py",
  ]);

  assert.equal(result.lightweightOnly, false);
  assert.equal(result.pythonChanged, true);
});

test("Python dependency inputs request the normal validation path", () => {
  const result = classifyChangeScope(["config/python/smoke-requirements.in"]);

  assert.equal(result.lightweightOnly, false);
  assert.equal(result.pythonChanged, true);
});

test("runtime authorities select their documentation and frontend consumers", async () => {
  const pythonResult = classifyChangeScope([".python-version"]);
  assert.equal(pythonResult.docsChanged, true);
  assert.equal(pythonResult.frontendChanged, true);
  assert.equal(pythonResult.lightweightOnly, false);
  assert.equal(pythonResult.pythonChanged, false);
  assert.deepEqual(pythonResult.affectedServices, []);

  const nodeResult = classifyChangeScope([".node-version"]);
  assert.equal(nodeResult.docsChanged, true);
  assert.equal(nodeResult.frontendChanged, true);
  assert.equal(nodeResult.lightweightOnly, false);
  assert.equal(nodeResult.pythonChanged, false);
  assert.deepEqual(nodeResult.affectedServices, []);

  for (const [path, docsChanged, frontendChanged] of [
    [".python-version", true, true],
    [".node-version", true, true],
  ]) {
    const result = await classifyGithubFiles([path], 1);
    assert.equal(result.docsChanged, docsChanged, path);
    assert.equal(result.frontendChanged, frontendChanged, path);
    assert.equal(result.lightweightOnly, false, path);
    assert.deepEqual(result.affectedServices, [], path);
  }
});

test("documentation requirements are docs but not Python dependencies", () => {
  const result = classifyChangeScope(["config/docs/requirements.txt"]);

  assert.equal(result.docsChanged, true);
  assert.equal(result.pythonChanged, false);
  assert.equal(result.lightweightOnly, true);
});

test("workflow and Gradle wrapper changes force all module validation", () => {
  for (const path of [
    ".github/workflows/ci.yml",
    ".github/workflows/security.yml",
    ".github/workflows/preview.yml",
    ".github/workflows/zap-baseline.yml",
    ".github/scripts/classify-change-scope.cjs",
    "gradlew",
    "gradlew.bat",
  ]) {
    const result = classifyChangeScope([path]);
    assert.equal(result.runAll, true, path);
    assert.equal(result.lightweightOnly, false, path);
    assert.deepEqual(result.affectedServices, ALL_SERVICES, path);
    assert.deepEqual(result.affectedModules, ALL_MODULES, path);
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

test("GitHub non-PR events always execute the complete path", async () => {
  const result = await classifyGithubChangeScope({}, {
    eventName: "push",
  });

  assert.equal(result.runAll, true);
  assert.equal(result.lightweightOnly, false);
  assert.equal(result.pythonChanged, true);
  assert.deepEqual(result.affectedServices, ALL_SERVICES);
});

test("GitHub file-count mismatches fail closed to the complete path", async () => {
  const github = {
    paginate: async () => [{ filename: "design/README.md" }],
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
    paginate: async () => [{ filename: "design/README.md" }],
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

test("GitHub missing file counts fail closed to the complete path", async () => {
  const result = await classifyGithubFiles(["design/README.md"]);

  assert.equal(result.runAll, true);
  assert.equal(result.lightweightOnly, false);
  assert.deepEqual(result.affectedServices, ALL_SERVICES);
});

test("GitHub noninteger file counts fail closed to the complete path", async () => {
  const result = await classifyGithubFiles(["design/README.md"], "1");

  assert.equal(result.runAll, true);
  assert.equal(result.lightweightOnly, false);
  assert.deepEqual(result.affectedServices, ALL_SERVICES);
});

test("GitHub shared paths force the complete path", async () => {
  const result = await classifyGithubFiles([".github/actions/example/action.yml"], 1);

  assert.equal(result.runAll, true);
  assert.equal(result.lightweightOnly, false);
  assert.deepEqual(result.affectedServices, ALL_SERVICES);
});

test("GitHub unknown service paths force the complete path", async () => {
  for (const path of [
    "services/unknown-service",
    "services/unknown-service/src/main.java",
  ]) {
    const result = await classifyGithubFiles([path], 1);

    assert.equal(result.runAll, true, path);
    assert.equal(result.lightweightOnly, false, path);
    assert.deepEqual(result.affectedServices, ALL_SERVICES, path);
    assert.deepEqual(result.affectedModules, ALL_MODULES, path);
  }
});

test("shared modules force every Gradle module, including common-temporal", () => {
  const result = classifyChangeScope([
    "services/common-temporal/src/main/kotlin/example/Temporal.kt",
  ]);

  assert.equal(result.runAll, true);
  assert.deepEqual(result.affectedModules, ALL_MODULES);
  assert.deepEqual(result.bootableModules, ALL_SERVICES.concat("hosted-environment-identity-controller"));
});

test("controller and load-testing changes receive their own module checks", () => {
  const controllerResult = classifyChangeScope([
    "services/hosted-environment-identity-controller/src/main/java/example/Controller.java",
  ]);
  assert.equal(controllerResult.runAll, false);
  assert.deepEqual(controllerResult.affectedServices, []);
  assert.deepEqual(controllerResult.affectedModules, ["hosted-environment-identity-controller"]);
  assert.deepEqual(controllerResult.bootableModules, ["hosted-environment-identity-controller"]);

  const loadTestingResult = classifyChangeScope([
    "dev-tools/load-testing/src/gatling/example/Simulation.java",
  ]);
  assert.equal(loadTestingResult.runAll, false);
  assert.deepEqual(loadTestingResult.affectedServices, []);
  assert.deepEqual(loadTestingResult.affectedModules, ["load-testing"]);
  assert.deepEqual(loadTestingResult.bootableModules, []);
});

test("GitHub complete docs and known Account service lists target Account only", async () => {
  const result = await classifyGithubFiles(
    ["design/README.md", "services/account-service/src/main.java"],
    2,
  );

  assert.equal(result.runAll, false);
  assert.equal(result.lightweightOnly, false);
  assert.deepEqual(result.affectedServices, ["account-service"]);
  assert.deepEqual(result.affectedModules, ["account-service"]);
});

test("GitHub rename classification includes both old and new paths", async () => {
  const github = {
    paginate: async (_method, _parameters, mapResponse) =>
      mapResponse({
        data: [
          {
            filename: "design/new-name.md",
            previous_filename: "services/account-service/src/main.java",
          },
        ],
      }),
    rest: { pulls: { listFiles() {} } },
  };
  const context = {
    eventName: "pull_request",
    repo: { owner: "example", repo: "firemud" },
    payload: { pull_request: { number: 1, changed_files: 1 } },
  };

  const result = await classifyGithubChangeScope(github, context);

  assert.equal(result.runAll, false);
  assert.deepEqual(result.affectedModules, ["account-service"]);
});

test("GitHub malformed file entries fail closed to the complete path", async () => {
  for (const file of [{}, { filename: "" }, { filename: "design/new.md", previous_filename: "" }]) {
    const result = await classifyGithubFiles([file], 1);

    assert.equal(result.runAll, true, JSON.stringify(file));
    assert.deepEqual(result.affectedModules, ALL_MODULES, JSON.stringify(file));
  }
});
