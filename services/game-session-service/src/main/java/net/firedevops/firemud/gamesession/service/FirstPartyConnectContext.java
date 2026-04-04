package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;

public record FirstPartyConnectContext(
    long accountId,
    long tenantId,
    long gameInstanceId,
    String connectTokenJti,
    String gatewayRequestId)
    implements Serializable {
  private static final long serialVersionUID = 1L;
}
