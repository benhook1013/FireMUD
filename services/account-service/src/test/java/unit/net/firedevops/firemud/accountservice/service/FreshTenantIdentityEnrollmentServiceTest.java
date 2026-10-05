package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.accountservice.client.GameDesignFreshTenantIdentityClient;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.FreshTenantIdentityEnrollmentService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class FreshTenantIdentityEnrollmentServiceTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void ownerTransportFailureTouchesNoAccountRepositoryOrGenerationState() {
    GameDesignFreshTenantIdentityClient client = mock(GameDesignFreshTenantIdentityClient.class);
    FreshTenantIdentityAssociationRepository associations =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    TransactionTemplate transaction = mock(TransactionTemplate.class);
    RuntimeException unavailable = new IllegalStateException("Game Design unavailable");
    when(client.resolveCreation(REQUEST_ID, REQUEST_DIGEST)).thenThrow(unavailable);

    FreshTenantIdentityEnrollmentService service =
        new FreshTenantIdentityEnrollmentService(client, associations, generations, transaction);

    assertThatThrownBy(() -> service.enroll(REQUEST_ID, REQUEST_DIGEST)).isSameAs(unavailable);

    verify(client).resolveCreation(REQUEST_ID, REQUEST_DIGEST);
    verifyNoInteractions(associations, generations, transaction);
  }

  @Test
  void ownerOperationMismatchIsRejectedBeforeOpeningAccountTransaction() {
    GameDesignFreshTenantIdentityClient client = mock(GameDesignFreshTenantIdentityClient.class);
    FreshTenantIdentityAssociationRepository associations =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    TransactionTemplate transaction = mock(TransactionTemplate.class);
    net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence evidence =
        evidence(UUID.fromString("22222222-2222-4222-8222-222222222222"), REQUEST_DIGEST);
    when(client.resolveCreation(REQUEST_ID, REQUEST_DIGEST)).thenReturn(evidence);

    FreshTenantIdentityEnrollmentService service =
        new FreshTenantIdentityEnrollmentService(client, associations, generations, transaction);

    assertThatThrownBy(() -> service.enroll(REQUEST_ID, REQUEST_DIGEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from the requested operation");

    verify(client).resolveCreation(REQUEST_ID, REQUEST_DIGEST);
    verifyNoInteractions(associations, generations, transaction);
  }

  private static net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence evidence(
      UUID creationRequestId, String requestDigest) {
    UUID operationId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    UUID canonicalTenantId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    String sourceGameTenantKey = "game-tenant-key";
    String evidenceDigest =
        net.firedevops.firemud.common.tenant.GameTenantCreationDigest.evidenceDigest(
            "account-service",
            creationRequestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            17L,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence(
        1,
        "account-service",
        creationRequestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        17L,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }
}
