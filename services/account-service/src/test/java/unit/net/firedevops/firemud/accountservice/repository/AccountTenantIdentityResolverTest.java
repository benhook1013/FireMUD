package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AccountTenantIdentityResolverTest {
  private static final long LEGACY_TENANT_ID = 41L;
  private static final String NAMESPACE = "proof";
  private static final String EVIDENCE_DIGEST = "sha256:" + "a".repeat(64);

  private final ApprovedLegacyTenantAssociationRepository associations =
      mock(ApprovedLegacyTenantAssociationRepository.class);
  private final LegacyTenantSourceEvidence sourceEvidence = mock(LegacyTenantSourceEvidence.class);
  private final AccountTenantIdentityResolver resolver =
      new AccountTenantIdentityResolver(associations, sourceEvidence, NAMESPACE);

  @Test
  void resolverRequiresCallerOwnedSnapshotTransaction() throws NoSuchMethodException {
    Transactional boundary =
        AccountTenantIdentityResolver.class
            .getMethod("resolve", long.class)
            .getAnnotation(Transactional.class);
    assertThat(boundary.propagation()).isEqualTo(Propagation.MANDATORY);
  }

  @Test
  void resolvesCanonicalIdentityWithRetainedProvenanceAfterExactEvidenceReadback() {
    ApprovedAssociation association = association();
    when(associations.findByLegacyTenantId(LEGACY_TENANT_ID)).thenReturn(Optional.of(association));
    when(sourceEvidence.digest(LEGACY_TENANT_ID)).thenReturn(EVIDENCE_DIGEST);

    ApprovedAssociation resolved = resolver.resolve(LEGACY_TENANT_ID);

    assertThat(resolved).isSameAs(association);
    assertThat(resolved.canonicalTenantId())
        .isEqualTo(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    assertThat(resolved.sourceLegacyGameTenantId()).isEqualTo("legacy-game-7");
    assertThat(resolved.sourceGameRowId()).isEqualTo(7L);
    assertThat(resolved.accountEvidenceDigest()).isEqualTo(EVIDENCE_DIGEST);
    assertThat(resolved.operationId())
        .isEqualTo(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    assertThat(resolved.manifestDigest()).isEqualTo("sha256:" + "b".repeat(64));
    assertThat(resolved.targetNamespace()).isEqualTo(NAMESPACE);
    verify(sourceEvidence).digest(LEGACY_TENANT_ID);
  }

  @Test
  void missingAssociationFailsClosedBeforeReadingRetainedEvidence() {
    when(associations.findByLegacyTenantId(LEGACY_TENANT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolve(LEGACY_TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("association is absent");
    verify(sourceEvidence, never()).digest(LEGACY_TENANT_ID);
  }

  @Test
  void changedEvidenceFailsClosed() {
    when(associations.findByLegacyTenantId(LEGACY_TENANT_ID))
        .thenReturn(Optional.of(association()));
    when(sourceEvidence.digest(LEGACY_TENANT_ID)).thenReturn("sha256:" + "c".repeat(64));

    assertThatThrownBy(() -> resolver.resolve(LEGACY_TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("evidence differs");
  }

  @Test
  void conflictingEvidenceFailsClosed() {
    when(associations.findByLegacyTenantId(LEGACY_TENANT_ID))
        .thenReturn(Optional.of(association()));
    when(sourceEvidence.digest(LEGACY_TENANT_ID))
        .thenThrow(new IllegalStateException("retained Account source is conflicting"));

    assertThatThrownBy(() -> resolver.resolve(LEGACY_TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source is conflicting");
  }

  @Test
  void namespaceMismatchFailsClosedBeforeReadingRetainedEvidence() {
    when(associations.findByLegacyTenantId(LEGACY_TENANT_ID))
        .thenReturn(Optional.of(association("other-namespace")));

    assertThatThrownBy(() -> resolver.resolve(LEGACY_TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("association is mismatched");
    verify(sourceEvidence, never()).digest(LEGACY_TENANT_ID);
  }

  @Test
  void invalidKeyFailsClosedBeforeRepositoryReads() {
    assertThatThrownBy(() -> resolver.resolve(0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive retained Account tenant key");
    assertThatThrownBy(() -> resolver.resolve(-1L)).isInstanceOf(IllegalArgumentException.class);
    verify(associations, never()).findByLegacyTenantId(0L);
    verify(associations, never()).findByLegacyTenantId(-1L);
  }

  private static ApprovedAssociation association() {
    return association(NAMESPACE);
  }

  private static ApprovedAssociation association(String targetNamespace) {
    return new ApprovedAssociation(
        LEGACY_TENANT_ID,
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "legacy-game-7",
        7L,
        EVIDENCE_DIGEST,
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        "sha256:" + "b".repeat(64),
        "A".repeat(88),
        targetNamespace,
        "game-design-owner-2026",
        "owner@example.test",
        "reviewed-change-123",
        "2026-09-26T00:00:00Z",
        1,
        1);
  }
}
