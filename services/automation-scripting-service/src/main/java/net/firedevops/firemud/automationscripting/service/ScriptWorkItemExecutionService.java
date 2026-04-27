package net.firedevops.firemud.automationscripting.service;

public interface ScriptWorkItemExecutionService {
  ExecutionBatchResult processPendingWorkItems(int maxItems);

  record ExecutionBatchResult(int claimedCount, int completedCount, int failedCount) {
    public int terminalCount() {
      return completedCount + failedCount;
    }
  }
}
