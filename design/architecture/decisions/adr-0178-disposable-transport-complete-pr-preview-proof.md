# ADR 0178: Disposable Transport-Complete PR Preview Proof

## Status

Accepted

## Implementation Status

This decision is partially implemented. Telnet preview proof exists, but the deployed browser path, shared semantic assertions, head-bound lease handling, isolation proof, and transport-complete acceptance remain gaps. The in-cluster hosted identity controller and runtime-first workflow contracts are implemented only as local/static integration evidence; no controller-backed hosted proof is claimed.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-21
- Human review disposition: Revised
- Review source: `OPS-06`
- Decision date: 2026-07-21
- Decision key: `OPS-06`
- Primary capability: `PO-4.4` operational verification
- Affected capabilities: `PO-3.1`, `PO-3.4`, `AA-2.1`, `EA-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of preview persistence, scarce hosted capacity, Telnet and browser public paths, shared semantic proof, security isolation, and current implementation reality

## Context

PR previews are isolated, resettable reviewer environments. The original hosted milestone deliberately proved Telnet `LOGIN -> PLAY -> LOOK` before a browser product existed. That milestone is complete. ADR 0144 now requires an independently deployed first-party frontend and browser journey, so Telnet-first cannot remain the continuing acceptance policy.

The current hosted cluster reliably supports roughly one full-stack preview. Keeping every open PR indefinitely can monopolize that capacity. Making ordinary previews persistent across arbitrary PR revisions would also introduce migrations, stale credentials, state contamination, backup expectations, and nondeterministic proof.

## Decision

PR previews remain disposable and reproducible. Eligibility is a prerequisite for allocation, not evidence that allocation succeeded. Each successfully allocated preview for an eligible new PR head receives a clean namespace and deterministic seed state; state persists only within that deployed head and is not backed up. The namespace is deleted when the PR closes, the preview is explicitly released, or its bounded renewable lease expires. Expiry is visible to the PR and never silently evicts an actively leased review session. The exact `preview:priority` label is the bounded operator-visible exception: while capacity is full, a currently labelled request may reclaim the oldest ordinary allocation but never another currently labelled allocation. Reclaim waits in the durable bounded non-cancelling lifecycle queue for any active deploy/proof lifecycle to finish, revalidates both label states immediately before deletion, and makes the displaced status and prior result visible on its pull request. Priority is an allocation decision at that serialized boundary, not a guarantee about GitHub's raw job-scheduling order.

The disposable namespace is the runtime boundary. When the hosted identity controller is active, its separate retained identity namespace survives runtime redeploy, reclaim, explicit release, and lease expiry; identity retirement is requested separately and is performed only after runtime cleanup observes exact `NotFound`. The trusted workflow prepares and validates the runtime before requesting controller `Active`, while PR-controlled rendering remains credential-free. This lifecycle amendment is defined in [ADR 0182](./adr-0182-separated-hosted-runtime-and-certificate-identity-lifecycles.md).

Eligible same-repository pull requests request allocation automatically; ordinary allocation remains first-come. A request that passes eligibility but is not allocated, including because capacity is exhausted or every allocation is priority-protected, reports `preview_unavailable`, never success. A slice that claims hosted preview proof must retain a successful result bound to its current head SHA.

One transport-neutral semantic assertion set describes the shared player outcome:

1. the user is authenticated through the path's proper authentication flow;
2. a gameplay identity is selected and admitted;
3. gameplay reaches the bound character/session state;
4. an authoritative `LOOK` result is returned.

The assertion set describes semantic states, not identical wire commands. Telnet may carry credentials in `LOGIN`; the browser authenticates through HTTPS, obtains its narrow connect-token cookie, and then enters gameplay through its browser contract.

Hosted preview proof uses two thin public-path adapters:

- **Telnet:** public TCP exposure, TCP Proxy, Proxy-to-Gateway bridge, line framing, and `LOGIN -> PLAY -> LOOK`.
- **Deployed first-party browser:** immutable frontend delivery, HTTPS Account bootstrap and discovery, realm/character selection, HttpOnly connect-token carriage, public `/ws/game/**` admission, gameplay `LOOK`, one fresh-token reconnect, explicit logout, and rejection of cleared or revoked authority. Focused browser checks also cover reserved-path routing and the static host's security/runtime-configuration contract from ADR 0144.

Direct Game Session WebSocket or backend-only smoke remains useful in CI and diagnostics but does not substitute for either supported public path and is not a third mandatory hosted-preview adapter.

Preview executes same-repository PR code inside a dedicated low-trust boundary. It has preview-only cluster credentials, data, signing/JWKS material, external bindings, and registry access and holds no production data or credentials. Full player-facing backup and admission guarantees do not apply.

Persistent creator playtests, upgrade/state-preservation proof, and long investigations use staging or a separately selected retained playtest mode. They do not weaken the clean default preview contract.

## Consequences

- Preview results remain deterministic and attributable to one PR head.
- Both supported public transports receive real ingress proof; neither stands in for the other.
- Shared semantic assertions reduce duplicated gameplay expectations while adapters retain path-specific checks.
- Browser automation and static-frontend deployment add proof work, but only short checks after the dominant full-stack deployment cost.
- Lease management and the visible priority label make scarce capacity explicit; ordinary previews may be displaced, while priority-labelled previews wait rather than preempting each other.
- Reviewer-created state is lost on redeploy; retained playtests use another environment.

## Alternatives Considered

### Keep Telnet as the Only Hosted Acceptance Path

This is valid MUD protocol proof but cannot exercise frontend delivery, HTTPS bootstrap, browser cookie carriage, route separation, CSP, reconnect, or logout. It conflicts with the accepted browser boundary.

### Make Every Preview Persistent Across Updates

This helps manual authoring and upgrade investigation but makes ordinary proof depend on unknown prior state and adds migrations, cleanup, storage, secret, and backup obligations across arbitrary PR code.

### Use Only Direct Backend or Python WebSocket Smoke

This is fast and useful for diagnosis but bypasses the TCP edge or deployed browser/frontend contracts. It cannot establish supported public-path behavior.

## Implementation and Proof Obligations

Implement the static frontend preview workload and route, shared semantic assertion model, Telnet adapter, bounded Playwright browser journey, head-SHA evidence binding, lease/eligibility handling, and visible unavailable/expired/reclaimed outcomes. Prove clean redeploy, pod restart within a head, lease renewal/expiry, active-lifecycle protection, ordinary oldest-first priority reclaim, all-priority refusal, capacity exhaustion, PR closure cleanup, namespace trust isolation, both public paths, browser reconnect/logout/non-reuse, and diagnostic backend smoke independence.

## Reversibility and Revisit Triggers

Lease duration and eligibility mechanics are operational settings. Revisit capacity or a retained preview mode when measured concurrent review demand justifies more infrastructure or when a concrete upgrade/playtest use case cannot be served by staging. Do not convert ordinary previews into durable environments implicitly.
