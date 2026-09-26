package net.firedevops.firemud.accountservice.entity;

public enum AccountIdentityProvenance {
  ACCOUNT_V29_MIGRATION,
  ACCOUNT_REPOSITORY_INSERT,
  ACCOUNT_DATABASE_INSERT;

  public static AccountIdentityProvenance fromStorageValue(String value) {
    if (value == null) {
      throw new IllegalStateException("Account UUID provenance must not be null");
    }
    try {
      return AccountIdentityProvenance.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Unknown Account UUID provenance: " + value, exception);
    }
  }
}
