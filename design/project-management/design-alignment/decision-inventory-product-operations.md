# Product And Operations Decision Inventory

Status: Complete and independently coverage/fidelity-audited. This artifact is non-normative. It records consequential decisions found in the assigned product, frontend, authoring, protocol, infrastructure, deployment, recovery, observability, and generated-settings sources. It does not replace the canonical architecture or cross-cutting inventories.

This inventory contains 38 decision rows. Its assigned source set is the exact 35-path product/operations partition in the disjoint `22 + 39 + 35 = 96` allocation: 22 counted cross-cutting paths, 39 additional specialized runtime/support paths, and 35 product/operations paths. Counts and capability coverage below are derived from the rows in this file.

## Implementation Status

- **Scan completeness:** The product/operations documentation scan is complete for its disjoint 35-path assignment; the full 96-path allocation is accounted for by the 22 cross-cutting, 39 specialized runtime/support, and 35 assigned paths.
- **Implementation and approval state:** The `Complete` status above describes scan coverage and fidelity only. It does not mean that every decision is implemented or approved; each row's status and human-consultation field record the current state.

## Scope And Method

The 96-path allocation universe contains 89 direct architecture sources, 6 infrastructure sources, and 1 generated source. The 22 paths already counted by `decision-inventory-cross-cutting.md` were excluded from this scan:

- `design/architecture/README.md`
- `design/architecture/system-architecture-overview.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-grpc.md`
- `design/architecture/system-architecture-settings-model.md`
- `design/architecture/system-architecture-transactions.md`
- `design/architecture/system-architecture-temporal-workflows.md`
- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/system-architecture-player-command-model.md`
- `design/architecture/system-architecture-input-output-and-presentation.md`
- `design/architecture/system-architecture-game-customization.md`
- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-versioning-runtime.md`
- `design/architecture/system-architecture-security.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-backup-recovery.md`
- `design/architecture/service-responsibility-matrix.md`

The specialized runtime/support exclusion contains 42 named or pattern-matched paths, but three (`system-architecture-redis.md`, `system-architecture-scripting.md`, and `system-architecture-ticks.md`) are already in the counted cross-cutting partition. The disjoint specialized set therefore contributes 39 additional paths: all remaining `system-architecture-redis*.md`, `system-architecture-scripting*.md`, and `system-architecture-tick*.md` files, plus `system-architecture-spatial-and-ambient-effects-catalog.md`, `system-architecture-identifier-glossary.md`, `system-architecture-authz-route-matrix.md`, `system-architecture-jwt-and-token-contracts.md`, `system-architecture-shared-libraries.md`, `system-architecture-database-migrations.md`, and `system-architecture-tracing.md`. The resulting assigned set is the 35 paths in the coverage ledger below.

Cross-partition references may be cited to reconcile an assigned-source decision with its canonical system contract, but they do not become part of this inventory's assigned corpus or its 35-path coverage count. In particular, `SESSION-08` cross-checks `system-architecture-reconnection.md`, `system-architecture-redis.md`, and `system-architecture-session-behavior.md`; those three remain counted only in the cross-cutting partition.

The scan used the threshold in [consequential-decision-inventory.md](consequential-decision-inventory.md): a choice is recorded only when it crosses a service or product boundary, affects security, tenant isolation, durability, consistency, recovery, operational safety, cost, or player/creator experience, constrains extensibility, has a credible alternative, or leaves a material conflict or human decision unresolved. Existing keys are cross-referenced rather than copied. Routine implementation choices, indexes, diagrams, and generated values without independent authority are explicitly classified as no-decision evidence.

Status meanings are `accepted-explicit`, `accepted-implicit`, `proposed/deferred`, `conflicting`, or `needs-human-review`. Importance/reversibility uses `H` or `M` for impact and `hard` or `med` for the cost of reversal. `Human: yes` means the decision needs product, security, operations, legal, or creator/player consultation before its boundary is treated as final; it is not a claim that approval has already occurred.

## New Decision Ledger

### Assets, Release, And Recovery

#### `ASSET-01` - CAS-guarded asset lifecycle and exact-byte repair

- **Capability:** Primary `AR-1.5` Revisions, versions, publishing, validation, and attestation. Secondary `AR-1.4`, `AR-3.2`, `AR-3.3`, `PO-3.3`, and `SF-2.3`.
- **Decision / status / importance:** Treat the persisted `version_asset_artifact` record and its `stateEpoch` as authoritative for publish, retire, purge, and repair. Use compare-and-set admission (`CanDeleteVersionAssets`, `BeginPurgeVersionAssets`) and explicit `TOMBSTONED -> PURGE_IN_PROGRESS -> PURGED` or `PURGE_FAILED` states; never infer lifecycle from object-store listings. Published or Active repair must restore exact expected bytes and fail closed on mismatch. Status `accepted-implicit`; `H/hard`.
- **Sources / headings:** [system-architecture-asset-store-runbook.md](../../architecture/system-architecture-asset-store-runbook.md) `§ Health Checks`, `§ Incident Handling`, `§ MinIO Deployment and Configuration`, and `§ Handling Failed Publish Versions`.
- **Strongest alternative:** Let operators check a bucket listing and manually delete or mutate objects, with application state inferred from object-store contents.
- **ADR recommendation:** Yes. Record the asset-state authority, CAS transition contract, exact-byte repair rule, and last-resort manual-operation boundary.
- **Human consultation:** Yes; operations and content-publishing owners must accept the irreversible purge and repair authority.

#### `ASSET-02` - Unattested or failed publishes are not launchable

- **Capability:** Primary `AR-3.2` Release readiness, compatibility, and propagation. Secondary `AR-1.5`, `AR-1.4`, `PO-3.3`, and `SF-2.3`.
- **Decision / status / importance:** A failed, partial, or `EXPORTED_UNATTESTED` prefix is never launchable. Retain terminal diagnostic metadata, retry only through the documented publish workflow, and make a missing or mismatched manifest hash a fail-closed condition for Published and Active versions. Status `accepted-implicit`; `H/hard`.
- **Sources / headings:** [system-architecture-asset-store-runbook.md](../../architecture/system-architecture-asset-store-runbook.md) `§ Health Checks` and `§ Handling Failed Publish Versions`; [system-architecture-promotion-attestation.md](../../architecture/system-architecture-promotion-attestation.md) `§ Validation Rules`.
- **Strongest alternative:** Expose a partially exported version and repair it in place while runtime admission continues.
- **ADR recommendation:** Yes. Tie launch admission to the attested artifact state and define the operator-visible retry and quarantine path.
- **Human consultation:** Yes; content, release, and incident owners must define acceptable repair and retention windows.

#### `RECOVERY-01` - Evidence-gated player traffic opening

- **Capability:** Primary `PO-3.4` Backup, restore, disaster recovery, and self-hosted recovery. Secondary `PO-4.4`, `PO-3.1`, `GR-1.4`, and `SF-2.1`.
- **Decision / status / importance:** Do not open or reopen player-facing traffic until an online environment-wide PostgreSQL artifact has passed verification and a production-equivalent `cold_start_restore` drill has proved quarantine, safe recovery-participant dispositions, hardening, and controlled reopen within the stated window. Ordinary rollback-compatible releases reuse the baseline through a compact compatibility result; `roll-forward-only` releases require an exact release-candidate drill. Status `accepted-explicit`; implementation and proof remain incomplete; `H/hard`.
- **Sources / headings:** [ADR 0015](../../architecture/decisions/adr-0015-online-backup-and-environment-wide-cold-start-recovery.md); [system-architecture-backup-recovery-evidence-and-compliance.md](../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md) `§ Production Backup Readiness Evidence`, `§ Production Recovery Compatibility Result`, `§ Production Traffic-Open Backup Evidence`, `§ Hobby Backup Compliance Evidence`, `§ Hobby Traffic-Open Evidence`, and `§ Canonical Recovery Record`; [system-architecture-deployment-runbook.md](../../architecture/system-architecture-deployment-runbook.md) `§ Production Traffic-Open Backup Gate`, `§ Recovery Proof Cadence and Release Reuse`, and `§ Fresh-Boundary Restore Bootstrap`.
- **Strongest alternative:** Open traffic after a healthy rollout or scheduled backup check, then perform restore evidence later or only on demand.
- **ADR recommendation:** ADR 0015 is the accepted authority. Revisit only if FireMUD adopts PITR, tenant/scoped restore, a different player-facing recovery mode, or materially different RPO/RTO promises.
- **Human consultation:** Yes; the release owner must accept the availability cost of withholding traffic and the recovery-drill standard.

#### `RECOVERY-02` - Canonical quarantine release and trust reset

- **Capability:** Primary `PO-3.4`. Secondary `SF-1.3`, `PO-4.4`, `GR-1.4`, and `PO-2.1`.
- **Decision / status / importance:** A player-facing restored environment remains quarantined until the canonical environment-wide `cold_start_restore` record proves empty Coordination Redis, gameplay and Account session invalidation, epoch/fence reset, safe durable/external participant dispositions, secret and certificate hardening, external credential validation, smoke checks, and explicit reopen approval. `scoped_reset_restore` remains quarantined/deferred pending a separate design and proof package; staging restores from production-origin data also require sanitization. Status `accepted-explicit`; implementation and proof remain incomplete; `H/hard`.
- **Sources / headings:** [system-architecture-backup-recovery-evidence-and-compliance.md](../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md) `§ Canonical Recovery Record`; [system-architecture-post-restore-hardening.md](../../architecture/system-architecture-post-restore-hardening.md) `§ Restore Quarantine`, `§ Post-Restore Secret Hardening`, `§ Post-Restore Coordination Recovery Gate`, `§ Reopen Sequence`, and `§ Planned DB Credential Rotation`; [system-architecture-jwt-compromise-runbook.md](../../architecture/system-architecture-jwt-compromise-runbook.md) `§ Required Response Flow` and `§ Mandatory Evidence Checklist`.
- **Strongest alternative:** Use an operator's manual checklist and restore services incrementally without a single typed record or an explicit trust reset.
- **ADR recommendation:** ADR 0015 is the accepted authority. Remaining work is implementation and production-equivalent proof; a separate accepted decision is required before `scoped_reset_restore` can become player-facing.
- **Human consultation:** Yes; security, database, platform, and operations owners must approve the trust-reset and player-impact policy.

#### `PREFLIGHT-01` - One deterministic fail-closed deployment preflight

- **Capability:** Primary `PO-3.1` Packaging, CI/CD, deployment, and infrastructure topology. Secondary `PO-3.2`, `PO-4.4`, and `SF-1.1`.
- **Decision / status / importance:** Use one deterministic preflight policy, report shape, and policy-ID set for CI and operator apply across `staging`, `production`, and `hobby-self-hosted`. Apply is blocked unless all applicable checks pass or an event-scoped waiver contains an approver, ticket, rationale, and expiration; a static report alone cannot authorize traffic opening. Status `proposed/deferred`; `H/hard`.
- **Sources / headings:** [system-architecture-deploy-preflight-policy.md](../../architecture/system-architecture-deploy-preflight-policy.md) `§ Bootstrap Contract`, `§ Authoritative Entrypoint`, `§ Enforcement Boundaries`, `§ Environment Applicability`, `§ Required Policy Checks`, `§ Evidence Contract`, `§ Evidence Storage and Retention`, and `§ Failure Handling`; [system-architecture-deployment-runbook.md](../../architecture/system-architecture-deployment-runbook.md) `§ Overlay Deployment Flow (Staging and Production)` and `§ Hobby Manifest/Chart Deployment Flow (Hobby / Self-Hosted)`.
- **Strongest alternative:** Maintain separate CI, operator, and environment-specific checks with manual interpretation of their outputs.
- **ADR recommendation:** Yes. Establish the single preflight authority and its relationship to deployment apply, break-glass, and traffic-open gates.
- **Human consultation:** Yes; operators and release owners must define waiver authority and the minimum checks for each environment class.

#### `PREFLIGHT-02` - Explicit expected-binding and environment-isolation contract

- **Capability:** Primary `PO-3.2` Environment, configuration, secret, certificate, and service-discovery delivery. Secondary `PO-3.4`, `SF-1.3`, and `PO-4.4`.
- **Decision / status / importance:** Treat `design/operations/environments/<environment>/expected-bindings.yaml` and its `bindingRef` as the canonical declaration of internal and external bindings. Undeclared cross-environment reuse fails preflight; a shared binding is valid only with `shared: true` and a written rationale. Status `proposed/deferred`; `H/hard`.
- **Sources / headings:** [system-architecture-deploy-preflight-policy.md](../../architecture/system-architecture-deploy-preflight-policy.md) `§ Canonical Expected-Binding Inputs`, `§ Required Policy Checks`, and `§ Failure Handling`; [deployment-environments.md](../../architecture/infrastructure/deployment-environments.md) `§ Canonical Environment Classes` and `§ Kubernetes Characteristics`; [environment-and-secrets-overview.md](../../architecture/infrastructure/environment-and-secrets-overview.md) `§ Secret Governance Tiers` and `§ Player-Facing Environment Bootstrap Requirements`.
- **Strongest alternative:** Rely on cluster-local names, default configuration, or operator knowledge to determine whether a binding is safe to reuse.
- **ADR recommendation:** Yes. Define the binding manifest as the isolation proof and specify how shared infrastructure is reviewed and attested.
- **Human consultation:** Yes; security and platform operators must approve the allowable shared-resource exceptions.

#### `PROMO-01` - Git-reviewed promotion evidence is the current trust root

- **Capability:** Primary `PO-3.1`. Secondary `PO-4.4` and `SF-1.1`.
- **Decision / status / importance:** In the current single-admin/operator model, the exact in-repository JSON promotion attestation committed with the production overlay is the trust root: it records staged digest lineage, approval or ticket, and the exact release manifest, and forbids rebuild during promotion. Detached signatures or SLSA-style provenance remain future alternatives rather than current prerequisites. Status `accepted-implicit`; `H/med`.
- **Sources / headings:** [system-architecture-promotion-attestation.md](../../architecture/system-architecture-promotion-attestation.md) `§ Artifact Format`, `§ Validation Rules`, `§ Storage and Retention`, and `§ Ownership`; [system-architecture-deployment-runbook.md](../../architecture/system-architecture-deployment-runbook.md) `§ Overlay Deployment Flow (Staging and Production)` and `§ Rollback`.
- **Strongest alternative:** Require detached cryptographic signatures, an external transparency log, or SLSA provenance before any production promotion.
- **ADR recommendation:** Yes. Record why Git-reviewed immutable evidence is sufficient now and the trigger for upgrading the trust root before multi-party or untrusted promotion.
- **Human consultation:** Yes; security and release-governance owners must accept the current trust model.

### Frontend, Protocol, Authoring, And Observability

#### `FRONT-01` - Dedicated first-party web application after the Telnet-first proof

- **Capability:** Primary `EA-3.1` Player web/mobile applications. Secondary `PO-3.1`, `PO-2.2`, and `EA-3.4`.
- **Decision / status / importance:** The long-term player browser is a dedicated first-party web application rather than a web surface hosted by Gateway. The first hosted proof is the TCP/Telnet `LOGIN -> PLAY -> LOOK` path; browser discovery, bootstrap, and reconnect are sequenced after that proof. Status `proposed/deferred`; `H/med`.
- **Sources / headings:** [system-architecture-frontend.md](../../architecture/system-architecture-frontend.md) `§ Hosting Direction`, `§ Build Tooling`, and `§ End-to-End Testing`; [deployment-environments.md](../../architecture/infrastructure/deployment-environments.md) `§ PR Preview Environment` and `§ Staging Environment for Playtesting`; [user-journeys-players.md](../../architecture/user-journeys-players.md) `§ 2. Join a Game for the First Time` and `§ 4. Player Login and Gameplay`.
- **Strongest alternative:** Make the browser the first end-to-end proof or keep player web rendering inside Gateway as a permanent architecture.
- **ADR recommendation:** Yes before committing hosted web routing, preview ownership, or browser-specific release guarantees; otherwise cross-reference `OPS-06`.
- **Human consultation:** Yes; product, frontend, and operations owners must agree on sequencing and hosting ownership.

#### `LLM-01` - Human-controlled, sandboxed, draft-only authoring assistance

- **Capability:** Primary `AR-1.2` Procedural, LLM-assisted, and external authoring tools. Secondary `AR-1.5`, `AS-1.2`, and `EA-3.2`.
- **Decision / status / importance:** LLM tooling is design-time only. Game Design Service and its authoring UI orchestrate it; the model cannot directly call design APIs, write the production database or asset store, or act as an in-game chatbot. Any optional offline agent is read-only except for typed draft-bundle endpoints, and validation and human review happen outside the model before publish. Status `proposed/deferred`; `H/hard`.
- **Sources / headings:** [system-architecture-llm-content-tools.md](../../architecture/system-architecture-llm-content-tools.md) `§ Non-Goals`, `§ Integration Model`, `§ Phased Implementation`, `§ Agent Sandbox Model`, and `§ Safety and Review`.
- **Strongest alternative:** Permit autonomous model writes to authoring or production APIs, use a general plugin runtime, or make an LLM agent part of gameplay.
- **ADR recommendation:** Yes. Record the trust boundary, draft-bundle schema, sandbox permissions, quota model, and human publication gate.
- **Human consultation:** Yes; creators, security, privacy, and product owners must decide provider, retention, prompt-data, and PII boundaries, which the source does not yet settle.

#### `OBS-01` - Bounded-cardinality observability identity

- **Capability:** Primary `PO-4.1` Logging, metrics, tracing, dashboards, and alerting. Secondary `SF-1.1` and `PO-4.4`.
- **Decision / status / importance:** Keep tenant, game, region, session, and player identity in structured logs and trace context, but do not use raw high-cardinality identifiers as ordinary metric labels. Runtime identity exposure and any bounded exception must be explicit and reviewed. Status `accepted-implicit`; `H/med`.
- **Sources / headings:** [system-architecture-logging-monitoring.md](../../architecture/system-architecture-logging-monitoring.md) `§ Request-Path Logging Baseline and Bounded Exceptions`, `§ Runtime Identity Exposure`, `§ Metrics Cardinality Rule For Runtime Identity`, and `§ Cardinality Guardrails for Metrics`.
- **Strongest alternative:** Put raw player, session, tenant, or trace IDs on all metrics for easy filtering.
- **ADR recommendation:** Yes before granting an exception; otherwise cross-reference the existing observability contract and record only the bounded label set.
- **Human consultation:** Yes; operations and privacy/security owners must approve identity exposure and retention.

#### `OBS-02` - Player-experience SLO targets are a product reliability contract

- **Capability:** Primary `PO-4.2` Health, readiness, reliability policy, SLOs, and degraded operation. Secondary `PO-2.4`, `GR-1.2`, and `EA-2.1`.
- **Decision / status / importance:** Use target-state player SLIs/SLOs, not infrastructure health alone: login at least 99.5% over 15 minutes, core move/look/combat commands at least 99% under 250 ms over 5 minutes, entry path at least 99.9% over one day with sustained P0 treatment, and chat at least 99% under 1 second over 5 minutes. Status `proposed/deferred`; `H/med`.
- **Sources / headings:** [system-architecture-logging-monitoring.md](../../architecture/system-architecture-logging-monitoring.md) `§ Player Experience SLIs and SLOs (Target-State Contract)` and `§ Player Experience Metrics Catalog (Target-State Contract)`; [system-architecture-player-experience-incident-runbook.md](../../architecture/system-architecture-player-experience-incident-runbook.md) `§ Incident Types`, `§ Login Success Ratio Below SLO`, `§ Command Latency Above SLO`, `§ Chat Delivery Latency Above SLO`, and `§ Telnet and WebSocket Path Availability Below SLO`.
- **Strongest alternative:** Operate on process health, CPU, and error rate without player-facing budgets, or choose different windows and thresholds.
- **ADR recommendation:** Yes. Product and operations should approve these as release and incident budgets before they become hard gates.
- **Human consultation:** Yes; product, SRE, and player-support owners must accept the user-visible tradeoffs.

#### `OBS-03` - Independent synthetic player-flow canaries

- **Capability:** Primary `PO-4.4` Smoke, canary, incident evidence, and architecture conformance proof. Secondary `EA-3.1`, `PO-2.1`, and `AA-1.1`.
- **Decision / status / importance:** Exercise login and LOOK through each public path with a dedicated marked canary identity and data set. Keep canary traffic outside normal moderation and analytics, run probes outside the failure domain, and alert with the defined severity mapping. Status `proposed/deferred`; `H/med`.
- **Sources / headings:** [system-architecture-logging-monitoring.md](../../architecture/system-architecture-logging-monitoring.md) `§ Synthetic Player-Flow Canaries (Target-State Prod-Like Contract)` and `§ Canary Alert Contract (Target-State Prod-Like Contract)`; [system-architecture-player-experience-incident-runbook.md](../../architecture/system-architecture-player-experience-incident-runbook.md) `§ Incident Types` and `§ Telnet and WebSocket Path Availability Below SLO`; [system-architecture-testing.md](../../architecture/system-architecture-testing.md) `§ Synthetic Player-Flow Canary Checks`.
- **Strongest alternative:** Infer player availability from live traffic or run probes from inside the same cluster and monitoring failure domain.
- **ADR recommendation:** Yes. Define canary identity/data ownership, exclusion from product analytics, public-path coverage, and alert authority.
- **Human consultation:** Yes; operations, moderation, privacy, and product owners must approve the synthetic account and data policy.

#### `OBS-04` - Observability is mostly best effort, with explicit authoritative fallbacks

- **Capability:** Primary `PO-4.2`. Secondary `PO-1.1`, `PO-1.4`, and `PO-4.4`.
- **Decision / status / importance:** Keep authoritative gameplay, Gateway, Logging/Admin, and moderation dependencies usable when Elasticsearch, Prometheus, Grafana, Jaeger, or Alertmanager enrichment is unavailable. Alertmanager is authoritative when healthy; a fallback recording path is used only while it is unavailable and is stale after the specified freshness window. Status `accepted-implicit`; `H/hard`.
- **Sources / headings:** [system-architecture-logging-monitoring.md](../../architecture/system-architecture-logging-monitoring.md) `§ Degraded Modes and Observability Dependencies`, `§ Alert Fallback Recording Rules`, `§ Logging & Admin Alert-State Contract (Normative)`, and `§ Alert Taxonomy and Ownership`; [system-architecture-observability-incident-runbook.md](../../architecture/system-architecture-observability-incident-runbook.md) `§ Common Fallbacks (When Dashboards Are Unavailable)`, `§ Prometheus Down or Stale`, `§ Alertmanager Down or Not Routing`, `§ Elasticsearch/Kibana Down or Indexing Stalled`, `§ Grafana Down`, and `§ Jaeger / OpenTelemetry Collector Down`.
- **Strongest alternative:** Make dashboards, metrics, traces, or the alerting backend hard dependencies for gameplay or administrative safety actions.
- **ADR recommendation:** Yes. Reconcile fallback authority, stale-state behavior, and which operational actions remain possible without enrichment.
- **Human consultation:** Yes; incident, moderation, and platform owners must approve the degraded-mode safety boundary.

#### `OBS-05` - Independent deadman detection for total monitoring and edge failure

- **Capability:** Primary `PO-4.4`. Secondary `PO-2.1` and `PO-4.2`.
- **Decision / status / importance:** An external pager or blackbox path must detect total monitoring-stack failure and public gameplay-edge failure; a Prometheus mirror alone is insufficient. The deadman freshness threshold defaults to three expected intervals, with a 60-second interval and 180-second threshold where the runbook applies. Status `proposed/deferred`; `H/hard`.
- **Sources / headings:** [system-architecture-logging-monitoring.md](../../architecture/system-architecture-logging-monitoring.md) `§ External Probe and Deadman Contract (Normative)` and `§ Synthetic Player-Flow Canaries (Target-State Prod-Like Contract)`; [system-architecture-observability-incident-runbook.md](../../architecture/system-architecture-observability-incident-runbook.md) `§ Independent Detection Contract` and `§ Deadman Freshness Contract`; [deployment-environments.md](../../architecture/infrastructure/deployment-environments.md) `§ Monitoring & Logging`.
- **Strongest alternative:** Rely on Prometheus and Alertmanager inside the same failure domain, including when that stack is the failed system.
- **ADR recommendation:** Yes. Choose the external monitoring owner, failure-domain separation, probe inventory, and deadman freshness authority.
- **Human consultation:** Yes; operations and incident-communications owners must approve pager ownership and alert severity.

#### `OBS-06` - End-to-end structured-log queryability is readiness evidence

- **Capability:** Primary `PO-4.4`. Secondary `PO-4.1`.
- **Decision / status / importance:** A deployed path is not observability-ready merely because emitters are producing logs. Structured logs and trace context must be queryable through the `firemud-logs-*` index by service and request context within the default two-minute window, with missing queryability treated as evidence failure. Status `proposed/deferred`; `M/med`.
- **Sources / headings:** [system-architecture-logging-monitoring.md](../../architecture/system-architecture-logging-monitoring.md) `§ Log Pipeline Queryability Contract` and `§ Health Checks`; [system-architecture-observability-incident-runbook.md](../../architecture/system-architecture-observability-incident-runbook.md) `§ Elasticsearch/Kibana Down or Indexing Stalled` and `§ Post-Incident Checklist`; [system-architecture-testing.md](../../architecture/system-architecture-testing.md) `§ Observability Tests`.
- **Strongest alternative:** Accept logger or collector output as proof without an end-to-end indexed query test.
- **ADR recommendation:** Yes if this becomes a deployment gate; otherwise keep it as a target-state evidence check linked to `OPS-06`.
- **Human consultation:** Yes; operations must choose the queryability SLO and evidence retention cost.

#### `MCP-01` - Optional structured protocol extension with plain-text fallback

- **Capability:** Primary `PO-2.3` Client protocol negotiation and structured protocol extensions. Secondary `PO-2.2`, `EA-1.2`, and `PO-2.4`.
- **Decision / status / importance:** MCP is optional and negotiated per connection; plain text remains canonical, unknown packages are ignored, malformed packages are dropped, and MCP is never gameplay-critical. Budgets for active cords, data tags, control lines, and negotiation failures are per connection/auth key/cord state. Status `accepted-implicit`; `M/med`.
- **Sources / headings:** [system-architecture-mud-client-protocol.md](../../architecture/system-architecture-mud-client-protocol.md) `§ Protocol Handshake`, `§ Message Format`, `§ Optional Packages`, `§ Interaction with abuse heuristics`, `§ MCP resource limits & abuse budgets`, and `§ Reconnection & Session Recovery`; [system-architecture-protocol-bridging.md](../../architecture/system-architecture-protocol-bridging.md) `§ Ordering & Delivery Invariants` and `§ Backpressure & Slow Clients`.
- **Strongest alternative:** Make structured MCP messages required, use them as the canonical gameplay representation, or silently reconnect and reattach negotiated state.
- **ADR recommendation:** No new ADR while MCP remains optional. Yes before making MCP gameplay-critical or promising a compatibility-stable package/limit contract.
- **Human consultation:** Yes before a client-compatibility promise; otherwise the current optional boundary is sufficient.

### Procedural Generation And Runtime Authoring

#### `PROC-01` - Separate design-template and runtime-instance generation authority

- **Capability:** Primary `AR-1.1` World, entity, rule, and content authoring. Secondary `GR-2.1`, `AR-3.2`, and `AS-1.3`.
- **Decision / status / importance:** Generation is mode-aware: Game Design Service alone writes draft template rows in `DESIGN_TEMPLATE`; runtime generation creates `RUNTIME_INSTANCE` output and never writes templates. Published templates are immutable, and template and instance keys remain distinct. Status `accepted-implicit`; `H/hard`.
- **Sources / headings:** [system-architecture-procedural-generation.md](../../architecture/system-architecture-procedural-generation.md) `§ Generation Pipeline:`, `§ Output and Metadata (Common)`, `§ Integration Guidelines`, and `§ Service Responsibilities`; [user-journeys-creators.md](../../architecture/user-journeys-creators.md) `§ 2. World and Entity Design` and `§ 4. Publish and Start a Game Instance`.
- **Strongest alternative:** Use one mutable generator path that can write either design templates or live runtime state based on caller intent.
- **ADR recommendation:** Yes. Make the mode and write-authority boundary explicit alongside `CONTENT-01` and `CONTENT-03`.
- **Human consultation:** Yes; creators and runtime owners must approve the boundary and its authoring ergonomics.

#### `PROC-02` - Deterministic generation provenance and fail-closed replay

- **Capability:** Primary `AR-1.5`. Secondary `AR-3.2`, `GR-2.1`, and `SF-2.3`.
- **Decision / status / importance:** Persist `generationRunId` or `requestId`, implementation version, configuration snapshot, schema version, seed, and output digest. Replay must reproduce the exact output or fail `OUT_OF_SYNC`; the version-scoped generation configuration revision is frozen for publish and runtime. Status `accepted-implicit`; `H/hard`.
- **Sources / headings:** [system-architecture-procedural-generation.md](../../architecture/system-architecture-procedural-generation.md) `§ Deterministic Replay Contract for Design-Time Generation`, `§ Generation Pipeline:`, and `§ Output and Metadata (Common)`; [system-architecture-promotion-attestation.md](../../architecture/system-architecture-promotion-attestation.md) `§ Validation Rules`.
- **Strongest alternative:** Rerun generation with current implementation or mutable defaults and accept a different output if the seed is unchanged.
- **ADR recommendation:** Yes. Record the provenance fields, digest definition, replay failure behavior, and relationship to immutable content promotion.
- **Human consultation:** Yes; content and operations owners must decide the retention and user-facing handling of out-of-sync revisions.

#### `PROC-03` - Revision-scoped replacement preserves manual edits

- **Capability:** Primary `AR-1.1`. Secondary `AR-1.5`, `AR-2.3`, and `GR-2.1`.
- **Decision / status / importance:** Generation revisions are first-class and scope-aware. `REPLACE_SCOPE` may replace only the declared generated scope; `SEED_APPEND_ONLY` may append without rewriting prior generated or manual content. Scope epochs and generation configuration revisions prevent a later run from silently overwriting manual edits. Status `accepted-implicit`; `H/hard`.
- **Sources / headings:** [system-architecture-procedural-generation.md](../../architecture/system-architecture-procedural-generation.md) `§ Deterministic Replay Contract for Design-Time Generation`, `§ Generation Pipeline:`, and `§ Integration Guidelines`; [user-journeys-creators.md](../../architecture/user-journeys-creators.md) `§ 5. Patch and Update a Live Game`.
- **Strongest alternative:** Overwrite or rebase all generated output on every run and require creators to reapply manual edits.
- **ADR recommendation:** Yes. Define scope ownership, revision/epoch semantics, conflict reporting, and the publish-time freeze.
- **Human consultation:** Yes; creators must choose replacement and append semantics for each generator family.

#### `PROC-04` - Staged, idempotent, single-writer generation with convergence

- **Capability:** Primary `GR-2.1` World topology, rooms, regions, and runtime instances. Secondary `GR-1.4`, `SF-2.3`, and `AS-1.5`.
- **Decision / status / importance:** Use staged output and an explicit finalize step, stable business idempotency keys, uniqueness constraints, and one writer per generation scope. Runtime population retries until convergence and does not use compensating deletes except for ephemeral instances; partial persistence and duplicate effects are not acceptable. Status `accepted-implicit`; `H/hard`.
- **Sources / headings:** [system-architecture-procedural-generation.md](../../architecture/system-architecture-procedural-generation.md) `§ Generation Pipeline:`, `§ Output and Metadata (Common)`, `§ Integration Guidelines`, and `§ Service Responsibilities`; [system-architecture-protocol-bridging.md](../../architecture/system-architecture-protocol-bridging.md) `§ Ordering & Delivery Invariants`.
- **Strongest alternative:** Put the whole generation in one oversized transaction or compensate failed populations with broad deletes.
- **ADR recommendation:** Yes. Align staged/finalize behavior with `TICK-03`, `TICK-04`, `TICK-05`, and the replay/reconciliation contract.
- **Human consultation:** Yes; runtime and content owners must accept convergence latency and the cleanup boundary.

#### `PROC-05` - World density and movement cost are product choices

- **Capability:** Primary `GR-2.2` Location, occupancy, movement, exits, and spatial reads. Secondary `GR-2.1` and `AR-1.1`.
- **Decision / status / importance:** Sparse versus full-grid generation and the `spacingMultiplier` effect on movement/travel cost are part of the world-design contract, not merely storage optimizations. The source supplies algorithms and defaults but does not settle the desired density or player travel budget. Status `needs-human-review`; `M/med`.
- **Sources / headings:** [system-architecture-procedural-generation.md](../../architecture/system-architecture-procedural-generation.md) `§ 1. SimpleDungeonGenerator`, `§ Algorithm`, `§ 2. OverworldMapGenerator`, `§ Generation Pipeline:`, and `§ Integration Guidelines`; [user-journeys-creators.md](../../architecture/user-journeys-creators.md) `§ 2. World and Entity Design`.
- **Strongest alternative:** Require a full grid with uniform movement cost, or treat spacing as an implementation-only tuning knob.
- **ADR recommendation:** Yes before generation defaults become a platform-wide content contract; record per-game configurability if that is the choice.
- **Human consultation:** Yes; game designers and player-experience owners must set density and travel expectations.

### Capacity, Environments, And Verification

#### `CAPACITY-01` - Baseline sizing is a calibration envelope, not a promise

- **Capability:** Primary `PO-4.2` Health, readiness, reliability policy, SLOs, and degraded operation. Secondary `GR-1.1`, `SF-2.2`, and `PO-4.3`.
- **Decision / status / importance:** The starting envelope of roughly 50-100 active regions per pod at a 100-250 ms tick interval, one in-flight tick plus a small buffer, and a tens-of-thousands timer/retry envelope is a sizing hypothesis. It must be calibrated by target-profile load tests before it becomes a capacity gate or SLO. Status `proposed/deferred`; `M/med`.
- **Sources / headings:** [system-architecture-scaling-runbook.md](../../architecture/system-architecture-scaling-runbook.md) `§ Starting Guardrails (Baseline Sizing)` and `§ Capacity Model (Required Inputs)`; [system-architecture-testing.md](../../architecture/system-architecture-testing.md) `§ High-Concurrency Load Testing`.
- **Strongest alternative:** Treat the starting numbers as fixed production limits or scale without a measured capacity model.
- **ADR recommendation:** No ADR for the provisional numbers. Yes when promoting them to hard limits, autoscaling thresholds, or release gates.
- **Human consultation:** Yes; operations and product owners must define target profiles and acceptable degradation.

#### `CAPACITY-02` - Unified retention policy for high-churn persistence

- **Capability:** Primary `SF-2.1` PostgreSQL ownership, schemas, migrations, and retention. Secondary `GR-1.4`, `PO-4.2`, and `PO-4.3`.
- **Decision / status / importance:** High-churn PostgreSQL tables, tick history, and command-status records need one explicit retention, partitioning, and garbage-collection policy. Command status must outlive the retry window; retention cannot be chosen independently per table without considering replay, reconciliation, and operational query cost. Status `proposed/deferred`; `H/med`.
- **Sources / headings:** [system-architecture-scaling-runbook.md](../../architecture/system-architecture-scaling-runbook.md) `§ Scaling PostgreSQL`, `§ Tick- and Redis-Aware Scaling Indicators`, and `§ Capacity Model (Required Inputs)`; [system-architecture-testing.md](../../architecture/system-architecture-testing.md) `§ Cross-Service Integration Testing`.
- **Strongest alternative:** Let each service choose table retention independently or retain all history indefinitely.
- **ADR recommendation:** Yes. Define the retention classes, partition/GC ownership, command-status minimum, and replay/audit exceptions.
- **Human consultation:** Yes; database, runtime, compliance, and operations owners must balance recovery evidence against storage cost.

#### `HEALTH-01` - Liveness is local; readiness protects admission

- **Capability:** Primary `PO-4.2`. Secondary `PO-2.2`, `GR-1.1`, and `PO-3.2`.
- **Decision / status / importance:** Liveness checks answer whether the process is alive and remain local. Readiness is dependency-aware and must remove a path from player admission when required dependencies or startup contracts are not safe; process health alone is not readiness. Status `accepted-implicit`; `H/med`.
- **Sources / headings:** [deployment-environments.md](../../architecture/infrastructure/deployment-environments.md) `§ Docker Health Checks`, `§ Kubernetes Health Monitoring`, `§ Kubernetes Auto Recovery`, and `§ Monitoring & Logging`; [system-architecture-deploy-preflight-policy.md](../../architecture/system-architecture-deploy-preflight-policy.md) `§ Enforcement Boundaries`.
- **Strongest alternative:** Use process-only liveness as readiness and let traffic reach instances whose dependencies are unavailable.
- **ADR recommendation:** Yes. Define dependency classes, readiness failure behavior, and the relationship between readiness and edge admission.
- **Human consultation:** Yes; operations and service owners must set the dependency and degraded-operation boundary.

#### `TEST-01` - Test Redis is not production durability proof

- **Capability:** Primary `PO-4.3` Unit, integration, contract, static, and load verification. Secondary `SF-2.2` and `PO-4.4`.
- **Decision / status / importance:** Test Redis is ephemeral, does not run production AOF durability, and cannot prove the production tail-loss or recovery contract. Durable behavior must be proved through PostgreSQL-backed ledgers and targeted production-like evidence; tests must not flush shared Redis. Status `accepted-implicit`; `H/med`.
- **Sources / headings:** [system-architecture-testing.md](../../architecture/system-architecture-testing.md) `§ Redis in Tests`, `§ Cross-Service Integration Testing`, and `§ Observability Tests`; [system-architecture-scaling-runbook.md](../../architecture/system-architecture-scaling-runbook.md) `§ Tick- and Redis-Aware Scaling Indicators`.
- **Strongest alternative:** Treat integration tests against ephemeral Redis as proof of AOF persistence, tail-loss bounds, and restore behavior.
- **ADR recommendation:** Yes if the proof boundary is not already captured by `REDIS-01` and `TICK-03`; otherwise cross-reference those keys and keep this as verification evidence.
- **Human consultation:** Yes; operations and reliability owners must accept the production-like evidence requirement.

#### `TEST-02` - Two-tier verification with retained hobby evidence

- **Capability:** Primary `PO-4.4`. Secondary `PO-4.3`, `PO-3.4`, and `PO-3.1`.
- **Decision / status / importance:** Separate fast static/contract checks in PR and main CI from backend-dependent smoke, canary, and recovery evidence in staging or prod-like environments. Hobby operators may satisfy the external proof through an explicitly retained operator-run evidence record; an in-repo check alone is not equivalent. Status `proposed/deferred`; `H/med`.
- **Sources / headings:** [system-architecture-testing.md](../../architecture/system-architecture-testing.md) `§ CI/CD Integration`, `§ Observability Tests`, `§ Synthetic Player-Flow Canary Checks`, and `§ Where These Checks Run (Decision)`; [system-architecture-deploy-preflight-policy.md](../../architecture/system-architecture-deploy-preflight-policy.md) `§ Evidence Contract` and `§ Evidence Storage and Retention`; [deployment-environments.md](../../architecture/infrastructure/deployment-environments.md) `§ PR Preview Environment` and `§ Staging Environment for Playtesting`.
- **Strongest alternative:** Put every check in pull-request CI, or allow a green static report to stand in for external smoke and recovery proof.
- **ADR recommendation:** Yes. Define which evidence is required at PR, promotion, traffic-open, and hobby self-hosting boundaries.
- **Human consultation:** Yes; release and hobby-support owners must approve the exception and its retention standard.

#### `TEST-03` - Full high-concurrency load tests are non-blocking unless promoted

- **Capability:** Primary `PO-4.3`. Secondary `PO-3.1` and `PO-4.2`.
- **Decision / status / importance:** Full high-concurrency load tests run on demand or in a dedicated environment and are not a default blocking CI step. A smoke load check cannot substitute for full load proof; release blocking begins only when an explicit capacity or SLO policy promotes the test. Status `accepted-implicit`; `H/med`.
- **Sources / headings:** [system-architecture-testing.md](../../architecture/system-architecture-testing.md) `§ High-Concurrency Load Testing`, `§ CI/CD Integration`, and `§ Where These Checks Run (Decision)`; [system-architecture-scaling-runbook.md](../../architecture/system-architecture-scaling-runbook.md) `§ Capacity Model (Required Inputs)`.
- **Strongest alternative:** Block every promotion on a full load test or omit load testing and rely on unit/integration checks.
- **ADR recommendation:** Yes before a load result becomes a release gate; otherwise cross-reference `CAPACITY-01`.
- **Human consultation:** Yes; operations and product owners must choose the cost, cadence, and gate threshold.

### Creator, Player, And Governance Journeys

#### `LIFE-01` - Tenant-owned routine lifecycle with platform break-glass

- **Capability:** Primary `AR-3.1` Runtime instance launch, lifecycle, and termination. Secondary `AA-1.5`, `PO-1.1`, and `AR-3.3`.
- **Decision / status / importance:** `tenantAdmin` owns routine game-instance launch, fork, patch pinning, cutover, and rollback within billing and entitlement controls. `platformAdmin` is reserved for break-glass intervention; a creator cannot bypass paid or safety gates merely by owning content. Status `needs-human-review`; `H/hard`.
- **Sources / headings:** [user-journeys-creators.md](../../architecture/user-journeys-creators.md) `§ 1. Game Creation`, `§ 4. Publish and Start a Game Instance`, `§ 5. Patch and Update a Live Game`, and `§ 7. Playtesting & Analytics`; [user-journeys-operators.md](../../architecture/user-journeys-operators.md) `§ 2. Operator Recovery Journeys` and `§ 4. Deployment & Environment Configuration`.
- **Strongest alternative:** Make platform operators own every launch and cutover, or let tenant admins launch without billing, entitlement, or safety enforcement.
- **ADR recommendation:** Yes. Align lifecycle authority, paid-state behavior, and break-glass auditability with `AUTH-07`, `ADMIT-01`, and `OPS-05`.
- **Human consultation:** Yes; product, finance, platform, and creator owners must approve the authority split.

#### `PLAYTEST-01` - Explicit, expiring playtest grants with forward-looking revocation

- **Capability:** Primary `AR-3.4` Playtest forks, reset, expiry, and isolation. Secondary `AA-3.2`, `AA-2.2`, `PO-1.2`, and `EA-3.3`.
- **Decision / status / importance:** A playtest fork requires an explicit grant, may carry `expiresAt`, and has isolated state with no merge-back. Revocation stops future visibility and admission; existing sessions may drain rather than being implicitly ejected, unless a separate safety or incident action says otherwise. Status `needs-human-review`; `H/med`.
- **Sources / headings:** [user-journeys-creators.md](../../architecture/user-journeys-creators.md) `§ 4. Publish and Start a Game Instance` and `§ 7. Playtesting & Analytics`; [user-journeys-operators.md](../../architecture/user-journeys-operators.md) `§ 2. Operator Recovery Journeys`; [deployment-environments.md](../../architecture/infrastructure/deployment-environments.md) `§ Staging Environment for Playtesting`.
- **Strongest alternative:** Use implicit membership, eject all current sessions on revocation, allow indefinite grants, or merge playtest state back into production.
- **ADR recommendation:** Yes. Define grant ownership, expiry, revocation timing, drain behavior, and the exception for safety enforcement alongside `TENANT-03`.
- **Human consultation:** Yes; product, moderation, creator, and player-support owners must decide the session and data semantics.

#### `EQUIP-01` - Equipment vocabulary is game-authored and runtime-validated

- **Capability:** Primary `GR-3.3` Equipment, body layouts, slots, and loadouts. Secondary `AR-1.1` and `GR-3.1`.
- **Decision / status / importance:** Equipment slots and body layouts are authored per game and carried by published content; the runtime validates against the resolved game definitions rather than a platform-global enum. Status `accepted-implicit`; `M/med`.
- **Sources / headings:** [user-journeys-creators.md](../../architecture/user-journeys-creators.md) `§ 2. World and Entity Design` and `§ 6. Branding and Customization`; [user-journeys-players.md](../../architecture/user-journeys-players.md) `§ 3. Character Creation & Selection`.
- **Strongest alternative:** Define one platform-global slot vocabulary and reject game-specific body layouts.
- **ADR recommendation:** Yes only if the slot vocabulary crosses a shared persistence or protocol boundary; otherwise cross-reference the content-schema decision in `CONTENT-01` and `CONTENT-03`.
- **Human consultation:** Yes; game designers and client owners must agree on the extensibility boundary.

#### `PLAYER-01` - Character selection is explicit and realm-local

- **Capability:** Primary `AA-2.1` Gameplay login, character selection, and session binding. Secondary `AA-3.2`, `GR-3.1`, and `EA-3.1`.
- **Decision / status / importance:** Character rosters and selection are scoped to the selected game realm; an account has no implicit default character across realms. Login requires explicit character selection or an explicit creation path, and character identity/state remains isolated per realm. Status `needs-human-review`; `H/hard`.
- **Sources / headings:** [user-journeys-players.md](../../architecture/user-journeys-players.md) `§ 2. Join a Game for the First Time`, `§ 3. Character Creation & Selection`, `§ 4. Player Login and Gameplay`, and `§ 8. Switch Games or Manage Multiple Games`; [system-architecture-frontend.md](../../architecture/system-architecture-frontend.md) `§ Authentication and Session Handling`.
- **Strongest alternative:** Maintain a tenant-wide roster, choose an account-wide default character, or auto-create/select a character on first entry.
- **ADR recommendation:** Yes. Define character-creation descriptors, missing-descriptor behavior, realm-local data, and the session-binding contract with `AUTH-01` and `SESSION-02`.
- **Human consultation:** Yes; product, game-design, and player-support owners must approve the creation and selection experience.

#### `SAFETY-01` - Moderation outcomes are scoped by enforcement category and owner

- **Capability:** Primary `EA-2.4` Player blocking, reporting, and safety controls. Secondary `PO-1.2`, `AA-1.3`, and `PO-1.3`.
- **Decision / status / importance:** Distinguish `account_security_ban`, `gameplay_ban`, `chat_mute`, and `chat_ban`; route the decision through Logging/Admin while the owning service enforces the resulting scope. Do not collapse all moderation into a generic account ban. Status `accepted-implicit`; `H/hard`.
- **Sources / headings:** [user-journeys-players.md](../../architecture/user-journeys-players.md) `§ 5. Social Interaction & Safety`; [user-journeys-operators.md](../../architecture/user-journeys-operators.md) `§ 1. Monitoring and Moderation`; [system-architecture-player-experience-incident-runbook.md](../../architecture/system-architecture-player-experience-incident-runbook.md) `§ Incident Types`.
- **Strongest alternative:** Use one generic ban state or let each enforcement service invent incompatible moderation outcomes.
- **ADR recommendation:** Yes. Define category scope, precedence, appeal/audit records, and enforcement ownership alongside existing `SEC-04` and the moderation policy boundary.
- **Human consultation:** Yes; trust-and-safety, legal, support, and game operations owners must approve policy meaning and appeals.

#### `COMMERCE-01` - Stripe-only payments with explicit fee and entitlement reversal semantics

- **Capability:** Primary `AA-1.4` Commerce, subscriptions, purchases, donations, and platform fees. Secondary `AA-1.5`, `PO-1.3`, and `EA-3.3`.
- **Decision / status / importance:** Use Stripe as the payment provider in the journey, disallow external payment methods for the product path, apply the stated platform fee, and model refunds as entitlement revocations while keeping billing-safe management reachable when gameplay is unavailable. Status `needs-human-review`; `H/hard`.
- **Sources / headings:** [user-journeys-players.md](../../architecture/user-journeys-players.md) `§ 6. Purchases and Subscriptions`; [user-journeys-creators.md](../../architecture/user-journeys-creators.md) `§ 1. Game Creation` and `§ 4. Publish and Start a Game Instance`; [user-journeys-operators.md](../../architecture/user-journeys-operators.md) `§ 1. Monitoring and Moderation`.
- **Strongest alternative:** Support multiple providers or external payment links, use no platform fee, or make purchased entitlements irreversible after refund.
- **ADR recommendation:** Yes. Record provider ownership, fee policy, entitlement state transitions, refund/revocation behavior, and billing-safe availability.
- **Human consultation:** Yes; finance, legal, product, creator, and support owners must approve this commercial boundary.

#### `DATA-01` - Export and deletion are account-governed and subscription-aware

- **Capability:** Primary `AA-1.3` Authentication, recovery, security policy, and account data rights. Secondary `PO-1.3` and `AA-2.3`.
- **Decision / status / importance:** Export covers account-owned data across its tenant/game relationships as JSON, with tenant-admin-scoped export where applicable. Deletion is blocked while a subscription is non-terminal until it is transferred or canceled, then revokes sessions and records the required audit evidence. Status `needs-human-review`; `H/hard`.
- **Sources / headings:** [user-journeys-players.md](../../architecture/user-journeys-players.md) `§ 9. Account Data Export & Deletion` and `§ 7. Password Resets & Account Recovery`; [user-journeys-operators.md](../../architecture/user-journeys-operators.md) `§ 2. Operator Recovery Journeys`.
- **Strongest alternative:** Export only a central account record and delete immediately regardless of subscription or tenant ownership.
- **ADR recommendation:** Yes. Define data ownership, tenant-admin authority, export completeness, subscription transfer/cancel preconditions, and audit retention.
- **Human consultation:** Yes; legal/privacy, finance, product, and tenant-administration owners must approve the boundary.

#### `SOCIAL-01` - Communication is room-local until broader scopes are designed

- **Capability:** Primary `EA-2.1` Chat, private communication, and mail. Secondary `AR-2.2`, `EA-1.2`, and `PO-1.2`.
- **Decision / status / importance:** The current player communication scope is room-local `SAY`, targeted `WHISPER`, and targeted `TELL`; cross-room, global, or mail-like audience scopes are deferred. Moderation and capability policy must not infer broader reach from the current commands. Status `proposed/deferred`; `M/med`.
- **Sources / headings:** [user-journeys-players.md](../../architecture/user-journeys-players.md) `§ 5. Social Interaction & Safety`; [system-architecture-mud-client-protocol.md](../../architecture/system-architecture-mud-client-protocol.md) `§ Interaction with abuse heuristics` and `§ MCP resource limits & abuse budgets`; [platform-settings-reference.md](../../architecture/generated/platform-settings-reference.md) `§ firemud.communication` and `§ firemud.command-capabilities`.
- **Strongest alternative:** Add global, cross-room, or mail scopes immediately and make them part of the baseline client contract.
- **ADR recommendation:** Yes before expanding audience scope; define delivery, retention, moderation, capability, and privacy semantics first.
- **Human consultation:** Yes; product, trust-and-safety, and player-support owners must choose the future audience model.

#### `ACCOUNT-01` - External identity providers are a product promise, not an implementation detail

- **Capability:** Primary `AA-1.3`. Secondary `EA-3.1`.
- **Decision / status / importance:** The player journey names Google, Discord, and Steam as external sign-up providers. Treat that list as an explicit product commitment requiring provider-linking, account-recovery, identity-collision, and availability policy; do not silently implement it as a generic OAuth placeholder. Status `needs-human-review`; `M/med`.
- **Sources / headings:** [user-journeys-players.md](../../architecture/user-journeys-players.md) `§ 1. Sign Up` and `§ 7. Password Resets & Account Recovery`; [system-architecture-frontend.md](../../architecture/system-architecture-frontend.md) `§ Authentication and Session Handling`.
- **Strongest alternative:** Support only password/email authentication, or promise provider-neutral federation without naming supported providers.
- **ADR recommendation:** Yes before implementation or public documentation; define provider scope, account linking, recovery fallback, and provider outage behavior.
- **Human consultation:** Yes; product, security, legal, and support owners must approve the provider promise.

#### `COMPLIANCE-01` - Tier A credential evidence is a measurable release and recovery gate

- **Capability:** Primary `PO-3.2` Environment, configuration, secret, certificate, and service-discovery delivery. Secondary `PO-1.3`, `PO-3.1`, `PO-4.4`, and `SF-1.3`.
- **Decision / status / importance:** Tier A secrets and certificates require age/provisioning or rotation evidence, alerting, emergency handling, and an immutable versioned compliance record with exactly one freshness timestamp. Production blocks on noncompliance; staging hard enforcement took effect on July 1, 2026, so non-compliant records block staging promotion/deployment evidence and any staging deployment intended to serve as production-promotion evidence, while detached or quarantined non-promotion drills remain outside that gate; hobby environments validate before traffic. Status `proposed/deferred` because the policy is explicit but implementation is partial; `H/hard`.
- **Sources / headings:** [environment-and-secrets-overview.md](../../architecture/infrastructure/environment-and-secrets-overview.md) `§ Secret Governance Tiers`, `§ Secret Compliance Controls`, `§ Player-Facing Environment Bootstrap Requirements`, and `§ Certificate Management & Watchers`; [environment-and-secrets-catalog.md](../../architecture/infrastructure/environment-and-secrets-catalog.md) `§ TLS & Certificates`, `§ Authentication & JWT`, and `§ Additional Notes`; [schedule.md](../../architecture/infrastructure/schedule.md) `§ Kubernetes Cluster (Production)`, `§ Kubernetes Cluster (Staging)`, and `§ Hobby / Self-Hosted Environments`.
- **Strongest alternative:** Keep credential rotation as policy text without immutable evidence, age enforcement, or environment-specific traffic and promotion consequences, or make an external secret manager mandatory now.
- **ADR recommendation:** Yes. Establish the compliance record schema, timestamp authority, enforcement dates, exception/waiver rules, and relationship to `SEC-01`, `SEC-02`, `OPS-04`, and `PREFLIGHT-01`.
- **Human consultation:** Yes; security, operations, legal/compliance, and self-hosting owners must approve the gates and exception model.

#### `SESSION-08` - Session validity and reconnect lifetimes are separate

- **Capability:** Primary `AA-2.2` Reconnect, resume eligibility, and cross-device continuity. Secondary `GR-1.4`, `AR-2.1`, `EA-3.4`, and `SF-1.1`.
- **Decision / status / importance:** The private player-delegation token lifetime, healthy active gameplay, continuity-binding eligibility, disconnected resume, Redis cleanup, and transcript retention are separate. `continuityBindingExpiresAt` caps reuse of the old binding after transport loss but does not itself kick a continuously connected authorized player; resume uses the stricter remaining continuity lifetime and configured resume window. Explicit logout terminates resume authority and private replay. Status `accepted-explicit`; `H/hard`.
- **Sources / headings:** [ADR 0019](../../architecture/decisions/adr-0019-separate-active-session-resume-and-transcript-lifetimes.md); [environment-and-secrets-catalog.md](../../architecture/infrastructure/environment-and-secrets-catalog.md) `§ Authentication & JWT`; [system-architecture-reconnection.md](../../architecture/system-architecture-reconnection.md) `§ Requirements`; [system-architecture-redis.md](../../architecture/system-architecture-redis.md) `§ Session Keys and Gameplay Binding`; [system-architecture-session-behavior.md](../../architecture/system-architecture-session-behavior.md) `§ Session Types and Lifetimes`; `§ Gameplay Logout and Resume Transcript`.
- **Strongest alternative:** Make the derived anchor a hard active-session cutoff, use one lifetime for every concern, adopt a sliding binding, or disable resume.
- **ADR recommendation:** Revised and accepted in ADR 0019; current runtime still lacks immutable continuity/deadline enforcement and logout/transcript convergence proof.
- **Human consultation:** Completed through the human-led adversarial review on 2026-07-18.

## Coverage By Exact Capability

The following crosswalk is generated from all 38 primary ledger rows and their declared secondary capabilities. `Primary` lists product/operations keys whose primary capability is the exact taxonomy ID; `Secondary-only` lists IDs present only as secondary handoffs in this ledger; `No primary` lists taxonomy IDs with neither a primary nor a secondary row here. It is a coverage statement, not a claim that this partition owns the capability.

| Capability group | Primary | Secondary-only | No primary |
| --- | --- | --- | --- |
| Accounts and Access (`AA`) | `AA-1.3` DATA-01/ACCOUNT-01; `AA-1.4` COMMERCE-01; `AA-2.1` PLAYER-01; `AA-2.2` SESSION-08 | `AA-1.1` OBS-03; `AA-1.5` LIFE-01/COMMERCE-01; `AA-2.3` DATA-01; `AA-3.2` PLAYTEST-01/PLAYER-01 | `AA-1.2`, `AA-3.1`, `AA-3.3` |
| Experience and Applications (`EA`) | `EA-2.1` SOCIAL-01; `EA-2.4` SAFETY-01; `EA-3.1` FRONT-01 | `EA-1.2` MCP-01/SOCIAL-01; `EA-3.2` LLM-01; `EA-3.3` PLAYTEST-01/COMMERCE-01; `EA-3.4` FRONT-01/SESSION-08 | `EA-1.1`, `EA-1.3`, `EA-2.2`, `EA-2.3` |
| Gameplay Runtime (`GR`) | `GR-2.1` PROC-04; `GR-2.2` PROC-05; `GR-3.3` EQUIP-01 | `GR-1.1` CAPACITY-01/HEALTH-01; `GR-1.2` OBS-02; `GR-1.4` RECOVERY-01/RECOVERY-02/PROC-04/CAPACITY-02/SESSION-08; `GR-3.1` EQUIP-01/PLAYER-01 | `GR-1.3`, `GR-2.3`, `GR-3.2`, `GR-4.1`, `GR-4.2`, `GR-4.3`, `GR-4.4` |
| Authoring and Release (`AR`) | `AR-1.1` PROC-01/PROC-03; `AR-1.2` LLM-01; `AR-1.5` ASSET-01/PROC-02; `AR-3.1` LIFE-01; `AR-3.2` ASSET-02; `AR-3.4` PLAYTEST-01 | `AR-1.4` ASSET-01/ASSET-02; `AR-2.1` SESSION-08; `AR-2.2` SOCIAL-01; `AR-2.3` PROC-03; `AR-3.3` ASSET-01/LIFE-01 | `AR-1.3` |
| Automation and Scripting (`AS`) | None | `AS-1.2` LLM-01; `AS-1.3` PROC-01; `AS-1.5` PROC-04 | `AS-1.1`, `AS-1.4`, `AS-1.6` |
| Shared Runtime Foundations (`SF`) | `SF-2.1` CAPACITY-02 | `SF-1.1` PREFLIGHT-01/PROMO-01/OBS-01/SESSION-08; `SF-1.3` RECOVERY-02/PREFLIGHT-02/COMPLIANCE-01; `SF-2.2` CAPACITY-01/TEST-01; `SF-2.3` ASSET-01/ASSET-02/PROC-02/PROC-04 | `SF-1.2`, `SF-1.4`, `SF-1.5`, `SF-2.4` |
| Platform and Operations (`PO`) | `PO-2.3` MCP-01; `PO-3.1` PREFLIGHT-01/PROMO-01; `PO-3.2` PREFLIGHT-02/COMPLIANCE-01; `PO-3.4` RECOVERY-01/RECOVERY-02; `PO-4.1` OBS-01; `PO-4.2` OBS-02/OBS-04/CAPACITY-01/HEALTH-01; `PO-4.3` TEST-01/TEST-03; `PO-4.4` OBS-03/OBS-05/OBS-06/TEST-02 | `PO-1.1` OBS-04/LIFE-01; `PO-1.2` PLAYTEST-01/SAFETY-01/SOCIAL-01; `PO-1.3` SAFETY-01/COMMERCE-01/DATA-01/COMPLIANCE-01; `PO-1.4` OBS-04; `PO-2.1` RECOVERY-02/OBS-03/OBS-05; `PO-2.2` FRONT-01/MCP-01/HEALTH-01; `PO-2.4` OBS-02/MCP-01; `PO-3.3` ASSET-01/ASSET-02 | None |

## Existing-Key Evidence Crosswalk

The assigned sources also provide materially stronger or contradictory evidence for existing inventory keys. These entries are deliberately not duplicated as new decisions.

| Existing key(s) | Stronger or contradictory evidence | Assigned sources and headings | Treatment |
| --- | --- | --- | --- |
| `CONTENT-01`, `CONTENT-02`, `OPS-01` | Asset writes remain Game Design-owned; runtime/CDN/Gateway read resolved immutable bundles; hashes and exact staged digests are mandatory. | [system-architecture-asset-store-runbook.md](../../architecture/system-architecture-asset-store-runbook.md) `§ MinIO Deployment and Configuration`, `§ Handling Failed Publish Versions`; [system-architecture-frontend.md](../../architecture/system-architecture-frontend.md) `§ Game-Specific Customization`; [system-architecture-promotion-attestation.md](../../architecture/system-architecture-promotion-attestation.md) `§ Artifact Format`, `§ Validation Rules`. | Strengthens ownership, immutable bundle, and no-rebuild promotion rules; `ASSET-01`, `ASSET-02`, and `PROMO-01` capture only the additional boundaries. |
| `OPS-01`, `OPS-02`, `OPS-05`, `OPS-06` | Overlay commit/live-state evidence, exact digest lineage, preflight before apply, and rollback compatibility beyond a binary image rollback. | [system-architecture-deployment-runbook.md](../../architecture/system-architecture-deployment-runbook.md) `§ Overlay Deployment Flow (Staging and Production)`, `§ Canary or Phased Rollouts`, and `§ Rollback`; [system-architecture-deploy-preflight-policy.md](../../architecture/system-architecture-deploy-preflight-policy.md) `§ Authoritative Entrypoint`, `§ Evidence Contract`. | Cross-reference existing promotion and CI/CD keys; do not create a second rollout or rollback inventory. |
| `OPS-03`, `OPS-04` | **Current implementation** has a scheduled online `pg_dump` and selected evidence checks, but does not yet emit complete lineage or prove controller-governed recovery/reopen. **Accepted target** under ADR 0015 is an online environment-wide snapshot with player-facing `cold_start_restore`; the **durable recovery controller is the runtime authority**, while checked-in recovery and traffic-open records are immutable projections after finalization. | [ADR 0015](../../architecture/decisions/adr-0015-online-backup-and-environment-wide-cold-start-recovery.md); [system-architecture-backup-recovery-evidence-and-compliance.md](../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md) `§ Production Backup Readiness Evidence`, `§ Production Recovery Compatibility Result`, `§ Production Traffic-Open Backup Evidence`, `§ Canonical Recovery Record`; [system-architecture-deployment-runbook.md](../../architecture/system-architecture-deployment-runbook.md) `§ Fresh-Boundary Restore Bootstrap`, `§ Recovery Proof Cadence and Release Reuse`. | The accepted target and runtime authority are explicit; artifact lineage, quarantine, convergence, reconciliation, controller gating, and executable gate validation remain implementation/proof debt. |
| `AUTH-02`, `AUTH-06`, `AUTH-07`, `ADMIT-01`, `SESSION-03`, `SESSION-07`, `EDGE-04`, `SESSION-09` | Target browser bootstrap uses an HttpOnly connect-token cookie, explicit open-enrollment `Join & Play`, realm-scoped `CHARS`, single-use connect-token carriage, WebSocket establishment, bare `LOGIN`, and then `PLAY`; the durable membership powers return discovery while billing-safe management remains reachable. Text `PLAY` now returns `JOIN_REQUIRED` when public-production membership is absent, but connect-token issuance may still create membership implicitly and explicit `JOIN` is not implemented. The target runtime membership response also carries an opaque Account-owned `membershipAuthorityGeneration`; the live proto/implementation has not exposed that field yet. | [system-architecture-frontend.md](../../architecture/system-architecture-frontend.md) `§ Authentication and Session Handling`, `§ API Usage Patterns`; [user-journeys-players.md](../../architecture/user-journeys-players.md) `§ 2. Join a Game for the First Time`, `§ 4. Player Login and Gameplay`, `§ 6. Purchases and Subscriptions`; [user-journeys-creators.md](../../architecture/user-journeys-creators.md) `§ 4. Publish and Start a Game Instance`; [player-access-and-session.md](../implementation-tracking/player-access-and-session.md); [account runtime/data](../../architecture/microservices/account-service/runtime-and-data.md) `§ Membership and Entitlement Authority` | Target-state product-journey evidence plus explicit current implementation gaps. `PLAYER-01` and `SESSION-08` capture the additional character and lifetime boundaries; cross-cutting `EDGE-04` and `SESSION-09` carry the distinct carrier/replay and refresh/logout contracts. |
| `SEC-01`, `SEC-02`, `SEC-03`, `EDGE-03` | File-mounted JWT/JWKS and mTLS material, cert-manager/operator certificate controls, hard-cutover compromise response, and public Telnet/PROXY trust handling are reinforced. | [system-architecture-jwt-compromise-runbook.md](../../architecture/system-architecture-jwt-compromise-runbook.md) `§ Required Response Flow`, `§ Mandatory Evidence Checklist`; [system-architecture-operator-credentials-runbook.md](../../architecture/system-architecture-operator-credentials-runbook.md) `§ Storage and Distribution`, `§ Rotation`, `§ Revocation / Incident Response`; [system-architecture-protocol-bridging.md](../../architecture/system-architecture-protocol-bridging.md) `§ Telnet TLS modes and PROXY protocol`, `§ Protocol handling and security`; [post-restore-hardening.md](../../architecture/system-architecture-post-restore-hardening.md) `§ Post-Restore Secret Hardening`. | Stronger runbook evidence; `COMPLIANCE-01` captures the measurable evidence/enforcement addition. |
| `REDIS-01`, `REDIS-02`, `TICK-03`, `TICK-06`, `TICK-16`, `TICK-17`, `SESSION-06`, `RECON-01` | Scale changes require freeze/fence/epoch/rebuild/reconcile/resume; separate Coord and Cache Redis roles are required; edge delivery and runtime effects retain the existing delivery guarantees. | [system-architecture-scaling-runbook.md](../../architecture/system-architecture-scaling-runbook.md) `§ Pre-Scale Topology Decision Gate (Required)`, `§ Scaling Redis`, `§ Tick- and Redis-Aware Scaling Indicators`; [environment-and-secrets-catalog.md](../../architecture/infrastructure/environment-and-secrets-catalog.md) `§ Redis Coordination & Cache`; [system-architecture-protocol-bridging.md](../../architecture/system-architecture-protocol-bridging.md) `§ Ordering & Delivery Invariants`. | Strengthens existing runtime/Redis keys; `CAPACITY-01` and `CAPACITY-02` capture only calibration and retention choices, while cross-cutting `TICK-16`, `TICK-17`, and `RECON-01` carry terminal, isolation, and backlog ownership contracts. |
| `TICK-01`, `TICK-02`, `TICK-04`, `TICK-05`, `AUTO-01`, `AUTO-02`, `CMD-02` | Procedural generation uses stable idempotency, typed effects, single-writer scope, `EffectId`/`RoomInstanceRef`, and convergence rather than arbitrary mutation. | [system-architecture-procedural-generation.md](../../architecture/system-architecture-procedural-generation.md) `§ Generation Pipeline:`, `§ Integration Guidelines`, `§ Service Responsibilities`; [system-architecture-testing.md](../../architecture/system-architecture-testing.md) `§ Cross-Service Integration Testing`. | Cross-reference existing runtime automation keys; `PROC-01` through `PROC-04` capture the authoring-generation boundary and provenance, not new tick semantics. |
| `CMD-04`, `CMD-05`, `CMD-06`, `SET-01`, `SET-02` | Generated values make presentation defaults, reconnect buffer limits, command-history bounds, capability defaults, and topology scope concrete; frontend uses memory-only admin JWT state and no browser token storage/query parameters. | [platform-settings-reference.md](../../architecture/generated/platform-settings-reference.md) `§ firemud.presentation`, `§ firemud.reconnection`, `§ firemud.command-history`, `§ firemud.command-capabilities`, `§ firemud.world-topology`; [system-architecture-frontend.md](../../architecture/system-architecture-frontend.md) `§ Authentication and Session Handling`, `§ State Management`. | Treat generated settings as evidence, not an independent authority. The former `SESSION-08` conflict is resolved; TanStack Query/local state versus Redux remains a reversible implementation no-decision. |
| `TENANT-03`, `OPS-05` | Creator and operator journeys reinforce isolated playtest forks, explicit grants, tenant-admin routine lifecycle, and platform-admin break-glass. | [user-journeys-creators.md](../../architecture/user-journeys-creators.md) `§ 4. Publish and Start a Game Instance`, `§ 7. Playtesting & Analytics`; [user-journeys-operators.md](../../architecture/user-journeys-operators.md) `§ 2. Operator Recovery Journeys`, `§ 4. Deployment & Environment Configuration`. | `PLAYTEST-01` and `LIFE-01` capture grant/revocation and authority choices; no duplicate tenancy key. |
| `OPS-02`, `OPS-04` | Player SLOs, canary flows, external deadman detection, alert fallback authority, and indexed-log queryability define stronger proof and degraded-mode behavior. | [system-architecture-logging-monitoring.md](../../architecture/system-architecture-logging-monitoring.md) `§ Player Experience SLIs and SLOs (Target-State Contract)`, `§ Synthetic Player-Flow Canaries (Target-State Prod-Like Contract)`, `§ External Probe and Deadman Contract (Normative)`, `§ Log Pipeline Queryability Contract`; [system-architecture-observability-incident-runbook.md](../../architecture/system-architecture-observability-incident-runbook.md) `§ Independent Detection Contract`, `§ Deadman Freshness Contract`; [system-architecture-player-experience-incident-runbook.md](../../architecture/system-architecture-player-experience-incident-runbook.md) `§ Incident Types`. | `OBS-01` through `OBS-06` capture the added observability decisions; existing operational keys remain the cross-cutting authority. |
| `SEC-04`, `MOD-01` | Moderation actions have a category taxonomy, Logging/Admin control-plane routing, owning-service enforcement, and audit/appeal implications. | [user-journeys-players.md](../../architecture/user-journeys-players.md) `§ 5. Social Interaction & Safety`; [user-journeys-operators.md](../../architecture/user-journeys-operators.md) `§ 1. Monitoring and Moderation`. | `SAFETY-01` records the additional policy taxonomy; cross-cutting `MOD-01` carries versioned propagation, bounded cache invalidation, and fail-safe enforcement rather than a duplicate policy key. |

## Conflicts And Open Boundaries

`SESSION-08` was the only direct contradiction found in the assigned sources. It is now resolved: healthy uninterrupted play is independent of private player-delegation token lifetime, the three-minute setting is the disconnected-resume admission cap under the remaining continuity-binding lifetime, stale bindings follow the configured fresh-entry fallback, and transcript retention is independent.

The following are stronger evidence or implementation gaps, not additional contradictions:

- ADR 0015 resolves the recovery target; routine backup does not depend on region pause. Player-facing restore remains an implementation/proof gap until environment-wide cold-start quarantine, convergence, hardening, and controlled reopen are executable and proved.
- The settings model has a target precedence contract while the environment catalog describes current operator-scoped values; use `SET-01` rather than create a duplicate setting key.
- JWT rotation and compromise response are normatively specified, but the environment catalog and cross-cutting inventory still record incomplete implementation; use `SEC-02` and `COMPLIANCE-01`.
- `regions-enabled=false` in generated settings is a world-topology default, not evidence that tick-region authority or topology-change recovery can be removed; resolve against `TICK-01` and `TICK-06` if the setting is expanded.
- LLM provider retention, PII handling, external tool trust, and human-review SLA are not specified. They remain consultation questions under `LLM-01`, not invented decisions.

## Explicit No-Decision Classifications

The following assigned sources were considered but do not create a new consequential key:

- [product-capability-taxonomy.md](../../architecture/product-capability-taxonomy.md) `§ Purpose`, `§ Taxonomy Contract`, `§ Boundary Rules`, and `§ Product Coverage Basis` defines allocation vocabulary and ownership boundaries; it does not select a new product or operational behavior.
- [repository-structure.md](../../architecture/repository-structure.md) `§ Directory summary`, `§ Related Documentation`, and `§ Local workspace examples` is a repository map, not an architecture choice.
- [system-architecture-diagram.md](../../architecture/system-architecture-diagram.md) `§ Core Services Shown`, `§ Datastore Layer`, `§ Observability Components`, `§ Asynchronous Flows`, and `§ Related Documentation` and [system-context-diagram.md](../../architecture/system-context-diagram.md) `§ Related Documentation` are explanatory diagrams; the system-context document has no `Context` section heading, and their repeated boundaries strengthen existing keys but are not independent decisions.
- [system-architecture-runbooks.md](../../architecture/system-architecture-runbooks.md) `§ Deployment`, `§ Scaling`, `§ Operator Access`, `§ Recovery`, `§ Player Experience Incidents`, `§ Observability Stack Incidents`, `§ Asset Store`, `§ Hotfix Procedure`, and `§ Telnet Path Degraded or Failing` and [user-journeys.md](../../architecture/user-journeys.md) `§ Players`, `§ Creators`, and `§ Operators` are indexes/hubs.
- [infrastructure/README.md](../../architecture/infrastructure/README.md) `§ Core Infrastructure Docs`, `§ Network Boundary and Certificates`, `§ Multi-Tenant Deployment`, `§ Logging Stack`, `§ Usage`, and `§ Related Documentation` and [environment-and-secrets.md](../../architecture/infrastructure/environment-and-secrets.md) `§ Overview`, `§ Operator & Architecture Overview`, `§ Operator Quick Reference`, and `§ Environment Variable Catalog` are navigation/stub documents.
- [system-architecture-deployment-runbook.md](../../architecture/system-architecture-deployment-runbook.md) `§ Canary or Phased Rollouts` does not add a default canary policy; it explicitly leaves the existing `OPS-02` choice in place. Its remaining deployment details strengthen `OPS-01`, `OPS-02`, `OPS-04`, `OPS-05`, `OPS-06`, `RECOVERY-01`, and `PREFLIGHT-01`.
- [system-architecture-jwt-compromise-runbook.md](../../architecture/system-architecture-jwt-compromise-runbook.md) `§ Trigger Conditions`, `§ Required Response Flow`, `§ Mandatory Evidence Checklist`, and `§ Environment Notes` operationalizes `SEC-02`; it does not create a second JWT rotation decision.
- [system-architecture-observability-incident-runbook.md](../../architecture/system-architecture-observability-incident-runbook.md) `§ Common Fallbacks (When Dashboards Are Unavailable)`, `§ Post-Incident Checklist`, and `§ Fallback Query Cheat Sheet` operationalizes `OBS-02` through `OBS-05` and existing operations keys.
- [system-architecture-operator-credentials-runbook.md](../../architecture/system-architecture-operator-credentials-runbook.md) `§ Issuance`, `§ Storage and Distribution`, `§ Rotation`, and `§ Revocation / Incident Response` operationalizes `SEC-01` and `PREFLIGHT-02`.
- [system-architecture-player-experience-incident-runbook.md](../../architecture/system-architecture-player-experience-incident-runbook.md) `§ Login Success Ratio Below SLO`, `§ Command Latency Above SLO`, `§ Chat Delivery Latency Above SLO`, and `§ Telnet and WebSocket Path Availability Below SLO` operationalizes `OBS-02` and `OBS-03`; it does not choose new SLOs.
- [system-architecture-protocol-bridging.md](../../architecture/system-architecture-protocol-bridging.md) `§ WebSocket Client Flow (Modern Clients)`, `§ Gameplay WebSocket route contract (normative)`, `§ Telnet / TCP Client Flow (Legacy Clients)`, `§ Ordering & Delivery Invariants`, `§ Gameplay Command Idempotency (Client View)`, `§ Telnet Disconnect Reasons`, `§ Backpressure & Slow Clients`, `§ Global Load Shedding Strategy`, `§ Telnet TLS modes and PROXY protocol`, `§ Buffering, reconnection, and observability`, and `§ Outbound Recovery Boundary` strengthens `EDGE-01`, `EDGE-03`, `SESSION-03`, `SESSION-05`, `SESSION-06`, `SEC-03`, and `PO-2.4`.
- [system-architecture-telnet-degraded-runbook.md](../../architecture/system-architecture-telnet-degraded-runbook.md) `§ Triage`, `§ Remediation`, `§ Buffer, Slow-Client, and WebSocket-Specific Considerations`, and `§ Stalled Backend and Partial-Disconnect Symptoms` operationalizes `SEC-03`, `SESSION-05`, `SESSION-06`, and `PO-2.4`.
- [schedule.md](../../architecture/infrastructure/schedule.md) `§ GitHub CI`, `§ Kubernetes Cluster (Production)`, `§ Kubernetes Cluster (Staging)`, and `§ Hobby / Self-Hosted Environments` supplies cadence evidence for `OPS-03` and `RECOVERY-01`; the schedules do not independently choose a new retention or restore policy.
- [platform-settings-reference.md](../../architecture/generated/platform-settings-reference.md) is generated from metadata and explicitly says not to edit it manually. Its values are evidence for `SET-01`, `SET-02`, `CMD-04`, `CMD-05`, `CMD-06`, `SOCIAL-01`, and the resolved `SESSION-08` lifetime distinction; it has no independent design authority.
- The frontend choice of TanStack Query plus local state instead of Redux in [system-architecture-frontend.md](../../architecture/system-architecture-frontend.md) `§ State Management`, and the `react-i18next` choice in `§ Internationalization Strategy`, are reversible implementation choices unless later elevated into a cross-client compatibility contract.
- The MCP per-connection budgets and malformed/unknown-package handling in [system-architecture-mud-client-protocol.md](../../architecture/system-architecture-mud-client-protocol.md) `§ MCP resource limits & abuse budgets` reinforce `PO-2.4` and `MCP-01`; the numeric defaults alone are not separate decisions.

## Assigned Path Coverage

Every remaining allocation path was read and classified below. The result column identifies a new key, an existing-key cross-reference, or an explicit no-decision classification.

| # | Assigned path | Allocation classification | Headings considered | Result |
| ---: | --- | --- | --- | --- |
| 1 | [product-capability-taxonomy.md](../../architecture/product-capability-taxonomy.md) | Normative design | `§ Purpose`; `§ Taxonomy Contract`; `§ Capability Groups`; `§ Boundary Rules`; `§ Product Coverage Basis` | No new decision; allocation vocabulary and exact child IDs. |
| 2 | [repository-structure.md](../../architecture/repository-structure.md) | Reference | `§ Directory summary`; `§ Related Documentation`; `§ Local workspace examples` | No new decision; repository map. |
| 3 | [system-architecture-asset-store-runbook.md](../../architecture/system-architecture-asset-store-runbook.md) | Runbook | `§ Health Checks`; `§ Incident Handling`; `§ MinIO Deployment and Configuration`; `§ Handling Failed Publish Versions` | `ASSET-01`, `ASSET-02`; strengthens `CONTENT-01`, `CONTENT-02`, `OPS-01`. |
| 4 | [system-architecture-backup-recovery-evidence-and-compliance.md](../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md) | Normative design | `§ Implementation Notes`; `§ Backup Observability and Alerts`; `§ Production Backup Readiness Evidence`; `§ Production Traffic-Open Backup Evidence`; `§ Hobby Backup Compliance Evidence`; `§ Hobby Traffic-Open Evidence`; `§ Canonical Recovery Record`; `§ Naming Rule` | `RECOVERY-01`, `RECOVERY-02`; strengthens `OPS-03`, `OPS-04`. |
| 5 | [system-architecture-deploy-preflight-policy.md](../../architecture/system-architecture-deploy-preflight-policy.md) | Normative design | `§ Purpose`; `§ Implementation Notes`; `§ Bootstrap Contract`; `§ Authoritative Entrypoint`; `§ Enforcement Boundaries`; `§ Environment Applicability`; `§ Required Policy Checks`; `§ Canonical Expected-Binding Inputs`; `§ Evidence Contract`; `§ Evidence Storage and Retention`; `§ Failure Handling` | `PREFLIGHT-01`, `PREFLIGHT-02`; strengthens `OPS-01`, `OPS-05`, `OPS-06`. |
| 6 | [system-architecture-deployment-runbook.md](../../architecture/system-architecture-deployment-runbook.md) | Runbook | `§ Prerequisites`; `§ Implementation Notes`; `§ Environment Bootstrap (First Deployment Only)`; `§ Fresh-Boundary Restore Bootstrap`; `§ Production Traffic-Open Backup Gate`; `§ Recovery Proof Cadence and Release Reuse`; `§ Overlay Deployment Flow (Staging and Production)`; `§ Hobby Manifest/Chart Deployment Flow (Hobby / Self-Hosted)`; `§ Canary or Phased Rollouts`; `§ Rollback`; `§ Per-Environment Deployment & Rollback Summary` | Strengthens `PREFLIGHT-01`, `RECOVERY-01`, `OPS-01`, `OPS-02`, `OPS-04`, `OPS-05`, `OPS-06`; no additional rollout key. |
| 7 | [system-architecture-diagram.md](../../architecture/system-architecture-diagram.md) | Reference | `§ Core Services Shown`; `§ Datastore Layer`; `§ Observability Components`; `§ Asynchronous Flows`; `§ Related Documentation` | No new decision; diagram evidence for `SEC-04`, `REDIS-02`, `CONTENT-01`, and `SESSION-01`. |
| 8 | [system-architecture-frontend.md](../../architecture/system-architecture-frontend.md) | Normative design | `§ Implementation Notes`; `§ Component Hierarchy`; `§ State Management`; `§ Authentication and Session Handling`; `§ API Usage Patterns`; `§ Hosting Direction`; `§ Build Tooling`; `§ Game-Specific Customization`; `§ Internationalization Strategy`; `§ End-to-End Testing` | `FRONT-01`; strengthens `AUTH-02`, `SEC-01`, `SEC-02`, `SESSION-03`, `CONTENT-01`, `CMD-04`, `CMD-05`, `CMD-06`; routine state/i18n choices are no-decision. |
| 9 | [system-architecture-jwt-compromise-runbook.md](../../architecture/system-architecture-jwt-compromise-runbook.md) | Runbook | `§ Trigger Conditions`; `§ Required Response Flow`; `§ Mandatory Evidence Checklist`; `§ Environment Notes` | No new decision; strengthens `SEC-02` and `RECOVERY-02`. |
| 10 | [system-architecture-llm-content-tools.md](../../architecture/system-architecture-llm-content-tools.md) | Normative design | `§ Scope`; `§ Non-Goals`; `§ Integration Model`; `§ Phased Implementation`; `§ Agent Sandbox Model`; `§ Safety and Review` | `LLM-01`; strengthens `CONTENT-01`, `CONTENT-03`, and `CONTENT-04`. |
| 11 | [system-architecture-logging-monitoring.md](../../architecture/system-architecture-logging-monitoring.md) | Normative design | `§ Implementation Notes`; `§ Logging Pipeline`; `§ Log Service Identity Contract`; `§ Runtime Instance Identity Contract`; `§ Request-Path Logging Baseline and Bounded Exceptions`; `§ Runtime Identity Exposure`; `§ Metrics Cardinality Rule For Runtime Identity`; `§ Metrics & Tracing`; `§ Cardinality Guardrails for Metrics`; `§ Player Experience SLIs and SLOs (Target-State Contract)`; `§ Player Experience Metrics Catalog (Target-State Contract)`; `§ Synthetic Player-Flow Canaries (Target-State Prod-Like Contract)`; `§ Canary Alert Contract (Target-State Prod-Like Contract)`; `§ Degraded Modes and Observability Dependencies`; `§ Alert Taxonomy and Ownership`; `§ Owner Catalog (Normative)`; `§ Alert Fallback Recording Rules`; `§ Logging & Admin Alert-State Contract (Normative)`; `§ Observability Stack Alerts`; `§ External Probe and Deadman Contract (Normative)`; `§ Log Pipeline Queryability Contract`; `§ Health Checks`; `§ Error Tracking and Hotfixes`; `§ Related Documentation` | `OBS-01` through `OBS-06`; strengthens `PO-4.1`, `PO-4.2`, `PO-4.4`, `OPS-02`, and `OPS-04`. |
| 12 | [system-architecture-mud-client-protocol.md](../../architecture/system-architecture-mud-client-protocol.md) | Normative design | `§ Goals`; `§ MCP Basics`; `§ Overview`; `§ Protocol Handshake`; `§ Message Format`; `§ Optional Packages`; `§ Interaction with abuse heuristics`; `§ MCP resource limits & abuse budgets`; `§ Implementation Status and Client Expectations`; `§ Reconnection & Session Recovery`; `§ Example Workflow`; `§ Related Documentation` | `MCP-01`; strengthens `SESSION-03`, `SESSION-06`, and `PO-2.4`. |
| 13 | [system-architecture-observability-incident-runbook.md](../../architecture/system-architecture-observability-incident-runbook.md) | Runbook | `§ Objectives`; `§ Independent Detection Contract`; `§ Deadman Freshness Contract`; `§ Common Fallbacks (When Dashboards Are Unavailable)`; `§ Prometheus Down or Stale`; `§ Alertmanager Down or Not Routing`; `§ Elasticsearch/Kibana Down or Indexing Stalled`; `§ Grafana Down`; `§ Jaeger / OpenTelemetry Collector Down`; `§ Post-Incident Checklist`; `§ Fallback Query Cheat Sheet` | No new decision; operationalizes `OBS-02` through `OBS-05`. |
| 14 | [system-architecture-operator-credentials-runbook.md](../../architecture/system-architecture-operator-credentials-runbook.md) | Runbook | `§ Operator Client Certificates (mTLS)`; `§ Issuance`; `§ Storage and Distribution`; `§ Rotation`; `§ Revocation / Incident Response` | No new decision; strengthens `SEC-01`, `PREFLIGHT-02`, and `RECOVERY-02`. |
| 15 | [system-architecture-player-experience-incident-runbook.md](../../architecture/system-architecture-player-experience-incident-runbook.md) | Runbook | `§ Incident Types`; `§ Trace Preconditions (For Latency/Tick Root Cause)`; `§ Login Success Ratio Below SLO`; `§ Command Latency Above SLO`; `§ Chat Delivery Latency Above SLO`; `§ Telnet and WebSocket Path Availability Below SLO` | No new decision; operationalizes `OBS-02`, `OBS-03`, `OBS-04`, and `OBS-05`. |
| 16 | [system-architecture-post-restore-hardening.md](../../architecture/system-architecture-post-restore-hardening.md) | Runbook | `§ Restore Quarantine`; `§ Post-Restore Secret Hardening`; `§ Post-Restore Coordination Recovery Gate`; `§ Reopen Sequence`; `§ Planned DB Credential Rotation` | Strengthens `RECOVERY-02`, `SEC-02`, and `COMPLIANCE-01`; no duplicate recovery key. |
| 17 | [system-architecture-procedural-generation.md](../../architecture/system-architecture-procedural-generation.md) | Normative design | `§ Use Cases`; `§ Generator Types`; `§ 1. SimpleDungeonGenerator`; `§ Algorithm`; `§ Deterministic Replay Contract for Design-Time Generation`; `§ 2. OverworldMapGenerator`; `§ Generation Pipeline:`; `§ Output and Metadata (Common)`; `§ Integration Guidelines`; `§ Service Responsibilities` | `PROC-01` through `PROC-05`; strengthens `CONTENT-01`, `CONTENT-02`, `CONTENT-03`, `TICK-03`, `TICK-04`, `TICK-05`, and `TICK-06`. |
| 18 | [system-architecture-promotion-attestation.md](../../architecture/system-architecture-promotion-attestation.md) | Normative design | `§ Purpose`; `§ Artifact Format`; `§ Validation Rules`; `§ Storage and Retention`; `§ Ownership` | `PROMO-01`; strengthens `OPS-01`, `OPS-02`, and `OPS-05`. |
| 19 | [system-architecture-protocol-bridging.md](../../architecture/system-architecture-protocol-bridging.md) | Normative design | `§ Bridging Overview`; `§ WebSocket Client Flow (Modern Clients)`; `§ Gameplay WebSocket route contract (normative)`; `§ Telnet / TCP Client Flow (Legacy Clients)`; `§ Ordering & Delivery Invariants`; `§ Gameplay Command Idempotency (Client View)`; `§ Telnet Disconnect Reasons`; `§ Cross-Client Takeover Examples`; `§ Backpressure & Slow Clients`; `§ Global Load Shedding Strategy`; `§ Telnet TLS modes and PROXY protocol`; `§ Protocol handling and security`; `§ Bridging to the backend`; `§ Buffering, reconnection, and observability`; `§ Outbound Recovery Boundary`; `§ WebSocket Bridge Configuration`; `§ TCP Flow Benefits`; `§ Unified Backend Session Logic`; `§ Recommended Telnet deployment modes` | No new decision; materially strengthens `EDGE-01`, `EDGE-03`, `SESSION-03`, `SESSION-05`, `SESSION-06`, `SEC-03`, and `PO-2.4`. |
| 20 | [system-architecture-runbooks.md](../../architecture/system-architecture-runbooks.md) | Index | `§ Deployment`; `§ Scaling`; `§ Operator Access`; `§ Recovery`; `§ Player Experience Incidents`; `§ Observability Stack Incidents`; `§ Asset Store`; `§ Hotfix Procedure`; `§ Telnet Path Degraded or Failing` | No new decision; runbook index. |
| 21 | [system-architecture-scaling-runbook.md](../../architecture/system-architecture-scaling-runbook.md) | Runbook | `§ Scaling Principles`; `§ Pre-Scale Topology Decision Gate (Required)`; `§ Scaling Application Services`; `§ Scaling Redis`; `§ Scaling PostgreSQL`; `§ Verification`; `§ Tick- and Redis-Aware Scaling Indicators`; `§ Starting Guardrails (Baseline Sizing)`; `§ Capacity Model (Required Inputs)` | `CAPACITY-01`, `CAPACITY-02`; strengthens `TICK-06`, `REDIS-02`, and `REDIS-01`. |
| 22 | [system-architecture-telnet-degraded-runbook.md](../../architecture/system-architecture-telnet-degraded-runbook.md) | Runbook | `§ Symptoms`; `§ Triage`; `§ Remediation`; `§ Buffer, Slow-Client, and WebSocket-Specific Considerations`; `§ Web-Only WebSocket Degradation Playbook`; `§ Stalled Backend and Partial-Disconnect Symptoms` | No new decision; operationalizes `SEC-03`, `EDGE-03`, `SESSION-05`, `SESSION-06`, `PO-2.4`, and `OPS-06`. |
| 23 | [system-architecture-testing.md](../../architecture/system-architecture-testing.md) | Normative design | `§ Testing Scope`; `§ Redis in Tests`; `§ Tooling and Gradle Layout`; `§ Cross-Service Integration Testing`; `§ CI/CD Integration`; `§ High-Concurrency Load Testing`; `§ Security Testing`; `§ Observability Tests`; `§ Synthetic Player-Flow Canary Checks`; `§ Where These Checks Run (Decision)` | `TEST-01`, `TEST-02`, `TEST-03`; strengthens `OPS-04`, `OPS-06`, `OBS-02`, `OBS-03`, and `CAPACITY-01`. |
| 24 | [system-context-diagram.md](../../architecture/system-context-diagram.md) | Index/diagram | document title (no section heading); `§ Related Documentation` | No new decision; explanatory context diagram. |
| 25 | [user-journeys-creators.md](../../architecture/user-journeys-creators.md) | Reference | `§ Goals`; `§ Quick Reference`; `§ 1. Game Creation`; `§ 2. World and Entity Design`; `§ 3. Add Automation & Scripting`; `§ 4. Publish and Start a Game Instance`; `§ 5. Patch and Update a Live Game`; `§ 6. Branding and Customization`; `§ 7. Playtesting & Analytics`; `§ 8. Extensibility & External Tools`; `§ Related Documentation` | `LIFE-01`, `PLAYTEST-01`, `EQUIP-01`; strengthens `AUTH-06`, `AUTH-07`, `ADMIT-01`, `TENANT-03`, `CONTENT-02`, and `OPS-05`. |
| 26 | [user-journeys-operators.md](../../architecture/user-journeys-operators.md) | Reference | `§ Goals`; `§ Quick Reference`; `§ 1. Monitoring and Moderation`; `§ 2. Operator Recovery Journeys`; `§ 3. Testing & Continuous Delivery`; `§ 4. Deployment & Environment Configuration`; `§ 5. Observability & Debugging`; `§ 6. Platform Service Updates`; `§ Related Documentation` | No new decision beyond `LIFE-01`, `PLAYTEST-01`, and `SAFETY-01`; strengthens recovery, observability, and promotion evidence. |
| 27 | [user-journeys-players.md](../../architecture/user-journeys-players.md) | Reference | `§ Goals`; `§ Quick Reference`; `§ 1. Sign Up`; `§ 2. Join a Game for the First Time`; `§ 3. Character Creation & Selection`; `§ 4. Player Login and Gameplay`; `§ 5. Social Interaction & Safety`; `§ 6. Purchases and Subscriptions`; `§ 7. Password Resets & Account Recovery`; `§ 8. Switch Games or Manage Multiple Games`; `§ 9. Account Data Export & Deletion`; `§ Related Documentation` | `PLAYER-01`, `SAFETY-01`, `COMMERCE-01`, `DATA-01`, `SOCIAL-01`, `ACCOUNT-01`; strengthens `AUTH-02`, `AUTH-06`, `AUTH-07`, `ADMIT-01`, `SESSION-07`, and `CONTENT-04`. |
| 28 | [user-journeys.md](../../architecture/user-journeys.md) | Index | `§ Players`; `§ Creators`; `§ Operators` | No new decision; journey hub. |
| 29 | [infrastructure/README.md](../../architecture/infrastructure/README.md) | Index | `§ Core Infrastructure Docs`; `§ Network Boundary and Certificates`; `§ Multi-Tenant Deployment`; `§ Logging Stack`; `§ Usage`; `§ Related Documentation` | No new decision; infrastructure index. |
| 30 | [deployment-environments.md](../../architecture/infrastructure/deployment-environments.md) | Normative design | `§ Quick Environment Decision Guide`; `§ Implementation Notes`; `§ Terms`; `§ Canonical Environment Classes`; `§ Local Development: Docker Compose`; `§ Docker Compose Characteristics`; `§ Docker Health Checks`; `§ Production: Kubernetes`; `§ Kubernetes Characteristics`; `§ Kubernetes Health Monitoring`; `§ Kubernetes Auto Recovery`; `§ Telnet Edge Deployment`; `§ Monitoring & Logging`; `§ Kubernetes (Default)`; `§ Docker Compose (Optional)`; `§ Spring Profile Configuration`; `§ Staging Environment for Playtesting`; `§ PR Preview Environment`; `§ Related Documentation` | `HEALTH-01`; strengthens `PREFLIGHT-01`, `PREFLIGHT-02`, `RECOVERY-01`, `OPS-04`, and `OPS-06`. |
| 31 | [environment-and-secrets-catalog.md](../../architecture/infrastructure/environment-and-secrets-catalog.md) | Reference | `§ Common Application Settings`; `§ PostgreSQL Credentials`; `§ Redis Coordination & Cache`; `§ TLS & Certificates`; `§ Authentication & JWT`; `§ Service Discovery`; `§ Observability`; `§ Asset Storage`; `§ Backup & Restore Variables`; `§ Additional Notes` | Resolved `SESSION-08` lifetime distinction; strengthens `REDIS-02`, `SEC-01`, `SEC-02`, `EDGE-03`, and `PREFLIGHT-02`. |
| 32 | [environment-and-secrets-overview.md](../../architecture/infrastructure/environment-and-secrets-overview.md) | Normative design | `§ Operator Quick Reference`; `§ Implementation Notes`; `§ Core Profiles`; `§ PostgreSQL (Authoritative Data)`; `§ Redis (Coordination vs Cache/Rate‑Limit)`; `§ TCP Proxy → Gateway Bridge (Telnet)`; `§ Secrets & Certificates`; `§ Local Development vs Kubernetes Environments`; `§ Local Development`; `§ Shared and Player-Facing Kubernetes Environments`; `§ JWT Trust Model by Environment`; `§ Secret Governance Tiers`; `§ Secret Compliance Controls`; `§ Player-Facing Environment Bootstrap Requirements`; `§ Configuration vs Secrets`; `§ Certificate Management & Watchers`; `§ How to Use the Catalog`; `§ Related Documentation` | `COMPLIANCE-01`; strengthens `SEC-01`, `SEC-02`, `SEC-03`, `REDIS-02`, `PREFLIGHT-02`, and `RECOVERY-02`. |
| 33 | [environment-and-secrets.md](../../architecture/infrastructure/environment-and-secrets.md) | Index/stub | `§ Overview`; `§ Operator & Architecture Overview`; `§ Operator Quick Reference`; `§ Environment Variable Catalog` | No new decision; navigation/stub document. |
| 34 | [schedule.md](../../architecture/infrastructure/schedule.md) | Reference | `§ GitHub CI`; `§ Kubernetes Cluster (Production)`; `§ Kubernetes Cluster (Staging)`; `§ Hobby / Self-Hosted Environments` | No new decision; schedule evidence for `OPS-03`, `OPS-04`, and `RECOVERY-01`. |
| 35 | [platform-settings-reference.md](../../architecture/generated/platform-settings-reference.md) | Generated | `§ firemud.communication`; `§ firemud.presentation`; `§ firemud.reconnection`; `§ firemud.command-history`; `§ firemud.command-capabilities`; `§ firemud.movement`; `§ firemud.world-topology` | No independent decision; concrete evidence for existing settings/command keys and the resolved `SESSION-08` lifetime distinction. |

## Prioritized Adversarial Queue

The queue is ordered by the cost of leaving a contradiction or untestable boundary unresolved, not by document order.

1. **P0 - Verify resolved `SESSION-08`.** Prove uninterrupted token rotation, fresh-token reconnect, the 180-second resume cap under the remaining continuity-binding lifetime, stale-session fall-through, logout replay suppression, independent transcript retention, and cross-device behavior against `AUTH-02`, `SESSION-03`, `SESSION-04`, `CMD-04`, and `SET-01`.
2. **P0 - Prove the accepted `RECOVERY-01` and `RECOVERY-02` gates and challenge `PREFLIGHT-01`, `PREFLIGHT-02`, and `COMPLIANCE-01`.** Restore an online-write artifact with a missing participant, surviving Redis, stale credentials, cross-environment bindings, incomplete quarantine, or unsafe external-effect result. The expected result is fail closed before player admission; an accepted target does not count as implementation proof.
3. **P0 - Attack `ASSET-01`, `ASSET-02`, `PROC-02`, `PROC-04`, `CONTENT-01`, `CONTENT-02`, and `PROMO-01`.** Exercise interrupted publish, stale `stateEpoch`, exact-byte repair, mixed prefixes, replay with changed implementation/configuration, duplicate generation, and promotion without rebuild. Any path that can launch partial or non-reproducible content invalidates the proposed authority chain.
4. **P0 - Attack `OBS-04`, `OBS-05`, and `HEALTH-01`.** Disable the monitoring stack, Alertmanager, indexed logs, and dependency readiness independently. Verify that external detection still fires, fallback state expires, administrative safety actions remain available, and an unhealthy dependency cannot receive player traffic.
5. **P1 - Reconcile `PROC-01`, `PROC-03`, `PROC-05`, `TICK-06`, and `EQUIP-01`.** Test a generated revision over manually edited content, a playtest fork, a topology epoch change, and game-specific body layouts. Confirm ownership, scope replacement, replay, density, and runtime validation do not silently overwrite or cross tenant/realm boundaries.
6. **P1 - Obtain product and policy decisions for `LIFE-01`, `PLAYTEST-01`, `PLAYER-01`, `SAFETY-01`, `COMMERCE-01`, and `DATA-01`.** Specifically resolve who may launch/cut over, whether revocation drains or ejects, character creation when descriptors are missing, moderation precedence and appeals, refund entitlement reversal, and deletion while subscriptions or tenant-owned data remain.
7. **P1 - Validate `OBS-01`, `OBS-02`, `OBS-03`, `OBS-06`, `TEST-01`, `TEST-02`, `TEST-03`, `CAPACITY-01`, and `CAPACITY-02`.** Measure cardinality, player SLOs, canary contamination, two-minute log queryability, Redis proof limits, load-test cost, tick capacity, and retention/GC behavior before turning target numbers into gates.
8. **P2 - Decide future compatibility commitments for `FRONT-01`, `MCP-01`, `SOCIAL-01`, `ACCOUNT-01`, `LLM-01`, and `COMPLIANCE-01`.** Resolve browser hosting/sequence, MCP package stability, audience scopes, external identity providers, LLM provider and PII policy, and the operational burden of immutable credential evidence before public promises are made.
