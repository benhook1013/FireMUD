package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;

/** Bounded recent-presence facts retained after a gameplay presence disconnects. */
public record AccountRecentPresenceState(
    long tenantId,
    long accountId,
    long lastSeenAtEpochMs,
    AccountPresenceVisibilityPolicy visibilityPolicy)
    implements Serializable {
  private static final long serialVersionUID = 1L;
}
