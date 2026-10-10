package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.client.GameDesignFreshTenantCreationClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionFreshTenantAssociationRepositoryTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void rejectsInvalidSelectorsBeforeCallingTheRemoteOwnerOrOpeningSql() {
    GameDesignFreshTenantCreationClient gameDesignClient =
        mock(GameDesignFreshTenantCreationClient.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    GameSessionFreshTenantAssociationRepository repository =
        new GameSessionFreshTenantAssociationRepository(
            mock(DSLContext.class), transactionManager, gameDesignClient, "test");

    assertThatThrownBy(() -> repository.associateFreshTenant(null, REQUEST_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.associateFreshTenant(REQUEST_ID, "invalid"))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(gameDesignClient, transactionManager);
  }

  @Test
  void resolvesSourceOnlyOutsideAnExistingGameSessionTransaction() {
    GameDesignFreshTenantCreationClient gameDesignClient =
        mock(GameDesignFreshTenantCreationClient.class);
    GameSessionFreshTenantAssociationRepository repository =
        new GameSessionFreshTenantAssociationRepository(
            mock(DSLContext.class),
            mock(PlatformTransactionManager.class),
            gameDesignClient,
            "test");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> repository.associateFreshTenant(REQUEST_ID, REQUEST_DIGEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("before opening a Game Session transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(gameDesignClient);
  }

  @Test
  void refusesOwnerReceiptThatDoesNotMatchTheExactRequestedNamespaceAndDigest() {
    GameDesignFreshTenantCreationClient gameDesignClient =
        mock(GameDesignFreshTenantCreationClient.class);
    when(gameDesignClient.resolveCreation(REQUEST_ID, REQUEST_DIGEST))
        .thenReturn(evidence("other", REQUEST_ID, REQUEST_DIGEST, SOURCE_OPERATION_ID));
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    GameSessionFreshTenantAssociationRepository repository =
        new GameSessionFreshTenantAssociationRepository(
            mock(DSLContext.class), transactionManager, gameDesignClient, "test");

    assertThatThrownBy(() -> repository.associateFreshTenant(REQUEST_ID, REQUEST_DIGEST))
        .isInstanceOf(
            GameSessionFreshTenantAssociationRepository.InvalidFreshTenantAssociationException
                .class)
        .hasMessageContaining("exact Game Session request");

    verify(gameDesignClient).resolveCreation(REQUEST_ID, REQUEST_DIGEST);
    verifyNoInteractions(transactionManager);
  }

  private static FreshTenantCreationEvidence evidence(
      String namespace, UUID requestId, String requestDigest, UUID operationId) {
    String sourceKey = "owner-key";
    long sourceRowId = 19L;
    return new FreshTenantCreationEvidence(
        1,
        namespace,
        requestId,
        operationId,
        requestDigest,
        CANONICAL_TENANT_ID,
        sourceRowId,
        sourceKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            namespace,
            requestId,
            operationId,
            requestDigest,
            CANONICAL_TENANT_ID,
            sourceRowId,
            sourceKey,
            "NEW_GAME_ROW"));
  }
}
