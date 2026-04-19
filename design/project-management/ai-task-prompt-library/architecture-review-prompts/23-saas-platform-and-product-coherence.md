# Architecture Review Prompt: SaaS Platform and Product Coherence

Best used for:

- reviewing whether FireMUD's current architecture, product model, and implementation direction actually hang together as a hosted multi-tenant platform rather than only as a single-game runtime

Read the following sources first. Follow references only when a listed doc clearly delegates a canonical contract needed to judge a finding. Then inspect the concrete code paths, API surfaces, and slice docs implicated by the docs and current branch state.

- `design/architecture/system-architecture-overview.md`
- `design/architecture/service-responsibility-matrix.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-versioning-runtime.md`
- `design/architecture/system-architecture-frontend.md`
- `design/architecture/system-architecture-security.md`
- `design/architecture/system-architecture-game-customization.md`
- `design/architecture/system-architecture-promotion-attestation.md`
- `design/project-management/vertical-slices/00-slice-progress.md`
- `design/project-management/vertical-slices/00-design-area-slice-coverage.md`
- `design/project-management/service-status-account-service.md`
- `design/project-management/service-status-game-design-service.md`
- `design/project-management/service-status-game-session-service.md`
- `design/project-management/service-status-logging-admin-service.md`
- `design/project-management/service-status-spring-cloud-gateway.md`

Review the current FireMUD branch for SaaS/platform compatibility: whether the product, tenancy, identity, entitlement, publishing, operator, and lifecycle model coherently supports FireMUD as a hosted multi-tenant platform.

Context:

- Repo: `/home/ben/src/FireMUD-wsl-copy`
- Read `AGENTS.md` first and follow it as canonical instructions.
- FireMUD is still in initial development.
- The goal is not only to review technical correctness. The goal is to review whether the implemented and designed system actually makes sense as a hosted platform where many games, realms, accounts, operators, and future commercial flows must coexist cleanly.
- `08` and `09` now describe major publish/version/runtime and tenant/realm-routing platform seams, so the branch has enough product shape to evaluate whether the overall SaaS model is coherent.

What to look for:

- places where tenant, account, creator, player, operator, or realm identity are modeled inconsistently
- hosted-platform workflows that are only implied rather than represented end to end
- places where the system still assumes one game or one tenant owns the whole platform
- gaps or contradictions in membership, admission, entitlements, billing, or lifecycle ownership
- places where publishing, version activation, realm routing, and first-party client flows do not combine into a coherent hosted product
- operator/support/admin assumptions that would break down in a multi-customer hosted environment
- missing or weak handling for tenant suspension, realm visibility, product plan enforcement, export/deletion boundaries, backup ownership, or support investigation surfaces
- places where creator tooling or branding/customization is treated as if it were only a local content concern rather than a hosted platform contract
- areas where docs, slice tracking, and current code imply different answers to "what is the actual product model here?"
- nearby related issues that would cause future implementation to build the wrong SaaS/product substrate

What I want in the output:

1. Findings first, ordered by severity
2. Focus on real product-model, tenancy, lifecycle, entitlement, operator, and hosted-platform coherence issues
3. Include concrete file references
4. Distinguish:
   - fix now
   - fix soon
   - design follow-up
5. Call out whether each finding is mainly about:
   - product-model gap
   - tenant/account/membership inconsistency
   - entitlement or billing-lifecycle mismatch
   - operator/support-model weakness
   - hosted-platform assumption gap
   - slice/design tracking gap
6. Prefer high-signal findings over broad summaries

Constraints:

- Default to static review unless a small targeted test/run materially helps confirm a concern
- Do not make code changes unless explicitly asked
- Do not spend time re-explaining accepted slice docs unless it directly supports a finding
- Keep the review at the product/platform/system level, not generic framework or style cleanup
- Record reusable lessons in `design/project-management/ai-observations.md` if you discover them

Helpful framing:

- Assume the goal is a coherent hosted FireMUD platform, not just a technically working gameplay runtime
- Be skeptical of designs that work only because one operator, one tenant, or one game is implicitly controlling everything
- Prefer findings that protect future platform, onboarding, support, billing, or tenant-isolation work from landing on the wrong substrate
- Review across product model, runtime model, operator model, and creator model together rather than treating them as unrelated concerns
