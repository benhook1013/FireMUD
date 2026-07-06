package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;
import org.springframework.util.StringUtils;

public record FirstPartyConnectContext(
    long accountId,
    long tenantId,
    String worldSlug,
    String realmSlug,
    long gameInstanceId,
    long pointerVersion,
    String connectScopeId,
    String connectTokenJti,
    String connectRequestId,
    String gatewayRequestId)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public boolean hasCompleteRoutingScope() {
    return accountId > 0L
        && tenantId > 0L
        && gameInstanceId > 0L
        && pointerVersion > 0L
        && StringUtils.hasText(worldSlug)
        && StringUtils.hasText(realmSlug)
        && StringUtils.hasText(connectScopeId)
        && StringUtils.hasText(connectRequestId);
  }
}
