package net.firedevops.firemud.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.AccountDto;
import net.firedevops.firemud.dto.CreateAccountRequest;
import net.firedevops.firemud.entity.Account;
import net.firedevops.firemud.mapper.AccountMapper;
import net.firedevops.firemud.repository.AccountRepository;
import net.firedevops.firemud.service.AccountService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountServiceImpl implements AccountService {
  private static final Logger logger = LoggingUtil.getLogger(AccountServiceImpl.class);

  private final AccountRepository accountRepository;
  private final AccountMapper accountMapper;

  public AccountServiceImpl(AccountRepository accountRepository, AccountMapper accountMapper) {
    this.accountRepository = accountRepository;
    this.accountMapper = accountMapper;
  }

  @Override
  @Transactional
  public AccountDto createAccount(CreateAccountRequest request) {
    logger.info("Creating account {}", request.username());
    Account account = new Account();
    account.setTenantId(request.tenantId());
    account.setUsername(request.username());
    account.setEmail(request.email());
    account.setPasswordHash(hashPassword(request.password()));
    account = accountRepository.save(account);
    return accountMapper.toDto(account);
  }

  private String hashPassword(String password) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
