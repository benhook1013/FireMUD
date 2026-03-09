package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.CurrencyBalance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.CurrencyBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class VirtualCurrencyServiceImplTest {
  @Mock private AccountRepository accountRepository;
  @Mock private CurrencyBalanceRepository balanceRepository;

  private VirtualCurrencyServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    service = new VirtualCurrencyServiceImpl(accountRepository, balanceRepository);
  }

  @Test
  void addCurrencyCreatesBalance() {
    Account account = new Account();
    account.setId(1L);
    account.setTenantId(2L);
    when(balanceRepository.findByTenantIdAndAccountIdAndCurrencyCode(2L, 1L, "GOLD"))
        .thenReturn(Optional.empty());
    when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
    ArgumentCaptor<CurrencyBalance> captor = ArgumentCaptor.forClass(CurrencyBalance.class);
    when(balanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    long balance = service.addCurrency(2L, 1L, "GOLD", 50L);

    verify(balanceRepository).save(captor.capture());
    assertEquals(50L, captor.getValue().getBalance());
    assertEquals(50L, balance);
  }

  @Test
  void spendCurrencyFailsForInsufficient() {
    CurrencyBalance bal = new CurrencyBalance();
    bal.setBalance(10L);
    when(balanceRepository.findByTenantIdAndAccountIdAndCurrencyCode(2L, 1L, "GOLD"))
        .thenReturn(Optional.of(bal));

    assertThrows(IllegalArgumentException.class, () -> service.spendCurrency(2L, 1L, "GOLD", 20L));
  }
}
