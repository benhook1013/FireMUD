package net.firedevops.firemud.accountservice.service.session;

public interface SessionService {
  void storeSession(Long tenantId, Long accountId, String token);

  Long getAccountId(Long tenantId, String token);
}
