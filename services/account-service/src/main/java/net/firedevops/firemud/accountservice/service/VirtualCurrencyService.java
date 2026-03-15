package net.firedevops.firemud.accountservice.service;

public interface VirtualCurrencyService {
  long addCurrency(Long tenantId, Long accountId, String currencyCode, Long amount);

  long spendCurrency(Long tenantId, Long accountId, String currencyCode, Long amount);

  long getBalance(Long tenantId, Long accountId, String currencyCode);
}
