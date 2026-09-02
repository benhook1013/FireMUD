package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AutomationQueuePublicationSupportTest {
  private final AutomationQueueService automationQueueService =
      Mockito.mock(AutomationQueueService.class);
  private final Logger logger = Mockito.mock(Logger.class);
  private final ScriptWorkItem workItem = new ScriptWorkItem();

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void doesNotPublishBeforeCommitAndPublishesAfterCommit() {
    TransactionSynchronizationManager.initSynchronization();
    AutomationQueuePublicationSupport.enqueueAfterCommit(automationQueueService, workItem, logger);

    verify(automationQueueService, never()).enqueueWorkItem(workItem);
    TransactionSynchronization synchronization =
        TransactionSynchronizationManager.getSynchronizations().getFirst();
    synchronization.afterCommit();

    verify(automationQueueService).enqueueWorkItem(workItem);
  }

  @Test
  void rollbackDoesNotPublish() {
    TransactionSynchronizationManager.initSynchronization();
    AutomationQueuePublicationSupport.enqueueAfterCommit(automationQueueService, workItem, logger);

    TransactionSynchronizationManager.getSynchronizations()
        .getFirst()
        .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

    verify(automationQueueService, never()).enqueueWorkItem(workItem);
  }

  @Test
  void publicationFailureDoesNotEscapeAfterCommitCallback() {
    RuntimeException failure = new IllegalStateException("redis unavailable");
    doThrow(failure).when(automationQueueService).enqueueWorkItem(workItem);
    TransactionSynchronizationManager.initSynchronization();
    AutomationQueuePublicationSupport.enqueueAfterCommit(automationQueueService, workItem, logger);

    assertThatCode(
            () -> TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit())
        .doesNotThrowAnyException();
    verify(automationQueueService).enqueueWorkItem(workItem);
    verify(logger)
        .warn(
            eq(
                "Automation queue pointer publication failed for work item {}; durable pending state remains rebuildable"),
            isNull(),
            same(failure));
  }

  @Test
  void publishesImmediatelyWhenNoTransactionSynchronizationIsActive() {
    AutomationQueuePublicationSupport.enqueueAfterCommit(automationQueueService, workItem, logger);

    verify(automationQueueService).enqueueWorkItem(workItem);
  }
}
