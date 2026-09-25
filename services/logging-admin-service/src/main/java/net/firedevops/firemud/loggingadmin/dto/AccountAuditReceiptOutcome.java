package net.firedevops.firemud.loggingadmin.dto;

public enum AccountAuditReceiptOutcome {
  ACCEPTED,
  DUPLICATE,
  NON_REPLAYABLE,
  IDEMPOTENCY_CONFLICT
}
