package net.firedevops.firemud.gamedesign.service;

public final class PublishAttemptPendingReconciliationException extends IllegalStateException {
  public static final String ERROR_CODE = "PUBLISH_ATTEMPT_PENDING_RECONCILIATION_REQUIRED";
  public static final String SAFE_MESSAGE =
      "Publication outcome is pending reconciliation. Retry with the same publish request ID.";

  public PublishAttemptPendingReconciliationException() {
    super(SAFE_MESSAGE);
  }

  public String errorCode() {
    return ERROR_CODE;
  }
}
