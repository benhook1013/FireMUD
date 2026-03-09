package net.firedevops.firemud.accountservice.service.impl;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.CurrencyBalance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.CurrencyBalanceRepository;
import net.firedevops.firemud.accountservice.service.VirtualCurrencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VirtualCurrencyServiceImpl implements VirtualCurrencyService {
  private final AccountRepository accountRepository;
  private final CurrencyBalanceRepository balanceRepository;

  public VirtualCurrencyServiceImpl(
      AccountRepository accountRepository, CurrencyBalanceRepository balanceRepository) {
    this.accountRepository = accountRepository;
    this.balanceRepository = balanceRepository;
  }

  @Override
  @Transactional
  @Timed(value = "currency.add")
  public long addCurrency(Long tenantId, Long accountId, String currencyCode, Long amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    CurrencyBalance balance =
        balanceRepository
            .findByTenantIdAndAccountIdAndCurrencyCode(tenantId, accountId, currencyCode)
            .orElseGet(
                () -> {
                  Account account =
                      accountRepository
                          .findById(accountId)
                          .filter(a -> a.getTenantId().equals(tenantId))
                          .orElseThrow(() -> new IllegalArgumentException("Account not found"));
                  CurrencyBalance cb = new CurrencyBalance();
                  cb.setAccount(account);
                  cb.setCurrencyCode(currencyCode);
                  cb.setBalance(0L);
                  cb.setTenantId(tenantId);
                  return cb;
                });
    balance.setBalance(balance.getBalance() + amount);
    balanceRepository.save(balance);
    return balance.getBalance();
  }

  @Override
  @Transactional
  @Timed(value = "currency.spend")
  public long spendCurrency(Long tenantId, Long accountId, String currencyCode, Long amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    CurrencyBalance balance =
        balanceRepository
            .findByTenantIdAndAccountIdAndCurrencyCode(tenantId, accountId, currencyCode)
            .orElseThrow(() -> new IllegalArgumentException("Balance not found"));
    if (balance.getBalance() < amount) {
      throw new IllegalArgumentException("Insufficient balance");
    }
    balance.setBalance(balance.getBalance() - amount);
    balanceRepository.save(balance);
    return balance.getBalance();
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "currency.get")
  public long getBalance(Long tenantId, Long accountId, String currencyCode) {
    return balanceRepository
        .findByTenantIdAndAccountIdAndCurrencyCode(tenantId, accountId, currencyCode)
        .map(CurrencyBalance::getBalance)
        .orElse(0L);
  }
}
