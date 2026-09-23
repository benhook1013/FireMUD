package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.dto.UpdateAccountLoginAuthModesRequest;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthMode;
import net.firedevops.firemud.accountservice.entity.AccountRealmAccessGrant;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.entity.EmailVerificationToken;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.entity.Subscription;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.mapper.ProfileMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.accountservice.repository.ExternalAccountRepository;
import net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import net.firedevops.firemud.accountservice.repository.SubscriptionRepository;
import net.firedevops.firemud.accountservice.service.EmailService;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.accountservice.service.exception.AccountAlreadyExistsException;
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.ReloadableJwtUtil;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AccountServiceImplTest {
  private static final String JWT_SECRET = "mysecretkey123456789012345678901";
  @Mock private AccountRepository accountRepository;
  @Mock private AccountAuditOutboxRepository accountAuditOutboxRepository;
  @Mock private AccountConnectScopeRepository accountConnectScopeRepository;
  @Mock private AccountJoinOperationRepository accountJoinOperationRepository;
  @Mock private AccountEmailLoginChallengeRepository accountEmailLoginChallengeRepository;
  @Mock private AccountRealmAccessGrantRepository accountRealmAccessGrantRepository;
  @Mock private AccountTenantMembershipRepository accountTenantMembershipRepository;
  @Mock private ProfileRepository profileRepository;
  @Mock private ProfileMapper profileMapper;
  @Mock private NotificationService notificationService;
  @Mock private EmailService emailService;
  @Mock private MailProperties mailProperties;
  private final AccountTokenProperties tokenProperties = new AccountTokenProperties();
  private final JwtAuthProperties jwtAuthProperties = new JwtAuthProperties();
  @Mock private GameSessionClient gameSessionClient;
  @Mock private EntityManagementClient entityManagementClient;
  @Mock private SessionService sessionService;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private SubscriptionRepository subscriptionRepository;
  @Mock private ExternalAccountRepository externalAccountRepository;

  @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;

  @Mock
  private net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository
      passwordResetTokenRepository;

  private AccountServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    Subscription explicitActiveEntitlement = new Subscription();
    explicitActiveEntitlement.setId(1L);
    explicitActiveEntitlement.setTenantId(7L);
    explicitActiveEntitlement.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L))
        .thenReturn(java.util.List.of(explicitActiveEntitlement));
    Subscription explicitActiveEntitlementForSecondTenant = new Subscription();
    explicitActiveEntitlementForSecondTenant.setId(2L);
    explicitActiveEntitlementForSecondTenant.setTenantId(8L);
    explicitActiveEntitlementForSecondTenant.setStatus("active");
    when(subscriptionRepository.findByTenantId(8L))
        .thenReturn(java.util.List.of(explicitActiveEntitlementForSecondTenant));
    AccountMapper mapper = Mappers.getMapper(AccountMapper.class);
    JwtUtil jwtUtil = new ReloadableJwtUtil(JWT_SECRET, 3600000L);
    jwtAuthProperties.setJwtSecret(JWT_SECRET);
    tokenProperties.setPlayerBootstrapExpirationMs(300000L);
    tokenProperties.setConnectScopeExpirationMs(120000L);
    tokenProperties.setConnectTokenExpirationMs(30000L);
    tokenProperties.setSessionExpirationMs(3600000L);
    when(gameSessionClient.listGameplayWorlds())
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayWorld.newBuilder()
                    .setWorldSlug("demo")
                    .setDisplayName("Demo World")
                    .build()));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setDisplayName("Live Realm")
                    .setTenantId("7")
                    .setGameInstanceId("44")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("44")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());
    when(sessionService.getConnectTokenReplay(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());
    when(mailProperties.getResetUrl()).thenReturn("http://reset/%s");
    when(mailProperties.getVerificationUrl()).thenReturn("http://verify/%s");
    service =
        new AccountServiceImpl(
            accountRepository,
            accountAuditOutboxRepository,
            accountConnectScopeRepository,
            accountJoinOperationRepository,
            accountEmailLoginChallengeRepository,
            accountRealmAccessGrantRepository,
            accountTenantMembershipRepository,
            mapper,
            profileRepository,
            profileMapper,
            paymentTransactionRepository,
            subscriptionRepository,
            externalAccountRepository,
            passwordResetTokenRepository,
            emailVerificationTokenRepository,
            notificationService,
            emailService,
            mailProperties,
            tokenProperties,
            jwtAuthProperties,
            gameSessionClient,
            entityManagementClient,
            jwtUtil,
            sessionService);
  }

  @Test
  void createAccountPersistsOnlyGlobalIdentity() {
    CreateAccountRequest request =
        new CreateAccountRequest("demo", "  DEMO@example.com ", "password");
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              saved.setId(1L);
              return saved;
            });

    AccountDto dto = service.createAccount(request);

    assertEquals(1L, dto.id());
    assertEquals("demo", dto.username());
    org.mockito.ArgumentCaptor<Account> accountCaptor =
        org.mockito.ArgumentCaptor.forClass(Account.class);
    org.mockito.Mockito.verify(accountRepository).save(accountCaptor.capture());
    assertEquals("demo@example.com", accountCaptor.getValue().getEmail());
    org.mockito.Mockito.verify(accountAuditOutboxRepository)
        .append(
            org.mockito.ArgumentMatchers.any(java.util.UUID.class),
            org.mockito.ArgumentMatchers.eq("platform"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("ACCOUNT_REGISTERED"),
            org.mockito.ArgumentMatchers.eq("{\"accountId\":1}"));
    assertEquals(null, accountCaptor.getValue().getRole());
    verifyNoInteractions(profileRepository, accountTenantMembershipRepository);
  }

  @Test
  void createAccountReturnsExplicitConflictForCanonicalIdentityCollision() {
    CreateAccountRequest request =
        new CreateAccountRequest("demo", " DEMO@EXAMPLE.COM ", "password");
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class)))
        .thenThrow(new org.jooq.exception.IntegrityConstraintViolationException("duplicate"));

    AccountAlreadyExistsException exception =
        assertThrows(AccountAlreadyExistsException.class, () -> service.createAccount(request));

    assertEquals("Account already exists", exception.getMessage());
  }

  @Test
  void createAccountMapsSpringDataIntegrityConflict() {
    CreateAccountRequest request = new CreateAccountRequest("demo", "demo@example.com", "password");
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class)))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

    AccountAlreadyExistsException exception =
        assertThrows(AccountAlreadyExistsException.class, () -> service.createAccount(request));

    assertEquals("Account already exists", exception.getMessage());
  }

  @Test
  void joinPublicProductionCreatesMembershipAndAuditOnceAndReplaysExactReceipt() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    AtomicReference<AccountTenantMembership> joinedMembership = new AtomicReference<>();
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenAnswer(invocation -> Optional.ofNullable(joinedMembership.get()));
    when(accountTenantMembershipRepository.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              AccountTenantMembership membership = invocation.getArgument(0);
              membership.setId(701L);
              joinedMembership.set(membership);
              return membership;
            });
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantIdForUpdate(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, "join-attempt-1");

    JoinPublicProductionResult first =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    JoinPublicProductionResult retried =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    ConnectTokenResult onboardingToken =
        service.issueConnectToken(
            bootstrap.bootstrapToken(),
            new ConnectTokenRequest(connectScopeId, "first-play-after-join"));

    assertTrue(first.success());
    assertEquals("JOINED", first.outcomeCode());
    assertEquals(701L, first.membershipId());
    assertEquals(1L, first.membershipVersion());
    assertTrue(retried.success());
    assertTrue(retried.replayed());
    assertNotNull(onboardingToken.connectToken());
    assertEquals(
        first,
        new JoinPublicProductionResult(
            retried.success(),
            retried.outcomeCode(),
            retried.accountId(),
            retried.tenantId(),
            retried.membershipId(),
            retried.membershipVersion(),
            retried.membershipAuthorityGeneration(),
            false));
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.times(1))
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(accountAuditOutboxRepository, org.mockito.Mockito.times(1))
        .append(
            org.mockito.ArgumentMatchers.any(java.util.UUID.class),
            org.mockito.ArgumentMatchers.eq("tenant"),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("ACCOUNT_JOINED_PUBLIC_PRODUCTION"),
            org.mockito.ArgumentMatchers.contains("join-attempt-1"));
    assertEquals("COMMITTED", retainedOperation.get().status());

    AuthenticationException changedDigest =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.joinPublicProduction(
                    bootstrap.bootstrapToken(),
                    new JoinPublicProductionRequest("different-scope", "join-attempt-1")));
    assertEquals("IDEMPOTENCY_CONFLICT", changedDigest.getCode());
  }

  @Test
  void closedPublicJoinRetainsFailureAndCannotCreateMembershipOrAudit() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    Subscription grace = new Subscription();
    grace.setId(22L);
    grace.setTenantId(7L);
    grace.setStatus("grace");
    when(subscriptionRepository.findByTenantIdForUpdate(7L)).thenReturn(java.util.List.of(grace));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, "join-closed-1");

    JoinPublicProductionResult denied =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    JoinPublicProductionResult retry =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertFalse(denied.success());
    assertEquals("PUBLIC_PRODUCTION_ADMISSION_DENIED", denied.outcomeCode());
    assertFalse(retry.success());
    assertTrue(retry.replayed());
    assertEquals(denied.outcomeCode(), retry.outcomeCode());
    assertEquals("FAILED", retainedOperation.get().status());
    grace.setStatus("active");
    grace.setEntitlementVersion(2L);
    AuthenticationException changedPolicy =
        assertThrows(
            AuthenticationException.class,
            () -> service.joinPublicProduction(bootstrap.bootstrapToken(), request));
    assertEquals("IDEMPOTENCY_CONFLICT", changedPolicy.getCode());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(3))
        .findByTenantIdForUpdate(7L);
  }

  @ParameterizedTest
  @CsvSource({"false", "true"})
  void missingOrAmbiguousEntitlementRetainsUnavailableJoinReceipt(boolean ambiguous) {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    when(subscriptionRepository.findByTenantIdForUpdate(7L))
        .thenReturn(
            ambiguous
                ? java.util.List.of(new Subscription(), new Subscription())
                : java.util.List.of());

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, "join-unavailable-1");

    JoinPublicProductionResult first =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    JoinPublicProductionResult retry =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertFalse(first.success());
    assertEquals("ENTITLEMENT_UNAVAILABLE", first.outcomeCode());
    assertFalse(retry.success());
    assertTrue(retry.replayed());
    assertEquals(first.outcomeCode(), retry.outcomeCode());
    assertEquals("FAILED", retainedOperation.get().status());
    assertEquals("UNAVAILABLE", retainedOperation.get().entitlementAuthorityAvailability());
    assertEquals(null, retainedOperation.get().allowPublicJoin());
    assertEquals(null, retainedOperation.get().entitlementVersion());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(2))
        .findByTenantIdForUpdate(7L);
  }

  @Test
  void joinPublicProductionRevalidatesPolicyAtTheMembershipCommitGate() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    Subscription initialPolicy = new Subscription();
    initialPolicy.setId(22L);
    initialPolicy.setTenantId(7L);
    initialPolicy.setStatus("active");
    initialPolicy.setEntitlementVersion(5L);
    Subscription changedPolicy = new Subscription();
    changedPolicy.setId(22L);
    changedPolicy.setTenantId(7L);
    changedPolicy.setStatus("active");
    changedPolicy.setEntitlementVersion(6L);
    when(subscriptionRepository.findByTenantIdForUpdate(7L))
        .thenReturn(java.util.List.of(initialPolicy), java.util.List.of(changedPolicy));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    JoinPublicProductionResult result =
        service.joinPublicProduction(
            bootstrap.bootstrapToken(),
            new JoinPublicProductionRequest(connectScopeId, "join-race-1"));

    assertFalse(result.success());
    assertEquals("ENTITLEMENT_UNAVAILABLE", result.outcomeCode());
    assertEquals("FAILED", retainedOperation.get().status());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(2))
        .findByTenantIdForUpdate(7L);
  }

  @Test
  void joinPublicProductionReturnsConflictWhenGlobalRequestIdClaimIsLost() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    org.mockito.Mockito.when(
            accountJoinOperationRepository.insertPending(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.nullable(Boolean.class),
                org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(false);

    AuthenticationException conflict =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.joinPublicProduction(
                    bootstrap.bootstrapToken(),
                    new JoinPublicProductionRequest(connectScopeId, "join-global-collision-1")));

    assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
  }

  @Test
  void quarantinedLegacyMembershipIsNeverRestoredByPublicJoin() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    AccountTenantMembership quarantined = membership(account, 7L);
    quarantined.setLifecycleState("LEGACY_UNVERIFIED");
    quarantined.setGameplayAdmissionAllowed(false);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(quarantined));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantIdForUpdate(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    JoinPublicProductionResult result =
        service.joinPublicProduction(
            bootstrap.bootstrapToken(),
            new JoinPublicProductionRequest(connectScopeId, "join-legacy-1"));

    assertFalse(result.success());
    assertEquals("MEMBERSHIP_RECONCILIATION_REQUIRED", result.outcomeCode());
    assertEquals("LEGACY_UNVERIFIED", quarantined.getLifecycleState());
    assertFalse(quarantined.isGameplayAdmissionAllowed());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
  }

  private void retainJoinEvidence(
      AtomicReference<VerifiedJoinScope> retainedScope,
      AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation) {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              retainedScope.set(invocation.getArgument(0));
              return null;
            })
        .when(accountConnectScopeRepository)
        .insert(org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class));
    when(accountConnectScopeRepository.find(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(retainedScope.get()));
    when(accountJoinOperationRepository.find(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(retainedOperation.get()));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              String requestId = invocation.getArgument(0);
              VerifiedJoinScope scope = invocation.getArgument(1);
              String callerBinding = invocation.getArgument(2);
              String requestDigest = invocation.getArgument(3);
              String authorityAvailability = invocation.getArgument(4);
              Long entitlementVersion = invocation.getArgument(5);
              Boolean allowPublicJoin = invocation.getArgument(6);
              retainedOperation.set(
                  new AccountJoinOperationRepository.JoinOperation(
                      scope.accountId(),
                      scope.tenantId(),
                      callerBinding,
                      net.firedevops.firemud.accountservice.dto.AccountJoinDigest.tokenHash(
                          scope.connectScopeId()),
                      scope.snapshotDigest(),
                      authorityAvailability,
                      allowPublicJoin,
                      entitlementVersion,
                      1,
                      requestDigest,
                      "PENDING",
                      null,
                      null,
                      null,
                      null));
              return true;
            })
        .when(accountJoinOperationRepository)
        .insertPending(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Boolean.class),
            org.mockito.ArgumentMatchers.anyBoolean());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var pending = retainedOperation.get();
              retainedOperation.set(
                  new AccountJoinOperationRepository.JoinOperation(
                      pending.accountId(),
                      pending.tenantId(),
                      pending.callerBinding(),
                      pending.scopeTokenHash(),
                      pending.connectScopeDigest(),
                      pending.entitlementAuthorityAvailability(),
                      pending.allowPublicJoin(),
                      pending.entitlementVersion(),
                      pending.requestDigestVersion(),
                      pending.requestDigest(),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      invocation.getArgument(3),
                      invocation.getArgument(4),
                      invocation.getArgument(5)));
              return null;
            })
        .when(accountJoinOperationRepository)
        .finish(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Long.class));
  }

  @Test
  void emailLoginOtpRequestIsNeutralForUnknownEmail() {
    when(accountRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

    service.requestEmailLoginOtp("unknown@example.com");

    verifyNoInteractions(emailService, accountEmailLoginChallengeRepository);
  }

  @ParameterizedTest
  @EnumSource(
      value = AccountLifecycleState.class,
      names = {"SECURITY_LOCKED", "DEACTIVATED_PENDING_DELETE", "DELETED"})
  void emailLoginOtpRequestIsNeutralForIneligibleLifecycleState(
      AccountLifecycleState lifecycleState) {
    Account account = new Account();
    account.setId(9L);
    account.setEmail("verified@example.com");
    account.setEmailVerified(true);
    account.setLifecycleState(lifecycleState);
    when(accountRepository.findByEmail("verified@example.com")).thenReturn(Optional.of(account));

    service.requestEmailLoginOtp("verified@example.com");

    verifyNoInteractions(
        accountTenantMembershipRepository, accountEmailLoginChallengeRepository, emailService);
  }

  @Test
  void emailLoginOtpRequestPersistsOnlyHashedChallengeAndSendsSixDigitCode() {
    Account account = new Account();
    account.setId(9L);
    account.setEmail("verified@example.com");
    account.setEmailVerified(true);
    when(accountRepository.findByEmail("verified@example.com")).thenReturn(Optional.of(account));
    when(accountEmailLoginChallengeRepository.findByAccountId(9L)).thenReturn(Optional.empty());
    when(accountEmailLoginChallengeRepository.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge challenge =
                  invocation.getArgument(0);
              challenge.setId(3L);
              return challenge;
            });

    service.requestEmailLoginOtp("verified@example.com");

    org.mockito.ArgumentCaptor<
            net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge>
        challengeCaptor =
            org.mockito.ArgumentCaptor.forClass(
                net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge.class);
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository)
        .save(challengeCaptor.capture());
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).lockAccountChallenge(9L);
    assertEquals(9L, challengeCaptor.getValue().getAccountId());
    assertFalse(challengeCaptor.getValue().getCodeHash().matches("\\d{6}"));
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("verified@example.com"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.matches(".*\\b\\d{6}\\b.*"));
    verifyNoInteractions(accountTenantMembershipRepository);
  }

  @Test
  void emailLoginOtpVerificationAuthenticatesAndConsumesMatchingChallenge() {
    Account account = new Account();
    account.setId(9L);
    account.setEmail("verified@example.com");
    account.setEmailVerified(true);
    account.setRole("player");
    when(accountRepository.findByEmail("verified@example.com")).thenReturn(Optional.of(account));
    when(accountEmailLoginChallengeRepository.findByAccountId(9L)).thenReturn(Optional.empty());
    when(accountEmailLoginChallengeRepository.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.requestEmailLoginOtp("verified@example.com");

    org.mockito.ArgumentCaptor<
            net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge>
        challengeCaptor =
            org.mockito.ArgumentCaptor.forClass(
                net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge.class);
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository)
        .save(challengeCaptor.capture());
    org.mockito.ArgumentCaptor<String> emailBodyCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("verified@example.com"),
            org.mockito.ArgumentMatchers.anyString(),
            emailBodyCaptor.capture());
    Matcher codeMatcher = Pattern.compile("\\b(\\d{6})\\b").matcher(emailBodyCaptor.getValue());
    assertTrue(codeMatcher.find());
    when(accountEmailLoginChallengeRepository.findByAccountId(9L))
        .thenReturn(Optional.of(challengeCaptor.getValue()));

    AuthenticationResult result =
        service.verifyEmailLoginOtp("verified@example.com", codeMatcher.group(1));

    assertEquals(9L, result.accountId());
    assertNotNull(result.authToken());
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository)
        .delete(challengeCaptor.getValue());
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(
            org.mockito.ArgumentMatchers.eq(9L),
            org.mockito.ArgumentMatchers.eq(result.authToken()),
            org.mockito.ArgumentMatchers.eq(jwtAuthProperties.getJwtExpirationMs()));
    verifyNoInteractions(accountTenantMembershipRepository);
  }

  @Test
  void authenticateUsesMatchingEmailLoginOtpBeforePasswordFallback() {
    Account account = new Account();
    account.setId(9L);
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge challenge =
        new net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge();
    challenge.setId(3L);
    challenge.setAccountId(9L);
    challenge.setCodeHash(hash("123456"));
    challenge.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountEmailLoginChallengeRepository.findByAccountId(9L))
        .thenReturn(Optional.of(challenge));

    AuthenticationResult result = service.authenticateForGameplay("demo@example.com", "123456");

    assertEquals(9L, result.accountId());
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).delete(challenge);
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(9L, result.authToken(), jwtAuthProperties.getJwtExpirationMs());
    verifyNoInteractions(accountTenantMembershipRepository);
  }

  @Test
  void authenticateFallsBackToPasswordWithoutBurningUnmatchedEmailLoginOtp() {
    Account account = new Account();
    account.setId(9L);
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge challenge =
        new net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge();
    challenge.setId(3L);
    challenge.setAccountId(9L);
    challenge.setCodeHash(hash("123456"));
    challenge.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountEmailLoginChallengeRepository.findByAccountId(9L))
        .thenReturn(Optional.of(challenge));

    AuthenticationResult result = service.authenticateForGameplay("demo@example.com", "password");

    assertEquals(9L, result.accountId());
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository, org.mockito.Mockito.never())
        .delete(challenge);
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository, org.mockito.Mockito.never())
        .save(challenge);
  }

  @Test
  void authenticateCountsFailedMixedModeSecretAgainstActiveEmailLoginOtp() {
    Account account = new Account();
    account.setId(9L);
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge challenge =
        new net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge();
    challenge.setId(3L);
    challenge.setAccountId(9L);
    challenge.setCodeHash(hash("123456"));
    challenge.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountEmailLoginChallengeRepository.findByAccountId(9L))
        .thenReturn(Optional.of(challenge));
    when(accountEmailLoginChallengeRepository.save(challenge)).thenReturn(challenge);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.authenticateForGameplay("demo@example.com", "wrong"));

    assertEquals(AuthenticationErrorCodes.INVALID_CREDENTIALS, exception.getCode());
    assertEquals(1, challenge.getInvalidAttemptCount());
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).save(challenge);
    verifyNoInteractions(sessionService);
  }

  @Test
  void passwordOnlyAccountsDoNotIssueEmailLoginChallenges() {
    Account account = new Account();
    account.setId(9L);
    account.setEmail("verified@example.com");
    account.setEmailVerified(true);
    account.setLoginAuthModes("PASSWORD");
    when(accountRepository.findByEmail("verified@example.com")).thenReturn(Optional.of(account));

    service.requestEmailLoginOtp("verified@example.com");

    verifyNoInteractions(
        accountTenantMembershipRepository, accountEmailLoginChallengeRepository, emailService);
  }

  @Test
  void emailLoginOtpVerificationFailsClosedForUnknownEmail() {
    when(accountRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.verifyEmailLoginOtp("unknown@example.com", "123456"));

    assertEquals(AuthenticationErrorCodes.INVALID_CREDENTIALS, exception.getCode());
    verifyNoInteractions(accountEmailLoginChallengeRepository, sessionService);
  }

  @Test
  void emailLoginOtpVerificationLocksChallengeBeforeConsumingIt() {
    Account account = new Account();
    account.setId(9L);
    account.setEmail("verified@example.com");
    account.setRole("player");
    net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge challenge =
        new net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge();
    challenge.setId(5L);
    challenge.setAccountId(9L);
    challenge.setCodeHash(hash("123456"));
    challenge.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(10));
    challenge.setInvalidAttemptCount(0);
    when(accountRepository.findByEmail("verified@example.com")).thenReturn(Optional.of(account));
    when(accountEmailLoginChallengeRepository.findByAccountId(9L))
        .thenReturn(Optional.of(challenge));

    AuthenticationResult result = service.verifyEmailLoginOtp("verified@example.com", "123456");

    assertEquals(9L, result.accountId());
    var claims = new JwtUtil(JWT_SECRET, 3600000L).parseToken(result.authToken()).getPayload();
    assertEquals("account-service", claims.getAudience().iterator().next());
    assertEquals(9L, claims.get("accountId", Long.class));
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(accountEmailLoginChallengeRepository);
    inOrder.verify(accountEmailLoginChallengeRepository).lockAccountChallenge(9L);
    inOrder.verify(accountEmailLoginChallengeRepository).findByAccountId(9L);
    inOrder.verify(accountEmailLoginChallengeRepository).delete(challenge);
  }

  @Test
  void authenticateReturnsControlUiTokenWithoutGameplayMembership() {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));

    AuthenticationResult result = service.authenticate("demo", "password");

    assertNotNull(result.authToken());
    assertEquals(1L, result.accountId());
    var claims = new JwtUtil(JWT_SECRET, 3600000L).parseToken(result.authToken()).getPayload();
    assertEquals("control-ui", claims.getAudience().iterator().next());
    assertEquals(1L, claims.get("accountId", Long.class));
    assertEquals(java.util.List.of(), claims.get("globalRoles"));
    assertNotNull(claims.get("jti"));
    assertFalse(claims.containsKey("tenantId"));
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(1L, result.authToken(), 3600000L);
    verifyNoInteractions(accountTenantMembershipRepository);
  }

  @ParameterizedTest
  @CsvSource({
    "SECURITY_LOCKED, AUTH_ACCOUNT_LOCKED",
    "DEACTIVATED_PENDING_DELETE, AUTH_INVALID_CREDENTIALS",
    "DELETED, AUTH_INVALID_CREDENTIALS"
  })
  void authenticateRejectsIneligibleLifecycleState(
      AccountLifecycleState lifecycleState, String expectedCode) {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    account.setLifecycleState(lifecycleState);
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));

    AuthenticationException exception =
        assertThrows(AuthenticationException.class, () -> service.authenticate("demo", "password"));

    assertEquals(expectedCode, exception.getCode());
    verifyNoInteractions(sessionService);
  }

  @Test
  void authenticateForGameplayReturnsTokenWhenPasswordMatches() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    jwtAuthProperties.setJwtSecret(null);

    AuthenticationResult result = service.authenticateForGameplay("demo@example.com", "password");

    assertNotNull(result.authToken());
    assertEquals(1L, result.accountId());
    var claims = new JwtUtil(JWT_SECRET, 3600000L).parseToken(result.authToken()).getPayload();
    assertEquals("account-service", claims.getAudience().iterator().next());
    assertEquals(1L, claims.get("accountId", Long.class));
    assertEquals(java.util.List.of(), claims.get("globalRoles"));
    assertNotNull(claims.get("jti"));
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(1L, result.authToken(), jwtAuthProperties.getJwtExpirationMs());
    verifyNoInteractions(accountTenantMembershipRepository);
  }

  @Test
  void issuePlayerBootstrapReturnsShortLivedToken() {
    Account account = new Account();
    account.setId(7L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    account.setLoginAuthModes("PASSWORD");
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    jwtAuthProperties.setJwtSecret(null);

    PlayerBootstrapResult result = service.issuePlayerBootstrap("demo", "password");

    assertEquals(7L, result.accountId());
    assertNotNull(result.bootstrapToken());
    assertNotNull(result.issuedAt());
    assertNotNull(result.expiresAt());
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(result.bootstrapToken()),
            org.mockito.ArgumentMatchers.eq(300000L));
    var claims = new JwtUtil(JWT_SECRET, 300000L).parseToken(result.bootstrapToken()).getPayload();
    assertEquals("player-bootstrap", claims.getAudience().iterator().next());
    assertFalse(claims.containsKey("tenantId"));
    assertEquals(300000L, claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    verifyNoInteractions(accountEmailLoginChallengeRepository);
  }

  @Test
  void issuePlayerBootstrapPrefersEmailLookupOverCollidingUsername() {
    Account emailAccount = new Account();
    emailAccount.setId(7L);
    emailAccount.setUsername("email-owner");
    emailAccount.setEmail("player@example.com");
    emailAccount.setPasswordHash(hash("password"));
    emailAccount.setLoginAuthModes("PASSWORD");
    when(accountRepository.findByEmail("player@example.com")).thenReturn(Optional.of(emailAccount));

    PlayerBootstrapResult result =
        service.issuePlayerBootstrap("  PLAYER@EXAMPLE.COM ", "password");

    assertEquals(7L, result.accountId());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .findByUsername(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void issuePlayerBootstrapAcceptsAndConsumesEmailLoginOtp() {
    Account account = new Account();
    account.setId(7L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setLoginAuthModes("EMAIL_OTP");
    net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge challenge =
        new net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge();
    challenge.setAccountId(7L);
    challenge.setCodeHash(hash("123456"));
    challenge.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountEmailLoginChallengeRepository.findByAccountId(7L))
        .thenReturn(Optional.of(challenge));

    PlayerBootstrapResult result = service.issuePlayerBootstrap("demo", "123456");

    assertEquals(7L, result.accountId());
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(accountEmailLoginChallengeRepository);
    inOrder.verify(accountEmailLoginChallengeRepository).lockAccountChallenge(7L);
    inOrder.verify(accountEmailLoginChallengeRepository).findByAccountId(7L);
    inOrder.verify(accountEmailLoginChallengeRepository).delete(challenge);
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(7L, result.bootstrapToken(), 300000L);
  }

  @Test
  void listBootstrapWorldsRejectsInactiveAccountBootstrapToken() {
    String bootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken("11", Map.of("aud", "player-bootstrap", "accountId", "11"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class, () -> service.listBootstrapWorlds(bootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
    assertEquals("Bootstrap token expired", ex.getMessage());
    org.mockito.Mockito.verify(sessionService).isAccountSessionActive(11L, bootstrapToken);
  }

  @Test
  void listBootstrapWorldsRejectsMalformedBootstrapTokenAccountClaim() {
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken("11", Map.of("aud", "player-bootstrap", "accountId", "abc"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
  }

  @Test
  void listBootstrapWorldsRejectsBootstrapTokenAccountSubjectMismatch() {
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken("12", Map.of("aud", "player-bootstrap", "accountId", "11"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
  }

  @Test
  void listBootstrapWorldsRejectsNonPositiveBootstrapTokenClaims() {
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken("11", Map.of("aud", "player-bootstrap", "accountId", "0"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
  }

  @Test
  void listBootstrapWorldsRejectsBootstrapTokenWithoutAudience() {
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L).generateToken("11", Map.of("accountId", "11"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
  }

  @Test
  void listBootstrapWorldsDoesNotMaskUnexpectedJwtParserRuntimeFailure() {
    JwtUtil jwtUtil = org.mockito.Mockito.mock(JwtUtil.class);
    when(jwtUtil.parseToken("boom-token")).thenThrow(new IllegalStateException("boom"));
    service =
        new AccountServiceImpl(
            accountRepository,
            accountAuditOutboxRepository,
            accountConnectScopeRepository,
            accountJoinOperationRepository,
            accountEmailLoginChallengeRepository,
            accountRealmAccessGrantRepository,
            accountTenantMembershipRepository,
            Mappers.getMapper(AccountMapper.class),
            profileRepository,
            profileMapper,
            paymentTransactionRepository,
            subscriptionRepository,
            externalAccountRepository,
            passwordResetTokenRepository,
            emailVerificationTokenRepository,
            notificationService,
            emailService,
            mailProperties,
            tokenProperties,
            jwtAuthProperties,
            gameSessionClient,
            entityManagementClient,
            jwtUtil,
            sessionService);

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> service.listBootstrapWorlds("boom-token"));

    assertEquals("boom", ex.getMessage());
  }

  @Test
  void listBootstrapWorldsRejectsWorldWithMalformedRuntimeRealmRow() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("broken")
                    .setDisplayName("Broken Realm")
                    .setTenantId("bad")
                    .setGameInstanceId("44")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.listBootstrapWorlds(bootstrap.bootstrapToken()));
  }

  @Test
  void issueConnectTokenRejectsMalformedConnectScopeId() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(), new ConnectTokenRequest("bad", "req-err")));

    assertEquals("CONNECT_SCOPE_INVALID", ex.getCode());
  }

  @Test
  void issueConnectTokenRejectsBlankConnectScopeId() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(), new ConnectTokenRequest("   ", "req-blank-scope")));

    assertEquals("CONNECT_SCOPE_INVALID", ex.getCode());
  }

  @Test
  void issueConnectTokenRejectsNonPositiveConnectScopeClaims() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String malformedConnectScopeId =
        new JwtUtil(JWT_SECRET, 120000L)
            .generateToken(
                "11",
                Map.of(
                    "aud",
                    "bootstrap-connect-scope",
                    "accountId",
                    "11",
                    "tenantId",
                    "7",
                    "worldSlug",
                    "demo",
                    "realmSlug",
                    "production",
                    "gameInstanceId",
                    "0",
                    "pointerVersion",
                    "17",
                    "connectScopeExpiresAt",
                    java.time.Instant.now().plusSeconds(3600).toString(),
                    "jti",
                    "invalid"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(malformedConnectScopeId, "req-err2")));

    assertEquals("CONNECT_SCOPE_INVALID", ex.getCode());
  }

  @Test
  void issueConnectTokenRejectsMalformedConnectScopeClaims() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String malformedConnectScopeId =
        new JwtUtil(JWT_SECRET, 120000L)
            .generateToken(
                "11",
                Map.of(
                    "aud",
                    "bootstrap-connect-scope",
                    "accountId",
                    "abc",
                    "tenantId",
                    "7",
                    "worldSlug",
                    "demo",
                    "realmSlug",
                    "production",
                    "gameInstanceId",
                    "44",
                    "pointerVersion",
                    "17",
                    "connectScopeExpiresAt",
                    java.time.Instant.now().plusSeconds(3600).toString(),
                    "jti",
                    "invalid"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(malformedConnectScopeId, "req-err3")));

    assertEquals("CONNECT_SCOPE_INVALID", ex.getCode());
  }

  @Test
  void issueConnectTokenRejectsConnectScopeAccountSubjectMismatch() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String malformedConnectScopeId =
        new JwtUtil(JWT_SECRET, 120000L)
            .generateToken(
                "12",
                Map.of(
                    "aud",
                    "bootstrap-connect-scope",
                    "accountId",
                    "11",
                    "tenantId",
                    "7",
                    "worldSlug",
                    "demo",
                    "realmSlug",
                    "production",
                    "gameInstanceId",
                    "44",
                    "pointerVersion",
                    "17",
                    "connectScopeExpiresAt",
                    java.time.Instant.now().plusSeconds(3600).toString(),
                    "jti",
                    "invalid"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(malformedConnectScopeId, "req-subject-mismatch")));

    assertEquals("CONNECT_SCOPE_INVALID", ex.getCode());
  }

  @Test
  void issueConnectTokenRejectsBlankWorldSlugConnectScopeClaims() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String malformedConnectScopeId =
        new JwtUtil(JWT_SECRET, 120000L)
            .generateToken(
                "11",
                Map.of(
                    "aud",
                    "bootstrap-connect-scope",
                    "accountId",
                    "11",
                    "tenantId",
                    "7",
                    "worldSlug",
                    " ",
                    "realmSlug",
                    "production",
                    "gameInstanceId",
                    "44",
                    "pointerVersion",
                    "17",
                    "connectScopeExpiresAt",
                    java.time.Instant.now().plusSeconds(3600).toString(),
                    "jti",
                    "invalid"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(malformedConnectScopeId, "req-err4")));

    assertEquals("CONNECT_SCOPE_INVALID", ex.getCode());
  }

  @Test
  void issueConnectTokenRejectsZeroPointerVersionInConnectScopeClaims() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String malformedConnectScopeId =
        new JwtUtil(JWT_SECRET, 120000L)
            .generateToken(
                "11",
                Map.of(
                    "aud",
                    "bootstrap-connect-scope",
                    "accountId",
                    "11",
                    "tenantId",
                    "7",
                    "worldSlug",
                    "demo",
                    "realmSlug",
                    "production",
                    "gameInstanceId",
                    "44",
                    "pointerVersion",
                    "0",
                    "connectScopeExpiresAt",
                    java.time.Instant.now().plusSeconds(3600).toString(),
                    "jti",
                    "invalid"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(malformedConnectScopeId, "req-err5")));

    assertEquals("CONNECT_SCOPE_INVALID", ex.getCode());
  }

  @Test
  void authenticateForGameplayUsesNormalizedEmailLookup() {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));

    AuthenticationResult result =
        service.authenticateForGameplay("  DEMO@example.com ", "password");

    assertNotNull(result.authToken());
    assertEquals(1L, result.accountId());
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(1L, result.authToken(), jwtAuthProperties.getJwtExpirationMs());
    verifyNoInteractions(accountTenantMembershipRepository);
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .findByUsername(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void authenticateThrowsWhenInvalid() {
    when(accountRepository.findByEmail("demo")).thenReturn(Optional.empty());
    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class, () -> service.authenticateForGameplay("demo", "bad"));
    assertEquals(AuthenticationErrorCodes.INVALID_CREDENTIALS, exception.getCode());
  }

  @Test
  void authenticateDoesNotUseGlobalAccountFallback() {
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.empty());

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.authenticateForGameplay("demo@example.com", "password"));

    assertEquals(AuthenticationErrorCodes.INVALID_CREDENTIALS, exception.getCode());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .findByUsername(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void authenticateAllowsGlobalIdentityWithoutTenantMembership() {
    Account account = new Account();
    account.setId(7L);
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    AuthenticationResult result = service.authenticateForGameplay("demo@example.com", "password");

    assertEquals(7L, result.accountId());
    assertNotNull(result.authToken());
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(7L, result.authToken(), jwtAuthProperties.getJwtExpirationMs());
    verifyNoInteractions(accountTenantMembershipRepository);
  }

  @Test
  void getTenantMembershipForRuntimeReturnsAdmissionAllowedForExistingAccount() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));

    var dto = service.getTenantMembershipForRuntime(11L, 7L, "req-1");

    assertEquals(11L, dto.accountId());
    assertEquals(7L, dto.tenantId());
    assertTrue(dto.membershipExists());
    assertTrue(dto.gameplayAdmissionAllowed());
    assertEquals(1L, dto.membershipVersion());
    assertEquals("ACTIVE", dto.membershipLifecycleState());
    assertEquals(1L, dto.membershipAuthorityGeneration());
    assertNotNull(dto.evaluatedAt());
  }

  @Test
  void getTenantMembershipForRuntimeRejectsMissingAccount() {
    when(accountRepository.findById(11L)).thenReturn(Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.getTenantMembershipForRuntime(11L, 7L, "req-1"));
  }

  @Test
  void getTenantMembershipForRuntimeRejectsCrossTenantAccount() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));

    var dto = service.getTenantMembershipForRuntime(11L, 7L, "req-1");

    assertEquals(11L, dto.accountId());
    assertEquals(7L, dto.tenantId());
    assertTrue(!dto.membershipExists());
    assertTrue(!dto.gameplayAdmissionAllowed());
  }

  @Test
  void getTenantEntitlementsForRuntimeUsesCurrentSubscriptions() {
    Subscription active = new Subscription();
    active.setId(31L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    var dto = service.getTenantEntitlementsForRuntime(7L, "req-2");

    assertEquals(7L, dto.tenantId());
    assertTrue(dto.gameplayAvailable());
    assertTrue(dto.allowPublicJoin());
    assertEquals(1L, dto.entitlementVersion());
    assertEquals(1L, dto.tenantBillingSequence());
    assertNotNull(dto.evaluatedAt());
  }

  @Test
  void getTenantEntitlementsForRuntimeTreatsMissingSubscriptionAsUnavailable() {
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of());

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.getTenantEntitlementsForRuntime(7L, "req-missing-entitlement"));

    assertEquals("ENTITLEMENT_UNAVAILABLE", exception.getCode());
  }

  @Test
  void getTenantEntitlementsForRuntimeTreatsMixedSubscriptionRowsAsUnavailable() {
    Subscription active = new Subscription();
    active.setId(31L);
    active.setTenantId(7L);
    active.setStatus("active");
    Subscription canceled = new Subscription();
    canceled.setId(32L);
    canceled.setTenantId(7L);
    canceled.setStatus("canceled");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active, canceled));

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.getTenantEntitlementsForRuntime(7L, "req-ambiguous-entitlement"));

    assertEquals("ENTITLEMENT_UNAVAILABLE", exception.getCode());
  }

  @Test
  void issueConnectTokenReturnsShortLivedConnectToken() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    ConnectTokenResult result =
        service.issueConnectToken(
            bootstrap.bootstrapToken(), new ConnectTokenRequest(connectScopeId, "req-3"));

    assertEquals(11L, result.accountId());
    assertEquals(7L, result.tenantId());
    assertEquals(44L, result.gameInstanceId());
    assertEquals(connectScopeId, result.connectScopeId());
    assertNotNull(result.connectToken());
    assertNotNull(result.jti());
    assertEquals("req-3", result.requestId());
    assertNotNull(result.issuedAt());
    assertNotNull(result.expiresAt());
    assertTrue(!result.replayed());
    var connectTokenClaims =
        new JwtUtil("mysecretkey123456789012345678901", 30000L)
            .parseToken(result.connectToken())
            .getPayload();
    assertEquals("gameplay-connect", connectTokenClaims.getAudience().iterator().next());
    assertEquals(17L, ((Number) connectTokenClaims.get("pointerVersion")).longValue());
    org.mockito.Mockito.verify(sessionService)
        .storeSession(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(30000L));
  }

  @Test
  void issueConnectTokenRetriesAfterEntitlementAuthorityRecoversWithoutCachingFailure() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L))
        .thenReturn(java.util.List.of(active), java.util.List.of(), java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    ConnectTokenRequest request = new ConnectTokenRequest(connectScopeId, "req-entitlement-retry");

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () -> service.issueConnectToken(bootstrap.bootstrapToken(), request));
    assertEquals("ENTITLEMENT_UNAVAILABLE", unavailable.getCode());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("req-entitlement-retry"),
            org.mockito.ArgumentMatchers.argThat(
                replay ->
                    !replay.success() && "ENTITLEMENT_UNAVAILABLE".equals(replay.errorCode())),
            org.mockito.ArgumentMatchers.anyLong());

    ConnectTokenResult retried = service.issueConnectToken(bootstrap.bootstrapToken(), request);

    assertEquals("req-entitlement-retry", retried.requestId());
    assertNotNull(retried.connectToken());
    assertFalse(retried.replayed());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.times(1))
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.eq(connectScopeId),
            org.mockito.ArgumentMatchers.eq("req-entitlement-retry"),
            org.mockito.ArgumentMatchers.argThat(replay -> replay.success()),
            org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void issueConnectTokenRejectsStaleAdmissionPointerAfterBootstrapDiscovery() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("99")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(18L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(), new ConnectTokenRequest(connectScopeId, "req-4")));

    // The catalog and pointer disagree before Account has a valid current pair to compare.
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", ex.getCode());
    assertEquals(
        "Selected gameplay realm is no longer admissible; rerun realm discovery before retrying gameplay entry",
        ex.getMessage());
  }

  @Test
  void issueConnectTokenRejectsWorldMismatchAfterBootstrapDiscovery() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("sandbox")
                .setWorldDisplayName("Builder Sandbox")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("44")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-world-mismatch")));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", ex.getCode());
  }

  @Test
  void issueConnectTokenResolvesAdmissionRoutingBeforeEntitlementEvaluation() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    org.mockito.Mockito.clearInvocations(accountTenantMembershipRepository, subscriptionRepository);

    Subscription canceled = new Subscription();
    canceled.setId(23L);
    canceled.setTenantId(7L);
    canceled.setStatus("canceled");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(canceled));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setDisplayName("Live Realm")
                    .setTenantId("")
                    .setGameInstanceId("44")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-routing-first")));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .findByAccountIdAndTenantId(11L, 7L);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.never())
        .findByTenantId(7L);
  }

  @Test
  void issueConnectTokenReplaysSameTokenForSameRequestIdAfterLaterPointerCutover() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    ConnectTokenResult firstResult =
        service.issueConnectToken(
            bootstrap.bootstrapToken(), new ConnectTokenRequest(connectScopeId, "req-replay-1"));

    when(sessionService.getConnectTokenReplay(7L, 11L, connectScopeId, "req-replay-1"))
        .thenReturn(Optional.of(new SessionService.ConnectTokenReplay(true, firstResult, "", "")));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("99")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(18L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());

    ConnectTokenResult replayed =
        service.issueConnectToken(
            bootstrap.bootstrapToken(), new ConnectTokenRequest(connectScopeId, "req-replay-1"));

    assertEquals(firstResult.connectToken(), replayed.connectToken());
    assertEquals(firstResult.issuedAt(), replayed.issuedAt());
    assertEquals(firstResult.expiresAt(), replayed.expiresAt());
    assertEquals(firstResult.requestId(), replayed.requestId());
    assertTrue(replayed.replayed());
  }

  @Test
  void issueConnectTokenRequiresExplicitPublicProductionMembership() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));
    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-join-2")));

    assertEquals("JOIN_REQUIRED", exception.getCode());
    assertEquals(
        "Join the selected world before requesting a connect token", exception.getMessage());
    org.mockito.Mockito.verify(sessionService)
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.eq(connectScopeId),
            org.mockito.ArgumentMatchers.eq("req-join-2"),
            org.mockito.ArgumentMatchers.argThat(
                replay ->
                    !replay.success()
                        && "JOIN_REQUIRED".equals(replay.errorCode())
                        && exception.getMessage().equals(replay.errorMessage())),
            org.mockito.ArgumentMatchers.longThat(ttl -> ttl > 0L));
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .saveAndFlush(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void issueConnectTokenRejectsMissingMembershipWhenPublicJoiningIsDisabled() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());
    Subscription grace = new Subscription();
    grace.setId(22L);
    grace.setTenantId(7L);
    grace.setStatus("grace");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(grace));
    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-join-disabled")));

    assertEquals("PUBLIC_PRODUCTION_ADMISSION_DENIED", exception.getCode());
    assertEquals("Public joining is not allowed for the selected game", exception.getMessage());
  }

  @Test
  void issueConnectTokenClassifiesKnownBillingDenialAndReplaysItDeterministically() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());
    Subscription active = new Subscription();
    active.setId(21L);
    active.setTenantId(7L);
    active.setStatus("active");
    Subscription canceled = new Subscription();
    canceled.setId(22L);
    canceled.setTenantId(7L);
    canceled.setStatus("canceled");
    when(subscriptionRepository.findByTenantId(7L))
        .thenReturn(java.util.List.of(active), java.util.List.of(canceled));
    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    ConnectTokenRequest request = new ConnectTokenRequest(connectScopeId, "req-gameplay-disabled");

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.issueConnectToken(bootstrap.bootstrapToken(), request));

    assertEquals("TENANT_BILLING_BLOCKED", exception.getCode());
    assertEquals("Gameplay is not available for this tenant", exception.getMessage());
    org.mockito.Mockito.verify(sessionService)
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.eq(connectScopeId),
            org.mockito.ArgumentMatchers.eq("req-gameplay-disabled"),
            org.mockito.ArgumentMatchers.argThat(
                replay -> !replay.success() && "TENANT_BILLING_BLOCKED".equals(replay.errorCode())),
            org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .saveAndFlush(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());

    when(sessionService.getConnectTokenReplay(7L, 11L, connectScopeId, "req-gameplay-disabled"))
        .thenReturn(
            Optional.of(
                new SessionService.ConnectTokenReplay(
                    false, null, "TENANT_BILLING_BLOCKED", exception.getMessage())));
    AuthenticationException replayed =
        assertThrows(
            AuthenticationException.class,
            () -> service.issueConnectToken(bootstrap.bootstrapToken(), request));

    assertEquals("TENANT_BILLING_BLOCKED", replayed.getCode());
    assertEquals(exception.getMessage(), replayed.getMessage());
  }

  @Test
  void issueConnectTokenKeepsGenericMembershipRejectionDistinctFromBillingDenial() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    AccountTenantMembership deniedMembership = membership(account, 7L);
    deniedMembership.setGameplayAdmissionAllowed(true);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(deniedMembership));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("preview")
                    .setDisplayName("Preview Realm")
                    .setTenantId("7")
                    .setGameInstanceId("55")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(19L)
                    .setVisible(true)
                    .setPublicProductionRealm(false)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "preview"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("preview")
                .setRealmDisplayName("Preview Realm")
                .setTenantId("7")
                .setGameInstanceId("55")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(19L)
                .setVisible(true)
                .setPublicProductionRealm(false)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());
    when(accountRealmAccessGrantRepository.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "preview"))
        .thenReturn(true);
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    deniedMembership.setGameplayAdmissionAllowed(false);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-membership-denied")));

    assertEquals("CONNECT_TOKEN_REJECTED", exception.getCode());
    assertEquals("Gameplay admission is not allowed for this account", exception.getMessage());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .saveAndFlush(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void issueConnectTokenReplaysSameFailureForSameRequestId() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("99")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(18L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());

    AuthenticationException firstFailure =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-replay-fail-1")));
    // The catalog and pointer disagree before Account has a valid current pair to compare.
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", firstFailure.getCode());

    when(sessionService.getConnectTokenReplay(7L, 11L, connectScopeId, "req-replay-fail-1"))
        .thenReturn(
            Optional.of(
                new SessionService.ConnectTokenReplay(
                    false, null, "ADMISSION_POINTER_UNAVAILABLE", firstFailure.getMessage())));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("44")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());

    AuthenticationException replayedFailure =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-replay-fail-1")));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", replayedFailure.getCode());
    assertEquals(firstFailure.getMessage(), replayedFailure.getMessage());
    assertEquals(
        "Selected gameplay realm is no longer admissible; rerun realm discovery before retrying gameplay entry",
        replayedFailure.getMessage());
  }

  @Test
  void listBootstrapCharactersUsesEntityManagementForResolvedRealm() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));
    net.firedevops.firemud.entitymanagement.v1.Character character =
        net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
            .setId("char-1")
            .setName("Mara")
            .setLevel(12)
            .build();
    when(entityManagementClient.listCharactersByAccount(
            7L, 11L, 44L, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(java.util.List.of(character));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    var characters =
        service.listBootstrapCharacters(
            bootstrap.bootstrapToken(), "demo", "production", connectScopeId);

    assertEquals(1, characters.size());
    assertEquals("char-1", characters.getFirst().characterId());
    assertEquals("Mara", characters.getFirst().characterName());
    assertEquals("SHARED", characters.getFirst().stateScope());
    assertEquals("ALLOW_NEW", characters.getFirst().characterCreationPolicy());
  }

  @ParameterizedTest
  @CsvSource({"false", "true"})
  void listBootstrapCharactersRequiresCurrentAdmittingMembership(boolean membershipExists) {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    if (membershipExists) {
      AccountTenantMembership membership = membership(account, 7L);
      membership.setGameplayAdmissionAllowed(false);
      when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
          .thenReturn(Optional.of(membership));
    } else {
      when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
          .thenReturn(Optional.empty());
    }
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.listBootstrapCharacters(
                    bootstrap.bootstrapToken(), "demo", "production", connectScopeId));

    assertEquals("JOIN_REQUIRED", exception.getCode());
    assertEquals("Join the selected world before discovering characters", exception.getMessage());
    verifyNoInteractions(entityManagementClient);
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .saveAndFlush(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
  }

  @Test
  void listBootstrapCharactersRejectsAmbiguousRealmBeforeAdmissionFiltering() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    var visibleRealm =
        net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setDisplayName("Live Realm")
            .setTenantId("7")
            .setGameInstanceId("44")
            .setRealmId("101")
            .setPlayableStateNamespaceId("production-namespace-7")
            .setCatalogRevision(23L)
            .setPointerVersion(17L)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setRequiresCharacterSelection(false)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .build();
    var hiddenDuplicate =
        visibleRealm.toBuilder().setVisible(false).setPublicProductionRealm(false).build();
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(java.util.List.of(visibleRealm, hiddenDuplicate));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.listBootstrapCharacters(
                    bootstrap.bootstrapToken(), "demo", "production", connectScopeId));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", ex.getCode());
    verifyNoInteractions(entityManagementClient);
  }

  @Test
  void listBootstrapCharactersSkipsMalformedUnrelatedRealm() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    var visibleRealm =
        net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setDisplayName("Live Realm")
            .setTenantId("7")
            .setGameInstanceId("44")
            .setRealmId("101")
            .setPlayableStateNamespaceId("production-namespace-7")
            .setCatalogRevision(23L)
            .setPointerVersion(17L)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setRequiresCharacterSelection(false)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .build();
    var malformedUnrelatedRealm =
        visibleRealm.toBuilder().setRealmSlug("broken").setTenantId("bad").build();
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(java.util.List.of(malformedUnrelatedRealm, visibleRealm));

    var characters =
        service.listBootstrapCharacters(
            bootstrap.bootstrapToken(), "demo", "production", connectScopeId);

    assertTrue(characters.isEmpty());
  }

  @Test
  void listBootstrapRealmsIncludesRealmStatePolicy() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    var realms = service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo");

    assertEquals(1, realms.size());
    assertEquals("SHARED", realms.getFirst().stateScope());
    assertEquals("ALLOW_NEW", realms.getFirst().characterCreationPolicy());
  }

  @Test
  void listBootstrapRealmsRejectsRealmWithMalformedTenantId() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("broken")
                    .setDisplayName("Broken Realm")
                    .setTenantId("bad")
                    .setGameInstanceId("44")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build(),
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setDisplayName("Live Realm")
                    .setTenantId("7")
                    .setGameInstanceId("44")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));
  }

  @Test
  void listBootstrapCharactersUsesIsolatedRealmRoster() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));

    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setDisplayName("Live Realm")
                    .setTenantId("7")
                    .setGameInstanceId("91")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("ISOLATED")
                    .setCharacterCreationPolicy("COPIED_ONLY")
                    .build()));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("91")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("ISOLATED")
                .setCharacterCreationPolicy("COPIED_ONLY")
                .build());
    net.firedevops.firemud.entitymanagement.v1.Character character =
        net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
            .setId("char-iso-1")
            .setName("ForkMara")
            .setLevel(4)
            .build();
    when(entityManagementClient.listCharactersByAccount(
            7L, 11L, 91L, PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(java.util.List.of(character));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    var characters =
        service.listBootstrapCharacters(
            bootstrap.bootstrapToken(), "demo", "production", connectScopeId);

    assertEquals(1, characters.size());
    assertEquals("char-iso-1", characters.getFirst().characterId());
    assertEquals("ForkMara", characters.getFirst().characterName());
    assertEquals("ISOLATED", characters.getFirst().stateScope());
    assertEquals("COPIED_ONLY", characters.getFirst().characterCreationPolicy());
  }

  @Test
  void listBootstrapCharactersRejectsAdmissionPointerWithMalformedGameInstanceId() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            admissionPointer(
                7L, "demo", "production", "44", 17L, true, true, "SHARED", "ALLOW_NEW"),
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("bad")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.listBootstrapCharacters(
                    bootstrap.bootstrapToken(), "demo", "production", connectScopeId));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", ex.getCode());
    org.mockito.Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void listBootstrapCharactersRejectsAdmissionPointerWithUnknownStateScope() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            admissionPointer(
                7L, "demo", "production", "44", 17L, true, true, "SHARED", "ALLOW_NEW"),
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("44")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("UNKNOWN")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.listBootstrapCharacters(
                    bootstrap.bootstrapToken(), "demo", "production", connectScopeId));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
    verifyNoInteractions(entityManagementClient);
  }

  @Test
  void listBootstrapCharactersUsesWorldQualifiedPointerLookupWhenRealmSlugDuplicatesAcrossWorlds() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));

    when(gameSessionClient.listGameplayRealms("sandbox"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("sandbox")
                    .setRealmSlug("production")
                    .setDisplayName("Live Realm")
                    .setTenantId("7")
                    .setGameInstanceId("91")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("ISOLATED")
                    .setCharacterCreationPolicy("COPIED_ONLY")
                    .build()));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("44")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());
    when(gameSessionClient.getAdmissionPointer(7L, "sandbox", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("sandbox")
                .setWorldDisplayName("Builder Sandbox")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("91")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("ISOLATED")
                .setCharacterCreationPolicy("COPIED_ONLY")
                .build());
    net.firedevops.firemud.entitymanagement.v1.Character character =
        net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
            .setId("char-sandbox-1")
            .setName("BuilderMara")
            .setLevel(9)
            .build();
    when(entityManagementClient.listCharactersByAccount(
            7L, 11L, 91L, PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(java.util.List.of(character));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service
            .listBootstrapRealms(bootstrap.bootstrapToken(), "sandbox")
            .getFirst()
            .connectScopeId();

    var characters =
        service.listBootstrapCharacters(
            bootstrap.bootstrapToken(), "sandbox", "production", connectScopeId);

    assertEquals(1, characters.size());
    assertEquals("char-sandbox-1", characters.getFirst().characterId());
    assertEquals("BuilderMara", characters.getFirst().characterName());
    assertEquals("ISOLATED", characters.getFirst().stateScope());
    org.mockito.Mockito.verify(gameSessionClient, org.mockito.Mockito.times(2))
        .getAdmissionPointer(7L, "sandbox", "production");
  }

  @Test
  void listBootstrapCharactersUsesSignedScopeToDisambiguateTenantIdentity() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(Optional.of(membership(account, 7L)));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setDisplayName("Tenant Seven")
                    .setTenantId("7")
                    .setGameInstanceId("44")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build(),
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setDisplayName("Tenant Eight")
                    .setTenantId("8")
                    .setGameInstanceId("45")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-8")
                    .setCatalogRevision(23L)
                    .setPointerVersion(18L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            admissionPointer(
                7L, "demo", "production", "44", 17L, true, true, "SHARED", "ALLOW_NEW"));
    when(gameSessionClient.getAdmissionPointer(8L, "demo", "production"))
        .thenReturn(
            admissionPointer(
                8L, "demo", "production", "45", 18L, true, true, "SHARED", "ALLOW_NEW"));
    when(entityManagementClient.listCharactersByAccount(
            7L, 11L, 44L, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(java.util.List.of());

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").stream()
            .filter(realm -> realm.tenantId() == 7L)
            .findFirst()
            .orElseThrow()
            .connectScopeId();

    var characters =
        service.listBootstrapCharacters(
            bootstrap.bootstrapToken(), "demo", "production", connectScopeId);

    assertTrue(characters.isEmpty());
    org.mockito.Mockito.verify(gameSessionClient, org.mockito.Mockito.times(2))
        .getAdmissionPointer(7L, "demo", "production");
    org.mockito.Mockito.verify(gameSessionClient).getAdmissionPointer(8L, "demo", "production");
  }

  @Test
  void listBootstrapCharactersRejectsPathThatDoesNotMatchSignedScope() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.listBootstrapCharacters(
                    bootstrap.bootstrapToken(), "sandbox", "production", connectScopeId));

    assertEquals("CONNECT_SCOPE_MISMATCH", ex.getCode());
    verifyNoInteractions(entityManagementClient);
  }

  @Test
  void listBootstrapRealmsExcludesNonPublicRealmWithoutGrant() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("preview")
                    .setDisplayName("Preview Realm")
                    .setTenantId("7")
                    .setGameInstanceId("55")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(19L)
                    .setVisible(false)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "preview"))
        .thenReturn(
            admissionPointer(
                7L, "demo", "preview", "55", 19L, false, false, "SHARED", "ALLOW_NEW"));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    var realms = service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo");

    assertEquals(0, realms.size());
  }

  @ParameterizedTest
  @CsvSource({"true, false", "false, true"})
  void listBootstrapRealmsExcludesNonPublicRealmWithoutMembershipEvenWithGrant(
      boolean visible, boolean publicProductionRealm) {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("preview")
                    .setDisplayName("Preview Realm")
                    .setTenantId("7")
                    .setGameInstanceId("55")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(19L)
                    .setVisible(visible)
                    .setPublicProductionRealm(publicProductionRealm)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "preview"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("preview")
                .setRealmDisplayName("Preview Realm")
                .setTenantId("7")
                .setGameInstanceId("55")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(19L)
                .setVisible(visible)
                .setPublicProductionRealm(publicProductionRealm)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());
    when(accountRealmAccessGrantRepository.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "preview"))
        .thenReturn(true);

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    assertTrue(service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"true, false", "false, true"})
  void listBootstrapRealmsIncludesNonPublicRealmWhenMembershipAndGrantPresent(
      boolean visible, boolean publicProductionRealm) {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("preview")
                    .setDisplayName("Preview Realm")
                    .setTenantId("7")
                    .setGameInstanceId("55")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(19L)
                    .setVisible(visible)
                    .setPublicProductionRealm(publicProductionRealm)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "preview"))
        .thenReturn(
            admissionPointer(
                7L,
                "demo",
                "preview",
                "55",
                19L,
                visible,
                publicProductionRealm,
                "SHARED",
                "ALLOW_NEW"));
    when(accountRealmAccessGrantRepository.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "preview"))
        .thenReturn(true);

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    var realms = service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo");

    assertEquals(1, realms.size());
    assertEquals("preview", realms.getFirst().realmSlug());
    assertFalse(realms.getFirst().connectScopeId().isBlank());
  }

  @ParameterizedTest
  @CsvSource({"true, false", "false, true"})
  void issueConnectTokenRevalidatesMembershipAfterNonPublicDiscovery(
      boolean visible, boolean publicProductionRealm) {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)), Optional.empty());
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("preview")
                    .setDisplayName("Preview Realm")
                    .setTenantId("7")
                    .setGameInstanceId("55")
                    .setRealmId("101")
                    .setPlayableStateNamespaceId("production-namespace-7")
                    .setCatalogRevision(23L)
                    .setPointerVersion(19L)
                    .setVisible(visible)
                    .setPublicProductionRealm(publicProductionRealm)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "preview"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("preview")
                .setRealmDisplayName("Preview Realm")
                .setTenantId("7")
                .setGameInstanceId("55")
                .setRealmId("101")
                .setPlayableStateNamespaceId("production-namespace-7")
                .setCatalogRevision(23L)
                .setPointerVersion(19L)
                .setVisible(visible)
                .setPublicProductionRealm(publicProductionRealm)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());
    when(accountRealmAccessGrantRepository.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "preview"))
        .thenReturn(true);

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-preview-1")));

    assertEquals("NON_PUBLIC_ENROLLMENT_REQUIRED", exception.getCode());
    assertEquals(
        "Existing game membership is required for this non-public realm", exception.getMessage());
    org.mockito.Mockito.verify(sessionService)
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.eq(connectScopeId),
            org.mockito.ArgumentMatchers.eq("req-preview-1"),
            org.mockito.ArgumentMatchers.argThat(
                replay ->
                    !replay.success()
                        && "NON_PUBLIC_ENROLLMENT_REQUIRED".equals(replay.errorCode())
                        && exception.getMessage().equals(replay.errorMessage())),
            org.mockito.ArgumentMatchers.longThat(ttl -> ttl > 0L));
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .saveAndFlush(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void grantRealmAccessUpsertsRuntimeGrant() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountRealmAccessGrantRepository.findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "preview"))
        .thenReturn(Optional.empty());
    when(accountRealmAccessGrantRepository.save(
            org.mockito.ArgumentMatchers.any(AccountRealmAccessGrant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.grantRealmAccess(
            new RealmAccessGrantRequest(
                11L, 7L, "demo", "preview", "operator", "preview access", "req-grant-1"));

    assertTrue(result.granted());
    assertEquals(1L, result.grantVersion());
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository)
        .save(org.mockito.ArgumentMatchers.any(AccountRealmAccessGrant.class));
  }

  @Test
  void getRealmAccessGrantForRuntimeReturnsGrantState() {
    Account account = new Account();
    account.setId(11L);
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    AccountRealmAccessGrant grant = new AccountRealmAccessGrant();
    grant.setGrantVersion(4L);
    when(accountRealmAccessGrantRepository.findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "preview"))
        .thenReturn(Optional.of(grant));

    var result = service.getRealmAccessGrantForRuntime(11L, 7L, "demo", "preview", "req-grant-1");

    assertTrue(result.granted());
    assertEquals(4L, result.grantVersion());
  }

  @Test
  void getProfileReturnsDto() {
    Account account = new Account();
    account.setId(2L);
    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setTenantId(1L);
    profile.setDisplayName("demo");
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));
    when(profileMapper.toDto(profile))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                1L, 1L, 2L, "demo", null, ProfilePresenceVisibilityPolicy.FRIENDS_ONLY));

    var dto = service.getProfile(1L, 2L);

    assertEquals("demo", dto.displayName());
  }

  @Test
  void listPresenceVisibilityPoliciesOmitsProfilesWithoutAPolicy() {
    Account account = new Account();
    account.setId(2L);
    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setPresenceVisibilityPolicy(null);
    when(profileRepository.findByTenantIdAndAccountIds(1L, java.util.List.of(2L)))
        .thenReturn(java.util.List.of(profile));

    Map<Long, ProfilePresenceVisibilityPolicy> policies =
        service.listPresenceVisibilityPolicies(1L, java.util.List.of(2L));

    assertTrue(policies.isEmpty());
  }

  @Test
  void updateProfileStoresChanges() {
    Profile profile = new Profile();
    profile.setAccount(new Account());
    profile.setTenantId(1L);
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));
    when(profileRepository.save(profile)).thenReturn(profile);
    when(profileMapper.toDto(profile))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                1L, 1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE));

    var dto =
        service.updateProfile(
            new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
                1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE));

    assertEquals("demo", dto.displayName());
    assertEquals(ProfilePresenceVisibilityPolicy.PRIVATE, profile.getPresenceVisibilityPolicy());
    org.mockito.Mockito.verify(notificationService).sendNotification(1L, 2L, "Profile updated");
  }

  @Test
  void profileReadAndUpdateFailClosedWithoutCurrentTenantMembership() {
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(false);

    assertEquals(
        "Profile not found",
        assertThrows(IllegalArgumentException.class, () -> service.getProfile(1L, 2L))
            .getMessage());
    assertEquals(
        "Profile not found",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    service.updateProfile(
                        new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
                            1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE)))
            .getMessage());

    verifyNoInteractions(profileRepository, profileMapper, notificationService);
  }

  @Test
  void updateProfileRejectsReservedHiddenStaffPolicyBeforePersistence() {
    var request =
        new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
            1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.HIDDEN_STAFF);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(request));

    assertEquals(
        "Profile presence visibility policy HIDDEN_STAFF is reserved", exception.getMessage());
    verifyNoInteractions(profileRepository, notificationService);
  }

  @Test
  void exportAccountDataIncludesProfilesAcrossTenants() {
    Account account = new Account();
    account.setId(2L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    Profile tenantOne = profile(account, 1L, "one");
    Profile tenantTwo = profile(account, 2L, "two");
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(profileRepository.findByAccountId(2L)).thenReturn(java.util.List.of(tenantOne, tenantTwo));
    when(profileMapper.toDto(tenantOne))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                10L, 1L, 2L, "one", null, ProfilePresenceVisibilityPolicy.FRIENDS_ONLY));
    when(profileMapper.toDto(tenantTwo))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                20L, 2L, 2L, "two", null, ProfilePresenceVisibilityPolicy.FRIENDS_ONLY));

    var export = service.exportAccountData(2L);

    assertEquals(2L, export.account().id());
    assertEquals(2, export.profiles().size());
  }

  @Test
  void exportTenantDataRequiresTenantMembershipOrProfile() {
    Account account = new Account();
    account.setId(2L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(profileRepository.findByAccountIdAndTenantId(2L, 7L)).thenReturn(Optional.empty());
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 7L)).thenReturn(false);

    assertThrows(IllegalArgumentException.class, () -> service.exportTenantData(7L, 2L));
  }

  @Test
  void deleteAccountRefusesNonterminalSubscription() {
    Account account = new Account();
    account.setId(2L);
    Subscription subscription = new Subscription();
    subscription.setStatus("active");
    subscription.setAccount(account);
    subscription.setTenantId(7L);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(subscriptionRepository.findByAccountId(2L)).thenReturn(java.util.List.of(subscription));

    AccountLifecycleException ex =
        assertThrows(AccountLifecycleException.class, () -> service.deleteAccount(2L));
    assertEquals("ACCOUNT_DELETE_ACTIVE_BILLING_OWNER", ex.getCode());
  }

  @Test
  void deleteAccountRemovesAccountOwnedRowsAfterTerminalSubscriptions() {
    Account account = new Account();
    account.setId(2L);
    Subscription subscription = new Subscription();
    subscription.setStatus("canceled");
    subscription.setEndedAt(java.time.LocalDateTime.now());
    subscription.setAccount(account);
    subscription.setTenantId(7L);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(subscriptionRepository.findByAccountId(2L)).thenReturn(java.util.List.of(subscription));

    service.deleteAccount(2L);

    org.mockito.Mockito.verify(emailVerificationTokenRepository).deleteByAccountId(2L);
    org.mockito.Mockito.verify(passwordResetTokenRepository).deleteByAccountId(2L);
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository).deleteByAccountId(2L);
    org.mockito.Mockito.verify(externalAccountRepository).deleteByAccountId(2L);
    org.mockito.Mockito.verify(paymentTransactionRepository).deleteByAccountId(2L);
    org.mockito.Mockito.verify(subscriptionRepository).deleteByAccountId(2L);
    org.mockito.Mockito.verify(profileRepository).deleteByAccountId(2L);
    org.mockito.Mockito.verify(accountTenantMembershipRepository).deleteByAccountId(2L);
    org.mockito.Mockito.verify(accountRepository).delete(account);
  }

  @Test
  void requestPasswordResetCreatesToken() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));

    service.requestPasswordReset(new PasswordResetRequest("  DEMO@example.com "));

    org.mockito.Mockito.verify(passwordResetTokenRepository)
        .save(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("demo@example.com"),
            org.mockito.ArgumentMatchers.eq("Password Reset"),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verifyNoInteractions(notificationService);
  }

  @Test
  void requestPasswordResetUnknownEmailIsNeutral() {
    when(accountRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

    service.requestPasswordReset(new PasswordResetRequest("unknown@example.com"));

    org.mockito.Mockito.verifyNoInteractions(
        passwordResetTokenRepository, emailService, notificationService);
  }

  @Test
  void sendUsernameReminderEmailsUsername() {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));

    service.sendUsernameReminder(
        new net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest(
            "  DEMO@example.com "));

    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("demo@example.com"),
            org.mockito.ArgumentMatchers.eq("Username Reminder"),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verifyNoInteractions(notificationService);
  }

  @Test
  void sendUsernameReminderUnknownEmailIsNeutral() {
    when(accountRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

    service.sendUsernameReminder(
        new net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest(
            "unknown@example.com"));

    org.mockito.Mockito.verifyNoInteractions(emailService, notificationService);
  }

  @Test
  void linkExternalAccountSavesEntity() {
    Account account = new Account();
    account.setId(5L);
    when(accountRepository.findById(5L)).thenReturn(Optional.of(account));
    when(externalAccountRepository.existsByTenantIdAndAccountIdAndProvider(1L, 5L, "google"))
        .thenReturn(false);

    service.linkExternalAccount(
        new net.firedevops.firemud.accountservice.dto.LinkExternalAccountRequest(
            1L, 5L, "google", "abc"));

    org.mockito.ArgumentCaptor<net.firedevops.firemud.accountservice.entity.ExternalAccount>
        captor =
            org.mockito.ArgumentCaptor.forClass(
                net.firedevops.firemud.accountservice.entity.ExternalAccount.class);
    org.mockito.Mockito.verify(externalAccountRepository).save(captor.capture());
    assertEquals("abc", captor.getValue().getExternalId());
  }

  @Test
  void requestEmailVerificationCreatesToken() {
    Account account = new Account();
    account.setId(6L);
    account.setEmail("demo@example.com");
    when(accountRepository.findById(6L)).thenReturn(Optional.of(account));

    service.requestEmailVerification(6L);

    org.mockito.Mockito.verify(emailVerificationTokenRepository)
        .save(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("Email Verification"),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verifyNoInteractions(notificationService);
  }

  @Test
  void requestEmailVerificationByEmailCreatesTokenForResolvedAccount() {
    Account account = new Account();
    account.setId(6L);
    account.setEmail("demo@example.com");
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));

    service.requestEmailVerification("  DEMO@example.com ");

    org.mockito.Mockito.verify(emailVerificationTokenRepository)
        .save(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("demo@example.com"),
            org.mockito.ArgumentMatchers.eq("Email Verification"),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verifyNoInteractions(notificationService);
  }

  @Test
  void requestEmailVerificationByUnknownEmailIsNeutral() {
    when(accountRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

    service.requestEmailVerification("unknown@example.com");

    org.mockito.Mockito.verifyNoInteractions(
        emailVerificationTokenRepository, emailService, notificationService);
  }

  @Test
  void verifyEmailSetsFlag() {
    Account account = new Account();
    EmailVerificationToken token = new EmailVerificationToken();
    token.setAccount(account);
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
    when(emailVerificationTokenRepository.findByToken("tok")).thenReturn(Optional.of(token));

    service.verifyEmail(new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest("tok"));

    assertTrue(account.isEmailVerified());
    org.mockito.Mockito.verify(emailVerificationTokenRepository).delete(token);
  }

  @Test
  void updateLoginAuthModesFailsClosedBeforeReadingOrPersistingAccount() {
    org.springframework.web.server.ResponseStatusException exception =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                service.updateLoginAuthModes(
                    44L,
                    new UpdateAccountLoginAuthModesRequest(
                        java.util.Set.of(AccountLoginAuthMode.EMAIL_OTP))));

    assertEquals(501, exception.getStatusCode().value());
    assertEquals(
        "Recent ordinary reauthentication is required; login-factor changes are unavailable until Account implements its evidence mechanism",
        exception.getReason());
    org.mockito.Mockito.verifyNoInteractions(accountRepository);
  }

  private static String hash(String password) {
    Argon2 argon2 = Argon2Factory.create();
    char[] chars = password.toCharArray();
    try {
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }

  private static net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer admissionPointer(
      long tenantId,
      String worldSlug,
      String realmSlug,
      String gameInstanceId,
      long pointerVersion,
      boolean visible,
      boolean publicProductionRealm,
      String stateScope,
      String characterCreationPolicy) {
    return net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
        .setWorldSlug(worldSlug)
        .setWorldDisplayName("demo".equals(worldSlug) ? "Demo World" : "Builder Sandbox")
        .setRealmSlug(realmSlug)
        .setRealmDisplayName("production".equals(realmSlug) ? "Live Realm" : "Preview Realm")
        .setTenantId(Long.toString(tenantId))
        .setGameInstanceId(gameInstanceId)
        .setRealmId("101")
        .setPlayableStateNamespaceId("production-namespace-" + tenantId)
        .setCatalogRevision(23L)
        .setPointerVersion(pointerVersion)
        .setVisible(visible)
        .setPublicProductionRealm(publicProductionRealm)
        .setRequiresCharacterSelection(false)
        .setStateScope(stateScope)
        .setCharacterCreationPolicy(characterCreationPolicy)
        .build();
  }

  private static AccountTenantMembership membership(Account account, long tenantId) {
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setId(tenantId * 100 + (account.getId() == null ? 0L : account.getId()));
    membership.setAccount(account);
    membership.setTenantId(tenantId);
    membership.setGameplayAdmissionAllowed(true);
    membership.setLifecycleState("ACTIVE");
    membership.setMembershipVersion(1L);
    membership.setMembershipAuthorityGeneration(1L);
    membership.setAuthorityProvenance("SEEDED_DEMO");
    return membership;
  }

  private static Profile profile(Account account, long tenantId, String displayName) {
    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setTenantId(tenantId);
    profile.setDisplayName(displayName);
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
    return profile;
  }
}
