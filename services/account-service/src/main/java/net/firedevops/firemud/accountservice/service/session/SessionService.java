package net.firedevops.firemud.accountservice.service.session;

import java.util.Optional;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;

public interface SessionService {
  record ConnectTokenReplay(
      boolean success, ConnectTokenResult result, String errorCode, String errorMessage) {}

  void storeSession(Long tenantId, Long accountId, String token);

  void storeSession(Long tenantId, Long accountId, String token, long expirationMs);

  Long getAccountId(Long tenantId, String token);

  Optional<ConnectTokenReplay> getConnectTokenReplay(
      Long tenantId, Long accountId, String connectScopeId, String requestId);

  void storeConnectTokenReplay(
      Long tenantId,
      Long accountId,
      String connectScopeId,
      String requestId,
      ConnectTokenReplay replay,
      long expirationMs);
}
