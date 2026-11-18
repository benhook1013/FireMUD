# Model Routing

Use this guide when choosing a model for a subagent, an independent review, or a persistent task. It is the only place in the repository that names model families. [AI delegation and review](./ai-delegation-and-review.md) owns delegation boundaries, prompt requirements, and review evidence; this guide only selects the model.

FireMUD work runs on two providers: Codex (Luna and Sol families) and Claude (Opus, with Sonnet as an optional cheaper option). Either may be unavailable at any time, so every role has a default plus a single-provider fallback.

## Principles

- Choose by task role, then read the model from the table. Do not assign work by provider name alone.
- Resolve the latest available version of the selected family from actual tool model metadata at delegation time. Do not pin version identifiers or infer availability from marketing descriptions.
- An explicit human model choice takes precedence. Preserve the user-selected model and reasoning settings of persistent tasks unless that provider is unavailable.
- Prefer a reviewer from a different model family than the author. Different families tend to miss different things, which makes the review more independent.

## Routing Table

| Role | What it covers | Both providers available | Codex only | Claude only |
|---|---|---|---|---|
| Orchestrator | Main thread or lane orchestrator: planning, integration, adjudication, PR and review operations | Human's choice per session (Sol or Opus) | Sol | Opus |
| Bulk executor | Bounded investigation, mechanical patches, straightforward test updates | Luna `high` | Luna `high` | Opus `medium` (Sonnet optional) |
| Deep executor | Substantial multi-file implementation, wide reading, documentation rewrites | Opus `high` | Luna `xhigh`, then Sol `medium` | Opus `high` |
| Independent reviewer | Fresh-context review or commissioned audit | The other provider from the author: Sol `high` for Opus or Luna work, Opus `high` for Sol work | Sol `high`, fresh context | Opus `high` or `xhigh`, fresh context |
| Arbiter | Conflicting evidence, competing options, consequential analysis | Sol or Opus at `high`, preferring whichever did not produce the evidence | Sol `high` | Opus `high` |

Reasoning or effort scales differ by provider. Codex offers `low`, `medium`, `high`, and `xhigh`; Claude offers `low`, `medium`, `high`, `xhigh`, and `max`. `max` is not an allowed autonomous tier for either provider. Every tier in the table may be chosen without separate approval.

## Escalation

- Start at the role's tier. Escalate by raising effort within the role.
- After two inconclusive passes, switch to the other provider at the same role when it is available, rather than climbing further on the same model.
- Do not delegate product or architecture design decisions at any tier.

## Fallback

- If a launch fails because the selected model is at capacity, wait 30 to 60 seconds and retry the same model and tier once.
- If the retry fails, or the provider is down, out of quota, or rate-limited, use the single-provider column for the remaining provider. This needs no approval. Report the substitution when it starts and in the end-of-round delegation line, and distinguish it from reasoning-driven escalation.
- When the provider returns, do not redo completed work. New assignments use the default column again.
- If no permitted model is available, report a capacity blocker and continue independent authorized work.

## Host Notes

These are harness mechanics, not policy.

- **Codex:** treat `agent thread limit reached` as a structural harness limit, not model capacity. Do not change models or use capacity retries; inspect agent states, recover a slot without discarding useful active threads, or report the blocker. When editing a non-default worktree with `apply_patch`, use absolute target paths.
- **Claude Code:** reads [CLAUDE.md](../../CLAUDE.md), which imports `AGENTS.md`. Subagents run through the Agent tool; pass the model and effort from the table. Repository skills are visible through `.claude/skills`, which links to `.agents/skills`.
