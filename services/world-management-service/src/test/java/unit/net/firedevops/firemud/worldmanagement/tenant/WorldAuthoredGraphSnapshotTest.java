package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import org.junit.jupiter.api.Test;

class WorldAuthoredGraphSnapshotTest {
  private static final String DIGEST = "a".repeat(64);

  @Test
  void acceptsRetainedSchema2AndCurrentSchema3ButRejectsUnsupportedSchemas() {
    for (int schema : new int[] {2, 3})
      assertThat(request(schema).digestSchemaVersion()).isEqualTo(schema);
    for (int schema : new int[] {0, 1, 4}) {
      assertThatThrownBy(() -> request(schema)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  private CaptureRequest request(int schema) {
    return new CaptureRequest(
        "firemud",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "publish-request-1",
        DIGEST,
        1L,
        "publish:tenant:publish-request:publish-request-1",
        "commit-1",
        DIGEST,
        schema,
        List.of());
  }

  @Test
  void captureRequestPreservesTypedEpochAndCanonicalizesTupleOrder() {
    OwnedAffectedTuple scope =
        new OwnedAffectedTuple(
            "WORLD_MANAGEMENT",
            "ROOM",
            "9223372036854775806",
            "ZONE_SUBTREE",
            "9223372036854775805",
            "9223372036854775807");
    OwnedAffectedTuple aggregate =
        new OwnedAffectedTuple("WORLD_MANAGEMENT", "REGION", "4", "", "", "9");

    CaptureRequest request =
        new CaptureRequest(
            "firemud",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "publish-request-1",
            DIGEST,
            1L,
            "publish:tenant:publish-request:publish-request-1",
            "commit-1",
            DIGEST,
            2,
            List.of(scope, aggregate));

    assertThat(request.suppliedOwnedAffectedTuples()).containsExactly(aggregate, scope);
    assertThat(scope.expectedEpoch()).isEqualTo("9223372036854775807");
  }

  @Test
  void changedOrAmbiguousTupleDeclarationsAreRejected() {
    assertThatThrownBy(
            () -> new OwnedAffectedTuple("GAME_DESIGN", "ROOM", "1", "ZONE_SUBTREE", "2", "3"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OwnedAffectedTuple("WORLD_MANAGEMENT", "ROOM", "1", "ZONE_SUBTREE", "2", "03"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new OwnedAffectedTuple("WORLD_MANAGEMENT", "ROOM", "1", "ZONE_SUBTREE", "", "3"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sourceAndReceiptDigestsRetainTheirRequiredPrefix() {
    assertThatThrownBy(
            () ->
                new WorldAuthoredGraphSnapshot(
                    UUID.randomUUID(),
                    "firemud",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "world-one",
                    1L,
                    2L,
                    3L,
                    (short) 1,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "sha256:" + DIGEST,
                    UUID.randomUUID(),
                    DIGEST,
                    "sha256:" + DIGEST,
                    UUID.randomUUID(),
                    "publish-request-1",
                    DIGEST,
                    1L,
                    "publish:tenant:publish-request:publish-request-1",
                    "commit-1",
                    DIGEST,
                    2,
                    DIGEST,
                    "[]",
                    "[]",
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    DIGEST,
                    WorldAuthoredGraphSnapshot.OwnerCommitProofStatus.CAPTURED_UNVERIFIED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceEvidenceDigest");

    assertThatThrownBy(
            () ->
                new WorldAuthoredGraphSnapshot(
                    UUID.randomUUID(),
                    "firemud",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "world-one",
                    1L,
                    2L,
                    3L,
                    (short) 1,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "sha256:" + DIGEST,
                    UUID.randomUUID(),
                    "sha256:" + DIGEST,
                    DIGEST,
                    UUID.randomUUID(),
                    "publish-request-1",
                    DIGEST,
                    1L,
                    "publish:tenant:publish-request:publish-request-1",
                    "commit-1",
                    DIGEST,
                    2,
                    DIGEST,
                    "[]",
                    "[]",
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    DIGEST,
                    WorldAuthoredGraphSnapshot.OwnerCommitProofStatus.CAPTURED_UNVERIFIED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("intakeReceiptDigest");
  }
}
