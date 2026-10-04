package net.firedevops.firemud.common.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeAccountIdentityEvidenceTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void acceptsEachCanonicalAccountProvenanceKind() {
    assertThat(evidence("ACCOUNT_V29_MIGRATION").sourceAccountRowId()).isEqualTo(73L);
    assertThat(evidence("ACCOUNT_REPOSITORY_INSERT").accountUuidProvenance())
        .isEqualTo("ACCOUNT_REPOSITORY_INSERT");
    assertThat(evidence("ACCOUNT_DATABASE_INSERT").accountUuidProvenance())
        .isEqualTo("ACCOUNT_DATABASE_INSERT");
  }

  @Test
  void rejectsUnsupportedSchemaNamespaceIdentitySourceRowAndProvenance() {
    RuntimeAccountIdentityEvidence valid = evidence("ACCOUNT_V29_MIGRATION");
    assertInvalid(
        2,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalAccountId(),
        valid.sourceAccountRowId(),
        valid.accountUuidProvenance(),
        valid.sourceNumericRowId());
    assertInvalid(
        1,
        "test.invalid",
        valid.requestId(),
        valid.canonicalAccountId(),
        valid.sourceAccountRowId(),
        valid.accountUuidProvenance(),
        valid.sourceNumericRowId());
    assertInvalid(
        1,
        valid.targetNamespace(),
        new UUID(0L, 0L),
        valid.canonicalAccountId(),
        valid.sourceAccountRowId(),
        valid.accountUuidProvenance(),
        valid.sourceNumericRowId());
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        new UUID(0L, 0L),
        valid.sourceAccountRowId(),
        valid.accountUuidProvenance(),
        valid.sourceNumericRowId());
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalAccountId(),
        0L,
        valid.accountUuidProvenance(),
        valid.sourceNumericRowId());
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalAccountId(),
        valid.sourceAccountRowId(),
        valid.accountUuidProvenance(),
        0L);
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalAccountId(),
        valid.sourceAccountRowId(),
        valid.accountUuidProvenance(),
        valid.sourceNumericRowId() + 1L);
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalAccountId(),
        valid.sourceAccountRowId(),
        null,
        valid.sourceNumericRowId());
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalAccountId(),
        valid.sourceAccountRowId(),
        "UNKNOWN",
        valid.sourceNumericRowId());
  }

  private static RuntimeAccountIdentityEvidence evidence(String provenance) {
    return new RuntimeAccountIdentityEvidence(
        1, "test", REQUEST_ID, ACCOUNT_ID, 73L, provenance, 73L);
  }

  private static void assertInvalid(
      int schemaVersion,
      String targetNamespace,
      UUID requestId,
      UUID canonicalAccountId,
      long sourceAccountRowId,
      String provenance,
      long sourceNumericRowId) {
    assertThatThrownBy(
            () ->
                new RuntimeAccountIdentityEvidence(
                    schemaVersion,
                    targetNamespace,
                    requestId,
                    canonicalAccountId,
                    sourceAccountRowId,
                    provenance,
                    sourceNumericRowId))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
