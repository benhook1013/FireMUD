package net.firedevops.firemud.accountservice.entity;

import java.util.Locale;

public enum AccountLifecycleState {
  ACTIVE,
  SECURITY_LOCKED,
  DEACTIVATED_PENDING_DELETE,
  DELETED;

  public String storageValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static AccountLifecycleState fromStorageValue(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Account lifecycle state is required");
    }
    return valueOf(value.trim().toUpperCase(Locale.ROOT));
  }
}
