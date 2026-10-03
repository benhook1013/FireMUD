package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;

/** Bounded recent-presence facts retained after a gameplay presence disconnects. */
public record AccountRecentPresenceState(
    long tenantId,
    String accountId,
    Long gameInstanceId,
    String playableStateScope,
    String worldSlug,
    String realmSlug,
    Long pointerVersion,
    long lastSeenAtEpochMs,
    AccountRecentPresenceDisposition disposition)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public AccountRecentPresenceState(
      long tenantId,
      String accountId,
      long lastSeenAtEpochMs,
      AccountRecentPresenceDisposition disposition) {
    this(tenantId, accountId, null, null, null, null, null, lastSeenAtEpochMs, disposition);
  }
}
