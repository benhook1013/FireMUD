package net.firedevops.firemud.accountservice.service.impl;

import com.bastiaanjansen.otp.TOTPGenerator;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.config.AuthProperties;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.dto.AccountDataExportDto;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.RuntimeEntitlementsDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.EmailVerificationToken;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.mapper.ProfileMapper;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.accountservice.repository.ExternalAccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import net.firedevops.firemud.accountservice.repository.SubscriptionRepository;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.EmailService;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.security.JwtUtil;
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
  private final AuthProperties authProperties;
  private final LoggingAdminClient loggingAdminClient;
  private final JwtUtil jwtUtil;
  private final net.firedevops.firemud.accountservice.service.session.SessionService sessionService;
  private final SagaRunner sagaRunner;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Dependencies are injected and kept internal")
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
      AuthProperties authProperties,
      LoggingAdminClient loggingAdminClient,
      JwtUtil jwtUtil,
      net.firedevops.firemud.accountservice.service.session.SessionService sessionService,
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
    this.authProperties = authProperties;
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
    account.setTenantId(0L);
    account.setUsername(request.username());
    account.setEmail(request.email());
    account.setPasswordHash(hashPassword(request.password()));
    account.setRole("player");
    Profile profile = new Profile();
    profile.setTenantId(0L);
    // Accounts are system-wide; tenant membership is assigned later.

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
        .step("logCreation", () -> safeLogAccountCreation(account.getId()));
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
  public net.firedevops.firemud.accountservice.dto.AuthenticationResult authenticate(
      Long tenantId, String username, String password, String otp) {
    Optional<Account> accountOpt = findAccountForAuthentication(tenantId, username);
    Account account =
        accountOpt.orElseThrow(
            () ->
                new AuthenticationException(
                    AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials"));
    if (!verifyPassword(password, account.getPasswordHash())) {
      throw new AuthenticationException(
          AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials");
    }
    if (account.getTwoFactorSecret() != null
        && ("admin".equals(account.getRole()) || "moderator".equals(account.getRole()))) {
      TOTPGenerator generator = new TOTPGenerator.Builder(account.getTwoFactorSecret()).build();
      if (otp == null || !generator.verify(otp)) {
        throw new AuthenticationException(
            AuthenticationErrorCodes.OTP_REQUIRED, "Invalid 2FA code");
      }
    }
    String token =
        jwtUtil.generateToken(
            account.getId().toString(),
            Map.of(
                "accountId", account.getId(), "globalRoles", java.util.List.of(account.getRole())));
    sessionService.storeSession(tenantId, account.getId(), token);
    return new net.firedevops.firemud.accountservice.dto.AuthenticationResult(
        account.getId(), token);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.player_bootstrap")
  public PlayerBootstrapResult issuePlayerBootstrap(
      Long tenantId, String username, String password, String otp) {
    var auth = authenticate(tenantId, username, password, otp);
    String jti = UUID.randomUUID().toString();
    long issuedAt = System.currentTimeMillis();
    long expiresAt = issuedAt + authProperties.getPlayerBootstrapExpirationMs();
    String bootstrapToken =
        mintToken(
            String.valueOf(auth.accountId()),
            authProperties.getPlayerBootstrapExpirationMs(),
            Map.of(
                "aud",
                "player-bootstrap",
                "accountId",
                auth.accountId(),
                "tenantId",
                tenantId,
                "jti",
                jti));
    sessionService.storeSession(
        tenantId,
        auth.accountId(),
        bootstrapToken,
        authProperties.getPlayerBootstrapExpirationMs());
    logger.info(
        "Issued player bootstrap token for account {} tenant {}", auth.accountId(), tenantId);
    return new PlayerBootstrapResult(
        auth.accountId(),
        bootstrapToken,
        Instant.ofEpochMilli(issuedAt).toString(),
        Instant.ofEpochMilli(expiresAt).toString());
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.connect_token")
  public ConnectTokenResult issueConnectToken(String bootstrapToken, ConnectTokenRequest request) {
    if (bootstrapToken == null || bootstrapToken.isBlank()) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Missing bootstrap token");
    }
    Claims claims;
    try {
      claims = jwtUtil.parseToken(bootstrapToken).getPayload();
    } catch (RuntimeException ex) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token", ex);
    }
    String audience = claimText(claims.get("aud"));
    if (!"player-bootstrap".equals(audience)) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token");
    }
    Long accountId = parseLong(claims.get("accountId"));
    Long bootstrapTenantId = parseLong(claims.get("tenantId"));
    if (accountId == null || bootstrapTenantId == null) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token");
    }
    Long storedAccountId = sessionService.getAccountId(bootstrapTenantId, bootstrapToken);
    if (storedAccountId == null || !storedAccountId.equals(accountId)) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Bootstrap token expired");
    }
    if (!bootstrapTenantId.equals(request.tenantId())) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Tenant mismatch");
    }

    RuntimeMembershipDto membership =
        getTenantMembershipForRuntime(accountId, request.tenantId(), request.requestId());
    if (!membership.gameplayAdmissionAllowed()) {
      throw new AuthenticationException(
          "CONNECT_TOKEN_REJECTED", "Gameplay admission is not allowed for this account");
    }
    RuntimeEntitlementsDto entitlements =
        getTenantEntitlementsForRuntime(request.tenantId(), request.requestId());
    if (!entitlements.gameplayAvailable()) {
      throw new AuthenticationException(
          "CONNECT_TOKEN_REJECTED", "Gameplay is not available for this tenant");
    }

    String jti = UUID.randomUUID().toString();
    long issuedAt = System.currentTimeMillis();
    long expiresAt = issuedAt + authProperties.getConnectTokenExpirationMs();
    String connectToken =
        mintToken(
            String.valueOf(accountId),
            authProperties.getConnectTokenExpirationMs(),
            Map.of(
                "aud",
                "gameplay-connect",
                "accountId",
                accountId,
                "tenantId",
                request.tenantId(),
                "gameInstanceId",
                request.gameInstanceId(),
                "realmSlug",
                request.realmSlug() == null ? "" : request.realmSlug(),
                "connectScopeId",
                request.connectScopeId(),
                "jti",
                jti));
    sessionService.storeSession(
        request.tenantId(), accountId, connectToken, authProperties.getConnectTokenExpirationMs());
    logger.info(
        "Issued connect token for account {} tenant {} gameInstance {} jti {}",
        accountId,
        request.tenantId(),
        request.gameInstanceId(),
        jti);
    return new ConnectTokenResult(
        accountId,
        request.tenantId(),
        request.gameInstanceId(),
        request.realmSlug(),
        request.connectScopeId(),
        connectToken,
        jti,
        Instant.ofEpochMilli(issuedAt).toString(),
        Instant.ofEpochMilli(expiresAt).toString());
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.runtime_membership")
  public RuntimeMembershipDto getTenantMembershipForRuntime(
      Long accountId, Long tenantId, String requestId) {
    Account account =
        accountRepository
            .findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    return new RuntimeMembershipDto(
        account.getId(), tenantId, true, Math.max(1L, account.getId()), Instant.now().toString());
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.runtime_entitlements")
  public RuntimeEntitlementsDto getTenantEntitlementsForRuntime(Long tenantId, String requestId) {
    List<net.firedevops.firemud.accountservice.entity.Subscription> subscriptions =
        subscriptionRepository.findByTenantId(tenantId);
    boolean gameplayAvailable =
        subscriptions.isEmpty()
            || subscriptions.stream().anyMatch(s -> isGameplayAvailableStatus(s.getStatus()));
    long version =
        subscriptions.stream()
            .mapToLong(subscription -> subscription.getId() == null ? 0L : subscription.getId())
            .max()
            .orElse(0L);
    return new RuntimeEntitlementsDto(
        tenantId, gameplayAvailable, version, version, Instant.now().toString());
  }

  private Optional<Account> findAccountForAuthentication(Long tenantId, String usernameOrEmail) {
    Optional<Account> tenantUsernameMatch =
        accountRepository.findByTenantIdAndUsername(tenantId, usernameOrEmail);
    if (tenantUsernameMatch.isPresent()) {
      return tenantUsernameMatch;
    }

    Optional<Account> tenantEmailMatch =
        accountRepository.findByTenantIdAndEmail(tenantId, usernameOrEmail);
    if (tenantEmailMatch.isPresent()) {
      return tenantEmailMatch;
    }

    Optional<Account> globalUsernameMatch =
        accountRepository.findByTenantIdAndUsername(0L, usernameOrEmail);
    if (globalUsernameMatch.isPresent()) {
      return globalUsernameMatch;
    }

    return accountRepository.findByTenantIdAndEmail(0L, usernameOrEmail);
  }

  private String mintToken(String subject, long expirationMs, Map<String, Object> claims) {
    long now = System.currentTimeMillis();
    String secret = authProperties.getJwtSecret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("JWT secret must be configured");
    }
    Object audience = claims.get("aud");
    Map<String, Object> nonRegisteredClaims = new java.util.HashMap<>(claims);
    nonRegisteredClaims.remove("aud");
    var builder =
        Jwts.builder()
            .subject(subject)
            .claims(nonRegisteredClaims)
            .issuedAt(new Date(now))
            .expiration(new Date(now + expirationMs));
    if (audience != null) {
      builder.audience().add(audience.toString()).and();
    }
    return builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
  }

  private Long parseLong(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(value.toString());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String claimText(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Iterable<?> iterable) {
      for (Object candidate : iterable) {
        if (candidate != null) {
          return candidate.toString();
        }
      }
      return "";
    }
    return value.toString();
  }

  private boolean isGameplayAvailableStatus(String status) {
    if (status == null) {
      return false;
    }
    return switch (status.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "active", "trialing", "grace" -> true;
      default -> false;
    };
  }

  private void safeLogAccountCreation(Long accountId) {
    try {
      loggingAdminClient.logAccountCreation(0L, accountId);
    } catch (RuntimeException ex) {
      logger.warn("Account creation logging failed for account {}", accountId, ex);
    }
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
    net.firedevops.firemud.accountservice.entity.Account account =
        accountRepository
            .findByTenantIdAndEmail(request.tenantId(), request.email())
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    net.firedevops.firemud.accountservice.entity.PasswordResetToken token =
        new net.firedevops.firemud.accountservice.entity.PasswordResetToken();
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
    net.firedevops.firemud.accountservice.entity.PasswordResetToken token =
        passwordResetTokenRepository
            .findByTokenAndTenantId(request.token(), request.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Invalid token"));
    if (token.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
      throw new IllegalArgumentException("Token expired");
    }
    net.firedevops.firemud.accountservice.entity.Account account = token.getAccount();
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
  public void linkExternalAccount(
      net.firedevops.firemud.accountservice.dto.LinkExternalAccountRequest request) {
    Account account =
        accountRepository
            .findById(request.accountId())
            .filter(a -> a.getTenantId().equals(request.tenantId()))
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

    if (externalAccountRepository.existsByTenantIdAndAccountIdAndProvider(
        request.tenantId(), request.accountId(), request.provider())) {
      throw new IllegalArgumentException("Account already linked");
    }

    net.firedevops.firemud.accountservice.entity.ExternalAccount entity =
        new net.firedevops.firemud.accountservice.entity.ExternalAccount();
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
    Argon2 argon2 = Argon2Factory.create();
    char[] chars = password.toCharArray();
    try {
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }

  private boolean verifyPassword(String password, String hash) {
    Argon2 argon2 = Argon2Factory.create();
    char[] chars = password.toCharArray();
    try {
      return argon2.verify(hash, chars);
    } finally {
      argon2.wipeArray(chars);
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
