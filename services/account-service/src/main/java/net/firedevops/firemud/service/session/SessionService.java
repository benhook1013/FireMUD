package net.firedevops.firemud.service.session;

public interface SessionService {
  void storeSession(Long tenantId, Long accountId, String token);

  Long getAccountId(Long tenantId, String token);
}
