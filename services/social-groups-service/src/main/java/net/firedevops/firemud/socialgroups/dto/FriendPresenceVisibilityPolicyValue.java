package net.firedevops.firemud.socialgroups.dto;

public enum FriendPresenceVisibilityPolicyValue {
  PUBLIC,
  FRIENDS_ONLY,
  PRIVATE,
  HIDDEN_STAFF;

  public boolean selectableByAccountHolder() {
    return this != HIDDEN_STAFF;
  }
}
