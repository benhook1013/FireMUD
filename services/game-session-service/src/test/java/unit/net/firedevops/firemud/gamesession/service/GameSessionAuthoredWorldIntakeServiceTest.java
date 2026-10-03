package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldIntakeDigest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionAuthoredWorldIntakeServiceTest {
  private static final UUID REQUEST = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String WORLD = "violet-wilds";
  private final GameDesignRuntimeTenantIdentityClient client =
      mock(GameDesignRuntimeTenantIdentityClient.class);
  private final GameSessionAuthoredWorldSourceRepository repository =
      mock(GameSessionAuthoredWorldSourceRepository.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final GameSessionAuthoredWorldIntakeService service =
      new GameSessionAuthoredWorldIntakeService(client, repository, transactionManager, "firemud");

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsInvalidSelectorsBeforeSourceReadOrPersistence() {
    assertThatThrownBy(() -> service.intake(null, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, new UUID(0, 0), WORLD))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.intake(REQUEST, new UUID(0, 0), SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, "Bad-Slug"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(client, repository, transactionManager);
  }

  @Test
  void persistsOnlyTheAuthenticatedExactSourceAndKeepsReadIdentitySeparate() {
    var source = source("firemud", TENANT, SOURCE_OPERATION, WORLD);
    var localOperation = UUID.fromString("55555555-5555-4555-8555-555555555555");
    var requestDigest = GameSessionAuthoredWorldIntakeDigest.requestDigest(REQUEST, source);
    var receipt =
        new IntakeReceipt(
            localOperation,
            REQUEST,
            requestDigest,
            source,
            GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                localOperation, requestDigest, source.evidenceDigest()));
    when(client.resolveAuthoredWorldSource(
            eq(TENANT.toString()), eq(SOURCE_OPERATION.toString()), eq(WORLD), anyString()))
        .thenReturn(source);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return new SimpleTransactionStatus();
            });
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactionManager)
        .commit(any(TransactionStatus.class));
    when(repository.register(REQUEST, source)).thenReturn(receipt);

    assertThat(service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD)).isEqualTo(receipt);
    InOrder ordered = inOrder(client, transactionManager, repository);
    ordered
        .verify(client)
        .resolveAuthoredWorldSource(
            eq(TENANT.toString()), eq(SOURCE_OPERATION.toString()), eq(WORLD), anyString());
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    ordered.verify(transactionManager).getTransaction(definition.capture());
    ordered.verify(repository).register(REQUEST, source);
    ordered.verify(transactionManager).commit(any(TransactionStatus.class));
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var readRequest = ArgumentCaptor.forClass(String.class);
    verify(client)
        .resolveAuthoredWorldSource(
            eq(TENANT.toString()),
            eq(SOURCE_OPERATION.toString()),
            eq(WORLD),
            readRequest.capture());
    assertThat(UUID.fromString(readRequest.getValue()))
        .isNotEqualTo(new UUID(0, 0))
        .isNotEqualTo(REQUEST);
  }

  @Test
  void coherentlyRedigestedWrongScopeCannotReachPersistence() {
    var wrongSources =
        java.util.List.of(
            source("other", TENANT, SOURCE_OPERATION, WORLD),
            source("firemud", REQUEST, SOURCE_OPERATION, WORLD),
            source("firemud", TENANT, REQUEST, WORLD),
            source("firemud", TENANT, SOURCE_OPERATION, "other-world"));
    for (var wrong : wrongSources) {
      when(client.resolveAuthoredWorldSource(anyString(), anyString(), anyString(), anyString()))
          .thenReturn(wrong);
      assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
          .isInstanceOf(IllegalStateException.class);
    }
    verifyNoInteractions(repository, transactionManager);
  }

  @Test
  void absentOrUnavailableOwnerCannotLeaveAnyLocalClaim() {
    when(client.resolveAuthoredWorldSource(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(null);
    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalStateException.class);
    when(client.resolveAuthoredWorldSource(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("source unavailable"));
    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("source unavailable");
    verifyNoInteractions(repository, transactionManager);
  }

  @Test
  void sourceFetchRunsOutsideTransactionAndRegistrationRunsInside() {
    var source = source("firemud", TENANT, SOURCE_OPERATION, WORLD);
    var receipt = mock(IntakeReceipt.class);
    when(client.resolveAuthoredWorldSource(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return source;
            });
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return new SimpleTransactionStatus();
            });
    when(repository.register(REQUEST, source))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return receipt;
            });
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactionManager)
        .commit(any(TransactionStatus.class));

    assertThat(service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD)).isSameAs(receipt);
  }

  @Test
  void registrationFailureRollsBackOwnerTransaction() {
    var source = source("firemud", TENANT, SOURCE_OPERATION, WORLD);
    when(client.resolveAuthoredWorldSource(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(source);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return new SimpleTransactionStatus();
            });
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactionManager)
        .rollback(any(TransactionStatus.class));
    when(repository.register(REQUEST, source)).thenThrow(new IllegalStateException("write failed"));

    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("write failed");

    verify(transactionManager).rollback(any(TransactionStatus.class));
    verify(transactionManager, org.mockito.Mockito.never()).commit(any(TransactionStatus.class));
  }

  @Test
  void activeCallerTransactionIsRejectedBeforeSourceReadOrOwnerTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an owner transaction");

    verifyNoInteractions(client, repository, transactionManager);
  }

  private static AuthoredWorldSourceEvidence source(
      String namespace, UUID tenant, UUID operation, String world) {
    var registration = UUID.fromString("11111111-1111-4111-8111-111111111111");
    String request =
        AuthoredWorldSourceDigest.requestDigest(
            namespace, registration, tenant, "north-star", world, "Café 🐉");
    String evidence =
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            registration,
            operation,
            request,
            tenant,
            "north-star",
            world,
            "Café 🐉",
            42L,
            "legacy-game-tenant-42",
            "RETAINED_GAME_V30");
    return new AuthoredWorldSourceEvidence(
        1,
        namespace,
        registration,
        operation,
        request,
        tenant,
        "north-star",
        world,
        "Café 🐉",
        42L,
        "legacy-game-tenant-42",
        "RETAINED_GAME_V30",
        evidence);
  }
}
