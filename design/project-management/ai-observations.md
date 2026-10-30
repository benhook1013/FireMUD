# AI Observations

A small inbox for unresolved agent, tooling, environment, and workflow issues; see [recording and stewardship policy](../../AGENTS.md#execution-basics).

- `2026-10-30`: Full-run launcher receipt went missing.
  - An unchanged full Game Session retry failed without a final receipt; its sampler stopped at 01:22:20Z and Gradle log ended at 01:53:11Z, with no exact process or lock found and tool-session state unknown. Raw XML/profile and a separately recovered result were preserved. Next: correlate caller, supervisor, process/session, and report timestamps on one bounded retry before assigning a cause or changing the wrapper.
