package net.firedevops.firemud.gamedesign.service.impl;

record PublishWorkflowSnapshot(
    long versionId,
    int versionNumber,
    String publishWorkflowId,
    String status,
    String failureCode,
    String failureMessage) {
  boolean isTerminal() {
    return "SUCCEEDED".equals(status) || "FAILED".equals(status);
  }

  boolean isSucceeded() {
    return "SUCCEEDED".equals(status);
  }
}
