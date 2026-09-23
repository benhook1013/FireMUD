package net.firedevops.firemud.loggingadmin.service;

public class AuditReceiptNotFoundException extends RuntimeException {
  public AuditReceiptNotFoundException() {
    super("Account audit receipt not found");
  }
}
