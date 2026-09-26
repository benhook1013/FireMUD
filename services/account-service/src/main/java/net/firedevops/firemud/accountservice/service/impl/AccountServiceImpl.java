package net.firedevops.firemud.accountservice.service.impl;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.dto.AccountDataExportDto;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.AccountLoginAuthModesDto;
import net.firedevops.firemud.accountservice.dto.BootstrapCharacterDto;
import net.firedevops.firemud.accountservice.dto.BootstrapRealmDto;
import net.firedevops.firemud.accountservice.dto.BootstrapWorldDto;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
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
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthMode;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.entity.AccountRealmAccessGrant;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.entity.EmailVerificationToken;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.mapper.ProfileMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.JoinOperation;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.accountservice.repository.ExternalAccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import net.firedevops.firemud.accountservice.repository.SubscriptionRepository;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.EmailService;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.accountservice.service.exception.AccountAlreadyExistsException;
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.slf4j.Logger;
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
import tools.jackson.databind.json.JsonMapper;

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
  private static final int EMAIL_LOGIN_OTP_MAX_ATTEMPTS = 5;
  private static final SecureRandom EMAIL_LOGIN_OTP_RANDOM = new SecureRandom();
  private static final JsonMapper AUDIT_JSON = JsonMapper.builder().build();

  private final AccountRepository accountRepository;
  private final AccountAuditOutboxRepository accountAuditOutboxRepository;
  private final AccountConnectScopeRepository accountConnectScopeRepository;
  private final AccountJoinOperationRepository accountJoinOperationRepository;
  private final AccountMembershipTransitionReceiptRepository membershipTransitionReceiptRepository;
  private final AccountEmailLoginChallengeRepository accountEmailLoginChallengeRepository;
  private final AccountRealmAccessGrantRepository accountRealmAccessGrantRepository;
  private final AccountTenantMembershipRepository accountTenantMembershipRepository;
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
  private final AccountTokenProperties tokenProperties;
  private final JwtAuthProperties jwtAuthProperties;
  private final GameSessionClient gameSessionClient;
  private final EntityManagementClient entityManagementClient;
  private final JwtUtil jwtUtil;
  private final net.firedevops.firemud.accountservice.service.session.SessionService sessionService;
  private final TransactionTemplate joinTransactionTemplate;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Dependencies are injected and kept internal")
  public AccountServiceImpl(
      AccountRepository accountRepository,
      AccountAuditOutboxRepository accountAuditOutboxRepository,
      AccountConnectScopeRepository accountConnectScopeRepository,
      AccountJoinOperationRepository accountJoinOperationRepository,
      AccountMembershipTransitionReceiptRepository membershipTransitionReceiptRepository,
      AccountEmailLoginChallengeRepository accountEmailLoginChallengeRepository,
      AccountRealmAccessGrantRepository accountRealmAccessGrantRepository,
      AccountTenantMembershipRepository accountTenantMembershipRepository,
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
      AccountTokenProperties tokenProperties,
      JwtAuthProperties jwtAuthProperties,
      GameSessionClient gameSessionClient,
      EntityManagementClient entityManagementClient,
      JwtUtil jwtUtil,
      net.firedevops.firemud.accountservice.service.session.SessionService sessionService,
      PlatformTransactionManager transactionManager) {
    this.accountRepository = accountRepository;
    this.accountAuditOutboxRepository = accountAuditOutboxRepository;
    this.accountConnectScopeRepository = accountConnectScopeRepository;
    this.accountJoinOperationRepository = accountJoinOperationRepository;
    this.membershipTransitionReceiptRepository = membershipTransitionReceiptRepository;
    this.accountEmailLoginChallengeRepository = accountEmailLoginChallengeRepository;
    this.accountRealmAccessGrantRepository = accountRealmAccessGrantRepository;
    this.accountTenantMembershipRepository = accountTenantMembershipRepository;
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
    this.tokenProperties = tokenProperties;
    this.jwtAuthProperties = jwtAuthProperties;
    this.gameSessionClient = gameSessionClient;
    this.entityManagementClient = entityManagementClient;
    this.jwtUtil = jwtUtil;
    this.sessionService = sessionService;
    this.joinTransactionTemplate = new TransactionTemplate(transactionManager);
    this.joinTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
    accountAuditOutboxRepository.append(
        UUID.randomUUID(),
        "platform",
        null,
        "ACCOUNT_REGISTERED",
        "{\"accountId\":" + saved.getId() + "}");
    return accountMapper.toDto(saved);
  }

  @Override
  @Transactional
  @Timed(value = "account.authenticate")
  public net.firedevops.firemud.accountservice.dto.AuthenticationResult authenticate(
      String username, String password) {
    PrimaryAuthentication authentication = authenticateAccountIdentity(username, password, true);
    Account account = authentication.account();
    authentication.emailLoginChallenge().ifPresent(accountEmailLoginChallengeRepository::delete);
    String token =
        mintToken(
            account.getId().toString(),
            jwtAuthProperties.getJwtExpirationMs(),
            authenticationTokenClaims("control-ui", account));
    sessionService.storeAccountSession(
        account.getId(), token, jwtAuthProperties.getJwtExpirationMs());
    return new net.firedevops.firemud.accountservice.dto.AuthenticationResult(
        account.getId(), token);
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
    authentication.emailLoginChallenge().ifPresent(accountEmailLoginChallengeRepository::delete);
    String token =
        mintToken(
            account.getId().toString(),
            jwtAuthProperties.getJwtExpirationMs(),
            authenticationTokenClaims(GAMEPLAY_DELEGATION_AUDIENCE, account));
    sessionService.storeAccountSession(
        account.getId(), token, jwtAuthProperties.getJwtExpirationMs());
    return new net.firedevops.firemud.accountservice.dto.AuthenticationResult(
        account.getId(), token);
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
    accountEmailLoginChallengeRepository.delete(challenge);
    String token =
        mintToken(
            account.getId().toString(),
            jwtAuthProperties.getJwtExpirationMs(),
            authenticationTokenClaims(GAMEPLAY_DELEGATION_AUDIENCE, account));
    sessionService.storeAccountSession(
        account.getId(), token, jwtAuthProperties.getJwtExpirationMs());
    return new net.firedevops.firemud.accountservice.dto.AuthenticationResult(
        account.getId(), token);
  }

  @Override
  @Transactional
  @Timed(value = "account.player_bootstrap")
  public PlayerBootstrapResult issuePlayerBootstrap(String accountIdentifier, String secret) {
    PrimaryAuthentication authentication =
        authenticateAccountIdentity(accountIdentifier, secret, true);
    Account account = authentication.account();
    authentication.emailLoginChallenge().ifPresent(accountEmailLoginChallengeRepository::delete);
    String jti = UUID.randomUUID().toString();
    long issuedAt = System.currentTimeMillis();
    long expiresAt = issuedAt + tokenProperties.getPlayerBootstrapExpirationMs();
    String bootstrapToken =
        mintToken(
            String.valueOf(account.getId()),
            tokenProperties.getPlayerBootstrapExpirationMs(),
            Map.of("aud", "player-bootstrap", "accountId", account.getId(), "jti", jti));
    sessionService.storeAccountSession(
        account.getId(), bootstrapToken, tokenProperties.getPlayerBootstrapExpirationMs());
    logger.info("Issued player bootstrap token for account {}", account.getId());
    return new PlayerBootstrapResult(
        account.getId(),
        bootstrapToken,
        Instant.ofEpochMilli(issuedAt).toString(),
        Instant.ofEpochMilli(expiresAt).toString());
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.bootstrap_worlds")
  public List<BootstrapWorldDto> listBootstrapWorlds(String bootstrapToken) {
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    return gameSessionClient.listGameplayWorlds().stream()
        .filter(world -> hasAdmissibleRealm(bootstrapContext, world.getWorldSlug()))
        .map(world -> new BootstrapWorldDto(world.getWorldSlug(), world.getDisplayName()))
        .toList();
  }

  @Override
  @Transactional
  @Timed(value = "account.bootstrap_realms")
  public List<BootstrapRealmDto> listBootstrapRealms(String bootstrapToken, String worldSlug) {
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    Instant evaluatedAt = Instant.now();
    Instant expiresAt = evaluatedAt.plusMillis(tokenProperties.getConnectScopeExpirationMs());
    return gameSessionClient.listGameplayRealms(worldSlug).stream()
        .map(this::readRuntimeRealmTarget)
        .map(realm -> requireRealmTarget(realm.tenantId(), realm.worldSlug(), realm.realmSlug()))
        .filter(realm -> isRealmAdmissible(bootstrapContext, realm))
        .map(
            realm ->
                new BootstrapRealmDto(
                    realm.worldSlug(),
                    realm.realmSlug(),
                    realm.realmId().toString(),
                    realm.displayName(),
                    realm.tenantId(),
                    realm.gameInstanceId(),
                    realm.pointerVersion(),
                    realm.requiresCharacterSelection(),
                    realm.stateScope(),
                    realm.characterCreationPolicy(),
                    evaluatedAt.toString(),
                    expiresAt.toString(),
                    mintAndRetainConnectScope(bootstrapContext, realm, evaluatedAt, expiresAt)))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.bootstrap_characters")
  public List<BootstrapCharacterDto> listBootstrapCharacters(
      String bootstrapToken, String worldSlug, String realmSlug, String connectScopeId) {
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    ConnectScopeContext scopeContext = requireConnectScopeContext(connectScopeId);
    validateConnectScopeAgainstBootstrap(bootstrapContext, scopeContext);
    validateConnectScopeRoute(scopeContext, worldSlug, realmSlug);
    RuntimeRealmTarget realm =
        requireCurrentAdmissibleConnectScopeTarget(bootstrapContext, scopeContext);
    return entityManagementClient
        .listCharactersByAccount(
            realm.tenantId(),
            bootstrapContext.accountId(),
            realm.gameInstanceId(),
            toPlayableStateScope(realm))
        .stream()
        .sorted(Comparator.comparing(net.firedevops.firemud.entitymanagement.v1.Character::getName))
        .map(
            character ->
                new BootstrapCharacterDto(
                    character.getId(),
                    character.getName(),
                    character.getLevel(),
                    realm.stateScope(),
                    realm.characterCreationPolicy()))
        .toList();
  }

  @Override
  @Timed(value = "account.public_production_join")
  public JoinPublicProductionResult joinPublicProduction(
      String bootstrapToken, JoinPublicProductionRequest request) {
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
    return joinPublicProductionForTrustedCaller(caller.accountId(), callerBinding, null, request);
  }

  @Override
  @Transactional
  @Timed(value = "account.direct_text_connect_scope")
  public DirectTextJoinScope issueDirectTextConnectScope(
      DirectTextCallerContext caller, DirectTextJoinTarget target) {
    if (caller == null
        || target == null
        || caller.accountId() <= 0L
        || caller.tenantId() != target.tenantId()
        || !caller.realmId().equals(target.realmId())
        || caller.gameInstanceId() != target.gameInstanceId()
        || !caller.playableStateNamespaceId().equals(target.playableStateNamespaceId())
        || !caller.playableStateScope().equals(target.playableStateScope())) {
      throw new AuthenticationException("CONNECT_SCOPE_INVALID", INVALID_CONNECT_SCOPE_MESSAGE);
    }
    requireAuthenticationEligible(requireAccount(caller.accountId()));
    RuntimeRealmTarget current =
        requireRealmTarget(target.tenantId(), target.worldSlug(), target.realmSlug());
    if (!isPublicProductionRealm(current)) {
      throw new AuthenticationException(
          "REALM_UNAVAILABLE", "Selected public realm is unavailable");
    }
    if (!current.realmId().equals(target.realmId())
        || !current.playableStateNamespaceId().equals(target.playableStateNamespaceId())
        || !current.stateScope().equals(target.playableStateScope())
        || current.gameInstanceId() != target.gameInstanceId()
        || current.catalogRevision() != target.catalogRevision()
        || current.pointerVersion() != target.pointerVersion()) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
    }
    Instant evaluatedAt = Instant.now();
    Instant expiresAt = evaluatedAt.plusMillis(tokenProperties.getConnectScopeExpirationMs());
    String scopeId =
        mintAndRetainConnectScope(
            new BootstrapContext(caller.accountId()), current, evaluatedAt, expiresAt);
    return new DirectTextJoinScope(scopeId, expiresAt.toString());
  }

  @Override
  @Timed(value = "account.public_production_join_internal")
  public JoinPublicProductionResult joinPublicProductionFromGameSession(
      DirectTextCallerContext caller, JoinPublicProductionRequest request) {
    if (caller == null
        || caller.accountId() <= 0L
        || !StringUtils.hasText(caller.sessionId())
        || request == null
        || !caller.requestId().equals(request.requestId())) {
      throw new AuthenticationException(
          "CONNECT_CONTEXT_INVALID", "JOIN caller context is missing");
    }
    requireAuthenticationEligible(requireAccount(caller.accountId()));
    return joinPublicProductionForTrustedCaller(
        caller.accountId(), caller.sessionId(), caller, request);
  }

  private JoinPublicProductionResult joinPublicProductionForTrustedCaller(
      long accountId,
      String callerBinding,
      DirectTextCallerContext directTextCaller,
      JoinPublicProductionRequest request) {
    if (request == null
        || !StringUtils.hasText(request.connectScopeId())
        || !StringUtils.hasText(request.requestId())) {
      throw new AuthenticationException("CONNECT_SCOPE_INVALID", INVALID_CONNECT_SCOPE_MESSAGE);
    }
    String requestId = request.requestId();
    String scopeTokenHash = AccountJoinDigest.tokenHash(request.connectScopeId());
    Optional<JoinOperation> existing = accountJoinOperationRepository.find(requestId);
    VerifiedJoinScope retained;
    if (existing.isPresent()) {
      JoinOperation operation = existing.orElseThrow();
      if (operation.accountId() != accountId
          || !operation.callerBinding().equals(callerBinding)
          || !operation.scopeTokenHash().equals(scopeTokenHash)) {
        throw new AuthenticationException(
            "IDEMPOTENCY_CONFLICT", "JOIN request ID was reused with different input");
      }
      retained = retainedJoinScope(request.connectScopeId());
      validateDirectTextJoinScope(directTextCaller, retained);
      requireMatchingJoinIntent(operation, requestId, callerBinding, retained);
    } else {
      ConnectScopeContext signedScope = requireConnectScopeContext(request.connectScopeId());
      retained = retainedJoinScope(request.connectScopeId());
      validateDirectTextJoinScope(directTextCaller, retained);
      validateSignedJoinScope(signedScope, retained, accountId);
      String intentDigest = AccountJoinDigest.intent(requestId, retained, callerBinding);
      try {
        joinTransactionTemplate.execute(
            transactionStatus ->
                accountJoinOperationRepository.insertIntent(
                    requestId, retained, callerBinding, intentDigest));
      } catch (RuntimeException ex) {
        Optional<JoinOperation> claimReadback = safeFindJoinOperation(requestId);
        if (claimReadback.isEmpty()) {
          throw new AuthenticationException(
              "AUTH_UNAVAILABLE", "JOIN request claim is uncertain; retry the same request", ex);
        }
        requireMatchingJoinIntent(claimReadback.orElseThrow(), requestId, callerBinding, retained);
      }
      JoinOperation claimed =
          accountJoinOperationRepository
              .find(requestId)
              .orElseThrow(
                  () ->
                      new AuthenticationException(
                          "AUTH_UNAVAILABLE", "JOIN request claim is not yet readable"));
      requireMatchingJoinIntent(claimed, requestId, callerBinding, retained);
    }

    try {
      return joinTransactionTemplate.execute(
          transactionStatus -> executeJoinAttempt(accountId, callerBinding, requestId, retained));
    } catch (AuthenticationException ex) {
      if ("IDEMPOTENCY_CONFLICT".equals(ex.getCode())) {
        throw ex;
      }
      recordJoinAttemptFailureAfterRollback(requestId, ex.getCode());
      return pendingJoinFailure(retained, ex.getCode());
    } catch (RuntimeException ex) {
      Optional<JoinOperation> outcomeReadback = safeFindJoinOperation(requestId);
      if (outcomeReadback.isPresent()) {
        JoinOperation operation = outcomeReadback.orElseThrow();
        requireMatchingJoinIntent(operation, requestId, callerBinding, retained);
        if (!"PENDING".equals(operation.status())) {
          return resultFromJoinOperation(operation, true);
        }
        recordJoinAttemptFailureAfterRollback(requestId, "AUTH_UNAVAILABLE");
      }
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "JOIN outcome is uncertain; retry the same request", ex);
    }
  }

  private JoinPublicProductionResult executeJoinAttempt(
      long accountId, String callerBinding, String requestId, VerifiedJoinScope scope) {
    accountJoinOperationRepository.lockAccount(accountId);
    JoinOperation operation =
        accountJoinOperationRepository
            .findForUpdate(requestId)
            .orElseThrow(() -> new IllegalStateException("JOIN request claim disappeared"));
    requireMatchingJoinIntent(operation, requestId, callerBinding, scope);

    if (!"PENDING".equals(operation.status())) {
      return replayJoinOperation(operation, callerBinding, scope);
    }

    if (isConnectScopeExpired(scope)) {
      accountJoinOperationRepository.recordAttemptFailure(
          requestId, "NOT_EVALUATED", "AUTH_UNAVAILABLE");
      return pendingJoinFailure(scope, "AUTH_UNAVAILABLE");
    }

    JoinEvaluation evaluation = evaluateJoin(scope);
    if (evaluation.failureCode() != null) {
      if (isRetryableJoinAuthorityFailure(evaluation)) {
        accountJoinOperationRepository.recordAttemptFailure(
            requestId, evaluation.authorityAvailability(), evaluation.failureCode());
        return pendingJoinFailure(scope, evaluation.failureCode());
      }
      return failedJoin(requestId, scope, evaluation.failureCode());
    }
    if (!"AVAILABLE".equals(evaluation.authorityAvailability())
        || evaluation.allowPublicJoin() == null
        || evaluation.entitlementVersion() == null) {
      accountJoinOperationRepository.recordAttemptFailure(
          requestId, evaluation.authorityAvailability(), "ENTITLEMENT_UNAVAILABLE");
      return pendingJoinFailure(scope, "ENTITLEMENT_UNAVAILABLE");
    }
    String requestDigest =
        AccountJoinDigest.request(
            scope, callerBinding, evaluation.allowPublicJoin(), evaluation.entitlementVersion());
    if (operation.requestDigest() != null
        && (!Integer.valueOf(1).equals(operation.requestDigestVersion())
            || !operation.requestDigest().equals(requestDigest))) {
      return failedJoin(requestId, scope, "IDEMPOTENCY_CONFLICT");
    }
    if (operation.requestDigest() == null) {
      accountJoinOperationRepository.bindPolicyEvidence(
          requestId, requestDigest, evaluation.entitlementVersion(), evaluation.allowPublicJoin());
    }
    if (!evaluation.gameplayAvailable()) {
      return failedJoin(requestId, scope, "TENANT_BILLING_BLOCKED");
    }
    if (!evaluation.allowPublicJoin()) {
      return failedJoin(requestId, scope, "PUBLIC_PRODUCTION_ADMISSION_DENIED");
    }

    JoinEvaluation commitEvaluation = evaluateJoin(scope);
    if (commitEvaluation.failureCode() != null) {
      if (isRetryableJoinAuthorityFailure(commitEvaluation)) {
        accountJoinOperationRepository.recordAttemptFailure(
            requestId, commitEvaluation.authorityAvailability(), commitEvaluation.failureCode());
        return pendingJoinFailure(scope, commitEvaluation.failureCode());
      }
      return failedJoin(requestId, scope, commitEvaluation.failureCode());
    }
    if (!"AVAILABLE".equals(commitEvaluation.authorityAvailability())) {
      accountJoinOperationRepository.recordAttemptFailure(
          requestId, commitEvaluation.authorityAvailability(), "ENTITLEMENT_UNAVAILABLE");
      return pendingJoinFailure(scope, "ENTITLEMENT_UNAVAILABLE");
    }
    String commitDigest =
        AccountJoinDigest.request(
            scope,
            callerBinding,
            commitEvaluation.allowPublicJoin(),
            commitEvaluation.entitlementVersion());
    if (!requestDigest.equals(commitDigest)) {
      return failedJoin(requestId, scope, "IDEMPOTENCY_CONFLICT");
    }
    if (!commitEvaluation.gameplayAvailable()) {
      return failedJoin(requestId, scope, "TENANT_BILLING_BLOCKED");
    }
    if (!commitEvaluation.allowPublicJoin()) {
      return failedJoin(requestId, scope, "PUBLIC_PRODUCTION_ADMISSION_DENIED");
    }
    AccountTenantMembership membership =
        accountTenantMembershipRepository
            .findByAccountIdAndTenantId(accountId, scope.tenantId())
            .orElse(null);
    boolean transitioned = false;
    String membershipTransitionType = null;
    if (membership == null) {
      membershipTransitionReceiptRepository.assertNewMembershipTransitionCanStart(
          accountId, scope.tenantId());
      membership = new AccountTenantMembership();
      membership.setAccount(requireAccount(accountId));
      membership.setTenantId(scope.tenantId());
      membership.setMembershipVersion(1L);
      membership.setMembershipAuthorityGeneration(1L);
      membership.setLifecycleState("ACTIVE");
      membership.setGameplayAdmissionAllowed(true);
      membership.setAuthorityProvenance("EXPLICIT_JOIN");
      accountTenantMembershipRepository.save(membership);
      transitioned = true;
      membershipTransitionType = "MEMBERSHIP_JOINED";
    } else if ("INACTIVE".equals(membership.getLifecycleState())) {
      membershipTransitionReceiptRepository.requireInactiveMembershipHistory(membership);
      accountJoinOperationRepository.recordCallerBoundAuthorityInvalidation(requestId);
      membership.setLifecycleState("ACTIVE");
      membership.setGameplayAdmissionAllowed(true);
      membership.setMembershipVersion(membership.getMembershipVersion() + 1L);
      membership.setMembershipAuthorityGeneration(
          membership.getMembershipAuthorityGeneration() + 1L);
      membership.setAuthorityProvenance("EXPLICIT_JOIN");
      accountTenantMembershipRepository.save(membership);
      transitioned = true;
      membershipTransitionType = "MEMBERSHIP_REACTIVATED";
    } else if ("ACTIVE".equals(membership.getLifecycleState())
        && membership.isGameplayAdmissionAllowed()) {
      membershipTransitionReceiptRepository
          .findLatestReceipt(accountId, scope.tenantId())
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Active Account membership lacks a provisional transition receipt"));
    } else {
      return failedJoin(requestId, scope, "MEMBERSHIP_RECONCILIATION_REQUIRED");
    }
    if (transitioned) {
      membershipTransitionReceiptRepository.appendTransition(
          membership, membershipTransitionType, requestId);
      String payload =
          AUDIT_JSON.writeValueAsString(
              new JoinAuditPayload(
                  accountId,
                  scope.tenantId(),
                  scope.worldSlug(),
                  scope.realmSlug(),
                  membership.getMembershipVersion(),
                  requestId));
      UUID auditEventId =
          UUID.nameUUIDFromBytes(
              ("account-join-audit/v1:" + requestId).getBytes(StandardCharsets.UTF_8));
      accountAuditOutboxRepository.append(
          auditEventId, "tenant", scope.tenantId(), "ACCOUNT_JOINED_PUBLIC_PRODUCTION", payload);
    }
    accountJoinOperationRepository.finish(
        requestId,
        "COMMITTED",
        transitioned ? "JOINED" : "ALREADY_ACTIVE",
        membership.getId(),
        membership.getMembershipVersion(),
        membership.getMembershipAuthorityGeneration());
    return new JoinPublicProductionResult(
        true,
        transitioned ? "JOINED" : "ALREADY_ACTIVE",
        accountId,
        scope.tenantId(),
        membership.getId(),
        membership.getMembershipVersion(),
        membership.getMembershipAuthorityGeneration(),
        false);
  }

  private JoinPublicProductionResult failedJoin(
      String requestId, VerifiedJoinScope scope, String outcomeCode) {
    accountJoinOperationRepository.finish(requestId, "FAILED", outcomeCode, null, null, null);
    return new JoinPublicProductionResult(
        false, outcomeCode, scope.accountId(), scope.tenantId(), 0L, 0L, 0L, false);
  }

  private JoinPublicProductionResult pendingJoinFailure(
      VerifiedJoinScope scope, String outcomeCode) {
    return new JoinPublicProductionResult(
        false, outcomeCode, scope.accountId(), scope.tenantId(), 0L, 0L, 0L, false);
  }

  private boolean isConnectScopeExpired(VerifiedJoinScope scope) {
    try {
      return !Instant.parse(scope.connectScopeExpiresAt()).isAfter(Instant.now());
    } catch (RuntimeException ex) {
      return true;
    }
  }

  private boolean isRetryableJoinAuthorityFailure(JoinEvaluation evaluation) {
    return "UNAVAILABLE".equals(evaluation.authorityAvailability())
        || "ADMISSION_POINTER_UNAVAILABLE".equals(evaluation.failureCode())
        || "AUTH_UNAVAILABLE".equals(evaluation.failureCode())
        || "ENTITLEMENT_UNAVAILABLE".equals(evaluation.failureCode());
  }

  private VerifiedJoinScope retainedJoinScope(String connectScopeId) {
    return accountConnectScopeRepository
        .find(connectScopeId)
        .orElseThrow(
            () ->
                new AuthenticationException(
                    "CONNECT_SCOPE_INVALID", "JOIN scope evidence is unavailable"));
  }

  private void validateSignedJoinScope(
      ConnectScopeContext signedScope, VerifiedJoinScope retained, long accountId) {
    if (retained.accountId() != accountId
        || signedScope.accountId() != accountId
        || signedScope.tenantId() != retained.tenantId()
        || !signedScope.realmId().equals(retained.realmId())
        || !signedScope.playableStateNamespaceId().equals(retained.playableStateNamespaceId())
        || !signedScope.playableStateScope().equals(retained.playableStateScope())
        || signedScope.gameInstanceId() != retained.gameInstanceId()
        || signedScope.catalogRevision() != retained.catalogRevision()
        || signedScope.pointerVersion() != retained.pointerVersion()
        || !signedScope.worldSlug().equals(retained.worldSlug())
        || !signedScope.realmSlug().equals(retained.realmSlug())
        || !signedScope.evaluatedAt().toString().equals(retained.evaluatedAt())
        || !signedScope
            .connectScopeExpiresAt()
            .toString()
            .equals(retained.connectScopeExpiresAt())) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
    }
  }

  private void requireMatchingJoinIntent(
      JoinOperation operation, String requestId, String callerBinding, VerifiedJoinScope scope) {
    String expectedIntentDigest = AccountJoinDigest.intent(requestId, scope, callerBinding);
    if (operation.accountId() != scope.accountId()
        || !operation.realmId().equals(scope.realmId())
        || !operation.callerBinding().equals(callerBinding)
        || !operation.scopeTokenHash().equals(AccountJoinDigest.tokenHash(scope.connectScopeId()))
        || !operation.connectScopeDigest().equals(scope.snapshotDigest())
        || operation.intentDigestVersion() != 1
        || !operation.intentDigest().equals(expectedIntentDigest)) {
      throw new AuthenticationException(
          "IDEMPOTENCY_CONFLICT", "JOIN request ID was reused with different input");
    }
  }

  private JoinPublicProductionResult replayJoinOperation(
      JoinOperation operation, String callerBinding, VerifiedJoinScope scope) {
    if (operation.requestDigest() != null) {
      JoinEvaluation evaluation = evaluateJoin(scope);
      if (evaluation.failureCode() != null) {
        throw new AuthenticationException(
            evaluation.failureCode(), "Current JOIN authority could not be revalidated");
      }
      if (!"AVAILABLE".equals(evaluation.authorityAvailability())) {
        throw new AuthenticationException(
            "ENTITLEMENT_UNAVAILABLE", "Current JOIN policy could not be revalidated");
      }
      String currentDigest =
          AccountJoinDigest.request(
              scope, callerBinding, evaluation.allowPublicJoin(), evaluation.entitlementVersion());
      if (!Integer.valueOf(1).equals(operation.requestDigestVersion())
          || !operation.requestDigest().equals(currentDigest)) {
        throw new AuthenticationException("IDEMPOTENCY_CONFLICT", "JOIN request digest changed");
      }
    }
    return resultFromJoinOperation(operation, true);
  }

  private JoinPublicProductionResult resultFromJoinOperation(
      JoinOperation operation, boolean replayed) {
    return new JoinPublicProductionResult(
        "COMMITTED".equals(operation.status()),
        operation.outcome(),
        operation.accountId(),
        operation.tenantId(),
        operation.membershipId() == null ? 0L : operation.membershipId(),
        operation.membershipVersion() == null ? 0L : operation.membershipVersion(),
        operation.membershipAuthorityGeneration() == null
            ? 0L
            : operation.membershipAuthorityGeneration(),
        replayed);
  }

  private Optional<JoinOperation> safeFindJoinOperation(String requestId) {
    try {
      return accountJoinOperationRepository.find(requestId);
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  private void recordJoinAttemptFailureAfterRollback(String requestId, String failureCode) {
    String availability =
        "ENTITLEMENT_UNAVAILABLE".equals(failureCode) ? "UNAVAILABLE" : "NOT_EVALUATED";
    try {
      joinTransactionTemplate.execute(
          transactionStatus -> {
            accountJoinOperationRepository
                .findForUpdate(requestId)
                .ifPresent(
                    operation -> {
                      if ("PENDING".equals(operation.status())) {
                        accountJoinOperationRepository.recordAttemptFailure(
                            requestId, availability, failureCode);
                      }
                    });
            return null;
          });
    } catch (RuntimeException ignored) {
      // The durable intent stays pending, and the caller retries the same request ID.
    }
  }

  private void validateDirectTextJoinScope(
      DirectTextCallerContext caller, VerifiedJoinScope scope) {
    if (caller != null
        && (caller.accountId() != scope.accountId()
            || caller.tenantId() != scope.tenantId()
            || !caller.realmId().equals(scope.realmId())
            || caller.gameInstanceId() != scope.gameInstanceId()
            || !caller.playableStateNamespaceId().equals(scope.playableStateNamespaceId())
            || !caller.playableStateScope().equals(scope.playableStateScope()))) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
    }
  }

  private JoinEvaluation evaluateJoin(VerifiedJoinScope scope) {
    return evaluateJoin(
        new ConnectScopeContext(
            scope.accountId(),
            scope.tenantId(),
            scope.realmId(),
            scope.worldSlug(),
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            Instant.parse(scope.evaluatedAt()),
            Instant.parse(scope.connectScopeExpiresAt())));
  }

  private JoinEvaluation evaluateJoin(ConnectScopeContext scope) {
    RuntimeRealmTarget target;
    try {
      target = requireCurrentConnectScopeTarget(scope);
    } catch (AuthenticationException ex) {
      if ("CONNECT_SCOPE_MISMATCH".equals(ex.getCode())
          || "ADMISSION_POINTER_UNAVAILABLE".equals(ex.getCode())) {
        return new JoinEvaluation("NOT_EVALUATED", null, null, false, ex.getCode());
      }
      throw ex;
    }
    if (!isPublicProductionRealm(target)) {
      return new JoinEvaluation(
          "NOT_EVALUATED", null, null, false, "PUBLIC_PRODUCTION_ADMISSION_DENIED");
    }
    try {
      RuntimeEntitlementsDto entitlement = lockedJoinEntitlement(scope.tenantId());
      return new JoinEvaluation(
          "AVAILABLE",
          entitlement.allowPublicJoin(),
          entitlement.entitlementVersion(),
          entitlement.gameplayAvailable(),
          null);
    } catch (AuthenticationException ex) {
      if ("ENTITLEMENT_UNAVAILABLE".equals(ex.getCode())) {
        return new JoinEvaluation("UNAVAILABLE", null, null, false, ex.getCode());
      }
      throw ex;
    }
  }

  private record JoinEvaluation(
      String authorityAvailability,
      Boolean allowPublicJoin,
      Long entitlementVersion,
      boolean gameplayAvailable,
      String failureCode) {}

  private RuntimeEntitlementsDto lockedJoinEntitlement(long tenantId) {
    List<net.firedevops.firemud.accountservice.entity.Subscription> rows =
        subscriptionRepository.findByTenantIdForUpdate(tenantId);
    if (rows.size() != 1) {
      throw new AuthenticationException(
          "ENTITLEMENT_UNAVAILABLE", "Tenant entitlement authority is missing or ambiguous");
    }
    var subscription = rows.getFirst();
    if (subscription.getEntitlementVersion() <= 0L) {
      throw new AuthenticationException(
          "ENTITLEMENT_UNAVAILABLE", "Entitlement version is missing");
    }
    return new RuntimeEntitlementsDto(
        tenantId,
        isGameplayAvailableStatus(subscription.getStatus()),
        isPublicJoinAllowedStatus(subscription.getStatus()),
        subscription.getEntitlementVersion(),
        subscription.getEntitlementVersion(),
        Instant.now().toString());
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.connect_token")
  public ConnectTokenResult issueConnectToken(String bootstrapToken, ConnectTokenRequest request) {
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    ConnectScopeContext scopeContext = requireConnectScopeContext(request.connectScopeId());
    validateConnectScopeAgainstBootstrap(bootstrapContext, scopeContext);
    Optional<
            net.firedevops.firemud.accountservice.service.session.SessionService.ConnectTokenReplay>
        cachedReplay =
            sessionService.getConnectTokenReplay(
                scopeContext.tenantId(),
                bootstrapContext.accountId(),
                request.connectScopeId(),
                request.requestId());
    if (cachedReplay.isPresent()) {
      var replay = cachedReplay.orElseThrow();
      if (replay.success()) {
        logger.info(
            "Replayed connect-token attempt for account {} tenant {} world {} realm {} requestId {}",
            bootstrapContext.accountId(),
            scopeContext.tenantId(),
            scopeContext.worldSlug(),
            scopeContext.realmSlug(),
            request.requestId());
        return replayedConnectTokenResult(replay.result());
      }
      logger.info(
          "Replayed failed connect-token attempt for account {} tenant {} world {} realm {} requestId {} code {}",
          bootstrapContext.accountId(),
          scopeContext.tenantId(),
          scopeContext.worldSlug(),
          scopeContext.realmSlug(),
          request.requestId(),
          replay.errorCode());
      throw new AuthenticationException(replay.errorCode(), replay.errorMessage());
    }
    try {
      return issueConnectTokenFresh(bootstrapContext, scopeContext, request);
    } catch (AuthenticationException ex) {
      if (!"AUTH_UNAVAILABLE".equals(ex.getCode())
          && !"ENTITLEMENT_UNAVAILABLE".equals(ex.getCode())) {
        sessionService.storeConnectTokenReplay(
            scopeContext.tenantId(),
            bootstrapContext.accountId(),
            request.connectScopeId(),
            request.requestId(),
            new net.firedevops.firemud.accountservice.service.session.SessionService
                .ConnectTokenReplay(false, null, ex.getCode(), ex.getMessage()),
            remainingConnectScopeReplayTtl(scopeContext));
      }
      throw ex;
    }
  }

  private ConnectTokenResult issueConnectTokenFresh(
      BootstrapContext bootstrapContext,
      ConnectScopeContext scopeContext,
      ConnectTokenRequest request) {
    RuntimeRealmTarget realm = requireCurrentConnectScopeTarget(scopeContext);
    RuntimeEntitlementsDto entitlements =
        getTenantEntitlementsForRuntime(scopeContext.tenantId(), request.requestId());
    if (!entitlements.gameplayAvailable()) {
      throw new AuthenticationException(
          "TENANT_BILLING_BLOCKED", "Gameplay is not available for this tenant");
    }
    RuntimeMembershipDto membership =
        getTenantMembershipForRuntime(
            bootstrapContext.accountId(), scopeContext.tenantId(), request.requestId());

    if (membership.membershipExists()
        && !"ACTIVE".equals(membership.membershipLifecycleState())
        && !"INACTIVE".equals(membership.membershipLifecycleState())) {
      throw new AuthenticationException(
          "CONNECT_TOKEN_REJECTED", "Membership authority requires reconciliation");
    }
    if (membership.membershipExists() && "INACTIVE".equals(membership.membershipLifecycleState())) {
      if (!isPublicProductionRealm(realm)) {
        throw new AuthenticationException(
            "NON_PUBLIC_ENROLLMENT_REQUIRED",
            "Existing game membership is required for this non-public realm");
      }
      if (!entitlements.allowPublicJoin()) {
        throw new AuthenticationException(
            "PUBLIC_PRODUCTION_ADMISSION_DENIED",
            "Public joining is not allowed for the selected game");
      }
      throw new AuthenticationException(
          "JOIN_REQUIRED", "Join the selected world before requesting a connect token");
    }
    if (!membership.membershipExists() || !membership.gameplayAdmissionAllowed()) {
      if (!isPublicProductionRealm(realm)) {
        if (membership.membershipExists()) {
          throw new AuthenticationException(
              "CONNECT_TOKEN_REJECTED", "Gameplay admission is not allowed for this account");
        }
        throw new AuthenticationException(
            "NON_PUBLIC_ENROLLMENT_REQUIRED",
            "Existing game membership is required for this non-public realm");
      }
      if (!entitlements.allowPublicJoin()) {
        throw new AuthenticationException(
            "PUBLIC_PRODUCTION_ADMISSION_DENIED",
            "Public joining is not allowed for the selected game");
      }
      throw new AuthenticationException(
          "JOIN_REQUIRED", "Join the selected world before requesting a connect token");
    }

    if (!isPublicProductionRealm(realm)
        && !hasRealmAccessGrant(
            bootstrapContext.accountId(),
            scopeContext.tenantId(),
            scopeContext.worldSlug(),
            scopeContext.realmSlug())) {
      throw new AuthenticationException(
          "REALM_ACCESS_DENIED",
          "The selected non-public realm does not have an active access grant");
    }

    String jti =
        stableId(
            "gameplay-connect",
            bootstrapContext.accountId(),
            scopeContext.tenantId(),
            scopeContext.gameInstanceId(),
            scopeContext.realmSlug(),
            request.requestId());
    long issuedAt = System.currentTimeMillis();
    long expiresAt = issuedAt + tokenProperties.getConnectTokenExpirationMs();
    String connectToken =
        mintToken(
            String.valueOf(bootstrapContext.accountId()),
            tokenProperties.getConnectTokenExpirationMs(),
            Map.of(
                "aud",
                "gameplay-connect",
                "accountId",
                bootstrapContext.accountId(),
                "tenantId",
                scopeContext.tenantId(),
                "gameInstanceId",
                scopeContext.gameInstanceId(),
                "pointerVersion",
                scopeContext.pointerVersion(),
                "realmSlug",
                scopeContext.realmSlug(),
                "worldSlug",
                scopeContext.worldSlug(),
                "connectScopeId",
                request.connectScopeId(),
                "requestId",
                request.requestId(),
                "jti",
                jti));
    sessionService.storeSession(
        scopeContext.tenantId(),
        bootstrapContext.accountId(),
        connectToken,
        tokenProperties.getConnectTokenExpirationMs());
    ConnectTokenResult result =
        new ConnectTokenResult(
            bootstrapContext.accountId(),
            scopeContext.tenantId(),
            scopeContext.gameInstanceId(),
            scopeContext.realmSlug(),
            request.connectScopeId(),
            connectToken,
            jti,
            request.requestId(),
            Instant.ofEpochMilli(issuedAt).toString(),
            Instant.ofEpochMilli(expiresAt).toString(),
            false);
    sessionService.storeConnectTokenReplay(
        scopeContext.tenantId(),
        bootstrapContext.accountId(),
        request.connectScopeId(),
        request.requestId(),
        new net.firedevops.firemud.accountservice.service.session.SessionService.ConnectTokenReplay(
            true, result, "", ""),
        Math.min(
            tokenProperties.getConnectTokenExpirationMs(),
            remainingConnectScopeReplayTtl(scopeContext)));
    logger.info(
        "Issued connect token for account {} tenant {} world {} realm {} gameInstance {} requestId {} jti {}",
        bootstrapContext.accountId(),
        scopeContext.tenantId(),
        scopeContext.worldSlug(),
        scopeContext.realmSlug(),
        scopeContext.gameInstanceId(),
        request.requestId(),
        jti);
    return result;
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
                    true,
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
                  created.setCreatedAt(now);
                  return created;
                });
    grant.setGrantVersion(grant.getGrantVersion() + 1L);
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
    accountRealmAccessGrantRepository.deleteByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
        accountId, tenantId, worldSlug, realmSlug);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.runtime_entitlements")
  public RuntimeEntitlementsDto getTenantEntitlementsForRuntime(Long tenantId, String requestId) {
    List<net.firedevops.firemud.accountservice.entity.Subscription> subscriptions =
        subscriptionRepository.findByTenantId(tenantId);
    if (subscriptions.size() != 1) {
      throw new AuthenticationException(
          "ENTITLEMENT_UNAVAILABLE",
          "Tenant entitlement authority is missing or ambiguous; retry later");
    }
    net.firedevops.firemud.accountservice.entity.Subscription subscription =
        subscriptions.getFirst();
    boolean gameplayAvailable = isGameplayAvailableStatus(subscription.getStatus());
    boolean allowPublicJoin = isPublicJoinAllowedStatus(subscription.getStatus());
    long version = subscription.getEntitlementVersion();
    return new RuntimeEntitlementsDto(
        tenantId, gameplayAvailable, allowPublicJoin, version, version, Instant.now().toString());
  }

  private BootstrapContext requireBootstrapContext(String bootstrapToken) {
    Claims claims =
        requireSignedTokenClaims(
            bootstrapToken,
            "player-bootstrap",
            "CONNECT_CONTEXT_INVALID",
            "Missing bootstrap token",
            "Invalid bootstrap token");
    try {
      long accountId = requireSignedActorAccountId(claims);
      if (!sessionService.isAccountSessionActive(accountId, bootstrapToken)) {
        throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Bootstrap token expired");
      }
      return new BootstrapContext(accountId);
    } catch (IllegalArgumentException ex) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token", ex);
    }
  }

  private RuntimeRealmTarget requireAdmissibleRealm(
      BootstrapContext bootstrapContext,
      Long expectedTenantId,
      String worldSlug,
      String realmSlug) {
    RuntimeRealmTarget realm = requireRealmTarget(expectedTenantId, worldSlug, realmSlug);
    if (!isRealmAdmissible(bootstrapContext, realm)) {
      throw new AuthenticationException(
          "ADMISSION_POINTER_UNAVAILABLE",
          "Selected gameplay realm is no longer admissible; rerun realm discovery before retrying gameplay entry");
    }
    return realm;
  }

  private RuntimeRealmTarget requireRealmTarget(
      Long expectedTenantId, String worldSlug, String realmSlug) {
    try {
      List<RuntimeRealmTarget> candidates =
          gameSessionClient.listGameplayRealms(worldSlug).stream()
              .filter(realm -> java.util.Objects.equals(worldSlug, realm.getWorldSlug()))
              .filter(realm -> java.util.Objects.equals(realmSlug, realm.getRealmSlug()))
              .map(this::readRuntimeRealmTarget)
              .filter(realm -> expectedTenantId == null || realm.tenantId() == expectedTenantId)
              .toList();
      if (candidates.size() != 1) {
        throw new IllegalStateException("Selected gameplay realm is absent or ambiguous");
      }
      RuntimeRealmTarget candidate = candidates.getFirst();
      RuntimeRealmTarget realm =
          readRuntimeRealmTarget(
              gameSessionClient.getAdmissionPointer(candidate.tenantId(), worldSlug, realmSlug));
      if (realm.tenantId() != candidate.tenantId()
          || !realm.realmId().equals(candidate.realmId())
          || !realm.playableStateNamespaceId().equals(candidate.playableStateNamespaceId())
          || !realm.stateScope().equals(candidate.stateScope())
          || realm.gameInstanceId() != candidate.gameInstanceId()
          || realm.visible() != candidate.visible()
          || realm.publicProductionRealm() != candidate.publicProductionRealm()
          || realm.catalogRevision() != candidate.catalogRevision()
          || realm.pointerVersion() != candidate.pointerVersion()
          || !java.util.Objects.equals(worldSlug, realm.worldSlug())
          || !java.util.Objects.equals(realmSlug, realm.realmSlug())) {
        throw new AuthenticationException(
            "ADMISSION_POINTER_UNAVAILABLE",
            "Selected gameplay realm is no longer admissible; rerun realm discovery before retrying gameplay entry");
      }
      return realm;
    } catch (IllegalArgumentException | IllegalStateException ex) {
      throw new AuthenticationException(
          "ADMISSION_POINTER_UNAVAILABLE",
          "Selected gameplay realm is no longer admissible; rerun realm discovery before retrying gameplay entry",
          ex);
    }
  }

  private boolean hasAdmissibleRealm(BootstrapContext bootstrapContext, String worldSlug) {
    try {
      return gameSessionClient.listGameplayRealms(worldSlug).stream()
          .map(this::readRuntimeRealmTarget)
          .anyMatch(realm -> isRealmAdmissible(bootstrapContext, realm));
    } catch (IllegalStateException ex) {
      return false;
    }
  }

  private boolean isRealmAdmissible(BootstrapContext bootstrapContext, RuntimeRealmTarget realm) {
    long tenantId = realm.tenantId();
    if (!isPublicProductionRealm(realm)) {
      if (accountTenantMembershipRepository
          .findByAccountIdAndTenantId(bootstrapContext.accountId(), tenantId)
          .filter(AccountTenantMembership::isGameplayAdmissionAllowed)
          .isEmpty()) {
        return false;
      }
      if (!hasRealmAccessGrant(
          bootstrapContext.accountId(), tenantId, realm.worldSlug(), realm.realmSlug())) {
        return false;
      }
    }
    RuntimeEntitlementsDto entitlements =
        getTenantEntitlementsForRuntime(tenantId, "bootstrap-discovery");
    return entitlements.gameplayAvailable();
  }

  private boolean isPublicProductionRealm(RuntimeRealmTarget realm) {
    return realm.visible() && realm.publicProductionRealm();
  }

  private String mintConnectScopeId(
      BootstrapContext bootstrapContext,
      RuntimeRealmTarget realm,
      Instant evaluatedAt,
      Instant expiresAt) {
    long expirationMs = Math.max(1L, expiresAt.toEpochMilli() - evaluatedAt.toEpochMilli());
    return mintToken(
        String.valueOf(bootstrapContext.accountId()),
        expirationMs,
        Map.ofEntries(
            Map.entry("aud", "bootstrap-connect-scope"),
            Map.entry("accountId", bootstrapContext.accountId()),
            Map.entry("tenantId", realm.tenantId()),
            Map.entry("realmId", realm.realmId().toString()),
            Map.entry("worldSlug", realm.worldSlug()),
            Map.entry("realmSlug", realm.realmSlug()),
            Map.entry("playableStateNamespaceId", realm.playableStateNamespaceId()),
            Map.entry("playableStateScope", realm.stateScope()),
            Map.entry("gameInstanceId", realm.gameInstanceId()),
            Map.entry("catalogRevision", realm.catalogRevision()),
            Map.entry("pointerVersion", realm.pointerVersion()),
            Map.entry("evaluatedAt", evaluatedAt.toString()),
            Map.entry("connectScopeExpiresAt", expiresAt.toString()),
            Map.entry(
                "jti",
                stableId(
                    "connect-scope",
                    bootstrapContext.accountId(),
                    Long.toString(realm.tenantId()),
                    realm.worldSlug(),
                    realm.realmSlug(),
                    realm.pointerVersion(),
                    evaluatedAt.toString()))));
  }

  private String mintAndRetainConnectScope(
      BootstrapContext caller, RuntimeRealmTarget realm, Instant evaluatedAt, Instant expiresAt) {
    String connectScopeId = mintConnectScopeId(caller, realm, evaluatedAt, expiresAt);
    if (isPublicProductionRealm(realm)) {
      VerifiedJoinScope pending =
          new VerifiedJoinScope(
              connectScopeId,
              caller.accountId(),
              realm.tenantId(),
              realm.realmId(),
              realm.worldSlug(),
              realm.realmSlug(),
              realm.playableStateNamespaceId(),
              realm.stateScope(),
              realm.gameInstanceId(),
              realm.catalogRevision(),
              realm.pointerVersion(),
              evaluatedAt.toString(),
              expiresAt.toString(),
              "");
      accountConnectScopeRepository.insert(
          new VerifiedJoinScope(
              pending.connectScopeId(),
              pending.accountId(),
              pending.tenantId(),
              pending.realmId(),
              pending.worldSlug(),
              pending.realmSlug(),
              pending.playableStateNamespaceId(),
              pending.playableStateScope(),
              pending.gameInstanceId(),
              pending.catalogRevision(),
              pending.pointerVersion(),
              pending.evaluatedAt(),
              pending.connectScopeExpiresAt(),
              AccountJoinDigest.scope(pending)));
    }
    return connectScopeId;
  }

  private ConnectScopeContext requireConnectScopeContext(String connectScopeId) {
    Claims claims =
        requireSignedTokenClaims(
            connectScopeId,
            "bootstrap-connect-scope",
            "CONNECT_SCOPE_INVALID",
            INVALID_CONNECT_SCOPE_MESSAGE,
            INVALID_CONNECT_SCOPE_MESSAGE);
    try {
      JwtClaims.SignedGameplayRoutingClaims routingClaims =
          JwtClaims.requireSignedGameplayRoutingClaims(
              claims, "signed token account subject mismatch");
      Instant connectScopeExpiresAt = parseInstant(claims.get("connectScopeExpiresAt"));
      Instant evaluatedAt = parseInstant(claims.get("evaluatedAt"));
      if (connectScopeExpiresAt == null) {
        throw new AuthenticationException("CONNECT_SCOPE_INVALID", INVALID_CONNECT_SCOPE_MESSAGE);
      }
      if (evaluatedAt == null) {
        throw new AuthenticationException("CONNECT_SCOPE_INVALID", INVALID_CONNECT_SCOPE_MESSAGE);
      }
      return new ConnectScopeContext(
          routingClaims.accountId(),
          routingClaims.tenantId(),
          requireCanonicalRealmId(claims.get("realmId")),
          routingClaims.worldSlug(),
          routingClaims.realmSlug(),
          JwtClaims.requireText(claims.get("playableStateNamespaceId"), "playableStateNamespaceId"),
          JwtClaims.requireText(claims.get("playableStateScope"), "playableStateScope"),
          routingClaims.gameInstanceId(),
          requirePositiveLong(claims.get("catalogRevision"), "catalogRevision"),
          routingClaims.pointerVersion(),
          evaluatedAt,
          connectScopeExpiresAt);
    } catch (IllegalArgumentException ex) {
      throw new AuthenticationException("CONNECT_SCOPE_INVALID", INVALID_CONNECT_SCOPE_MESSAGE, ex);
    }
  }

  private static long requireSignedActorAccountId(Claims claims) {
    return JwtClaims.requireSignedActorAccountId(claims, "signed token account subject mismatch");
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

  private void validateConnectScopeAgainstBootstrap(
      BootstrapContext bootstrapContext, ConnectScopeContext scopeContext) {
    if (scopeContext.accountId() != bootstrapContext.accountId()) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
    }
  }

  private void validateConnectScopeRoute(
      ConnectScopeContext scopeContext, String worldSlug, String realmSlug) {
    if (!java.util.Objects.equals(scopeContext.worldSlug(), worldSlug)
        || !java.util.Objects.equals(scopeContext.realmSlug(), realmSlug)) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
    }
  }

  private RuntimeRealmTarget requireCurrentConnectScopeTarget(ConnectScopeContext scopeContext) {
    RuntimeRealmTarget currentRealm =
        requireRealmTarget(
            scopeContext.tenantId(), scopeContext.worldSlug(), scopeContext.realmSlug());
    if (currentRealm.tenantId() != scopeContext.tenantId()
        || !currentRealm.realmId().equals(scopeContext.realmId())
        || !currentRealm.playableStateNamespaceId().equals(scopeContext.playableStateNamespaceId())
        || !currentRealm.stateScope().equals(scopeContext.playableStateScope())
        || currentRealm.gameInstanceId() != scopeContext.gameInstanceId()
        || currentRealm.catalogRevision() != scopeContext.catalogRevision()
        || currentRealm.pointerVersion() != scopeContext.pointerVersion()) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
    }
    return currentRealm;
  }

  private RuntimeRealmTarget requireCurrentAdmissibleConnectScopeTarget(
      BootstrapContext bootstrapContext, ConnectScopeContext scopeContext) {
    RuntimeRealmTarget currentRealm = requireCurrentConnectScopeTarget(scopeContext);
    requireGameplayAdmissionMembership(bootstrapContext.accountId(), currentRealm);
    if (!isRealmAdmissible(bootstrapContext, currentRealm)) {
      throw new AuthenticationException(
          "ADMISSION_POINTER_UNAVAILABLE",
          "Selected gameplay realm is no longer admissible; rerun realm discovery before retrying gameplay entry");
    }
    return currentRealm;
  }

  private void requireGameplayAdmissionMembership(long accountId, RuntimeRealmTarget realm) {
    if (accountTenantMembershipRepository
        .findByAccountIdAndTenantId(accountId, realm.tenantId())
        .filter(AccountTenantMembership::isGameplayAdmissionAllowed)
        .isEmpty()) {
      throw new AuthenticationException("JOIN_REQUIRED", JOIN_REQUIRED_CHARACTERS_MESSAGE);
    }
  }

  private long remainingConnectScopeReplayTtl(ConnectScopeContext scopeContext) {
    long remainingMs =
        scopeContext.connectScopeExpiresAt().toEpochMilli() - System.currentTimeMillis();
    return Math.max(1L, remainingMs);
  }

  private Optional<Account> findAccountForAuthentication(String usernameOrEmail) {
    Optional<Account> emailMatch =
        accountRepository.findByEmail(EmailCanonicalization.normalize(usernameOrEmail));
    if (emailMatch.isPresent()) {
      return emailMatch;
    }

    return accountRepository.findByUsername(usernameOrEmail);
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
    Optional<AccountEmailLoginChallenge> emailLoginChallenge = Optional.empty();
    if (allowEmailLoginOtp && allowsEmailLoginOtp(account)) {
      accountEmailLoginChallengeRepository.lockAccountChallenge(account.getId());
      emailLoginChallenge = activeEmailLoginChallenge(account);
    }
    if (emailLoginChallenge
        .filter(challenge -> matchesEmailLoginOtp(challenge, password))
        .isPresent()) {
      requireAuthenticationEligible(account);
      return new PrimaryAuthentication(account, emailLoginChallenge);
    }
    if (!allowsPassword(account) || !verifyPassword(password, account.getPasswordHash())) {
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

  private record PrimaryAuthentication(
      Account account, Optional<AccountEmailLoginChallenge> emailLoginChallenge) {}

  private Account requireAccount(Long accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(() -> new IllegalArgumentException("Account not found"));
  }

  private boolean hasRealmAccessGrant(
      Long accountId, Long tenantId, String worldSlug, String realmSlug) {
    return accountRealmAccessGrantRepository.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
        accountId, tenantId, worldSlug, realmSlug);
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
        account.getId(),
        "globalRoles",
        globalRoles,
        "jti",
        UUID.randomUUID().toString());
  }

  private Instant parseInstant(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value.toString());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private String stableId(Object... components) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Object component : components) {
        digest.update(String.valueOf(component).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      byte[] encoded = digest.digest();
      StringBuilder builder = new StringBuilder(encoded.length * 2);
      for (byte value : encoded) {
        builder.append(Character.forDigit((value >> 4) & 0xF, 16));
        builder.append(Character.forDigit(value & 0xF, 16));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("Missing SHA-256 implementation", ex);
    }
  }

  private boolean isGameplayAvailableStatus(String status) {
    if (status == null) {
      return false;
    }
    return switch (status.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "active", "trialing", "past_due", "grace" -> true;
      default -> false;
    };
  }

  private boolean isPublicJoinAllowedStatus(String status) {
    if (status == null) {
      return false;
    }
    return switch (status.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "active", "trialing", "past_due" -> true;
      default -> false;
    };
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
    return profileMapper.toDto(profile);
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
    requireProfileMembership(request.tenantId(), request.accountId());
    Profile profile =
        profileRepository
            .findByAccountIdAndTenantId(request.accountId(), request.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Profile not found"));
    profile.setDisplayName(request.displayName());
    profile.setBio(request.bio());
    profile.setPresenceVisibilityPolicy(request.presenceVisibilityPolicy());
    profile = profileRepository.save(profile);
    runAfterCommit(
        () ->
            notificationService.sendNotification(
                request.tenantId(), request.accountId(), "Profile updated"));
    return profileMapper.toDto(profile);
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
    List<ProfileDto> profiles =
        profileRepository.findByAccountId(accountId).stream().map(profileMapper::toDto).toList();
    return new AccountDataExportDto(accountMapper.toDto(account), profiles);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.tenant_export")
  public TenantDataExportDto exportTenantData(Long tenantId, Long accountId) {
    throw new ResponseStatusException(
        HttpStatus.NOT_IMPLEMENTED,
        "Tenant-admin export is unavailable until the tenant-wide export contract is implemented");
  }

  @Override
  @Transactional
  @Timed(value = "account.delete")
  public void deleteAccount(Long accountId) {
    requireAccount(accountId);
    throw new AccountLifecycleException(
        "ACCOUNT_DELETE_WORKFLOW_UNAVAILABLE",
        "Account deletion is unavailable until its provider reconciliation and data retention workflow is implemented");
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
            emailService.sendEmail(
                account.getEmail(),
                "Password Reset",
                String.format(readTemplate("password-reset.txt"), url)));
  }

  @Override
  @Transactional
  @Timed(value = "account.complete_password_reset")
  public void completePasswordReset(CompletePasswordResetRequest request) {
    net.firedevops.firemud.accountservice.entity.PasswordResetToken token =
        passwordResetTokenRepository
            .findByToken(request.token())
            .orElseThrow(() -> new IllegalArgumentException("Invalid token"));
    LocalDateTime now = LocalDateTime.now();
    if (token.getExpiresAt().isBefore(now)) {
      throw new IllegalArgumentException("Token expired");
    }
    if (!passwordResetTokenRepository.consumeIfUnexpired(token, now)) {
      throw new IllegalArgumentException("Invalid token");
    }
    net.firedevops.firemud.accountservice.entity.Account account = token.getAccount();
    account.setPasswordHash(hashPassword(request.newPassword()));
    accountRepository.save(account);
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
    issueEmailVerification(accountOptional.get());
  }

  private void issueEmailVerification(Account account) {
    EmailVerificationToken token = new EmailVerificationToken();
    token.setAccount(account);
    token.setToken(java.util.UUID.randomUUID().toString());
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(24));
    emailVerificationTokenRepository.save(token);
    String url = String.format(mailProperties.getVerificationUrl(), token.getToken());
    runAfterCommit(
        () ->
            emailService.sendEmail(
                account.getEmail(),
                "Email Verification",
                String.format(readTemplate("email-verification.txt"), url)));
  }

  @Override
  @Transactional
  @Timed(value = "account.verify_email")
  public void verifyEmail(VerifyEmailRequest request) {
    EmailVerificationToken token =
        emailVerificationTokenRepository
            .findByToken(request.token())
            .orElseThrow(() -> new IllegalArgumentException("Invalid token"));
    LocalDateTime now = LocalDateTime.now();
    if (token.getExpiresAt().isBefore(now)) {
      throw new IllegalArgumentException("Token expired");
    }
    if (!emailVerificationTokenRepository.consumeIfUnexpired(token, now)) {
      throw new IllegalArgumentException("Invalid token");
    }
    Account account = token.getAccount();
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
            emailService.sendEmail(
                account.getEmail(),
                "Username Reminder",
                String.format(readTemplate("username-reminder.txt"), account.getUsername())));
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

  private record BootstrapContext(long accountId) {}

  private record JoinAuditPayload(
      long accountId,
      long tenantId,
      String worldSlug,
      String realmSlug,
      long membershipVersion,
      String requestId) {}

  private ConnectTokenResult replayedConnectTokenResult(ConnectTokenResult result) {
    return new ConnectTokenResult(
        result.accountId(),
        result.tenantId(),
        result.gameInstanceId(),
        result.realmSlug(),
        result.connectScopeId(),
        result.connectToken(),
        result.jti(),
        result.requestId(),
        result.issuedAt(),
        result.expiresAt(),
        true);
  }

  private RuntimeRealmTarget readRuntimeRealmTarget(
      net.firedevops.firemud.gamesession.v1.GameplayRealm realm) {
    return buildRuntimeRealmTarget(
        realm.getTenantId(),
        realm.getRealmId(),
        realm.getPlayableStateNamespaceId(),
        realm.getGameInstanceId(),
        realm.getCatalogRevision(),
        realm.getPointerVersion(),
        realm.getWorldSlug(),
        realm.getRealmSlug(),
        realm.getVisible(),
        realm.getPublicProductionRealm(),
        realm.getStateScope(),
        realm.getCharacterCreationPolicy(),
        realm.getRequiresCharacterSelection(),
        realm.getDisplayName());
  }

  private RuntimeRealmTarget readRuntimeRealmTarget(
      net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer realm) {
    return buildRuntimeRealmTarget(
        realm.getTenantId(),
        realm.getRealmId(),
        realm.getPlayableStateNamespaceId(),
        realm.getGameInstanceId(),
        realm.getCatalogRevision(),
        realm.getPointerVersion(),
        realm.getWorldSlug(),
        realm.getRealmSlug(),
        realm.getVisible(),
        realm.getPublicProductionRealm(),
        realm.getStateScope(),
        realm.getCharacterCreationPolicy(),
        realm.getRequiresCharacterSelection(),
        realm.getRealmDisplayName());
  }

  private RuntimeRealmTarget buildRuntimeRealmTarget(
      String tenantIdText,
      String realmIdText,
      String playableStateNamespaceId,
      String gameInstanceIdText,
      long catalogRevision,
      long pointerVersion,
      String worldSlug,
      String realmSlug,
      boolean visible,
      boolean publicProductionRealm,
      String stateScope,
      String characterCreationPolicy,
      boolean requiresCharacterSelection,
      String displayName) {
    long tenantId = requirePositiveLong(tenantIdText, "tenantId");
    UUID realmId = requireCanonicalRealmId(realmIdText);
    long gameInstanceId = requirePositiveLong(gameInstanceIdText, "gameInstanceId");
    if (!StringUtils.hasText(playableStateNamespaceId)) {
      throw new IllegalArgumentException("playableStateNamespaceId is required");
    }
    if (catalogRevision <= 0) {
      throw new IllegalArgumentException("catalogRevision must be positive");
    }
    if (pointerVersion <= 0) {
      throw new IllegalArgumentException("pointerVersion must be positive");
    }
    if (!StringUtils.hasText(worldSlug)) {
      throw new IllegalArgumentException("worldSlug is required");
    }
    if (!StringUtils.hasText(realmSlug)) {
      throw new IllegalArgumentException("realmSlug is required");
    }
    if (!"SHARED".equals(stateScope) && !"ISOLATED".equals(stateScope)) {
      throw new IllegalArgumentException("stateScope must be SHARED or ISOLATED");
    }
    return new RuntimeRealmTarget(
        tenantId,
        realmId,
        playableStateNamespaceId,
        gameInstanceId,
        worldSlug,
        realmSlug,
        pointerVersion,
        catalogRevision,
        visible,
        publicProductionRealm,
        stateScope,
        characterCreationPolicy,
        requiresCharacterSelection,
        displayName);
  }

  private PlayableStateScope toPlayableStateScope(RuntimeRealmTarget realm) {
    if ("SHARED".equals(realm.stateScope())) {
      return PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
    }
    if ("ISOLATED".equals(realm.stateScope())) {
      return PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
    }
    throw new IllegalArgumentException("stateScope must be SHARED or ISOLATED");
  }

  private long requirePositiveLong(Object value, String field) {
    return JwtClaims.requireLong(value, field, false);
  }

  private UUID requireCanonicalRealmId(Object value) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("realmId must be a canonical UUID");
    }
    try {
      UUID realmId = UUID.fromString(text);
      if (!realmId.toString().equals(text)) {
        throw new IllegalArgumentException("realmId must be a canonical UUID");
      }
      return realmId;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("realmId must be a canonical UUID", ex);
    }
  }

  private record ConnectScopeContext(
      long accountId,
      long tenantId,
      UUID realmId,
      String worldSlug,
      String realmSlug,
      String playableStateNamespaceId,
      String playableStateScope,
      long gameInstanceId,
      long catalogRevision,
      long pointerVersion,
      Instant evaluatedAt,
      Instant connectScopeExpiresAt) {}

  private record RuntimeRealmTarget(
      long tenantId,
      UUID realmId,
      String playableStateNamespaceId,
      long gameInstanceId,
      String worldSlug,
      String realmSlug,
      long pointerVersion,
      long catalogRevision,
      boolean visible,
      boolean publicProductionRealm,
      String stateScope,
      String characterCreationPolicy,
      boolean requiresCharacterSelection,
      String displayName) {}
}
