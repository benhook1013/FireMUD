package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import org.junit.jupiter.api.Test;

class CanonicalInitialAdmissionRepositoryTest {
  @Test
  void pointerAndAuditVersionsUseValueEqualityBeyondLongCache() {
    Long attemptVersion = Long.valueOf(129L);
    Long pointerVersion = Long.valueOf(129L);
    Long auditVersion = Long.valueOf(129L);

    assertThat(attemptVersion).isNotSameAs(pointerVersion);
    assertThat(pointerVersion).isNotSameAs(auditVersion);
    assertThat(
            CanonicalInitialAdmissionRepository.pointerVersionMatches(
                attemptVersion, pointerVersion))
        .isTrue();
    assertThat(
            CanonicalInitialAdmissionRepository.pointerVersionMatches(attemptVersion, auditVersion))
        .isTrue();
  }

  @Test
  void pointerAndAuditVersionComparisonRejectsNullMismatch() {
    assertThat(CanonicalInitialAdmissionRepository.pointerVersionMatches(null, 129L)).isFalse();
    assertThat(CanonicalInitialAdmissionRepository.pointerVersionMatches(129L, null)).isFalse();
  }

  @Test
  void malformedPersistedOpenProjectionRequiresReconciliationAtSnapshotBoundary() {
    CanonicalInitialAdmissionOwnerProof proof = committedProof();
    CanonicalInitialAdmissionRepository.CurrentOpenPointer validPointer =
        new CanonicalInitialAdmissionRepository.CurrentOpenPointer(validOpenFields());

    assertThat(
            CanonicalInitialAdmissionRepository.snapshotDigestOrReconciliation(validPointer, proof))
        .matches("[0-9a-f]{64}");

    Map<String, Object> paddedName = validOpenFields();
    paddedName.put("world_display_name", " Demo World ");
    assertMalformedProjection(
        new CanonicalInitialAdmissionRepository.CurrentOpenPointer(paddedName),
        proof,
        IllegalArgumentException.class);

    Map<String, Object> invalidOrigin = validOpenFields();
    invalidOrigin.put("initial_admission_origin_kind", "UNKNOWN_ORIGIN");
    assertMalformedProjection(
        new CanonicalInitialAdmissionRepository.CurrentOpenPointer(invalidOrigin),
        proof,
        IllegalArgumentException.class);

    Map<String, Object> nullOrigin = validOpenFields();
    nullOrigin.put("initial_admission_origin_kind", null);
    assertMalformedProjection(
        new CanonicalInitialAdmissionRepository.CurrentOpenPointer(nullOrigin),
        proof,
        NullPointerException.class);
  }

  private static void assertMalformedProjection(
      CanonicalInitialAdmissionRepository.CurrentOpenPointer pointer,
      CanonicalInitialAdmissionOwnerProof proof,
      Class<? extends Throwable> cause) {
    assertThatThrownBy(
            () ->
                CanonicalInitialAdmissionRepository.snapshotDigestOrReconciliation(pointer, proof))
        .isInstanceOf(
            CanonicalInitialAdmissionRepository
                .CanonicalInitialAdmissionReconciliationRequiredException.class)
        .hasCauseInstanceOf(cause);
  }

  private static Map<String, Object> validOpenFields() {
    Map<String, Object> fields = new HashMap<>();
    fields.put("representation_version", 3);
    fields.put("target_namespace", "gameplay");
    fields.put("canonical_tenant_id", uuid(1));
    fields.put("world_slug", "demo-world");
    fields.put("world_display_name", "Demo World");
    fields.put("realm_id", uuid(2));
    fields.put("realm_slug", "main");
    fields.put("realm_display_name", "Production Main");
    fields.put("playable_state_namespace_id", uuid(3));
    fields.put("state_scope", "SHARED");
    fields.put("catalog_revision", 41L);
    fields.put("pointer_version", 73L);
    fields.put("admission_state", "OPEN");
    fields.put("visible", true);
    fields.put("public_production_realm", true);
    fields.put("requires_character_selection", null);
    fields.put("character_creation_policy", "ALLOW_NEW");
    fields.put("canonical_game_instance_id", uuid(4));
    fields.put("canonical_version_id", uuid(5));
    fields.put("initial_admission_request_id", "initial-admission-request");
    fields.put("initial_admission_request_digest", "a".repeat(64));
    fields.put("initial_admission_origin_kind", "NO_PRIOR_POINTER");
    fields.put("initial_admission_prior_pointer_version", null);
    fields.put("initial_admission_active_epoch", 82L);
    fields.put("initial_admission_hold_id", uuid(6));
    fields.put("initial_admission_hold_fence", uuid(7));
    fields.put("initial_admission_hold_binding_digest", "sha256:" + "b".repeat(64));
    fields.put("last_updated_by", "game-session-canonical-initial-admission");
    fields.put("last_update_reason", "World-held initial admission");
    fields.put("prepared_version_upgrade_id", null);
    return fields;
  }

  private static CanonicalInitialAdmissionOwnerProof committedProof() {
    return new CanonicalInitialAdmissionOwnerProof(
        CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        "initial-admission-request",
        "a".repeat(64),
        "gameplay",
        uuid(1),
        "demo-world",
        uuid(2),
        uuid(3),
        "SHARED",
        uuid(4),
        uuid(5),
        82L,
        41L,
        OriginKind.NO_PRIOR_POINTER,
        null,
        uuid(6),
        uuid(7),
        "sha256:" + "b".repeat(64),
        73L,
        27L,
        "sha256:" + "f".repeat(64),
        false,
        Instant.parse("2026-10-07T00:00:00Z"));
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }
}
