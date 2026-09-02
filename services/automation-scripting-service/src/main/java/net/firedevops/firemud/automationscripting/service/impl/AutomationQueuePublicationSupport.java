package net.firedevops.firemud.automationscripting.service.impl;

import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes rebuildable queue pointers only after the owning durable transaction commits. */
final class AutomationQueuePublicationSupport {
  private AutomationQueuePublicationSupport() {}

  static void enqueueAfterCommit(
      AutomationQueueService automationQueueService, ScriptWorkItem workItem, Logger logger) {
    Runnable publication =
        () -> {
          if (automationQueueService == null) {
            return;
          }
          try {
            automationQueueService.enqueueWorkItem(workItem);
          } catch (RuntimeException ex) {
            logger.warn(
                "Automation queue pointer publication failed for work item {}; durable pending state remains rebuildable",
                workItem.getId(),
                ex);
          }
        };
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      publication.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            publication.run();
          }
        });
  }
}
