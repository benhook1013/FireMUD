package net.firedevops.firemud.accountservice.service.impl;

import com.bastiaanjansen.otp.TOTPGenerator;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
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
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantResult;
import net.firedevops.firemud.accountservice.dto.RuntimeEntitlementsDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto;
import net.firedevops.firemud.accountservice.dto.TenantDataExportDto;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountRealmAccessGrant;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.entity.EmailVerificationToken;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.mapper.ProfileMapper;
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
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class AccountServiceImpl implements AccountService {
  private static final Logger logger = LoggingUtil.getLogger(AccountServiceImpl.class);
  private static final String STALE_CONNECT_SCOPE_MESSAGE =
      "Selected gameplay target is no longer admissible; rerun bootstrap discovery and request a fresh connect scope";
  private static final String INVALID_CONNECT_SCOPE_MESSAGE =
      "Connect scope is invalid or expired; rerun bootstrap discovery and request a fresh connect scope";

  private final AccountRepository accountRepository;
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
      LoggingAdminClient loggingAdminClient,
      GameSessionClient gameSessionClient,
      EntityManagementClient entityManagementClient,
      JwtUtil jwtUtil,
      net.firedevops.firemud.accountservice.service.session.SessionService sessionService,
      SagaRunner sagaRunner) {
    this.accountRepository = accountRepository;
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
    Account account = authenticateAccountIdentity(username, password, otp);
    requireGameplayMembership(account.getId(), tenantId, "Invalid credentials");
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
    Account account = authenticateAccountIdentity(username, password, otp);
    String jti = UUID.randomUUID().toString();
    long issuedAt = System.currentTimeMillis();
    long expiresAt = issuedAt + tokenProperties.getPlayerBootstrapExpirationMs();
    String bootstrapToken =
        mintToken(
            String.valueOf(account.getId()),
            tokenProperties.getPlayerBootstrapExpirationMs(),
            Map.of(
                "aud",
                "player-bootstrap",
                "accountId",
                account.getId(),
                "tenantId",
                tenantId,
                "jti",
                jti));
    sessionService.storeSession(
        tenantId,
        account.getId(),
        bootstrapToken,
        tokenProperties.getPlayerBootstrapExpirationMs());
    logger.info(
        "Issued player bootstrap token for account {} tenant {}", account.getId(), tenantId);
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
  @Transactional(readOnly = true)
  @Timed(value = "account.bootstrap_realms")
  public List<BootstrapRealmDto> listBootstrapRealms(String bootstrapToken, String worldSlug) {
    BootstrapContext bootstrapContext = requireBootstrapContext(bootstrapToken);
    Instant evaluatedAt = Instant.now();
    Instant expiresAt = evaluatedAt.plusMillis(tokenProperties.getConnectScopeExpirationMs());
    return gameSessionClient.listGameplayRealms(worldSlug).stream()
        .map(this::readRuntimeRealmTarget)
        .flatMap(Optional::stream)
        .filter(realm -> isRealmAdmissible(bootstrapContext, realm))
        .map(
            realm ->
                new BootstrapRealmDto(
                    realm.worldSlug(),
                    realm.realmSlug(),
                    realm.displayName(),
                    realm.tenantId(),
                    realm.gameInstanceId(),
                    realm.pointerVersion(),
                    realm.requiresCharacterSelection(),
                    realm.stateScope(),
                    realm.characterCreationPolicy(),
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
    RuntimeRealmTarget realm = requireAdmissibleRealm(bootstrapContext, worldSlug, realmSlug);
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
      sessionService.storeConnectTokenReplay(
          scopeContext.tenantId(),
          bootstrapContext.accountId(),
          request.connectScopeId(),
          request.requestId(),
          new net.firedevops.firemud.accountservice.service.session.SessionService
              .ConnectTokenReplay(false, null, ex.getCode(), ex.getMessage()),
          remainingConnectScopeReplayTtl(scopeContext));
      throw ex;
    }
  }

  private ConnectTokenResult issueConnectTokenFresh(
      BootstrapContext bootstrapContext,
      ConnectScopeContext scopeContext,
      ConnectTokenRequest request) {
    RuntimeRealmTarget currentRealm =
        requireAdmissibleRealm(
            bootstrapContext, scopeContext.worldSlug(), scopeContext.realmSlug());
    if (currentRealm.tenantId() != scopeContext.tenantId()
        || currentRealm.gameInstanceId() != scopeContext.gameInstanceId()
        || currentRealm.pointerVersion() != scopeContext.pointerVersion()) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", STALE_CONNECT_SCOPE_MESSAGE);
    }

    boolean nonPublicGrant =
        hasRealmAccessGrant(
            bootstrapContext.accountId(),
            scopeContext.tenantId(),
            scopeContext.worldSlug(),
            scopeContext.realmSlug());
    RuntimeMembershipDto membership =
        getTenantMembershipForRuntime(
            bootstrapContext.accountId(), scopeContext.tenantId(), request.requestId());
    if (!nonPublicGrant
        && !membership.gameplayAdmissionAllowed()
        && currentRealm.publicProductionRealm()
        && scopeContext.tenantId() == currentRealm.tenantId()) {
      membership =
          toRuntimeMembership(
              ensurePublicProductionPlayerMembership(
                  bootstrapContext.accountId(),
                  scopeContext.tenantId(),
                  scopeContext.worldSlug(),
                  scopeContext.realmSlug(),
                  request.requestId()));
    }
    if (!nonPublicGrant && !membership.gameplayAdmissionAllowed()) {
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
  @Transactional
  @Timed(value = "account.public_production_membership")
  public PublicProductionMembershipResult ensurePublicProductionPlayerMembership(
      Long accountId, Long tenantId, String worldSlug, String realmSlug, String requestId) {
    Optional<
            net.firedevops.firemud.accountservice.service.session.SessionService
                .PublicProductionMembershipReplay>
        cachedReplay =
            sessionService.getPublicProductionMembershipReplay(
                tenantId, accountId, worldSlug, realmSlug, requestId);
    if (cachedReplay.isPresent()) {
      var replay = cachedReplay.orElseThrow();
      if (replay.success()) {
        logger.info(
            "Replayed public-production membership attempt for account {} tenant {} world {} realm {} requestId {}",
            accountId,
            tenantId,
            worldSlug,
            realmSlug,
            requestId);
        return replayedPublicProductionMembershipResult(replay.result());
      }
      logger.info(
          "Replayed failed public-production membership attempt for account {} tenant {} world {} realm {} requestId {} code {}",
          accountId,
          tenantId,
          worldSlug,
          realmSlug,
          requestId,
          replay.errorCode());
      throw new AuthenticationException(replay.errorCode(), replay.errorMessage());
    }
    try {
      PublicProductionMembershipResult result =
          ensurePublicProductionPlayerMembershipFresh(
              accountId, tenantId, worldSlug, realmSlug, requestId);
      sessionService.storePublicProductionMembershipReplay(
          tenantId,
          accountId,
          worldSlug,
          realmSlug,
          requestId,
          new net.firedevops.firemud.accountservice.service.session.SessionService
              .PublicProductionMembershipReplay(true, result, "", ""),
          tokenProperties.getSessionExpirationMs());
      return result;
    } catch (AuthenticationException ex) {
      sessionService.storePublicProductionMembershipReplay(
          tenantId,
          accountId,
          worldSlug,
          realmSlug,
          requestId,
          new net.firedevops.firemud.accountservice.service.session.SessionService
              .PublicProductionMembershipReplay(false, null, ex.getCode(), ex.getMessage()),
          tokenProperties.getSessionExpirationMs());
      throw ex;
    }
  }

  private PublicProductionMembershipResult ensurePublicProductionPlayerMembershipFresh(
      Long accountId, Long tenantId, String worldSlug, String realmSlug, String requestId) {
    requireAccount(accountId);
    var realm = requirePublicProductionRealm(tenantId, worldSlug, realmSlug);
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
          realm.worldSlug(),
          realm.realmSlug(),
          membership.getId(),
          false,
          requestId,
          Instant.now().toString(),
          false);
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
          realm.worldSlug(),
          realm.realmSlug(),
          concurrent.getId(),
          false,
          requestId,
          Instant.now().toString(),
          false);
    }

    long membershipVersion = created.getId();
    runAfterCommit(
        () ->
            safeLogPublicProductionMembershipCreated(
                tenantId,
                accountId,
                realm.worldSlug(),
                realm.realmSlug(),
                membershipVersion,
                requestId));
    logger.info(
        "Created public-production gameplay membership for account {} tenant {} world {} realm {} membershipVersion {} requestId {}",
        accountId,
        tenantId,
        realm.worldSlug(),
        realm.realmSlug(),
        membershipVersion,
        requestId);
    return new PublicProductionMembershipResult(
        accountId,
        tenantId,
        realm.worldSlug(),
        realm.realmSlug(),
        membershipVersion,
        true,
        requestId,
        Instant.now().toString(),
        false);
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
    Claims claims =
        requireSignedTokenClaims(
            bootstrapToken,
            "player-bootstrap",
            "CONNECT_CONTEXT_INVALID",
            "Missing bootstrap token",
            "Invalid bootstrap token");
    try {
      long accountId = JwtClaims.requireLong(claims.get("accountId"), "accountId", false);
      long tenantId = JwtClaims.requireLong(claims.get("tenantId"), "tenantId", false);
      Long storedAccountId = sessionService.getAccountId(tenantId, bootstrapToken);
      if (storedAccountId == null || !storedAccountId.equals(accountId)) {
        throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Bootstrap token expired");
      }
      return new BootstrapContext(accountId, tenantId);
    } catch (IllegalArgumentException ex) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token", ex);
    }
  }

  private RuntimeRealmTarget requireAdmissibleRealm(
      BootstrapContext bootstrapContext, String worldSlug, String realmSlug) {
    try {
      Optional<RuntimeRealmTarget> realm =
          readRuntimeRealmTarget(
              gameSessionClient.getAdmissionPointer(
                  bootstrapContext.tenantId(), worldSlug, realmSlug));
      if (realm.isEmpty()
          || realm.get().tenantId() != bootstrapContext.tenantId()
          || !java.util.Objects.equals(worldSlug, realm.get().worldSlug())
          || !java.util.Objects.equals(realmSlug, realm.get().realmSlug())
          || !isRealmAdmissible(bootstrapContext, realm.get())) {
        throw new AuthenticationException(
            "ADMISSION_POINTER_UNAVAILABLE",
            "Selected gameplay realm is no longer admissible; rerun realm discovery before retrying gameplay entry");
      }
      return realm.orElseThrow();
    } catch (IllegalStateException ex) {
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
          .flatMap(Optional::stream)
          .anyMatch(realm -> isRealmAdmissible(bootstrapContext, realm));
    } catch (IllegalStateException ex) {
      return false;
    }
  }

  private boolean isRealmAdmissible(BootstrapContext bootstrapContext, RuntimeRealmTarget realm) {
    long tenantId = realm.tenantId();
    if (!realm.visible()) {
      if (!hasRealmAccessGrant(
          bootstrapContext.accountId(), tenantId, realm.worldSlug(), realm.realmSlug())) {
        return false;
      }
    } else {
      RuntimeMembershipDto membership =
          getTenantMembershipForRuntime(
              bootstrapContext.accountId(), tenantId, "bootstrap-discovery");
      if (!membership.gameplayAdmissionAllowed() && !realm.publicProductionRealm()) {
        return false;
      }
    }
    RuntimeEntitlementsDto entitlements =
        getTenantEntitlementsForRuntime(tenantId, "bootstrap-discovery");
    return entitlements.gameplayAvailable();
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
        Map.of(
            "aud",
            "bootstrap-connect-scope",
            "accountId",
            bootstrapContext.accountId(),
            "tenantId",
            realm.tenantId(),
            "worldSlug",
            realm.worldSlug(),
            "realmSlug",
            realm.realmSlug(),
            "gameInstanceId",
            realm.gameInstanceId(),
            "pointerVersion",
            realm.pointerVersion(),
            "evaluatedAt",
            evaluatedAt.toString(),
            "connectScopeExpiresAt",
            expiresAt.toString(),
            "jti",
            stableId(
                "connect-scope",
                bootstrapContext.accountId(),
                Long.toString(realm.tenantId()),
                realm.worldSlug(),
                realm.realmSlug(),
                realm.pointerVersion(),
                evaluatedAt.toString())));
  }

  private ConnectScopeContext requireConnectScopeContext(String connectScopeId) {
    Claims claims =
        requireSignedTokenClaims(
            connectScopeId,
            "bootstrap-connect-scope",
            "CONNECT_SCOPE_INVALID",
            INVALID_CONNECT_SCOPE_MESSAGE,
            INVALID_CONNECT_SCOPE_MESSAGE);
    long accountId;
    long tenantId;
    long gameInstanceId;
    long pointerVersion;
    String worldSlug;
    String realmSlug;
    try {
      accountId = JwtClaims.requireLong(claims.get("accountId"), "accountId", false);
      tenantId = JwtClaims.requireLong(claims.get("tenantId"), "tenantId", false);
      gameInstanceId = JwtClaims.requireLong(claims.get("gameInstanceId"), "gameInstanceId", false);
      pointerVersion = JwtClaims.requireLong(claims.get("pointerVersion"), "pointerVersion", false);
      worldSlug = JwtClaims.requireText(claims.get("worldSlug"), "worldSlug");
      realmSlug = JwtClaims.requireText(claims.get("realmSlug"), "realmSlug");
    } catch (IllegalArgumentException ex) {
      throw new AuthenticationException("CONNECT_SCOPE_INVALID", INVALID_CONNECT_SCOPE_MESSAGE, ex);
    }
    Instant connectScopeExpiresAt = parseInstant(claims.get("connectScopeExpiresAt"));
    if (connectScopeExpiresAt == null) {
      throw new AuthenticationException("CONNECT_SCOPE_INVALID", INVALID_CONNECT_SCOPE_MESSAGE);
    }
    return new ConnectScopeContext(
        accountId,
        tenantId,
        worldSlug,
        realmSlug,
        gameInstanceId,
        pointerVersion,
        connectScopeExpiresAt);
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

  private long remainingConnectScopeReplayTtl(ConnectScopeContext scopeContext) {
    long remainingMs =
        scopeContext.connectScopeExpiresAt().toEpochMilli() - System.currentTimeMillis();
    return Math.max(1L, remainingMs);
  }

  private Optional<Account> findAccountForAuthentication(String usernameOrEmail) {
    Optional<Account> usernameMatch = accountRepository.findByUsername(usernameOrEmail);
    if (usernameMatch.isPresent()) {
      return usernameMatch;
    }

    return accountRepository.findByEmail(usernameOrEmail);
  }

  private Account authenticateAccountIdentity(String username, String password, String otp) {
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
    if (account.getTwoFactorSecret() != null
        && ("platformAdmin".equals(account.getRole())
            || "tenantAdmin".equals(account.getRole())
            || "moderator".equals(account.getRole()))) {
      TOTPGenerator generator = new TOTPGenerator.Builder(account.getTwoFactorSecret()).build();
      if (otp == null || !generator.verify(otp)) {
        throw new AuthenticationException(
            AuthenticationErrorCodes.OTP_REQUIRED, "Invalid 2FA code");
      }
    }
    return account;
  }

  private Account requireAccount(Long accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(() -> new IllegalArgumentException("Account not found"));
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

  private boolean hasRealmAccessGrant(
      Long accountId, Long tenantId, String worldSlug, String realmSlug) {
    return accountRealmAccessGrantRepository.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
        accountId, tenantId, worldSlug, realmSlug);
  }

  private String mintToken(String subject, long expirationMs, Map<String, Object> claims) {
    long now = System.currentTimeMillis();
    String secret = jwtAuthProperties.getJwtSecret();
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
      Long tenantId,
      Long accountId,
      String worldSlug,
      String realmSlug,
      long membershipVersion,
      String requestId) {
    try {
      loggingAdminClient.logPublicProductionMembershipCreated(
          tenantId, accountId, worldSlug, realmSlug, membershipVersion, requestId);
    } catch (RuntimeException ex) {
      logger.warn(
          "Failed to record public-production membership creation for tenant {} account {} world {} realm {} requestId {}",
          tenantId,
          accountId,
          worldSlug,
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
    Account account = requireAccount(accountId);
    Profile profile =
        profileRepository.findByAccountIdAndTenantId(accountId, tenantId).orElse(null);
    if (!accountTenantMembershipRepository.existsByAccountIdAndTenantId(accountId, tenantId)
        && profile == null) {
      throw new IllegalArgumentException("Tenant data not found");
    }
    return new TenantDataExportDto(
        tenantId,
        accountMapper.toDto(account),
        profile != null ? profileMapper.toDto(profile) : null);
  }

  @Override
  @Transactional
  @Timed(value = "account.delete")
  public void deleteAccount(Long accountId) {
    Account account = requireAccount(accountId);
    List<net.firedevops.firemud.accountservice.entity.Subscription> subscriptions =
        subscriptionRepository.findByAccountId(accountId);
    Optional<net.firedevops.firemud.accountservice.entity.Subscription> blockingSubscription =
        subscriptions.stream().filter(this::isNonterminalSubscription).findFirst();
    if (blockingSubscription.isPresent()) {
      throw new AccountLifecycleException(
          "ACCOUNT_DELETE_ACTIVE_BILLING_OWNER",
          "Account has nonterminal tenant subscriptions; cancel or end subscriptions first");
    }
    emailVerificationTokenRepository.deleteByAccountId(accountId);
    passwordResetTokenRepository.deleteByAccountId(accountId);
    accountRealmAccessGrantRepository.deleteByAccountId(accountId);
    externalAccountRepository.deleteByAccountId(accountId);
    paymentTransactionRepository.deleteByAccountId(accountId);
    subscriptionRepository.deleteByAccountId(accountId);
    profileRepository.deleteByAccountId(accountId);
    accountTenantMembershipRepository.deleteByAccountId(accountId);
    accountRepository.delete(account);
  }

  private boolean isNonterminalSubscription(
      net.firedevops.firemud.accountservice.entity.Subscription subscription) {
    String status =
        subscription.getStatus() == null
            ? ""
            : subscription.getStatus().trim().toLowerCase(java.util.Locale.ROOT);
    boolean terminalStatus =
        switch (status) {
          case "canceled", "cancelled", "ended", "expired" -> true;
          default -> false;
        };
    return !terminalStatus || subscription.getEndedAt() == null;
  }

  @Override
  @Transactional
  @Timed(value = "account.request_password_reset")
  public void requestPasswordReset(PasswordResetRequest request) {
    net.firedevops.firemud.accountservice.entity.Account account =
        accountRepository
            .findByEmail(request.email())
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
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
  public void requestEmailVerification(Long accountId) {
    Account account = requireAccount(accountId);
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
    Account account =
        accountRepository
            .findByEmail(request.email())
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
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

  private PublicProductionMembershipResult replayedPublicProductionMembershipResult(
      PublicProductionMembershipResult result) {
    return new PublicProductionMembershipResult(
        result.accountId(),
        result.tenantId(),
        result.worldSlug(),
        result.realmSlug(),
        result.membershipVersion(),
        result.created(),
        result.requestId(),
        result.evaluatedAt(),
        true);
  }

  private RuntimeRealmTarget requirePublicProductionRealm(
      Long tenantId, String worldSlug, String realmSlug) {
    Optional<RuntimeRealmTarget> realm;
    try {
      realm =
          readRuntimeRealmTarget(
              gameSessionClient.getAdmissionPointer(tenantId, worldSlug, realmSlug));
    } catch (IllegalStateException ex) {
      throw new AuthenticationException(
          "ADMISSION_POINTER_UNAVAILABLE", "Selected gameplay realm is not admissible", ex);
    }
    if (realm.isEmpty()
        || realm.get().tenantId() != tenantId
        || !java.util.Objects.equals(worldSlug, realm.get().worldSlug())
        || !java.util.Objects.equals(realmSlug, realm.get().realmSlug())) {
      throw new AuthenticationException(
          "ADMISSION_POINTER_UNAVAILABLE", "Selected gameplay realm is not admissible");
    }
    if (!realm.get().publicProductionRealm()) {
      throw new AuthenticationException(
          "PUBLIC_PRODUCTION_MEMBERSHIP_NOT_ALLOWED",
          "Public membership creation is not allowed for this realm");
    }
    return realm.orElseThrow();
  }

  private Optional<RuntimeRealmTarget> readRuntimeRealmTarget(
      net.firedevops.firemud.gamesession.v1.GameplayRealm realm) {
    Long tenantId = parseLong(realm.getTenantId());
    Long gameInstanceId = parseLong(realm.getGameInstanceId());
    if (tenantId == null
        || gameInstanceId == null
        || realm.getPointerVersion() <= 0
        || !StringUtils.hasText(realm.getWorldSlug())
        || !StringUtils.hasText(realm.getRealmSlug())) {
      return Optional.empty();
    }
    return Optional.of(
        new RuntimeRealmTarget(
            tenantId,
            gameInstanceId,
            realm.getWorldSlug(),
            realm.getRealmSlug(),
            realm.getPointerVersion(),
            realm.getVisible(),
            realm.getPublicProductionRealm(),
            realm.getStateScope(),
            realm.getCharacterCreationPolicy(),
            realm.getRequiresCharacterSelection(),
            realm.getDisplayName()));
  }

  private Optional<RuntimeRealmTarget> readRuntimeRealmTarget(
      net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer realm) {
    Long tenantId = parseLong(realm.getTenantId());
    Long gameInstanceId = parseLong(realm.getGameInstanceId());
    if (tenantId == null
        || gameInstanceId == null
        || realm.getPointerVersion() <= 0
        || !StringUtils.hasText(realm.getWorldSlug())
        || !StringUtils.hasText(realm.getRealmSlug())) {
      return Optional.empty();
    }
    return Optional.of(
        new RuntimeRealmTarget(
            tenantId,
            gameInstanceId,
            realm.getWorldSlug(),
            realm.getRealmSlug(),
            realm.getPointerVersion(),
            realm.getVisible(),
            realm.getPublicProductionRealm(),
            realm.getStateScope(),
            realm.getCharacterCreationPolicy(),
            realm.getRequiresCharacterSelection(),
            realm.getRealmDisplayName()));
  }

  private PlayableStateScope toPlayableStateScope(RuntimeRealmTarget realm) {
    return switch (realm.stateScope()) {
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
    };
  }

  private Long parseLong(Object value) {
    try {
      return JwtClaims.requireLong(value, "claim value", false);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private record ConnectScopeContext(
      long accountId,
      long tenantId,
      String worldSlug,
      String realmSlug,
      long gameInstanceId,
      long pointerVersion,
      Instant connectScopeExpiresAt) {}

  private record RuntimeRealmTarget(
      long tenantId,
      long gameInstanceId,
      String worldSlug,
      String realmSlug,
      long pointerVersion,
      boolean visible,
      boolean publicProductionRealm,
      String stateScope,
      String characterCreationPolicy,
      boolean requiresCharacterSelection,
      String displayName) {}
}
