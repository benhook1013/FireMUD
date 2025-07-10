package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.dto.AccountDto;
import net.firedevops.firemud.dto.CreateAccountRequest;
import net.firedevops.firemud.entity.Account;
import net.firedevops.firemud.mapper.AccountMapper;
import net.firedevops.firemud.repository.AccountRepository;
import net.firedevops.firemud.service.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AccountServiceImplTest {
  @Mock private AccountRepository accountRepository;
  @Mock private SessionService sessionService;

  private AccountServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    AccountMapper mapper = Mappers.getMapper(AccountMapper.class);
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 3600000L);
    service = new AccountServiceImpl(accountRepository, mapper, jwtUtil, sessionService);
  }

  @Test
  void createAccountPersistsEntity() {
    CreateAccountRequest request =
        new CreateAccountRequest(1L, "demo", "demo@example.com", "password");
    Account saved = new Account();
    saved.setId(1L);
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class))).thenReturn(saved);

    AccountDto dto = service.createAccount(request);

    assertEquals(1L, dto.id());
  }

  @Test
  void authenticateReturnsTokenWhenPasswordMatches() {
    Account account = new Account();
    account.setId(1L);
    account.setTenantId(1L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByTenantIdAndUsername(1L, "demo")).thenReturn(Optional.of(account));

    String token = service.authenticate(1L, "demo", "password");

    assertNotNull(token);
    org.mockito.Mockito.verify(sessionService).storeSession(1L, 1L, token);
  }

  @Test
  void authenticateThrowsWhenInvalid() {
    when(accountRepository.findByTenantIdAndUsername(1L, "demo")).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> service.authenticate(1L, "demo", "bad"));
  }

  private static String hash(String password) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
