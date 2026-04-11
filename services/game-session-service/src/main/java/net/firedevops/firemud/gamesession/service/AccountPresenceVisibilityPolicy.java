package net.firedevops.firemud.gamesession.service;

/** Explicit account-scoped social presence visibility policy for friend/social consumers. */
public enum AccountPresenceVisibilityPolicy {
  PUBLIC,
  FRIENDS_ONLY,
  PRIVATE,
  HIDDEN_STAFF
}
