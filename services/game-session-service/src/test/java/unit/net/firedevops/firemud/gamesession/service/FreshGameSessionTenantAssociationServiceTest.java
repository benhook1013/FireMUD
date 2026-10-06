package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient;
import net.firedevops.firemud.gamesession.repository.FreshGameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociationService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class FreshGameSessionTenantAssociationServiceTest {
  private static final String NAMESPACE = "game-session-test";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");

  private final GameDesignRuntimeTenantIdentityClient sourceClient =
      mock(GameDesignRuntimeTenantIdentityClient.class);
  private final FreshGameSessionTenantAssociationRepository repository =
      mock(FreshGameSessionTenantAssociationRepository.class);
  private final FreshGameSessionTenantAssociationService service =
      new FreshGameSessionTenantAssociationService(sourceClient, repository, NAMESPACE);

  @Test
  void readsExactAuthenticatedFreshIdentityBeforeStartingLocalAssociation() {
    RuntimeTenantIdentityEvidence evidence =
        evidence(NAMESPACE, REQUEST_ID, CANONICAL_TENANT_ID, "NEW_GAME_ROW");
    FreshGameSessionTenantAssociation expected =
        new FreshGameSessionTenantAssociation(UUID.randomUUID(), 812L, evidence);
    when(sourceClient.resolveRuntimeTenantIdentity(
            CANONICAL_TENANT_ID.toString(), REQUEST_ID.toString()))
        .thenReturn(evidence);
    when(repository.registerAndReadback(evidence)).thenReturn(expected);

    assertThat(service.associate(REQUEST_ID, CANONICAL_TENANT_ID)).isEqualTo(expected);

    InOrder order = inOrder(sourceClient, repository);
    order
        .verify(sourceClient)
        .resolveRuntimeTenantIdentity(CANONICAL_TENANT_ID.toString(), REQUEST_ID.toString());
    order.verify(repository).registerAndReadback(evidence);
  }

  @Test
  void namespaceRequestTenantOrProvenanceMismatchCannotReachLocalOwnerWrite() {
    when(sourceClient.resolveRuntimeTenantIdentity(
            CANONICAL_TENANT_ID.toString(), REQUEST_ID.toString()))
        .thenReturn(evidence("other-namespace", REQUEST_ID, CANONICAL_TENANT_ID, "NEW_GAME_ROW"));
    assertMismatchedSourceIsRejected();

    when(sourceClient.resolveRuntimeTenantIdentity(
            CANONICAL_TENANT_ID.toString(), REQUEST_ID.toString()))
        .thenReturn(evidence(NAMESPACE, UUID.randomUUID(), CANONICAL_TENANT_ID, "NEW_GAME_ROW"));
    assertMismatchedSourceIsRejected();

    when(sourceClient.resolveRuntimeTenantIdentity(
            CANONICAL_TENANT_ID.toString(), REQUEST_ID.toString()))
        .thenReturn(evidence(NAMESPACE, REQUEST_ID, UUID.randomUUID(), "NEW_GAME_ROW"));
    assertMismatchedSourceIsRejected();

    when(sourceClient.resolveRuntimeTenantIdentity(
            CANONICAL_TENANT_ID.toString(), REQUEST_ID.toString()))
        .thenReturn(evidence(NAMESPACE, REQUEST_ID, CANONICAL_TENANT_ID, "RETAINED_GAME_V30"));
    assertMismatchedSourceIsRejected();

    verify(sourceClient, org.mockito.Mockito.times(4))
        .resolveRuntimeTenantIdentity(CANONICAL_TENANT_ID.toString(), REQUEST_ID.toString());
    verifyNoInteractions(repository);
  }

  @Test
  void invalidIdentityInputsDoNotCallEitherOwner() {
    assertThatThrownBy(() -> service.associate(new UUID(0L, 0L), CANONICAL_TENANT_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("associationRequestId");
    assertThatThrownBy(() -> service.associate(REQUEST_ID, new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonicalTenantId");

    verifyNoInteractions(sourceClient, repository);
  }

  @Test
  void unavailableAuthenticatedSourceReadNeverOpensLocalOwnerWrite() {
    when(sourceClient.resolveRuntimeTenantIdentity(
            CANONICAL_TENANT_ID.toString(), REQUEST_ID.toString()))
        .thenThrow(new IllegalStateException("source identity unavailable"));

    assertThatThrownBy(() -> service.associate(REQUEST_ID, CANONICAL_TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source identity unavailable");

    verify(sourceClient)
        .resolveRuntimeTenantIdentity(CANONICAL_TENANT_ID.toString(), REQUEST_ID.toString());
    verify(repository, never()).registerAndReadback(org.mockito.ArgumentMatchers.any());
  }

  private void assertMismatchedSourceIsRejected() {
    assertThatThrownBy(() -> service.associate(REQUEST_ID, CANONICAL_TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match the exact fresh request");
  }

  private static RuntimeTenantIdentityEvidence evidence(
      String namespace, UUID requestId, UUID tenantId, String provenanceKind) {
    return new RuntimeTenantIdentityEvidence(
        1, namespace, requestId, tenantId, 9_001L, "source-tenant-key-9002", provenanceKind);
  }
}
