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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.config.AuthProperties;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.dto.AccountDataExportDto;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.BootstrapCharacterDto;
import net.firedevops.firemud.accountservice.dto.BootstrapRealmDto;
import net.firedevops.firemud.accountservice.dto.BootstrapWorldDto;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.PublicProductionMembershipResult;
import net.firedevops.firemud.accountservice.dto.RuntimeEntitlementsDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.entity.EmailVerificationToken;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.mapper.ProfileMapper;
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
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AccountServiceImpl implements AccountService {
  private static final Logger logger = LoggingUtil.getLogger(AccountServiceImpl.class);

  private final AccountRepository accountRepository;
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
  private final AuthProperties authProperties;
  private final GameplayCatalogProperties gameplayCatalogProperties;
  private final LoggingAdminClient loggingAdminClient;
  private final GameSessionClient gameSessionClient;
  private final EntityManagementClient entityManagementClient;
  private final JwtUtil jwtUtil;
  private final net.firedevops.firemud.accountservice.service.session.SessionService sessionService;
  private final SagaRunner sagaRunner;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Dependencies are injected and kept internal")
  public AccountServiceImpl(
      AccountRepository accountRepository,
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
      AuthProperties authProperties,
      GameplayCatalogProperties gameplayCatalogProperties,
      LoggingAdminClient loggingAdminClient,
      GameSessionClient gameSessionClient,
      EntityManagementClient entityManagementClient,
      JwtUtil jwtUtil,
      net.firedevops.firemud.accountservice.service.session.SessionService sessionService,
      SagaRunner sagaRunner) {
    this.accountRepository = accountRepository;
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
    this.authProperties = authProperties;
    this.gameplayCatalogProperties = gameplayCatalogProperties;
    this.loggingAdminClient = loggingAdminClient;
    this.gameSessionClient = gameSessionClient;
    this.entityManagementClient = entityManagementClient;
    this.jwtUtil = jwtUtil;
    this.sessionService = sessionService;
    this.sagaRunner = sagaRunner;
  }

  @Override
  @Transactional
  @Timed(value = "account.create")
  public AccountDto createAccount(CreateAccountRequest request) {
    logger.info("Creating account {} for tenant {}", request.username(), request.tenantId());
    Account account = new Account();
    account.setUsername(request.username());
    account.setEmail(request.email());
    account.setPasswordHash(hashPassword(request.password()));
    account.setRole("player");
    Profile profile = new Profile();
    profile.setTenantId(request.tenantId());
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setTenantId(request.tenantId());
    membership.setGameplayAdmissionAllowed(true);

    SagaBuilder builder = new SagaBuilder("accountCreation");
    builder.step(
        "persistAccount",
        () -> {
          Account saved = accountRepository.save(account);
          account.setId(saved.getId());
          membership.setAccount(saved);
          accountTenantMembershipRepository.save(membership);
          profile.setAccount(saved);
          profileRepository.save(profile);
        },
        () -> {
          profileRepository.delete(profile);
          accountTenantMembershipRepository.delete(membership);
          accountRepository.delete(account);
        });
    var saga = builder.build();
    try {
      sagaRunner.run(saga);
    } catch (SagaException e) {
      logger.warn("Account creation saga failed", e);
      throw new IllegalStateException("Account creation failed", e);
    }
    runAfterCommit(() -> safeLogAccountCreation(request.tenantId(), account.getId()));

    return accountMapper.toDto(account);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.authenticate")
  public net.firedevops.firemud.accountservice.dto.AuthenticationResult authenticate(
      Long tenantId, String username, String password, String otp) {
    Optional<Account> accountOpt = findAccountForAuthentication(username);
    Account account =
        accountOpt.orElseThrow(
            () ->
                new AuthenticationException(
                    AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials"));
    if (!verifyPassword(password, account.getPasswordHash())) {
      throw new AuthenticationException(
          AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials");
    }
    requireGameplayMembership(account.getId(), tenantId, "Invalid credentials");
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
  @Timed(value = "account.bootstrap_worlds")
  public List<BootstrapWorldDto> listBootstrapWorlds(String bootstrapToken) {
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    return gameSessionClient.listGameplayWorlds().stream()
        .filter(world -> hasAdmissibleRealm(bootstrapContext, world.getWorldSlug()))
        .map(world -> new BootstrapWorldDto(world.getWorldSlug(), world.getDisplayName()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.bootstrap_realms")
  public List<BootstrapRealmDto> listBootstrapRealms(String bootstrapToken, String worldSlug) {
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    Instant evaluatedAt = Instant.now();
    Instant expiresAt = evaluatedAt.plusMillis(authProperties.getConnectScopeExpirationMs());
    return gameSessionClient.listGameplayRealms(worldSlug).stream()
        .filter(realm -> isRealmAdmissible(bootstrapContext, realm))
        .map(
            realm ->
                new BootstrapRealmDto(
                    realm.getWorldSlug(),
                    realm.getRealmSlug(),
                    realm.getDisplayName(),
                    Long.parseLong(realm.getTenantId()),
                    Long.parseLong(realm.getGameInstanceId()),
                    realm.getPointerVersion(),
                    realm.getRequiresCharacterSelection(),
                    realm.getStateScope(),
                    realm.getCharacterCreationPolicy(),
                    evaluatedAt.toString(),
                    expiresAt.toString(),
                    mintConnectScopeId(bootstrapContext, realm, evaluatedAt, expiresAt)))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.bootstrap_characters")
  public List<BootstrapCharacterDto> listBootstrapCharacters(
      String bootstrapToken, String worldSlug, String realmSlug) {
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer realm =
        requireAdmissibleRealm(bootstrapContext, worldSlug, realmSlug);
    return entityManagementClient
        .listCharactersByAccount(
            Long.parseLong(realm.getTenantId()),
            bootstrapContext.accountId(),
            Long.parseLong(realm.getGameInstanceId()),
            toPlayableStateScope(realm))
        .stream()
        .sorted(Comparator.comparing(net.firedevops.firemud.entitymanagement.v1.Character::getName))
        .map(
            character ->
                new BootstrapCharacterDto(
                    character.getId(),
                    character.getName(),
                    character.getLevel(),
                    realm.getStateScope(),
                    realm.getCharacterCreationPolicy()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.connect_token")
  public ConnectTokenResult issueConnectToken(String bootstrapToken, ConnectTokenRequest request) {
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    ConnectScopeContext scopeContext = requireConnectScopeContext(request.connectScopeId());
    validateConnectScopeAgainstBootstrap(bootstrapContext, scopeContext);
    net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer currentRealm =
        requireAdmissibleRealm(
            bootstrapContext, scopeContext.worldSlug(), scopeContext.realmSlug());
    if (Long.parseLong(currentRealm.getTenantId()) != scopeContext.tenantId()
        || Long.parseLong(currentRealm.getGameInstanceId()) != scopeContext.gameInstanceId()
        || currentRealm.getPointerVersion() != scopeContext.pointerVersion()) {
      throw new AuthenticationException(
          "CONNECT_SCOPE_MISMATCH", "Selected gameplay target is no longer admissible");
    }

    RuntimeMembershipDto membership =
        getTenantMembershipForRuntime(
            bootstrapContext.accountId(), scopeContext.tenantId(), request.requestId());
    if (!membership.gameplayAdmissionAllowed()
        && isPublicProductionRealm(currentRealm)
        && scopeContext.tenantId() == Long.parseLong(currentRealm.getTenantId())) {
      membership =
          toRuntimeMembership(
              ensurePublicProductionPlayerMembership(
                  bootstrapContext.accountId(),
                  scopeContext.tenantId(),
                  scopeContext.realmSlug(),
                  request.requestId()));
    }
    if (!membership.gameplayAdmissionAllowed()) {
      throw new AuthenticationException(
          "CONNECT_TOKEN_REJECTED", "Gameplay admission is not allowed for this account");
    }
    RuntimeEntitlementsDto entitlements =
        getTenantEntitlementsForRuntime(scopeContext.tenantId(), request.requestId());
    if (!entitlements.gameplayAvailable()) {
      throw new AuthenticationException(
          "CONNECT_TOKEN_REJECTED", "Gameplay is not available for this tenant");
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
    long expiresAt = issuedAt + authProperties.getConnectTokenExpirationMs();
    String connectToken =
        mintToken(
            String.valueOf(bootstrapContext.accountId()),
            authProperties.getConnectTokenExpirationMs(),
            Map.of(
                "aud",
                "gameplay-connect",
                "accountId",
                bootstrapContext.accountId(),
                "tenantId",
                scopeContext.tenantId(),
                "gameInstanceId",
                scopeContext.gameInstanceId(),
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
        authProperties.getConnectTokenExpirationMs());
    logger.info(
        "Issued connect token for account {} tenant {} world {} realm {} gameInstance {} requestId {} jti {}",
        bootstrapContext.accountId(),
        scopeContext.tenantId(),
        scopeContext.worldSlug(),
        scopeContext.realmSlug(),
        scopeContext.gameInstanceId(),
        request.requestId(),
        jti);
    return new ConnectTokenResult(
        bootstrapContext.accountId(),
        scopeContext.tenantId(),
        scopeContext.gameInstanceId(),
        scopeContext.realmSlug(),
        request.connectScopeId(),
        connectToken,
        jti,
        Instant.ofEpochMilli(issuedAt).toString(),
        Instant.ofEpochMilli(expiresAt).toString());
  }

  @Override
  @Transactional
  @Timed(value = "account.public_production_membership")
  public PublicProductionMembershipResult ensurePublicProductionPlayerMembership(
      Long accountId, Long tenantId, String realmSlug, String requestId) {
    requireAccount(accountId);
    GameplayCatalogProperties.Realm realm = requirePublicProductionRealm(tenantId, realmSlug);
    RuntimeEntitlementsDto entitlements = getTenantEntitlementsForRuntime(tenantId, requestId);
    if (!entitlements.gameplayAvailable()) {
      throw new AuthenticationException(
          "GAMEPLAY_UNAVAILABLE", "Gameplay is not available for this tenant");
    }

    Optional<AccountTenantMembership> existing =
        accountTenantMembershipRepository.findByAccountIdAndTenantId(accountId, tenantId);
    if (existing.isPresent()) {
      AccountTenantMembership membership = existing.orElseThrow();
      if (!membership.isGameplayAdmissionAllowed()) {
        throw new AuthenticationException(
            "CONNECT_TOKEN_REJECTED", "Gameplay admission is not allowed for this account");
      }
      return new PublicProductionMembershipResult(
          accountId,
          tenantId,
          realm.getSlug(),
          membership.getId(),
          false,
          Instant.now().toString());
    }

    AccountTenantMembership created = new AccountTenantMembership();
    created.setAccount(requireAccount(accountId));
    created.setTenantId(tenantId);
    created.setGameplayAdmissionAllowed(true);
    try {
      created = accountTenantMembershipRepository.saveAndFlush(created);
    } catch (DataIntegrityViolationException ex) {
      AccountTenantMembership concurrent =
          accountTenantMembershipRepository
              .findByAccountIdAndTenantId(accountId, tenantId)
              .orElseThrow(() -> ex);
      if (!concurrent.isGameplayAdmissionAllowed()) {
        throw new AuthenticationException(
            "CONNECT_TOKEN_REJECTED", "Gameplay admission is not allowed for this account");
      }
      return new PublicProductionMembershipResult(
          accountId,
          tenantId,
          realm.getSlug(),
          concurrent.getId(),
          false,
          Instant.now().toString());
    }

    long membershipVersion = created.getId();
    runAfterCommit(
        () ->
            safeLogPublicProductionMembershipCreated(
                tenantId, accountId, realm.getSlug(), membershipVersion, requestId));
    logger.info(
        "Created public-production gameplay membership for account {} tenant {} realm {} membershipVersion {} requestId {}",
        accountId,
        tenantId,
        realm.getSlug(),
        membershipVersion,
        requestId);
    return new PublicProductionMembershipResult(
        accountId, tenantId, realm.getSlug(), membershipVersion, true, Instant.now().toString());
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
        membership.map(AccountTenantMembership::isGameplayAdmissionAllowed).orElse(false),
        membership.map(AccountTenantMembership::getId).orElse(0L),
        Instant.now().toString());
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

  private BootstrapContext requireBootstrapContext(String bootstrapToken) {
    if (bootstrapToken == null || bootstrapToken.isBlank()) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Missing bootstrap token");
    }
    Claims claims;
    try {
      claims = jwtUtil.parseToken(bootstrapToken).getPayload();
    } catch (RuntimeException ex) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token", ex);
    }
    if (!"player-bootstrap".equals(claimText(claims.get("aud")))) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token");
    }
    Long accountId = parseLong(claims.get("accountId"));
    Long tenantId = parseLong(claims.get("tenantId"));
    if (accountId == null || tenantId == null) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token");
    }
    Long storedAccountId = sessionService.getAccountId(tenantId, bootstrapToken);
    if (storedAccountId == null || !storedAccountId.equals(accountId)) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Bootstrap token expired");
    }
    return new BootstrapContext(accountId, tenantId);
  }

  private net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer requireAdmissibleRealm(
      BootstrapContext bootstrapContext, String worldSlug, String realmSlug) {
    try {
      net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer realm =
          gameSessionClient.getAdmissionPointer(worldSlug, realmSlug);
      if (!isRealmAdmissible(bootstrapContext, realm)) {
        throw new AuthenticationException(
            "ADMISSION_POINTER_UNAVAILABLE", "Selected gameplay realm is not admissible");
      }
      return realm;
    } catch (IllegalStateException ex) {
      throw new AuthenticationException(
          "ADMISSION_POINTER_UNAVAILABLE", "Selected gameplay realm is not admissible", ex);
    }
  }

  private boolean hasAdmissibleRealm(BootstrapContext bootstrapContext, String worldSlug) {
    try {
      return gameSessionClient.listGameplayRealms(worldSlug).stream()
          .anyMatch(realm -> isRealmAdmissible(bootstrapContext, realm));
    } catch (IllegalStateException ex) {
      return false;
    }
  }

  private boolean isRealmAdmissible(
      BootstrapContext bootstrapContext, GameplayCatalogProperties.Realm realm) {
    if (!realm.isVisible()) {
      return false;
    }
    RuntimeMembershipDto membership =
        getTenantMembershipForRuntime(
            bootstrapContext.accountId(), realm.getTenantId(), "bootstrap-discovery");
    if (!membership.gameplayAdmissionAllowed() && !isPublicProductionRealm(realm)) {
      return false;
    }
    RuntimeEntitlementsDto entitlements =
        getTenantEntitlementsForRuntime(realm.getTenantId(), "bootstrap-discovery");
    return entitlements.gameplayAvailable();
  }

  private boolean isRealmAdmissible(
      BootstrapContext bootstrapContext,
      net.firedevops.firemud.gamesession.v1.GameplayRealm realm) {
    RuntimeMembershipDto membership =
        getTenantMembershipForRuntime(
            bootstrapContext.accountId(),
            Long.parseLong(realm.getTenantId()),
            "bootstrap-discovery");
    if (!membership.gameplayAdmissionAllowed() && !isPublicProductionRealm(realm)) {
      return false;
    }
    RuntimeEntitlementsDto entitlements =
        getTenantEntitlementsForRuntime(Long.parseLong(realm.getTenantId()), "bootstrap-discovery");
    return entitlements.gameplayAvailable();
  }

  private boolean isRealmAdmissible(
      BootstrapContext bootstrapContext,
      net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer realm) {
    RuntimeMembershipDto membership =
        getTenantMembershipForRuntime(
            bootstrapContext.accountId(),
            Long.parseLong(realm.getTenantId()),
            "bootstrap-discovery");
    if (!membership.gameplayAdmissionAllowed() && !isPublicProductionRealm(realm)) {
      return false;
    }
    RuntimeEntitlementsDto entitlements =
        getTenantEntitlementsForRuntime(Long.parseLong(realm.getTenantId()), "bootstrap-discovery");
    return entitlements.gameplayAvailable();
  }

  private String mintConnectScopeId(
      BootstrapContext bootstrapContext,
      net.firedevops.firemud.gamesession.v1.GameplayRealm realm,
      Instant evaluatedAt,
      Instant expiresAt) {
    long expirationMs = Math.max(1L, expiresAt.toEpochMilli() - evaluatedAt.toEpochMilli());
    return mintToken(
        String.valueOf(bootstrapContext.accountId()),
        expirationMs,
        Map.of(
            "aud",
            "bootstrap-connect-scope",
            "accountId",
            bootstrapContext.accountId(),
            "tenantId",
            Long.parseLong(realm.getTenantId()),
            "worldSlug",
            realm.getWorldSlug(),
            "realmSlug",
            realm.getRealmSlug(),
            "gameInstanceId",
            Long.parseLong(realm.getGameInstanceId()),
            "pointerVersion",
            realm.getPointerVersion(),
            "evaluatedAt",
            evaluatedAt.toString(),
            "connectScopeExpiresAt",
            expiresAt.toString(),
            "jti",
            stableId(
                "connect-scope",
                bootstrapContext.accountId(),
                realm.getTenantId(),
                realm.getWorldSlug(),
                realm.getRealmSlug(),
                realm.getPointerVersion(),
                evaluatedAt.toString())));
  }

  private ConnectScopeContext requireConnectScopeContext(String connectScopeId) {
    Claims claims;
    try {
      claims = jwtUtil.parseToken(connectScopeId).getPayload();
    } catch (RuntimeException ex) {
      throw new AuthenticationException("CONNECT_SCOPE_INVALID", "Invalid connect scope", ex);
    }
    if (!"bootstrap-connect-scope".equals(claimText(claims.get("aud")))) {
      throw new AuthenticationException("CONNECT_SCOPE_INVALID", "Invalid connect scope");
    }
    Long accountId = parseLong(claims.get("accountId"));
    Long tenantId = parseLong(claims.get("tenantId"));
    Long gameInstanceId = parseLong(claims.get("gameInstanceId"));
    Long pointerVersion = parseLong(claims.get("pointerVersion"));
    String worldSlug = claimText(claims.get("worldSlug"));
    String realmSlug = claimText(claims.get("realmSlug"));
    if (accountId == null
        || tenantId == null
        || gameInstanceId == null
        || pointerVersion == null
        || worldSlug.isBlank()
        || realmSlug.isBlank()) {
      throw new AuthenticationException("CONNECT_SCOPE_INVALID", "Invalid connect scope");
    }
    return new ConnectScopeContext(
        accountId, tenantId, worldSlug, realmSlug, gameInstanceId, pointerVersion);
  }

  private void validateConnectScopeAgainstBootstrap(
      BootstrapContext bootstrapContext, ConnectScopeContext scopeContext) {
    if (scopeContext.accountId() != bootstrapContext.accountId()) {
      throw new AuthenticationException(
          "CONNECT_SCOPE_MISMATCH", "Selected gameplay target is no longer admissible");
    }
  }

  private Optional<Account> findAccountForAuthentication(String usernameOrEmail) {
    Optional<Account> usernameMatch = accountRepository.findByUsername(usernameOrEmail);
    if (usernameMatch.isPresent()) {
      return usernameMatch;
    }

    return accountRepository.findByEmail(usernameOrEmail);
  }

  private Account requireAccount(Long accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(() -> new IllegalArgumentException("Account not found"));
  }

  private Account requireAccountWithMembership(Long accountId, Long tenantId) {
    Account account = requireAccount(accountId);
    requireMembership(accountId, tenantId);
    return account;
  }

  private void requireGameplayMembership(Long accountId, Long tenantId, String message) {
    AccountTenantMembership membership =
        accountTenantMembershipRepository
            .findByAccountIdAndTenantId(accountId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException(message));
    if (!membership.isGameplayAdmissionAllowed()) {
      throw new IllegalArgumentException(message);
    }
  }

  private void requireMembership(Long accountId, Long tenantId) {
    requireGameplayMembership(accountId, tenantId, "Account not found");
  }

  private boolean hasAnyMembership(Long accountId) {
    return accountTenantMembershipRepository.existsByAccountId(accountId);
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
      case "active", "trialing", "grace" -> true;
      default -> false;
    };
  }

  private void safeLogAccountCreation(Long tenantId, Long accountId) {
    try {
      loggingAdminClient.logAccountCreation(tenantId, accountId);
    } catch (RuntimeException ex) {
      logger.warn("Account creation logging failed for account {}", accountId, ex);
    }
  }

  private void safeLogPublicProductionMembershipCreated(
      Long tenantId, Long accountId, String realmSlug, long membershipVersion, String requestId) {
    try {
      loggingAdminClient.logPublicProductionMembershipCreated(
          tenantId, accountId, realmSlug, membershipVersion, requestId);
    } catch (RuntimeException ex) {
      logger.warn(
          "Failed to record public-production membership creation for tenant {} account {} realm {} requestId {}",
          tenantId,
          accountId,
          realmSlug,
          requestId,
          ex);
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
    profile.setPresenceVisibilityPolicy(request.presenceVisibilityPolicy());
    profile = profileRepository.save(profile);
    runAfterCommit(
        () ->
            notificationService.sendNotification(
                request.tenantId(), request.accountId(), "Profile updated"));
    return profileMapper.toDto(profile);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "account.export")
  public AccountDataExportDto exportAccountData(Long tenantId, Long accountId) {
    Account account = requireAccountWithMembership(accountId, tenantId);
    Profile profile =
        profileRepository.findByAccountIdAndTenantId(accountId, tenantId).orElse(null);
    return new AccountDataExportDto(
        accountMapper.toDto(account), profile != null ? profileMapper.toDto(profile) : null);
  }

  @Override
  @Transactional
  @Timed(value = "account.delete")
  public void deleteAccount(Long tenantId, Long accountId) {
    Account account = requireAccountWithMembership(accountId, tenantId);
    paymentTransactionRepository.deleteByAccountId(accountId, tenantId);
    subscriptionRepository.deleteByAccountId(accountId, tenantId);
    profileRepository
        .findByAccountIdAndTenantId(accountId, tenantId)
        .ifPresent(profileRepository::delete);
    accountTenantMembershipRepository.deleteByAccountIdAndTenantId(accountId, tenantId);
    if (!hasAnyMembership(accountId)) {
      accountRepository.delete(account);
    }
  }

  @Override
  @Transactional
  @Timed(value = "account.request_password_reset")
  public void requestPasswordReset(PasswordResetRequest request) {
    net.firedevops.firemud.accountservice.entity.Account account =
        accountRepository
            .findByEmail(request.email())
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    requireMembership(account.getId(), request.tenantId());
    net.firedevops.firemud.accountservice.entity.PasswordResetToken token =
        new net.firedevops.firemud.accountservice.entity.PasswordResetToken();
    token.setAccount(account);
    token.setTenantId(request.tenantId());
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
    runAfterCommit(
        () ->
            notificationService.sendNotification(
                request.tenantId(), account.getId(), "Password reset requested"));
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
    Account account = requireAccountWithMembership(accountId, tenantId);
    EmailVerificationToken token = new EmailVerificationToken();
    token.setAccount(account);
    token.setTenantId(tenantId);
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
    runAfterCommit(
        () ->
            notificationService.sendNotification(
                tenantId, accountId, "Email verification requested"));
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
    Account account = requireAccountWithMembership(request.accountId(), request.tenantId());

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
            .findByEmail(request.email())
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    requireMembership(account.getId(), request.tenantId());
    runAfterCommit(
        () ->
            emailService.sendEmail(
                account.getEmail(),
                "Username Reminder",
                String.format(readTemplate("username-reminder.txt"), account.getUsername())));
    runAfterCommit(
        () ->
            notificationService.sendNotification(
                request.tenantId(), account.getId(), "Username reminder requested"));
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

  private record BootstrapContext(long accountId, long tenantId) {}

  private RuntimeMembershipDto toRuntimeMembership(PublicProductionMembershipResult result) {
    return new RuntimeMembershipDto(
        result.accountId(),
        result.tenantId(),
        true,
        result.membershipVersion(),
        result.evaluatedAt());
  }

  private GameplayCatalogProperties.Realm requirePublicProductionRealm(
      Long tenantId, String realmSlug) {
    GameplayCatalogProperties.Realm realm =
        gameplayCatalogProperties.getWorlds().stream()
            .filter(Objects::nonNull)
            .flatMap(world -> world.getRealms().stream())
            .filter(Objects::nonNull)
            .filter(candidate -> candidate.getTenantId() == tenantId)
            .filter(candidate -> Objects.equals(candidate.getSlug(), realmSlug))
            .findFirst()
            .orElseThrow(
                () ->
                    new AuthenticationException(
                        "ADMISSION_POINTER_UNAVAILABLE",
                        "Selected gameplay realm is not admissible"));
    if (!isPublicProductionRealm(realm)) {
      throw new AuthenticationException(
          "PUBLIC_PRODUCTION_MEMBERSHIP_NOT_ALLOWED",
          "Public membership creation is not allowed for this realm");
    }
    return realm;
  }

  private boolean isPublicProductionRealm(GameplayCatalogProperties.Realm realm) {
    return realm.isVisible() && "production".equalsIgnoreCase(realm.getSlug());
  }

  private boolean isPublicProductionRealm(
      net.firedevops.firemud.gamesession.v1.GameplayRealm realm) {
    return "production".equalsIgnoreCase(realm.getRealmSlug());
  }

  private boolean isPublicProductionRealm(
      net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer realm) {
    return "production".equalsIgnoreCase(realm.getRealmSlug());
  }

  private PlayableStateScope toPlayableStateScope(GameplayCatalogProperties.Realm realm) {
    return switch (realm.getStateScope()) {
      case SHARED -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case ISOLATED -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
    };
  }

  private PlayableStateScope toPlayableStateScope(
      net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer realm) {
    return switch (realm.getStateScope()) {
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
    };
  }

  private record ConnectScopeContext(
      long accountId,
      long tenantId,
      String worldSlug,
      String realmSlug,
      long gameInstanceId,
      long pointerVersion) {}
}
