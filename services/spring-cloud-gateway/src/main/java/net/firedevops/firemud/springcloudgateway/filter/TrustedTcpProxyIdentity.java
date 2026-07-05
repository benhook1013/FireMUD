package net.firedevops.firemud.springcloudgateway.filter;

import net.firedevops.firemud.common.security.JwtClaims;
import org.springframework.util.StringUtils;

final class TrustedTcpProxyIdentity {

  private static final String TENANT_ID_HEADER = "X-Tenant-Id";
  private static final String GAME_INSTANCE_ID_HEADER = "X-Game-Instance-Id";

  private TrustedTcpProxyIdentity() {}

  static void validateIncoming(String tenantId, String gameInstanceId) {
    boolean hasTenantId = StringUtils.hasText(tenantId);
    boolean hasGameInstanceId = StringUtils.hasText(gameInstanceId);
    if (!hasTenantId && !hasGameInstanceId) {
      return;
    }
    if (!hasTenantId || !hasGameInstanceId) {
      throw new IllegalArgumentException("Malformed trusted proxy identity bundle");
    }
    JwtClaims.requireLong(tenantId, TENANT_ID_HEADER, false);
    JwtClaims.requireLong(gameInstanceId, GAME_INSTANCE_ID_HEADER, false);
  }
}
