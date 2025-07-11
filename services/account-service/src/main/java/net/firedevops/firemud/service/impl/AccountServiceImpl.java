package net.firedevops.firemud.service.impl;

import com.bastiaanjansen.otp.TOTPGenerator;
import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.JwtUtil;
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
  private final JwtUtil jwtUtil;
  private final net.firedevops.firemud.service.session.SessionService sessionService;

  public AccountServiceImpl(
      AccountRepository accountRepository,
      AccountMapper accountMapper,
      JwtUtil jwtUtil,
      net.firedevops.firemud.service.session.SessionService sessionService) {
    this.accountRepository = accountRepository;
    this.accountMapper = accountMapper;
    this.jwtUtil = jwtUtil;
    this.sessionService = sessionService;
  }

  @Override
  @Transactional
  @Timed(value = "account.create")
  public AccountDto createAccount(CreateAccountRequest request) {
    logger.info("Creating account {}", request.username());
    Account account = new Account();
    account.setTenantId(request.tenantId());
    account.setUsername(request.username());
    account.setEmail(request.email());
    account.setPasswordHash(hashPassword(request.password()));
    account.setRole("player");
    account = accountRepository.save(account);
    return accountMapper.toDto(account);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.authenticate")
  public String authenticate(Long tenantId, String username, String password, String otp) {
    Optional<Account> accountOpt = accountRepository.findByTenantIdAndUsername(tenantId, username);
    Account account =
        accountOpt.orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
    String hash = hashPassword(password);
    if (!hash.equals(account.getPasswordHash())) {
      throw new IllegalArgumentException("Invalid credentials");
    }
    if (account.getTwoFactorSecret() != null
        && ("admin".equals(account.getRole()) || "moderator".equals(account.getRole()))) {
      TOTPGenerator generator = new TOTPGenerator.Builder(account.getTwoFactorSecret()).build();
      if (otp == null || !generator.verify(otp)) {
        throw new IllegalArgumentException("Invalid 2FA code");
      }
    }
    String token =
        jwtUtil.generateToken(
            account.getId().toString(),
            Map.of(
                "accountId", account.getId(), "globalRoles", java.util.List.of(account.getRole())));
    sessionService.storeSession(tenantId, account.getId(), token);
    return token;
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
