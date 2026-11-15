# Selected FireMUD recovery sources


This source-only pack preserves five selected recovery inputs for the named owners. Files retain their original repository-relative paths. Each file SHA-256 and source commit is recorded in `source-manifest.json`. This branch contains the selected source files, patches, and provenance.

## Destinations and provenance

### `account-player-admission`

Destination: Account tracker: design/project-management/implementation-tracking/player-access-and-session.md; receiver on origin/dot/oldstack-account-20261113 at bdc881d15503cae58685258336f1294713a1dacb. Runtime registration and validation remain pending.
Original source: `FireMUD-dot-entitlement-3434-20261114` at `detached` / `d7ebf9689c4ff40146dd09f911ca386f6fbfb812`.
Input artifact: `Account-PLAYER-ADMISSION-receiver.patch` SHA-256 `15e0ae40cdcf95fd779d50c6c850240f7c0b7a73e72287a8d36c8cbd15210e3e`; expected selected source files: 4; included: 5.
The patch is preserved byte-for-byte. 4 exact postimages reconstructed from patch against named Account base.

### `world-fixture-activation`

Destination: World tracker design/project-management/implementation-tracking/world-runtime-and-movement.md; canonical fixture launch boundary. Compose with current World hold and Game Session pointer-proof owners; storage change follows World V1050.
Original source: `FireMUD-gameplay-fixture-first-open-owner-preparation-20261015` at `refs/heads/codex/gameplay-fixture-first-open-owner-preparation-20261015` / `6b487afb6c69d7a55ac954439b5ac3c13e8c6f6e`.
Input artifact: `World-fixture-activation-reference.tar.gz` SHA-256 `c53a123bd4a7b2750f13ff3250f39c17acb6bdd2ac89a297333c22e968258eaa`; expected selected source files: 4; included: 4.

### `account-character-create`

Destination: Account tracker design/project-management/implementation-tracking/player-access-and-session.md and Entity contract design/architecture/microservices/entity-management-service/api-contracts.md#character-creation-owner-protection. Add current realm-grant proof and delegation resolver; use a forward migration because V1012 is occupied.
Original source: `FireMUD-2972-headroom-repair-20261018` at `refs/heads/codex/gameplay-2972-current-parent-receiving-20261018` / `9a5a165e90cb9e9e9c3ff352584474e62c3aa719`.
Input artifact: `Account-CHARACTER-CREATE-reference.tar.gz` SHA-256 `11510f399f25c0cea031ce45b674e54f301da9a1375056c884dd857414a6234e`; expected selected source files: 24; included: 24.

Sanitization: `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/charactercreate/AccountCharacterCreateOutboundMtlsTest.java` contained one bearer-credential-shaped test fixture literal. It is now the deterministic non-secret `Bearer test-fixture-token`; there was no assertion of the original literal to update. The original source-file SHA-256 and sanitized postimage SHA-256 are recorded in `source-manifest.json`.

### `automation-script-patch-snapshot`

Destination: Creator tracker design/project-management/implementation-tracking/game-authoring-publishing-and-activation.md and design/architecture/microservices/game-design-service/version-control.md. Bind tenant/patch serialization and definitions-plus-bindings snapshot to the current stable request and complete affected-handler manifest.
Original source: `FireMUD-script-patch-publication-envelope` at `refs/heads/codex/script-patch-publication-envelope` / `50c3cc7c7bc47b41a4a2407874a749e06d7e7c10`.
Input artifact: `Automation-script-patch-snapshot-reference.tar.gz` SHA-256 `9f867aa9f97c9ddea178274e2484842507dcd946eefe9005ebcf3059338c0399`; expected selected source files: 5; included: 5.

### `account-draft-entity-settlement`

Destination: Account tracker design/project-management/implementation-tracking/player-access-and-session.md, current author-draft terminal settlement owner: add required-owner Entity readback and focused test.
Original source: `FireMUD-document-gameplay-owned-bridge-20261007` at `refs/heads/codex/document-gameplay-owned-bridge-20261007` / `7cfc5e7d7836758063f9ea0d46dd4e9fd5510395`.
Input artifact: `Account-draft-Entity-settlement-reference.tar.gz` SHA-256 `6de70685dbcd9a476c98c2df7b76e9c9f1348b344dc19de4f3317becced31455`; expected selected source files: 2; included: 2.

## Public-source scan

No credential or private-user-data concern was found after the test fixture literal was replaced.
