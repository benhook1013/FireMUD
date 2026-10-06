package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FreshTenantCreationEvidenceTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OPERATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String REQUEST_DIGEST =
      "sha256:467cbe9641a3ad61d14969aece34b770629ae569641d69fb1aea6e3f1538a28a";
  private static final String SOURCE_KEY = "legacy-α";

  @Test
  void acceptsExactGoldenFreshTenantEvidence() {
    FreshTenantCreationEvidence evidence = validEvidence();

    assertThat(evidence.schemaVersion()).isEqualTo(1);
    assertThat(evidence.targetNamespace()).isEqualTo("prod");
    assertThat(evidence.creationRequestId()).isEqualTo(REQUEST_ID);
    assertThat(evidence.operationId()).isEqualTo(OPERATION_ID);
    assertThat(evidence.requestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(evidence.canonicalTenantId()).isEqualTo(CANONICAL_TENANT_ID);
    assertThat(evidence.sourceGameRowId()).isEqualTo(42L);
    assertThat(evidence.sourceGameTenantKey()).isEqualTo(SOURCE_KEY);
    assertThat(evidence.provenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(evidence.evidenceDigest())
        .isEqualTo("sha256:533f0c6590aff359baa6bbaa1a4b9ab7cd6ebf68a3663ed6681053f5a8cc1955");
  }

  @Test
  void rejectsUnsupportedSchemaNamespaceAndNilUuids() {
    FreshTenantCreationEvidence valid = validEvidence();
    assertInvalid(
        valid.schemaVersion() + 1,
        valid.targetNamespace(),
        valid.creationRequestId(),
        valid.operationId(),
        valid.requestDigest(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        valid.provenanceKind(),
        valid.evidenceDigest());
    assertInvalid(
        valid.schemaVersion(),
        "prod.other",
        valid.creationRequestId(),
        valid.operationId(),
        valid.requestDigest(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        valid.provenanceKind(),
        valid.evidenceDigest());
    assertInvalid(
        valid.schemaVersion(),
        valid.targetNamespace(),
        new UUID(0L, 0L),
        valid.operationId(),
        valid.requestDigest(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        valid.provenanceKind(),
        valid.evidenceDigest());
    assertInvalid(
        valid.schemaVersion(),
        valid.targetNamespace(),
        valid.creationRequestId(),
        new UUID(0L, 0L),
        valid.requestDigest(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        valid.provenanceKind(),
        valid.evidenceDigest());
    assertInvalid(
        valid.schemaVersion(),
        valid.targetNamespace(),
        valid.creationRequestId(),
        valid.operationId(),
        valid.requestDigest(),
        new UUID(0L, 0L),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        valid.provenanceKind(),
        valid.evidenceDigest());
  }

  @Test
  void rejectsMalformedDigestSourceKeyProvenanceAndChangedTupleDigest() {
    FreshTenantCreationEvidence valid = validEvidence();
    assertInvalid(
        valid.schemaVersion(),
        valid.targetNamespace(),
        valid.creationRequestId(),
        valid.operationId(),
        "SHA256:" + "a".repeat(64),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        valid.provenanceKind(),
        valid.evidenceDigest());
    assertInvalid(
        valid.schemaVersion(),
        valid.targetNamespace(),
        valid.creationRequestId(),
        valid.operationId(),
        valid.requestDigest(),
        valid.canonicalTenantId(),
        0L,
        valid.sourceGameTenantKey(),
        valid.provenanceKind(),
        valid.evidenceDigest());
    assertInvalid(
        valid.schemaVersion(),
        valid.targetNamespace(),
        valid.creationRequestId(),
        valid.operationId(),
        valid.requestDigest(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        "bad" + (char) 0xd800,
        valid.provenanceKind(),
        valid.evidenceDigest());
    assertInvalid(
        valid.schemaVersion(),
        valid.targetNamespace(),
        valid.creationRequestId(),
        valid.operationId(),
        valid.requestDigest(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        "a".repeat(37),
        valid.provenanceKind(),
        valid.evidenceDigest());
    assertInvalid(
        valid.schemaVersion(),
        valid.targetNamespace(),
        valid.creationRequestId(),
        valid.operationId(),
        valid.requestDigest(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        "RETAINED_GAME_V30",
        valid.evidenceDigest());
    assertInvalid(
        valid.schemaVersion(),
        valid.targetNamespace(),
        valid.creationRequestId(),
        valid.operationId(),
        valid.requestDigest(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId() + 1,
        valid.sourceGameTenantKey(),
        valid.provenanceKind(),
        "sha256:" + "d".repeat(64));
  }

  private static FreshTenantCreationEvidence validEvidence() {
    return evidence(
        1,
        "prod",
        REQUEST_ID,
        OPERATION_ID,
        REQUEST_DIGEST,
        CANONICAL_TENANT_ID,
        42L,
        SOURCE_KEY,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            REQUEST_ID,
            OPERATION_ID,
            REQUEST_DIGEST,
            CANONICAL_TENANT_ID,
            42L,
            SOURCE_KEY,
            "NEW_GAME_ROW"));
  }

  private static void assertInvalid(
      int schemaVersion,
      String namespace,
      UUID creationRequestId,
      UUID operationId,
      String requestDigest,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind,
      String evidenceDigest) {
    assertThatThrownBy(
            () ->
                evidence(
                    schemaVersion,
                    namespace,
                    creationRequestId,
                    operationId,
                    requestDigest,
                    canonicalTenantId,
                    sourceGameRowId,
                    sourceGameTenantKey,
                    provenanceKind,
                    evidenceDigest))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static FreshTenantCreationEvidence evidence(
      int schemaVersion,
      String namespace,
      UUID creationRequestId,
      UUID operationId,
      String requestDigest,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind,
      String evidenceDigest) {
    return new FreshTenantCreationEvidence(
        schemaVersion,
        namespace,
        creationRequestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        evidenceDigest);
  }
}
