package net.firedevops.firemud.accountservice.entity;

/** Account-owned profile visibility policy, including the retained legacy database value. */
public enum ProfilePresenceVisibilityPolicy {
  PUBLIC,
  FRIENDS_ONLY,
  PRIVATE,
  HIDDEN_STAFF;

  /** Returns the value exposed in Account profile responses without rewriting persisted state. */
  public static ProfilePresenceVisibilityPolicy toProfileResponseValue(
      ProfilePresenceVisibilityPolicy value) {
    return value == null || value == HIDDEN_STAFF ? PRIVATE : value;
  }

  /** Returns whether an account holder may select this policy through profile updates. */
  public boolean selectableByAccountHolder() {
    return this != HIDDEN_STAFF;
  }

  /** Rejects legacy values that account holders may no longer select. */
  public void requireSelectableByAccountHolder() {
    if (!selectableByAccountHolder()) {
      throw new IllegalArgumentException("Profile presence visibility policy is not selectable");
    }
  }
}
