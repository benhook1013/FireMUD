package net.firedevops.firemud.loggingadmin.dto;

public enum AccountAuditScope {
  PLATFORM("platform"),
  TENANT("tenant");

  private final String databaseValue;

  AccountAuditScope(String databaseValue) {
    this.databaseValue = databaseValue;
  }

  public String databaseValue() {
    return databaseValue;
  }
}
