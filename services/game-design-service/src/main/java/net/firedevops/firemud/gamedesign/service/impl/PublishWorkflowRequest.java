package net.firedevops.firemud.gamedesign.service.impl;

record PublishWorkflowRequest(
    String tenantId, String notes, String publishRequestId, String publishWorkflowId) {

  /**
   * Recovers the request id for the legacy command shape that carried only the workflow id.
   *
   * <p>Recovery is deliberately limited to the exact canonical full-publication prefix and one
   * non-empty final segment. The normal publication-identity validation still owns the recovered
   * value, and explicit (including blank) request ids are never rewritten.
   */
  PublishWorkflowRequest recoverMissingPublishRequestId() {
    if (publishRequestId != null || tenantId == null || publishWorkflowId == null) {
      return this;
    }
    String canonicalPrefix = "publish:" + tenantId + ":publish-request:";
    if (!publishWorkflowId.startsWith(canonicalPrefix)) {
      return this;
    }
    String recoveredRequestId = publishWorkflowId.substring(canonicalPrefix.length());
    if (recoveredRequestId.isBlank() || recoveredRequestId.indexOf(':') >= 0) {
      return this;
    }
    if (!publishWorkflowId.equals(
        TemporalVersionPublishOrchestrator.workflowId(tenantId, recoveredRequestId))) {
      return this;
    }
    return new PublishWorkflowRequest(tenantId, notes, recoveredRequestId, publishWorkflowId);
  }
}
