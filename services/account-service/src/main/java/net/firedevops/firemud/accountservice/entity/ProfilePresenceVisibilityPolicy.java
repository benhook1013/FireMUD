package net.firedevops.firemud.accountservice.entity;

/** Account-owned social-presence visibility policy for cross-game friend activity surfaces. */
public enum ProfilePresenceVisibilityPolicy {
  PUBLIC,
  FRIENDS_ONLY,
  PRIVATE,
  HIDDEN_STAFF;

  /** Returns whether an account holder may select this policy through profile updates. */
  public boolean selectableByAccountHolder() {
    return this != HIDDEN_STAFF;
  }

  /** Rejects policy values reserved for the staff-visibility owner. */
  public void requireSelectableByAccountHolder() {
    if (!selectableByAccountHolder()) {
      throw new IllegalArgumentException(
          "Profile presence visibility policy HIDDEN_STAFF is reserved");
    }
  }
}
