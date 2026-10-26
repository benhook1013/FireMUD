package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.lettuce.core.RedisClient;
import java.math.BigInteger;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalGameplayIssuerBootstrapAmbientTransactionTest {
  @Test
  void coverageOwnerRejectsBothAmbientStatesBeforeReadingCurrentSourceOrUsingSql() {
    DSLContext dsl = mock(DSLContext.class);
    var partitionRedisOwner = mock(CanonicalGameplayIssuerBootstrapPartitionRedisOwner.class);
    var currentStateSource =
        mock(CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource.class);
    var owner =
        new CanonicalGameplayIssuerBootstrapCoverageOwner(
            dsl, partitionRedisOwner, currentStateSource);
    UUID operationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();

    assertRefusesInBothAmbientStates(
        () -> owner.completeInitialBootstrap(operationId, requestId), "ambient SQL transaction");
    assertRefusesInBothAmbientStates(
        () -> owner.verifyInstalledPhysicalCoverage(operationId, requestId),
        "ambient SQL transaction");

    verifyNoInteractions(dsl, partitionRedisOwner, currentStateSource);
  }

  @Test
  void coverageRepositoryRejectsBothAmbientStatesBeforeSql() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new CanonicalGameplayIssuerBootstrapCoverageRepository(dsl);
    var currentState =
        mock(CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState.class);
    UUID operationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();

    assertRefusesInBothAmbientStates(
        () -> repository.readInstalledAnchor(operationId, requestId, currentState),
        "ambient SQL transaction");
    assertRefusesInBothAmbientStates(
        () -> repository.installInitialAnchor(operationId, requestId, currentState),
        "ambient SQL transaction");

    verifyNoInteractions(dsl, currentState);
  }

  @Test
  void memberOperationRepositoryRejectsBothAmbientStatesBeforeSql() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new CanonicalGameplayIssuerBootstrapMemberOperationRepository(dsl);
    var currentCheckpoint =
        mock(CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint.class);
    var incarnation =
        mock(
            CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation
                .class);
    UUID operationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();

    assertRefusesInBothAmbientStates(
        () ->
            repository.beginOrResumePendingPhysicalProof(
                operationId, requestId, 0, currentCheckpoint, incarnation),
        "ambient transaction");
    assertRefusesInBothAmbientStates(
        () ->
            repository.preparePartitionWork(
                operationId, requestId, BigInteger.ZERO, currentCheckpoint, incarnation),
        "ambient transaction");

    verifyNoInteractions(dsl, currentCheckpoint, incarnation);
  }

  @Test
  void partitionRedisOwnerRejectsBothAmbientStatesBeforeRedisOrCurrentSource() {
    var redisClient = mock(RedisClient.class);
    var catalog = mock(RedisScriptCatalog.class);
    var currentStateSource =
        mock(CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource.class);
    var owner =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner(
            redisClient, catalog, currentStateSource);
    var work = mock(CanonicalGameplayIssuerBootstrapPartitionWork.class);

    assertRefusesInBothAmbientStates(
        () -> owner.buildAndRead(work), "partition projection is unavailable");
    assertRefusesInBothAmbientStates(
        () -> owner.readExistingAndRead(work), "partition projection is unavailable");

    verifyNoInteractions(redisClient, catalog, currentStateSource, work);
  }

  private static void assertRefusesInBothAmbientStates(Runnable invocation, String message) {
    assertRefuses(invocation, message, false);
    assertRefuses(invocation, message, true);
  }

  private static void assertRefuses(
      Runnable invocation, String message, boolean synchronizationOnly) {
    try {
      if (synchronizationOnly) {
        TransactionSynchronizationManager.initSynchronization();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      } else {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      }
      assertThatThrownBy(invocation::run)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(message);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }
}
