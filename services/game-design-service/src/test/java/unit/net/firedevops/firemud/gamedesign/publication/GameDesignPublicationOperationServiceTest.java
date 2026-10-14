package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameDesignPublicationOperationServiceTest {
  @Test
  void scopeReadUsesIndependentRepeatableReadAndRejectsChangedScope() throws Exception {
    var repository = mock(GameDesignPublicationOperationRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var service = new GameDesignPublicationOperationService(repository, transactions);
    var operation =
        IsolatedPublicationOperationFixtures.fresh(
            new TargetProof(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                19L,
                "tenant-key",
                42L,
                "tenant-key",
                "NEW_GAME_ROW"));
    var retained =
        new GameDesignPublicationOperationRepository.Readback(operation, "PENDING", null, null);
    when(repository.read(operation.workflowId())).thenReturn(Optional.of(retained));
    assertThat(
            service.readExact(
                operation.tenantKey(),
                operation.workflowId(),
                operation.versionId(),
                operation.selectionDigest()))
        .contains(retained);
    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThat(definition.getValue().isReadOnly()).isTrue();
    assertThatThrownBy(
            () ->
                service.readExact(
                    "changed",
                    operation.workflowId(),
                    operation.versionId(),
                    operation.selectionDigest()))
        .hasMessage("PUBLICATION_OPERATION_IDENTITY_CONFLICT");
  }

  @Test
  void ambientActualOrSynchronizationOnlyRejectsEveryEntryBeforeStorage() {
    var repository = mock(GameDesignPublicationOperationRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    var service = new GameDesignPublicationOperationService(repository, transactions);
    for (boolean actual : new boolean[] {true, false}) {
      try {
        if (actual) TransactionSynchronizationManager.setActualTransactionActive(true);
        else TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(
                () -> service.readExact("tenant", "workflow", 1, "sha256:" + "a".repeat(64)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.readExact((GameDesignPublicationOperation) null))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.retainVerifiedOperation(null))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
    verifyNoInteractions(repository, transactions);
  }
}
