# Model Routing

Use this guide when choosing a model for a task, a subagent, or an independent review. It is the only place in the repository that names model families. [AI delegation and review](./ai-delegation-and-review.md) owns how delegation and review work; this guide only picks the model.

FireMUD runs on two providers: Codex (Sol and Luna) and Claude (Opus). Either can run low on usage or go down, so each role has a model for every provider mode.

## Principles

- Choose by task role, then read the model from the table. No role has a hard-coded provider.
- Use the latest available version of each family, as reported by the tool's model metadata. Do not pin version identifiers.
- An explicit human model choice always wins. Keep the model and reasoning settings the human chose for a task.
- The human starts Codex tasks with Sol. Luna is only ever a subagent, and the orchestrator verifies its work.
- Prefer a reviewer or arbiter from the other provider (Codex versus Claude) when the mode allows, because different providers miss different things. That reviewer runs as a separate task, since a host cannot spawn the other provider's models. Otherwise use a different, stronger model from the same provider in a fresh context, or the author's own model in a fresh context when nothing stronger is eligible.
- Within one host, subagents use that host's models: a Codex orchestrator delegates to Codex models, a Claude orchestrator to Claude models. Work for the other provider goes through a separate task (a t3code thread or a FireController job), not a subagent. That includes table entries naming the other provider's model, such as Opus work under a Sol orchestrator in `codex_low`.

## Provider Mode

The FireController operator variable `provider_mode` says which providers to lean on. The human sets it; agents read it and never check usage or quota themselves. If it is unset or cannot be read, use `both`.

- `both`: both providers have room.
- `codex_low`: Codex usage is low; move work to Claude.
- `claude_low`: Claude usage is low; move work to Codex.

## Routing Table

| Role | What it covers | `both` | `codex_low` | `claude_low` |
| --- | --- | --- | --- | --- |
| Orchestrator | Plans and runs a workstream, integrates and verifies subagent work, owns PRs | The human's choice: Sol or Opus | Opus | Sol |
| Bulk executor (subagent) | Bounded investigation, mechanical edits, straightforward test updates | Luna `high` under Sol; Opus `medium` under Opus | Opus `medium` | Luna `high` |
| Deep executor (subagent or task) | Substantial multi-file implementation, wide reading, documentation rewrites | Opus `high` under Opus; Luna `xhigh` under Sol | Opus `high` | Luna `xhigh`, then Sol `medium` |
| Independent reviewer | Fresh-context review or commissioned audit | Sol `high` or Opus `high`, from the other provider than the author where possible | Opus `high`, fresh context | Sol `high`, fresh context |
| Arbiter | Conflicting evidence, competing options, consequential analysis | Sol or Opus at `high`, preferring the one that did not produce the evidence | Opus `high` | Sol `high` |

Effort scales differ: Codex has `low`, `medium`, `high`, and `xhigh`; Claude has `low`, `medium`, `high`, `xhigh`, and `max`. Any tier in the table may be chosen without approval. `max` is not used.

## Escalation

- Start at the role's tier and escalate by raising effort within the role.
- After two inconclusive passes, change model family if the provider mode allows it, rather than climbing further on the same model.

## Capacity, Quota, And Outages

- **Model at capacity** (a temporary capacity error): wait 30 to 60 seconds and retry the same model once. If that fails, use the next model the role allows in the current mode and tell the human when the substitution starts.
- **Usage or quota limit, or provider down:** stop starting new work on that provider. Bring the current step to a safe checkpoint, record the state and next step in the FireController job (or, for a task without one, in its own thread), and hand off to the remaining provider using the column for that situation (`codex_low` when Codex is out, `claude_low` when Claude is out). Tell the human when the substitution starts; the human decides whether to change `provider_mode`.
- When a provider is back, do not redo finished work. New assignments follow the current mode.
- If no permitted model is available, report a capacity blocker and continue any other authorized work.
- Report every substitution in the end-of-round delegation line, separately from reasoning-driven escalation.

## Host Notes

These are harness mechanics, not policy.

- **Codex:** `agent thread limit reached` is a harness slot limit, not model capacity. Do not change models; recover a slot without discarding useful active threads, or report the blocker. When editing a non-default worktree with `apply_patch`, use absolute paths.
- **Claude Code:** reads [CLAUDE.md](../../CLAUDE.md), which imports `AGENTS.md`. Subagents run through the Agent tool; pass the model and effort from the table. Repository skills are visible through `.claude/skills`, which links to `.agents/skills`.
