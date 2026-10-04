package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldIntakeDigest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.InvalidIntakeEvidenceException;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.RegistrationConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
  private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
  private final GameSessionAuthoredWorldIntakeService service =
      new GameSessionAuthoredWorldIntakeService(client, repository, transactionManager, "firemud");

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsAmbientTransactionBeforeSourceOrOwnerRead() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");
    verifyNoInteractions(client, repository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void rejectsAmbientSynchronizationBeforeSourceOrOwnerRead() {
    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");
    verifyNoInteractions(client, repository);
    assertThat(transactionManager.startedWith).isNull();
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
    verifyNoInteractions(client, repository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void obtainsSourceOutsideWritableReadCommittedOwnerTransactionAndReadsCommitBack() {
    AuthoredWorldSourceEvidence source = source("firemud", TENANT, SOURCE_OPERATION, WORLD);
    IntakeReceipt receipt = receipt(source, uuid(55));
    when(repository.readByIntakeRequest("firemud", REQUEST))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return Optional.empty();
            });
    when(client.resolveAuthoredWorldSource(
            eq(TENANT.toString()), eq(SOURCE_OPERATION.toString()), eq(WORLD), anyString()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return source;
            });
    when(repository.register(REQUEST, source))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                  .isFalse();
              assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
              return receipt;
            });
    when(repository.read(receipt.operationId(), TENANT, WORLD, "firemud"))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return Optional.of(receipt);
            });

    assertThat(service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD)).isEqualTo(receipt);
    assertThat(transactionManager.startedWith.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(transactionManager.startedWith.isReadOnly()).isFalse();
    assertThat(transactionManager.startedWith.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(transactionManager.commitCount).isEqualTo(1);

    ArgumentCaptor<String> readRequest = ArgumentCaptor.forClass(String.class);
    verify(client)
        .resolveAuthoredWorldSource(
            eq(TENANT.toString()),
            eq(SOURCE_OPERATION.toString()),
            eq(WORLD),
            readRequest.capture());
    assertThat(UUID.fromString(readRequest.getValue())).isNotEqualTo(REQUEST);
    verify(repository).register(REQUEST, source);
    verify(repository).read(receipt.operationId(), TENANT, WORLD, "firemud");
  }

  @Test
  void exactStoredRetryReturnsOriginalReceiptWithoutSourceReadOrMutation() {
    AuthoredWorldSourceEvidence source = source("firemud", TENANT, SOURCE_OPERATION, WORLD);
    IntakeReceipt receipt = receipt(source, uuid(55));
    when(repository.readByIntakeRequest("firemud", REQUEST)).thenReturn(Optional.of(receipt));

    assertThat(service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD)).isEqualTo(receipt);

    verifyNoInteractions(client);
    verify(repository, never()).register(any(UUID.class), any(AuthoredWorldSourceEvidence.class));
    verify(repository, never()).read(any(UUID.class), any(UUID.class), anyString(), anyString());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void changedRetryBindingsConflictWithoutSourceReadOrMutation() {
    AuthoredWorldSourceEvidence source = source("firemud", TENANT, SOURCE_OPERATION, WORLD);
    IntakeReceipt receipt = receipt(source, uuid(55));
    when(repository.readByIntakeRequest("firemud", REQUEST)).thenReturn(Optional.of(receipt));

    List<Runnable> changedBindings =
        List.of(
            () -> service.intake(REQUEST, uuid(99), SOURCE_OPERATION, WORLD),
            () -> service.intake(REQUEST, TENANT, uuid(99), WORLD),
            () -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, "other-world"));
    for (Runnable changedBinding : changedBindings) {
      assertThatThrownBy(changedBinding::run).isInstanceOf(RegistrationConflictException.class);
    }

    verifyNoInteractions(client);
    verify(repository, never()).register(any(UUID.class), any(AuthoredWorldSourceEvidence.class));
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void coherentlyRedigestedWrongScopeCannotReachOwnerWrite() {
    when(repository.readByIntakeRequest("firemud", REQUEST)).thenReturn(Optional.empty());
    List<AuthoredWorldSourceEvidence> wrongSources =
        List.of(
            source("other", TENANT, SOURCE_OPERATION, WORLD),
            source("firemud", REQUEST, SOURCE_OPERATION, WORLD),
            source("firemud", TENANT, REQUEST, WORLD),
            source("firemud", TENANT, SOURCE_OPERATION, "other-world"));
    when(client.resolveAuthoredWorldSource(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(wrongSources.get(0))
        .thenReturn(wrongSources.get(1))
        .thenReturn(wrongSources.get(2))
        .thenReturn(wrongSources.get(3));

    for (int index = 0; index < wrongSources.size(); index++) {
      assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
          .isInstanceOf(IllegalStateException.class);
    }
    verify(repository, never()).register(any(UUID.class), any(AuthoredWorldSourceEvidence.class));
    verify(repository, never()).read(any(UUID.class), any(UUID.class), anyString(), anyString());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void unavailableOwnerCannotLeaveAnyLocalClaim() {
    when(repository.readByIntakeRequest("firemud", REQUEST)).thenReturn(Optional.empty());
    when(client.resolveAuthoredWorldSource(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(null)
        .thenThrow(new IllegalStateException("source unavailable"));

    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match intake");
    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("source unavailable");
    verify(repository, never()).register(any(UUID.class), any(AuthoredWorldSourceEvidence.class));
    verify(repository, never()).read(any(UUID.class), any(UUID.class), anyString(), anyString());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void deniesMissingPostCommitReadback() {
    AuthoredWorldSourceEvidence source = source("firemud", TENANT, SOURCE_OPERATION, WORLD);
    IntakeReceipt receipt = receipt(source, uuid(55));
    stubFreshWrite(source, receipt);
    when(repository.read(receipt.operationId(), TENANT, WORLD, "firemud"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(InvalidIntakeEvidenceException.class)
        .hasMessageContaining("missing after commit readback");
    assertThat(transactionManager.commitCount).isEqualTo(1);
  }

  @Test
  void deniesChangedPostCommitReadback() {
    AuthoredWorldSourceEvidence source = source("firemud", TENANT, SOURCE_OPERATION, WORLD);
    IntakeReceipt written = receipt(source, uuid(55));
    AuthoredWorldSourceEvidence changedSource =
        source("firemud", TENANT, SOURCE_OPERATION, WORLD, "Changed display");
    IntakeReceipt corruptReadback =
        new IntakeReceipt(
            written.operationId(),
            REQUEST,
            GameSessionAuthoredWorldIntakeDigest.requestDigest(REQUEST, changedSource),
            changedSource,
            GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                written.operationId(),
                GameSessionAuthoredWorldIntakeDigest.requestDigest(REQUEST, changedSource),
                changedSource.evidenceDigest()));
    stubFreshWrite(source, written);
    when(repository.read(written.operationId(), TENANT, WORLD, "firemud"))
        .thenReturn(Optional.of(corruptReadback));

    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(InvalidIntakeEvidenceException.class)
        .hasMessageContaining("changed during commit readback");
    assertThat(transactionManager.commitCount).isEqualTo(1);
  }

  @Test
  void registrationFailureRollsBackOwnerTransaction() {
    AuthoredWorldSourceEvidence source = source("firemud", TENANT, SOURCE_OPERATION, WORLD);
    when(repository.readByIntakeRequest("firemud", REQUEST)).thenReturn(Optional.empty());
    when(client.resolveAuthoredWorldSource(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(source);
    when(repository.register(REQUEST, source)).thenThrow(new IllegalStateException("write failed"));

    assertThatThrownBy(() -> service.intake(REQUEST, TENANT, SOURCE_OPERATION, WORLD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("write failed");

    assertThat(transactionManager.rollbackCount).isEqualTo(1);
    assertThat(transactionManager.commitCount).isZero();
  }

  private void stubFreshWrite(AuthoredWorldSourceEvidence source, IntakeReceipt receipt) {
    when(repository.readByIntakeRequest("firemud", REQUEST)).thenReturn(Optional.empty());
    when(client.resolveAuthoredWorldSource(
            eq(TENANT.toString()), eq(SOURCE_OPERATION.toString()), eq(WORLD), anyString()))
        .thenReturn(source);
    when(repository.register(REQUEST, source)).thenReturn(receipt);
  }

  private static AuthoredWorldSourceEvidence source(
      String namespace, UUID tenant, UUID operation, String world) {
    return source(namespace, tenant, operation, world, "Café 🐉");
  }

  private static AuthoredWorldSourceEvidence source(
      String namespace, UUID tenant, UUID operation, String world, String displayName) {
    UUID registration = uuid(11);
    String request =
        AuthoredWorldSourceDigest.requestDigest(
            namespace, registration, tenant, "north-star", world, displayName);
    String evidence =
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            registration,
            operation,
            request,
            tenant,
            "north-star",
            world,
            displayName,
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
        displayName,
        42L,
        "legacy-game-tenant-42",
        "RETAINED_GAME_V30",
        evidence);
  }

  private static IntakeReceipt receipt(AuthoredWorldSourceEvidence source, UUID operationId) {
    String requestDigest = GameSessionAuthoredWorldIntakeDigest.requestDigest(REQUEST, source);
    return new IntakeReceipt(
        operationId,
        REQUEST,
        requestDigest,
        source,
        GameSessionAuthoredWorldIntakeDigest.receiptDigest(
            operationId, requestDigest, source.evidenceDigest()));
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private TransactionDefinition startedWith;
    private int commitCount;
    private int rollbackCount;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      startedWith = definition;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          definition.getIsolationLevel());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commitCount++;
      TransactionSynchronizationManager.clear();
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbackCount++;
      TransactionSynchronizationManager.clear();
    }
  }
}
