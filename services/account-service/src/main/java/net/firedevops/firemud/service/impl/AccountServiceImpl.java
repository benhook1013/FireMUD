package net.firedevops.firemud.service.impl;

import com.bastiaanjansen.otp.TOTPGenerator;
import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.client.LoggingAdminClient;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.dto.AccountDataExportDto;
import net.firedevops.firemud.dto.AccountDto;
import net.firedevops.firemud.dto.CreateAccountRequest;
import net.firedevops.firemud.dto.ProfileDto;
import net.firedevops.firemud.dto.UpdateProfileRequest;
import net.firedevops.firemud.entity.Account;
import net.firedevops.firemud.entity.Profile;
import net.firedevops.firemud.mapper.AccountMapper;
import net.firedevops.firemud.mapper.ProfileMapper;
import net.firedevops.firemud.repository.AccountRepository;
import net.firedevops.firemud.repository.PaymentTransactionRepository;
import net.firedevops.firemud.repository.ProfileRepository;
import net.firedevops.firemud.repository.SubscriptionRepository;
import net.firedevops.firemud.service.AccountService;
import net.firedevops.firemud.service.NotificationService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountServiceImpl implements AccountService {
  private static final Logger logger = LoggingUtil.getLogger(AccountServiceImpl.class);

  private final AccountRepository accountRepository;
  private final AccountMapper accountMapper;
  private final ProfileRepository profileRepository;
  private final ProfileMapper profileMapper;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final SubscriptionRepository subscriptionRepository;
  private final NotificationService notificationService;
  private final LoggingAdminClient loggingAdminClient;
  private final JwtUtil jwtUtil;
  private final net.firedevops.firemud.service.session.SessionService sessionService;

  public AccountServiceImpl(
      AccountRepository accountRepository,
      AccountMapper accountMapper,
      ProfileRepository profileRepository,
      ProfileMapper profileMapper,
      PaymentTransactionRepository paymentTransactionRepository,
      SubscriptionRepository subscriptionRepository,
      NotificationService notificationService,
      LoggingAdminClient loggingAdminClient,
      JwtUtil jwtUtil,
      net.firedevops.firemud.service.session.SessionService sessionService) {
    this.accountRepository = accountRepository;
    this.accountMapper = accountMapper;
    this.profileRepository = profileRepository;
    this.profileMapper = profileMapper;
    this.paymentTransactionRepository = paymentTransactionRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.notificationService = notificationService;
    this.loggingAdminClient = loggingAdminClient;
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
    Profile profile = new Profile();
    profile.setTenantId(request.tenantId());

    SagaBuilder builder = new SagaBuilder();
    builder
        .step(
            "persistAccount",
            () -> {
              Account saved = accountRepository.save(account);
              account.setId(saved.getId());
              profile.setAccount(saved);
              profileRepository.save(profile);
            },
            () -> {
              profileRepository.delete(profile);
              accountRepository.delete(account);
            })
        .step(
            "logCreation",
            () -> loggingAdminClient.logAccountCreation(account.getTenantId(), account.getId()));

    try {
      builder.run();
    } catch (SagaException e) {
      logger.warn("Account creation saga failed", e);
      throw new IllegalStateException("Account creation failed", e);
    }

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

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.get_profile")
  public ProfileDto getProfile(Long tenantId, Long accountId) {
    Profile profile =
        profileRepository
            .findByAccountIdAndTenantId(accountId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Profile not found"));
    return profileMapper.toDto(profile);
  }

  @Override
  @Transactional
  @Timed(value = "account.update_profile")
  public ProfileDto updateProfile(UpdateProfileRequest request) {
    Profile profile =
        profileRepository
            .findByAccountIdAndTenantId(request.accountId(), request.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Profile not found"));
    profile.setDisplayName(request.displayName());
    profile.setBio(request.bio());
    profile = profileRepository.save(profile);
    notificationService.sendNotification(
        request.tenantId(), request.accountId(), "Profile updated");
    return profileMapper.toDto(profile);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.export")
  public AccountDataExportDto exportAccountData(Long tenantId, Long accountId) {
    Account account =
        accountRepository
            .findById(accountId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    Profile profile =
        profileRepository.findByAccountIdAndTenantId(accountId, tenantId).orElse(null);
    return new AccountDataExportDto(
        accountMapper.toDto(account), profile != null ? profileMapper.toDto(profile) : null);
  }

  @Override
  @Transactional
  @Timed(value = "account.delete")
  public void deleteAccount(Long tenantId, Long accountId) {
    Account account =
        accountRepository
            .findById(accountId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    paymentTransactionRepository.deleteByAccountId(accountId, tenantId);
    subscriptionRepository.deleteByAccountId(accountId, tenantId);
    profileRepository
        .findByAccountIdAndTenantId(accountId, tenantId)
        .ifPresent(profileRepository::delete);
    accountRepository.delete(account);
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
