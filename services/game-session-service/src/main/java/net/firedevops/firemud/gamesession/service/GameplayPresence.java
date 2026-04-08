package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;

/** Local gameplay presence record used by the first WHO implementation. */
public record GameplayPresence(
    long sessionId,
    long tenantId,
    long gameInstanceId,
    long accountId,
    long characterId,
    String characterName,
    GameplayPresenceRole role)
    implements Serializable {
  private static final long serialVersionUID = 1L;
}
