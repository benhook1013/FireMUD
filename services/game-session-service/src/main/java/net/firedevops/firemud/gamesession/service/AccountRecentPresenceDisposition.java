package net.firedevops.firemud.gamesession.service;

/** Canonical cause of the most recent transition from live gameplay presence to recent presence. */
public enum AccountRecentPresenceDisposition {
  TRANSPORT_LOSS,
  LOGOUT,
  TAKEOVER
}
