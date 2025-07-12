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
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.config.MailProperties;
import net.firedevops.firemud.dto.AccountDataExportDto;
import net.firedevops.firemud.dto.AccountDto;
import net.firedevops.firemud.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.dto.CreateAccountRequest;
import net.firedevops.firemud.dto.PasswordResetRequest;
import net.firedevops.firemud.dto.ProfileDto;
import net.firedevops.firemud.dto.UpdateProfileRequest;
import net.firedevops.firemud.dto.UsernameRecoveryRequest;
import net.firedevops.firemud.dto.VerifyEmailRequest;
import net.firedevops.firemud.entity.Account;
import net.firedevops.firemud.entity.EmailVerificationToken;
import net.firedevops.firemud.entity.Profile;
import net.firedevops.firemud.mapper.AccountMapper;
import net.firedevops.firemud.mapper.ProfileMapper;
import net.firedevops.firemud.repository.AccountRepository;
import net.firedevops.firemud.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.repository.ExternalAccountRepository;
import net.firedevops.firemud.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.repository.PaymentTransactionRepository;
import net.firedevops.firemud.repository.ProfileRepository;
import net.firedevops.firemud.repository.SubscriptionRepository;
import net.firedevops.firemud.service.AccountService;
import net.firedevops.firemud.service.EmailService;
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
  private final ExternalAccountRepository externalAccountRepository;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final EmailVerificationTokenRepository emailVerificationTokenRepository;
  private final NotificationService notificationService;
  private final EmailService emailService;
  private final MailProperties mailProperties;
  private final LoggingAdminClient loggingAdminClient;
  private final JwtUtil jwtUtil;
  private final net.firedevops.firemud.service.session.SessionService sessionService;
  private final SagaRunner sagaRunner;

  public AccountServiceImpl(
      AccountRepository accountRepository,
      AccountMapper accountMapper,
      ProfileRepository profileRepository,
      ProfileMapper profileMapper,
      PaymentTransactionRepository paymentTransactionRepository,
      SubscriptionRepository subscriptionRepository,
      ExternalAccountRepository externalAccountRepository,
      PasswordResetTokenRepository passwordResetTokenRepository,
      EmailVerificationTokenRepository emailVerificationTokenRepository,
      NotificationService notificationService,
      EmailService emailService,
      MailProperties mailProperties,
      LoggingAdminClient loggingAdminClient,
      JwtUtil jwtUtil,
      net.firedevops.firemud.service.session.SessionService sessionService,
      SagaRunner sagaRunner) {
    this.accountRepository = accountRepository;
    this.accountMapper = accountMapper;
    this.profileRepository = profileRepository;
    this.profileMapper = profileMapper;
    this.paymentTransactionRepository = paymentTransactionRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.externalAccountRepository = externalAccountRepository;
    this.passwordResetTokenRepository = passwordResetTokenRepository;
    this.emailVerificationTokenRepository = emailVerificationTokenRepository;
    this.notificationService = notificationService;
    this.emailService = emailService;
    this.mailProperties = mailProperties;
    this.loggingAdminClient = loggingAdminClient;
    this.jwtUtil = jwtUtil;
    this.sessionService = sessionService;
    this.sagaRunner = sagaRunner;
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

    SagaBuilder builder = new SagaBuilder("accountCreation");
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
    var saga = builder.build();
    try {
      sagaRunner.run(saga);
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

  @Override
  @Transactional
  @Timed(value = "account.request_password_reset")
  public void requestPasswordReset(PasswordResetRequest request) {
    net.firedevops.firemud.entity.Account account =
        accountRepository
            .findByTenantIdAndEmail(request.tenantId(), request.email())
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    net.firedevops.firemud.entity.PasswordResetToken token =
        new net.firedevops.firemud.entity.PasswordResetToken();
    token.setAccount(account);
    token.setTenantId(request.tenantId());
    token.setToken(java.util.UUID.randomUUID().toString());
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
    passwordResetTokenRepository.save(token);
    String url = String.format(mailProperties.getResetUrl(), token.getToken());
    emailService.sendEmail(
        account.getEmail(),
        "Password Reset",
        String.format(readTemplate("password-reset.txt"), url));
    notificationService.sendNotification(
        request.tenantId(), account.getId(), "Password reset requested");
  }

  @Override
  @Transactional
  @Timed(value = "account.complete_password_reset")
  public void completePasswordReset(CompletePasswordResetRequest request) {
    net.firedevops.firemud.entity.PasswordResetToken token =
        passwordResetTokenRepository
            .findByTokenAndTenantId(request.token(), request.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Invalid token"));
    if (token.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
      throw new IllegalArgumentException("Token expired");
    }
    net.firedevops.firemud.entity.Account account = token.getAccount();
    account.setPasswordHash(hashPassword(request.newPassword()));
    accountRepository.save(account);
    passwordResetTokenRepository.delete(token);
  }

  @Override
  @Transactional
  @Timed(value = "account.request_email_verification")
  public void requestEmailVerification(Long tenantId, Long accountId) {
    Account account =
        accountRepository
            .findById(accountId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    EmailVerificationToken token = new EmailVerificationToken();
    token.setAccount(account);
    token.setTenantId(tenantId);
    token.setToken(java.util.UUID.randomUUID().toString());
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(24));
    emailVerificationTokenRepository.save(token);
    String url = String.format(mailProperties.getVerificationUrl(), token.getToken());
    emailService.sendEmail(
        account.getEmail(),
        "Email Verification",
        String.format(readTemplate("email-verification.txt"), url));
    notificationService.sendNotification(tenantId, accountId, "Email verification requested");
  }

  @Override
  @Transactional
  @Timed(value = "account.verify_email")
  public void verifyEmail(VerifyEmailRequest request) {
    EmailVerificationToken token =
        emailVerificationTokenRepository
            .findByTokenAndTenantId(request.token(), request.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Invalid token"));
    if (token.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
      throw new IllegalArgumentException("Token expired");
    }
    Account account = token.getAccount();
    account.setEmailVerified(true);
    accountRepository.save(account);
    emailVerificationTokenRepository.delete(token);
  }

  @Override
  @Transactional
  @Timed(value = "account.link_external")
  public void linkExternalAccount(net.firedevops.firemud.dto.LinkExternalAccountRequest request) {
    Account account =
        accountRepository
            .findById(request.accountId())
            .filter(a -> a.getTenantId().equals(request.tenantId()))
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

    if (externalAccountRepository.existsByTenantIdAndAccountIdAndProvider(
        request.tenantId(), request.accountId(), request.provider())) {
      throw new IllegalArgumentException("Account already linked");
    }

    net.firedevops.firemud.entity.ExternalAccount entity =
        new net.firedevops.firemud.entity.ExternalAccount();
    entity.setAccount(account);
    entity.setTenantId(request.tenantId());
    entity.setProvider(request.provider());
    entity.setExternalId(request.externalId());
    externalAccountRepository.save(entity);
  }

  @Override
  @Transactional
  @Timed(value = "account.username_reminder")
  public void sendUsernameReminder(UsernameRecoveryRequest request) {
    Account account =
        accountRepository
            .findByTenantIdAndEmail(request.tenantId(), request.email())
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    emailService.sendEmail(
        account.getEmail(),
        "Username Reminder",
        String.format(readTemplate("username-reminder.txt"), account.getUsername()));
    notificationService.sendNotification(
        request.tenantId(), account.getId(), "Username reminder requested");
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

  private String readTemplate(String name) {
    try (var in = getClass().getClassLoader().getResourceAsStream("templates/" + name)) {
      if (in == null) {
        throw new IllegalStateException("Missing template: " + name);
      }
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed to read template", e);
    }
  }
}
