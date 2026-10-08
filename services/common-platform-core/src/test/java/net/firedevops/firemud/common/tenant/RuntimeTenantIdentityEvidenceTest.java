package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeTenantIdentityEvidenceTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void acceptsNewAndRetainedOwnerEvidence() {
    assertThat(evidence("NEW_GAME_ROW").provenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(evidence("RETAINED_GAME_V29").provenanceKind()).isEqualTo("RETAINED_GAME_V29");
  }

  @Test
  void rejectsUnsupportedOrIncompleteOwnerEvidence() {
    RuntimeTenantIdentityEvidence valid = evidence("NEW_GAME_ROW");
    assertInvalid(
        2,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        valid.provenanceKind());
    assertInvalid(
        1,
        "test.invalid",
        valid.requestId(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        valid.provenanceKind());
    assertInvalid(
        1,
        valid.targetNamespace(),
        new UUID(0L, 0L),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        valid.provenanceKind());
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        new UUID(0L, 0L),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        valid.provenanceKind());
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalTenantId(),
        0,
        valid.sourceGameTenantKey(),
        valid.provenanceKind());
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        " ",
        valid.provenanceKind());
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        "x".repeat(37),
        valid.provenanceKind());
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        "invalid-" + (char) 0xd800,
        valid.provenanceKind());
    assertInvalid(
        1,
        valid.targetNamespace(),
        valid.requestId(),
        valid.canonicalTenantId(),
        valid.sourceGameRowId(),
        valid.sourceGameTenantKey(),
        "UNKNOWN");
  }

  private static RuntimeTenantIdentityEvidence evidence(String provenanceKind) {
    return new RuntimeTenantIdentityEvidence(
        1, "test", REQUEST_ID, TENANT_ID, 42L, "source-game-key-42", provenanceKind);
  }

  private static void assertInvalid(
      int schemaVersion,
      String targetNamespace,
      UUID requestId,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind) {
    assertThatThrownBy(
            () ->
                new RuntimeTenantIdentityEvidence(
                    schemaVersion,
                    targetNamespace,
                    requestId,
                    canonicalTenantId,
                    sourceGameRowId,
                    sourceGameTenantKey,
                    provenanceKind))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
