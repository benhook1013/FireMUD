package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import org.junit.jupiter.api.Test;

class WorldAuthoredSourceIntakeDigestTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REGISTRATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SOURCE_OPERATION =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID INTAKE_REQUEST =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID WORLD_OPERATION =
      UUID.fromString("55555555-5555-4555-8555-555555555555");

  @Test
  void emitsSharedUnicodeAndFramingVectorsForCompleteSourceAndPrivateAssociation() {
    AuthoredWorldSourceEvidence source = source("violet-wilds", "Café 🐉", "NEW_GAME_ROW");

    assertThat(source.requestDigest())
        .isEqualTo("sha256:2f2e50fd98aede5418d7b52b8c837e12b092edd4044624b4047d21123cb35ba3");
    assertThat(source.evidenceDigest())
        .isEqualTo("sha256:0084bd312cf3c13540d453c6a1e173b6b8ef5436a57a55db3363e2cd6879cbda");
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, INTAKE_REQUEST, source);
    assertThat(requestDigest)
        .isEqualTo("sha256:1946ce9cb46af17732c4ec06445d7d9e297ac9bc22e30e7dde2858f9e1626c1f");
    assertThat(
            WorldAuthoredSourceIntakeDigest.receiptDigest(
                NAMESPACE, WORLD_OPERATION, requestDigest, source, 9001L))
        .isEqualTo("sha256:7d0ab0104385d80e5a2bc1f72386ca09b50473d262a0389840a90136a4778fb7");
  }

  @Test
  void requestAndReceiptDigestsBindEveryLocalScopeAndAllocationInput() {
    AuthoredWorldSourceEvidence source = source("violet-wilds", "Café 🐉", "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence changedWorld = source("amber-coast", "Café 🐉", "NEW_GAME_ROW");
    String request =
        WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, INTAKE_REQUEST, source);
    String receipt =
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            NAMESPACE, WORLD_OPERATION, request, source, 9001L);

    assertThat(WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, WORLD_OPERATION, source))
        .isNotEqualTo(request);
    assertThat(
            WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, INTAKE_REQUEST, changedWorld))
        .isNotEqualTo(request);
    assertThat(
            WorldAuthoredSourceIntakeDigest.receiptDigest(
                NAMESPACE, UUID.randomUUID(), request, source, 9001L))
        .isNotEqualTo(receipt);
    assertThat(
            WorldAuthoredSourceIntakeDigest.receiptDigest(
                NAMESPACE, WORLD_OPERATION, request, source, 9002L))
        .isNotEqualTo(receipt);
  }

  @Test
  void rejectsNilMalformedMismatchedAndRetainedInputs() {
    AuthoredWorldSourceEvidence fresh = source("violet-wilds", "A world", "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence retained = source("violet-wilds", "A world", "RETAINED_GAME_V30");

    assertThatThrownBy(
            () -> WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, new UUID(0L, 0L), fresh))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldAuthoredSourceIntakeDigest.requestDigest(
                    "other-namespace", INTAKE_REQUEST, fresh))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, INTAKE_REQUEST, retained))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldAuthoredSourceIntakeDigest.receiptDigest(
                    NAMESPACE, WORLD_OPERATION, "sha256:bad", fresh, 9001L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldAuthoredSourceIntakeDigest.receiptDigest(
                    NAMESPACE,
                    WORLD_OPERATION,
                    WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, INTAKE_REQUEST, fresh),
                    fresh,
                    0L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AuthoredWorldSourceEvidence source(
      String worldSlug, String displayName, String provenanceKind) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, REGISTRATION, TENANT, "north-star", worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION,
            SOURCE_OPERATION,
            requestDigest,
            TENANT,
            "north-star",
            worldSlug,
            displayName,
            42L,
            "legacy-game-tenant-42",
            provenanceKind);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        REGISTRATION,
        SOURCE_OPERATION,
        requestDigest,
        TENANT,
        "north-star",
        worldSlug,
        displayName,
        42L,
        "legacy-game-tenant-42",
        provenanceKind,
        evidenceDigest);
  }
}
