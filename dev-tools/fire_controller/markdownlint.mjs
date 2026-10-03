// Established markdownlint library, resolved from this checkout's pinned tools.
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

try {
  const require = createRequire(new URL("../../config/openapi/package.json", import.meta.url));
  const { lint } = await import(pathToFileURL(require.resolve("markdownlint/sync")).href);
  const strings = JSON.parse(readFileSync(0, "utf8"));
  if (!strings || Array.isArray(strings) || typeof strings !== "object" ||
      Object.values(strings).some((value) => typeof value !== "string")) {
    throw new Error("Expected a JSON object of text fields");
  }
  const results = lint({
    strings,
    noInlineConfig: true,
    config: { default: false, MD011: true, MD033: true, MD042: true,
      MD052: { shortcut_syntax: false }, MD056: true }
  });
  const diagnostics = [];
  for (const [field, warnings] of Object.entries(results)) {
    const grouped = new Map();
    for (const warning of warnings) {
      const rule = warning.ruleNames[0];
      if (grouped.has(rule)) {
        grouped.get(rule).occurrences += 1;
      } else {
        grouped.set(rule, { field, rule, line: warning.lineNumber,
          message: warning.ruleDescription + (warning.errorDetail ? `: ${warning.errorDetail}` : ""),
          occurrences: 1 });
      }
    }
    diagnostics.push(...grouped.values());
  }
  console.log(JSON.stringify(diagnostics));
} catch {
  console.error("Markdown precheck unavailable or failed; prepare this checkout's pinned config/openapi dependencies and Node toolchain.");
  process.exitCode = 2;
}
