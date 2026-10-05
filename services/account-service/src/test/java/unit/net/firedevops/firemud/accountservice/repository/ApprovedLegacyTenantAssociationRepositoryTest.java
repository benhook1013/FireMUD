package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Base64;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class ApprovedLegacyTenantAssociationRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final LegacyTenantSourceEvidence sources = mock(LegacyTenantSourceEvidence.class);
  private final ApprovedLegacyTenantAssociationRepository repository =
      new ApprovedLegacyTenantAssociationRepository(dsl, sources, "proof");

  @Test
  void mismatchedOrIncompleteOwnerReadNeverTouchesRetainedRows() {
    ResolveLegacyAccountTenantAssociationResponse valid = response().build();

    assertThatThrownBy(() -> repository.importApproved(42L, valid))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                repository.importApproved(
                    41L, response().setTargetNamespace("other-namespace").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> repository.importApproved(41L, response().clearManifestSignature().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> repository.importApproved(41L, response().clearCanonicalTenantId().build()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl, sources);
  }

  private ResolveLegacyAccountTenantAssociationResponse.Builder response() {
    return ResolveLegacyAccountTenantAssociationResponse.newBuilder()
        .setLegacyAccountTenantId(41L)
        .setCanonicalTenantId("22222222-2222-4222-8222-222222222222")
        .setSourceLegacyGameTenantId("legacy-game-7")
        .setSourceGameRowId(7L)
        .setAccountEvidenceDigest("sha256:" + "a".repeat(64))
        .setOperationId("11111111-1111-4111-8111-111111111111")
        .setManifestDigest("sha256:" + "b".repeat(64))
        .setManifestSignature(Base64.getEncoder().encodeToString(new byte[64]))
        .setTargetNamespace("proof")
        .setSignerKeyId("game-design-owner-2026")
        .setApprovedBy("owner@example.test")
        .setApprovalReference("reviewed-change-123")
        .setSignedAt("2026-09-26T00:00:00Z")
        .setOperationEntryCount(1)
        .setManifestSchemaVersion(1);
  }
}
