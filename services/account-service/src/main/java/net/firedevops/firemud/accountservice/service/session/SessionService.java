package net.firedevops.firemud.accountservice.service.session;

import java.util.Optional;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import net.firedevops.firemud.accountservice.dto.PublicProductionMembershipResult;

public interface SessionService {
  record ConnectTokenReplay(
      boolean success, ConnectTokenResult result, String errorCode, String errorMessage) {}

  record PublicProductionMembershipReplay(
      boolean success,
      PublicProductionMembershipResult result,
      String errorCode,
      String errorMessage) {}

  void storeSession(Long tenantId, Long accountId, String token);

  void storeSession(Long tenantId, Long accountId, String token, long expirationMs);

  void storeAccountSession(Long accountId, String token, long expirationMs);

  Long getAccountId(Long tenantId, String token);

  boolean isAccountSessionActive(Long accountId, String token);

  Optional<ConnectTokenReplay> getConnectTokenReplay(
      Long tenantId, Long accountId, String connectScopeId, String requestId);

  void storeConnectTokenReplay(
      Long tenantId,
      Long accountId,
      String connectScopeId,
      String requestId,
      ConnectTokenReplay replay,
      long expirationMs);

  Optional<PublicProductionMembershipReplay> getPublicProductionMembershipReplay(
      Long tenantId, Long accountId, String worldSlug, String realmSlug, String requestId);

  void storePublicProductionMembershipReplay(
      Long tenantId,
      Long accountId,
      String worldSlug,
      String realmSlug,
      String requestId,
      PublicProductionMembershipReplay replay,
      long expirationMs);
}
