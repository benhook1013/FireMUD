package net.firedevops.firemud.accountservice.service.impl;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.annotation.Timed;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.AccountUuidText;
import net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.dto.AccountDataExportDto;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.AccountLoginAuthModesDto;
import net.firedevops.firemud.accountservice.dto.BootstrapCharacterDto;
import net.firedevops.firemud.accountservice.dto.BootstrapRealmDto;
import net.firedevops.firemud.accountservice.dto.BootstrapWorldDto;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantResult;
import net.firedevops.firemud.accountservice.dto.RuntimeEntitlementsDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto;
import net.firedevops.firemud.accountservice.dto.TenantDataExportDto;
import net.firedevops.firemud.accountservice.dto.UpdateAccountLoginAuthModesRequest;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthMode;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.entity.AccountRealmAccessGrant;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.entity.EmailVerificationToken;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.entity.RetainedSubscriptionEvidence;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.mapper.ProfileMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionCurrentReceipt;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReadFence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinTerminalProof;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceSelection;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.accountservice.repository.ExternalAccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import net.firedevops.firemud.accountservice.repository.RetainedSubscriptionEvidenceRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountCanonicalPublicRealmScopeOwner;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository.Intent;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapWorldsOwner;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.EmailService;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.accountservice.service.exception.AccountAlreadyExistsException;
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner.CredentialVerification;
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec.PasswordResetAuthorityEvent;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountServiceImpl implements AccountService {
  private static final Logger logger = LoggingUtil.getLogger(AccountServiceImpl.class);
  private static final String STALE_CONNECT_SCOPE_MESSAGE =
      "Selected gameplay target is no longer admissible; rerun bootstrap discovery and request a fresh connect scope";
  private static final String INVALID_CONNECT_SCOPE_MESSAGE =
      "Connect scope is invalid or expired; rerun bootstrap discovery and request a fresh connect scope";
  private static final String JOIN_REQUIRED_CHARACTERS_MESSAGE =
      "Join the selected world before discovering characters";
  private static final String GAMEPLAY_DELEGATION_AUDIENCE = "account-service";
  private static final String PASSWORD_RESET_OPERATION_KIND = "PASSWORD_RESET";
  private static final String PASSWORD_RESET_REQUEST_DOMAIN = "account-password-reset-request/v1";
  private static final String PASSWORD_RESET_REQUEST_ID_PREFIX =
      "account-password-reset-request-v1:";
  private static final String PASSWORD_RESET_EVENT_ID_PREFIX = "account-password-reset-event-v1:";
  private static final int EMAIL_LOGIN_OTP_MAX_ATTEMPTS = 5;
  private static final UUID NIL_ACCOUNT_UUID = new UUID(0L, 0L);
  private static final SecureRandom EMAIL_LOGIN_OTP_RANDOM = new SecureRandom();

  private String gameplayDecoyPasswordVerifier;
  private volatile AccountPublicBootstrapWorldsOwner publicBootstrapWorldsOwner;
  private volatile AccountCanonicalPublicRealmScopeOwner canonicalPublicRealmScopeOwner;
  private volatile CanonicalScopeConsumers canonicalScopeConsumers;
  private volatile AccountBootstrapRosterReadClient accountBootstrapRosterReadClient;

  private final AccountRepository accountRepository;
  private final AccountAuthorityGenerationRepository accountAuthorityGenerationRepository;
  private final AccountAuthoritySourceEvidenceRepository accountAuthoritySourceEvidenceRepository;
  private final AccountAuthorityOutboxRepository accountAuthorityOutboxRepository;
  private final AccountPasswordResetOperationRepository passwordResetOperationRepository;
  private final AccountAuthoritySourceEventReadback accountAuthoritySourceEventReadback;
  private final AccountPasswordResetDraftSourceChangeRepository passwordResetDraftSourceChanges;
  private final AccountAuditOutboxRepository accountAuditOutboxRepository;
  private final AccountJoinOperationRepository accountJoinOperationRepository;
  private final AccountEmailLoginChallengeRepository accountEmailLoginChallengeRepository;
  private final AccountRealmAccessGrantRepository accountRealmAccessGrantRepository;
  private final AccountTenantMembershipRepository accountTenantMembershipRepository;
  private final AccountMapper accountMapper;
  private final ProfileRepository profileRepository;
  private final ProfileMapper profileMapper;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final RetainedSubscriptionEvidenceRepository retainedSubscriptionEvidenceRepository;
  private final ExternalAccountRepository externalAccountRepository;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final EmailVerificationTokenRepository emailVerificationTokenRepository;
  private final NotificationService notificationService;
  private final EmailService emailService;
  private final MailProperties mailProperties;
  private final AccountTokenProperties tokenProperties;
  private final JwtAuthProperties jwtAuthProperties;
  private final JwtUtil jwtUtil;
  private final net.firedevops.firemud.accountservice.service.session.SessionService sessionService;
  private final TransactionTemplate joinTransactionTemplate;
  private final TransactionTemplate passwordResetOwnerTransaction;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Dependencies are injected and kept internal")
  public AccountServiceImpl(
      AccountRepository accountRepository,
      AccountAuthorityGenerationRepository accountAuthorityGenerationRepository,
      AccountAuthoritySourceEvidenceRepository accountAuthoritySourceEvidenceRepository,
      AccountAuthorityOutboxRepository accountAuthorityOutboxRepository,
      AccountPasswordResetOperationRepository passwordResetOperationRepository,
      AccountLogoutAllOperationRepository logoutAllOperationRepository,
      AccountSecurityStateOperationRepository securityStateOperationRepository,
      AccountPasswordResetDraftSourceChangeRepository passwordResetDraftSourceChanges,
      AccountAuditOutboxRepository accountAuditOutboxRepository,
      AccountJoinOperationRepository accountJoinOperationRepository,
      AccountEmailLoginChallengeRepository accountEmailLoginChallengeRepository,
      AccountRealmAccessGrantRepository accountRealmAccessGrantRepository,
      AccountTenantMembershipRepository accountTenantMembershipRepository,
      AccountMapper accountMapper,
      ProfileRepository profileRepository,
      ProfileMapper profileMapper,
      PaymentTransactionRepository paymentTransactionRepository,
      RetainedSubscriptionEvidenceRepository retainedSubscriptionEvidenceRepository,
      ExternalAccountRepository externalAccountRepository,
      PasswordResetTokenRepository passwordResetTokenRepository,
      EmailVerificationTokenRepository emailVerificationTokenRepository,
      NotificationService notificationService,
      EmailService emailService,
      MailProperties mailProperties,
      AccountTokenProperties tokenProperties,
      JwtAuthProperties jwtAuthProperties,
      JwtUtil jwtUtil,
      net.firedevops.firemud.accountservice.service.session.SessionService sessionService,
      PlatformTransactionManager transactionManager) {
    this.accountRepository = accountRepository;
    this.accountAuthorityGenerationRepository = accountAuthorityGenerationRepository;
    this.accountAuthoritySourceEvidenceRepository = accountAuthoritySourceEvidenceRepository;
    this.accountAuthorityOutboxRepository = accountAuthorityOutboxRepository;
    this.passwordResetOperationRepository = passwordResetOperationRepository;
    this.accountAuthoritySourceEventReadback =
        new AccountAuthoritySourceEventReadback(
            accountAuthorityOutboxRepository,
            passwordResetOperationRepository,
            logoutAllOperationRepository,
            securityStateOperationRepository);
    this.passwordResetDraftSourceChanges = passwordResetDraftSourceChanges;
    this.accountAuditOutboxRepository = accountAuditOutboxRepository;
    this.accountJoinOperationRepository = accountJoinOperationRepository;
    this.accountEmailLoginChallengeRepository = accountEmailLoginChallengeRepository;
    this.accountRealmAccessGrantRepository = accountRealmAccessGrantRepository;
    this.accountTenantMembershipRepository = accountTenantMembershipRepository;
    this.accountMapper = accountMapper;
    this.profileRepository = profileRepository;
    this.profileMapper = profileMapper;
    this.paymentTransactionRepository = paymentTransactionRepository;
    this.retainedSubscriptionEvidenceRepository = retainedSubscriptionEvidenceRepository;
    this.externalAccountRepository = externalAccountRepository;
    this.passwordResetTokenRepository = passwordResetTokenRepository;
    this.emailVerificationTokenRepository = emailVerificationTokenRepository;
    this.notificationService = notificationService;
    this.emailService = emailService;
    this.mailProperties = mailProperties;
    this.tokenProperties = tokenProperties;
    this.jwtAuthProperties = jwtAuthProperties;
    this.jwtUtil = jwtUtil;
    this.sessionService = sessionService;
    this.joinTransactionTemplate = new TransactionTemplate(transactionManager);
    this.joinTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.joinTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    this.joinTransactionTemplate.setReadOnly(false);
    this.passwordResetOwnerTransaction = new TransactionTemplate(transactionManager);
    this.passwordResetOwnerTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.passwordResetOwnerTransaction.setIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.passwordResetOwnerTransaction.setReadOnly(false);
  }

  @Override
  @Transactional
  @Timed(value = "account.create")
  public AccountDto createAccount(CreateAccountRequest request) {
    logger.info("Creating global account {}", request.username());
    Account account = new Account();
    account.setUsername(request.username());
    account.setEmail(EmailCanonicalization.normalize(request.email()));
    account.setPasswordHash(hashPassword(request.password()));
    Account saved;
    try {
      saved = accountRepository.save(account);
    } catch (IntegrityConstraintViolationException | DataIntegrityViolationException ex) {
      throw new AccountAlreadyExistsException(ex);
    }
    AccountDto accountDto = accountMapper.toDto(saved);
    accountAuditOutboxRepository.append(
        UUID.randomUUID(),
        "platform",
        null,
        "ACCOUNT_REGISTERED",
        "{\"accountId\":\"" + accountDto.id() + "\"}");
    return accountDto;
  }

  @Override
  @Transactional
  @Timed(value = "account.authenticate")
  public net.firedevops.firemud.accountservice.dto.AuthenticationResult authenticate(
      String username, String password) {
    PrimaryAuthentication authentication = authenticateAccountIdentity(username, password, true);
    Account account = authentication.account();
    String accountUuid = requireAuthenticationAccountUuid(account).toString();
    authentication.emailLoginChallenge().ifPresent(accountEmailLoginChallengeRepository::delete);
    String token =
        mintToken(
            accountUuid,
            jwtAuthProperties.getJwtExpirationMs(),
            authenticationTokenClaims("control-ui", account));
    sessionService.storeAccountSession(
        account.getId(), token, jwtAuthProperties.getJwtExpirationMs());
    return new net.firedevops.firemud.accountservice.dto.AuthenticationResult(accountUuid, token);
  }

  @Override
  @Transactional
  @Timed(value = "account.authenticate_gameplay")
  public net.firedevops.firemud.accountservice.dto.AuthenticationResult authenticateForGameplay(
      String email, String password) {
    Account gameplayAccount =
        accountRepository
            .findByEmail(EmailCanonicalization.normalize(email))
            .orElseThrow(this::invalidCredentials);
    PrimaryAuthentication authentication =
        authenticateAccountIdentity(gameplayAccount, password, true);
    Account account = authentication.account();
    String accountUuid = requireAuthenticationAccountUuid(account).toString();
    authentication.emailLoginChallenge().ifPresent(accountEmailLoginChallengeRepository::delete);
    String token =
        mintToken(
            accountUuid,
            jwtAuthProperties.getJwtExpirationMs(),
            authenticationTokenClaims(GAMEPLAY_DELEGATION_AUDIENCE, account));
    sessionService.storeAccountSession(
        account.getId(), token, jwtAuthProperties.getJwtExpirationMs());
    return new net.firedevops.firemud.accountservice.dto.AuthenticationResult(accountUuid, token);
  }

  @Override
  @Transactional
  @Timed(value = "account.request_email_login_otp")
  public void requestEmailLoginOtp(String email) {
    Optional<Account> account =
        accountRepository.findByEmail(EmailCanonicalization.normalize(email));
    if (account.isEmpty()
        || !account.orElseThrow().isEmailVerified()
        || !allowsEmailLoginOtp(account.orElseThrow())) {
      return;
    }
    Account resolvedAccount = account.orElseThrow();
    try {
      requireAuthenticationEligible(resolvedAccount);
    } catch (AuthenticationException ex) {
      return;
    }
    accountEmailLoginChallengeRepository.lockAccountChallenge(resolvedAccount.getId());
    LocalDateTime now = LocalDateTime.now();
    Optional<AccountEmailLoginChallenge> existing =
        accountEmailLoginChallengeRepository.findByAccountId(resolvedAccount.getId());
    if (existing.filter(challenge -> challenge.getResendAvailableAt().isAfter(now)).isPresent()) {
      return;
    }
    existing.ifPresent(accountEmailLoginChallengeRepository::delete);
    String code = String.format("%06d", EMAIL_LOGIN_OTP_RANDOM.nextInt(1_000_000));
    AccountEmailLoginChallenge challenge = new AccountEmailLoginChallenge();
    challenge.setAccountId(resolvedAccount.getId());
    challenge.setCodeHash(hashPassword(code));
    challenge.setExpiresAt(now.plusMinutes(10));
    challenge.setResendAvailableAt(now.plusSeconds(60));
    challenge.setCreatedAt(now);
    challenge.setUpdatedAt(now);
    accountEmailLoginChallengeRepository.save(challenge);
    runAfterCommit(() -> safeSendEmailLoginOtp(resolvedAccount.getEmail(), code));
  }

  @Override
  @Transactional
  @Timed(value = "account.verify_email_login_otp")
  public net.firedevops.firemud.accountservice.dto.AuthenticationResult verifyEmailLoginOtp(
      String email, String code) {
    Account account =
        accountRepository
            .findByEmail(EmailCanonicalization.normalize(email))
            .orElseThrow(this::invalidCredentials);
    if (!allowsEmailLoginOtp(account)) {
      throw invalidCredentials();
    }
    accountEmailLoginChallengeRepository.lockAccountChallenge(account.getId());
    AccountEmailLoginChallenge challenge =
        accountEmailLoginChallengeRepository
            .findByAccountId(account.getId())
            .orElseThrow(this::invalidCredentials);
    LocalDateTime now = LocalDateTime.now();
    if (challenge.getExpiresAt().isBefore(now)
        || challenge.getInvalidAttemptCount() >= EMAIL_LOGIN_OTP_MAX_ATTEMPTS
        || !verifyPassword(code == null ? "" : code, challenge.getCodeHash())) {
      recordFailedEmailLoginAttempt(challenge, now);
      throw invalidCredentials();
    }
    requireAuthenticationEligible(account);
    String accountUuid = requireAuthenticationAccountUuid(account).toString();
    accountEmailLoginChallengeRepository.delete(challenge);
    String token =
        mintToken(
            accountUuid,
            jwtAuthProperties.getJwtExpirationMs(),
            authenticationTokenClaims(GAMEPLAY_DELEGATION_AUDIENCE, account));
    sessionService.storeAccountSession(
        account.getId(), token, jwtAuthProperties.getJwtExpirationMs());
    return new net.firedevops.firemud.accountservice.dto.AuthenticationResult(accountUuid, token);
  }

  @Override
  @Transactional
  @Timed(value = "account.player_bootstrap")
  public PlayerBootstrapResult issuePlayerBootstrap(String accountIdentifier, String secret) {
    PrimaryAuthentication authentication =
        authenticateAccountIdentity(accountIdentifier, secret, true);
    Account account = authentication.account();
    String accountUuid = requireAuthenticationAccountUuid(account).toString();
    authentication.emailLoginChallenge().ifPresent(accountEmailLoginChallengeRepository::delete);
    String jti = UUID.randomUUID().toString();
    long issuedAt = System.currentTimeMillis();
    long expiresAt = issuedAt + tokenProperties.getPlayerBootstrapExpirationMs();
    String bootstrapToken =
        mintToken(
            accountUuid,
            tokenProperties.getPlayerBootstrapExpirationMs(),
            Map.of("aud", "player-bootstrap", "accountId", accountUuid, "jti", jti));
    sessionService.storeAccountSession(
        account.getId(), bootstrapToken, tokenProperties.getPlayerBootstrapExpirationMs());
    logger.info("Issued player bootstrap token for account {}", account.getId());
    return new PlayerBootstrapResult(
        accountUuid,
        bootstrapToken,
        Instant.ofEpochMilli(issuedAt).toString(),
        Instant.ofEpochMilli(expiresAt).toString());
  }

  /**
   * Explicit held composition only. The canonical source remains unregistered until its owning
   * runtime boundary is qualified; absence never selects the retained numeric discovery path.
   */
  @Autowired(required = false)
  public void setPublicBootstrapWorldsOwner(AccountPublicBootstrapWorldsOwner owner) {
    this.publicBootstrapWorldsOwner = Objects.requireNonNull(owner);
  }

  // Bootstrap/JOIN orchestration deliberately has no transaction advice: NOT_SUPPORTED would
  // still create empty synchronization. These entry points reject ambient SQL, while each
  // canonical owner opens its own short transaction around owner-local state only.
  @Override
  @Timed(value = "account.bootstrap_worlds")
  public List<BootstrapWorldDto> listBootstrapWorlds(String bootstrapToken) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Public bootstrap world discovery refuses ambient SQL");
    }
    AccountPublicBootstrapWorldsOwner owner = publicBootstrapWorldsOwner;
    if (owner == null) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Canonical public-world discovery source is unavailable");
    }
    return owner.listWorlds(bootstrapToken);
  }

  /**
   * Explicit held composition; the owner retains the signed V2 selector before publication. Its
   * direct-text entry point authenticates the existing Game Session caller separately.
   */
  @Autowired(required = false)
  public void setCanonicalPublicRealmScopeOwner(AccountCanonicalPublicRealmScopeOwner owner) {
    this.canonicalPublicRealmScopeOwner = Objects.requireNonNull(owner);
  }

  /** Explicit composition of the existing canonical consumers; this does not register an issuer. */
  public void setCanonicalScopeConsumers(
      AccountCanonicalConnectScopeReader reader,
      CanonicalGameplayAdmissionDecisionReadClient targets,
      AccountTenantAuthorityEventRepository tenantSources,
      AccountDemoTenantEntitlementRepository demos,
      AccountHostingSubscriptionLifecycleRepository hosting,
      StripeHostingSubscriptionObserver observer,
      AccountCanonicalFirstJoinTerminalCoordinator firstJoin,
      String namespace,
      Clock clock) {
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    canonicalScopeConsumers =
        new CanonicalScopeConsumers(
            Objects.requireNonNull(reader),
            Objects.requireNonNull(targets),
            Objects.requireNonNull(tenantSources),
            Objects.requireNonNull(demos),
            Objects.requireNonNull(hosting),
            Objects.requireNonNull(observer),
            Objects.requireNonNull(firstJoin),
            namespace,
            Objects.requireNonNull(clock));
  }

  private CanonicalScopeConsumers requireCanonicalScopeConsumers() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Canonical scope consumers refuse ambient Account SQL");
    }
    if (canonicalScopeConsumers == null) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Canonical scope consumers are unavailable");
    }
    return canonicalScopeConsumers;
  }

  private Target readCanonicalScopeTarget(
      CanonicalScopeConsumers consumers, CanonicalJoinScopeV2 scope) {
    return readCanonicalScopePublishedTarget(consumers, scope).currentTarget();
  }

  private PublishedRealmEvidence readCanonicalScopePublishedTarget(
      CanonicalScopeConsumers consumers, CanonicalJoinScopeV2 scope) {
    try {
      RetainedTargetSelector selector =
          new RetainedTargetSelector(
              UUID.randomUUID(),
              scope.accountId(),
              consumers.namespace(),
              scope.tenantId(),
              scope.tenantSlug(),
              scope.worldSlug(),
              scope.realmId(),
              scope.realmSlug(),
              scope.playableStateNamespaceId(),
              scope.playableStateScope(),
              scope.gameInstanceId(),
              scope.catalogRevision(),
              scope.pointerVersion());
      var resolved =
          Objects.requireNonNull(
              consumers.targets().resolvePublishedRealmAdmissionTarget(selector));
      // Only a locally verified target mismatch is definitive. Exceptions from either remote
      // owner (including malformed evidence) remain unavailable rather than condemning the intent.
      try {
        net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec
            .requireRetainedTargetMatches(selector, resolved.currentTarget());
      } catch (IllegalArgumentException mismatch) {
        throw new AuthenticationException(
            "CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE, mismatch);
      }
      if (!selector.equals(resolved.selector()))
        throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
      var published =
          Objects.requireNonNull(
              consumers.targets().readPublishedRealmAdmissionOwner(resolved.strictSelector()));
      if (!resolved.strictSelector().equals(published.selector())
          || !resolved.currentTarget().equals(published.currentTarget())) {
        throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
      }
      return published;
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new AuthenticationException(
          "ADMISSION_POINTER_UNAVAILABLE",
          "Gameplay target authority is unavailable; retry the same request",
          failure);
    }
  }

  private Account lockCanonicalScopeAccount(CanonicalJoinScopeV2 scope) {
    Account account =
        accountRepository
            .findByAccountUuidForUpdate(scope.accountId())
            .orElseThrow(
                () -> new AuthenticationException("AUTH_UNAVAILABLE", "Account is unavailable"));
    requireAccountStorageId(account, scope.accountId());
    requireAuthenticationEligible(account);
    return account;
  }

  private CanonicalJoinPreparation prepareCanonicalJoin(
      CanonicalScopeConsumers consumers,
      CanonicalJoinScopeV2 scope,
      String requestId,
      String callerBinding) {
    // Retain the exact policy-independent intent before any fallible source/provider work.
    joinTransactionTemplate.executeWithoutResult(
        status -> {
          lockCanonicalScopeAccount(scope);
          accountJoinOperationRepository.insertCanonicalIntent(requestId, scope, callerBinding);
        });
    return Objects.requireNonNull(
        joinTransactionTemplate.execute(
            status -> {
              lockCanonicalScopeAccount(scope);
              CanonicalJoinOperationEvidence operation =
                  accountJoinOperationRepository
                      .findCanonicalEvidenceForUpdateByRequestId(requestId)
                      .orElseThrow();
              if ("FAILED".equals(operation.status())) {
                return new CanonicalJoinPreparation(operation, null, null, null);
              }
              SourceSelection source =
                  consumers.tenantSources().readCurrentSourceSelection(scope.tenantId());
              return switch (source.kind()) {
                case PAID_HOSTING ->
                    new CanonicalJoinPreparation(
                        operation,
                        source,
                        null,
                        consumers.hosting().prepareCurrentRead(scope.tenantId()));
                case NON_PAID_DEMO ->
                    new CanonicalJoinPreparation(
                        operation, source, consumers.demos().readCurrent(scope.tenantId()), null);
              };
            }));
  }

  private CanonicalJoinResult commitCanonicalJoin(
      CanonicalScopeConsumers consumers,
      CanonicalJoinScopeV2 scope,
      String requestId,
      String callerBinding,
      CanonicalJoinPreparation prepared,
      StripeHostingSubscriptionObserver.Result observation) {
    return Objects.requireNonNull(
        joinTransactionTemplate.execute(
            status -> {
              lockCanonicalScopeAccount(scope);
              requireCanonicalScopeFresh(consumers, scope);
              CanonicalJoinOperationEvidence lockedOperation =
                  accountJoinOperationRepository
                      .findCanonicalEvidenceForUpdateByRequestId(requestId)
                      .orElseThrow();
              if ("FAILED".equals(lockedOperation.status())) {
                // Preparation precedes provider/target reads. Another exact attempt may have
                // finished meanwhile, including before any policy evidence could be bound.
                var retained =
                    accountJoinOperationRepository.finishCanonicalFailure(
                        requestId, scope, callerBinding, lockedOperation.outcome());
                return new CanonicalJoinResult(retained.outcome(), null, false, true);
              }
              SourceSelection current =
                  consumers.tenantSources().readCurrentSourceSelection(scope.tenantId());
              if (current.kind() != prepared.source().kind()
                  || !prepared.source().matches(current.checkpoint())) {
                throw new AuthenticationException(
                    "ENTITLEMENT_UNAVAILABLE", "Tenant source changed");
              }
              final boolean gameplayAvailable;
              final boolean allowPublicJoin;
              final long entitlementVersion;
              if (prepared.fence() != null) {
                AccountHostingSubscriptionCurrentReceipt receipt =
                    consumers.hosting().readCurrent(prepared.fence(), observation);
                gameplayAvailable = receipt.gameplayAvailable();
                allowPublicJoin = receipt.allowPublicJoin();
                entitlementVersion = receipt.entitlementVersion();
              } else {
                DemoTenantEntitlementSnapshot demo = consumers.demos().revalidate(prepared.demo());
                gameplayAvailable = demo.gameplayAvailable();
                allowPublicJoin = demo.allowPublicJoin();
                entitlementVersion = demo.entitlementVersion();
              }
              CanonicalJoinOperationEvidence policyBoundOperation =
                  accountJoinOperationRepository.bindCanonicalPolicyEvidence(
                      requestId, scope, callerBinding, allowPublicJoin, entitlementVersion);
              Optional<AccountTenantMembership> member =
                  accountTenantMembershipRepository.findFreshMembershipForUpdate(
                      scope.accountId(), scope.tenantId());
              boolean active =
                  member.filter(value -> "ACTIVE".equals(value.getLifecycleState())).isPresent();
              if ("PENDING".equals(policyBoundOperation.status())
                  && (!gameplayAvailable || (!active && !allowPublicJoin))) {
                String outcome =
                    gameplayAvailable
                        ? "PUBLIC_PRODUCTION_ADMISSION_DENIED"
                        : "TENANT_BILLING_BLOCKED";
                accountJoinOperationRepository.finishCanonicalFailure(
                    requestId, scope, callerBinding, outcome);
                return new CanonicalJoinResult(outcome, null, false, false);
              }
              var committed =
                  consumers
                      .firstJoin()
                      .commitCanonicalJoin(
                          scope,
                          requestId,
                          callerBinding,
                          prepared.fence() == null ? null : consumers.hosting(),
                          prepared.fence(),
                          observation);
              requireCanonicalScopeFresh(consumers, scope);
              Optional<AccountTenantMembership> currentMembership =
                  accountTenantMembershipRepository.findFreshMembershipForUpdate(
                      scope.accountId(), scope.tenantId());
              if ("COMMITTED".equals(policyBoundOperation.status())) {
                // A committed exact retry returns its original operation proof after the current
                // policy binding above. Membership may have changed since that proof was retained;
                // expose only the current admission flag, never use the old proof as admission.
                return new CanonicalJoinResult(
                    committed.outcome(),
                    committed.terminalProof(),
                    currentMembership
                        .map(AccountTenantMembership::isGameplayAdmissionAllowed)
                        .orElse(false),
                    true);
              }
              AccountTenantMembership committedMembership = currentMembership.orElseThrow();
              if (committedMembership.getMembershipVersion()
                      != committed.terminalProof().membershipVersion()
                  || committedMembership.getMembershipAuthorityGeneration()
                      != committed.terminalProof().membershipAuthorityGeneration()) {
                throw new IllegalStateException(
                    "Canonical JOIN result differs from current membership");
              }
              return new CanonicalJoinResult(
                  committed.outcome(),
                  committed.terminalProof(),
                  committedMembership.isGameplayAdmissionAllowed(),
                  false);
            }));
  }

  private void requireCanonicalScopeFresh(
      CanonicalScopeConsumers consumers, CanonicalJoinScopeV2 scope) {
    Instant now = consumers.clock().instant();
    if (now.isBefore(Instant.parse(scope.evaluatedAt()))
        || !now.isBefore(Instant.parse(scope.connectScopeExpiresAt()))) {
      throw new AuthenticationException("CONNECT_SCOPE_INVALID", INVALID_CONNECT_SCOPE_MESSAGE);
    }
  }

  private record CanonicalScopeConsumers(
      AccountCanonicalConnectScopeReader reader,
      CanonicalGameplayAdmissionDecisionReadClient targets,
      AccountTenantAuthorityEventRepository tenantSources,
      AccountDemoTenantEntitlementRepository demos,
      AccountHostingSubscriptionLifecycleRepository hosting,
      StripeHostingSubscriptionObserver observer,
      AccountCanonicalFirstJoinTerminalCoordinator firstJoin,
      String namespace,
      Clock clock) {}

  private record CanonicalJoinPreparation(
      CanonicalJoinOperationEvidence operation,
      SourceSelection source,
      DemoTenantEntitlementSnapshot demo,
      ReadFence fence) {}

  private record CanonicalJoinResult(
      String outcome,
      CanonicalJoinTerminalProof proof,
      boolean gameplayAdmissionAllowed,
      boolean replayed) {}

  /** Explicit non-admitting Entity projection; no default runtime client is registered here. */
  public void setAccountBootstrapRosterReadClient(AccountBootstrapRosterReadClient client) {
    accountBootstrapRosterReadClient = Objects.requireNonNull(client);
  }

  private BootstrapCharacterAuthority prepareBootstrapCharacterAuthority(
      CanonicalScopeConsumers consumers, CanonicalJoinScopeV2 scope) {
    return Objects.requireNonNull(
        joinTransactionTemplate.execute(
            status -> {
              lockCanonicalScopeAccount(scope);
              requireCanonicalScopeFresh(consumers, scope);
              var membership = readBootstrapCharacterMembership(scope);
              try {
                SourceSelection source =
                    consumers.tenantSources().readCurrentSourceSelection(scope.tenantId());
                return new BootstrapCharacterAuthority(
                    source,
                    membership,
                    source.kind() == AccountTenantAuthorityEventRepository.SourceKind.NON_PAID_DEMO
                        ? consumers.demos().readCurrent(scope.tenantId())
                        : null,
                    source.kind() == AccountTenantAuthorityEventRepository.SourceKind.PAID_HOSTING
                        ? consumers.hosting().prepareCurrentRead(scope.tenantId())
                        : null,
                    consumers.clock().instant());
              } catch (RuntimeException failure) {
                throw new AuthenticationException(
                    "ENTITLEMENT_UNAVAILABLE",
                    "Bootstrap entitlement source is unavailable",
                    failure);
              }
            }));
  }

  private Optional<AccountCanonicalPublicRealmScopeOwner.ExistingMembership>
      readBootstrapCharacterMembership(CanonicalJoinScopeV2 scope) {
    try {
      return accountTenantMembershipRepository
          .findFreshMembershipForUpdate(scope.accountId(), scope.tenantId())
          .map(
              member ->
                  new AccountCanonicalPublicRealmScopeOwner.ExistingMembership(
                      member.getId(),
                      member.getLifecycleState(),
                      member.isGameplayAdmissionAllowed(),
                      member.getMembershipVersion(),
                      member.getMembershipAuthorityGeneration()));
    } catch (RuntimeException failure) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Bootstrap membership source is unavailable", failure);
    }
  }

  private void requireBootstrapCharacterAuthority(
      CanonicalScopeConsumers consumers,
      CanonicalJoinScopeV2 scope,
      BootstrapCharacterAuthority prepared,
      StripeHostingSubscriptionObserver.Result observation) {
    joinTransactionTemplate.executeWithoutResult(
        status -> {
          lockCanonicalScopeAccount(scope);
          requireCanonicalScopeFresh(consumers, scope);
          var membership = readBootstrapCharacterMembership(scope);
          if (!prepared.membership().equals(membership)) {
            throw new AuthenticationException(
                "AUTH_UNAVAILABLE", "Bootstrap membership changed during discovery");
          }
          final boolean gameplayAvailable;
          final boolean allowPublicJoin;
          try {
            SourceSelection current =
                consumers.tenantSources().readCurrentSourceSelection(scope.tenantId());
            if (current.kind() != prepared.source().kind()
                || !prepared.source().matches(current.checkpoint())) {
              throw new IllegalStateException("Bootstrap entitlement source changed");
            }
            if (prepared.fence() != null) {
              var receipt = consumers.hosting().readCurrent(prepared.fence(), observation);
              gameplayAvailable = receipt.gameplayAvailable();
              allowPublicJoin = receipt.allowPublicJoin();
            } else {
              Instant currentTime = consumers.clock().instant();
              if (currentTime.isBefore(prepared.observedAt())
                  || !currentTime.isBefore(prepared.observedAt().plusSeconds(15))) {
                throw new IllegalStateException(
                    "Original bootstrap entitlement observation expired");
              }
              var demo = consumers.demos().revalidate(prepared.demo());
              gameplayAvailable = demo.gameplayAvailable();
              allowPublicJoin = demo.allowPublicJoin();
            }
          } catch (RuntimeException failure) {
            throw new AuthenticationException(
                "ENTITLEMENT_UNAVAILABLE",
                "Bootstrap entitlement source is unavailable or changed",
                failure);
          }
          if (!gameplayAvailable) {
            throw new AuthenticationException(
                "TENANT_BILLING_BLOCKED", "Gameplay is not available for this tenant");
          }
          if (membership.isEmpty()
              || "INACTIVE".equals(membership.orElseThrow().lifecycleState())) {
            throw new AuthenticationException(
                allowPublicJoin ? "JOIN_REQUIRED" : "PUBLIC_PRODUCTION_ADMISSION_DENIED",
                allowPublicJoin ? JOIN_REQUIRED_CHARACTERS_MESSAGE : "Public enrollment is closed");
          }
          if (!"ACTIVE".equals(membership.orElseThrow().lifecycleState())
              || !membership.orElseThrow().gameplayAdmissionAllowed()) {
            throw new AuthenticationException(
                "PUBLIC_PRODUCTION_ADMISSION_DENIED", "Membership does not allow actor discovery");
          }
        });
  }

  private record BootstrapCharacterAuthority(
      SourceSelection source,
      Optional<AccountCanonicalPublicRealmScopeOwner.ExistingMembership> membership,
      DemoTenantEntitlementSnapshot demo,
      ReadFence fence,
      Instant observedAt) {}

  private StripeHostingSubscriptionObserver.Result observeBootstrapCharacterSource(
      CanonicalScopeConsumers consumers, BootstrapCharacterAuthority prepared) {
    if (prepared.fence() == null) return null;
    try {
      return consumers.observer().observe(prepared.fence().binding());
    } catch (RuntimeException failure) {
      throw new AuthenticationException(
          "ENTITLEMENT_UNAVAILABLE", "Bootstrap provider source is unavailable", failure);
    }
  }

  private void requireSameBootstrapCaller(BootstrapContext original, String token) {
    if (!original.equals(requireBootstrapContext(token))) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Bootstrap caller changed during actor discovery");
    }
  }

  @Override
  @Timed(value = "account.bootstrap_realms")
  public List<BootstrapRealmDto> listBootstrapRealms(
      String bootstrapToken, String tenantSlug, String worldSlug) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Public bootstrap realm discovery refuses ambient SQL");
    }
    AccountCanonicalPublicRealmScopeOwner owner = canonicalPublicRealmScopeOwner;
    if (owner == null) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Canonical public-realm scope source is unavailable");
    }
    return owner.listRealmScopes(bootstrapToken, tenantSlug, worldSlug).stream()
        .map(
            retained -> {
              var target = retained.target();
              var scope = retained.scope();
              return new BootstrapRealmDto(
                  scope.worldSlug(),
                  scope.realmSlug(),
                  scope.realmId(),
                  target.realmDisplayName(),
                  scope.tenantId(),
                  scope.gameInstanceId(),
                  scope.pointerVersion(),
                  scope.catalogRevision(),
                  scope.playableStateNamespaceId(),
                  PlayableStateScope.valueOf("PLAYABLE_STATE_SCOPE_" + scope.playableStateScope())
                      .name(),
                  scope.playableStateScope(),
                  target.characterCreationPolicy(),
                  scope.evaluatedAt(),
                  scope.connectScopeExpiresAt(),
                  scope.connectScopeId());
            })
        .toList();
  }

  @Override
  @Timed(value = "account.bootstrap_characters")
  public List<BootstrapCharacterDto> listBootstrapCharacters(
      String bootstrapToken, String worldSlug, String realmSlug, String connectScopeId) {
    CanonicalScopeConsumers consumers = requireCanonicalScopeConsumers();
    AccountBootstrapRosterReadClient roster = accountBootstrapRosterReadClient;
    if (roster == null)
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Canonical Entity bootstrap discovery is unavailable");
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    CanonicalJoinScopeV2 scope =
        consumers.reader().read(bootstrapContext.accountUuid(), connectScopeId);
    if (!scope.worldSlug().equals(worldSlug) || !scope.realmSlug().equals(realmSlug)) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
    }
    if (!"SHARED".equals(scope.playableStateScope())) {
      throw new AuthenticationException(
          "REALM_UNAVAILABLE", "Bootstrap actor discovery does not yet support this realm policy");
    }
    PublishedRealmEvidence published = readCanonicalScopePublishedTarget(consumers, scope);
    if (!"SHARED".equals(published.currentTarget().playableStateScope())
        || !"PRESEEDED_ONLY".equals(published.currentTarget().characterCreationPolicy())) {
      throw new AuthenticationException(
          "REALM_UNAVAILABLE", "Bootstrap actor discovery does not yet support this realm policy");
    }
    BootstrapCharacterAuthority prepared = prepareBootstrapCharacterAuthority(consumers, scope);
    var observation = observeBootstrapCharacterSource(consumers, prepared);
    requireBootstrapCharacterAuthority(consumers, scope, prepared, observation);
    requireSameBootstrapCaller(bootstrapContext, bootstrapToken);
    final List<AccountBootstrapRosterReadClient.Actor> actors;
    try {
      actors = List.copyOf(roster.read(UUID.randomUUID(), scope.accountId(), published));
    } catch (io.grpc.StatusRuntimeException failure) {
      String code =
          failure.getStatus().getCode() == io.grpc.Status.Code.FAILED_PRECONDITION
              ? "CONNECT_SCOPE_MISMATCH"
              : "AUTH_UNAVAILABLE";
      throw new AuthenticationException(
          code, "Entity bootstrap discovery is unavailable or changed", failure);
    } catch (RuntimeException failure) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Entity bootstrap discovery is malformed or unavailable", failure);
    }
    PublishedRealmEvidence current = readCanonicalScopePublishedTarget(consumers, scope);
    if (!published.currentTarget().equals(current.currentTarget())
        || !java.util.Arrays.equals(
            published.sourceEvidence().publishedPolicySetEvidence().canonicalBytes(),
            current.sourceEvidence().publishedPolicySetEvidence().canonicalBytes())) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
    }
    requireSameBootstrapCaller(bootstrapContext, bootstrapToken);
    var finalObservation = observeBootstrapCharacterSource(consumers, prepared);
    requireBootstrapCharacterAuthority(consumers, scope, prepared, finalObservation);
    requireCanonicalScopeFresh(consumers, scope);
    requireSameBootstrapCaller(bootstrapContext, bootstrapToken);
    return actors.stream()
        .sorted(
            Comparator.comparing(AccountBootstrapRosterReadClient.Actor::displayName)
                .thenComparing(actor -> actor.characterId().toString()))
        .map(
            actor ->
                new BootstrapCharacterDto(
                    actor.characterId().toString(),
                    actor.displayName(),
                    actor.level(),
                    scope.playableStateScope(),
                    published.currentTarget().characterCreationPolicy()))
        .toList();
  }

  @Override
  @Timed(value = "account.public_production_join")
  public JoinPublicProductionResult joinPublicProduction(
      String bootstrapToken, JoinPublicProductionRequest request) {
    CanonicalScopeConsumers consumers = requireCanonicalScopeConsumers();
    BootstrapContext caller = requireBootstrapContext(bootstrapToken);
    Claims callerClaims =
        requireSignedTokenClaims(
            bootstrapToken,
            "player-bootstrap",
            "CONNECT_CONTEXT_INVALID",
            "Missing bootstrap token",
            "Invalid bootstrap token");
    String callerBinding;
    try {
      callerBinding = JwtClaims.requireText(callerClaims.get("jti"), "jti");
    } catch (IllegalArgumentException ex) {
      throw new AuthenticationException(
          "CONNECT_CONTEXT_INVALID", "Bootstrap caller binding is missing", ex);
    }
    return joinCanonicalPublicProduction(
        consumers,
        caller.accountUuid(),
        callerBinding,
        request,
        () -> {
          BootstrapContext current = requireBootstrapContext(bootstrapToken);
          if (!caller.equals(current)) {
            throw new AuthenticationException("AUTH_UNAVAILABLE", "Bootstrap caller changed");
          }
        });
  }

  private JoinPublicProductionResult joinCanonicalPublicProduction(
      CanonicalScopeConsumers consumers,
      UUID accountUuid,
      String callerBinding,
      JoinPublicProductionRequest request,
      Runnable recheckCaller) {
    if (request == null
        || !StringUtils.hasText(request.connectScopeId())
        || !StringUtils.hasText(request.requestId())) {
      throw new AuthenticationException("CONNECT_SCOPE_INVALID", INVALID_CONNECT_SCOPE_MESSAGE);
    }
    if (request.requestId().length() > JoinPublicProductionRequest.MAX_REQUEST_ID_LENGTH) {
      throw new AuthenticationException(
          "INVALID_ARGUMENT", "JOIN requestId exceeds the maximum length");
    }
    CanonicalJoinScopeV2 scope = consumers.reader().read(accountUuid, request.connectScopeId());
    try {
      CanonicalJoinPreparation prepared =
          prepareCanonicalJoin(consumers, scope, request.requestId(), callerBinding);
      StripeHostingSubscriptionObserver.Result observation = null;
      if (!"FAILED".equals(prepared.operation().status())) {
        Target target = readCanonicalScopeTarget(consumers, scope);
        // The SDK's sealed result crosses source transactions; provider I/O never runs under SQL.
        observation =
            prepared.fence() == null
                ? null
                : consumers.observer().observe(prepared.fence().binding());
        if (!target.equals(readCanonicalScopeTarget(consumers, scope))) {
          throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
        }
      }
      // Terminal failures need no remote authority, but retain caller and locked identity checks.
      recheckCaller.run();
      CanonicalJoinResult committed =
          commitCanonicalJoin(
              consumers, scope, request.requestId(), callerBinding, prepared, observation);
      CanonicalJoinTerminalProof proof = committed.proof();
      // Publish only after the owner transaction has committed; response loss is exact-retryable.
      return new JoinPublicProductionResult(
          proof != null,
          committed.outcome(),
          scope.accountId().toString(),
          scope.tenantId(),
          proof == null ? 0L : proof.membershipId(),
          proof == null ? 0L : proof.membershipVersion(),
          proof == null ? 0L : proof.membershipAuthorityGeneration(),
          committed.gameplayAdmissionAllowed(),
          committed.replayed());
    } catch (AccountJoinOperationRepository.CanonicalJoinOperationConflictException failure) {
      throw new AuthenticationException(
          "IDEMPOTENCY_CONFLICT", "JOIN request ID conflicts", failure);
    } catch (RuntimeException failure) {
      String code =
          failure instanceof AuthenticationException authentication
              ? authentication.getCode()
              : "ENTITLEMENT_UNAVAILABLE";
      try {
        recordCanonicalJoinFailure(scope, request.requestId(), callerBinding, code);
      } catch (RuntimeException recordFailure) {
        if (recordFailure != failure) failure.addSuppressed(recordFailure);
        logger.warn(
            "Canonical JOIN failure state could not be recorded; cause={}",
            recordFailure.getClass().getSimpleName());
      }
      if (failure instanceof AuthenticationException authentication) throw authentication;
      throw new AuthenticationException(
          code, "Canonical JOIN authority is unavailable; retry the same request", failure);
    }
  }

  private void recordCanonicalJoinFailure(
      CanonicalJoinScopeV2 scope, String requestId, String callerBinding, String failureCode) {
    // Expiry is terminal for this caller attempt, not a new durable operation outcome. The
    // reader rejects the same expired scope without rewriting its retained intent; preserve
    // that behavior when time crosses the boundary during provider I/O or the commit gate.
    if ("CONNECT_SCOPE_INVALID".equals(failureCode)) return;
    joinTransactionTemplate.executeWithoutResult(
        status -> {
          lockCanonicalScopeAccount(scope);
          Optional<CanonicalJoinOperationEvidence> found =
              accountJoinOperationRepository.findCanonicalEvidenceForUpdateByRequestId(requestId);
          if (found.isEmpty() || !"PENDING".equals(found.orElseThrow().status())) return;
          if ("CONNECT_SCOPE_MISMATCH".equals(failureCode)) {
            accountJoinOperationRepository.finishCanonicalFailure(
                requestId, scope, callerBinding, failureCode);
          } else {
            String retryCode =
                List.of(
                            "ADMISSION_POINTER_UNAVAILABLE",
                            "ENTITLEMENT_UNAVAILABLE",
                            "AUTH_UNAVAILABLE")
                        .contains(failureCode)
                    ? failureCode
                    : "AUTH_UNAVAILABLE";
            String authorityAvailability =
                "ENTITLEMENT_UNAVAILABLE".equals(retryCode) ? "UNAVAILABLE" : "NOT_EVALUATED";
            accountJoinOperationRepository.recordCanonicalAttemptFailure(
                requestId, scope, callerBinding, authorityAvailability, retryCode);
          }
        });
  }

  @Override
  @Timed(value = "account.direct_text_connect_scope")
  public DirectTextJoinScope issueDirectTextConnectScope(
      DirectTextCallerContext caller, DirectTextJoinTarget target) {
    if (canonicalPublicRealmScopeOwner == null) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Canonical scope producer is unavailable");
    }
    CanonicalJoinScopeV2 scope =
        canonicalPublicRealmScopeOwner.issueDirectTextScope(caller, target).scope();
    return new DirectTextJoinScope(
        scope.connectScopeId(), scope.connectScopeExpiresAt(), scope.evaluatedAt());
  }

  @Override
  @Timed(value = "account.public_production_join_internal")
  public JoinPublicProductionResult joinPublicProductionFromGameSession(
      DirectTextCallerContext caller, JoinPublicProductionRequest request) {
    CanonicalScopeConsumers consumers = requireCanonicalScopeConsumers();
    requireCanonicalDirectPeer(consumers);
    if (caller == null
        || caller.accountId() == null
        || NIL_ACCOUNT_UUID.equals(caller.accountId())
        || !StringUtils.hasText(caller.sessionId())
        || caller.sessionId().length() > 32
        || !caller.sessionId().matches("[0-9]+")
        || !isPositiveTransportSession(caller.sessionId())
        || !StringUtils.hasText(caller.requestId())
        || request == null
        || !caller.requestId().equals(request.requestId())) {
      throw new AuthenticationException(
          "CONNECT_CONTEXT_INVALID", "JOIN caller context is missing");
    }
    CanonicalJoinScopeV2 scope =
        consumers.reader().read(caller.accountId(), request.connectScopeId());
    if (!scope.tenantId().equals(caller.tenantId())
        || !scope.realmId().equals(caller.realmId())
        || !scope.playableStateNamespaceId().equals(caller.playableStateNamespaceId())
        || !scope.playableStateScope().equals(caller.playableStateScope())
        || !scope.gameInstanceId().equals(caller.gameInstanceId())) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
    }
    return joinCanonicalPublicProduction(
        consumers,
        caller.accountId(),
        caller.sessionId(),
        request,
        () -> requireCanonicalDirectPeer(consumers));
  }

  private static boolean isPositiveTransportSession(String value) {
    try {
      return Long.parseLong(value) > 0;
    } catch (NumberFormatException malformed) {
      return false;
    }
  }

  private void requireCanonicalDirectPeer(CanonicalScopeConsumers consumers) {
    var peer = net.firedevops.firemud.common.grpc.GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("game-session-service")
        || !peer.isInNamespace(consumers.namespace())) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Exact Game Session workload is required");
    }
  }

  private String responseAccountUuid(Long accountId) {
    try {
      return responseAccountUuid(requireAccount(accountId), accountId);
    } catch (RuntimeException ex) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Account response authority is unavailable", ex);
    }
  }

  private String responseAccountUuid(Account account, Long accountId) {
    try {
      if (!Objects.equals(accountId, account.getId())) {
        throw new IllegalStateException("Account response readback returned a different owner");
      }
      return requireAuthenticationAccountUuid(account).toString();
    } catch (RuntimeException ex) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Account response authority is unavailable", ex);
    }
  }

  private RuntimeEntitlementsDto readTenantEntitlements(long tenantId, boolean lockSubscription) {
    // Retained numeric rows cannot supply canonical identity, provider provenance, current-row
    // selection, or a committed billing checkpoint. Deny before any historical evidence read.
    throw new AuthenticationException(
        "ENTITLEMENT_UNAVAILABLE",
        "Canonical tenant entitlement authority is unavailable; retry later");
  }

  @Override
  @Timed(value = "account.connect_token")
  public ConnectTokenResult issueConnectToken(String bootstrapToken, ConnectTokenRequest request) {
    CanonicalScopeConsumers consumers = requireCanonicalScopeConsumers();
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    consumers.reader().read(bootstrapContext.accountUuid(), request.connectScopeId());
    throw new AuthenticationException(
        "AUTH_UNAVAILABLE", "Canonical gameplay connect-token authority is unavailable");
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.runtime_membership")
  public RuntimeMembershipDto getTenantMembershipForRuntime(
      Long accountId, Long tenantId, String requestId) {
    requireAccount(accountId);
    Optional<AccountTenantMembership> membership =
        accountTenantMembershipRepository.findByAccountIdAndTenantId(accountId, tenantId);
    return new RuntimeMembershipDto(
        accountId,
        tenantId,
        membership.isPresent(),
        membership.map(AccountTenantMembership::isGameplayAdmissionAllowed).orElse(false),
        membership.map(AccountTenantMembership::getMembershipVersion).orElse(0L),
        membership.map(AccountTenantMembership::getLifecycleState).orElse("MISSING"),
        membership.map(AccountTenantMembership::getMembershipAuthorityGeneration).orElse(0L),
        Instant.now().toString());
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.realm_access_grant_runtime")
  public RealmAccessGrantResult getRealmAccessGrantForRuntime(
      Long accountId, Long tenantId, String worldSlug, String realmSlug, String requestId) {
    requireAccount(accountId);
    Instant evaluatedAt = Instant.now();
    return accountRealmAccessGrantRepository
        .findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            accountId, tenantId, worldSlug, realmSlug)
        .map(
            grant ->
                new RealmAccessGrantResult(
                    accountId,
                    tenantId,
                    worldSlug,
                    realmSlug,
                    grant.isGranted(),
                    grant.getGrantVersion(),
                    evaluatedAt.toString()))
        .orElseGet(
            () ->
                new RealmAccessGrantResult(
                    accountId, tenantId, worldSlug, realmSlug, false, 0L, evaluatedAt.toString()));
  }

  @Override
  @Transactional
  @Timed(value = "account.realm_access_grant_upsert")
  public RealmAccessGrantResult grantRealmAccess(RealmAccessGrantRequest request) {
    Account account = requireAccount(request.accountId());
    Instant now = Instant.now();
    AccountRealmAccessGrant grant =
        accountRealmAccessGrantRepository
            .findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                request.accountId(), request.tenantId(), request.worldSlug(), request.realmSlug())
            .orElseGet(
                () -> {
                  AccountRealmAccessGrant created = new AccountRealmAccessGrant();
                  created.setAccount(account);
                  created.setTenantId(request.tenantId());
                  created.setWorldSlug(request.worldSlug());
                  created.setRealmSlug(request.realmSlug());
                  created.setGrantVersion(0L);
                  created.setGranted(true);
                  created.setCreatedAt(now);
                  return created;
                });
    grant.setGrantVersion(grant.getGrantVersion() + 1L);
    grant.setGranted(true);
    grant.setGrantedBy(request.grantedBy());
    grant.setGrantReason(request.grantReason());
    grant.setUpdatedAt(now);
    accountRealmAccessGrantRepository.save(grant);
    return new RealmAccessGrantResult(
        request.accountId(),
        request.tenantId(),
        request.worldSlug(),
        request.realmSlug(),
        true,
        grant.getGrantVersion(),
        now.toString());
  }

  @Override
  @Transactional
  @Timed(value = "account.realm_access_grant_revoke")
  public void revokeRealmAccess(Long accountId, Long tenantId, String worldSlug, String realmSlug) {
    accountRealmAccessGrantRepository.revokeByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
        accountId, tenantId, worldSlug, realmSlug);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.runtime_entitlements")
  public RuntimeEntitlementsDto getTenantEntitlementsForRuntime(Long tenantId, String requestId) {
    return readTenantEntitlements(tenantId, false);
  }

  private BootstrapContext requireBootstrapContext(String bootstrapToken) {
    var caller =
        new net.firedevops.firemud.accountservice.service.session
                .AccountPlayerBootstrapCallerVerifier(jwtUtil, accountRepository, sessionService)
            .requireCaller(bootstrapToken);
    return new BootstrapContext(caller.accountId(), caller.accountUuid());
  }

  private Claims requireSignedTokenClaims(
      String token,
      String expectedAudience,
      String errorCode,
      String missingTokenMessage,
      String invalidTokenMessage) {
    if (token == null || token.isBlank()) {
      throw new AuthenticationException(errorCode, missingTokenMessage);
    }
    Claims claims;
    try {
      claims = jwtUtil.parseToken(token).getPayload();
    } catch (JwtException | IllegalArgumentException ex) {
      throw new AuthenticationException(errorCode, invalidTokenMessage, ex);
    }
    try {
      if (!expectedAudience.equals(JwtClaims.requireText(claims.get("aud"), "aud"))) {
        throw new AuthenticationException(errorCode, invalidTokenMessage);
      }
      return claims;
    } catch (IllegalArgumentException ex) {
      throw new AuthenticationException(errorCode, invalidTokenMessage, ex);
    }
  }

  private Optional<Account> findAccountForAuthentication(String usernameOrEmail) {
    Optional<Account> emailMatch =
        accountRepository.findByEmail(EmailCanonicalization.normalize(usernameOrEmail));
    if (emailMatch.isPresent()) {
      return emailMatch;
    }

    return accountRepository.findByUsername(usernameOrEmail);
  }

  /**
   * Explicit internal control-ui owner hook. It performs real primary authentication and consumes
   * the exact OTP in the caller's writable issuance transaction; it issues no token or session. The
   * owner must catch primary credential denial inside that transaction so failed-attempt accounting
   * commits, then report the denial outside it. Successful OTP consumption remains rollback-capable
   * when later issuance preparation fails.
   */
  @Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.MANDATORY,
      noRollbackFor = AuthenticationException.class)
  public ControlUiPrimaryIdentity authenticateControlUiPrimaryIdentity(
      String email, String secret) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account issuance transaction required");
    }
    Account account = lockSelectedAccountForPrimaryAuthentication(email);
    PrimaryAuthentication result = authenticateAccountIdentity(account, secret, true);
    result.emailLoginChallenge().ifPresent(accountEmailLoginChallengeRepository::delete);
    UUID accountUuid = UUID.fromString(accountMapper.toDto(result.account()).id());
    return new ControlUiPrimaryIdentity(accountUuid);
  }

  private Account lockSelectedAccountForPrimaryAuthentication(String selector) {
    String normalizedSelector = EmailCanonicalization.normalize(selector);
    Account selectedAccount =
        findAccountForAuthentication(selector).orElseThrow(this::invalidCredentials);
    if (selectedAccount.getId() == null || selectedAccount.getId() <= 0L) {
      throw invalidCredentials();
    }
    boolean selectedByEmail =
        normalizedSelector.equals(EmailCanonicalization.normalize(selectedAccount.getEmail()));
    boolean selectedByUsername =
        !selectedByEmail && Objects.equals(selector, selectedAccount.getUsername());
    if (!selectedByEmail && !selectedByUsername) {
      throw invalidCredentials();
    }
    Account account =
        accountRepository
            .findByIdForUpdate(selectedAccount.getId())
            .orElseThrow(this::invalidCredentials);
    requireAuthenticationPersistedIdentity(selectedAccount);
    requireAuthenticationPersistedIdentity(account);
    String selectedEmail = EmailCanonicalization.normalize(selectedAccount.getEmail());
    String lockedEmail = EmailCanonicalization.normalize(account.getEmail());
    if (!Objects.equals(selectedAccount.getId(), account.getId())
        || !Objects.equals(selectedAccount.getAccountUuid(), account.getAccountUuid())
        || selectedAccount.getAccountUuidProvenance() != account.getAccountUuidProvenance()
        || !Objects.equals(
            selectedAccount.getAccountUuidSourceNumericId(),
            account.getAccountUuidSourceNumericId())
        || !Objects.equals(selectedAccount.getUsername(), account.getUsername())
        || !selectedEmail.equals(lockedEmail)
        || (selectedByEmail && !normalizedSelector.equals(lockedEmail))
        || (selectedByUsername && !Objects.equals(selector, account.getUsername()))) {
      throw invalidCredentials();
    }
    return account;
  }

  /** No external constructor: only successful Account primary authentication creates this proof. */
  public static final class ControlUiPrimaryIdentity {
    private final UUID accountId;

    private ControlUiPrimaryIdentity(UUID accountId) {
      this.accountId = accountId;
    }

    public UUID accountId() {
      return accountId;
    }

    @Override
    public String toString() {
      return "ControlUiPrimaryIdentity[redacted]";
    }
  }

  /** Internal primary-credential hook for the unregistered canonical gameplay LOGIN owner. */
  @Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.MANDATORY,
      noRollbackFor = AuthenticationException.class)
  public CredentialVerification verifyGameplayPrimaryCredential(
      Account lockedAccount, String secret) {
    requireWritableGameplayCredentialTransaction();
    requireAuthenticationPersistedIdentity(lockedAccount);
    GameplayCredentialVerificationWork work = new GameplayCredentialVerificationWork(secret);
    PrimaryAuthentication authentication;
    try {
      authentication = authenticateAccountIdentity(lockedAccount, secret, true, work);
    } finally {
      work.complete();
    }
    authentication
        .emailLoginChallenge()
        .ifPresent(
            challenge -> {
              if (challenge.getId() == null
                  || challenge.getId() <= 0L
                  || !lockedAccount.getId().equals(challenge.getAccountId())) {
                throw new IllegalStateException("Exact login challenge ownership is required");
              }
            });
    return new CredentialVerification(
        authentication.account().getAccountUuid(),
        authentication.emailLoginChallenge().map(AccountEmailLoginChallenge::getId).orElse(null));
  }

  /** Non-authorizing equivalent KDF work with process-local, randomly generated decoy evidence. */
  public void verifyAbsentGameplayPrimaryCredential(String secret) {
    new GameplayCredentialVerificationWork(secret).complete();
  }

  private synchronized String gameplayDecoyPasswordVerifier() {
    if (gameplayDecoyPasswordVerifier == null) {
      char[] material = new char[64];
      try {
        for (int index = 0; index < material.length; index++) {
          material[index] = (char) ('!' + EMAIL_LOGIN_OTP_RANDOM.nextInt(94));
        }
        gameplayDecoyPasswordVerifier = hashPassword(material);
      } finally {
        java.util.Arrays.fill(material, '\0');
      }
    }
    return gameplayDecoyPasswordVerifier;
  }

  @SuppressFBWarnings(
      value = "AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE",
      justification =
          "Each instance is confined to one synchronous credential-verification call; this counter is not shared")
  private final class GameplayCredentialVerificationWork {
    private final String secret;
    private final String decoyVerifier;
    private int completedVerifications;

    private GameplayCredentialVerificationWork(String secret) {
      this.secret = secret == null ? "" : secret;
      this.decoyVerifier = gameplayDecoyPasswordVerifier();
    }

    private boolean verify(String verifier) {
      try {
        return verifyPassword(secret, verifier);
      } finally {
        completedVerifications++;
      }
    }

    private void complete() {
      while (completedVerifications < 2) {
        verify(decoyVerifier);
      }
    }
  }

  /** Consumes only the verified challenge after the LOGIN owner creates its PENDING operation. */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void consumeGameplayPrimaryCredential(
      Account lockedAccount, CredentialVerification verification) {
    requireWritableGameplayCredentialTransaction();
    requireAuthenticationPersistedIdentity(lockedAccount);
    if (verification == null || !lockedAccount.getAccountUuid().equals(verification.accountId())) {
      throw new IllegalStateException("Exact verified Account identity is required");
    }
    if (verification.oneTimeChallengeId() == null) {
      return;
    }
    accountEmailLoginChallengeRepository.lockAccountChallenge(lockedAccount.getId());
    AccountEmailLoginChallenge challenge =
        accountEmailLoginChallengeRepository
            .findByAccountId(lockedAccount.getId())
            .orElseThrow(
                () -> new IllegalStateException("Verified login challenge is unavailable"));
    if (!lockedAccount.getId().equals(challenge.getAccountId())
        || !verification.oneTimeChallengeId().equals(challenge.getId())) {
      throw new IllegalStateException("Exact verified login challenge ownership is required");
    }
    accountEmailLoginChallengeRepository.delete(challenge);
  }

  private void requireWritableGameplayCredentialTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account LOGIN transaction required");
    }
  }

  private PrimaryAuthentication authenticateAccountIdentity(
      String username, String password, boolean allowEmailLoginOtp) {
    Optional<Account> accountOpt = findAccountForAuthentication(username);
    Account account =
        accountOpt.orElseThrow(
            () ->
                new AuthenticationException(
                    AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials"));
    return authenticateAccountIdentity(account, password, allowEmailLoginOtp);
  }

  private PrimaryAuthentication authenticateAccountIdentity(
      Account account, String password, boolean allowEmailLoginOtp) {
    return authenticateAccountIdentity(account, password, allowEmailLoginOtp, null);
  }

  private PrimaryAuthentication authenticateAccountIdentity(
      Account account,
      String password,
      boolean allowEmailLoginOtp,
      GameplayCredentialVerificationWork work) {
    Optional<AccountEmailLoginChallenge> emailLoginChallenge = Optional.empty();
    if (allowEmailLoginOtp && allowsEmailLoginOtp(account)) {
      accountEmailLoginChallengeRepository.lockAccountChallenge(account.getId());
      emailLoginChallenge = activeEmailLoginChallenge(account);
    }
    if (emailLoginChallenge
        .filter(
            challenge ->
                work == null
                    ? matchesEmailLoginOtp(challenge, password)
                    : work.verify(challenge.getCodeHash()))
        .isPresent()) {
      requireAuthenticationEligible(account);
      return new PrimaryAuthentication(account, emailLoginChallenge);
    }
    if (!allowsPassword(account)
        || !(work == null
            ? verifyPassword(password, account.getPasswordHash())
            : work.verify(account.getPasswordHash()))) {
      emailLoginChallenge.ifPresent(
          challenge -> recordFailedEmailLoginAttempt(challenge, LocalDateTime.now()));
      throw new AuthenticationException(
          AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials");
    }
    requireAuthenticationEligible(account);
    return new PrimaryAuthentication(account, Optional.empty());
  }

  private void requireAuthenticationEligible(Account account) {
    AccountLifecycleState state = account.getLifecycleState();
    if (state == AccountLifecycleState.ACTIVE) {
      return;
    }
    if (state == AccountLifecycleState.SECURITY_LOCKED) {
      throw new AuthenticationException(
          AuthenticationErrorCodes.ACCOUNT_LOCKED, "Account is locked");
    }
    throw invalidCredentials();
  }

  private Optional<AccountEmailLoginChallenge> activeEmailLoginChallenge(Account account) {
    Optional<AccountEmailLoginChallenge> challenge =
        accountEmailLoginChallengeRepository.findByAccountId(account.getId());
    if (challenge.isEmpty()) {
      return Optional.empty();
    }
    AccountEmailLoginChallenge resolvedChallenge = challenge.orElseThrow();
    LocalDateTime now = LocalDateTime.now();
    if (resolvedChallenge.getExpiresAt().isBefore(now)
        || resolvedChallenge.getInvalidAttemptCount() >= EMAIL_LOGIN_OTP_MAX_ATTEMPTS) {
      recordFailedEmailLoginAttempt(resolvedChallenge, now);
      return Optional.empty();
    }
    return Optional.of(resolvedChallenge);
  }

  private boolean matchesEmailLoginOtp(AccountEmailLoginChallenge challenge, String secret) {
    return verifyPassword(secret == null ? "" : secret, challenge.getCodeHash());
  }

  private boolean allowsEmailLoginOtp(Account account) {
    return AccountLoginAuthModes.allows(
        account.getLoginAuthModes(), AccountLoginAuthMode.EMAIL_OTP);
  }

  private boolean allowsPassword(Account account) {
    return AccountLoginAuthModes.allows(account.getLoginAuthModes(), AccountLoginAuthMode.PASSWORD);
  }

  private void requireAuthenticationPersistedIdentity(Account account) {
    AccountIdentityProvenance provenance =
        account == null ? null : account.getAccountUuidProvenance();
    if (account == null
        || account.getId() == null
        || account.getId() <= 0L
        || account.getAccountUuid() == null
        || NIL_ACCOUNT_UUID.equals(account.getAccountUuid())
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())
        || !AccountIdentityProvenance.isAccepted(provenance)) {
      throw new IllegalStateException(
          "Exact persisted Account identity is required for password-reset authority");
    }
  }

  private record PrimaryAuthentication(
      Account account, Optional<AccountEmailLoginChallenge> emailLoginChallenge) {}

  private Account requireAccount(Long accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(() -> new IllegalArgumentException("Account not found"));
  }

  private UUID requireAuthenticationAccountUuid(Account account) {
    try {
      requireAuthenticationPersistedIdentity(account);
      UUID accountUuid = account.getAccountUuid();
      Long storageId = resolveAccountStorageId(accountUuid);
      if (!Objects.equals(account.getId(), storageId)) {
        throw new IllegalStateException("Account identity does not match its persisted owner");
      }
      return accountUuid;
    } catch (IllegalArgumentException | IllegalStateException | DataAccessException ex) {
      logger.warn(
          "Account owner validation failed during issuance; cause={}",
          ex.getClass().getSimpleName());
      throw new AuthenticationException("AUTH_UNAVAILABLE", "Account authority is unavailable", ex);
    }
  }

  /** Resolves a canonical public identity at the Account persistence boundary only. */
  @Override
  @Transactional(readOnly = true)
  public Long resolveAccountStorageId(UUID accountUuid) {
    if (accountUuid == null || NIL_ACCOUNT_UUID.equals(accountUuid)) {
      throw new IllegalArgumentException("A canonical non-nil Account UUID is required");
    }
    Account account =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    return requireAccountStorageId(account, accountUuid);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, Long> resolveAccountStorageIds(List<UUID> accountUuids) {
    if (accountUuids == null) {
      throw new IllegalArgumentException("Account UUIDs are required");
    }
    List<UUID> requestedAccountUuids = accountUuids.stream().distinct().toList();
    if (requestedAccountUuids.isEmpty()) {
      return Map.of();
    }
    if (requestedAccountUuids.stream()
        .anyMatch(uuid -> uuid == null || NIL_ACCOUNT_UUID.equals(uuid))) {
      throw new IllegalArgumentException("Canonical non-nil Account UUIDs are required");
    }
    Map<UUID, Long> storageIdsByAccountUuid = new LinkedHashMap<>();
    Map<Long, UUID> accountUuidsByStorageId = new HashMap<>();
    for (Account account : accountRepository.findByAccountUuids(requestedAccountUuids)) {
      UUID accountUuid = account == null ? null : account.getAccountUuid();
      if (accountUuid == null || !requestedAccountUuids.contains(accountUuid)) {
        throw new IllegalStateException("Account UUID batch read returned an unexpected row");
      }
      long storageId;
      try {
        storageId = requireAccountStorageId(account, accountUuid);
      } catch (IllegalArgumentException | IllegalStateException ex) {
        // The scalar caller omits this subject after the same failed owner readback.
        continue;
      }
      UUID previousAccountUuid = accountUuidsByStorageId.putIfAbsent(storageId, accountUuid);
      if (previousAccountUuid != null && !previousAccountUuid.equals(accountUuid)) {
        throw new IllegalStateException("Account UUID resolution returned an ambiguous row");
      }
      Long previousStorageId = storageIdsByAccountUuid.putIfAbsent(accountUuid, storageId);
      if (previousStorageId != null && previousStorageId != storageId) {
        throw new IllegalStateException("Account UUID resolution returned an ambiguous row");
      }
    }
    return Map.copyOf(storageIdsByAccountUuid);
  }

  private long requireAccountStorageId(Account account, UUID accountUuid) {
    if (account == null) {
      throw new IllegalStateException(
          "Account UUID readback did not match its exact persisted source row");
    }
    AccountIdentityProvenance provenance = account.getAccountUuidProvenance();
    if (account.getId() == null
        || account.getId() <= 0L
        || account.getAccountUuid() == null
        || NIL_ACCOUNT_UUID.equals(account.getAccountUuid())
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())
        || (provenance != AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            && provenance != AccountIdentityProvenance.ACCOUNT_V29_MIGRATION
            && provenance != AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT)
        || !accountUuid.equals(account.getAccountUuid())) {
      throw new IllegalStateException(
          "Account UUID readback did not match its exact persisted source row");
    }
    return account.getId();
  }

  private String mintToken(String subject, long expirationMs, Map<String, Object> claims) {
    return jwtUtil.generateToken(subject, expirationMs, claims);
  }

  private Map<String, Object> authenticationTokenClaims(String audience, Account account) {
    List<String> globalRoles =
        account.getRole() == null || account.getRole().isBlank()
            ? List.of()
            : List.of(account.getRole());
    return Map.of(
        "aud",
        audience,
        "accountId",
        account.getAccountUuid().toString(),
        "globalRoles",
        globalRoles,
        "jti",
        UUID.randomUUID().toString());
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.get_profile")
  public ProfileDto getProfile(Long tenantId, Long accountId) {
    requireProfileMembership(tenantId, accountId);
    Profile profile =
        profileRepository
            .findByAccountIdAndTenantId(accountId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Profile not found"));
    return projectProfile(profile, tenantId, accountId);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.list_presence_visibility_policies")
  public Map<Long, ProfilePresenceVisibilityPolicy> listPresenceVisibilityPolicies(
      Long tenantId, List<Long> accountIds) {
    if (accountIds == null || accountIds.isEmpty()) {
      return Map.of();
    }
    return profileRepository.findByTenantIdAndAccountIds(tenantId, accountIds).stream()
        .filter(
            profile ->
                profile.getAccount() != null
                    && profile.getAccount().getId() != null
                    && profile.getPresenceVisibilityPolicy() != null)
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                profile -> profile.getAccount().getId(),
                profile -> profile.getPresenceVisibilityPolicy(),
                (left, right) -> left));
  }

  @Override
  @Transactional
  @Timed(value = "account.update_profile")
  public ProfileDto updateProfile(UpdateProfileRequest request) {
    request.presenceVisibilityPolicy().requireSelectableByAccountHolder();
    UUID accountUuid = AccountUuidText.parseOrNull(request.accountId());
    if (accountUuid == null) {
      throw new IllegalArgumentException("accountId must be a canonical non-nil UUID");
    }
    Long accountId = resolveAccountStorageId(accountUuid);
    requireProfileMembership(request.tenantId(), accountId);
    Profile profile =
        profileRepository
            .findByAccountIdAndTenantId(accountId, request.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Profile not found"));
    requireProfileOwner(profile, request.tenantId(), accountId);
    profile.setDisplayName(request.displayName());
    profile.setBio(request.bio());
    profile.setPresenceVisibilityPolicy(request.presenceVisibilityPolicy());
    profile = profileRepository.save(profile);
    runAfterCommit(
        () ->
            notificationService.sendNotification(request.tenantId(), accountId, "Profile updated"));
    return projectProfile(profile, request.tenantId(), accountId);
  }

  private static void requireProfileOwner(Profile profile, Long tenantId, Long accountId) {
    if (profile == null
        || profile.getAccount() == null
        || !Objects.equals(accountId, profile.getAccount().getId())
        || !Objects.equals(tenantId, profile.getTenantId())) {
      throw new AuthenticationException("AUTH_UNAVAILABLE", "Profile owner is unavailable");
    }
  }

  private ProfileDto projectProfile(Profile profile, Long tenantId, Long accountId) {
    requireProfileOwner(profile, tenantId, accountId);
    return projectProfile(profile, tenantId, accountId, responseAccountUuid(accountId));
  }

  private ProfileDto projectProfile(
      Profile profile, Long tenantId, Long accountId, String accountUuid) {
    requireProfileOwner(profile, tenantId, accountId);
    ProfileDto dto = profileMapper.toProfileResponse(profile, accountUuid);
    if (dto == null
        || !accountUuid.equals(dto.accountId())
        || !Objects.equals(tenantId, dto.tenantId())) {
      throw new AuthenticationException("AUTH_UNAVAILABLE", "Profile projection is unavailable");
    }
    return dto;
  }

  private void requireProfileMembership(Long tenantId, Long accountId) {
    if (!accountTenantMembershipRepository.existsByAccountIdAndTenantId(accountId, tenantId)) {
      throw new IllegalArgumentException("Profile not found");
    }
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.get_login_auth_modes")
  public AccountLoginAuthModesDto getLoginAuthModes(Long accountId) {
    Account account = requireAccount(accountId);
    return new AccountLoginAuthModesDto(AccountLoginAuthModes.read(account.getLoginAuthModes()));
  }

  @Override
  @Transactional
  @Timed(value = "account.update_login_auth_modes")
  public AccountLoginAuthModesDto updateLoginAuthModes(
      Long accountId, UpdateAccountLoginAuthModesRequest request) {
    throw new ResponseStatusException(
        HttpStatus.NOT_IMPLEMENTED,
        "Recent ordinary reauthentication is required; login-factor changes are unavailable until Account implements its evidence mechanism");
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.export")
  public AccountDataExportDto exportAccountData(Long accountId) {
    Account account = requireAccount(accountId);
    String accountUuid = responseAccountUuid(account, accountId);
    List<ProfileDto> profiles =
        profileRepository.findByAccountId(accountId).stream()
            .map(profile -> projectProfile(profile, profile.getTenantId(), accountId, accountUuid))
            .toList();
    return new AccountDataExportDto(accountMapper.toDto(account), profiles);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.tenant_export")
  public TenantDataExportDto exportTenantData(Long tenantId, Long accountId) {
    Account account = requireAccount(accountId);
    Profile profile =
        profileRepository.findByAccountIdAndTenantId(accountId, tenantId).orElse(null);
    if (!accountTenantMembershipRepository.existsByAccountIdAndTenantId(accountId, tenantId)
        && profile == null) {
      throw new IllegalArgumentException("Tenant data not found");
    }
    String accountUuid = responseAccountUuid(account, accountId);
    return new TenantDataExportDto(
        tenantId,
        accountMapper.toDto(account),
        profile != null ? projectProfile(profile, tenantId, accountId, accountUuid) : null);
  }

  @Override
  @Transactional
  @Timed(value = "account.delete")
  public void deleteAccount(Long accountId) {
    requireAccount(accountId);
    if (accountJoinOperationRepository.hasRetainedOperation(accountId)) {
      throw new AccountLifecycleException(
          "ACCOUNT_DELETE_JOIN_RECEIPT_RETAINED",
          "Account JOIN receipts require retention and deletion reconciliation");
    }
    List<RetainedSubscriptionEvidence> subscriptions =
        retainedSubscriptionEvidenceRepository.findHistoryByAccountId(accountId);
    Optional<RetainedSubscriptionEvidence> blockingSubscription =
        subscriptions.stream().filter(this::isNonterminalSubscription).findFirst();
    if (blockingSubscription.isPresent()) {
      throw new AccountLifecycleException(
          "ACCOUNT_DELETE_ACTIVE_BILLING_OWNER",
          "Account has nonterminal tenant subscriptions; cancel or end subscriptions first");
    }
    throw new AccountLifecycleException(
        "ACCOUNT_DELETE_UNAVAILABLE",
        "Account deletion is unavailable until retained-data lifecycle and "
            + "deletion reconciliation are implemented");
  }

  private boolean isNonterminalSubscription(RetainedSubscriptionEvidence subscription) {
    String status =
        subscription.status() == null
            ? ""
            : subscription.status().trim().toLowerCase(java.util.Locale.ROOT);
    boolean terminalStatus =
        switch (status) {
          case "canceled", "cancelled", "ended", "expired" -> true;
          default -> false;
        };
    return !terminalStatus || subscription.endedAt() == null;
  }

  @Override
  @Transactional
  @Timed(value = "account.request_password_reset")
  public void requestPasswordReset(PasswordResetRequest request) {
    Optional<Account> accountOptional =
        accountRepository.findByEmail(EmailCanonicalization.normalize(request.email()));
    if (accountOptional.isEmpty()) {
      return;
    }
    Account account = accountOptional.get();
    net.firedevops.firemud.accountservice.entity.PasswordResetToken token =
        new net.firedevops.firemud.accountservice.entity.PasswordResetToken();
    token.setAccount(account);
    token.setToken(java.util.UUID.randomUUID().toString());
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
    passwordResetTokenRepository.save(token);
    String url = String.format(mailProperties.getResetUrl(), token.getToken());
    runAfterCommit(
        () ->
            sendRecoveryEmailSafely(
                () ->
                    emailService.sendEmail(
                        account.getEmail(),
                        "Password Reset",
                        String.format(readTemplate("password-reset.txt"), url))));
  }

  @Override
  @Timed(value = "account.complete_password_reset")
  public void completePasswordReset(CompletePasswordResetRequest request) {
    if (request == null
        || request.token() == null
        || request.token().isBlank()
        || request.newPassword() == null) {
      throw new IllegalArgumentException("Password-reset token and new password are required");
    }
    String tokenHash = sha256Hex(request.token());
    String candidatePasswordVerifier = hashPassword(request.newPassword());
    PasswordResetAttempt attempt =
        passwordResetOwnerTransaction.execute(
            status ->
                completePasswordResetInOwnerTransaction(
                    request, tokenHash, candidatePasswordVerifier));
    if (attempt == null) {
      throw new IllegalStateException("Password-reset owner transaction returned no result");
    }
    if (attempt.pending()) {
      throw new AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException();
    }
    if (attempt.aborted()) {
      throw new IllegalArgumentException(attempt.abortReason());
    }
    PasswordResetReceipt committed =
        Objects.requireNonNull(attempt.receipt(), "Committed password-reset receipt is required");
    PasswordResetReceipt postCommitReadback =
        passwordResetOwnerTransaction.execute(
            status ->
                passwordResetOperationRepository
                    .findByTokenHash(tokenHash)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "Password-reset receipt disappeared after commit")));
    if (!committed.equals(postCommitReadback)) {
      throw new IllegalStateException("Password-reset committed receipt readback differs");
    }
  }

  private PasswordResetAttempt completePasswordResetInOwnerTransaction(
      CompletePasswordResetRequest request, String tokenHash, String candidatePasswordVerifier) {
    Optional<PasswordResetReceipt> resolvedReceipt =
        passwordResetOperationRepository.findByTokenHash(tokenHash);
    Optional<Long> resolvedIntentAccountId =
        passwordResetDraftSourceChanges.findAccountIdByTokenHash(tokenHash);
    Optional<net.firedevops.firemud.accountservice.entity.PasswordResetToken> resolvedToken =
        resolvedReceipt.isPresent() || resolvedIntentAccountId.isPresent()
            ? Optional.empty()
            : passwordResetTokenRepository.findByToken(request.token());
    Long accountId =
        resolvedReceipt
            .map(PasswordResetReceipt::accountId)
            .orElseGet(
                () ->
                    resolvedIntentAccountId.orElseGet(
                        () ->
                            resolvedToken
                                .map(
                                    token ->
                                        token.getAccount() == null
                                            ? null
                                            : token.getAccount().getId())
                                .orElse(null)));
    if (accountId == null || accountId <= 0L) {
      throw new IllegalArgumentException("Invalid token");
    }

    Account account =
        accountRepository
            .findByIdForUpdate(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Invalid token"));
    requireAuthenticationPersistedIdentity(account);

    Optional<PasswordResetReceipt> lockedReceipt =
        passwordResetOperationRepository.findByTokenHash(tokenHash);
    if (lockedReceipt.isPresent()) {
      PasswordResetReceipt receipt = lockedReceipt.orElseThrow();
      requireReceiptAccountBinding(receipt, account);
      completePasswordResetRetry(account, receipt, request, tokenHash);
      return PasswordResetAttempt.committed(receipt);
    }
    if (resolvedReceipt.isPresent()) {
      throw new IllegalStateException(
          "Password-reset receipt changed while its Account was locked");
    }

    Optional<Intent> pending = passwordResetDraftSourceChanges.findByTokenHash(tokenHash);
    if (resolvedIntentAccountId.isPresent() && pending.isEmpty()) {
      throw new IllegalStateException(
          "Password-reset Draft source intent disappeared while its Account was locked");
    }
    Intent originalIntent = pending.orElse(null);
    if (originalIntent != null
        && (originalIntent.accountId() != account.getId()
            || !originalIntent.accountUuid().equals(account.getAccountUuid()))) {
      throw new IllegalStateException("Password-reset Draft intent Account binding changed");
    }
    if (originalIntent != null && "SOURCE_ABORTED".equals(originalIntent.status())) {
      return PasswordResetAttempt.aborted("Invalid token");
    }

    net.firedevops.firemud.accountservice.entity.PasswordResetToken token =
        passwordResetTokenRepository.findByToken(request.token()).orElse(null);
    String newPasswordVerifier;
    String passwordVerifierDigest;
    String requestDigest;
    LocalDateTime tokenExpiresAt;
    if (originalIntent != null) {
      if (!verifyPassword(request.newPassword(), originalIntent.passwordVerifier())) {
        throw new AccountPasswordResetOperationRepository.OperationConflictException(
            "Password-reset token is already bound to a different pending password request");
      }
      newPasswordVerifier = originalIntent.passwordVerifier();
      passwordVerifierDigest = originalIntent.passwordVerifierDigest();
      requestDigest = originalIntent.requestDigest();
      tokenExpiresAt = originalIntent.tokenExpiresAt();
    } else {
      if (token == null) {
        throw new IllegalArgumentException("Invalid token");
      }
      requireTokenAccountBinding(token, account);
      if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(LocalDateTime.now())) {
        throw new IllegalArgumentException("Token expired");
      }
      newPasswordVerifier = candidatePasswordVerifier;
      passwordVerifierDigest = sha256Hex(newPasswordVerifier);
      tokenExpiresAt = token.getExpiresAt();
      requestDigest =
          passwordResetRequestDigest(
              account.getAccountUuid(), tokenHash, tokenExpiresAt, passwordVerifierDigest);
    }

    AuthorityScope accountScope = AuthorityScope.account(account.getAccountUuid());
    ScopeState currentAuthority = readPasswordResetAccountAuthority(accountScope);
    AccountAuthoritySourceEventReadback.LatestSourceSnapshot latestSource =
        accountAuthoritySourceEventReadback.requireCurrentLatest(account, currentAuthority);

    if (originalIntent == null) {
      if (token == null) {
        throw new IllegalArgumentException("Invalid token");
      }
      requireTokenAccountBinding(token, account);
      if (token.getId() == null || !Objects.equals(token.getExpiresAt(), tokenExpiresAt)) {
        throw new IllegalArgumentException("Invalid token");
      }
      if (!tokenExpiresAt.isAfter(LocalDateTime.now())) {
        throw new IllegalArgumentException("Token expired");
      }
    } else if (token != null
        && (!Objects.equals(token.getId(), originalIntent.tokenId())
            || !Objects.equals(token.getExpiresAt(), tokenExpiresAt)
            || token.getAccount() == null
            || !Objects.equals(token.getAccount().getId(), account.getId()))) {
      token = null;
    }

    Intent intent =
        passwordResetDraftSourceChanges.participate(
            account,
            originalIntent == null
                ? Objects.requireNonNull(token).getId()
                : originalIntent.tokenId(),
            tokenHash,
            tokenExpiresAt,
            requestDigest,
            newPasswordVerifier,
            currentAuthority,
            latestSource);

    boolean settled = passwordResetDraftSourceChanges.sourceMutationPermitted(intent);
    if (!settled) {
      passwordResetDraftSourceChanges.verifyWaiting(intent);
      return PasswordResetAttempt.waiting();
    }

    if (!passwordResetDraftSourceChanges.matchesCapture(
        intent, account, currentAuthority, latestSource)) {
      passwordResetDraftSourceChanges.markSourceAborted(
          intent,
          net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository
              .SourceChangeAbortReason.DEFINITIVE_ABORT);
      deleteAbortedPasswordResetToken(intent);
      return PasswordResetAttempt.aborted("Invalid token");
    }
    if (token == null
        || !Objects.equals(token.getId(), intent.tokenId())
        || !Objects.equals(token.getExpiresAt(), tokenExpiresAt)
        || !Objects.equals(token.getAccount().getId(), account.getId())) {
      passwordResetDraftSourceChanges.markSourceAborted(
          intent,
          net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository
              .SourceChangeAbortReason.DEFINITIVE_ABORT);
      deleteAbortedPasswordResetToken(intent);
      return PasswordResetAttempt.aborted("Invalid token");
    }
    LocalDateTime claimTime = LocalDateTime.now();
    if (!tokenExpiresAt.isAfter(claimTime)) {
      passwordResetDraftSourceChanges.markSourceAborted(
          intent,
          net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository
              .SourceChangeAbortReason.EXPIRED);
      deleteAbortedPasswordResetToken(intent);
      return PasswordResetAttempt.aborted("Token expired");
    }
    if (!passwordResetTokenRepository.consumeIfUnexpired(token, claimTime)) {
      passwordResetDraftSourceChanges.markSourceAborted(
          intent,
          net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository
              .SourceChangeAbortReason.DEFINITIVE_ABORT);
      deleteAbortedPasswordResetToken(intent);
      return PasswordResetAttempt.aborted("Invalid token");
    }

    AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence currentSource =
        accountAuthoritySourceEvidenceRepository.readCurrentAccountSource(
            account.getAccountUuid(), currentAuthority);
    ScopeState advancedAuthority =
        accountAuthoritySourceEvidenceRepository.prepareClosedAccountAdvance(
            account.getAccountUuid(), currentSource);
    requireAdvancedPasswordResetAuthority(accountScope, currentAuthority, advancedAuthority);

    Account passwordUpdated =
        accountRepository.updatePasswordHashForLockedAccount(account, newPasswordVerifier);
    requireAccountReadback(account, passwordUpdated, newPasswordVerifier);

    Event appended =
        accountAuthorityOutboxRepository.append(
            passwordResetStreamKey(account.getAccountUuid()),
            intent.requestId(),
            sequence ->
                passwordResetEventEvidence(
                    account.getAccountUuid(),
                    intent.requestId(),
                    intent.eventId(),
                    sequence,
                    advancedAuthority.generation(),
                    advancedAuthority.sourceVersion()));
    PasswordResetAuthorityEvent committedEvent =
        requirePasswordResetEvent(
            appended,
            account.getAccountUuid(),
            intent.requestId(),
            intent.eventId(),
            advancedAuthority.generation(),
            advancedAuthority.sourceVersion());
    Checkpoint committedCheckpoint =
        accountAuthorityOutboxRepository
            .readCheckpoint(passwordResetStreamKey(account.getAccountUuid()))
            .orElseThrow(
                () -> new IllegalStateException("Password-reset outbox checkpoint is missing"));
    requireCheckpointMatches(committedCheckpoint, appended);

    PasswordResetReceipt receipt =
        PasswordResetReceipt.committed(
            account.getId(),
            account.getAccountUuid(),
            tokenHash,
            intent.requestId(),
            requestDigest,
            tokenExpiresAt,
            passwordVerifierDigest,
            appended.outboxStreamKey(),
            appended.outboxSequence(),
            committedEvent.eventId(),
            committedEvent.eventDigest(),
            advancedAuthority,
            advancedAuthority.issuanceFence());
    passwordResetOperationRepository.insert(receipt);
    accountAuthoritySourceEvidenceRepository.advanceClosedAccountHead(
        account.getAccountUuid(), currentAuthority, advancedAuthority, appended);
    passwordResetDraftSourceChanges.markSourceCommitted(intent, appended);

    Account finalAccount =
        accountRepository
            .findByIdForUpdate(account.getId())
            .orElseThrow(
                () -> new IllegalStateException("Password-reset Account readback is missing"));
    requireAccountReadback(account, finalAccount, newPasswordVerifier);
    ScopeState finalAuthority = readPasswordResetAccountAuthority(accountScope);
    if (!advancedAuthority.equals(finalAuthority)) {
      throw new IllegalStateException("Password-reset authority readback did not match its commit");
    }
    AccountAuthoritySourceEventReadback.LatestSourceSnapshot finalSource =
        accountAuthoritySourceEventReadback.requireCurrentLatest(finalAccount, finalAuthority);
    if (finalSource.outboxSequence() != appended.outboxSequence()
        || !finalSource.latestEvent().orElseThrow().equals(appended)) {
      throw new IllegalStateException(
          "Password-reset closed source readback differs from its commit");
    }
    Event eventReadback =
        accountAuthorityOutboxRepository
            .findEvent(appended.outboxStreamKey(), appended.outboxSequence())
            .orElseThrow(
                () -> new IllegalStateException("Password-reset event readback is missing"));
    if (!appended.equals(eventReadback)) {
      throw new IllegalStateException("Password-reset event readback did not match its commit");
    }
    requirePasswordResetEvent(
        eventReadback,
        account.getAccountUuid(),
        intent.requestId(),
        intent.eventId(),
        advancedAuthority.generation(),
        advancedAuthority.sourceVersion());
    Checkpoint finalCheckpoint =
        accountAuthorityOutboxRepository
            .readCheckpoint(appended.outboxStreamKey())
            .orElseThrow(
                () -> new IllegalStateException("Password-reset checkpoint readback is missing"));
    requireCheckpointMatches(finalCheckpoint, eventReadback);
    PasswordResetReceipt receiptReadback =
        passwordResetOperationRepository
            .findByTokenHash(tokenHash)
            .orElseThrow(
                () -> new IllegalStateException("Password-reset receipt readback is missing"));
    if (!receipt.equals(receiptReadback)) {
      throw new IllegalStateException("Password-reset receipt readback did not match its commit");
    }
    if (passwordResetTokenRepository.findByToken(request.token()).isPresent()) {
      throw new IllegalStateException("Consumed password-reset token remained readable");
    }
    return PasswordResetAttempt.committed(receipt);
  }

  private void completePasswordResetRetry(
      Account account,
      PasswordResetReceipt receipt,
      CompletePasswordResetRequest request,
      String tokenHash) {
    if (!receipt.tokenExpiresAt().isAfter(LocalDateTime.now())) {
      throw new IllegalArgumentException("Token expired");
    }
    requireReceiptAccountBinding(receipt, account);
    String currentVerifier = account.getPasswordHash();
    if (currentVerifier == null || currentVerifier.isBlank()) {
      throw new IllegalStateException("Password-reset result was superseded");
    }
    String currentVerifierDigest = sha256Hex(currentVerifier);
    if (!constantTimeTextEquals(currentVerifierDigest, receipt.passwordVerifierDigest())) {
      throw new IllegalStateException("Password-reset result was superseded");
    }
    if (!verifyPassword(request.newPassword(), currentVerifier)) {
      throw new AccountPasswordResetOperationRepository.OperationConflictException(
          "Password-reset token was already committed with a different password");
    }
    requireReceiptRequestDigest(receipt);
    if (!constantTimeTextEquals(receipt.tokenHash(), tokenHash)) {
      throw new IllegalStateException("Password-reset receipt token identity changed");
    }

    AuthorityScope accountScope = AuthorityScope.account(account.getAccountUuid());
    ScopeState currentAuthority = readPasswordResetAccountAuthority(accountScope);
    accountAuthoritySourceEventReadback.requireCurrentLatest(account, currentAuthority);
    requireReceiptAuthorityIsCurrentOrEarlier(receipt, currentAuthority);
    Event event =
        accountAuthorityOutboxRepository
            .findEvent(receipt.outboxStreamKey(), receipt.outboxSequence())
            .orElseThrow(
                () -> new IllegalStateException("Password-reset event readback is missing"));
    requireReceiptEventBinding(receipt, event);
    PasswordResetReceipt receiptReadback =
        passwordResetOperationRepository
            .findByTokenHash(tokenHash)
            .orElseThrow(
                () -> new IllegalStateException("Password-reset receipt readback is missing"));
    if (!receipt.equals(receiptReadback)) {
      throw new IllegalStateException("Password-reset receipt readback did not match its commit");
    }
    if (!receipt.tokenExpiresAt().isAfter(LocalDateTime.now())) {
      throw new IllegalArgumentException("Token expired");
    }
  }

  private ScopeState readPasswordResetAccountAuthority(AuthorityScope accountScope) {
    ScopeState state;
    try {
      state = accountAuthorityGenerationRepository.read(accountScope);
    } catch (IllegalStateException missingAuthority) {
      String message = missingAuthority.getMessage();
      if ("Account authority generation is missing".equals(message)
          || "Account authority issuance fence is missing".equals(message)) {
        throw new AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException();
      }
      throw missingAuthority;
    }
    if (!accountScope.equals(state.scope())) {
      throw new IllegalStateException("Account password-reset authority scope changed");
    }
    requirePositiveAccountFence(state.issuanceFence(), accountScope.accountId());
    return state;
  }

  private void requirePositiveAccountFence(
      AccountAuthorityGenerationRepository.IssuanceFence fence, UUID accountUuid) {
    if (fence == null
        || !accountUuid.equals(fence.accountId())
        || fence.value() <= 0L
        || fence.sourceVersion() <= 0L) {
      throw new IllegalStateException(
          "Account password-reset issuance fence is missing or invalid");
    }
  }

  private void requireAdvancedPasswordResetAuthority(
      AuthorityScope expectedScope, ScopeState previous, ScopeState advanced) {
    if (advanced == null
        || !expectedScope.equals(advanced.scope())
        || advanced.generation() != Math.addExact(previous.generation(), 1L)
        || advanced.sourceVersion() != Math.addExact(previous.sourceVersion(), 1L)) {
      throw new IllegalStateException(
          "Account password-reset authority did not advance exactly once");
    }
    requirePositiveAccountFence(advanced.issuanceFence(), expectedScope.accountId());
    if (advanced.issuanceFence().value() != Math.addExact(previous.issuanceFence().value(), 1L)
        || advanced.issuanceFence().sourceVersion()
            != Math.addExact(previous.issuanceFence().sourceVersion(), 1L)) {
      throw new IllegalStateException(
          "Account password-reset issuance fence did not advance exactly once");
    }
  }

  private void requireReceiptAccountBinding(PasswordResetReceipt receipt, Account account) {
    if (receipt.accountId() != account.getId()
        || !account.getAccountUuid().equals(receipt.accountUuid())
        || !passwordResetStreamKey(account.getAccountUuid()).equals(receipt.outboxStreamKey())) {
      throw new IllegalStateException(
          "Password-reset receipt does not match its Account source row");
    }
    requireReceiptRequestDigest(receipt);
  }

  private void requireReceiptRequestDigest(PasswordResetReceipt receipt) {
    String expected =
        passwordResetRequestDigest(
            receipt.accountUuid(),
            receipt.tokenHash(),
            receipt.tokenExpiresAt(),
            receipt.passwordVerifierDigest());
    if (receipt.requestDigestVersion() != 1
        || !PASSWORD_RESET_OPERATION_KIND.equals(receipt.operationKind())
        || !passwordResetRequestId(receipt.tokenHash()).equals(receipt.requestId())
        || !constantTimeTextEquals(expected, receipt.requestDigest())) {
      throw new IllegalStateException("Password-reset receipt request evidence is inconsistent");
    }
  }

  private void requireReceiptEventBinding(PasswordResetReceipt receipt, Event event) {
    PasswordResetAuthorityEvent verified = verifyPasswordResetEvent(event, receipt.accountUuid());
    if (!receipt.outboxStreamKey().equals(event.outboxStreamKey())
        || receipt.outboxSequence() != event.outboxSequence()
        || !receipt.requestId().equals(event.requestId())
        || !receipt.eventId().equals(event.eventId())
        || !receipt.eventDigest().equals(event.eventDigest())
        || !passwordResetEventId(receipt.tokenHash()).equals(event.eventId())
        || !Long.toString(receipt.accountAuthorityGeneration())
            .equals(verified.accountAuthorityGeneration())
        || !Long.toString(receipt.accountSourceVersion()).equals(verified.sourceVersion())) {
      throw new IllegalStateException("Password-reset receipt does not match its source event");
    }
  }

  private void requireReceiptAuthorityIsCurrentOrEarlier(
      PasswordResetReceipt receipt, ScopeState state) {
    AccountAuthorityGenerationRepository.IssuanceFence currentFence = state.issuanceFence();
    requirePositiveAccountFence(currentFence, receipt.accountUuid());
    if (receipt.accountAuthorityGeneration() > state.generation()
        || receipt.accountSourceVersion() > state.sourceVersion()
        || receipt.issuanceFence() > currentFence.value()
        || receipt.issuanceFenceSourceVersion() > currentFence.sourceVersion()) {
      throw new IllegalStateException(
          "Password-reset receipt is ahead of current Account authority");
    }
  }

  private void requireAccountReadback(Account expected, Account readback, String passwordVerifier) {
    requireAuthenticationPersistedIdentity(readback);
    if (!Objects.equals(expected.getId(), readback.getId())
        || !Objects.equals(expected.getAccountUuid(), readback.getAccountUuid())
        || expected.getAccountUuidProvenance() != readback.getAccountUuidProvenance()
        || !Objects.equals(
            expected.getAccountUuidSourceNumericId(), readback.getAccountUuidSourceNumericId())
        || !Objects.equals(passwordVerifier, readback.getPasswordHash())) {
      throw new IllegalStateException("Password-reset Account readback did not match its commit");
    }
  }

  private void requireTokenAccountBinding(
      net.firedevops.firemud.accountservice.entity.PasswordResetToken token, Account account) {
    if (token.getId() == null
        || token.getToken() == null
        || token.getExpiresAt() == null
        || token.getAccount() == null
        || !Objects.equals(token.getAccount().getId(), account.getId())) {
      throw new IllegalStateException("Password-reset token source does not match its Account row");
    }
  }

  private EventEvidence passwordResetEventEvidence(
      UUID accountUuid,
      String requestId,
      String eventId,
      long sequence,
      long accountAuthorityGeneration,
      long sourceVersion) {
    PasswordResetAuthorityEvent event =
        PasswordResetAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", PasswordResetAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", eventId),
                Map.entry("requestId", requestId),
                Map.entry("accountId", accountUuid.toString()),
                Map.entry("sourceScope", "account/" + accountUuid),
                Map.entry("outboxStreamKey", passwordResetStreamKey(accountUuid)),
                Map.entry("outboxSequence", Long.toString(sequence)),
                Map.entry("accountAuthorityGeneration", Long.toString(accountAuthorityGeneration)),
                Map.entry("sourceVersion", Long.toString(sourceVersion)),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", Long.toString(accountAuthorityGeneration),
                        "outboxStreamKey", passwordResetStreamKey(accountUuid),
                        "outboxSequence", Long.toString(sequence)))));
    return new EventEvidence(event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
  }

  private PasswordResetAuthorityEvent requirePasswordResetEvent(
      Event event,
      UUID accountUuid,
      String requestId,
      String eventId,
      long accountAuthorityGeneration,
      long sourceVersion) {
    PasswordResetAuthorityEvent verified = verifyPasswordResetEvent(event, accountUuid);
    if (!passwordResetStreamKey(accountUuid).equals(event.outboxStreamKey())
        || !requestId.equals(event.requestId())
        || !eventId.equals(event.eventId())
        || !Long.toString(accountAuthorityGeneration).equals(verified.accountAuthorityGeneration())
        || !Long.toString(sourceVersion).equals(verified.sourceVersion())) {
      throw new IllegalStateException(
          "Password-reset source event does not match its committed state");
    }
    return verified;
  }

  private PasswordResetAuthorityEvent verifyPasswordResetEvent(Event event, UUID accountUuid) {
    if (event == null) {
      throw new IllegalStateException("Password-reset source event is missing");
    }
    PasswordResetAuthorityEvent verified =
        PasswordResetAuthorityEventV1Codec.verify(
            new String(event.payload(), StandardCharsets.UTF_8));
    if (!MessageDigest.isEqual(event.payload(), verified.canonicalJsonUtf8())
        || !event.outboxStreamKey().equals(verified.outboxStreamKey())
        || !event.requestId().equals(verified.requestId())
        || !event.eventId().equals(verified.eventId())
        || !event.eventDigest().equals(verified.eventDigest())
        || !accountUuid.toString().equals(verified.accountId())
        || !Long.toString(event.outboxSequence()).equals(verified.outboxSequence())) {
      throw new IllegalStateException("Password-reset source event readback is inconsistent");
    }
    return verified;
  }

  private void requireCheckpointMatches(Checkpoint checkpoint, Event event) {
    if (!checkpoint.outboxStreamKey().equals(event.outboxStreamKey())
        || checkpoint.outboxSequence() != event.outboxSequence()
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())) {
      throw new IllegalStateException("Password-reset outbox checkpoint does not match its event");
    }
  }

  private String passwordResetRequestDigest(
      UUID accountUuid, String tokenHash, LocalDateTime tokenExpiresAt, String verifierDigest) {
    if (accountUuid == null || tokenExpiresAt == null) {
      throw new IllegalArgumentException("Password-reset request identity is incomplete");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      updateLengthPrefixed(digest, PASSWORD_RESET_REQUEST_DOMAIN);
      updateLengthPrefixed(digest, PASSWORD_RESET_OPERATION_KIND);
      updateLengthPrefixed(digest, accountUuid.toString());
      updateLengthPrefixed(digest, tokenHash);
      updateLengthPrefixed(digest, tokenExpiresAt.toString());
      updateLengthPrefixed(digest, verifierDigest);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void deleteAbortedPasswordResetToken(Intent intent) {
    passwordResetTokenRepository.deleteExactAfterSourceAbort(
        intent.tokenId(), intent.accountId(), intent.tokenHash(), intent.tokenExpiresAt());
  }

  private void updateLengthPrefixed(MessageDigest digest, String value) {
    if (value == null) {
      throw new IllegalArgumentException("Password-reset request field is required");
    }
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
    digest.update(encoded);
  }

  private boolean constantTimeTextEquals(String left, String right) {
    return left != null
        && right != null
        && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  private String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String passwordResetRequestId(String tokenHash) {
    return PASSWORD_RESET_REQUEST_ID_PREFIX + tokenHash;
  }

  private String passwordResetEventId(String tokenHash) {
    return PASSWORD_RESET_EVENT_ID_PREFIX + tokenHash;
  }

  private String passwordResetStreamKey(UUID accountUuid) {
    return "account:auth-authority:v1:account/" + accountUuid;
  }

  private record PasswordResetAttempt(
      boolean pending, boolean aborted, String abortReason, PasswordResetReceipt receipt) {
    static PasswordResetAttempt waiting() {
      return new PasswordResetAttempt(true, false, null, null);
    }

    static PasswordResetAttempt aborted(String reason) {
      return new PasswordResetAttempt(false, true, reason, null);
    }

    static PasswordResetAttempt committed(PasswordResetReceipt receipt) {
      return new PasswordResetAttempt(false, false, null, receipt);
    }
  }

  @Override
  @Transactional
  @Timed(value = "account.request_email_verification")
  public void requestEmailVerification(Long accountId) {
    Account account = requireAccount(accountId);
    issueEmailVerification(account);
  }

  @Override
  @Transactional
  @Timed(value = "account.request_email_verification_public")
  public void requestEmailVerification(String email) {
    Optional<Account> accountOptional =
        accountRepository.findByEmail(EmailCanonicalization.normalize(email));
    if (accountOptional.isEmpty()) {
      return;
    }
    issueEmailVerification(accountOptional.get(), true);
  }

  private void issueEmailVerification(Account account) {
    issueEmailVerification(account, false);
  }

  private void issueEmailVerification(Account account, boolean neutralPublicResponse) {
    EmailVerificationToken token = new EmailVerificationToken();
    token.setAccount(account);
    token.setToken(java.util.UUID.randomUUID().toString());
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(24));
    emailVerificationTokenRepository.save(token);
    String url = String.format(mailProperties.getVerificationUrl(), token.getToken());
    Runnable sendVerification =
        () ->
            emailService.sendEmail(
                account.getEmail(),
                "Email Verification",
                String.format(readTemplate("email-verification.txt"), url));
    runAfterCommit(
        neutralPublicResponse ? () -> sendRecoveryEmailSafely(sendVerification) : sendVerification);
  }

  @Override
  @Transactional
  @Timed(value = "account.verify_email")
  public void verifyEmail(VerifyEmailRequest request) {
    EmailVerificationToken token =
        emailVerificationTokenRepository
            .findByToken(request.token())
            .orElseThrow(() -> new IllegalArgumentException("Invalid token"));
    if (token.getId() == null
        || token.getId() <= 0L
        || token.getAccount() == null
        || token.getAccount().getId() == null
        || token.getAccount().getId() <= 0L
        || token.getExpiresAt() == null
        || !request.token().equals(token.getToken())) {
      throw new IllegalArgumentException("Invalid token");
    }
    // The token join supplies a locator, not the current full Account to mutate.
    Long accountId = token.getAccount().getId();
    Account account =
        accountRepository
            .findByIdForUpdate(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Invalid token"));
    if (!accountId.equals(account.getId())) {
      throw new IllegalArgumentException("Invalid token");
    }
    LocalDateTime capturedNow = LocalDateTime.now();
    if (!token.getExpiresAt().isAfter(capturedNow)) {
      throw new IllegalArgumentException("Token expired");
    }
    if (!emailVerificationTokenRepository.consumeIfUnexpired(token, capturedNow)) {
      throw new IllegalArgumentException("Invalid token");
    }
    account.setEmailVerified(true);
    accountRepository.save(account);
  }

  @Override
  @Transactional
  @Timed(value = "account.link_external")
  public void linkExternalAccount(
      net.firedevops.firemud.accountservice.dto.LinkExternalAccountRequest request) {
    Account account = requireAccount(request.accountId());

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
    Optional<Account> accountOptional =
        accountRepository.findByEmail(EmailCanonicalization.normalize(request.email()));
    if (accountOptional.isEmpty()) {
      return;
    }
    Account account = accountOptional.get();
    runAfterCommit(
        () ->
            sendRecoveryEmailSafely(
                () ->
                    emailService.sendEmail(
                        account.getEmail(),
                        "Username Reminder",
                        String.format(
                            readTemplate("username-reminder.txt"), account.getUsername()))));
  }

  private void sendRecoveryEmailSafely(Runnable delivery) {
    try {
      delivery.run();
    } catch (RuntimeException ex) {
      // Keep the public recovery response neutral and do not log the recipient, token, or SMTP
      // exception message; the exception class is enough to identify the failing dependency.
      logger.warn("Recovery email delivery failed; cause={}", ex.getClass().getSimpleName());
    }
  }

  private String hashPassword(String password) {
    return hashPassword(password.toCharArray());
  }

  private String hashPassword(char[] chars) {
    Argon2 argon2 = null;
    try {
      argon2 = Argon2Factory.create();
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      try {
        if (argon2 != null) {
          argon2.wipeArray(chars);
        }
      } finally {
        java.util.Arrays.fill(chars, '\0');
      }
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

  private AuthenticationException invalidCredentials() {
    return new AuthenticationException(
        AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials");
  }

  private void recordFailedEmailLoginAttempt(
      AccountEmailLoginChallenge challenge, LocalDateTime now) {
    challenge.setInvalidAttemptCount(challenge.getInvalidAttemptCount() + 1);
    challenge.setUpdatedAt(now);
    if (challenge.getInvalidAttemptCount() >= EMAIL_LOGIN_OTP_MAX_ATTEMPTS
        || challenge.getExpiresAt().isBefore(now)) {
      accountEmailLoginChallengeRepository.delete(challenge);
      return;
    }
    accountEmailLoginChallengeRepository.save(challenge);
  }

  private void safeSendEmailLoginOtp(String email, String code) {
    try {
      emailService.sendEmail(
          email,
          "Your FireMUD login code",
          "Your FireMUD login code is " + code + ". It expires in 10 minutes.");
    } catch (RuntimeException ex) {
      logger.warn("Email login code delivery failed");
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

  private void runAfterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }

  private record BootstrapContext(long accountId, UUID accountUuid) {}
}
