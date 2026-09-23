package net.firedevops.firemud.loggingadmin.service;

public class AuditStorageUnavailableException extends RuntimeException {
  public AuditStorageUnavailableException(Throwable cause) {
    super("Account audit receipt storage is unavailable", cause);
  }
}
