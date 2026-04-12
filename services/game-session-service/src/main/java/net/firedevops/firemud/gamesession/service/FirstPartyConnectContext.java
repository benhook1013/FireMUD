package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;

public record FirstPartyConnectContext(
    long accountId,
    long tenantId,
    String worldSlug,
    String realmSlug,
    long gameInstanceId,
    String connectScopeId,
    String connectTokenJti,
    String connectRequestId,
    String gatewayRequestId)
    implements Serializable {
  private static final long serialVersionUID = 1L;
}
