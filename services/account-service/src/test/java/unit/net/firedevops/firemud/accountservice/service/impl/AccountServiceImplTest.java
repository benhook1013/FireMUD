package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.dto.PublicProductionMembershipResult;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountRealmAccessGrant;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.entity.EmailVerificationToken;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.entity.Subscription;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.mapper.ProfileMapper;
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
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AccountServiceImplTest {
  @Mock private AccountRepository accountRepository;
  @Mock private AccountRealmAccessGrantRepository accountRealmAccessGrantRepository;
  @Mock private AccountTenantMembershipRepository accountTenantMembershipRepository;
  @Mock private ProfileRepository profileRepository;
  @Mock private ProfileMapper profileMapper;
  @Mock private NotificationService notificationService;
  @Mock private EmailService emailService;
  @Mock private MailProperties mailProperties;
  private final AccountTokenProperties tokenProperties = new AccountTokenProperties();
  private final JwtAuthProperties jwtAuthProperties = new JwtAuthProperties();
  @Mock private LoggingAdminClient loggingAdminClient;
  @Mock private GameSessionClient gameSessionClient;
  @Mock private EntityManagementClient entityManagementClient;
  @Mock private SessionService sessionService;
  @Mock private SagaRunner sagaRunner;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private SubscriptionRepository subscriptionRepository;
  @Mock private ExternalAccountRepository externalAccountRepository;

  @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;

  @Mock
  private net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository
      passwordResetTokenRepository;

  private AccountServiceImpl service;

  @BeforeEach
  void setup() throws net.firedevops.firemud.common.saga.SagaException {
    MockitoAnnotations.openMocks(this);
    AccountMapper mapper = Mappers.getMapper(AccountMapper.class);
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 3600000L);
    jwtAuthProperties.setJwtSecret("mysecretkey123456789012345678901");
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
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(gameSessionClient.getAdmissionPointer("demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("44")
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());
    when(mailProperties.getResetUrl()).thenReturn("http://reset/%s");
    when(mailProperties.getVerificationUrl()).thenReturn("http://verify/%s");
    service =
        new AccountServiceImpl(
            accountRepository,
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
            loggingAdminClient,
            gameSessionClient,
            entityManagementClient,
            jwtUtil,
            sessionService,
            sagaRunner);
    org.mockito.Mockito.doAnswer(
            inv -> {
              ((net.firedevops.firemud.common.saga.Saga) inv.getArgument(0)).run();
              return null;
            })
        .when(sagaRunner)
        .run(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void createAccountPersistsEntity() throws net.firedevops.firemud.common.saga.SagaException {
    CreateAccountRequest request =
        new CreateAccountRequest(7L, "demo", "demo@example.com", "password");
    Account saved = new Account();
    saved.setId(1L);
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class))).thenReturn(saved);

    AccountDto dto = service.createAccount(request);

    assertEquals(1L, dto.id());
    assertEquals("demo", dto.username());
    org.mockito.Mockito.verify(sagaRunner).run(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void createAccountContinuesWhenAuditLoggingFails()
      throws net.firedevops.firemud.common.saga.SagaException {
    CreateAccountRequest request =
        new CreateAccountRequest(7L, "demo", "demo@example.com", "password");
    Account saved = new Account();
    saved.setId(1L);
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class))).thenReturn(saved);
    org.mockito.Mockito.doThrow(new StatusRuntimeException(Status.UNAVAILABLE))
        .when(loggingAdminClient)
        .logAccountCreation(7L, 1L);

    AccountDto dto = service.createAccount(request);

    assertEquals(1L, dto.id());
    assertEquals("demo", dto.username());
    org.mockito.Mockito.verify(sagaRunner).run(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void authenticateReturnsTokenWhenPasswordMatches() {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(membership(account, 1L)));

    AuthenticationResult result = service.authenticate(1L, "demo", "password", null);

    assertNotNull(result.authToken());
    assertEquals(1L, result.accountId());
    org.mockito.Mockito.verify(sessionService).storeSession(1L, 1L, result.authToken());
  }

  @Test
  void issuePlayerBootstrapReturnsShortLivedToken() {
    Account account = new Account();
    account.setId(7L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));

    PlayerBootstrapResult result = service.issuePlayerBootstrap(1L, "demo", "password", null);

    assertEquals(7L, result.accountId());
    assertNotNull(result.bootstrapToken());
    assertNotNull(result.issuedAt());
    assertNotNull(result.expiresAt());
    org.mockito.Mockito.verify(sessionService)
        .storeSession(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(300000L));
    assertEquals(
        "player-bootstrap",
        new JwtUtil("mysecretkey123456789012345678901", 300000L)
            .parseToken(result.bootstrapToken())
            .getPayload()
            .getAudience()
            .iterator()
            .next());
  }

  @Test
  void authenticateFallsBackToTenantEmailLookup() {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo@example.com")).thenReturn(Optional.empty());
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(membership(account, 1L)));

    AuthenticationResult result = service.authenticate(1L, "demo@example.com", "password", null);

    assertNotNull(result.authToken());
    assertEquals(1L, result.accountId());
    org.mockito.Mockito.verify(sessionService).storeSession(1L, 1L, result.authToken());
  }

  @Test
  void authenticateThrowsWhenInvalid() {
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.empty());
    when(accountRepository.findByEmail("demo")).thenReturn(Optional.empty());
    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class, () -> service.authenticate(1L, "demo", "bad", null));
    assertEquals(AuthenticationErrorCodes.INVALID_CREDENTIALS, exception.getCode());
  }

  @Test
  void authenticateDoesNotUseGlobalAccountFallback() {
    when(accountRepository.findByUsername("demo@example.com")).thenReturn(Optional.empty());
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.empty());

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.authenticate(1L, "demo@example.com", "password", null));

    assertEquals(AuthenticationErrorCodes.INVALID_CREDENTIALS, exception.getCode());
  }

  @Test
  void authenticateRejectsMissingGameplayMembership() {
    Account account = new Account();
    account.setId(7L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(7L, 1L))
        .thenReturn(Optional.empty());

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.authenticate(1L, "demo", "password", null));

    assertEquals("Invalid credentials", exception.getMessage());
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
    assertTrue(dto.gameplayAdmissionAllowed());
    assertEquals(711L, dto.membershipVersion());
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
    assertEquals(31L, dto.entitlementVersion());
    assertEquals(31L, dto.tenantBillingSequence());
    assertNotNull(dto.evaluatedAt());
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

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap(7L, "demo", "password", null);
    when(sessionService.getAccountId(7L, bootstrap.bootstrapToken())).thenReturn(11L);
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
    assertNotNull(result.issuedAt());
    assertNotNull(result.expiresAt());
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

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap(7L, "demo", "password", null);
    when(sessionService.getAccountId(7L, bootstrap.bootstrapToken())).thenReturn(11L);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    when(gameSessionClient.getAdmissionPointer("demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("99")
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

    assertEquals("CONNECT_SCOPE_MISMATCH", ex.getCode());
  }

  @Test
  void ensurePublicProductionMembershipCreatesGameplayMembershipWhenMissing() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(membership(account, 7L)));
    when(accountTenantMembershipRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              AccountTenantMembership membership = invocation.getArgument(0);
              membership.setAccount(account);
              membership.setTenantId(7L);
              membership.setGameplayAdmissionAllowed(true);
              java.lang.reflect.Field idField =
                  AccountTenantMembership.class.getDeclaredField("id");
              idField.setAccessible(true);
              idField.set(membership, 711L);
              return membership;
            });

    PublicProductionMembershipResult result =
        service.ensurePublicProductionPlayerMembership(11L, 7L, "production", "req-join-1");

    assertEquals(11L, result.accountId());
    assertEquals(7L, result.tenantId());
    assertEquals("production", result.realmSlug());
    assertEquals(711L, result.membershipVersion());
    assertTrue(result.created());
    org.mockito.Mockito.verify(loggingAdminClient)
        .logPublicProductionMembershipCreated(7L, 11L, "production", 711L, "req-join-1");
  }

  @Test
  void issueConnectTokenCreatesPublicProductionMembershipWhenMissing() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.empty())
        .thenReturn(Optional.empty())
        .thenReturn(Optional.empty());
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));
    when(accountTenantMembershipRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              AccountTenantMembership membership = invocation.getArgument(0);
              membership.setAccount(account);
              membership.setTenantId(7L);
              membership.setGameplayAdmissionAllowed(true);
              java.lang.reflect.Field idField =
                  AccountTenantMembership.class.getDeclaredField("id");
              idField.setAccessible(true);
              idField.set(membership, 711L);
              return membership;
            });

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap(7L, "demo", "password", null);
    when(sessionService.getAccountId(7L, bootstrap.bootstrapToken())).thenReturn(11L);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    ConnectTokenResult result =
        service.issueConnectToken(
            bootstrap.bootstrapToken(), new ConnectTokenRequest(connectScopeId, "req-join-2"));

    assertEquals(11L, result.accountId());
    assertEquals(7L, result.tenantId());
    org.mockito.Mockito.verify(accountTenantMembershipRepository)
        .saveAndFlush(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
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

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap(7L, "demo", "password", null);
    when(sessionService.getAccountId(7L, bootstrap.bootstrapToken())).thenReturn(11L);

    var characters =
        service.listBootstrapCharacters(bootstrap.bootstrapToken(), "demo", "production");

    assertEquals(1, characters.size());
    assertEquals("char-1", characters.getFirst().characterId());
    assertEquals("Mara", characters.getFirst().characterName());
    assertEquals("SHARED", characters.getFirst().stateScope());
    assertEquals("ALLOW_NEW", characters.getFirst().characterCreationPolicy());
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

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap(7L, "demo", "password", null);
    when(sessionService.getAccountId(7L, bootstrap.bootstrapToken())).thenReturn(11L);

    var realms = service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo");

    assertEquals(1, realms.size());
    assertEquals("SHARED", realms.getFirst().stateScope());
    assertEquals("ALLOW_NEW", realms.getFirst().characterCreationPolicy());
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
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("ISOLATED")
                    .setCharacterCreationPolicy("COPIED_ONLY")
                    .build()));
    when(gameSessionClient.getAdmissionPointer("demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("91")
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

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap(7L, "demo", "password", null);
    when(sessionService.getAccountId(7L, bootstrap.bootstrapToken())).thenReturn(11L);

    var characters =
        service.listBootstrapCharacters(bootstrap.bootstrapToken(), "demo", "production");

    assertEquals(1, characters.size());
    assertEquals("char-iso-1", characters.getFirst().characterId());
    assertEquals("ForkMara", characters.getFirst().characterName());
    assertEquals("ISOLATED", characters.getFirst().stateScope());
    assertEquals("COPIED_ONLY", characters.getFirst().characterCreationPolicy());
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
                    .setPointerVersion(19L)
                    .setVisible(false)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap(7L, "demo", "password", null);
    when(sessionService.getAccountId(7L, bootstrap.bootstrapToken())).thenReturn(11L);

    var realms = service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo");

    assertEquals(0, realms.size());
  }

  @Test
  void issueConnectTokenAllowsNonPublicRealmWithGrant() {
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
                    .setPointerVersion(19L)
                    .setVisible(false)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(gameSessionClient.getAdmissionPointer("demo", "preview"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("preview")
                .setRealmDisplayName("Preview Realm")
                .setTenantId("7")
                .setGameInstanceId("55")
                .setPointerVersion(19L)
                .setVisible(false)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());
    when(accountRealmAccessGrantRepository.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "preview"))
        .thenReturn(true);

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap(7L, "demo", "password", null);
    when(sessionService.getAccountId(7L, bootstrap.bootstrapToken())).thenReturn(11L);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();

    ConnectTokenResult result =
        service.issueConnectToken(
            bootstrap.bootstrapToken(), new ConnectTokenRequest(connectScopeId, "req-preview-1"));

    assertEquals(11L, result.accountId());
    assertEquals(7L, result.tenantId());
    assertEquals(55L, result.gameInstanceId());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .saveAndFlush(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
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
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));
    when(profileMapper.toDto(profile))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                1L, 1L, 2L, "demo", null, ProfilePresenceVisibilityPolicy.FRIENDS_ONLY));

    var dto = service.getProfile(1L, 2L);

    assertEquals("demo", dto.displayName());
  }

  @Test
  void updateProfileStoresChanges() {
    Profile profile = new Profile();
    profile.setAccount(new Account());
    profile.setTenantId(1L);
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
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
  void requestPasswordResetCreatesToken() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));

    service.requestPasswordReset(new PasswordResetRequest(1L, "demo@example.com"));

    org.mockito.Mockito.verify(passwordResetTokenRepository)
        .save(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("demo@example.com"),
            org.mockito.ArgumentMatchers.eq("Password Reset"),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(notificationService)
        .sendNotification(1L, 1L, "Password reset requested");
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
            1L, "demo@example.com"));

    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("demo@example.com"),
            org.mockito.ArgumentMatchers.eq("Username Reminder"),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(notificationService)
        .sendNotification(1L, 1L, "Username reminder requested");
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

    service.requestEmailVerification(1L, 6L);

    org.mockito.Mockito.verify(emailVerificationTokenRepository)
        .save(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("Email Verification"),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(notificationService)
        .sendNotification(1L, 6L, "Email verification requested");
  }

  @Test
  void verifyEmailSetsFlag() {
    Account account = new Account();
    EmailVerificationToken token = new EmailVerificationToken();
    token.setAccount(account);
    token.setTenantId(1L);
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
    when(emailVerificationTokenRepository.findByTokenAndTenantId("tok", 1L))
        .thenReturn(Optional.of(token));

    service.verifyEmail(
        new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest(1L, "tok"));

    assertTrue(account.isEmailVerified());
    org.mockito.Mockito.verify(emailVerificationTokenRepository).delete(token);
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

  private static AccountTenantMembership membership(Account account, long tenantId) {
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setId(tenantId * 100 + (account.getId() == null ? 0L : account.getId()));
    membership.setAccount(account);
    membership.setTenantId(tenantId);
    membership.setGameplayAdmissionAllowed(true);
    return membership;
  }
}
