package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
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
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.dto.UpdateAccountLoginAuthModesRequest;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
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
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.OperationConflictException;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.accountservice.repository.ExternalAccountRepository;
import net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import net.firedevops.firemud.accountservice.repository.SubscriptionRepository;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.EmailService;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.accountservice.service.exception.AccountAlreadyExistsException;
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec.PasswordResetAuthorityEvent;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.ReloadableJwtUtil;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.jooq.exception.ConfigurationException;
import org.jooq.exception.MappingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AccountServiceImplTest {
  private static final String JWT_SECRET = "mysecretkey123456789012345678901";
  private static final String REALM_ID = "4c4b57d8-e3a2-48fe-9977-e7df0fdce901";
  private static final String PLAYABLE_STATE_NAMESPACE_ID = "c6ed6a44-c7e7-4f18-81fc-078a74e67c07";
  private static final String PLAYABLE_STATE_NAMESPACE_ID_TENANT_8 =
      "a741a4b8-a2cb-405e-a330-8fbf3dfb841f";
  @Mock private AccountRepository accountRepository;
  @Mock private AccountAuthorityGenerationRepository accountAuthorityGenerationRepository;
  @Mock private AccountAuthorityOutboxRepository accountAuthorityOutboxRepository;
  @Mock private AccountPasswordResetOperationRepository passwordResetOperationRepository;
  @Mock private AccountAuditOutboxRepository accountAuditOutboxRepository;
  @Mock private AccountConnectScopeRepository accountConnectScopeRepository;
  @Mock private AccountJoinOperationRepository accountJoinOperationRepository;
  @Mock private AccountMembershipTransitionReceiptRepository membershipTransitionReceiptRepository;
  @Mock private AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer;
  @Mock private AccountEmailLoginChallengeRepository accountEmailLoginChallengeRepository;
  @Mock private AccountRealmAccessGrantRepository accountRealmAccessGrantRepository;
  @Mock private AccountTenantMembershipRepository accountTenantMembershipRepository;

  @Mock
  private AccountTenantMembershipRoleSnapshotRepository
      accountTenantMembershipRoleSnapshotRepository;

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
  @Mock private PlatformTransactionManager transactionManager;

  @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;

  @Mock
  private net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository
      passwordResetTokenRepository;

  private AccountServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    when(transactionManager.getTransaction(
            org.mockito.ArgumentMatchers.any(TransactionDefinition.class)))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    when(membershipAuthorityEventProducer.publishNewMembershipChange(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(AccountTenantMembership.class)))
        .thenReturn(joinAuthorityCheckpoint());
    when(membershipAuthorityEventProducer.requireNewMembershipBaseline(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(new AccountMembershipAuthorityEventProducer.NewMembershipBaseline(1L, 1L));
    when(membershipAuthorityEventProducer.publishReactivatedMembershipChange(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(AccountTenantMembership.class)))
        .thenReturn(joinAuthorityCheckpoint());
    when(membershipAuthorityEventProducer.requireCommittedJoinEvent(
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(joinAuthorityCheckpoint());
    when(membershipAuthorityEventProducer.requireCurrentMembershipEvent(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(AccountTenantMembership.class),
            org.mockito.ArgumentMatchers.any(RoleSnapshot.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(joinAuthorityCheckpoint());
    service =
        new AccountServiceImpl(
            accountRepository,
            accountAuthorityGenerationRepository,
            accountAuthorityOutboxRepository,
            passwordResetOperationRepository,
            accountAuditOutboxRepository,
            accountConnectScopeRepository,
            accountJoinOperationRepository,
            membershipTransitionReceiptRepository,
            membershipAuthorityEventProducer,
            accountEmailLoginChallengeRepository,
            accountRealmAccessGrantRepository,
            accountTenantMembershipRepository,
            accountTenantMembershipRoleSnapshotRepository,
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
            sessionService,
            transactionManager);
  }

  @Test
  void createAccountPersistsOnlyGlobalIdentity() {
    CreateAccountRequest request =
        new CreateAccountRequest("demo", "  DEMO@example.com ", "password");
    UUID accountUuid = UUID.randomUUID();
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              saved.setId(1L);
              saved.setAccountUuid(accountUuid);
              saved.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
              saved.setAccountUuidSourceNumericId(1L);
              return saved;
            });

    AccountDto dto = service.createAccount(request);

    assertEquals(accountUuid.toString(), dto.id());
    assertEquals("demo", dto.username());
    org.mockito.ArgumentCaptor<Account> accountCaptor =
        org.mockito.ArgumentCaptor.forClass(Account.class);
    org.mockito.Mockito.verify(accountRepository).save(accountCaptor.capture());
    assertEquals("demo@example.com", accountCaptor.getValue().getEmail());
    org.mockito.Mockito.verify(accountAuthorityGenerationRepository)
        .initialize(AccountAuthorityGenerationRepository.AuthorityScope.account(accountUuid));
    org.mockito.Mockito.verify(accountAuditOutboxRepository)
        .append(
            org.mockito.ArgumentMatchers.any(java.util.UUID.class),
            org.mockito.ArgumentMatchers.eq("platform"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("ACCOUNT_REGISTERED"),
            org.mockito.ArgumentMatchers.eq("{\"accountId\":\"" + accountUuid + "\"}"));
    assertEquals(null, accountCaptor.getValue().getRole());
    verifyNoInteractions(profileRepository, accountTenantMembershipRepository);
  }

  @Test
  void createAccountInitializesCanonicalAccountAuthorityBeforeRegistrationAudit() {
    CreateAccountRequest request = new CreateAccountRequest("demo", "demo@example.com", "password");
    UUID accountUuid = UUID.randomUUID();
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              saved.setId(1L);
              saved.setAccountUuid(accountUuid);
              saved.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
              saved.setAccountUuidSourceNumericId(1L);
              return saved;
            });

    service.createAccount(request);

    org.mockito.InOrder inOrder =
        org.mockito.Mockito.inOrder(
            accountRepository, accountAuthorityGenerationRepository, accountAuditOutboxRepository);
    inOrder.verify(accountRepository).save(org.mockito.ArgumentMatchers.any(Account.class));
    inOrder
        .verify(accountAuthorityGenerationRepository)
        .initialize(AccountAuthorityGenerationRepository.AuthorityScope.account(accountUuid));
    inOrder
        .verify(accountAuditOutboxRepository)
        .append(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("platform"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("ACCOUNT_REGISTERED"),
            org.mockito.ArgumentMatchers.eq("{\"accountId\":\"" + accountUuid + "\"}"));
  }

  @Test
  void createAccountFailsClosedWithoutCanonicalIdentityBeforeAuthorityOrAudit() {
    CreateAccountRequest request = new CreateAccountRequest("demo", "demo@example.com", "password");
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              saved.setId(1L);
              return saved;
            });

    assertThrows(IllegalStateException.class, () -> service.createAccount(request));

    verifyNoInteractions(accountAuthorityGenerationRepository, accountAuditOutboxRepository);
  }

  @Test
  void createAccountDoesNotAppendRegistrationAuditWhenAuthorityInitializationFails() {
    CreateAccountRequest request = new CreateAccountRequest("demo", "demo@example.com", "password");
    UUID accountUuid = UUID.randomUUID();
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              saved.setId(1L);
              saved.setAccountUuid(accountUuid);
              saved.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
              saved.setAccountUuidSourceNumericId(1L);
              return saved;
            });
    when(accountAuthorityGenerationRepository.initialize(
            AccountAuthorityGenerationRepository.AuthorityScope.account(accountUuid)))
        .thenThrow(new IllegalStateException("authority init failed"));

    assertThrows(IllegalStateException.class, () -> service.createAccount(request));

    verify(accountAuthorityGenerationRepository)
        .initialize(AccountAuthorityGenerationRepository.AuthorityScope.account(accountUuid));
    verifyNoInteractions(accountAuditOutboxRepository);
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
    setPersistedAuthenticationIdentity(account);
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
    String requestId = "j".repeat(128);
    assertEquals(128, requestId.length());
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, requestId);

    JoinPublicProductionResult first =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    var committedOperation = retainedOperation.get();
    org.mockito.Mockito.clearInvocations(transactionManager, gameSessionClient);
    JoinPublicProductionResult retried =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    var normalReplayOrder = org.mockito.Mockito.inOrder(transactionManager, gameSessionClient);
    normalReplayOrder
        .verify(transactionManager)
        .getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition.class));
    normalReplayOrder.verify(transactionManager).commit(org.mockito.ArgumentMatchers.any());
    normalReplayOrder.verify(gameSessionClient).listGameplayRealms("demo");

    org.mockito.Mockito.doThrow(
            new DataAccessResourceFailureException("terminal operation lock read failed"))
        .when(accountJoinOperationRepository)
        .findForUpdate(requestId);
    org.mockito.Mockito.clearInvocations(transactionManager, gameSessionClient);
    JoinPublicProductionResult readbackReplay =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    var readbackReplayOrder = org.mockito.Mockito.inOrder(transactionManager, gameSessionClient);
    readbackReplayOrder
        .verify(transactionManager)
        .getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition.class));
    readbackReplayOrder.verify(transactionManager).rollback(org.mockito.ArgumentMatchers.any());
    readbackReplayOrder.verify(gameSessionClient).listGameplayRealms("demo");

    AuthenticationException onboardingTokenUnavailable =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "first-play-after-join")));

    assertTrue(first.success());
    assertEquals("JOINED", first.outcomeCode());
    assertEquals(701L, first.membershipId());
    assertEquals(2L, first.membershipVersion());
    assertTrue(retried.success());
    assertTrue(retried.replayed());
    assertEquals("AUTH_UNAVAILABLE", onboardingTokenUnavailable.getCode());
    assertTrue(readbackReplay.success());
    assertTrue(readbackReplay.replayed());
    assertEquals(committedOperation, retainedOperation.get());
    assertEquals(REALM_ID, retainedScope.get().realmId().toString());
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
    org.mockito.ArgumentCaptor<Collection<String>> roleSnapshotCaptor =
        org.mockito.ArgumentCaptor.forClass(Collection.class);
    org.mockito.Mockito.verify(accountTenantMembershipRoleSnapshotRepository)
        .replace(
            org.mockito.ArgumentMatchers.any(AccountTenantMembership.class),
            org.mockito.ArgumentMatchers.eq(2L),
            roleSnapshotCaptor.capture());
    assertEquals(java.util.List.of("player"), roleSnapshotCaptor.getValue().stream().toList());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.times(1))
        .bindPolicyEvidence(
            org.mockito.ArgumentMatchers.eq(requestId),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(true));
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.times(1))
        .finish(
            org.mockito.ArgumentMatchers.eq(requestId),
            org.mockito.ArgumentMatchers.eq("COMMITTED"),
            org.mockito.ArgumentMatchers.eq("JOINED"),
            org.mockito.ArgumentMatchers.eq(701L),
            org.mockito.ArgumentMatchers.eq(2L),
            org.mockito.ArgumentMatchers.eq(1L));
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .recordAttemptFailure(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(3))
        .findByTenantIdForUpdate(7L);
    org.mockito.Mockito.verify(accountAuditOutboxRepository, org.mockito.Mockito.times(1))
        .append(
            org.mockito.ArgumentMatchers.any(java.util.UUID.class),
            org.mockito.ArgumentMatchers.eq("tenant"),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("ACCOUNT_JOINED_PUBLIC_PRODUCTION"),
            org.mockito.ArgumentMatchers.contains(requestId));
    assertEquals("COMMITTED", retainedOperation.get().status());
    org.mockito.Mockito.verify(membershipAuthorityEventProducer)
        .preparePairAuthorityForJoin(11L, 7L);
    org.mockito.Mockito.verify(membershipAuthorityEventProducer)
        .requireNewMembershipBaseline(11L, 7L);
    org.mockito.Mockito.verify(membershipAuthorityEventProducer)
        .publishNewMembershipChange(
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(requestId),
            org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(membershipAuthorityEventProducer, org.mockito.Mockito.times(2))
        .requireCommittedJoinEvent(org.mockito.ArgumentMatchers.eq(committedOperation));
    AuthenticationException changedDigest =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.joinPublicProduction(
                    bootstrap.bootstrapToken(),
                    new JoinPublicProductionRequest("different-scope", requestId)));
    assertEquals("IDEMPOTENCY_CONFLICT", changedDigest.getCode());

    when(accountTenantMembershipRoleSnapshotRepository.findForUpdate(11L, 7L, 701L, 2L))
        .thenReturn(Optional.of(new RoleSnapshot(11L, 7L, 701L, 2L, java.util.List.of("player"))));
    when(membershipTransitionReceiptRepository.findLatestReceipt(11L, 7L))
        .thenReturn(
            Optional.of(
                new MembershipTransitionReceipt(
                    "membership-transition:account-11:tenant-7",
                    1L,
                    UUID.randomUUID(),
                    "sha256:" + "0".repeat(64),
                    "ACCOUNT_MEMBERSHIP_TRANSITION_RECEIPT_V1",
                    "MEMBERSHIP_JOINED",
                    requestId,
                    701L)));
    JoinPublicProductionResult alreadyActive =
        service.joinPublicProduction(
            bootstrap.bootstrapToken(),
            new JoinPublicProductionRequest(connectScopeId, "join-attempt-2"));

    assertTrue(alreadyActive.success());
    assertEquals("ALREADY_ACTIVE", alreadyActive.outcomeCode());
    org.mockito.Mockito.verify(membershipAuthorityEventProducer)
        .requireCurrentMembershipEvent(
            11L,
            7L,
            joinedMembership.get(),
            new RoleSnapshot(11L, 7L, 701L, 2L, java.util.List.of("player")),
            requestId,
            false);
  }

  @Test
  void committedJoinReplayClassifiesReadbackDependencyFailureAsUnavailable() {
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    CommittedJoinFixture fixture =
        prepareCommittedJoinOperation(retainedOperation, "join-readback-db-1");
    when(membershipAuthorityEventProducer.requireCommittedJoinEvent(
            org.mockito.ArgumentMatchers.any()))
        .thenThrow(
            new org.springframework.transaction.CannotCreateTransactionException(
                "authority readback transaction unavailable"));

    JoinPublicProductionResult replayed =
        service.joinPublicProduction(
            fixture.bootstrapToken(),
            new JoinPublicProductionRequest(fixture.connectScopeId(), "join-readback-db-1"));

    assertFalse(replayed.success());
    assertEquals("AUTH_UNAVAILABLE", replayed.outcomeCode());
    org.mockito.ArgumentCaptor<AccountJoinOperationRepository.JoinOperation> operationCaptor =
        org.mockito.ArgumentCaptor.forClass(AccountJoinOperationRepository.JoinOperation.class);
    verify(membershipAuthorityEventProducer).requireCommittedJoinEvent(operationCaptor.capture());
    assertEquals(retainedOperation.get(), operationCaptor.getValue());
  }

  @Test
  void committedJoinReplayClassifiesReachableMalformedReadbackAsSessionRevoked() {
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    CommittedJoinFixture fixture =
        prepareCommittedJoinOperation(retainedOperation, "join-readback-invalid-1");
    when(membershipAuthorityEventProducer.requireCommittedJoinEvent(
            org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("committed authority event is missing"));

    JoinPublicProductionResult replayed =
        service.joinPublicProduction(
            fixture.bootstrapToken(),
            new JoinPublicProductionRequest(fixture.connectScopeId(), "join-readback-invalid-1"));

    assertFalse(replayed.success());
    assertEquals("AUTH_SESSION_REVOKED", replayed.outcomeCode());
    org.mockito.ArgumentCaptor<AccountJoinOperationRepository.JoinOperation> operationCaptor =
        org.mockito.ArgumentCaptor.forClass(AccountJoinOperationRepository.JoinOperation.class);
    verify(membershipAuthorityEventProducer).requireCommittedJoinEvent(operationCaptor.capture());
    assertEquals(retainedOperation.get(), operationCaptor.getValue());
  }

  @Test
  void joinPublicProductionRejectsRequestIdLongerThan128BeforeIntentLookupOrMutation() {
    Account account = directTextAccount();
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    String requestId = "j".repeat(129);
    DirectTextCallerContext baseCaller = directTextCaller();
    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            baseCaller.accountId(),
            baseCaller.tenantId(),
            baseCaller.realmId(),
            baseCaller.playableStateNamespaceId(),
            baseCaller.playableStateScope(),
            baseCaller.gameInstanceId(),
            baseCaller.sessionId(),
            requestId);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.joinPublicProductionFromGameSession(
                    caller, new JoinPublicProductionRequest("retained-scope", requestId)));

    assertEquals("INVALID_ARGUMENT", exception.getCode());
    verifyNoInteractions(
        accountJoinOperationRepository,
        accountTenantMembershipRepository,
        accountAuditOutboxRepository);
  }

  @ParameterizedTest
  @CsvSource({
    "SECURITY_LOCKED, AUTH_ACCOUNT_LOCKED",
    "DEACTIVATED_PENDING_DELETE, AUTH_INVALID_CREDENTIALS"
  })
  void joinPublicProductionRejectsStillActiveBootstrapForIneligibleLifecycleState(
      AccountLifecycleState lifecycleState, String expectedCode) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    org.mockito.Mockito.clearInvocations(accountTenantMembershipRepository);
    account.setLifecycleState(lifecycleState);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.joinPublicProduction(
                    bootstrap.bootstrapToken(),
                    new JoinPublicProductionRequest(connectScopeId, "join-ineligible-1")));

    assertEquals(expectedCode, exception.getCode());
    verifyNoInteractions(
        accountJoinOperationRepository,
        accountTenantMembershipRepository,
        accountAuditOutboxRepository);
  }

  @Test
  void closedPublicJoinRetainsFailureAndCannotCreateMembershipOrAudit() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(grace));
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
    var storedDeniedOperation = retainedOperation.get();
    when(subscriptionRepository.findByTenantIdForUpdate(7L)).thenReturn(java.util.List.of());
    JoinPublicProductionResult unavailableAuthorityReplay =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    assertFalse(unavailableAuthorityReplay.success());
    assertFalse(unavailableAuthorityReplay.replayed());
    assertEquals("AUTH_UNAVAILABLE", unavailableAuthorityReplay.outcomeCode());
    assertEquals(storedDeniedOperation, retainedOperation.get());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(2))
        .findByTenantIdForUpdate(7L);
  }

  @Test
  void committedJoinRetryRejectsChangedPolicyWithoutMutatingStoredOutcomeOrMembership() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    Subscription originalPolicy = new Subscription();
    originalPolicy.setId(22L);
    originalPolicy.setTenantId(7L);
    originalPolicy.setStatus("active");
    originalPolicy.setEntitlementVersion(5L);
    Subscription changedPolicy = new Subscription();
    changedPolicy.setId(22L);
    changedPolicy.setTenantId(7L);
    changedPolicy.setStatus("active");
    changedPolicy.setEntitlementVersion(6L);
    when(subscriptionRepository.findByTenantIdForUpdate(7L))
        .thenReturn(java.util.List.of(originalPolicy), java.util.List.of(changedPolicy));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(originalPolicy));
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, "join-terminal-policy-change-1");

    JoinPublicProductionResult joined =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    var committedOperation = retainedOperation.get();
    AccountTenantMembership membership = joinedMembership.get();
    JoinPublicProductionResult retry =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertTrue(joined.success());
    assertEquals(2L, joined.membershipVersion());
    assertFalse(retry.success());
    assertFalse(retry.replayed());
    assertEquals("IDEMPOTENCY_CONFLICT", retry.outcomeCode());
    assertEquals(committedOperation, retainedOperation.get());
    assertEquals("COMMITTED", retainedOperation.get().status());
    assertEquals("JOINED", retainedOperation.get().outcome());
    assertEquals(5L, retainedOperation.get().entitlementVersion());
    assertEquals(membership, joinedMembership.get());
    assertEquals("ACTIVE", membership.getLifecycleState());
    assertEquals(2L, membership.getMembershipVersion());
    when(subscriptionRepository.findByTenantIdForUpdate(7L)).thenReturn(java.util.List.of());
    JoinPublicProductionResult unavailableAuthorityRetry =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    assertFalse(unavailableAuthorityRetry.success());
    assertFalse(unavailableAuthorityRetry.replayed());
    assertEquals("AUTH_UNAVAILABLE", unavailableAuthorityRetry.outcomeCode());
    assertEquals(committedOperation, retainedOperation.get());
    assertEquals(membership, joinedMembership.get());
    assertEquals(joined.membershipVersion(), membership.getMembershipVersion());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.times(1))
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(accountAuditOutboxRepository, org.mockito.Mockito.times(1))
        .append(
            org.mockito.ArgumentMatchers.any(java.util.UUID.class),
            org.mockito.ArgumentMatchers.eq("tenant"),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("ACCOUNT_JOINED_PUBLIC_PRODUCTION"),
            org.mockito.ArgumentMatchers.contains("join-terminal-policy-change-1"));
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(3))
        .findByTenantIdForUpdate(7L);
  }

  @ParameterizedTest
  @CsvSource({"false", "true"})
  void missingOrAmbiguousEntitlementRetainsUnavailableJoinReceipt(boolean ambiguous) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    Subscription recovered = new Subscription();
    recovered.setId(22L);
    recovered.setTenantId(7L);
    recovered.setStatus("active");
    recovered.setEntitlementVersion(1L);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());
    when(accountTenantMembershipRepository.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              AccountTenantMembership membership = invocation.getArgument(0);
              membership.setId(701L);
              return membership;
            });

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    when(subscriptionRepository.findByTenantId(7L))
        .thenReturn(
            ambiguous
                ? java.util.List.of(new Subscription(), new Subscription())
                : java.util.List.of(),
            java.util.List.of(recovered));
    when(subscriptionRepository.findByTenantIdForUpdate(7L))
        .thenReturn(java.util.List.of(recovered));
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, "join-unavailable-1");

    JoinPublicProductionResult first =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    assertFalse(first.success());
    assertEquals("ENTITLEMENT_UNAVAILABLE", first.outcomeCode());
    assertEquals("PENDING", retainedOperation.get().status());
    assertEquals(null, retainedOperation.get().outcome());
    assertEquals("ENTITLEMENT_UNAVAILABLE", retainedOperation.get().lastAttemptFailureCode());
    assertEquals("UNAVAILABLE", retainedOperation.get().lastAttemptAuthorityAvailability());
    assertNotNull(retainedOperation.get().intentDigest());
    assertEquals(null, retainedOperation.get().requestDigest());
    assertEquals(null, retainedOperation.get().requestDigestVersion());
    assertEquals(null, retainedOperation.get().allowPublicJoin());
    assertEquals(null, retainedOperation.get().entitlementVersion());

    JoinPublicProductionResult retriedAfterRecovery =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertTrue(retriedAfterRecovery.success());
    assertEquals("JOINED", retriedAfterRecovery.outcomeCode());
    assertEquals("COMMITTED", retainedOperation.get().status());
    assertEquals("AVAILABLE", retainedOperation.get().entitlementAuthorityAvailability());
    assertEquals(1L, retainedOperation.get().entitlementVersion());
    assertEquals(true, retainedOperation.get().allowPublicJoin());
    assertNotNull(retainedOperation.get().requestDigest());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.times(1))
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(accountAuditOutboxRepository, org.mockito.Mockito.times(1))
        .append(
            org.mockito.ArgumentMatchers.any(java.util.UUID.class),
            org.mockito.ArgumentMatchers.eq("tenant"),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("ACCOUNT_JOINED_PUBLIC_PRODUCTION"),
            org.mockito.ArgumentMatchers.contains("join-unavailable-1"));
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(1))
        .findByTenantIdForUpdate(7L);
  }

  @Test
  void joinPublicProductionRevalidatesPolicyAtTheMembershipCommitGate() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
        .thenReturn(java.util.List.of(changedPolicy));
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
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(initialPolicy));
    org.mockito.Mockito.clearInvocations(gameSessionClient, subscriptionRepository);

    JoinPublicProductionResult result =
        service.joinPublicProduction(
            bootstrap.bootstrapToken(),
            new JoinPublicProductionRequest(connectScopeId, "join-race-1"));

    assertFalse(result.success());
    assertEquals("IDEMPOTENCY_CONFLICT", result.outcomeCode());
    assertEquals("FAILED", retainedOperation.get().status());
    assertEquals("IDEMPOTENCY_CONFLICT", retainedOperation.get().outcome());
    assertEquals(5L, retainedOperation.get().entitlementVersion());
    assertEquals(true, retainedOperation.get().allowPublicJoin());
    assertEquals(1, retainedOperation.get().requestDigestVersion());
    assertNotNull(retainedOperation.get().requestDigest());
    assertEquals(null, retainedOperation.get().lastAttemptFailureCode());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(1))
        .findByTenantIdForUpdate(7L);
    var order = org.mockito.Mockito.inOrder(gameSessionClient, subscriptionRepository);
    order.verify(gameSessionClient).listGameplayRealms("demo");
    order.verify(gameSessionClient).getAdmissionPointer(7L, "demo", "production");
    order.verify(subscriptionRepository).findByTenantId(7L);
    order.verify(gameSessionClient).listGameplayRealms("demo");
    order.verify(gameSessionClient).getAdmissionPointer(7L, "demo", "production");
    order.verify(subscriptionRepository).findByTenantIdForUpdate(7L);
    org.mockito.Mockito.verifyNoMoreInteractions(gameSessionClient);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void scopeExpiringDuringFinalJoinEvaluationLeavesNewOrRetainedIntentPending(
      boolean retainedPendingIntent) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    tokenProperties.setConnectScopeExpirationMs(3_600_000L);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(
            connectScopeId,
            retainedPendingIntent ? "join-expiring-retained-1" : "join-expiring-new-1");

    if (retainedPendingIntent) {
      when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of());
      JoinPublicProductionResult initialAttempt =
          service.joinPublicProduction(bootstrap.bootstrapToken(), request);
      assertFalse(initialAttempt.success());
      assertEquals("ENTITLEMENT_UNAVAILABLE", initialAttempt.outcomeCode());
      assertEquals("PENDING", retainedOperation.get().status());
    }

    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    active.setEntitlementVersion(1L);
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());
    VerifiedJoinScope scope = retainedScope.get();
    java.time.Instant scopeExpiry = java.time.Instant.parse(scope.connectScopeExpiresAt());
    java.time.Instant evaluatedAt = java.time.Instant.parse(scope.evaluatedAt());
    assertTrue(scopeExpiry.isAfter(evaluatedAt));
    AtomicReference<java.time.Instant> now = new AtomicReference<>(evaluatedAt);
    when(subscriptionRepository.findByTenantIdForUpdate(7L))
        .thenAnswer(
            invocation -> {
              now.set(scopeExpiry);
              return java.util.List.of(active);
            });
    org.mockito.Mockito.clearInvocations(
        accountJoinOperationRepository,
        accountTenantMembershipRepository,
        accountAuditOutboxRepository,
        subscriptionRepository);

    JoinPublicProductionResult result;
    try (org.mockito.MockedStatic<java.time.Instant> instantMock =
        org.mockito.Mockito.mockStatic(
            java.time.Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      instantMock.when(java.time.Instant::now).thenAnswer(invocation -> now.get());
      result = service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    }

    assertFalse(result.success());
    assertFalse(result.replayed());
    assertEquals("AUTH_UNAVAILABLE", result.outcomeCode());
    assertEquals("PENDING", retainedOperation.get().status());
    assertNull(retainedOperation.get().outcome());
    assertEquals("AUTH_UNAVAILABLE", retainedOperation.get().lastAttemptFailureCode());
    assertEquals("NOT_EVALUATED", retainedOperation.get().lastAttemptAuthorityAvailability());
    assertNotNull(retainedOperation.get().requestDigest());
    assertEquals(1L, retainedOperation.get().entitlementVersion());
    assertEquals(true, retainedOperation.get().allowPublicJoin());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .recordAttemptFailure(request.requestId(), "NOT_EVALUATED", "AUTH_UNAVAILABLE");
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .recordCallerBoundAuthorityInvalidation(request.requestId());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .finish(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Long.class));
    org.mockito.Mockito.verify(accountTenantMembershipRepository)
        .findByAccountIdAndTenantId(11L, 7L);
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
  }

  @Test
  void joinPublicProductionRejectsLateAmbiguousPublicRealmBeforeMembershipCommit() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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

    net.firedevops.firemud.gamesession.v1.GameplayRealm selectedRealm =
        gameSessionClient.listGameplayRealms("demo").getFirst();
    net.firedevops.firemud.gamesession.v1.GameplayRealm lateDuplicate =
        selectedRealm.toBuilder()
            .setRealmSlug("staging")
            .setDisplayName("Staging Realm")
            .setRealmId("57c58f36-c5ea-4aa8-8ef7-91a45e407f01")
            .setGameInstanceId("45")
            .setPlayableStateNamespaceId("staging-namespace-7")
            .setCatalogRevision(24L)
            .setPointerVersion(1L)
            .build();
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(java.util.List.of(selectedRealm, lateDuplicate));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenThrow(new IllegalStateException("ambiguous public-production realm catalog"));

    JoinPublicProductionResult result =
        service.joinPublicProduction(
            bootstrap.bootstrapToken(),
            new JoinPublicProductionRequest(connectScopeId, "join-late-ambiguous-realm-1"));

    assertFalse(result.success());
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", result.outcomeCode());
    assertEquals("PENDING", retainedOperation.get().status());
    assertEquals(null, retainedOperation.get().outcome());
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", retainedOperation.get().lastAttemptFailureCode());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
    org.mockito.Mockito.verify(gameSessionClient, org.mockito.Mockito.atLeastOnce())
        .getAdmissionPointer(7L, "demo", "production");
  }

  @Test
  void changedPolicyOnRetryTerminalizesPreviouslyBoundPendingJoin() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    when(subscriptionRepository.findByTenantIdForUpdate(7L)).thenReturn(java.util.List.of());

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    when(subscriptionRepository.findByTenantId(7L))
        .thenReturn(java.util.List.of(initialPolicy), java.util.List.of(changedPolicy));
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, "join-policy-retry-1");

    JoinPublicProductionResult pending =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    String boundDigest = retainedOperation.get().requestDigest();
    String intentDigest = retainedOperation.get().intentDigest();

    assertFalse(pending.success());
    assertEquals("ENTITLEMENT_UNAVAILABLE", pending.outcomeCode());
    assertEquals("PENDING", retainedOperation.get().status());
    assertEquals(5L, retainedOperation.get().entitlementVersion());
    assertEquals("ENTITLEMENT_UNAVAILABLE", retainedOperation.get().lastAttemptFailureCode());

    JoinPublicProductionResult changedPolicyRetry =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertFalse(changedPolicyRetry.success());
    assertEquals("IDEMPOTENCY_CONFLICT", changedPolicyRetry.outcomeCode());
    assertEquals("FAILED", retainedOperation.get().status());
    assertEquals("IDEMPOTENCY_CONFLICT", retainedOperation.get().outcome());
    assertEquals(intentDigest, retainedOperation.get().intentDigest());
    assertEquals(boundDigest, retainedOperation.get().requestDigest());
    assertEquals(5L, retainedOperation.get().entitlementVersion());
    assertEquals(true, retainedOperation.get().allowPublicJoin());
    assertEquals(null, retainedOperation.get().lastAttemptFailureCode());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(1))
        .findByTenantIdForUpdate(7L);
  }

  @Test
  void unboundFailedJoinReplaysOnlyAfterCurrentScopeAndAuthorityChecks() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    var exactRealm = gameSessionClient.listGameplayRealms("demo").getFirst();
    var exactPointer = gameSessionClient.getAdmissionPointer(7L, "demo", "production");
    var changedRealm = exactRealm.toBuilder().setPointerVersion(18L).build();
    var changedPointer = exactPointer.toBuilder().setPointerVersion(18L).build();
    when(gameSessionClient.listGameplayRealms("demo")).thenReturn(java.util.List.of(changedRealm));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(changedPointer);
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantIdForUpdate(7L)).thenReturn(java.util.List.of(active));
    org.mockito.Mockito.clearInvocations(gameSessionClient, subscriptionRepository);
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, "join-unbound-failure-1");

    JoinPublicProductionResult originalFailure =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    var storedFailure = retainedOperation.get();

    assertFalse(originalFailure.success());
    assertEquals("CONNECT_SCOPE_MISMATCH", originalFailure.outcomeCode());
    assertEquals("FAILED", storedFailure.status());
    assertEquals(originalFailure.outcomeCode(), storedFailure.outcome());
    assertNull(storedFailure.requestDigest());

    JoinPublicProductionResult changedTargetDenial =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertFalse(changedTargetDenial.success());
    assertFalse(changedTargetDenial.replayed());
    assertEquals("CONNECT_SCOPE_MISMATCH", changedTargetDenial.outcomeCode());
    assertEquals(storedFailure, retainedOperation.get());

    when(gameSessionClient.listGameplayRealms("demo")).thenReturn(java.util.List.of(exactRealm));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production")).thenReturn(exactPointer);
    JoinPublicProductionResult replay =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertFalse(replay.success());
    assertTrue(replay.replayed());
    assertEquals(originalFailure.outcomeCode(), replay.outcomeCode());
    assertEquals(storedFailure, retainedOperation.get());

    VerifiedJoinScope originalScope = retainedScope.get();
    VerifiedJoinScope alteredScopeWithoutDigest =
        new VerifiedJoinScope(
            originalScope.connectScopeId(),
            originalScope.accountId(),
            originalScope.tenantId(),
            originalScope.realmId(),
            originalScope.worldSlug(),
            originalScope.realmSlug(),
            originalScope.playableStateNamespaceId(),
            originalScope.playableStateScope(),
            originalScope.gameInstanceId(),
            originalScope.catalogRevision(),
            originalScope.pointerVersion() + 1,
            originalScope.evaluatedAt(),
            originalScope.connectScopeExpiresAt(),
            "");
    retainedScope.set(
        new VerifiedJoinScope(
            alteredScopeWithoutDigest.connectScopeId(),
            alteredScopeWithoutDigest.accountId(),
            alteredScopeWithoutDigest.tenantId(),
            alteredScopeWithoutDigest.realmId(),
            alteredScopeWithoutDigest.worldSlug(),
            alteredScopeWithoutDigest.realmSlug(),
            alteredScopeWithoutDigest.playableStateNamespaceId(),
            alteredScopeWithoutDigest.playableStateScope(),
            alteredScopeWithoutDigest.gameInstanceId(),
            alteredScopeWithoutDigest.catalogRevision(),
            alteredScopeWithoutDigest.pointerVersion(),
            alteredScopeWithoutDigest.evaluatedAt(),
            alteredScopeWithoutDigest.connectScopeExpiresAt(),
            net.firedevops.firemud.accountservice.dto.AccountJoinDigest.scope(
                alteredScopeWithoutDigest)));

    AuthenticationException changedScope =
        assertThrows(
            AuthenticationException.class,
            () -> service.joinPublicProduction(bootstrap.bootstrapToken(), request));
    assertEquals("IDEMPOTENCY_CONFLICT", changedScope.getCode());
    assertEquals(storedFailure, retainedOperation.get());

    retainedScope.set(originalScope);
    when(subscriptionRepository.findByTenantIdForUpdate(7L)).thenReturn(java.util.List.of());
    JoinPublicProductionResult unavailableAuthority =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertFalse(unavailableAuthority.success());
    assertFalse(unavailableAuthority.replayed());
    assertEquals("AUTH_UNAVAILABLE", unavailableAuthority.outcomeCode());
    assertEquals(storedFailure, retainedOperation.get());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(2))
        .findByTenantIdForUpdate(7L);
  }

  @Test
  void pairAuthorityPreparationFailureRetainsRetryablePolicyIndependentJoinIntent() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    org.mockito.Mockito.doThrow(new IllegalStateException("pair authority storage unavailable"))
        .doNothing()
        .when(membershipAuthorityEventProducer)
        .preparePairAuthorityForJoin(11L, 7L);

    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    active.setEntitlementVersion(1L);
    when(subscriptionRepository.findByTenantIdForUpdate(7L)).thenReturn(java.util.List.of(active));
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

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation =
        new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, "join-pair-authority-retry-1");

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () -> service.joinPublicProduction(bootstrap.bootstrapToken(), request));

    assertEquals("AUTH_UNAVAILABLE", unavailable.getCode());
    assertEquals("PENDING", retainedOperation.get().status());
    assertEquals(null, retainedOperation.get().outcome());
    assertNotNull(retainedOperation.get().intentDigest());
    assertEquals(null, retainedOperation.get().requestDigest());
    assertEquals(null, retainedOperation.get().requestDigestVersion());
    assertEquals(null, retainedOperation.get().allowPublicJoin());
    assertEquals(null, retainedOperation.get().entitlementVersion());
    assertEquals("NOT_EVALUATED", retainedOperation.get().entitlementAuthorityAvailability());
    assertEquals("AUTH_UNAVAILABLE", retainedOperation.get().lastAttemptFailureCode());
    assertEquals("NOT_EVALUATED", retainedOperation.get().lastAttemptAuthorityAvailability());
    assertEquals(null, joinedMembership.get());
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.never())
        .findByTenantIdForUpdate(7L);
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(
            accountTenantMembershipRoleSnapshotRepository, org.mockito.Mockito.never())
        .replace(
            org.mockito.ArgumentMatchers.any(AccountTenantMembership.class),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyCollection());
    org.mockito.Mockito.verify(membershipAuthorityEventProducer, org.mockito.Mockito.never())
        .requireNewMembershipBaseline(11L, 7L);
    org.mockito.Mockito.verify(membershipAuthorityEventProducer, org.mockito.Mockito.never())
        .publishNewMembershipChange(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(membershipTransitionReceiptRepository, org.mockito.Mockito.never())
        .appendTransition(
            org.mockito.ArgumentMatchers.any(AccountTenantMembership.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);

    JoinPublicProductionResult retried =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertTrue(retried.success());
    assertFalse(retried.replayed());
    assertEquals("JOINED", retried.outcomeCode());
    assertEquals("COMMITTED", retainedOperation.get().status());
    assertEquals("AVAILABLE", retainedOperation.get().entitlementAuthorityAvailability());
    assertEquals(1L, retainedOperation.get().entitlementVersion());
    assertNotNull(retainedOperation.get().requestDigest());
    org.mockito.Mockito.verify(membershipAuthorityEventProducer, org.mockito.Mockito.times(2))
        .preparePairAuthorityForJoin(11L, 7L);
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.times(1))
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(membershipAuthorityEventProducer)
        .publishNewMembershipChange(
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(request.requestId()),
            org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verify(membershipTransitionReceiptRepository)
        .appendTransition(
            org.mockito.ArgumentMatchers.any(AccountTenantMembership.class),
            org.mockito.ArgumentMatchers.eq("MEMBERSHIP_JOINED"),
            org.mockito.ArgumentMatchers.eq(request.requestId()));
    org.mockito.Mockito.verify(accountAuditOutboxRepository)
        .append(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("tenant"),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("ACCOUNT_JOINED_PUBLIC_PRODUCTION"),
            org.mockito.ArgumentMatchers.contains(request.requestId()));
  }

  @Test
  void joinIntentCommitsSeparatelyWhenPolicyTransactionRollsBack() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    when(subscriptionRepository.findByTenantId(7L))
        .thenThrow(new org.jooq.exception.DataAccessException("entitlement storage unavailable"));

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.joinPublicProduction(
                    bootstrap.bootstrapToken(),
                    new JoinPublicProductionRequest(connectScopeId, "join-txn-boundary-1")));

    assertEquals("AUTH_UNAVAILABLE", unavailable.getCode());
    assertEquals("PENDING", retainedOperation.get().status());
    assertEquals(null, retainedOperation.get().outcome());
    assertNotNull(retainedOperation.get().intentDigest());
    assertEquals(null, retainedOperation.get().requestDigest());
    assertEquals("AUTH_UNAVAILABLE", retainedOperation.get().lastAttemptFailureCode());
    org.mockito.Mockito.verify(transactionManager, org.mockito.Mockito.times(3))
        .commit(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(transactionManager, org.mockito.Mockito.times(1))
        .rollback(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    org.mockito.Mockito.verifyNoInteractions(accountAuditOutboxRepository);
  }

  @Test
  void expiredPersistedPendingJoinRemainsPendingWithoutAuthorityOrMembershipReads() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    String connectScopeId = "expired-connect-scope";
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, "join-expired-pending-1");

    VerifiedJoinScope expiredScope =
        new VerifiedJoinScope(
            connectScopeId,
            11L,
            7L,
            UUID.fromString(REALM_ID),
            "demo",
            "production",
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            44L,
            23L,
            17L,
            "1999-12-31T23:59:00Z",
            "2000-01-01T00:00:00Z",
            "");
    expiredScope =
        new VerifiedJoinScope(
            expiredScope.connectScopeId(),
            expiredScope.accountId(),
            expiredScope.tenantId(),
            expiredScope.realmId(),
            expiredScope.worldSlug(),
            expiredScope.realmSlug(),
            expiredScope.playableStateNamespaceId(),
            expiredScope.playableStateScope(),
            expiredScope.gameInstanceId(),
            expiredScope.catalogRevision(),
            expiredScope.pointerVersion(),
            expiredScope.evaluatedAt(),
            expiredScope.connectScopeExpiresAt(),
            net.firedevops.firemud.accountservice.dto.AccountJoinDigest.scope(expiredScope));
    retainedScope.set(expiredScope);
    String callerBinding =
        new JwtUtil(JWT_SECRET, 300000L)
            .parseToken(bootstrap.bootstrapToken())
            .getPayload()
            .get("jti")
            .toString();
    retainedOperation.set(
        new AccountJoinOperationRepository.JoinOperation(
            request.requestId(),
            expiredScope.accountId(),
            expiredScope.tenantId(),
            expiredScope.realmId(),
            expiredScope.worldSlug(),
            expiredScope.realmSlug(),
            expiredScope.playableStateNamespaceId(),
            expiredScope.playableStateScope(),
            expiredScope.gameInstanceId(),
            expiredScope.catalogRevision(),
            expiredScope.pointerVersion(),
            callerBinding,
            net.firedevops.firemud.accountservice.dto.AccountJoinDigest.tokenHash(connectScopeId),
            expiredScope.snapshotDigest(),
            "UNAVAILABLE",
            null,
            null,
            null,
            null,
            "PENDING",
            null,
            null,
            null,
            null,
            1,
            net.firedevops.firemud.accountservice.dto.AccountJoinDigest.intent(
                request.requestId(), expiredScope, callerBinding),
            "ENTITLEMENT_UNAVAILABLE",
            "UNAVAILABLE",
            0,
            null,
            null,
            java.time.Instant.now()));

    JoinPublicProductionResult expiredRetry =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);
    JoinPublicProductionResult repeatedExpiredRetry =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertFalse(expiredRetry.success());
    assertFalse(expiredRetry.replayed());
    assertEquals("AUTH_UNAVAILABLE", expiredRetry.outcomeCode());
    assertFalse(repeatedExpiredRetry.success());
    assertFalse(repeatedExpiredRetry.replayed());
    assertEquals("AUTH_UNAVAILABLE", repeatedExpiredRetry.outcomeCode());
    assertEquals("PENDING", retainedOperation.get().status());
    assertEquals(null, retainedOperation.get().outcome());
    assertEquals("AUTH_UNAVAILABLE", retainedOperation.get().lastAttemptFailureCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.times(2))
        .recordAttemptFailure(request.requestId(), "NOT_EVALUATED", "AUTH_UNAVAILABLE");
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .finish(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Long.class));
    verifyNoInteractions(
        subscriptionRepository, accountTenantMembershipRepository, accountAuditOutboxRepository);
    verifyNoInteractions(membershipAuthorityEventProducer);
  }

  @ParameterizedTest
  @CsvSource({"COMMITTED, JOINED, true", "FAILED, PUBLIC_PRODUCTION_ADMISSION_DENIED, false"})
  void expiredTerminalJoinScopeFailsClosedWithoutReplayingStoredResult(
      String status, String outcome, boolean allowPublicJoin) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    String connectScopeId = expiredConnectScopeId();
    JoinPublicProductionRequest request =
        new JoinPublicProductionRequest(connectScopeId, "join-expired-terminal-1");
    VerifiedJoinScope expiredScope = expiredVerifiedJoinScope(connectScopeId);
    retainedScope.set(expiredScope);
    String callerBinding =
        new JwtUtil(JWT_SECRET, 300000L)
            .parseToken(bootstrap.bootstrapToken())
            .getPayload()
            .get("jti")
            .toString();
    var terminalOperation =
        terminalJoinOperation(
            request.requestId(),
            expiredScope,
            callerBinding,
            status,
            outcome,
            allowPublicJoin,
            false);
    retainedOperation.set(terminalOperation);

    JoinPublicProductionResult retry =
        service.joinPublicProduction(bootstrap.bootstrapToken(), request);

    assertFalse(retry.success());
    assertFalse(retry.replayed());
    assertEquals("CONNECT_SCOPE_INVALID", retry.outcomeCode());
    assertEquals(terminalOperation, retainedOperation.get());
    assertEquals(status, retainedOperation.get().status());
    assertEquals(outcome, retainedOperation.get().outcome());
    assertEquals(terminalOperation.membershipId(), retainedOperation.get().membershipId());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
    verifyNoInteractions(subscriptionRepository, accountAuditOutboxRepository);

    var mismatchedIntent =
        terminalJoinOperation(
            request.requestId(),
            expiredScope,
            callerBinding,
            status,
            outcome,
            allowPublicJoin,
            true);
    retainedOperation.set(mismatchedIntent);
    AuthenticationException intentConflict =
        assertThrows(
            AuthenticationException.class,
            () -> service.joinPublicProduction(bootstrap.bootstrapToken(), request));

    assertEquals("IDEMPOTENCY_CONFLICT", intentConflict.getCode());
    assertEquals(mismatchedIntent, retainedOperation.get());
    verifyNoInteractions(subscriptionRepository);
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .finish(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Long.class));
  }

  @Test
  void newJoinRejectsExpiredConnectScopeBeforePersistingIntent() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException expiredScope =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.joinPublicProduction(
                    bootstrap.bootstrapToken(),
                    new JoinPublicProductionRequest(
                        expiredConnectScopeId(), "join-expired-new-1")));

    assertEquals("CONNECT_SCOPE_INVALID", expiredScope.getCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .insertIntent(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    verifyNoInteractions(
        accountConnectScopeRepository,
        accountTenantMembershipRepository,
        subscriptionRepository,
        accountAuditOutboxRepository);
  }

  private VerifiedJoinScope expiredVerifiedJoinScope(String connectScopeId) {
    VerifiedJoinScope unsignedScope =
        new VerifiedJoinScope(
            connectScopeId,
            11L,
            7L,
            UUID.fromString(REALM_ID),
            "demo",
            "production",
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            44L,
            23L,
            17L,
            "1999-12-31T23:59:00Z",
            "2000-01-01T00:00:00Z",
            "");
    return new VerifiedJoinScope(
        unsignedScope.connectScopeId(),
        unsignedScope.accountId(),
        unsignedScope.tenantId(),
        unsignedScope.realmId(),
        unsignedScope.worldSlug(),
        unsignedScope.realmSlug(),
        unsignedScope.playableStateNamespaceId(),
        unsignedScope.playableStateScope(),
        unsignedScope.gameInstanceId(),
        unsignedScope.catalogRevision(),
        unsignedScope.pointerVersion(),
        unsignedScope.evaluatedAt(),
        unsignedScope.connectScopeExpiresAt(),
        net.firedevops.firemud.accountservice.dto.AccountJoinDigest.scope(unsignedScope));
  }

  private String expiredConnectScopeId() {
    return new JwtUtil(JWT_SECRET, 120000L)
        .generateToken(
            "11",
            -1000L,
            Map.ofEntries(
                Map.entry("aud", "bootstrap-connect-scope"),
                Map.entry("accountId", 11L),
                Map.entry("tenantId", 7L),
                Map.entry("realmId", REALM_ID),
                Map.entry("worldSlug", "demo"),
                Map.entry("realmSlug", "production"),
                Map.entry("playableStateNamespaceId", PLAYABLE_STATE_NAMESPACE_ID),
                Map.entry("playableStateScope", "SHARED"),
                Map.entry("gameInstanceId", 44L),
                Map.entry("catalogRevision", 23L),
                Map.entry("pointerVersion", 17L),
                Map.entry("evaluatedAt", "1999-12-31T23:59:00Z"),
                Map.entry("connectScopeExpiresAt", "2000-01-01T00:00:00Z"),
                Map.entry("jti", "expired-connect-scope")));
  }

  private AccountJoinOperationRepository.JoinOperation terminalJoinOperation(
      String requestId,
      VerifiedJoinScope scope,
      String callerBinding,
      String status,
      String outcome,
      boolean allowPublicJoin,
      boolean corruptIntentDigest) {
    String intentDigest =
        net.firedevops.firemud.accountservice.dto.AccountJoinDigest.intent(
            requestId, scope, callerBinding);
    if (corruptIntentDigest) {
      intentDigest = "corrupt-" + intentDigest;
    }
    boolean committed = "COMMITTED".equals(status);
    return new AccountJoinOperationRepository.JoinOperation(
        requestId,
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
        callerBinding,
        net.firedevops.firemud.accountservice.dto.AccountJoinDigest.tokenHash(
            scope.connectScopeId()),
        scope.snapshotDigest(),
        "AVAILABLE",
        allowPublicJoin,
        1L,
        1,
        net.firedevops.firemud.accountservice.dto.AccountJoinDigest.request(
            scope, callerBinding, allowPublicJoin, 1L),
        status,
        outcome,
        committed ? 701L : null,
        committed ? 1L : null,
        committed ? 1L : null,
        1,
        intentDigest,
        null,
        "AVAILABLE",
        0,
        null,
        null,
        java.time.Instant.now());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "COMMITTED", "FAILED"})
  void joinPublicProductionHidesAnotherAccountsGlobalRequestIdEvidence(String originalStatus) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    org.mockito.Mockito.clearInvocations(accountTenantMembershipRepository);
    VerifiedJoinScope scope = retainedScope.get();
    String requestId = "join-global-collision-" + originalStatus.toLowerCase();
    String originalOutcome =
        switch (originalStatus) {
          case "COMMITTED" -> "JOINED";
          case "FAILED" -> "PUBLIC_PRODUCTION_ADMISSION_DENIED";
          default -> null;
        };
    Long originalMembershipId = "COMMITTED".equals(originalStatus) ? 812L : null;
    AccountJoinOperationRepository.JoinOperation originalOperation =
        new AccountJoinOperationRepository.JoinOperation(
            requestId,
            12L,
            scope.tenantId(),
            scope.realmId(),
            scope.worldSlug(),
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            "different-caller",
            net.firedevops.firemud.accountservice.dto.AccountJoinDigest.tokenHash(connectScopeId),
            scope.snapshotDigest(),
            "COMMITTED".equals(originalStatus) || "FAILED".equals(originalStatus)
                ? "AVAILABLE"
                : "NOT_EVALUATED",
            "PENDING".equals(originalStatus) ? null : true,
            "PENDING".equals(originalStatus) ? null : 7L,
            "PENDING".equals(originalStatus) ? null : 1,
            "PENDING".equals(originalStatus) ? null : "original-request-digest",
            originalStatus,
            originalOutcome,
            originalMembershipId,
            originalMembershipId == null ? null : 9L,
            originalMembershipId == null ? null : 5L,
            1,
            net.firedevops.firemud.accountservice.dto.AccountJoinDigest.intent(
                requestId, scope, "different-caller"),
            null,
            "PENDING".equals(originalStatus) ? "NOT_EVALUATED" : "AVAILABLE",
            0,
            null,
            null,
            java.time.Instant.now());
    retainedOperation.set(originalOperation);

    AuthenticationException conflict =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.joinPublicProduction(
                    bootstrap.bootstrapToken(),
                    new JoinPublicProductionRequest(connectScopeId, requestId)));

    assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
    assertEquals("JOIN request ID was reused with different input", conflict.getMessage());
    assertFalse(conflict.getMessage().contains("JOINED"));
    assertFalse(conflict.getMessage().contains("PUBLIC_PRODUCTION_ADMISSION_DENIED"));
    assertFalse(conflict.getMessage().contains("812"));
    assertEquals(originalOperation, retainedOperation.get());
    assertEquals(originalStatus, retainedOperation.get().status());
    assertEquals(originalOutcome, retainedOperation.get().outcome());
    assertEquals(originalMembershipId, retainedOperation.get().membershipId());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .find(org.mockito.ArgumentMatchers.eq(requestId));
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .insertIntent(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .bindPolicyEvidence(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyBoolean());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .finish(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Long.class),
            org.mockito.ArgumentMatchers.nullable(Long.class));
    verifyNoInteractions(accountTenantMembershipRepository, accountAuditOutboxRepository);
  }

  @Test
  void reclaimedScopeRaceReturnsConnectScopeInvalid() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    VerifiedJoinScope scope = retainedScope.get();
    org.mockito.Mockito.clearInvocations(
        subscriptionRepository, accountTenantMembershipRepository, accountAuditOutboxRepository);
    when(accountConnectScopeRepository.find(connectScopeId))
        .thenReturn(Optional.of(scope), Optional.empty());
    org.mockito.Mockito.doThrow(new org.jooq.exception.DataAccessException("scope reclaimed"))
        .when(accountJoinOperationRepository)
        .insertIntent(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.joinPublicProduction(
                    bootstrap.bootstrapToken(),
                    new JoinPublicProductionRequest(connectScopeId, "join-reclaimed-scope")));

    assertEquals("CONNECT_SCOPE_INVALID", exception.getCode());
    verifyNoInteractions(
        subscriptionRepository, accountTenantMembershipRepository, accountAuditOutboxRepository);
  }

  @Test
  void quarantinedLegacyMembershipIsNeverRestoredByPublicJoin() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
        .thenReturn(Optional.of(membership(account, 7L)), Optional.of(quarantined));
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

  private CommittedJoinFixture prepareCommittedJoinOperation(
      AtomicReference<AccountJoinOperationRepository.JoinOperation> retainedOperation,
      String requestId) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    AtomicReference<VerifiedJoinScope> retainedScope = new AtomicReference<>();
    retainJoinEvidence(retainedScope, retainedOperation);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    JoinPublicProductionResult joined =
        service.joinPublicProduction(
            bootstrap.bootstrapToken(), new JoinPublicProductionRequest(connectScopeId, requestId));
    assertTrue(joined.success());
    assertEquals("JOINED", joined.outcomeCode());
    assertEquals("COMMITTED", retainedOperation.get().status());
    return new CommittedJoinFixture(bootstrap.bootstrapToken(), connectScopeId);
  }

  private record CommittedJoinFixture(String bootstrapToken, String connectScopeId) {}

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
        .thenAnswer(
            invocation -> {
              var current = retainedOperation.get();
              return current != null && current.requestId().equals(invocation.getArgument(0))
                  ? Optional.of(current)
                  : Optional.empty();
            });
    when(accountJoinOperationRepository.findForUpdate(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> {
              var current = retainedOperation.get();
              return current != null && current.requestId().equals(invocation.getArgument(0))
                  ? Optional.of(current)
                  : Optional.empty();
            });
    org.mockito.Mockito.doAnswer(
            invocation -> {
              String requestId = invocation.getArgument(0);
              VerifiedJoinScope scope = invocation.getArgument(1);
              String callerBinding = invocation.getArgument(2);
              String intentDigest = invocation.getArgument(3);
              retainedOperation.set(
                  new AccountJoinOperationRepository.JoinOperation(
                      requestId,
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
                      callerBinding,
                      net.firedevops.firemud.accountservice.dto.AccountJoinDigest.tokenHash(
                          scope.connectScopeId()),
                      scope.snapshotDigest(),
                      "NOT_EVALUATED",
                      null,
                      null,
                      null,
                      null,
                      "PENDING",
                      null,
                      null,
                      null,
                      null,
                      1,
                      intentDigest,
                      null,
                      "NOT_EVALUATED",
                      0,
                      null,
                      null,
                      java.time.Instant.now()));
              return true;
            })
        .when(accountJoinOperationRepository)
        .insertIntent(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var pending = retainedOperation.get();
              retainedOperation.set(
                  new AccountJoinOperationRepository.JoinOperation(
                      pending.requestId(),
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
                      pending.callerBinding(),
                      pending.scopeTokenHash(),
                      pending.connectScopeDigest(),
                      "AVAILABLE",
                      invocation.getArgument(3),
                      invocation.getArgument(2),
                      1,
                      invocation.getArgument(1),
                      pending.status(),
                      pending.outcome(),
                      pending.membershipId(),
                      pending.membershipVersion(),
                      pending.membershipAuthorityGeneration(),
                      pending.intentDigestVersion(),
                      pending.intentDigest(),
                      null,
                      "AVAILABLE",
                      pending.reconciliationAttemptCount(),
                      pending.lastReconciliationAttemptAt(),
                      pending.lastReconciliationAttemptReason(),
                      pending.nextReconciliationAttemptAt()));
              return null;
            })
        .when(accountJoinOperationRepository)
        .bindPolicyEvidence(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyBoolean());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var pending = retainedOperation.get();
              String availability = invocation.getArgument(1);
              retainedOperation.set(
                  new AccountJoinOperationRepository.JoinOperation(
                      pending.requestId(),
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
                      pending.callerBinding(),
                      pending.scopeTokenHash(),
                      pending.connectScopeDigest(),
                      pending.requestDigest() == null
                          ? availability
                          : pending.entitlementAuthorityAvailability(),
                      pending.allowPublicJoin(),
                      pending.entitlementVersion(),
                      pending.requestDigestVersion(),
                      pending.requestDigest(),
                      pending.status(),
                      pending.outcome(),
                      pending.membershipId(),
                      pending.membershipVersion(),
                      pending.membershipAuthorityGeneration(),
                      pending.intentDigestVersion(),
                      pending.intentDigest(),
                      invocation.getArgument(2),
                      availability,
                      pending.reconciliationAttemptCount(),
                      pending.lastReconciliationAttemptAt(),
                      pending.lastReconciliationAttemptReason(),
                      pending.nextReconciliationAttemptAt()));
              return null;
            })
        .when(accountJoinOperationRepository)
        .recordAttemptFailure(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var pending = retainedOperation.get();
              retainedOperation.set(
                  new AccountJoinOperationRepository.JoinOperation(
                      pending.requestId(),
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
                      invocation.getArgument(5),
                      pending.intentDigestVersion(),
                      pending.intentDigest(),
                      null,
                      pending.lastAttemptAuthorityAvailability(),
                      pending.reconciliationAttemptCount(),
                      pending.lastReconciliationAttemptAt(),
                      pending.lastReconciliationAttemptReason(),
                      pending.nextReconciliationAttemptAt()));
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
    setPersistedAuthenticationIdentity(account);
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

    assertEquals(account.getAccountUuid().toString(), result.accountId());
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
    setPersistedAuthenticationIdentity(account);
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

    assertEquals(account.getAccountUuid().toString(), result.accountId());
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).delete(challenge);
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(9L, result.authToken(), jwtAuthProperties.getJwtExpirationMs());
    verifyNoInteractions(accountTenantMembershipRepository);
  }

  @Test
  void authenticateFallsBackToPasswordWithoutBurningUnmatchedEmailLoginOtp() {
    Account account = new Account();
    account.setId(9L);
    setPersistedAuthenticationIdentity(account);
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

    assertEquals(account.getAccountUuid().toString(), result.accountId());
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
    setPersistedAuthenticationIdentity(account);
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

    assertEquals(account.getAccountUuid().toString(), result.accountId());
    var claims = new JwtUtil(JWT_SECRET, 3600000L).parseToken(result.authToken()).getPayload();
    assertEquals("account-service", claims.getAudience().iterator().next());
    assertEquals(account.getAccountUuid().toString(), claims.getSubject());
    assertEquals(account.getAccountUuid().toString(), claims.get("accountId", String.class));
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(accountEmailLoginChallengeRepository);
    inOrder.verify(accountEmailLoginChallengeRepository).lockAccountChallenge(9L);
    inOrder.verify(accountEmailLoginChallengeRepository).findByAccountId(9L);
    inOrder.verify(accountEmailLoginChallengeRepository).delete(challenge);
  }

  @Test
  void authenticateReturnsControlUiTokenWithoutGameplayMembership() {
    Account account = new Account();
    account.setId(1L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));

    AuthenticationResult result = service.authenticate("demo", "password");

    assertNotNull(result.authToken());
    assertEquals(account.getAccountUuid().toString(), result.accountId());
    var claims = new JwtUtil(JWT_SECRET, 3600000L).parseToken(result.authToken()).getPayload();
    assertEquals("control-ui", claims.getAudience().iterator().next());
    assertEquals(account.getAccountUuid().toString(), claims.getSubject());
    assertEquals(account.getAccountUuid().toString(), claims.get("accountId", String.class));
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
    setPersistedAuthenticationIdentity(account);
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    jwtAuthProperties.setJwtSecret(null);

    AuthenticationResult result = service.authenticateForGameplay("demo@example.com", "password");

    assertNotNull(result.authToken());
    assertEquals(account.getAccountUuid().toString(), result.accountId());
    var claims = new JwtUtil(JWT_SECRET, 3600000L).parseToken(result.authToken()).getPayload();
    assertEquals("account-service", claims.getAudience().iterator().next());
    assertEquals(account.getAccountUuid().toString(), claims.getSubject());
    assertEquals(account.getAccountUuid().toString(), claims.get("accountId", String.class));
    assertEquals(java.util.List.of(), claims.get("globalRoles"));
    assertNotNull(claims.get("jti"));
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(1L, result.authToken(), jwtAuthProperties.getJwtExpirationMs());
    verifyNoInteractions(accountTenantMembershipRepository);
  }

  @ParameterizedTest
  @EnumSource(AccountIdentityProvenance.class)
  void resolveAccountStorageIdAcceptsEveryExactPersistedUuidProvenance(
      AccountIdentityProvenance provenance) {
    UUID accountUuid = UUID.randomUUID();
    Account account = new Account();
    account.setId(31L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(provenance);
    account.setAccountUuidSourceNumericId(31L);
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.of(account));

    assertEquals(31L, service.resolveAccountStorageId(accountUuid));

    verify(accountRepository).findByAccountUuid(accountUuid);
    verifyNoInteractions(profileRepository);
  }

  @ParameterizedTest
  @EnumSource(InvalidAuthenticationIdentity.class)
  void resolveAccountStorageIdRejectsUnprovedUuidBeforeProfileOperations(
      InvalidAuthenticationIdentity invalidIdentity) {
    UUID accountUuid = UUID.randomUUID();
    Account account = new Account();
    account.setId(32L);
    setPersistedAuthenticationIdentity(account);
    assignInvalidAuthenticationIdentity(account, invalidIdentity);
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.of(account));

    assertThrows(IllegalStateException.class, () -> service.resolveAccountStorageId(accountUuid));

    verify(accountRepository).findByAccountUuid(accountUuid);
    verifyNoInteractions(profileRepository);
  }

  @Test
  void resolveAccountStorageIdRejectsMissingOrNilUuidBeforeLookup() {
    assertThrows(IllegalArgumentException.class, () -> service.resolveAccountStorageId(null));
    assertThrows(
        IllegalArgumentException.class, () -> service.resolveAccountStorageId(new UUID(0L, 0L)));

    verifyNoInteractions(accountRepository, profileRepository);
  }

  @Test
  void resolveAccountStorageIdRejectsMissingAccountBeforeProfileOperations() {
    UUID accountUuid = UUID.randomUUID();
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.empty());

    assertThrows(
        IllegalArgumentException.class, () -> service.resolveAccountStorageId(accountUuid));

    verify(accountRepository).findByAccountUuid(accountUuid);
    verifyNoInteractions(profileRepository);
  }

  @Test
  void resolveAccountStorageIdRejectsReadbackForAnotherUuid() {
    UUID requestedUuid = UUID.randomUUID();
    Account account = new Account();
    account.setId(33L);
    setPersistedAuthenticationIdentity(account);
    when(accountRepository.findByAccountUuid(requestedUuid)).thenReturn(Optional.of(account));

    assertThrows(IllegalStateException.class, () -> service.resolveAccountStorageId(requestedUuid));

    verifyNoInteractions(profileRepository);
  }

  @ParameterizedTest
  @EnumSource(AccountIdentityProvenance.class)
  void authenticateAcceptsEveryExactPersistedUuidProvenance(AccountIdentityProvenance provenance) {
    Account account = new Account();
    account.setId(31L);
    account.setEmail("retained@example.com");
    account.setPasswordHash(hash("password"));
    setPersistedAuthenticationIdentity(account, provenance);
    when(accountRepository.findByEmail("retained@example.com")).thenReturn(Optional.of(account));

    AuthenticationResult result =
        service.authenticateForGameplay("retained@example.com", "password");

    assertEquals(account.getAccountUuid().toString(), result.accountId());
    var claims = parseClaims(result.authToken());
    assertEquals(account.getAccountUuid().toString(), claims.getSubject());
    assertEquals(account.getAccountUuid().toString(), claims.get("accountId", String.class));
    assertFalse(claims.containsKey("tenantId"));
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(31L, result.authToken(), jwtAuthProperties.getJwtExpirationMs());
  }

  @ParameterizedTest
  @EnumSource(InvalidAuthenticationIdentity.class)
  void authenticationRejectsUnprovedIdentityBeforeConsumingChallengeOrStoringSession(
      InvalidAuthenticationIdentity invalidIdentity) {
    Account account = new Account();
    account.setId(32L);
    account.setEmail("unproved@example.com");
    assignInvalidAuthenticationIdentity(account, invalidIdentity);
    net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge challenge =
        new net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge();
    challenge.setId(8L);
    challenge.setAccountId(32L);
    challenge.setCodeHash(hash("123456"));
    challenge.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
    when(accountRepository.findByEmail("unproved@example.com")).thenReturn(Optional.of(account));
    when(accountEmailLoginChallengeRepository.findByAccountId(32L))
        .thenReturn(Optional.of(challenge));

    assertThrows(
        IllegalStateException.class,
        () -> service.authenticateForGameplay("unproved@example.com", "123456"));

    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).lockAccountChallenge(32L);
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).findByAccountId(32L);
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository, org.mockito.Mockito.never())
        .delete(challenge);
    verifyNoInteractions(sessionService);
  }

  @Test
  void accountJwtMintPathsRemainCanonicalAndConnectTokenFailsClosedWithoutAuthorityTuple() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));

    AuthenticationResult controlUi = service.authenticate("demo", "password");
    AuthenticationResult gameplayDelegation =
        service.authenticateForGameplay("demo@example.com", "password");
    assertEquals(account.getAccountUuid().toString(), controlUi.accountId());
    assertEquals(account.getAccountUuid().toString(), gameplayDelegation.accountId());
    assertCanonicalIssuerAndClaims(
        controlUi.authToken(), "control-ui", account.getAccountUuid().toString());
    assertEquals(java.util.List.of(), parseClaims(controlUi.authToken()).get("globalRoles"));
    assertCanonicalIssuerAndClaims(
        gameplayDelegation.authToken(), "account-service", account.getAccountUuid().toString());
    assertEquals(
        java.util.List.of(), parseClaims(gameplayDelegation.authToken()).get("globalRoles"));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    assertEquals(account.getAccountUuid().toString(), bootstrap.accountId());
    assertCanonicalIssuerAndClaims(
        bootstrap.bootstrapToken(), "player-bootstrap", account.getAccountUuid().toString());

    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    var connectScopeClaims = parseClaims(connectScopeId);
    assertEquals("firemud-account-service", connectScopeClaims.getIssuer());
    assertEquals("bootstrap-connect-scope", connectScopeClaims.getAudience().iterator().next());
    assertEquals(account.getAccountUuid().toString(), connectScopeClaims.getSubject());
    assertEquals(
        account.getAccountUuid().toString(), connectScopeClaims.get("accountId", String.class));
    assertNotNull(connectScopeClaims.get("jti"));
    assertEquals("4c4b57d8-e3a2-48fe-9977-e7df0fdce901", connectScopeClaims.get("realmId"));
    assertEquals(7L, connectScopeClaims.get("tenantId", Long.class));

    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    AuthenticationException connectTokenUnavailable =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "issuer-proof")));
    assertEquals("AUTH_UNAVAILABLE", connectTokenUnavailable.getCode());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());

    Account otpAccount = new Account();
    otpAccount.setId(12L);
    setPersistedAuthenticationIdentity(otpAccount);
    otpAccount.setEmail("otp@example.com");
    otpAccount.setLoginAuthModes("EMAIL_OTP");
    var challenge = new net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge();
    challenge.setAccountId(12L);
    challenge.setCodeHash(hash("123456"));
    challenge.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
    when(accountRepository.findByEmail("otp@example.com")).thenReturn(Optional.of(otpAccount));
    when(accountEmailLoginChallengeRepository.findByAccountId(12L))
        .thenReturn(Optional.of(challenge));
    AuthenticationResult otpDelegation = service.verifyEmailLoginOtp("otp@example.com", "123456");
    assertEquals(otpAccount.getAccountUuid().toString(), otpDelegation.accountId());
    assertCanonicalIssuerAndClaims(
        otpDelegation.authToken(), "account-service", otpAccount.getAccountUuid().toString());
    assertEquals(java.util.List.of(), parseClaims(otpDelegation.authToken()).get("globalRoles"));
  }

  @Test
  void issuePlayerBootstrapReturnsShortLivedToken() {
    Account account = new Account();
    account.setId(7L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    account.setLoginAuthModes("PASSWORD");
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    jwtAuthProperties.setJwtSecret(null);

    PlayerBootstrapResult result = service.issuePlayerBootstrap("demo", "password");

    assertEquals(account.getAccountUuid().toString(), result.accountId());
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
    assertEquals(account.getAccountUuid().toString(), claims.getSubject());
    assertEquals(account.getAccountUuid().toString(), claims.get("accountId"));
    assertFalse(claims.containsKey("tenantId"));
    assertEquals(300000L, claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    verifyNoInteractions(accountEmailLoginChallengeRepository);
  }

  @Test
  void issuePlayerBootstrapPrefersEmailLookupOverCollidingUsername() {
    Account emailAccount = new Account();
    emailAccount.setId(7L);
    setPersistedAuthenticationIdentity(emailAccount);
    emailAccount.setUsername("email-owner");
    emailAccount.setEmail("player@example.com");
    emailAccount.setPasswordHash(hash("password"));
    emailAccount.setLoginAuthModes("PASSWORD");
    when(accountRepository.findByEmail("player@example.com")).thenReturn(Optional.of(emailAccount));

    PlayerBootstrapResult result =
        service.issuePlayerBootstrap("  PLAYER@EXAMPLE.COM ", "password");

    assertEquals(emailAccount.getAccountUuid().toString(), result.accountId());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .findByUsername(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void issuePlayerBootstrapAcceptsAndConsumesEmailLoginOtp() {
    Account account = new Account();
    account.setId(7L);
    setPersistedAuthenticationIdentity(account);
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

    assertEquals(account.getAccountUuid().toString(), result.accountId());
    var claims = parseClaims(result.bootstrapToken());
    assertEquals(account.getAccountUuid().toString(), claims.getSubject());
    assertEquals(account.getAccountUuid().toString(), claims.get("accountId"));
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(accountEmailLoginChallengeRepository);
    inOrder.verify(accountEmailLoginChallengeRepository).lockAccountChallenge(7L);
    inOrder.verify(accountEmailLoginChallengeRepository).findByAccountId(7L);
    inOrder.verify(accountEmailLoginChallengeRepository).delete(challenge);
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(7L, result.bootstrapToken(), 300000L);
  }

  @Test
  void listBootstrapWorldsRejectsInactiveAccountBootstrapToken() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    UUID accountUuid = account.getAccountUuid();
    String bootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(
                accountUuid.toString(),
                Map.of("aud", "player-bootstrap", "accountId", accountUuid.toString()));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class, () -> service.listBootstrapWorlds(bootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
    assertEquals("Bootstrap token expired", ex.getMessage());
    org.mockito.Mockito.verify(sessionService).isAccountSessionActive(11L, bootstrapToken);
  }

  @Test
  void listBootstrapWorldsRejectsMalformedBootstrapTokenAccountClaim() {
    String accountUuid = "59a39c43-6829-48b1-8d09-99bfe533d919";
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(accountUuid, Map.of("aud", "player-bootstrap", "accountId", "abc"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
  }

  @Test
  void listBootstrapWorldsRejectsUnprovedPersistedAccountUuidBeforeSessionLookup() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    UUID accountUuid = account.getAccountUuid();
    account.setAccountUuidSourceNumericId(12L);
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.of(account));
    String bootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(
                accountUuid.toString(),
                Map.of("aud", "player-bootstrap", "accountId", accountUuid.toString()));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class, () -> service.listBootstrapWorlds(bootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
    verify(accountRepository).findByAccountUuid(accountUuid);
    verifyNoInteractions(sessionService, gameSessionClient);
  }

  @Test
  void listBootstrapWorldsRejectsBootstrapTokenAccountSubjectMismatch() {
    String subjectUuid = "d51ee4fa-3af3-437c-98dc-a1bd0706dd91";
    String accountUuid = "59a39c43-6829-48b1-8d09-99bfe533d919";
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(
                subjectUuid, Map.of("aud", "player-bootstrap", "accountId", accountUuid));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
  }

  @Test
  void listBootstrapWorldsRejectsNonCanonicalBootstrapTokenAccountUuid() {
    String accountUuid = "59a39c43-6829-48b1-8d09-99bfe533d919";
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(
                accountUuid, Map.of("aud", "player-bootstrap", "accountId", " " + accountUuid));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
  }

  @Test
  void listBootstrapWorldsRejectsNonStringBootstrapTokenAccountUuid() {
    String accountUuid = "59a39c43-6829-48b1-8d09-99bfe533d919";
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(accountUuid, Map.of("aud", "player-bootstrap", "accountId", 11L));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
    verifyNoInteractions(accountRepository, sessionService);
  }

  @Test
  void listBootstrapWorldsRejectsTrimmedUppercaseAndListAccountUuidClaims() {
    String accountUuid = "59a39c43-6829-48b1-8d09-99bfe533d919";
    for (Object malformedAccountId :
        java.util.List.of(
            " " + accountUuid,
            accountUuid.toUpperCase(java.util.Locale.ROOT),
            java.util.List.of(accountUuid))) {
      String malformedBootstrapToken =
          new JwtUtil(JWT_SECRET, 300000L)
              .generateToken(
                  accountUuid, Map.of("aud", "player-bootstrap", "accountId", malformedAccountId));

      AuthenticationException ex =
          assertThrows(
              AuthenticationException.class,
              () -> service.listBootstrapWorlds(malformedBootstrapToken));

      assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
    }

    verifyNoInteractions(accountRepository, sessionService);
  }

  @Test
  void listBootstrapWorldsRejectsBootstrapTokenWithoutAudience() {
    String accountUuid = "59a39c43-6829-48b1-8d09-99bfe533d919";
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(accountUuid, Map.of("accountId", accountUuid));

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
            accountAuthorityGenerationRepository,
            accountAuthorityOutboxRepository,
            passwordResetOperationRepository,
            accountAuditOutboxRepository,
            accountConnectScopeRepository,
            accountJoinOperationRepository,
            membershipTransitionReceiptRepository,
            membershipAuthorityEventProducer,
            accountEmailLoginChallengeRepository,
            accountRealmAccessGrantRepository,
            accountTenantMembershipRepository,
            accountTenantMembershipRoleSnapshotRepository,
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
            sessionService,
            transactionManager);

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> service.listBootstrapWorlds("boom-token"));

    assertEquals("boom", ex.getMessage());
  }

  @Test
  void listBootstrapWorldsRejectsWorldWithMalformedRuntimeRealmRow() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.Arrays.asList(
                null,
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("broken")
                    .setDisplayName("Broken Realm")
                    .setTenantId("bad")
                    .setGameInstanceId("44")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(bootstrap.bootstrapToken()));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
  }

  @Test
  void listBootstrapRealmsMapsZeroPublicAuthorityFromGameSession() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenThrow(
            new IllegalStateException(
                "Gameplay realm discovery failed: invalid public realm cardinality"));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
  }

  @Test
  void listBootstrapWorldsRejectsWorldWithUnknownStateScope() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setDisplayName("Live Realm")
                    .setTenantId("7")
                    .setGameInstanceId("44")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("UNKNOWN")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(bootstrap.bootstrapToken()));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
  }

  @Test
  void listBootstrapRealmsRejectsMultipleVisiblePublicRealms() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    var firstPublicRealm = gameSessionClient.listGameplayRealms("demo").getFirst();
    var secondPublicRealm =
        firstPublicRealm.toBuilder()
            .setRealmSlug("production-alt")
            .setRealmId("57c58f36-c5ea-4aa8-8ef7-91a45e407f01")
            .setGameInstanceId("45")
            .build();
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(java.util.List.of(firstPublicRealm, secondPublicRealm));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
  }

  @Test
  void listBootstrapRealmsAcceptsExactlyOneVisiblePublicRealm() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    var realms = service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo");

    assertEquals(1, realms.size());
    assertEquals("production", realms.getFirst().realmSlug());
  }

  @Test
  void bootstrapRealmDiscoveryAndDirectTextScopeIssuanceSuspendIncomingTransactions()
      throws NoSuchMethodException {
    Transactional listRealms =
        AccountServiceImpl.class
            .getMethod("listBootstrapRealms", String.class, String.class)
            .getAnnotation(Transactional.class);
    Transactional issueDirectTextScope =
        AccountServiceImpl.class
            .getMethod(
                "issueDirectTextConnectScope",
                DirectTextCallerContext.class,
                DirectTextJoinTarget.class)
            .getAnnotation(Transactional.class);

    assertNotNull(listRealms);
    assertNotNull(issueDirectTextScope);
    assertEquals(Propagation.NOT_SUPPORTED, listRealms.propagation());
    assertEquals(Propagation.NOT_SUPPORTED, issueDirectTextScope.propagation());
  }

  @Test
  void listBootstrapRealmsDoesNotIssueScopeWhenPublicPointerBecomesPrivateBeforeFinalRead() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));
    when(accountRealmAccessGrantRepository.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "production"))
        .thenReturn(true);

    var initiallyPublic = gameSessionClient.listGameplayRealms("demo").getFirst();
    var currentlyPrivate = initiallyPublic.toBuilder().setPublicProductionRealm(false).build();
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(java.util.List.of(initiallyPublic), java.util.List.of(currentlyPrivate));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            admissionPointer(
                7L, "demo", "production", "44", 17L, true, false, "SHARED", "ALLOW_NEW"));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
    org.mockito.Mockito.verify(accountConnectScopeRepository, org.mockito.Mockito.never())
        .insert(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void listBootstrapRealmsDoesNotIssueScopeWhenPointerChangesDuringDiscovery() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));

    var initiallyDiscovered = gameSessionClient.listGameplayRealms("demo").getFirst();
    var changedRealmId = "57c58f36-c5ea-4aa8-8ef7-91a45e407f01";
    var currentRealm =
        initiallyDiscovered.toBuilder().setRealmId(changedRealmId).setPointerVersion(18L).build();
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(java.util.List.of(initiallyDiscovered), java.util.List.of(currentRealm));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            admissionPointer(7L, "demo", "production", "44", 18L, true, true, "SHARED", "ALLOW_NEW")
                .toBuilder()
                .setRealmId(changedRealmId)
                .build());

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
    org.mockito.Mockito.verify(accountConnectScopeRepository, org.mockito.Mockito.never())
        .insert(org.mockito.ArgumentMatchers.any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"101", "not-a-uuid", "4C4B57D8-E3A2-48FE-9977-E7DF0FDCE901"})
  void listBootstrapRealmsRejectsNoncanonicalRealmIds(String realmId) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setDisplayName("Live Realm")
                    .setTenantId("7")
                    .setGameInstanceId("44")
                    .setRealmId(realmId)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
  }

  @Test
  void issueConnectTokenRejectsMalformedConnectScopeId() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    setPersistedAuthenticationIdentity(account);
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
    setPersistedAuthenticationIdentity(account);
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
                account.getAccountUuid().toString(),
                Map.of(
                    "aud",
                    "bootstrap-connect-scope",
                    "accountId",
                    account.getAccountUuid().toString(),
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
    setPersistedAuthenticationIdentity(account);
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
                account.getAccountUuid().toString(),
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

    String nonStringAccountIdScope =
        new JwtUtil(JWT_SECRET, 120000L)
            .generateToken(
                account.getAccountUuid().toString(),
                Map.of(
                    "aud",
                    "bootstrap-connect-scope",
                    "accountId",
                    11L,
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
                    "invalid-type"));
    AuthenticationException nonStringClaimException =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(nonStringAccountIdScope, "req-err3-type")));
    assertEquals("CONNECT_SCOPE_INVALID", nonStringClaimException.getCode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"101", "not-a-uuid", "4C4B57D8-E3A2-48FE-9977-E7DF0FDCE901"})
  void issueConnectTokenRejectsNoncanonicalRealmIdClaims(String realmId) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
                account.getAccountUuid().toString(),
                Map.ofEntries(
                    Map.entry("aud", "bootstrap-connect-scope"),
                    Map.entry("accountId", account.getAccountUuid().toString()),
                    Map.entry("tenantId", "7"),
                    Map.entry("realmId", realmId),
                    Map.entry("worldSlug", "demo"),
                    Map.entry("realmSlug", "production"),
                    Map.entry("playableStateNamespaceId", PLAYABLE_STATE_NAMESPACE_ID),
                    Map.entry("playableStateScope", "SHARED"),
                    Map.entry("gameInstanceId", "44"),
                    Map.entry("catalogRevision", "23"),
                    Map.entry("pointerVersion", "17"),
                    Map.entry("evaluatedAt", java.time.Instant.now().toString()),
                    Map.entry(
                        "connectScopeExpiresAt",
                        java.time.Instant.now().plusSeconds(3600).toString()),
                    Map.entry("jti", "invalid-realm-id")));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(malformedConnectScopeId, "req-invalid-realm-id")));

    assertEquals("CONNECT_SCOPE_INVALID", ex.getCode());
    verifyNoInteractions(accountConnectScopeRepository);
  }

  @Test
  void issueConnectTokenRejectsConnectScopeAccountSubjectMismatch() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    String otherAccountUuid = UUID.randomUUID().toString();
    String malformedConnectScopeId =
        new JwtUtil(JWT_SECRET, 120000L)
            .generateToken(
                otherAccountUuid,
                Map.of(
                    "aud",
                    "bootstrap-connect-scope",
                    "accountId",
                    account.getAccountUuid().toString(),
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
  void issueConnectTokenRejectsScopeBoundToAnotherPersistedAccountUuid() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    Account otherAccount = new Account();
    otherAccount.setId(12L);
    setPersistedAuthenticationIdentity(otherAccount);
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    java.time.Instant evaluatedAt = java.time.Instant.now();
    String otherAccountUuid = otherAccount.getAccountUuid().toString();
    String otherAccountScope =
        new JwtUtil(JWT_SECRET, 120000L)
            .generateToken(
                otherAccountUuid,
                Map.ofEntries(
                    Map.entry("aud", "bootstrap-connect-scope"),
                    Map.entry("accountId", otherAccountUuid),
                    Map.entry("tenantId", "7"),
                    Map.entry("realmId", REALM_ID),
                    Map.entry("worldSlug", "demo"),
                    Map.entry("realmSlug", "production"),
                    Map.entry("playableStateNamespaceId", "production-namespace-7"),
                    Map.entry("playableStateScope", "SHARED"),
                    Map.entry("gameInstanceId", "44"),
                    Map.entry("catalogRevision", "23"),
                    Map.entry("pointerVersion", "17"),
                    Map.entry("evaluatedAt", evaluatedAt.toString()),
                    Map.entry("connectScopeExpiresAt", evaluatedAt.plusSeconds(60).toString()),
                    Map.entry("jti", "other-account-scope")));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(), new ConnectTokenRequest(otherAccountScope, "req")));

    assertEquals("CONNECT_SCOPE_MISMATCH", ex.getCode());
    verify(accountRepository).findByAccountUuid(account.getAccountUuid());
    verify(accountRepository).findByAccountUuid(otherAccount.getAccountUuid());
    verifyNoInteractions(accountTenantMembershipRepository, accountConnectScopeRepository);
  }

  @Test
  void issueConnectTokenRejectsBlankWorldSlugConnectScopeClaims() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
                account.getAccountUuid().toString(),
                Map.of(
                    "aud",
                    "bootstrap-connect-scope",
                    "accountId",
                    account.getAccountUuid().toString(),
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
    setPersistedAuthenticationIdentity(account);
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
                account.getAccountUuid().toString(),
                Map.of(
                    "aud",
                    "bootstrap-connect-scope",
                    "accountId",
                    account.getAccountUuid().toString(),
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
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));

    AuthenticationResult result =
        service.authenticateForGameplay("  DEMO@example.com ", "password");

    assertNotNull(result.authToken());
    assertEquals(account.getAccountUuid().toString(), result.accountId());
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
    setPersistedAuthenticationIdentity(account);
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    AuthenticationResult result = service.authenticateForGameplay("demo@example.com", "password");

    assertEquals(account.getAccountUuid().toString(), result.accountId());
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void issueDirectTextConnectScopeDoesNotMintScopeWithoutUniqueEntitlement(boolean ambiguous) {
    Subscription active = new Subscription();
    active.setId(31L);
    active.setTenantId(7L);
    active.setStatus("active");
    Subscription duplicate = new Subscription();
    duplicate.setId(32L);
    duplicate.setTenantId(7L);
    duplicate.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L))
        .thenReturn(ambiguous ? java.util.List.of(active, duplicate) : java.util.List.of());

    AuthenticationException exception =
        assertThrows(AuthenticationException.class, this::issueDirectTextConnectScopeForTest);

    assertEquals("ENTITLEMENT_UNAVAILABLE", exception.getCode());
    verifyNoInteractions(accountConnectScopeRepository);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void issueDirectTextConnectScopeDoesNotMintScopeWhenEntitlementReadIsUnavailable(
      boolean jooqFailure) {
    RuntimeException cause =
        jooqFailure
            ? new org.jooq.exception.DataAccessException("subscription store unavailable")
            : new DataAccessResourceFailureException("subscription store unavailable");
    when(subscriptionRepository.findByTenantId(7L)).thenThrow(cause);

    AuthenticationException exception =
        assertThrows(AuthenticationException.class, this::issueDirectTextConnectScopeForTest);

    assertEquals("ENTITLEMENT_UNAVAILABLE", exception.getCode());
    assertSame(cause, exception.getCause());
    verifyNoInteractions(accountConnectScopeRepository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"illegal-state", "jooq-mapping", "jooq-configuration"})
  void issueDirectTextConnectScopePropagatesUnexpectedRuntimeFailureWithoutMintingScope(
      String failureType) {
    RuntimeException cause = unexpectedEntitlementFailure(failureType);
    when(subscriptionRepository.findByTenantId(7L)).thenThrow(cause);

    RuntimeException propagated =
        assertThrows(RuntimeException.class, this::issueDirectTextConnectScopeForTest);

    assertSame(cause, propagated);
    verifyNoInteractions(accountConnectScopeRepository);
  }

  @Test
  void issueDirectTextConnectScopeDoesNotMintScopeWhenGameplayIsUnavailable() {
    Subscription canceled = new Subscription();
    canceled.setId(31L);
    canceled.setTenantId(7L);
    canceled.setStatus("canceled");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(canceled));

    AuthenticationException exception =
        assertThrows(AuthenticationException.class, this::issueDirectTextConnectScopeForTest);

    assertEquals("TENANT_BILLING_BLOCKED", exception.getCode());
    verifyNoInteractions(accountConnectScopeRepository);
  }

  @Test
  void issueDirectTextConnectScopeRejectsNonPositiveEntitlementVersion() {
    Subscription active = new Subscription();
    active.setId(31L);
    active.setTenantId(7L);
    active.setStatus("active");
    active.setEntitlementVersion(0L);
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    AuthenticationException exception =
        assertThrows(AuthenticationException.class, this::issueDirectTextConnectScopeForTest);

    assertEquals("ENTITLEMENT_UNAVAILABLE", exception.getCode());
    verifyNoInteractions(accountConnectScopeRepository);
  }

  @Test
  void issueDirectTextConnectScopeAllowsDiscoveryWhenPublicJoiningIsDisabled() {
    Subscription grace = new Subscription();
    grace.setId(31L);
    grace.setTenantId(7L);
    grace.setStatus("grace");
    grace.setEntitlementVersion(4L);
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(grace));

    DirectTextJoinScope scope = issueDirectTextConnectScopeForTest();

    assertNotNull(scope.connectScopeId());
    assertFalse(scope.connectScopeId().isBlank());
    verify(subscriptionRepository).findByTenantId(7L);
    verify(accountConnectScopeRepository)
        .insert(org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class));
  }

  @Test
  void issueDirectTextConnectScopeRejectsUnavailableEntitlementWithoutRetainingScope() {
    Account account = directTextAccount();
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of());

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.issueDirectTextConnectScope(directTextCaller(), directTextTarget()));

    assertEquals("ENTITLEMENT_UNAVAILABLE", exception.getCode());
    org.mockito.Mockito.verify(accountConnectScopeRepository, org.mockito.Mockito.never())
        .insert(org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void issueDirectTextConnectScopeRejectsBlankRequestIdBeforeAuthorityOrScopeWork(
      String requestId) {
    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            11L,
            7L,
            UUID.fromString(REALM_ID),
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            44L,
            "session-1",
            requestId);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.issueDirectTextConnectScope(caller, directTextTarget()));

    assertEquals("CONNECT_SCOPE_INVALID", exception.getCode());
    verifyNoInteractions(accountRepository, subscriptionRepository, accountConnectScopeRepository);
  }

  @Test
  void issueDirectTextConnectScopeRejectsBillingBlockedEntitlementWithoutRetainingScope() {
    Account account = directTextAccount();
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    Subscription canceled = new Subscription();
    canceled.setId(31L);
    canceled.setTenantId(7L);
    canceled.setStatus("canceled");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(canceled));

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.issueDirectTextConnectScope(directTextCaller(), directTextTarget()));

    assertEquals("TENANT_BILLING_BLOCKED", exception.getCode());
    assertEquals("Gameplay is not available for this tenant", exception.getMessage());
    org.mockito.Mockito.verify(accountConnectScopeRepository, org.mockito.Mockito.never())
        .insert(org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class));
  }

  @Test
  void issueDirectTextConnectScopeRetainsScopeForActiveEntitlement() {
    Account account = directTextAccount();
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));

    DirectTextJoinScope result =
        service.issueDirectTextConnectScope(directTextCaller(), directTextTarget());

    assertNotNull(result.connectScopeId());
    assertNotNull(result.connectScopeExpiresAt());
    org.mockito.Mockito.verify(accountConnectScopeRepository)
        .insert(org.mockito.ArgumentMatchers.any(VerifiedJoinScope.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void getTenantEntitlementsForRuntimeTreatsDataAccessFailuresAsUnavailable(boolean jooqFailure) {
    RuntimeException cause =
        jooqFailure
            ? new org.jooq.exception.DataAccessException("subscription store unavailable")
            : new DataAccessResourceFailureException("subscription store unavailable");
    when(subscriptionRepository.findByTenantId(7L)).thenThrow(cause);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.getTenantEntitlementsForRuntime(7L, "req-entitlement-store-failure"));

    assertEquals("ENTITLEMENT_UNAVAILABLE", exception.getCode());
    assertSame(cause, exception.getCause());
  }

  @ParameterizedTest
  @ValueSource(strings = {"illegal-state", "jooq-mapping", "jooq-configuration"})
  void getTenantEntitlementsForRuntimePropagatesUnexpectedRuntimeFailureByIdentity(
      String failureType) {
    RuntimeException cause = unexpectedEntitlementFailure(failureType);
    when(subscriptionRepository.findByTenantId(7L)).thenThrow(cause);

    RuntimeException propagated =
        assertThrows(
            RuntimeException.class,
            () -> service.getTenantEntitlementsForRuntime(7L, "req-entitlement-runtime-failure"));

    assertSame(cause, propagated);
    verifyNoInteractions(accountConnectScopeRepository);
  }

  @Test
  void issueConnectTokenFailsClosedWithoutCompleteSelectedTargetAuthorityEvidence() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(), new ConnectTokenRequest(connectScopeId, "req-3")));

    assertEquals("AUTH_UNAVAILABLE", unavailable.getCode());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.eq(connectScopeId),
            org.mockito.ArgumentMatchers.eq("req-3"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verifyNoInteractions(accountRealmAccessGrantRepository);
  }

  @Test
  void issueConnectTokenRejectsLegacyGrantWithoutLifecycleProof() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    PrivateConnectTokenContext context = preparePrivateConnectTokenContext(account, true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    context.bootstrapToken(),
                    new ConnectTokenRequest(context.connectScopeId(), "req-private-grant")));

    assertEquals("REALM_ACCESS_DENIED", exception.getCode());
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
        .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void issueConnectTokenRejectsNonPublicRealmWhenCurrentGrantIsMissing() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    PrivateConnectTokenContext context = preparePrivateConnectTokenContext(account, false);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    context.bootstrapToken(),
                    new ConnectTokenRequest(context.connectScopeId(), "req-private-grant-denied")));

    assertEquals("REALM_ACCESS_DENIED", exception.getCode());
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
        .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verify(sessionService)
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.eq(context.connectScopeId()),
            org.mockito.ArgumentMatchers.eq("req-private-grant-denied"),
            org.mockito.ArgumentMatchers.argThat(
                replay -> !replay.success() && "REALM_ACCESS_DENIED".equals(replay.errorCode())),
            org.mockito.ArgumentMatchers.longThat(ttl -> ttl > 0L));
  }

  @Test
  void issueConnectTokenRequiresJoinForInactivePublicMembership() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    PrivateConnectTokenContext context = preparePublicInactiveConnectTokenContext(account, true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    context.bootstrapToken(),
                    new ConnectTokenRequest(context.connectScopeId(), "req-inactive-public-join")));

    assertEquals("JOIN_REQUIRED", exception.getCode());
    verifyConnectTokenFailureDoesNotStoreSuccess(
        context.connectScopeId(), "req-inactive-public-join", "JOIN_REQUIRED");
  }

  @Test
  void issueConnectTokenRejectsInactivePublicMembershipWhenJoiningIsDisabled() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    PrivateConnectTokenContext context = preparePublicInactiveConnectTokenContext(account, false);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    context.bootstrapToken(),
                    new ConnectTokenRequest(
                        context.connectScopeId(), "req-inactive-public-policy-denied")));

    assertEquals("PUBLIC_PRODUCTION_ADMISSION_DENIED", exception.getCode());
    verifyConnectTokenFailureDoesNotStoreSuccess(
        context.connectScopeId(),
        "req-inactive-public-policy-denied",
        "PUBLIC_PRODUCTION_ADMISSION_DENIED");
  }

  @Test
  void issueConnectTokenRequiresActiveMembershipForNonPublicRealm() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    PrivateConnectTokenContext context =
        preparePrivateConnectTokenContext(account, true, "INACTIVE");

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    context.bootstrapToken(),
                    new ConnectTokenRequest(context.connectScopeId(), "req-inactive-private")));

    assertEquals("CONNECT_TOKEN_REJECTED", exception.getCode());
    verifyConnectTokenFailureDoesNotStoreSuccess(
        context.connectScopeId(), "req-inactive-private", "CONNECT_TOKEN_REJECTED");
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
        .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
  }

  @Test
  void issueConnectTokenRetriesAfterEntitlementAuthorityRecoversWithoutCachingFailure() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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

    AuthenticationException contextUnavailable =
        assertThrows(
            AuthenticationException.class,
            () -> service.issueConnectToken(bootstrap.bootstrapToken(), request));

    assertEquals("AUTH_UNAVAILABLE", contextUnavailable.getCode());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
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
    setPersistedAuthenticationIdentity(account);
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    setPersistedAuthenticationIdentity(account);
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    setPersistedAuthenticationIdentity(account);
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
  void issueConnectTokenRevalidatesCachedSuccessButFailsClosedWithoutAuthorityEvidence() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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

    ConnectTokenResult legacyCachedResult =
        new ConnectTokenResult(
            11L,
            7L,
            44L,
            "production",
            connectScopeId,
            "legacy-cached-token",
            "legacy-jti",
            "req-replay-1",
            "2026-09-30T00:00:00Z",
            "2026-09-30T00:00:30Z",
            false);
    when(sessionService.getConnectTokenReplay(7L, 11L, connectScopeId, "req-replay-1"))
        .thenReturn(
            Optional.of(new SessionService.ConnectTokenReplay(true, legacyCachedResult, "", "")));

    org.mockito.Mockito.clearInvocations(
        accountTenantMembershipRepository,
        gameSessionClient,
        sessionService,
        subscriptionRepository);
    AuthenticationException currentReplayUnavailable =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-replay-1")));

    assertEquals("AUTH_UNAVAILABLE", currentReplayUnavailable.getCode());
    verify(accountTenantMembershipRepository).findByAccountIdAndTenantId(11L, 7L);
    verify(subscriptionRepository).findByTenantId(7L);
    verifyCachedConnectTokenReplayDidNotWrite();

    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Live Realm")
                .setTenantId("7")
                .setGameInstanceId("99")
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                .setCatalogRevision(23L)
                .setPointerVersion(18L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());
    org.mockito.Mockito.clearInvocations(
        accountTenantMembershipRepository,
        gameSessionClient,
        sessionService,
        subscriptionRepository);

    AuthenticationException staleReplayException =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-replay-1")));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", staleReplayException.getCode());
    verifyCachedConnectTokenReplayDidNotWrite();
  }

  @ParameterizedTest
  @CsvSource({
    "MISSING,JOIN_REQUIRED",
    "INACTIVE,JOIN_REQUIRED",
    "NON_ADMITTING,CONNECT_TOKEN_REJECTED"
  })
  void issueConnectTokenRevalidatesMembershipBeforeCachedSuccessReplay(
      String currentMembershipState, String expectedCode) {
    CachedPublicConnectTokenReplay cached = prepareCachedPublicConnectTokenReplay();
    if ("MISSING".equals(currentMembershipState)) {
      when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
          .thenReturn(Optional.empty());
    } else {
      Account account = new Account();
      account.setId(11L);
      AccountTenantMembership currentMembership = membership(account, 7L);
      if ("INACTIVE".equals(currentMembershipState)) {
        currentMembership.setLifecycleState("INACTIVE");
        currentMembership.setGameplayAdmissionAllowed(false);
      } else {
        currentMembership.setGameplayAdmissionAllowed(false);
      }
      when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
          .thenReturn(Optional.of(currentMembership));
    }
    org.mockito.Mockito.clearInvocations(accountTenantMembershipRepository);
    org.mockito.Mockito.clearInvocations(sessionService);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    cached.bootstrapToken(),
                    new ConnectTokenRequest(cached.connectScopeId(), cached.requestId())));

    assertEquals(expectedCode, exception.getCode());
    verify(accountTenantMembershipRepository).findByAccountIdAndTenantId(11L, 7L);
    verifyCachedConnectTokenReplayDidNotWrite();
  }

  @ParameterizedTest
  @CsvSource({"BLOCKED,TENANT_BILLING_BLOCKED", "UNAVAILABLE,ENTITLEMENT_UNAVAILABLE"})
  void issueConnectTokenRevalidatesEntitlementBeforeCachedSuccessReplay(
      String currentEntitlementState, String expectedCode) {
    CachedPublicConnectTokenReplay cached = prepareCachedPublicConnectTokenReplay();
    if ("BLOCKED".equals(currentEntitlementState)) {
      Subscription canceled = new Subscription();
      canceled.setId(22L);
      canceled.setTenantId(7L);
      canceled.setStatus("canceled");
      when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(canceled));
    } else {
      when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of());
    }
    org.mockito.Mockito.clearInvocations(accountTenantMembershipRepository);
    org.mockito.Mockito.clearInvocations(sessionService);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    cached.bootstrapToken(),
                    new ConnectTokenRequest(cached.connectScopeId(), cached.requestId())));

    assertEquals(expectedCode, exception.getCode());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .findByAccountIdAndTenantId(11L, 7L);
    verifyCachedConnectTokenReplayDidNotWrite();
  }

  @Test
  void issueConnectTokenRejectsCachedLegacySuccessForNonPublicRealm() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    PrivateConnectTokenContext context = preparePrivateConnectTokenContext(account, true);
    ConnectTokenResult cachedResult =
        new ConnectTokenResult(
            11L,
            7L,
            55L,
            "preview",
            context.connectScopeId(),
            "cached-private-connect-token",
            "cached-jti",
            "req-private-cached",
            "2026-09-30T00:00:00Z",
            "2026-09-30T00:00:30Z",
            false);
    when(sessionService.getConnectTokenReplay(
            7L, 11L, context.connectScopeId(), "req-private-cached"))
        .thenReturn(Optional.of(new SessionService.ConnectTokenReplay(true, cachedResult, "", "")));

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    context.bootstrapToken(),
                    new ConnectTokenRequest(context.connectScopeId(), "req-private-cached")));

    assertEquals("REALM_ACCESS_DENIED", exception.getCode());
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
        .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
  }

  @Test
  void issueConnectTokenDoesNotReplayCachedLegacySuccess() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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

    ConnectTokenResult legacyCachedResult =
        new ConnectTokenResult(
            11L,
            7L,
            44L,
            "production",
            connectScopeId,
            "legacy-cached-token",
            "legacy-jti",
            "req-replay-1",
            "2026-09-30T00:00:00Z",
            "2026-09-30T00:00:30Z",
            false);
    when(sessionService.getConnectTokenReplay(7L, 11L, connectScopeId, "req-replay-1"))
        .thenReturn(
            Optional.of(new SessionService.ConnectTokenReplay(true, legacyCachedResult, "", "")));

    AuthenticationException staleReplayException =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-replay-1")));

    assertEquals("AUTH_UNAVAILABLE", staleReplayException.getCode());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void issueConnectTokenRequiresExplicitPublicProductionMembership() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    setPersistedAuthenticationIdentity(account);
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

  @ParameterizedTest
  @CsvSource({"active, JOIN_REQUIRED", "grace, PUBLIC_PRODUCTION_ADMISSION_DENIED"})
  void issueConnectTokenClassifiesInactivePublicMembershipByJoinPolicy(
      String subscriptionStatus, String expectedCode) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    AccountTenantMembership inactiveMembership = membership(account, 7L);
    inactiveMembership.setLifecycleState("INACTIVE");
    inactiveMembership.setGameplayAdmissionAllowed(false);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(inactiveMembership));
    Subscription subscription = new Subscription();
    subscription.setId(22L);
    subscription.setTenantId(7L);
    subscription.setStatus(subscriptionStatus);
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(subscription));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    // Current INACTIVE membership suppresses new discovery, so exercise the selected-target check
    // with a valid signed scope retained from an earlier eligible snapshot.
    String connectScopeId =
        retainedConnectScopeForTest(
            account,
            7L,
            REALM_ID,
            "demo",
            "production",
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            44L,
            23L,
            17L);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    bootstrap.bootstrapToken(),
                    new ConnectTokenRequest(connectScopeId, "req-inactive-public")));

    assertEquals(expectedCode, exception.getCode());
    if ("JOIN_REQUIRED".equals(expectedCode)) {
      assertEquals(
          "Join the selected world before requesting a connect token", exception.getMessage());
    } else {
      assertEquals("Public joining is not allowed for the selected game", exception.getMessage());
    }
  }

  @Test
  void issueConnectTokenClassifiesKnownBillingDenialAndReplaysItDeterministically() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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

  @ParameterizedTest
  @CsvSource({"true,production", "false,preview"})
  void issueConnectTokenRejectsPresentNonAdmittingMembership(
      boolean publicProductionRealm, String realmSlug) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    if (!publicProductionRealm) {
      PrivateConnectTokenContext context = preparePrivateConnectTokenContext(account, true);
      AccountTenantMembership deniedMembership = membership(account, 7L);
      deniedMembership.setGameplayAdmissionAllowed(false);
      when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
          .thenReturn(Optional.of(deniedMembership));

      AuthenticationException privateException =
          assertThrows(
              AuthenticationException.class,
              () ->
                  service.issueConnectToken(
                      context.bootstrapToken(),
                      new ConnectTokenRequest(context.connectScopeId(), "req-membership-denied")));

      assertEquals("CONNECT_TOKEN_REJECTED", privateException.getCode());
      assertEquals(
          "Gameplay admission is not allowed for this account", privateException.getMessage());
      org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
          .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
      org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
          .storeSession(
              org.mockito.ArgumentMatchers.anyLong(),
              org.mockito.ArgumentMatchers.anyLong(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyLong());
      return;
    }
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    AccountTenantMembership deniedMembership = membership(account, 7L);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(deniedMembership));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug(realmSlug)
                    .setDisplayName("Preview Realm")
                    .setTenantId("7")
                    .setGameInstanceId("55")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                    .setCatalogRevision(23L)
                    .setPointerVersion(19L)
                    .setVisible(true)
                    .setPublicProductionRealm(publicProductionRealm)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", realmSlug))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug(realmSlug)
                .setRealmDisplayName("Preview Realm")
                .setTenantId("7")
                .setGameInstanceId("55")
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                .setCatalogRevision(23L)
                .setPointerVersion(19L)
                .setVisible(true)
                .setPublicProductionRealm(publicProductionRealm)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());
    when(accountRealmAccessGrantRepository.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", realmSlug))
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
    setPersistedAuthenticationIdentity(account);
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    setPersistedAuthenticationIdentity(account);
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
  void listBootstrapCharactersDistinguishesMissingAndNonAdmittingMembership(
      boolean membershipExists) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    if (membershipExists) {
      AccountTenantMembership nonAdmittingMembership = membership(account, 7L);
      nonAdmittingMembership.setGameplayAdmissionAllowed(false);
      AccountTenantMembership admittingMembership = membership(account, 7L);
      when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
          .thenReturn(Optional.of(admittingMembership), Optional.of(nonAdmittingMembership));
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

    if (membershipExists) {
      assertEquals("CONNECT_TOKEN_REJECTED", exception.getCode());
      assertEquals("Gameplay admission is not allowed for this account", exception.getMessage());
    } else {
      assertEquals("JOIN_REQUIRED", exception.getCode());
      assertEquals("Join the selected world before discovering characters", exception.getMessage());
    }
    verifyNoInteractions(entityManagementClient);
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .saveAndFlush(org.mockito.ArgumentMatchers.any(AccountTenantMembership.class));
  }

  @Test
  void listBootstrapCharactersChecksPublicJoinPolicyBeforeReturningJoinRequired() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
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
                service.listBootstrapCharacters(
                    bootstrap.bootstrapToken(), "demo", "production", connectScopeId));

    assertEquals("PUBLIC_PRODUCTION_ADMISSION_DENIED", exception.getCode());
    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(subscriptionRepository, accountTenantMembershipRepository);
    order.verify(subscriptionRepository, org.mockito.Mockito.times(2)).findByTenantId(7L);
    order.verify(accountTenantMembershipRepository).findByAccountIdAndTenantId(11L, 7L);
    verifyNoInteractions(entityManagementClient);
  }

  @ParameterizedTest
  @CsvSource({"active, JOIN_REQUIRED", "grace, PUBLIC_PRODUCTION_ADMISSION_DENIED"})
  void listBootstrapCharactersClassifiesInactivePublicMembershipByJoinPolicy(
      String subscriptionStatus, String expectedCode) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    AccountTenantMembership inactiveMembership = membership(account, 7L);
    inactiveMembership.setLifecycleState("INACTIVE");
    inactiveMembership.setGameplayAdmissionAllowed(false);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(inactiveMembership));
    Subscription subscription = new Subscription();
    subscription.setId(22L);
    subscription.setTenantId(7L);
    subscription.setStatus(subscriptionStatus);
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(subscription));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    // Current INACTIVE membership suppresses new discovery, so exercise the selected-target check
    // with a valid signed scope retained from an earlier eligible snapshot.
    String connectScopeId =
        retainedConnectScopeForTest(
            account,
            7L,
            REALM_ID,
            "demo",
            "production",
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            44L,
            23L,
            17L);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.listBootstrapCharacters(
                    bootstrap.bootstrapToken(), "demo", "production", connectScopeId));

    assertEquals(expectedCode, exception.getCode());
    if ("JOIN_REQUIRED".equals(expectedCode)) {
      assertEquals("Join the selected world before discovering characters", exception.getMessage());
    } else {
      assertEquals("Public joining is not allowed for the selected game", exception.getMessage());
    }
    verifyNoInteractions(entityManagementClient);
  }

  @Test
  void listBootstrapCharactersKeepsSelectedTargetFailClosedWhenEntitlementsAreUnavailable() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    Subscription active = new Subscription();
    active.setId(22L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L))
        .thenReturn(java.util.List.of(active), java.util.List.of());

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

    assertEquals("ENTITLEMENT_UNAVAILABLE", exception.getCode());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.times(1))
        .findByAccountIdAndTenantId(11L, 7L);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(2))
        .findByTenantId(7L);
    verifyNoInteractions(entityManagementClient);
  }

  @Test
  void listBootstrapCharactersDoesNotUsePublicJoinForPrivateMembershipLostAfterDiscovery() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    PrivateConnectTokenContext context = preparePrivateConnectTokenContext(account, true);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.listBootstrapCharacters(
                    context.bootstrapToken(), "demo", "preview", context.connectScopeId()));

    assertEquals("NON_PUBLIC_ENROLLMENT_REQUIRED", exception.getCode());
    assertEquals(
        "Existing game membership is required for this non-public realm", exception.getMessage());
    verifyNoInteractions(entityManagementClient);
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
        .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
  }

  @Test
  void
      listBootstrapCharactersDoesNotUsePublicJoinForPrivateMembershipLostAfterReachableDiscovery() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());
    var privateRealm =
        net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setDisplayName("Private Realm")
            .setTenantId("7")
            .setGameInstanceId("44")
            .setRealmId(REALM_ID)
            .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
            .setCatalogRevision(23L)
            .setPointerVersion(17L)
            .setVisible(true)
            .setPublicProductionRealm(false)
            .setRequiresCharacterSelection(false)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .build();
    when(gameSessionClient.listGameplayRealms("demo")).thenReturn(java.util.List.of(privateRealm));
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("demo")
                .setWorldDisplayName("Demo World")
                .setRealmSlug("production")
                .setRealmDisplayName("Private Realm")
                .setTenantId("7")
                .setGameInstanceId("44")
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                .setCatalogRevision(23L)
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(false)
                .setRequiresCharacterSelection(false)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    // The retained scope names a realm whose slug is "production", but its pointer is explicitly
    // private. A missing membership must not inherit public JOIN behavior from the slug.
    String connectScopeId =
        retainedConnectScopeForTest(
            account,
            7L,
            REALM_ID,
            "demo",
            "production",
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            44L,
            23L,
            17L);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.listBootstrapCharacters(
                    bootstrap.bootstrapToken(), "demo", "production", connectScopeId));

    assertEquals("NON_PUBLIC_ENROLLMENT_REQUIRED", exception.getCode());
    verifyNoInteractions(entityManagementClient);
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
        .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "production");
  }

  @Test
  void listBootstrapCharactersRejectsLegacyGrantWithoutLifecycleProof() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    PrivateConnectTokenContext context = preparePrivateConnectTokenContext(account, true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.listBootstrapCharacters(
                    context.bootstrapToken(), "demo", "preview", context.connectScopeId()));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
    verifyNoInteractions(entityManagementClient);
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
        .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
  }

  @Test
  void listBootstrapCharactersRejectsAmbiguousRealmBeforeAdmissionFiltering() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
            .setRealmId(REALM_ID)
            .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    setPersistedAuthenticationIdentity(account);
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
            .setRealmId(REALM_ID)
            .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    setPersistedAuthenticationIdentity(account);
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
    assertEquals(REALM_ID, realms.getFirst().realmId());
    assertEquals(
        REALM_ID,
        new JwtUtil(JWT_SECRET, 300000L)
            .parseToken(realms.getFirst().connectScopeId())
            .getPayload()
            .get("realmId"));
    assertEquals("SHARED", realms.getFirst().stateScope());
    assertEquals(23L, realms.getFirst().catalogRevision());
    assertEquals(PLAYABLE_STATE_NAMESPACE_ID, realms.getFirst().playableStateNamespaceId());
    assertEquals("PLAYABLE_STATE_SCOPE_SHARED", realms.getFirst().playableStateScope());
    assertEquals("ALLOW_NEW", realms.getFirst().characterCreationPolicy());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "shared-live",
        "C6ED6A44-C7E7-4F18-81FC-078A74E67C07",
        "c6ed6a44c7e74f1881fc078a74e67c07"
      })
  void listBootstrapRealmsRejectsNoncanonicalPlayableStateNamespaceIdFromReachableRealm(
      String namespaceId) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
                    .setGameInstanceId("44")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(namespaceId)
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

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
    verifyNoInteractions(accountConnectScopeRepository);
  }

  @Test
  void listBootstrapRealmsReadsFreshEntitlementsOncePerTenantPerInvocation() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setDisplayName("Live Realm")
                    .setTenantId("7")
                    .setGameInstanceId("44")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build(),
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("preview")
                    .setDisplayName("Private Preview Realm")
                    .setTenantId("7")
                    .setGameInstanceId("45")
                    .setRealmId("ce814357-64ad-44e8-b004-828a9ae37c13")
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                    .setCatalogRevision(23L)
                    .setPointerVersion(18L)
                    .setVisible(true)
                    .setPublicProductionRealm(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    var firstCall = service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo");
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(1))
        .findByTenantId(7L);
    var secondCall = service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo");

    assertEquals(1, firstCall.size());
    assertEquals("production", firstCall.getFirst().realmSlug());
    assertEquals(1, secondCall.size());
    assertEquals("production", secondCall.getFirst().realmSlug());
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(2))
        .findByTenantId(7L);
    verifyNoInteractions(accountRealmAccessGrantRepository);
  }

  @Test
  void listBootstrapWorldsReadsFreshEntitlementsOncePerTenantPerInvocation() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(gameSessionClient.listGameplayWorlds())
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayWorld.newBuilder()
                    .setWorldSlug("demo")
                    .setDisplayName("Demo World")
                    .build(),
                net.firedevops.firemud.gamesession.v1.GameplayWorld.newBuilder()
                    .setWorldSlug("sandbox")
                    .setDisplayName("Sandbox World")
                    .build()));
    when(gameSessionClient.listGameplayRealms("sandbox"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("sandbox")
                    .setRealmSlug("production")
                    .setDisplayName("Sandbox Realm")
                    .setTenantId("7")
                    .setGameInstanceId("45")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                    .setCatalogRevision(23L)
                    .setPointerVersion(18L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    var firstCall = service.listBootstrapWorlds(bootstrap.bootstrapToken());
    var secondCall = service.listBootstrapWorlds(bootstrap.bootstrapToken());

    assertEquals(2, firstCall.size());
    assertEquals(2, secondCall.size());
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(2))
        .findByTenantId(7L);
  }

  @Test
  void listBootstrapWorldsOmitsCanceledTenantAndFailsWhenEntitlementIsUnavailable() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    Subscription activeDemo = new Subscription();
    activeDemo.setId(1L);
    activeDemo.setTenantId(7L);
    activeDemo.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(activeDemo));
    when(gameSessionClient.listGameplayWorlds())
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayWorld.newBuilder()
                    .setWorldSlug("demo")
                    .setDisplayName("Demo World")
                    .build(),
                net.firedevops.firemud.gamesession.v1.GameplayWorld.newBuilder()
                    .setWorldSlug("sandbox")
                    .setDisplayName("Sandbox World")
                    .build()));
    when(gameSessionClient.listGameplayRealms("sandbox"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("sandbox")
                    .setRealmSlug("production")
                    .setDisplayName("Sandbox Realm")
                    .setTenantId("8")
                    .setGameInstanceId("45")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID_TENANT_8)
                    .setCatalogRevision(23L)
                    .setPointerVersion(18L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    Subscription canceledSandbox = new Subscription();
    canceledSandbox.setId(2L);
    canceledSandbox.setTenantId(8L);
    canceledSandbox.setStatus("canceled");
    when(subscriptionRepository.findByTenantId(8L)).thenReturn(java.util.List.of(canceledSandbox));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    var worldsWithCanceledTenant = service.listBootstrapWorlds(bootstrap.bootstrapToken());

    assertEquals(
        java.util.List.of("demo"),
        worldsWithCanceledTenant.stream().map(world -> world.worldSlug()).toList());

    when(subscriptionRepository.findByTenantId(8L)).thenReturn(java.util.List.of());
    AuthenticationException unavailableSandbox =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(bootstrap.bootstrapToken()));

    assertEquals("ENTITLEMENT_UNAVAILABLE", unavailableSandbox.getCode());

    Subscription activeSandbox = new Subscription();
    activeSandbox.setId(3L);
    activeSandbox.setTenantId(8L);
    activeSandbox.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of());
    when(subscriptionRepository.findByTenantId(8L)).thenReturn(java.util.List.of(activeSandbox));

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(bootstrap.bootstrapToken()));

    assertEquals("ENTITLEMENT_UNAVAILABLE", unavailable.getCode());
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(3))
        .findByTenantId(7L);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(2))
        .findByTenantId(8L);
  }

  @Test
  void listBootstrapRealmsPropagatesUnavailableEntitlements() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of());

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));

    assertEquals("ENTITLEMENT_UNAVAILABLE", exception.getCode());
  }

  @Test
  void listBootstrapWorldsFailsInsteadOfReturningIncompleteWorldDiscovery() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(gameSessionClient.listGameplayWorlds())
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayWorld.newBuilder()
                    .setWorldSlug("demo")
                    .setDisplayName("Demo World")
                    .build(),
                net.firedevops.firemud.gamesession.v1.GameplayWorld.newBuilder()
                    .setWorldSlug("sandbox")
                    .setDisplayName("Sandbox World")
                    .build()));
    when(gameSessionClient.listGameplayRealms("sandbox"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("sandbox")
                    .setRealmSlug("production")
                    .setDisplayName("Sandbox Realm")
                    .setTenantId("8")
                    .setGameInstanceId("45")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID_TENANT_8)
                    .setCatalogRevision(23L)
                    .setPointerVersion(18L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of());

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(bootstrap.bootstrapToken()));

    assertEquals("ENTITLEMENT_UNAVAILABLE", unavailable.getCode());
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.times(1))
        .findByTenantId(7L);
    org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.never())
        .findByTenantId(8L);
  }

  @Test
  void listBootstrapRealmsRethrowsOtherAuthenticationErrors() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(gameSessionClient.listGameplayRealms("demo"))
        .thenReturn(
            java.util.List.of(
                net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("preview")
                    .setDisplayName("Private Preview Realm")
                    .setTenantId("7")
                    .setGameInstanceId("45")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                    .setCatalogRevision(23L)
                    .setPointerVersion(18L)
                    .setVisible(true)
                    .setPublicProductionRealm(false)
                    .setStateScope("SHARED")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));
    AuthenticationException authorityError =
        new AuthenticationException("AUTH_UNAVAILABLE", "Membership authority is unavailable");
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenThrow(authorityError);

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));

    assertEquals("AUTH_UNAVAILABLE", exception.getCode());
  }

  @Test
  void listBootstrapRealmsRejectsRealmWithMalformedTenantId() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
  }

  @Test
  void listBootstrapRealmsRejectsRealmWithUnknownStateScope() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
                    .setGameInstanceId("44")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                    .setCatalogRevision(23L)
                    .setPointerVersion(17L)
                    .setVisible(true)
                    .setPublicProductionRealm(true)
                    .setRequiresCharacterSelection(false)
                    .setStateScope("UNKNOWN")
                    .setCharacterCreationPolicy("ALLOW_NEW")
                    .build()));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo"));

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", exception.getCode());
  }

  @Test
  void listBootstrapCharactersUsesIsolatedRealmRoster() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    setPersistedAuthenticationIdentity(account);
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    setPersistedAuthenticationIdentity(account);
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    setPersistedAuthenticationIdentity(account);
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    setPersistedAuthenticationIdentity(account);
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID_TENANT_8)
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
    setPersistedAuthenticationIdentity(account);
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
  void listBootstrapCharactersRejectsChangedCanonicalRealmId() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    net.firedevops.firemud.gamesession.v1.GameplayRealm changedRealm =
        gameSessionClient.listGameplayRealms("demo").getFirst().toBuilder()
            .setRealmId("57c58f36-c5ea-4aa8-8ef7-91a45e407f01")
            .build();
    when(gameSessionClient.listGameplayRealms("demo")).thenReturn(java.util.List.of(changedRealm));
    net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer changedAdmissionPointer =
        gameSessionClient.getAdmissionPointer(7L, "demo", "production").toBuilder()
            .setRealmId("57c58f36-c5ea-4aa8-8ef7-91a45e407f01")
            .build();
    when(gameSessionClient.getAdmissionPointer(7L, "demo", "production"))
        .thenReturn(changedAdmissionPointer);

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.listBootstrapCharacters(
                    bootstrap.bootstrapToken(), "demo", "production", connectScopeId));

    assertEquals("CONNECT_SCOPE_MISMATCH", ex.getCode());
    verifyNoInteractions(entityManagementClient);
  }

  @Test
  void listBootstrapRealmsExcludesNonPublicRealmWithoutGrant() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
    setPersistedAuthenticationIdentity(account);
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
  void listBootstrapRealmsDoesNotUseLegacyGrantAsLifecycleProof(
      boolean visible, boolean publicProductionRealm) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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

    assertTrue(realms.isEmpty());
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
        .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
  }

  @ParameterizedTest
  @CsvSource({"true", "false"})
  void listBootstrapRealmsExcludesInactiveMembershipDespiteAdmissionFlag(
      boolean publicProductionRealm) {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    AccountTenantMembership inactiveMembership = membership(account, 7L);
    inactiveMembership.setLifecycleState("INACTIVE");
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(inactiveMembership));
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                    .setCatalogRevision(23L)
                    .setPointerVersion(19L)
                    .setVisible(true)
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
                .setRealmId(REALM_ID)
                .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
                .setCatalogRevision(23L)
                .setPointerVersion(19L)
                .setVisible(true)
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
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
        .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
  }

  @Test
  void issueConnectTokenRequiresMembershipForPrivateTargetDespiteLegacyGrant() {
    Account account = new Account();
    account.setId(11L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    PrivateConnectTokenContext context = preparePrivateConnectTokenContext(account, true);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());

    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueConnectToken(
                    context.bootstrapToken(),
                    new ConnectTokenRequest(context.connectScopeId(), "req-preview-1")));

    assertEquals("NON_PUBLIC_ENROLLMENT_REQUIRED", exception.getCode());
    assertEquals(
        "Existing game membership is required for this non-public realm", exception.getMessage());
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository, org.mockito.Mockito.never())
        .existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
    org.mockito.Mockito.verify(sessionService)
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.eq(context.connectScopeId()),
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
  void grantRealmAccessFailsClosedForRepeatedAndChangedLegacyRequests() {
    RealmAccessGrantRequest originalRequest =
        new RealmAccessGrantRequest(
            11L, 7L, "demo", "preview", "operator", "preview access", "req-grant-1");
    RealmAccessGrantRequest changedRequest =
        new RealmAccessGrantRequest(
            11L, 7L, "demo", "preview", "different operator", "changed reason", "req-grant-1");

    for (RealmAccessGrantRequest request :
        java.util.List.of(originalRequest, originalRequest, changedRequest)) {
      AuthenticationException exception =
          assertThrows(AuthenticationException.class, () -> service.grantRealmAccess(request));
      assertEquals("AUTH_UNAVAILABLE", exception.getCode());
      assertEquals(
          "Lifecycle-qualified realm-grant mutations are unavailable", exception.getMessage());
    }

    verifyNoInteractions(accountRepository, accountRealmAccessGrantRepository);
  }

  @Test
  void revokeRealmAccessFailsClosedForRepeatedAndChangedLegacyRequests() {
    for (long accountId : java.util.List.of(11L, 11L, 12L)) {
      AuthenticationException exception =
          assertThrows(
              AuthenticationException.class,
              () -> service.revokeRealmAccess(accountId, 7L, "demo", "preview"));
      assertEquals("AUTH_UNAVAILABLE", exception.getCode());
      assertEquals(
          "Lifecycle-qualified realm-grant mutations are unavailable", exception.getMessage());
    }

    verifyNoInteractions(accountRepository, accountRealmAccessGrantRepository);
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
    setPersistedAuthenticationIdentity(account);
    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setTenantId(1L);
    profile.setDisplayName("demo");
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));
    when(profileMapper.toDto(profile, account.getAccountUuid().toString()))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                1L,
                1L,
                account.getAccountUuid().toString(),
                "demo",
                null,
                ProfilePresenceVisibilityPolicy.FRIENDS_ONLY));

    var dto = service.getProfile(1L, 2L);

    assertEquals("demo", dto.displayName());
    assertEquals(account.getAccountUuid().toString(), dto.accountId());
  }

  @Test
  void profileReadsRejectContradictoryExactAccountLookupBeforeReadingProfiles() {
    Account contradictoryAccount = new Account();
    contradictoryAccount.setId(3L);
    setPersistedAuthenticationIdentity(contradictoryAccount);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(contradictoryAccount));

    assertThrows(IllegalStateException.class, () -> service.getProfile(1L, 2L));
    assertThrows(IllegalStateException.class, () -> service.exportAccountData(2L));

    verifyNoInteractions(accountTenantMembershipRepository, profileRepository, profileMapper);
  }

  @Test
  void getProfileRejectsForeignAccountRelationBeforeMapping() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    Account foreignAccount = new Account();
    foreignAccount.setId(3L);
    Profile profile = new Profile();
    profile.setAccount(foreignAccount);
    profile.setTenantId(1L);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));

    assertThrows(IllegalStateException.class, () -> service.getProfile(1L, 2L));

    verifyNoInteractions(profileMapper);
  }

  @Test
  void listPresenceVisibilityPoliciesOmitsProfilesWithoutAPolicy() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setTenantId(1L);
    profile.setPresenceVisibilityPolicy(null);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByTenantIdAndAccountIds(1L, java.util.List.of(2L)))
        .thenReturn(java.util.List.of(profile));

    Map<String, ProfilePresenceVisibilityPolicy> policies =
        service.listPresenceVisibilityPolicies(
            1L, java.util.List.of(account.getAccountUuid().toString()));

    assertTrue(policies.isEmpty());
  }

  @Test
  void listPresenceVisibilityPoliciesKeysResultsByVerifiedAccountUuid() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setTenantId(1L);
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.PRIVATE);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByTenantIdAndAccountIds(1L, java.util.List.of(2L)))
        .thenReturn(java.util.List.of(profile));

    String accountUuid = account.getAccountUuid().toString();
    Map<String, ProfilePresenceVisibilityPolicy> policies =
        service.listPresenceVisibilityPolicies(1L, java.util.List.of(accountUuid));

    assertEquals(Map.of(accountUuid, ProfilePresenceVisibilityPolicy.PRIVATE), policies);
    verify(accountRepository).findByAccountUuid(account.getAccountUuid());
  }

  @Test
  void listPresenceVisibilityPoliciesRejectsMalformedUuidBeforeLookup() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.listPresenceVisibilityPolicies(1L, java.util.List.of("2")));

    verifyNoInteractions(accountRepository, accountTenantMembershipRepository, profileRepository);
  }

  @Test
  void listPresenceVisibilityPoliciesOmitsUnknownAccountUuids() {
    String unknownAccountUuid = UUID.randomUUID().toString();

    Map<String, ProfilePresenceVisibilityPolicy> policies =
        service.listPresenceVisibilityPolicies(1L, java.util.List.of(unknownAccountUuid));

    assertTrue(policies.isEmpty());
    verify(accountRepository).findByAccountUuid(UUID.fromString(unknownAccountUuid));
    verifyNoInteractions(accountTenantMembershipRepository, profileRepository);
  }

  @Test
  void listPresenceVisibilityPoliciesFailsClosedOnContradictoryAccountProvenance() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    account.setAccountUuidSourceNumericId(3L);

    assertThrows(
        IllegalStateException.class,
        () ->
            service.listPresenceVisibilityPolicies(
                1L, java.util.List.of(account.getAccountUuid().toString())));

    verifyNoInteractions(accountTenantMembershipRepository, profileRepository);
  }

  @Test
  void listPresenceVisibilityPoliciesOmitsAccountsWithoutTenantMembership() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(false);

    Map<String, ProfilePresenceVisibilityPolicy> policies =
        service.listPresenceVisibilityPolicies(
            1L, java.util.List.of(account.getAccountUuid().toString()));

    assertTrue(policies.isEmpty());
    verifyNoInteractions(profileRepository);
  }

  @Test
  void listPresenceVisibilityPoliciesRejectsForeignProfileOwner() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    Account foreignAccount = new Account();
    foreignAccount.setId(3L);
    Profile profile = new Profile();
    profile.setAccount(foreignAccount);
    profile.setTenantId(1L);
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.PRIVATE);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByTenantIdAndAccountIds(1L, java.util.List.of(2L)))
        .thenReturn(java.util.List.of(profile));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.listPresenceVisibilityPolicies(
                1L, java.util.List.of(account.getAccountUuid().toString())));
  }

  @Test
  void updateProfileStoresChanges() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setTenantId(1L);
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));
    when(profileRepository.save(profile)).thenReturn(profile);
    when(profileMapper.toDto(profile, account.getAccountUuid().toString()))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                1L,
                1L,
                account.getAccountUuid().toString(),
                "demo",
                "bio",
                ProfilePresenceVisibilityPolicy.PRIVATE));

    var dto =
        service.updateProfile(
            2L,
            new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
                1L,
                account.getAccountUuid().toString(),
                "demo",
                "bio",
                ProfilePresenceVisibilityPolicy.PRIVATE));

    assertEquals("demo", dto.displayName());
    assertEquals(account.getAccountUuid().toString(), dto.accountId());
    assertEquals(ProfilePresenceVisibilityPolicy.PRIVATE, profile.getPresenceVisibilityPolicy());
    org.mockito.Mockito.verify(notificationService).sendNotification(1L, 2L, "Profile updated");
  }

  @Test
  void updateProfileRejectsMismatchedSourceUuidBeforeMembershipOrProfileAccess() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    var request =
        new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
            1L,
            UUID.randomUUID().toString(),
            "demo",
            "bio",
            ProfilePresenceVisibilityPolicy.PRIVATE);

    assertEquals(
        "Profile account identity does not match its source",
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(2L, request))
            .getMessage());

    verifyNoInteractions(accountTenantMembershipRepository, profileRepository, notificationService);
  }

  @Test
  void profileReadAndUpdateFailClosedWithoutCurrentTenantMembership() {
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(false);
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));

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
                        2L,
                        new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
                            1L,
                            account.getAccountUuid().toString(),
                            "demo",
                            "bio",
                            ProfilePresenceVisibilityPolicy.PRIVATE)))
            .getMessage());

    verifyNoInteractions(profileRepository, profileMapper, notificationService);
  }

  @Test
  void updateProfileRejectsReservedHiddenStaffPolicyBeforePersistence() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    var request =
        new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
            1L,
            account.getAccountUuid().toString(),
            "demo",
            "bio",
            ProfilePresenceVisibilityPolicy.HIDDEN_STAFF);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(2L, request));

    assertEquals(
        "Profile presence visibility policy HIDDEN_STAFF is reserved", exception.getMessage());
    verifyNoInteractions(profileRepository, notificationService);
  }

  @Test
  void exportAccountDataIncludesProfilesAcrossTenants() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    UUID accountUuid = account.getAccountUuid();
    Profile tenantOne = profile(account, 1L, "one");
    Profile tenantTwo = profile(account, 2L, "two");
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(profileRepository.findByAccountId(2L)).thenReturn(java.util.List.of(tenantOne, tenantTwo));
    when(profileMapper.toDto(tenantOne, accountUuid.toString()))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                10L,
                1L,
                accountUuid.toString(),
                "one",
                null,
                ProfilePresenceVisibilityPolicy.FRIENDS_ONLY));
    when(profileMapper.toDto(tenantTwo, accountUuid.toString()))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                20L,
                2L,
                accountUuid.toString(),
                "two",
                null,
                ProfilePresenceVisibilityPolicy.FRIENDS_ONLY));

    var export = service.exportAccountData(2L);

    assertEquals(accountUuid.toString(), export.account().id());
    assertEquals(2, export.profiles().size());
    assertTrue(
        export.profiles().stream().allMatch(p -> accountUuid.toString().equals(p.accountId())));
  }

  @Test
  void exportAccountDataRejectsProfileFromAnotherAccountBeforeMapping() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    Account foreignAccount = new Account();
    foreignAccount.setId(3L);
    Profile foreignProfile = new Profile();
    foreignProfile.setAccount(foreignAccount);
    foreignProfile.setTenantId(1L);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(profileRepository.findByAccountId(2L)).thenReturn(java.util.List.of(foreignProfile));

    assertThrows(IllegalStateException.class, () -> service.exportAccountData(2L));

    verifyNoInteractions(profileMapper);
  }

  @Test
  void exportAccountDataRejectsContradictoryIdentityBeforeReadingProfiles() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    account.setAccountUuidSourceNumericId(3L);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));

    assertThrows(IllegalStateException.class, () -> service.exportAccountData(2L));

    verifyNoInteractions(profileRepository, profileMapper);
  }

  @Test
  void exportAccountDataRejectsUnmappedIdentityBeforeReadingProfiles() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(accountRepository.findByAccountUuid(account.getAccountUuid()))
        .thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service.exportAccountData(2L));

    verifyNoInteractions(profileRepository, profileMapper);
  }

  @Test
  void exportTenantDataFailsClosedBeforeReadingAccountOrTenantData() {
    org.springframework.web.server.ResponseStatusException exception =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> service.exportTenantData(7L, 2L));

    assertEquals(org.springframework.http.HttpStatus.NOT_IMPLEMENTED, exception.getStatusCode());
    verifyNoInteractions(accountRepository, profileRepository, accountTenantMembershipRepository);
  }

  @ParameterizedTest
  @CsvSource({"active, false", "canceled, true"})
  void deleteAccountFailsClosedWithoutMutationForAnySubscriptionState(
      String status, boolean terminal) {
    Account account = new Account();
    account.setId(2L);
    Subscription subscription = new Subscription();
    subscription.setStatus(status);
    if (terminal) {
      subscription.setEndedAt(java.time.LocalDateTime.now());
    }
    subscription.setAccount(account);
    subscription.setTenantId(7L);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(subscriptionRepository.findByAccountId(2L)).thenReturn(java.util.List.of(subscription));

    AccountLifecycleException ex =
        assertThrows(AccountLifecycleException.class, () -> service.deleteAccount(2L));
    assertEquals("ACCOUNT_DELETE_WORKFLOW_UNAVAILABLE", ex.getCode());
    verify(accountRepository).findById(2L);
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .delete(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(
        accountJoinOperationRepository,
        emailVerificationTokenRepository,
        passwordResetTokenRepository,
        accountRealmAccessGrantRepository,
        externalAccountRepository,
        paymentTransactionRepository,
        subscriptionRepository,
        profileRepository,
        accountTenantMembershipRepository);
  }

  @Test
  void deleteAccountStillRequiresAnExistingAccountBeforeReturningUnavailable() {
    when(accountRepository.findById(404L)).thenReturn(Optional.empty());

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> service.deleteAccount(404L));

    assertEquals("Account not found", exception.getMessage());
    verify(accountRepository).findById(404L);
    verifyNoInteractions(
        accountJoinOperationRepository,
        emailVerificationTokenRepository,
        passwordResetTokenRepository,
        accountRealmAccessGrantRepository,
        externalAccountRepository,
        paymentTransactionRepository,
        subscriptionRepository,
        profileRepository,
        accountTenantMembershipRepository);
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
  void completePasswordResetCommitsTokenPasswordAuthorityEventAndReceiptInOrder() {
    PasswordResetHarness harness =
        preparePasswordResetCommit(
            "reset-token-order", LocalDateTime.now().plusHours(1), null, true);

    service.completePasswordReset(
        new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
            "reset-token-order", "new-password"));

    PasswordResetReceipt receipt = harness.receipt().get();
    assertNotNull(receipt);
    assertFalse("old-verifier".equals(harness.account().getPasswordHash()));
    assertEquals(2L, receipt.accountAuthorityGeneration());
    assertEquals(2L, receipt.accountSourceVersion());
    assertEquals(8L, receipt.issuanceFence());
    assertEquals(6L, receipt.issuanceFenceSourceVersion());
    assertEquals(sha256Hex(harness.account().getPasswordHash()), receipt.passwordVerifierDigest());
    assertEquals(passwordResetTokenHash("reset-token-order"), receipt.tokenHash());
    assertFalse(
        new String(harness.event().get().payload(), StandardCharsets.UTF_8)
            .contains("reset-token-order"));

    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(
            passwordResetOperationRepository,
            passwordResetTokenRepository,
            accountRepository,
            accountAuthorityGenerationRepository,
            accountAuthorityOutboxRepository);
    order.verify(passwordResetOperationRepository).findByTokenHash(receipt.tokenHash());
    order.verify(passwordResetTokenRepository).findByToken("reset-token-order");
    order.verify(accountRepository).findByIdForUpdate(harness.account().getId());
    order.verify(passwordResetOperationRepository).findByTokenHash(receipt.tokenHash());
    order.verify(passwordResetTokenRepository).findByToken("reset-token-order");
    order.verify(accountAuthorityGenerationRepository).read(harness.scope());
    order.verify(accountAuthorityOutboxRepository).readCheckpoint(receipt.outboxStreamKey());
    order
        .verify(passwordResetTokenRepository)
        .consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(harness.token()),
            org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    order
        .verify(accountRepository)
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.eq(harness.account()),
            org.mockito.ArgumentMatchers.anyString());
    order
        .verify(accountAuthorityGenerationRepository)
        .advance(harness.initialAuthority(), harness.initialAuthority().issuanceFence());
    order
        .verify(accountAuthorityOutboxRepository)
        .append(
            org.mockito.ArgumentMatchers.eq(receipt.outboxStreamKey()),
            org.mockito.ArgumentMatchers.eq(receipt.requestId()),
            org.mockito.ArgumentMatchers.any());
    order.verify(passwordResetOperationRepository).insert(receipt);
  }

  @Test
  void completePasswordResetConsumesTokenBeforeUpdatingPassword() {
    PasswordResetHarness harness =
        preparePasswordResetCommit("tok", LocalDateTime.now().plusHours(1), null, true);

    service.completePasswordReset(
        new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
            "tok", "new-password"));

    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(passwordResetTokenRepository, accountRepository);
    order
        .verify(passwordResetTokenRepository)
        .consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(harness.token()),
            org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    order
        .verify(accountRepository)
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.eq(harness.account()),
            org.mockito.ArgumentMatchers.anyString());
    assertFalse("old-verifier".equals(harness.account().getPasswordHash()));
  }

  @Test
  void completePasswordResetRejectsAlreadyConsumedTokenBeforePasswordMutation() {
    PasswordResetHarness harness =
        preparePasswordResetCommit(
            "reset-token-consumed", LocalDateTime.now().plusHours(1), null, true);
    when(passwordResetTokenRepository.consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(harness.token()),
            org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
        .thenReturn(false);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.completePasswordReset(
                new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                    "reset-token-consumed", "new-password")));

    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(accountAuthorityGenerationRepository, org.mockito.Mockito.never())
        .advance(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void completePasswordResetRejectsExpiredTokenBeforeConsumption() {
    PasswordResetHarness harness =
        preparePasswordResetCommit(
            "reset-token-initial-expiry", LocalDateTime.now().minusSeconds(1), null, true);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.completePasswordReset(
                new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                    "reset-token-initial-expiry", "new-password")));

    org.mockito.Mockito.verify(passwordResetTokenRepository, org.mockito.Mockito.never())
        .consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(harness.token()),
            org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void completePasswordResetRejectsMissingOrContradictorySourceBeforeMutation() {
    ScopeState progressedWithoutHistory =
        new ScopeState(
            AuthorityScope.account(UUID.fromString("5d79f453-4f04-4cad-8561-cce2a2ab57bc")),
            2L,
            2L,
            new IssuanceFence(UUID.fromString("5d79f453-4f04-4cad-8561-cce2a2ab57bc"), 7L, 4L));
    preparePasswordResetCommit(
        "reset-token-unproven", LocalDateTime.now().plusHours(1), progressedWithoutHistory, true);

    assertThrows(
        IllegalStateException.class,
        () ->
            service.completePasswordReset(
                new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                    "reset-token-unproven", "new-password")));

    org.mockito.Mockito.verify(passwordResetTokenRepository, org.mockito.Mockito.never())
        .consumeIfUnexpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(accountAuthorityGenerationRepository, org.mockito.Mockito.never())
        .advance(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountAuthorityOutboxRepository, org.mockito.Mockito.never())
        .append(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(passwordResetOperationRepository, org.mockito.Mockito.never())
        .insert(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void completePasswordResetRejectsAStreamEventAtThePristineSourceBaseline() {
    PasswordResetHarness harness =
        preparePasswordResetCommit(
            "reset-token-contradictory-baseline", LocalDateTime.now().plusHours(1), null, true);
    harness
        .event()
        .set(
            new Event(
                "account:auth-authority:v1:account/" + harness.scope().accountId(),
                "unowned-prior-request",
                1L,
                "unowned-prior-event",
                "sha256:" + "4".repeat(64),
                new byte[] {1}));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.completePasswordReset(
                new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                    "reset-token-contradictory-baseline", "new-password")));

    org.mockito.Mockito.verify(passwordResetTokenRepository, org.mockito.Mockito.never())
        .consumeIfUnexpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(accountAuthorityGenerationRepository, org.mockito.Mockito.never())
        .advance(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountAuthorityOutboxRepository, org.mockito.Mockito.never())
        .append(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(passwordResetOperationRepository, org.mockito.Mockito.never())
        .insert(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void completePasswordResetRejectsMissingUuidProvenanceBeforeAuthorityReads() {
    PasswordResetHarness harness =
        preparePasswordResetCommit(
            "reset-token-unproven-identity", LocalDateTime.now().plusHours(1), null, true);
    harness.account().setAccountUuidProvenance(null);

    assertThrows(
        IllegalStateException.class,
        () ->
            service.completePasswordReset(
                new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                    "reset-token-unproven-identity", "new-password")));

    verifyNoInteractions(accountAuthorityGenerationRepository, accountAuthorityOutboxRepository);
    org.mockito.Mockito.verify(passwordResetTokenRepository, org.mockito.Mockito.never())
        .consumeIfUnexpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void completePasswordResetExactRetryRecoversLostResponseWithoutMutation() {
    PasswordResetRetryHarness harness =
        preparePasswordResetRetry("reset-token-exact", "password-one");

    service.completePasswordReset(
        new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
            "reset-token-exact", "password-one"));

    verifyNoInteractions(passwordResetTokenRepository);
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(accountAuthorityGenerationRepository, org.mockito.Mockito.never())
        .advance(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountAuthorityOutboxRepository, org.mockito.Mockito.never())
        .append(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(passwordResetOperationRepository, org.mockito.Mockito.never())
        .insert(org.mockito.ArgumentMatchers.any());
    assertEquals(harness.receipt(), harness.receiptReadback().get());
  }

  @Test
  void completePasswordResetChangedRetryConflictsWhileVerifierIsUnchanged() {
    preparePasswordResetRetry("reset-token-changed", "password-one");

    assertThrows(
        OperationConflictException.class,
        () ->
            service.completePasswordReset(
                new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                    "reset-token-changed", "different-password")));

    verifyNoInteractions(passwordResetTokenRepository);
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(accountAuthorityGenerationRepository, org.mockito.Mockito.never())
        .advance(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(passwordResetOperationRepository, org.mockito.Mockito.never())
        .insert(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void completePasswordResetExpiredReceiptCannotRecoverLostResponse() {
    preparePasswordResetRetry(
        "reset-token-expired-receipt", "password-one", LocalDateTime.now().minusSeconds(1), false);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.completePasswordReset(
                    new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                        "reset-token-expired-receipt", "password-one")));

    assertEquals("Token expired", exception.getMessage());
    verifyNoInteractions(passwordResetTokenRepository, accountAuthorityGenerationRepository);
    org.mockito.Mockito.verify(accountAuthorityOutboxRepository, org.mockito.Mockito.never())
        .findEvent(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void completePasswordResetSupersededVerifierIsNotReportedAsCallerConflict() {
    PasswordResetRetryHarness harness =
        preparePasswordResetRetry("reset-token-superseded", "password-one");
    harness.account().setPasswordHash(hash("later-password"));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.completePasswordReset(
                    new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                        "reset-token-superseded", "password-one")));

    assertEquals("Password-reset result was superseded", exception.getMessage());
    verifyNoInteractions(passwordResetTokenRepository, accountAuthorityGenerationRepository);
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void completePasswordResetFailsClosedWhenImmutableEventReadbackDiffers() {
    PasswordResetHarness harness =
        preparePasswordResetCommit(
            "reset-token-event-readback", LocalDateTime.now().plusHours(1), null, true);
    harness.corruptEventReadback().set(true);

    assertThrows(
        IllegalStateException.class,
        () ->
            service.completePasswordReset(
                new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                    "reset-token-event-readback", "new-password")));

    org.mockito.Mockito.verify(passwordResetOperationRepository)
        .insert(org.mockito.ArgumentMatchers.any());
    assertNotNull(harness.receipt().get());
  }

  private PasswordResetHarness preparePasswordResetCommit(
      String rawToken,
      LocalDateTime tokenExpiresAt,
      ScopeState requestedInitialAuthority,
      boolean receiptReadbackEnabled) {
    long accountId = 101L;
    UUID accountUuid =
        requestedInitialAuthority == null
            ? UUID.fromString("723d61a5-8e11-4b8f-9e01-3ab48e9b2702")
            : requestedInitialAuthority.scope().accountId();
    AuthorityScope scope = AuthorityScope.account(accountUuid);
    ScopeState initialAuthority =
        requestedInitialAuthority == null
            ? new ScopeState(scope, 1L, 1L, new IssuanceFence(accountUuid, 7L, 5L))
            : requestedInitialAuthority;

    Account account = new Account();
    account.setId(accountId);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    account.setAccountUuidSourceNumericId(accountId);
    account.setPasswordHash("old-verifier");

    Account tokenAccount = new Account();
    tokenAccount.setId(accountId);
    net.firedevops.firemud.accountservice.entity.PasswordResetToken token =
        new net.firedevops.firemud.accountservice.entity.PasswordResetToken();
    token.setId(73L);
    token.setAccount(tokenAccount);
    token.setToken(rawToken);
    token.setExpiresAt(tokenExpiresAt);

    String tokenHash = passwordResetTokenHash(rawToken);
    String requestId = "account-password-reset-request-v1:" + tokenHash;
    String streamKey = "account:auth-authority:v1:account/" + accountUuid;
    AtomicReference<PasswordResetReceipt> receipt = new AtomicReference<>();
    AtomicReference<Event> event = new AtomicReference<>();
    AtomicBoolean corruptEventReadback = new AtomicBoolean(false);
    AtomicReference<ScopeState> authority = new AtomicReference<>(initialAuthority);
    AtomicReference<Integer> tokenLookupCount = new AtomicReference<>(0);

    when(passwordResetOperationRepository.findByTokenHash(tokenHash))
        .thenAnswer(
            ignored ->
                receiptReadbackEnabled ? Optional.ofNullable(receipt.get()) : Optional.empty());
    when(passwordResetOperationRepository.findByRequestId(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());
    when(passwordResetOperationRepository.insert(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              PasswordResetReceipt inserted = invocation.getArgument(0);
              receipt.set(inserted);
              return inserted;
            });
    when(passwordResetTokenRepository.findByToken(rawToken))
        .thenAnswer(
            ignored -> {
              int lookupNumber = tokenLookupCount.get() + 1;
              tokenLookupCount.set(lookupNumber);
              return lookupNumber <= 2 ? Optional.of(token) : Optional.empty();
            });
    when(passwordResetTokenRepository.consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(token),
            org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
        .thenReturn(true);
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(accountRepository.updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.eq(account), org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> {
              account.setPasswordHash(invocation.getArgument(1));
              return account;
            });
    when(accountAuthorityGenerationRepository.read(scope)).thenAnswer(ignored -> authority.get());
    when(accountAuthorityGenerationRepository.advance(
            org.mockito.ArgumentMatchers.any(ScopeState.class),
            org.mockito.ArgumentMatchers.any(IssuanceFence.class)))
        .thenAnswer(
            invocation -> {
              ScopeState previous = invocation.getArgument(0);
              IssuanceFence previousFence = previous.issuanceFence();
              ScopeState next =
                  new ScopeState(
                      previous.scope(),
                      previous.generation() + 1L,
                      previous.sourceVersion() + 1L,
                      new IssuanceFence(
                          previousFence.accountId(),
                          previousFence.value() + 1L,
                          previousFence.sourceVersion() + 1L));
              authority.set(next);
              return next;
            });
    when(accountAuthorityOutboxRepository.readCheckpoint(streamKey))
        .thenAnswer(
            ignored -> {
              Event stored = event.get();
              return stored == null
                  ? Optional.empty()
                  : Optional.of(
                      new Checkpoint(
                          stored.outboxStreamKey(),
                          stored.outboxSequence(),
                          stored.eventId(),
                          stored.eventDigest()));
            });
    when(accountAuthorityOutboxRepository.append(
            org.mockito.ArgumentMatchers.eq(streamKey),
            org.mockito.ArgumentMatchers.eq(requestId),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              LongFunction<EventEvidence> factory = invocation.getArgument(2);
              EventEvidence evidence = factory.apply(1L);
              Event appended =
                  new Event(
                      streamKey,
                      requestId,
                      1L,
                      evidence.eventId(),
                      evidence.eventDigest(),
                      evidence.payload());
              event.set(appended);
              return appended;
            });
    when(accountAuthorityOutboxRepository.findEvent(
            org.mockito.ArgumentMatchers.eq(streamKey), org.mockito.ArgumentMatchers.anyLong()))
        .thenAnswer(
            invocation -> {
              Event stored = event.get();
              if (stored == null || stored.outboxSequence() != invocation.<Long>getArgument(1)) {
                return Optional.empty();
              }
              if (corruptEventReadback.get()) {
                return Optional.of(
                    new Event(
                        stored.outboxStreamKey(),
                        stored.requestId(),
                        stored.outboxSequence(),
                        stored.eventId(),
                        "sha256:" + "0".repeat(64),
                        stored.payload()));
              }
              return Optional.of(stored);
            });

    return new PasswordResetHarness(
        account, token, tokenHash, scope, initialAuthority, receipt, event, corruptEventReadback);
  }

  private PasswordResetRetryHarness preparePasswordResetRetry(String rawToken, String password) {
    return preparePasswordResetRetry(rawToken, password, LocalDateTime.now().plusHours(1), true);
  }

  private PasswordResetRetryHarness preparePasswordResetRetry(
      String rawToken,
      String password,
      LocalDateTime tokenExpiresAt,
      boolean currentVerifierMatchesReceipt) {
    long accountId = 107L;
    UUID accountUuid = UUID.fromString("8950829f-970b-4712-8d67-fbfe1c8813f5");
    AuthorityScope scope = AuthorityScope.account(accountUuid);
    Account account = new Account();
    account.setId(accountId);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    account.setAccountUuidSourceNumericId(accountId);
    String passwordVerifier = hash(password);
    account.setPasswordHash(
        currentVerifierMatchesReceipt ? passwordVerifier : hash("superseding-password"));

    String tokenHash = passwordResetTokenHash(rawToken);
    String requestId = "account-password-reset-request-v1:" + tokenHash;
    String eventId = "account-password-reset-event-v1:" + tokenHash;
    String streamKey = "account:auth-authority:v1:account/" + accountUuid;
    String verifierDigest = sha256Hex(passwordVerifier);
    String requestDigest =
        resetRequestDigest(accountUuid, tokenHash, tokenExpiresAt, verifierDigest);
    ScopeState committedAuthority =
        new ScopeState(scope, 2L, 2L, new IssuanceFence(accountUuid, 8L, 6L));
    PasswordResetAuthorityEvent eventEvidence =
        PasswordResetAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", PasswordResetAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", eventId),
                Map.entry("requestId", requestId),
                Map.entry("accountId", accountUuid.toString()),
                Map.entry("sourceScope", "account/" + accountUuid),
                Map.entry("outboxStreamKey", streamKey),
                Map.entry("outboxSequence", "1"),
                Map.entry("accountAuthorityGeneration", "2"),
                Map.entry("sourceVersion", "2"),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", "2",
                        "outboxStreamKey", streamKey,
                        "outboxSequence", "1"))));
    Event event =
        new Event(
            streamKey,
            requestId,
            1L,
            eventEvidence.eventId(),
            eventEvidence.eventDigest(),
            eventEvidence.canonicalJsonUtf8());
    PasswordResetReceipt receipt =
        PasswordResetReceipt.committed(
            accountId,
            accountUuid,
            tokenHash,
            requestId,
            requestDigest,
            tokenExpiresAt,
            verifierDigest,
            streamKey,
            1L,
            eventId,
            eventEvidence.eventDigest(),
            committedAuthority,
            committedAuthority.issuanceFence());
    AtomicReference<PasswordResetReceipt> receiptReadback = new AtomicReference<>(receipt);

    when(passwordResetOperationRepository.findByTokenHash(tokenHash))
        .thenAnswer(ignored -> Optional.ofNullable(receiptReadback.get()));
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(accountAuthorityGenerationRepository.read(scope)).thenReturn(committedAuthority);
    when(accountAuthorityOutboxRepository.findEvent(streamKey, 1L)).thenReturn(Optional.of(event));
    return new PasswordResetRetryHarness(account, receipt, receiptReadback);
  }

  private static String passwordResetTokenHash(String rawToken) {
    return sha256Hex(rawToken);
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String resetRequestDigest(
      UUID accountUuid, String tokenHash, LocalDateTime tokenExpiresAt, String verifierDigest) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      resetFrame(digest, "account-password-reset-request/v1");
      resetFrame(digest, "PASSWORD_RESET");
      resetFrame(digest, accountUuid.toString());
      resetFrame(digest, tokenHash);
      resetFrame(digest, tokenExpiresAt.toString());
      resetFrame(digest, verifierDigest);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void resetFrame(MessageDigest digest, String value) {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
    digest.update(encoded);
  }

  private record PasswordResetHarness(
      Account account,
      net.firedevops.firemud.accountservice.entity.PasswordResetToken token,
      String tokenHash,
      AuthorityScope scope,
      ScopeState initialAuthority,
      AtomicReference<PasswordResetReceipt> receipt,
      AtomicReference<Event> event,
      AtomicBoolean corruptEventReadback) {}

  private record PasswordResetRetryHarness(
      Account account,
      PasswordResetReceipt receipt,
      AtomicReference<PasswordResetReceipt> receiptReadback) {}

  @Test
  void requestPasswordResetContainsDeliveryFailureAfterSavingToken() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    org.mockito.Mockito.doThrow(new RuntimeException("SMTP failure"))
        .when(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("Password Reset"),
            org.mockito.ArgumentMatchers.anyString());

    service.requestPasswordReset(new PasswordResetRequest("demo@example.com"));

    org.mockito.Mockito.verify(passwordResetTokenRepository)
        .save(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("demo@example.com"),
            org.mockito.ArgumentMatchers.eq("Password Reset"),
            org.mockito.ArgumentMatchers.anyString());
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
  void sendUsernameReminderContainsDeliveryFailure() {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    org.mockito.Mockito.doThrow(new RuntimeException("SMTP failure"))
        .when(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("Username Reminder"),
            org.mockito.ArgumentMatchers.anyString());

    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AccountServiceImpl.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      service.sendUsernameReminder(
          new net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest(
              "demo@example.com"));
      assertTrue(
          appender.list.stream()
              .anyMatch(
                  event ->
                      "Recovery email delivery failed; cause=RuntimeException"
                          .equals(event.getFormattedMessage())));
      assertFalse(
          appender.list.stream()
              .anyMatch(event -> event.getFormattedMessage().contains("SMTP failure")));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("demo@example.com"),
            org.mockito.ArgumentMatchers.eq("Username Reminder"),
            org.mockito.ArgumentMatchers.anyString());
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
  void requestEmailVerificationByEmailContainsDeliveryFailureAfterSavingToken() {
    Account account = new Account();
    account.setId(6L);
    account.setEmail("demo@example.com");
    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    org.mockito.Mockito.doThrow(new RuntimeException("SMTP failure"))
        .when(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("Email Verification"),
            org.mockito.ArgumentMatchers.anyString());

    service.requestEmailVerification("demo@example.com");

    org.mockito.Mockito.verify(emailVerificationTokenRepository)
        .save(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("demo@example.com"),
            org.mockito.ArgumentMatchers.eq("Email Verification"),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void verifyEmailSetsFlag() {
    Account account = new Account();
    EmailVerificationToken token = new EmailVerificationToken();
    token.setId(9L);
    token.setAccount(account);
    token.setToken("tok");
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
    when(emailVerificationTokenRepository.findByToken("tok")).thenReturn(Optional.of(token));
    when(emailVerificationTokenRepository.consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(token),
            org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class)))
        .thenReturn(true);

    service.verifyEmail(new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest("tok"));

    assertTrue(account.isEmailVerified());
    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(emailVerificationTokenRepository, accountRepository);
    order
        .verify(emailVerificationTokenRepository)
        .consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(token),
            org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class));
    order.verify(accountRepository).save(account);
    org.mockito.Mockito.verify(emailVerificationTokenRepository, org.mockito.Mockito.never())
        .delete(token);
  }

  @Test
  void verifyEmailRejectsAlreadyConsumedTokenBeforeMarkingAccountVerified() {
    Account account = new Account();
    EmailVerificationToken token = new EmailVerificationToken();
    token.setId(9L);
    token.setAccount(account);
    token.setToken("tok");
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
    when(emailVerificationTokenRepository.findByToken("tok")).thenReturn(Optional.of(token));
    when(emailVerificationTokenRepository.consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(token),
            org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class)))
        .thenReturn(false);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.verifyEmail(
                new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest("tok")));

    assertFalse(account.isEmailVerified());
    org.mockito.Mockito.verifyNoInteractions(accountRepository);
    org.mockito.Mockito.verify(emailVerificationTokenRepository, org.mockito.Mockito.never())
        .delete(token);
  }

  @Test
  void verifyEmailRejectsExpiredTokenBeforeConsumption() {
    EmailVerificationToken token = new EmailVerificationToken();
    token.setId(9L);
    token.setToken("tok");
    token.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
    when(emailVerificationTokenRepository.findByToken("tok")).thenReturn(Optional.of(token));

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.verifyEmail(
                    new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest("tok")));

    assertEquals("Token expired", exception.getMessage());
    org.mockito.Mockito.verify(emailVerificationTokenRepository, org.mockito.Mockito.never())
        .consumeIfUnexpired(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class));
    org.mockito.Mockito.verifyNoInteractions(accountRepository);
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

  private void assertCanonicalIssuerAndClaims(
      String token, String expectedAudience, String expectedAccountUuid) {
    var claims = parseClaims(token);
    assertEquals("firemud-account-service", claims.getIssuer());
    assertEquals(expectedAudience, claims.getAudience().iterator().next());
    assertEquals(expectedAccountUuid, claims.getSubject());
    assertEquals(expectedAccountUuid, claims.get("accountId", String.class));
    assertNotNull(claims.get("jti"));
    assertFalse(claims.containsKey("tenantId"));
  }

  private void setPersistedAuthenticationIdentity(Account account) {
    setPersistedAuthenticationIdentity(
        account, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
  }

  private void setPersistedAuthenticationIdentity(
      Account account, AccountIdentityProvenance provenance) {
    account.setAccountUuid(UUID.randomUUID());
    account.setAccountUuidProvenance(provenance);
    account.setAccountUuidSourceNumericId(account.getId());
    when(accountRepository.findByAccountUuid(account.getAccountUuid()))
        .thenReturn(Optional.of(account));
  }

  private void assignInvalidAuthenticationIdentity(
      Account account, InvalidAuthenticationIdentity failure) {
    setPersistedAuthenticationIdentity(account);
    switch (failure) {
      case MISSING_UUID -> account.setAccountUuid(null);
      case NIL_UUID -> account.setAccountUuid(new UUID(0L, 0L));
      case MISSING_PROVENANCE -> account.setAccountUuidProvenance(null);
      case MISSING_SOURCE_ROW -> account.setAccountUuidSourceNumericId(null);
      case CONTRADICTORY_SOURCE_ROW -> account.setAccountUuidSourceNumericId(33L);
    }
  }

  private enum InvalidAuthenticationIdentity {
    MISSING_UUID,
    NIL_UUID,
    MISSING_PROVENANCE,
    MISSING_SOURCE_ROW,
    CONTRADICTORY_SOURCE_ROW
  }

  private io.jsonwebtoken.Claims parseClaims(String token) {
    return new JwtUtil(JWT_SECRET, 3600000L).parseToken(token).getPayload();
  }

  private static Checkpoint joinAuthorityCheckpoint() {
    return new Checkpoint(
        "account:auth-authority:v1:membership/account-a/tenant-a",
        1L,
        "event-1",
        "sha256:" + "0".repeat(64));
  }

  private static RuntimeException unexpectedEntitlementFailure(String failureType) {
    return switch (failureType) {
      case "illegal-state" -> new IllegalStateException("subscription mapping failed");
      case "jooq-mapping" -> new MappingException("subscription mapping failed");
      case "jooq-configuration" -> new ConfigurationException("subscription query misconfigured");
      default -> throw new IllegalArgumentException("Unknown entitlement failure type");
    };
  }

  private Account directTextAccount() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    return account;
  }

  private static DirectTextCallerContext directTextCaller() {
    return new DirectTextCallerContext(
        11L,
        7L,
        UUID.fromString(REALM_ID),
        PLAYABLE_STATE_NAMESPACE_ID,
        "SHARED",
        44L,
        "session-1",
        "direct-text-request-1");
  }

  private static DirectTextJoinTarget directTextTarget() {
    return new DirectTextJoinTarget(
        7L,
        UUID.fromString(REALM_ID),
        "demo",
        "production",
        PLAYABLE_STATE_NAMESPACE_ID,
        "SHARED",
        44L,
        23L,
        17L);
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

  private String retainedConnectScopeForTest(
      Account account,
      long tenantId,
      String realmId,
      String worldSlug,
      String realmSlug,
      String playableStateNamespaceId,
      String playableStateScope,
      long gameInstanceId,
      long catalogRevision,
      long pointerVersion) {
    UUID accountUuid = account.getAccountUuid();
    if (accountUuid == null
        || !java.util.Objects.equals(account.getId(), account.getAccountUuidSourceNumericId())) {
      throw new IllegalArgumentException("Account fixture lacks a persisted UUID identity");
    }
    java.time.Instant evaluatedAt = java.time.Instant.now();
    java.time.Instant expiresAt =
        evaluatedAt.plusMillis(tokenProperties.getConnectScopeExpirationMs());
    return new JwtUtil(JWT_SECRET, 30000L)
        .generateToken(
            accountUuid.toString(),
            tokenProperties.getConnectScopeExpirationMs(),
            Map.ofEntries(
                Map.entry("aud", "bootstrap-connect-scope"),
                Map.entry("accountId", accountUuid.toString()),
                Map.entry("tenantId", tenantId),
                Map.entry("realmId", realmId),
                Map.entry("worldSlug", worldSlug),
                Map.entry("realmSlug", realmSlug),
                Map.entry("playableStateNamespaceId", playableStateNamespaceId),
                Map.entry("playableStateScope", playableStateScope),
                Map.entry("gameInstanceId", gameInstanceId),
                Map.entry("catalogRevision", catalogRevision),
                Map.entry("pointerVersion", pointerVersion),
                Map.entry("evaluatedAt", evaluatedAt.toString()),
                Map.entry("connectScopeExpiresAt", expiresAt.toString())));
  }

  private PrivateConnectTokenContext preparePrivateConnectTokenContext(
      Account account, boolean grantAtIssuance) {
    return preparePrivateConnectTokenContext(account, grantAtIssuance, "ACTIVE");
  }

  private PrivateConnectTokenContext preparePrivateConnectTokenContext(
      Account account, boolean grantAtIssuance, String membershipLifecycleState) {
    setPersistedAuthenticationIdentity(account);
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    AccountTenantMembership tenantMembership = membership(account, 7L);
    tenantMembership.setLifecycleState(membershipLifecycleState);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(tenantMembership));
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
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
            admissionPointer(7L, "demo", "preview", "55", 19L, true, false, "SHARED", "ALLOW_NEW"));
    when(accountRealmAccessGrantRepository.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "preview"))
        .thenReturn(grantAtIssuance);

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    java.time.Instant evaluatedAt = java.time.Instant.now();
    java.time.Instant expiresAt =
        evaluatedAt.plusMillis(tokenProperties.getConnectScopeExpirationMs());
    // Simulate a retained legacy scope that has no lifecycle-qualified grant evidence.
    String connectScopeId =
        new JwtUtil(JWT_SECRET, 30000L)
            .generateToken(
                account.getAccountUuid().toString(),
                tokenProperties.getConnectScopeExpirationMs(),
                Map.ofEntries(
                    Map.entry("aud", "bootstrap-connect-scope"),
                    Map.entry("accountId", account.getAccountUuid().toString()),
                    Map.entry("tenantId", 7L),
                    Map.entry("realmId", REALM_ID),
                    Map.entry("worldSlug", "demo"),
                    Map.entry("realmSlug", "preview"),
                    Map.entry("playableStateNamespaceId", PLAYABLE_STATE_NAMESPACE_ID),
                    Map.entry("playableStateScope", "SHARED"),
                    Map.entry("gameInstanceId", 55L),
                    Map.entry("catalogRevision", 23L),
                    Map.entry("pointerVersion", 19L),
                    Map.entry("evaluatedAt", evaluatedAt.toString()),
                    Map.entry("connectScopeExpiresAt", expiresAt.toString())));
    return new PrivateConnectTokenContext(bootstrap.bootstrapToken(), connectScopeId);
  }

  private PrivateConnectTokenContext preparePublicInactiveConnectTokenContext(
      Account account, boolean publicJoiningAllowed) {
    setPersistedAuthenticationIdentity(account);
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    AccountTenantMembership tenantMembership = membership(account, 7L);
    tenantMembership.setLifecycleState("INACTIVE");
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(membership(account, 7L)), Optional.of(tenantMembership));
    Subscription subscription = new Subscription();
    subscription.setId(22L);
    subscription.setTenantId(7L);
    subscription.setStatus(publicJoiningAllowed ? "active" : "grace");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(subscription));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    String connectScopeId =
        service.listBootstrapRealms(bootstrap.bootstrapToken(), "demo").getFirst().connectScopeId();
    return new PrivateConnectTokenContext(bootstrap.bootstrapToken(), connectScopeId);
  }

  private void verifyConnectTokenFailureDoesNotStoreSuccess(
      String connectScopeId, String requestId, String errorCode) {
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verify(sessionService)
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.eq(connectScopeId),
            org.mockito.ArgumentMatchers.eq(requestId),
            org.mockito.ArgumentMatchers.argThat(
                replay -> !replay.success() && errorCode.equals(replay.errorCode())),
            org.mockito.ArgumentMatchers.longThat(ttl -> ttl > 0L));
  }

  private CachedPublicConnectTokenReplay prepareCachedPublicConnectTokenReplay() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
    String requestId = "req-cached-current-authority";
    ConnectTokenResult storedResult =
        new ConnectTokenResult(
            11L,
            7L,
            44L,
            "production",
            connectScopeId,
            "cached-connect-token",
            "cached-jti",
            requestId,
            "2026-09-30T00:00:00Z",
            "2026-09-30T00:00:30Z",
            false);
    when(sessionService.getConnectTokenReplay(7L, 11L, connectScopeId, requestId))
        .thenReturn(Optional.of(new SessionService.ConnectTokenReplay(true, storedResult, "", "")));
    return new CachedPublicConnectTokenReplay(
        bootstrap.bootstrapToken(), connectScopeId, requestId);
  }

  private void verifyCachedConnectTokenReplayDidNotWrite() {
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(SessionService.ConnectTokenReplay.class),
            org.mockito.ArgumentMatchers.anyLong());
  }

  private record CachedPublicConnectTokenReplay(
      String bootstrapToken, String connectScopeId, String requestId) {}

  private record PrivateConnectTokenContext(String bootstrapToken, String connectScopeId) {}

  private DirectTextJoinScope issueDirectTextConnectScopeForTest() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            11L,
            7L,
            UUID.fromString(REALM_ID),
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            44L,
            "session-1",
            "direct-realms-request-1");
    DirectTextJoinTarget target =
        new DirectTextJoinTarget(
            7L,
            UUID.fromString(REALM_ID),
            "demo",
            "production",
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            44L,
            23L,
            17L);
    return service.issueDirectTextConnectScope(caller, target);
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
        .setRealmId(REALM_ID)
        .setPlayableStateNamespaceId(playableStateNamespaceIdForTenant(tenantId))
        .setCatalogRevision(23L)
        .setPointerVersion(pointerVersion)
        .setVisible(visible)
        .setPublicProductionRealm(publicProductionRealm)
        .setRequiresCharacterSelection(false)
        .setStateScope(stateScope)
        .setCharacterCreationPolicy(characterCreationPolicy)
        .build();
  }

  private static String playableStateNamespaceIdForTenant(long tenantId) {
    return switch ((int) tenantId) {
      case 7 -> PLAYABLE_STATE_NAMESPACE_ID;
      case 8 -> PLAYABLE_STATE_NAMESPACE_ID_TENANT_8;
      default ->
          UUID.nameUUIDFromBytes(
                  ("playable-state-namespace-" + tenantId)
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .toString();
    };
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
