# ADR 0132: Future-Compatible Localization Boundary

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CMD-05`
- Primary capability: `EA-1.2` structured output, rendering, prompts, and presentation policy
- Affected capabilities: `EA-3.1`, `AR-1.1`, `EA-3.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of localization scope, authored-content fallback, runtime translation, evolving remote tooling, player speech, and present implementation reality

## Context

FireMUD needs enough locale structure now to avoid making future localization prohibitively disruptive, but it does not yet have a concrete localization product, creator workflow, language-coverage commitment, or selected translation provider. Choosing a comprehensive workflow or current remote service before those requirements exist would create premature dependency and may age poorly in a rapidly changing tooling field.

The implementation already contains partial technical groundwork. Game Session has stable keys and structured variables for some built-in output, renderer locale selection, and bounded alternate-locale tests. Authored room prose has explicit locale-tagged variants, a required source locale, and locale propagation through the current `LOOK` and movement-refresh path. That evidence does not make localization a completed current product capability: coverage is narrow, broader authored content and browser integration are incomplete, and no creator-facing localization workflow or external-tooling integration is proven.

The prior fallback phrase "language-only match" was also ambiguous. A locale request such as `fr-CA` must not nondeterministically select an authored regional sibling such as `fr-FR` or `fr-BE`.

## Decision

Localization and translation are deferred future product capabilities. FireMUD preserves only a minimal future-compatible boundary now and does not preselect a vendor, remote service, complete workflow, or platform-wide language coverage promise.

Every authored content bundle declares one required canonical source locale. Additional translations are optional stored variants identified by locale tag. Runtime fallback is deterministic:

1. exact requested locale tag;
2. an explicitly stored base-language variant;
3. the bundle's source locale.

Runtime never searches arbitrary regional siblings. For example, `fr-CA` may fall back to an authored `fr` variant, but the presence of `fr-FR` or `fr-BE` alone does not make either one a valid fallback.

The architecture keeps these concerns distinct:

- built-in platform and system strings;
- authored game/world content;
- first-party browser UI strings and assets;
- player-generated speech and communication.

Player-generated speech is not automatically translated by default. Adopting such a feature would require a separate product decision because translation could alter gameplay meaning and introduces privacy, abuse, consent, latency, and cost concerns.

There are no live translation-provider calls on the gameplay hot path. Any AI- or service-generated translation is an authoring-time draft that must be persisted as an explicit variant, versioned with the relevant authored bundle, reviewed as appropriate, and accepted before publication.

When a concrete localization use case is scheduled, the project must first research the then-current methods and remote localization tooling or services. Evaluation must cover workflow integration, privacy and data residency, security, cost, quality, tone and glossary control, provider dependency, offline and export options, and creator review. This ADR deliberately does not select today's vendor or tooling.

## Consequences

- The source-locale requirement and explicit variants keep stored content future-compatible without imposing a translation burden on every creator.
- Players may receive source-language content when an exact or explicit base-language translation does not exist; FireMUD makes no universal coverage promise.
- Deterministic fallback prevents unpredictable regional-language selection.
- Stored, published variants avoid provider latency, jitter, outages, and mutable provider output during gameplay.
- Deferring workflow and provider selection allows a later implementation to consider newer remote or local tooling against real requirements.
- Platform strings, authored content, browser UI, and player speech may progress at different times without falsely implying equal coverage.
- Authoring-time generated translations add review and version-management work but retain creator control over game-specific terminology and tone.

## Alternatives Considered

### Select a Localization Platform Now

FireMUD could standardize immediately on a current translation-management or remote AI service. That might accelerate a near-term localization project, but no concrete workflow or coverage target currently justifies the dependency. Provider capabilities and integration patterns are changing, so selection is deferred until research can evaluate the then-current field.

### Live Runtime Translation

The runtime could call a translation provider for missing content or player speech. This broadens apparent coverage, but adds gameplay latency, availability and cost dependencies, inconsistent output, privacy concerns, and semantic risk. It is rejected for authored gameplay content and is not the default for player speech.

### Arbitrary Language-Sibling Fallback

The runtime could select any stored regional variant sharing the requested base language. This can produce culturally or linguistically inappropriate output and can depend on storage or iteration order. It is rejected in favor of an explicitly authored base-language variant.

### Source-Locale-Only Content

FireMUD could avoid locale metadata until a localization project begins. That reduces immediate schema work but would make later adoption more disruptive and discard the bounded implementation already present. The minimal source-locale and explicit-variant boundary is retained.

## Implementation and Proof Reality

Current implementation is partial. Built-in keyed output and renderer locale selection cover a bounded subset of Game Session behavior. Locale-tagged room prose and adjacent-room naming are present on authoritative room views, with some alternate-locale renderer and integration proof. Broader world and item content, first-party browser consumption, creator review workflows, coverage reporting, publication-wide validation, and external localization-tool integration remain future work.

Existing fallback code and tests must be checked during implementation alignment to ensure they accept only an explicit base-language variant and never choose an arbitrary regional sibling. Future proof should distinguish source-locale behavior, exact matches, explicit base-language fallback, missing translations, publication/version association, and deterministic behavior across storage order.

## Reversibility and Revisit Triggers

The boundary is intentionally reversible at the tooling layer because it stores ordinary versioned locale variants and names no provider. Revisit the product and tooling decision when a concrete game, first-party UI, accessibility target, tenant requirement, or market commitment requires localization beyond the current bounded seam.

That revisit begins with research into then-current local and remote methods. Player-speech translation remains a separate consequential decision even if an authored-content localization workflow is adopted.
