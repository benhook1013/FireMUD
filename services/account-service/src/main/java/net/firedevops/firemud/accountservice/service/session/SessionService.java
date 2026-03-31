package net.firedevops.firemud.accountservice.service.session;

public interface SessionService {
  void storeSession(Long tenantId, Long accountId, String token);

  void storeSession(Long tenantId, Long accountId, String token, long expirationMs);

  Long getAccountId(Long tenantId, String token);
}
