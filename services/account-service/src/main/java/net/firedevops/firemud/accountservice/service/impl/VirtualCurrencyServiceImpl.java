package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.CurrencyBalance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.CurrencyBalanceRepository;
import net.firedevops.firemud.accountservice.service.VirtualCurrencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VirtualCurrencyServiceImpl implements VirtualCurrencyService {
  private final AccountRepository accountRepository;
  private final AccountTenantMembershipRepository accountTenantMembershipRepository;
  private final CurrencyBalanceRepository balanceRepository;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected repositories remain internal service collaborators.")
  public VirtualCurrencyServiceImpl(
      AccountRepository accountRepository,
      AccountTenantMembershipRepository accountTenantMembershipRepository,
      CurrencyBalanceRepository balanceRepository) {
    this.accountRepository = accountRepository;
    this.accountTenantMembershipRepository = accountTenantMembershipRepository;
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
                  Account account = requireAccountMembership(tenantId, accountId);
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

  private Account requireAccountMembership(Long tenantId, Long accountId) {
    if (!accountTenantMembershipRepository.existsByAccountIdAndTenantId(accountId, tenantId)) {
      throw new IllegalArgumentException("Account not found");
    }
    return accountRepository
        .findById(accountId)
        .orElseThrow(() -> new IllegalArgumentException("Account not found"));
  }
}
