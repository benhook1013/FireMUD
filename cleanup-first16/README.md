# First 16 dirty-checkout public source recovery

Source-only donor files from the first 16 Capture-local-changes rows. Paths below each group retain repository-relative layout. This pack is not an integration patch or production-readiness claim. Use the per-file manifest for source checkout HEAD, SHA-256, and named current destination. No generated/ignored payloads, local secrets, or CI/test results are included.

## Groups
### `account-authority-events` (29 files)

Source checkout: `FireMUD-unit1b-creator-bound-authoring-auth`. Destination: `origin/dot/oldstack-account-20261113`. Player Access Account authority-source/event and Control UI security-operation owner; retain producer/readback plus focused source/operation proof.

### `account-current-source-projection` (10 files)

Source checkout: `FireMUD-unit1b-account-source-recovery`. Destination: `origin/dot/oldstack-account-20261113`. Player Access Account current-source and issuer-generation projection owner; retain Redis/Postgres projection source and focused proof.

### `account-jwt-bootstrap-materializer` (6 files)

Source checkout: `FireMUD-account-interim-custody-bootstrap`. Destination: `origin/dot/oldstack-account-20261113`. Account private LOGIN/JWT readiness source owner; bounded bootstrap/materializer candidate only, not rotation/convergence proof.

### `game-session-issuer-retirement` (14 files)

Source checkout: `FireMUD-gameplay-ci-performance-20261030`. Destination: `origin/develop`. Player Access Game Session issuer barrier/partition and physical-retirement source/proof; current owner/tracker destination.

### `initial-gameplay-recovery-controller` (30 files)

Source checkout: `FireMUD-gameplay-initial-release-owner-20261008`. Destination: `origin/develop`. Platform Operations backup/recovery owner: fail-closed source-only initial_gameplay_release journal/transition/outbox stage; not live recovery or traffic-open authority.

### `preseeded-assignment-owner` (27 files)

Source checkout: `FireMUD-gameplay-preseeded-owner-composition-local-20261025`. Destination: `origin/develop`. Entity and Player Access preseeded assignment client/receiver/proto owner source; retain bounded candidate, not old broad LOGIN/PLAY/JOIN composition.

### `game-session-handoff-docs` (2 files)

Source checkout: `FireMUD-dot-doc-fix-0400`. Destination: `origin/develop`. Game Session API and operations docs: precise normal-handoff unavailability status at this front.

### `ci-coverage-dependencies` (10 files)

Source checkout: `FireMUD-ci-coverage-dependencies-20261103`. Destination: `origin/develop`. Platform Operations/Delivery gate dependency source and focused contract proofs; source-only, no CI run evidence.

### `game-session-admission-cost-proof` (2 files)

Source checkout: `FireMUD-gameplay-admission-cost-20261031`. Destination: `origin/develop`. Player Access Game Session admission-abort and issuer-barrier composition proof.

### `world-initial-admission-hold-donor` (13 files)

Source checkout: `FireMUD-gameplay-fixture-first-open-owner-preparation-20261015`. Destination: `origin/develop`. World initial-admission hold contract/owner donor and focused proof. V1043 activation sources already exist in public recovery ref; omit old V1044/V1042.6/V1014 migration identities and legacy Game Session composition.

## Sanitization

Quoted credential assignment literals were replaced by the fixed harmless `public-test-credential` value in these files (no original literals are recorded here):
- `services/account-service/src/test/java/integration/net/firedevops/firemud/accountservice/IssuerGenerationProjectionPostgresRedisIntegrationTest.java`
- `services/account-service/src/test/java/integration/net/firedevops/firemud/accountservice/service/impl/AccountControlUiCredentialProducerPostgresRedisIntegrationTest.java`
