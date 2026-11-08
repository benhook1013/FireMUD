package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.dto.BootstrapWorldDto;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.dto.UpdateAccountLoginAuthModesRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthMode;
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
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.accountservice.repository.ExternalAccountRepository;
import net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import net.firedevops.firemud.accountservice.repository.RetainedSubscriptionEvidenceRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalPublicRealmScopeOwner;
import net.firedevops.firemud.accountservice.service.AccountCanonicalPublicRealmScopeOwner.RealmScope;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapWorldsOwner;
import net.firedevops.firemud.accountservice.service.EmailService;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.accountservice.service.exception.AccountAlreadyExistsException;
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.ReloadableJwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountServiceImplTest {
  private static final String JWT_SECRET = "mysecretkey123456789012345678901";
  private static final String REALM_ID = "4c4b57d8-e3a2-48fe-9977-e7df0fdce901";
  private static final String PLAYABLE_STATE_NAMESPACE_ID = "c6ed6a44-c7e7-4f18-81fc-078a74e67c07";
  @Mock private AccountRepository accountRepository;
  @Mock private AccountAuthorityGenerationRepository accountAuthorityGenerationRepository;
  @Mock private AccountAuthoritySourceEvidenceRepository accountAuthoritySourceEvidenceRepository;
  @Mock private AccountAuthorityOutboxRepository accountAuthorityOutboxRepository;
  @Mock private AccountPasswordResetOperationRepository accountPasswordResetOperationRepository;
  @Mock private AccountLogoutAllOperationRepository accountLogoutAllOperationRepository;
  @Mock private AccountSecurityStateOperationRepository accountSecurityStateOperationRepository;

  @Mock
  private AccountPasswordResetDraftSourceChangeRepository
      accountPasswordResetDraftSourceChangeRepository;

  @Mock private AccountAuditOutboxRepository accountAuditOutboxRepository;
  @Mock private AccountConnectScopeRepository accountConnectScopeRepository;
  @Mock private AccountJoinOperationRepository accountJoinOperationRepository;
  @Mock private AccountEmailLoginChallengeRepository accountEmailLoginChallengeRepository;
  @Mock private AccountRealmAccessGrantRepository accountRealmAccessGrantRepository;
  @Mock private AccountTenantMembershipRepository accountTenantMembershipRepository;
  @Mock private ProfileRepository profileRepository;
  private final ProfileMapper profileMapper = Mappers.getMapper(ProfileMapper.class);
  @Mock private NotificationService notificationService;
  @Mock private EmailService emailService;
  @Mock private MailProperties mailProperties;
  private final AccountTokenProperties tokenProperties = new AccountTokenProperties();
  private final JwtAuthProperties jwtAuthProperties = new JwtAuthProperties();
  @Mock private SessionService sessionService;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private RetainedSubscriptionEvidenceRepository retainedSubscriptionEvidenceRepository;
  @Mock private ExternalAccountRepository externalAccountRepository;
  @Mock private PlatformTransactionManager transactionManager;

  @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;

  @Mock
  private net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository
      passwordResetTokenRepository;

  private AccountServiceImpl service;
  private JwtUtil jwtUtil;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    when(transactionManager.getTransaction(
            org.mockito.ArgumentMatchers.any(TransactionDefinition.class)))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    AccountMapper mapper = Mappers.getMapper(AccountMapper.class);
    jwtUtil = org.mockito.Mockito.spy(new ReloadableJwtUtil(JWT_SECRET, 3600000L));
    jwtAuthProperties.setJwtSecret(JWT_SECRET);
    tokenProperties.setPlayerBootstrapExpirationMs(300000L);
    tokenProperties.setConnectScopeExpirationMs(120000L);
    tokenProperties.setConnectTokenExpirationMs(30000L);
    tokenProperties.setSessionExpirationMs(3600000L);
    when(mailProperties.getResetUrl()).thenReturn("http://reset/%s");
    when(mailProperties.getVerificationUrl()).thenReturn("http://verify/%s");
    service =
        new AccountServiceImpl(
            accountRepository,
            accountAuthorityGenerationRepository,
            accountAuthoritySourceEvidenceRepository,
            accountAuthorityOutboxRepository,
            accountPasswordResetOperationRepository,
            accountLogoutAllOperationRepository,
            accountSecurityStateOperationRepository,
            accountPasswordResetDraftSourceChangeRepository,
            accountAuditOutboxRepository,
            accountJoinOperationRepository,
            accountEmailLoginChallengeRepository,
            accountRealmAccessGrantRepository,
            accountTenantMembershipRepository,
            mapper,
            profileRepository,
            profileMapper,
            paymentTransactionRepository,
            retainedSubscriptionEvidenceRepository,
            externalAccountRepository,
            passwordResetTokenRepository,
            emailVerificationTokenRepository,
            notificationService,
            emailService,
            mailProperties,
            tokenProperties,
            jwtAuthProperties,
            jwtUtil,
            sessionService,
            transactionManager);
  }

  private enum OrdinaryIssuance {
    CONTROL_UI,
    GAMEPLAY_PASSWORD,
    EMAIL_OTP,
    PLAYER_BOOTSTRAP
  }

  @Test
  void bootstrapRealmsDelegatesWorldAndCredentialAndProjectsRetainedUuidScopeWithoutReminting() {
    AccountCanonicalPublicRealmScopeOwner owner =
        org.mockito.Mockito.mock(AccountCanonicalPublicRealmScopeOwner.class);
    service.setCanonicalPublicRealmScopeOwner(owner);
    UUID tenant = UUID.fromString("07aee9f3-a635-4b7f-aa66-86489320d7c3");
    UUID instance = UUID.fromString("3ddc3564-435b-479d-b14d-c91995e673cc");
    CanonicalJoinScopeV2 scope =
        new CanonicalJoinScopeV2(
            "already-retained-v2-signed-scope",
            UUID.fromString("e9e3aa54-9712-4e01-8767-f23f3c662e64"),
            tenant,
            UUID.fromString(REALM_ID),
            "source-tenant",
            "demo",
            "production",
            UUID.fromString(PLAYABLE_STATE_NAMESPACE_ID),
            "SHARED",
            instance,
            23L,
            17L,
            "2026-11-03T00:00:00Z",
            "2026-11-03T00:02:00Z");
    Target target = org.mockito.Mockito.mock(Target.class);
    when(target.realmDisplayName()).thenReturn("Canonical realm");
    when(target.characterCreationPolicy()).thenReturn("PRESEEDED_ONLY");
    RealmScope retained = org.mockito.Mockito.mock(RealmScope.class);
    when(retained.target()).thenReturn(target);
    when(retained.scope()).thenReturn(scope);
    when(owner.listRealmScopes("canonical-bootstrap", "source-tenant", "demo"))
        .thenReturn(List.of(retained));
    org.mockito.Mockito.clearInvocations(
        accountRepository, sessionService, jwtUtil, retainedSubscriptionEvidenceRepository);

    var result =
        service.listBootstrapRealms("canonical-bootstrap", "source-tenant", "demo").getFirst();

    assertEquals(scope.worldSlug(), result.worldSlug());
    assertEquals(scope.realmSlug(), result.realmSlug());
    assertEquals(scope.realmId(), result.realmId());
    assertEquals(tenant, result.tenantId());
    assertEquals(instance, result.gameInstanceId());
    assertEquals(scope.playableStateNamespaceId(), result.playableStateNamespaceId());
    assertEquals(scope.pointerVersion(), result.pointerVersion());
    assertEquals(scope.catalogRevision(), result.catalogRevision());
    assertEquals("Canonical realm", result.displayName());
    assertEquals("PLAYABLE_STATE_SCOPE_SHARED", result.playableStateScope());
    assertEquals("SHARED", result.stateScope());
    assertEquals("PRESEEDED_ONLY", result.characterCreationPolicy());
    assertEquals(scope.evaluatedAt(), result.evaluatedAt());
    assertEquals(scope.connectScopeExpiresAt(), result.connectScopeExpiresAt());
    assertEquals(scope.connectScopeId(), result.connectScopeId());
    assertFalse(result.toString().contains(scope.connectScopeId()));
    org.mockito.Mockito.verify(owner)
        .listRealmScopes("canonical-bootstrap", "source-tenant", "demo");
    verifyNoInteractions(
        accountRepository, sessionService, jwtUtil, retainedSubscriptionEvidenceRepository);
  }

  @Test
  void bootstrapRealmsWithoutCanonicalCompositionRefusesBeforeAccountOrSessionWork() {
    org.mockito.Mockito.clearInvocations(accountRepository, sessionService);

    AuthenticationException failure =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms("bootstrap", "source-tenant", "demo"));

    assertEquals("AUTH_UNAVAILABLE", failure.getCode());
    verifyNoInteractions(accountRepository, sessionService);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CONNECT_CONTEXT_INVALID",
        "AUTH_UNAVAILABLE",
        "ENTITLEMENT_UNAVAILABLE",
        "ADMISSION_POINTER_UNAVAILABLE"
      })
  void bootstrapRealmsPreservesCanonicalSourceRefusal(String code) {
    AccountCanonicalPublicRealmScopeOwner owner =
        org.mockito.Mockito.mock(AccountCanonicalPublicRealmScopeOwner.class);
    service.setCanonicalPublicRealmScopeOwner(owner);
    AuthenticationException failure = new AuthenticationException(code, "Canonical source refusal");
    when(owner.listRealmScopes("bootstrap", "source-tenant", "demo")).thenThrow(failure);
    org.mockito.Mockito.clearInvocations(accountRepository, sessionService);

    assertSame(
        failure,
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms("bootstrap", "source-tenant", "demo")));

    verifyNoInteractions(accountRepository, sessionService);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bootstrapRealmsRefusesAmbientSqlBeforeCanonicalSource(boolean synchronizationOnly) {
    AccountCanonicalPublicRealmScopeOwner owner =
        org.mockito.Mockito.mock(AccountCanonicalPublicRealmScopeOwner.class);
    service.setCanonicalPublicRealmScopeOwner(owner);
    if (synchronizationOnly) TransactionSynchronizationManager.initSynchronization();
    else TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThrows(
          IllegalStateException.class,
          () -> service.listBootstrapRealms("bootstrap", "source-tenant", "demo"));
      verifyNoInteractions(owner);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void bootstrapRealmsKeepsPlayerContextForTheComposedOwnersWorkloadGuard() {
    AccountCanonicalPublicRealmScopeOwner owner =
        org.mockito.Mockito.mock(AccountCanonicalPublicRealmScopeOwner.class);
    service.setCanonicalPublicRealmScopeOwner(owner);
    SessionContext.setContext("e9e3aa54-9712-4e01-8767-f23f3c662e64", List.of(), Map.of());
    when(owner.listRealmScopes("bootstrap", "source-tenant", "demo"))
        .thenAnswer(
            invocation -> {
              assertTrue(SessionContext.hasAuthenticatedCallerContext());
              throw new IllegalStateException("Canonical source requires workload-only context");
            });
    try {
      assertThrows(
          IllegalStateException.class,
          () -> service.listBootstrapRealms("bootstrap", "source-tenant", "demo"));
      assertTrue(SessionContext.hasAuthenticatedCallerContext());
    } finally {
      SessionContext.clear();
    }
  }

  @Test
  void bootstrapWorldsDelegatesExactCredentialAndCompleteResultToCanonicalOwner() {
    AccountPublicBootstrapWorldsOwner owner =
        org.mockito.Mockito.mock(AccountPublicBootstrapWorldsOwner.class);
    service.setPublicBootstrapWorldsOwner(owner);
    List<BootstrapWorldDto> worlds =
        List.of(
            new BootstrapWorldDto("tenant-alpha", "alpha", "Alpha"),
            new BootstrapWorldDto("tenant-zulu", "zulu", "Zulu"));
    when(owner.listWorlds("canonical-bootstrap")).thenReturn(worlds);
    org.mockito.Mockito.clearInvocations(accountRepository, sessionService);

    assertSame(worlds, service.listBootstrapWorlds("canonical-bootstrap"));

    org.mockito.Mockito.verify(owner).listWorlds("canonical-bootstrap");
    verifyNoInteractions(accountRepository, sessionService);
  }

  @Test
  void bootstrapWorldsWithoutCanonicalCompositionRefusesBeforeAccountOrSessionWork() {
    org.mockito.Mockito.clearInvocations(accountRepository, sessionService);

    AuthenticationException failure =
        assertThrows(AuthenticationException.class, () -> service.listBootstrapWorlds("bootstrap"));

    assertEquals("AUTH_UNAVAILABLE", failure.getCode());
    verifyNoInteractions(accountRepository, sessionService);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"AUTH_UNAVAILABLE", "ENTITLEMENT_UNAVAILABLE", "ADMISSION_POINTER_UNAVAILABLE"})
  void bootstrapWorldsPreservesCanonicalSourceFailure(String code) {
    AccountPublicBootstrapWorldsOwner owner =
        org.mockito.Mockito.mock(AccountPublicBootstrapWorldsOwner.class);
    service.setPublicBootstrapWorldsOwner(owner);
    AuthenticationException unavailable = new AuthenticationException(code, "Source unavailable");
    when(owner.listWorlds("bootstrap")).thenThrow(unavailable);
    org.mockito.Mockito.clearInvocations(accountRepository, sessionService);

    assertSame(
        unavailable,
        assertThrows(
            AuthenticationException.class, () -> service.listBootstrapWorlds("bootstrap")));

    verifyNoInteractions(accountRepository, sessionService);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bootstrapWorldsRefusesAmbientTransactionOrSynchronizationBeforeCanonicalRead(
      boolean synchronizationOnly) {
    AccountPublicBootstrapWorldsOwner owner =
        org.mockito.Mockito.mock(AccountPublicBootstrapWorldsOwner.class);
    service.setPublicBootstrapWorldsOwner(owner);
    if (synchronizationOnly) {
      TransactionSynchronizationManager.initSynchronization();
    } else {
      TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    try {
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> service.listBootstrapWorlds("bootstrap"));

      assertTrue(failure.getMessage().contains("ambient SQL"));
      verifyNoInteractions(owner);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void bootstrapWorldsRetainsPlayerContextAndCanonicalWorkloadGuard() {
    var callerVerifier = org.mockito.Mockito.mock(AccountPlayerBootstrapCallerVerifier.class);
    var targets = org.mockito.Mockito.mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    var entitlements = org.mockito.Mockito.mock(AccountPublicBootstrapEntitlementOwner.class);
    service.setPublicBootstrapWorldsOwner(
        new AccountPublicBootstrapWorldsOwner(
            callerVerifier, targets, entitlements, Clock.systemUTC()));
    SessionContext.setContext("account", List.of(), Map.of());
    try {
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> service.listBootstrapWorlds("bootstrap"));

      assertTrue(failure.getMessage().contains("workload-only"));
      assertTrue(SessionContext.hasAuthenticatedCallerContext());
      verifyNoInteractions(callerVerifier, targets, entitlements);
    } finally {
      SessionContext.clear();
    }
  }

  private enum IdentityFailure {
    MISSING_UUID,
    NIL_UUID,
    MISSING_PROVENANCE,
    MISSING_SOURCE_ROW,
    WRONG_SOURCE_ROW,
    MISSING_LOOKUP,
    DIFFERENT_LOOKUP_OWNER,
    DIFFERENT_LOOKUP_UUID,
    READBACK_UNAVAILABLE
  }

  private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
      ordinaryIssuanceIdentityFailures() {
    return java.util.Arrays.stream(OrdinaryIssuance.values())
        .flatMap(
            mode ->
                java.util.Arrays.stream(IdentityFailure.values())
                    .map(failure -> org.junit.jupiter.params.provider.Arguments.of(mode, failure)));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("ordinaryIssuanceIdentityFailures")
  void ordinaryIssuanceRejectsUnverifiedOwnerBeforeSigningOrConsumingChallenge(
      OrdinaryIssuance mode, IdentityFailure failure) {
    Account account = ordinaryIssuanceAccount();
    var challenge = ordinaryIssuanceChallenge(account);
    switch (failure) {
      case MISSING_UUID -> account.setAccountUuid(null);
      case NIL_UUID -> account.setAccountUuid(new UUID(0L, 0L));
      case MISSING_PROVENANCE -> account.setAccountUuidProvenance(null);
      case MISSING_SOURCE_ROW -> account.setAccountUuidSourceNumericId(null);
      case WRONG_SOURCE_ROW -> account.setAccountUuidSourceNumericId(43L);
      case MISSING_LOOKUP ->
          when(accountRepository.findByAccountUuid(account.getAccountUuid()))
              .thenReturn(Optional.empty());
      case DIFFERENT_LOOKUP_OWNER, DIFFERENT_LOOKUP_UUID -> {
        Account different = new Account();
        different.setId(failure == IdentityFailure.DIFFERENT_LOOKUP_OWNER ? 43L : 42L);
        different.setAccountUuid(
            failure == IdentityFailure.DIFFERENT_LOOKUP_UUID
                ? UUID.randomUUID()
                : account.getAccountUuid());
        different.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
        different.setAccountUuidSourceNumericId(different.getId());
        when(accountRepository.findByAccountUuid(account.getAccountUuid()))
            .thenReturn(Optional.of(different));
      }
      case READBACK_UNAVAILABLE ->
          when(accountRepository.findByAccountUuid(account.getAccountUuid()))
              .thenThrow(
                  new org.springframework.dao.DataAccessResourceFailureException(
                      "private storage detail"));
    }

    AuthenticationException denied =
        assertThrows(AuthenticationException.class, () -> issueOrdinaryCredential(mode));

    assertEquals("AUTH_UNAVAILABLE", denied.getCode());
    assertEquals("Account authority is unavailable", denied.getMessage());
    org.mockito.Mockito.verify(jwtUtil, org.mockito.Mockito.never())
        .generateToken(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyMap());
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository, org.mockito.Mockito.never())
        .delete(challenge);
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(sessionService);
  }

  @ParameterizedTest
  @EnumSource(OrdinaryIssuance.class)
  void ordinaryIssuanceKeepsItsProfileContractAfterExactOwnerReadback(OrdinaryIssuance mode) {
    Account account = ordinaryIssuanceAccount();
    var challenge = ordinaryIssuanceChallenge(account);

    String token = issueOrdinaryCredential(mode);

    var claims = jwtUtil.parseToken(token).getPayload();
    assertEquals(account.getAccountUuid().toString(), claims.getSubject());
    assertEquals(account.getAccountUuid().toString(), claims.get("accountId", String.class));
    org.mockito.Mockito.verify(accountRepository).findByAccountUuid(account.getAccountUuid());
    if (mode != OrdinaryIssuance.GAMEPLAY_PASSWORD) {
      org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).delete(challenge);
    }
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(
            org.mockito.ArgumentMatchers.eq(42L),
            org.mockito.ArgumentMatchers.eq(token),
            org.mockito.ArgumentMatchers.anyLong());
  }

  @ParameterizedTest
  @EnumSource(OrdinaryIssuance.class)
  void invalidCredentialsDoNotConsultUuidOwnerReadback(OrdinaryIssuance mode) {
    Account account = ordinaryIssuanceAccount();
    ordinaryIssuanceChallenge(account);
    account.setAccountUuid(null);

    AuthenticationException denied =
        assertThrows(
            AuthenticationException.class,
            () -> {
              switch (mode) {
                case CONTROL_UI -> service.authenticate("owner@example.com", "wrong");
                case GAMEPLAY_PASSWORD ->
                    service.authenticateForGameplay("owner@example.com", "wrong");
                case EMAIL_OTP -> service.verifyEmailLoginOtp("owner@example.com", "wrong");
                case PLAYER_BOOTSTRAP -> service.issuePlayerBootstrap("owner@example.com", "wrong");
              }
            });

    assertEquals(AuthenticationErrorCodes.INVALID_CREDENTIALS, denied.getCode());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .findByAccountUuid(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(sessionService);
  }

  private Account ordinaryIssuanceAccount() {
    Account account = new Account();
    account.setId(42L);
    account.setUsername("owner");
    account.setEmail("owner@example.com");
    account.setEmailVerified(true);
    account.setLoginAuthModes("PASSWORD,EMAIL_OTP");
    account.setPasswordHash(hash("password"));
    setPersistedAuthenticationIdentity(account);
    when(accountRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(account));
    return account;
  }

  private net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge
      ordinaryIssuanceChallenge(Account account) {
    var challenge = new net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge();
    challenge.setId(3L);
    challenge.setAccountId(account.getId());
    challenge.setCodeHash(hash("123456"));
    challenge.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
    when(accountEmailLoginChallengeRepository.findByAccountId(account.getId()))
        .thenReturn(Optional.of(challenge));
    return challenge;
  }

  private String issueOrdinaryCredential(OrdinaryIssuance mode) {
    if (mode == OrdinaryIssuance.PLAYER_BOOTSTRAP) {
      PlayerBootstrapResult result = service.issuePlayerBootstrap("owner@example.com", "123456");
      assertNotNull(
          net.firedevops.firemud.accountservice.AccountUuidText.parseOrNull(result.accountId()));
      return result.bootstrapToken();
    }
    AuthenticationResult result =
        switch (mode) {
          case CONTROL_UI -> service.authenticate("owner@example.com", "123456");
          case GAMEPLAY_PASSWORD ->
              service.authenticateForGameplay("owner@example.com", "password");
          case EMAIL_OTP -> service.verifyEmailLoginOtp("owner@example.com", "123456");
          case PLAYER_BOOTSTRAP -> throw new IllegalStateException("Bootstrap handled above");
        };
    assertEquals(
        jwtUtil.parseToken(result.authToken()).getPayload().getSubject(), result.accountId());
    return result.authToken();
  }

  @ParameterizedTest
  @EnumSource(AccountIdentityProvenance.class)
  void resolvesAccountUuidToPrivateStorageIdForEachSupportedProvenance(
      AccountIdentityProvenance provenance) {
    UUID accountUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    Account account = new Account();
    account.setId(42L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(provenance);
    account.setAccountUuidSourceNumericId(42L);
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.of(account));

    assertEquals(42L, service.resolveAccountStorageId(accountUuid));
    org.mockito.Mockito.verify(accountRepository).findByAccountUuid(accountUuid);
  }

  @Test
  void rejectsNullAccountUuidBeforeResolvingPrivateStorageId() {
    assertThrows(IllegalArgumentException.class, () -> service.resolveAccountStorageId(null));
    verifyNoInteractions(accountRepository);
  }

  @Test
  void rejectsNilAccountUuidBeforeResolvingPrivateStorageId() {
    UUID nilUuid = new UUID(0L, 0L);

    assertThrows(IllegalArgumentException.class, () -> service.resolveAccountStorageId(nilUuid));
    verifyNoInteractions(accountRepository);
  }

  @Test
  void rejectsAccountUuidWithoutPersistedAccountRow() {
    UUID accountUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.empty());

    assertThrows(
        IllegalArgumentException.class, () -> service.resolveAccountStorageId(accountUuid));
  }

  @Test
  void rejectsAccountUuidMappingWhenReturnedRowHasDifferentUuid() {
    UUID requestedUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    Account account = new Account();
    account.setId(42L);
    account.setAccountUuid(UUID.fromString("b45dc804-6626-48e4-a657-4a8dfc4e9203"));
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(42L);
    when(accountRepository.findByAccountUuid(requestedUuid)).thenReturn(Optional.of(account));

    assertThrows(IllegalStateException.class, () -> service.resolveAccountStorageId(requestedUuid));
  }

  @Test
  void rejectsAccountUuidMappingWithoutPrivateStorageRowId() {
    UUID accountUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    Account account = new Account();
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(42L);
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.of(account));

    assertThrows(IllegalStateException.class, () -> service.resolveAccountStorageId(accountUuid));
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L})
  void rejectsAccountUuidMappingWithInvalidPrivateStorageRowId(long accountId) {
    UUID accountUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    Account account = new Account();
    account.setId(accountId);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(accountId);
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.of(account));

    assertThrows(IllegalStateException.class, () -> service.resolveAccountStorageId(accountUuid));
  }

  @Test
  void rejectsAccountUuidMappingWithoutPrivateSourceNumericId() {
    UUID accountUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    Account account = new Account();
    account.setId(42L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.of(account));

    assertThrows(IllegalStateException.class, () -> service.resolveAccountStorageId(accountUuid));
  }

  @Test
  void rejectsAccountUuidMappingWhoseProvenanceSourceDoesNotMatchTheRow() {
    UUID accountUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    Account account = new Account();
    account.setId(42L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION);
    account.setAccountUuidSourceNumericId(43L);
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.of(account));

    assertThrows(IllegalStateException.class, () -> service.resolveAccountStorageId(accountUuid));
  }

  @Test
  void rejectsAccountUuidMappingWithoutRecognizedProvenance() {
    UUID accountUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    Account account = new Account();
    account.setId(42L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidSourceNumericId(42L);
    when(accountRepository.findByAccountUuid(accountUuid)).thenReturn(Optional.of(account));

    assertThrows(IllegalStateException.class, () -> service.resolveAccountStorageId(accountUuid));
  }

  @Test
  void resolvesPresenceAccountUuidsInOneBatchAndOmitsUnknownOrUnprovenRows() {
    UUID validUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    UUID unknownUuid = UUID.fromString("b45dc804-6626-48e4-a657-4a8dfc4e9203");
    UUID unprovenUuid = UUID.fromString("b0c47761-2166-4311-8f2b-327f76f9fd8d");
    Account valid = new Account();
    valid.setId(42L);
    valid.setAccountUuid(validUuid);
    valid.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    valid.setAccountUuidSourceNumericId(42L);
    Account unproven = new Account();
    unproven.setId(43L);
    unproven.setAccountUuid(unprovenUuid);
    unproven.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION);
    unproven.setAccountUuidSourceNumericId(44L);
    when(accountRepository.findByAccountUuids(List.of(validUuid, unknownUuid, unprovenUuid)))
        .thenReturn(List.of(valid, unproven));

    assertEquals(
        Map.of(validUuid, 42L),
        service.resolveAccountStorageIds(List.of(validUuid, unknownUuid, unprovenUuid)));
    org.mockito.Mockito.verify(accountRepository)
        .findByAccountUuids(List.of(validUuid, unknownUuid, unprovenUuid));
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .findByAccountUuid(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsPresenceAccountUuidBatchWithAliasedPrivateRows() {
    UUID firstUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    UUID secondUuid = UUID.fromString("b45dc804-6626-48e4-a657-4a8dfc4e9203");
    Account first = new Account();
    first.setId(42L);
    first.setAccountUuid(firstUuid);
    first.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    first.setAccountUuidSourceNumericId(42L);
    Account second = new Account();
    second.setId(42L);
    second.setAccountUuid(secondUuid);
    second.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    second.setAccountUuidSourceNumericId(42L);
    when(accountRepository.findByAccountUuids(List.of(firstUuid, secondUuid)))
        .thenReturn(List.of(first, second));

    assertThrows(
        IllegalStateException.class,
        () -> service.resolveAccountStorageIds(List.of(firstUuid, secondUuid)));
  }

  @Test
  void rejectsNilAccountUuidInBatchBeforeRepositoryRead() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.resolveAccountStorageIds(List.of(new UUID(0L, 0L))));
    verifyNoInteractions(accountRepository);
  }

  @Test
  void createAccountPersistsOnlyGlobalIdentity() {
    CreateAccountRequest request =
        new CreateAccountRequest("demo", "  DEMO@example.com ", "password");
    UUID accountUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
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
  void createAccountRejectsPublicIdentityWithoutExactPersistedSourceRow() {
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              saved.setId(1L);
              saved.setAccountUuid(UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b"));
              saved.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
              saved.setAccountUuidSourceNumericId(2L);
              return saved;
            });

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createAccount(
                new CreateAccountRequest("demo", "demo@example.com", "password")));

    verifyNoInteractions(
        accountAuditOutboxRepository, profileRepository, accountTenantMembershipRepository);
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

  /**
   * These fixtures prove facade ordering/projection; physical owner atomicity is integration proof.
   */
  @ParameterizedTest
  @CsvSource({"JOINED,2,1", "ALREADY_ACTIVE,7,3", "JOINED,8,3"})
  void canonicalJoinProjectsFirstActiveAndRestoredOwnerSnapshots(
      String outcome, long version, long generation) {
    var f = canonicalJoinFixture();
    f.terminal(outcome, version, generation);
    var result = f.browserJoin();
    assertTrue(result.success());
    assertEquals(outcome, result.outcomeCode());
    assertEquals(f.scope.accountId().toString(), result.accountId());
    assertEquals(f.scope.tenantId(), result.tenantId());
    assertEquals(701L, result.membershipId());
    assertEquals(version, result.membershipVersion());
    assertEquals(generation, result.membershipAuthorityGeneration());
    assertFalse(result.replayed());
    assertTrue(result.gameplayAdmissionAllowed());
    org.mockito.Mockito.verify(f.demos).revalidate(f.demo);
    org.mockito.Mockito.verify(f.targets, org.mockito.Mockito.times(2))
        .readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(f.terminal)
        .commitCanonicalJoin(f.scope, f.requestId, f.binding, null, null, null);
    verifyNoInteractions(f.hosting, f.observer, accountAuditOutboxRepository);
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void canonicalCommittedRetryRevalidatesCurrentPolicyBeforeReturningRetainedTerminalProof() {
    var f = canonicalJoinFixture();
    when(f.pending.status()).thenReturn("COMMITTED");
    f.terminal("JOINED", 2L, 1L);
    var result = f.browserJoin();
    assertTrue(result.success());
    assertTrue(result.replayed());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .bindCanonicalPolicyEvidence(f.requestId, f.scope, f.binding, true, 1L);
    org.mockito.Mockito.verify(f.sources, org.mockito.Mockito.times(2))
        .readCurrentSourceSelection(f.scope.tenantId());
    org.mockito.Mockito.verify(f.demos).revalidate(f.demo);
    verifyNoInteractions(f.hosting, f.observer);
    org.mockito.Mockito.verify(f.terminal)
        .commitCanonicalJoin(f.scope, f.requestId, f.binding, null, null, null);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void canonicalConcurrentCommitUsesLockedReplayStateAndStillChecksCurrentPolicy(boolean gameplay) {
    var f = canonicalJoinFixture();
    when(f.committed.status()).thenReturn("COMMITTED");
    when(accountJoinOperationRepository.findCanonicalEvidenceForUpdateByRequestId(f.requestId))
        .thenReturn(Optional.of(f.pending), Optional.of(f.committed));
    when(accountJoinOperationRepository.bindCanonicalPolicyEvidence(
            f.requestId, f.scope, f.binding, true, 1L))
        .thenReturn(f.committed);
    when(f.demo.gameplayAvailable()).thenReturn(gameplay);

    var result = f.browserJoin();

    assertTrue(result.replayed());
    assertEquals(gameplay, result.success());
    assertEquals(gameplay ? "JOINED" : "TENANT_BILLING_BLOCKED", result.outcomeCode());
    org.mockito.Mockito.verify(f.demos).revalidate(f.demo);
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .finishCanonicalFailure(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    if (!gameplay) verifyNoInteractions(f.terminal);
  }

  @Test
  void canonicalCommittedRetryCannotUseUnavailableCurrentEntitlementOrRewriteTerminalBytes() {
    var f = canonicalJoinFixture();
    when(f.pending.status()).thenReturn("COMMITTED");
    when(f.demos.readCurrent(f.scope.tenantId()))
        .thenThrow(new DataAccessResourceFailureException("controlled current source outage"));
    assertEquals(
        "ENTITLEMENT_UNAVAILABLE",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .recordCanonicalAttemptFailure(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .finishCanonicalFailure(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    verifyNoInteractions(f.terminal);
  }

  @Test
  void canonicalJoinWithoutConsumerCompositionRefusesBeforeAccountIntentOrAuthorityWork() {
    assertEquals(
        "AUTH_UNAVAILABLE",
        assertThrows(
                AuthenticationException.class,
                () ->
                    service.joinPublicProduction(
                        "bootstrap", new JoinPublicProductionRequest("scope", "request")))
            .getCode());
    verifyNoInteractions(
        accountRepository,
        accountJoinOperationRepository,
        accountTenantMembershipRepository,
        retainedSubscriptionEvidenceRepository);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "PUBLIC_PRODUCTION_ADMISSION_DENIED",
        "CONNECT_SCOPE_INVALID",
        "REALM_UNAVAILABLE"
      })
  void canonicalFailedRetryPreservesTheOriginalOutcomeWithoutMembershipOrPolicyMutation(
      String outcome) {
    var f = canonicalJoinFixture();
    when(f.pending.status()).thenReturn("FAILED");
    when(f.pending.outcome()).thenReturn(outcome);
    when(accountJoinOperationRepository.finishCanonicalFailure(
            f.requestId, f.scope, f.binding, outcome))
        .thenReturn(f.pending);
    org.mockito.Mockito.doThrow(new IllegalStateException("Game Session unavailable"))
        .when(f.targets)
        .resolvePublishedRealmAdmissionTarget(org.mockito.ArgumentMatchers.any());
    var result = f.browserJoin();
    assertFalse(result.success());
    assertTrue(result.replayed());
    assertEquals(outcome, result.outcomeCode());
    verifyNoInteractions(f.targets, f.sources, f.demos, f.hosting, f.observer, f.terminal);
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .finishCanonicalFailure(f.requestId, f.scope, f.binding, outcome);
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.times(2))
        .isAccountSessionActive(f.account.getId(), f.bootstrap);
  }

  @Test
  void canonicalFailedRetryStillChecksLockedRequestIdentity() {
    var f = canonicalJoinFixture();
    when(f.pending.status()).thenReturn("FAILED");
    when(f.pending.outcome()).thenReturn("PUBLIC_PRODUCTION_ADMISSION_DENIED");
    when(accountJoinOperationRepository.finishCanonicalFailure(
            f.requestId, f.scope, f.binding, "PUBLIC_PRODUCTION_ADMISSION_DENIED"))
        .thenThrow(
            new AccountJoinOperationRepository.CanonicalJoinOperationConflictException(
                "Controlled retained identity conflict"));
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.times(2))
        .findCanonicalEvidenceForUpdateByRequestId(f.requestId);
    verifyNoInteractions(f.targets, f.sources, f.demos, f.hosting, f.observer, f.terminal);
  }

  @Test
  void canonicalFailedRetryStillRechecksCallerBeforeLockedReplay() {
    var f = canonicalJoinFixture();
    when(f.pending.status()).thenReturn("FAILED");
    when(sessionService.isAccountSessionActive(f.account.getId(), f.bootstrap))
        .thenReturn(true, false);
    assertEquals(
        "CONNECT_CONTEXT_INVALID",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .finishCanonicalFailure(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    verifyNoInteractions(f.targets, f.sources, f.demos, f.hosting, f.observer, f.terminal);
  }

  @ParameterizedTest
  @CsvSource({
    "true,false,PUBLIC_PRODUCTION_ADMISSION_DENIED",
    "false,false,TENANT_BILLING_BLOCKED"
  })
  void canonicalJoinRetainsAuthoritativePolicyDenialsWithoutCallingTransitionOwner(
      boolean gameplay, boolean open, String code) {
    var f = canonicalJoinFixture();
    when(f.demo.gameplayAvailable()).thenReturn(gameplay);
    when(f.demo.allowPublicJoin()).thenReturn(open);
    var result = f.browserJoin();
    assertFalse(result.success());
    assertEquals(code, result.outcomeCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .finishCanonicalFailure(f.requestId, f.scope, f.binding, code);
    verifyNoInteractions(f.terminal);
  }

  @Test
  void canonicalActiveMemberCanReturnCurrentSnapshotWithClosedEnrollment() {
    var f = canonicalJoinFixture();
    when(f.demo.allowPublicJoin()).thenReturn(false);
    AccountTenantMembership current = f.currentMembership;
    current.setLifecycleState("ACTIVE");
    current.setGameplayAdmissionAllowed(true);
    current.setMembershipVersion(7L);
    current.setMembershipAuthorityGeneration(3L);
    org.mockito.Mockito.doReturn(Optional.of(current))
        .when(accountTenantMembershipRepository)
        .findFreshMembershipForUpdate(f.scope.accountId(), f.scope.tenantId());
    f.terminal("ALREADY_ACTIVE", 7L, 3L);
    var result = f.browserJoin();
    assertTrue(result.success());
    assertEquals("ALREADY_ACTIVE", result.outcomeCode());
    assertEquals(7L, result.membershipVersion());
    assertEquals(3L, result.membershipAuthorityGeneration());
  }

  @Test
  void canonicalActiveControlOnlyMemberReturnsExactSnapshotWithoutGameplayOrRoleUpgrade() {
    var f = canonicalJoinFixture();
    f.terminal("ALREADY_ACTIVE", 7L, 3L);
    f.currentMembership.setGameplayAdmissionAllowed(false);
    f.joined.set(true);
    when(f.demo.allowPublicJoin()).thenReturn(false);
    var result = f.browserJoin();
    assertTrue(result.success());
    assertEquals("ALREADY_ACTIVE", result.outcomeCode());
    assertEquals(7L, result.membershipVersion());
    assertEquals(3L, result.membershipAuthorityGeneration());
    assertFalse(result.gameplayAdmissionAllowed());
    org.mockito.Mockito.verify(accountTenantMembershipRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(accountAuditOutboxRepository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"source", "demo", "final-demo", "member", "terminal"})
  void canonicalJoinOutageKeepsTheIntentRetryableAndRecoversTheSameRequest(String stage) {
    var f = canonicalJoinFixture();
    RuntimeException unavailable =
        new DataAccessResourceFailureException("controlled owner outage");
    switch (stage) {
      case "source" ->
          when(f.sources.readCurrentSourceSelection(f.scope.tenantId()))
              .thenThrow(unavailable)
              .thenReturn(f.source);
      case "demo" ->
          when(f.demos.readCurrent(f.scope.tenantId())).thenThrow(unavailable).thenReturn(f.demo);
      case "final-demo" ->
          when(f.demos.revalidate(f.demo)).thenThrow(unavailable).thenReturn(f.demo);
      case "member" ->
          org.mockito.Mockito.doThrow(unavailable)
              .doAnswer(
                  invocation ->
                      f.joined.get() ? Optional.of(f.currentMembership) : Optional.empty())
              .when(accountTenantMembershipRepository)
              .findFreshMembershipForUpdate(f.scope.accountId(), f.scope.tenantId());
      case "terminal" ->
          org.mockito.Mockito.doThrow(unavailable)
              .doAnswer(
                  invocation -> {
                    f.joined.set(true);
                    return f.committed;
                  })
              .when(f.terminal)
              .commitCanonicalJoin(f.scope, f.requestId, f.binding, null, null, null);
      default -> throw new AssertionError(stage);
    }
    assertEquals(
        "ENTITLEMENT_UNAVAILABLE",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .recordCanonicalAttemptFailure(
            f.requestId, f.scope, f.binding, "UNAVAILABLE", "ENTITLEMENT_UNAVAILABLE");
    assertTrue(f.browserJoin().success());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.times(2))
        .insertCanonicalIntent(f.requestId, f.scope, f.binding);
  }

  @Test
  void canonicalJoinSourceCheckpointChangeRefusesBeforePolicyBindingOrTransition() {
    var f = canonicalJoinFixture();
    when(f.source.matches(f.source.checkpoint())).thenReturn(false);
    assertEquals(
        "ENTITLEMENT_UNAVAILABLE",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .bindCanonicalPolicyEvidence(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyLong());
    verifyNoInteractions(f.terminal);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CONNECT_SCOPE_INVALID", "AUTH_UNAVAILABLE", "CONNECT_SCOPE_MISMATCH"})
  void canonicalJoinReaderRefusalPrecedesDurableIntentAndAllAuthorityWork(String code) {
    var f = canonicalJoinFixture();
    when(f.reader.read(f.scope.accountId(), f.scope.connectScopeId()))
        .thenThrow(new AuthenticationException(code, "controlled reader refusal"));
    assertEquals(code, assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    verifyNoInteractions(accountJoinOperationRepository, f.sources, f.demos, f.terminal);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADMISSION_POINTER_UNAVAILABLE", "CONNECT_SCOPE_MISMATCH"})
  void canonicalJoinTargetFailureRetainsTheOriginalIntentWithoutAWrite(String code) {
    var f = canonicalJoinFixture();
    org.mockito.Mockito.doThrow(new AuthenticationException(code, "controlled target refusal"))
        .when(f.targets)
        .resolvePublishedRealmAdmissionTarget(org.mockito.ArgumentMatchers.any());
    assertEquals(code, assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    if (code.equals("CONNECT_SCOPE_MISMATCH")) {
      org.mockito.Mockito.verify(accountJoinOperationRepository)
          .finishCanonicalFailure(f.requestId, f.scope, f.binding, code);
    } else {
      org.mockito.Mockito.verify(accountJoinOperationRepository)
          .recordCanonicalAttemptFailure(f.requestId, f.scope, f.binding, "UNAVAILABLE", code);
    }
    verifyNoInteractions(f.terminal);
  }

  @Test
  void canonicalJoinChangedTargetBetweenStrictReadsIsTerminalWithoutMembershipEffects() {
    var f = canonicalJoinFixture();
    var changed =
        org.mockito.Mockito.mock(Target.class, org.mockito.AdditionalAnswers.delegatesTo(f.target));
    when(changed.activeWorldEpoch()).thenReturn(2L);
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            invocation ->
                f.resolve(
                    invocation.getArgument(0), reads.incrementAndGet() == 1 ? f.target : changed))
        .when(f.targets)
        .resolvePublishedRealmAdmissionTarget(org.mockito.ArgumentMatchers.any());
    assertEquals(
        "CONNECT_SCOPE_MISMATCH",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .finishCanonicalFailure(f.requestId, f.scope, f.binding, "CONNECT_SCOPE_MISMATCH");
    org.mockito.Mockito.verify(f.targets, org.mockito.Mockito.times(2))
        .readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(f.terminal);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"retainedTarget", "resolvedSelector", "publishedSelector", "publishedTarget"})
  void canonicalJoinLocallyVerifiedTargetMismatchIsTerminal(String mismatch) {
    var f = canonicalJoinFixture();
    if (mismatch.equals("retainedTarget") || mismatch.equals("resolvedSelector")) {
      org.mockito.Mockito.doAnswer(
              invocation -> {
                var resolved = f.resolve(invocation.getArgument(0), f.target);
                if (mismatch.equals("retainedTarget")) {
                  when(f.target.pointerVersion()).thenReturn(f.scope.pointerVersion() + 1L);
                } else {
                  when(resolved.selector())
                      .thenReturn(
                          org.mockito.Mockito.mock(
                              net.firedevops.firemud.common.gamesession
                                  .CanonicalGameplayAdmissionDecisionReadGrpcCodec
                                  .RetainedTargetSelector.class));
                }
                return resolved;
              })
          .when(f.targets)
          .resolvePublishedRealmAdmissionTarget(org.mockito.ArgumentMatchers.any());
    } else {
      org.mockito.Mockito.doAnswer(
              invocation -> {
                var published =
                    org.mockito.Mockito.mock(
                        net.firedevops.firemud.common.gamesession
                            .CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence
                            .class);
                when(published.selector())
                    .thenReturn(
                        mismatch.equals("publishedSelector")
                            ? org.mockito.Mockito.mock(
                                net.firedevops.firemud.common.world
                                    .CanonicalGameplayRosterOwnerReadEvidence.Request.class)
                            : invocation.getArgument(0));
                when(published.currentTarget())
                    .thenReturn(
                        mismatch.equals("publishedTarget")
                            ? org.mockito.Mockito.mock(Target.class)
                            : f.target);
                return published;
              })
          .when(f.targets)
          .readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any());
    }

    assertEquals(
        "CONNECT_SCOPE_MISMATCH",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .finishCanonicalFailure(f.requestId, f.scope, f.binding, "CONNECT_SCOPE_MISMATCH");
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .recordCanonicalAttemptFailure(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(f.terminal);
  }

  @ParameterizedTest
  @ValueSource(strings = {"resolver", "published"})
  void canonicalJoinRemoteValidationFailureRemainsRetryable(String owner) {
    var f = canonicalJoinFixture();
    var failure = new IllegalArgumentException("Remote owner could not validate evidence");
    if (owner.equals("resolver")) {
      org.mockito.Mockito.doThrow(failure)
          .when(f.targets)
          .resolvePublishedRealmAdmissionTarget(org.mockito.ArgumentMatchers.any());
    } else {
      org.mockito.Mockito.doThrow(failure)
          .when(f.targets)
          .readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any());
    }
    var result = assertThrows(AuthenticationException.class, f::browserJoin);
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", result.getCode());
    assertEquals(
        "Gameplay target authority is unavailable; retry the same request", result.getMessage());
    assertSame(failure, result.getCause());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .recordCanonicalAttemptFailure(
            f.requestId, f.scope, f.binding, "UNAVAILABLE", "ADMISSION_POINTER_UNAVAILABLE");
    verifyNoInteractions(f.terminal);
  }

  @Test
  void canonicalJoinStrictPublishedSourceOutageCannotUseResolverOnlyAuthority() {
    var f = canonicalJoinFixture();
    org.mockito.Mockito.doThrow(
            new IllegalStateException("controlled unavailable World ACTIVE source"))
        .when(f.targets)
        .readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any());
    assertEquals(
        "ADMISSION_POINTER_UNAVAILABLE",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .recordCanonicalAttemptFailure(
            f.requestId, f.scope, f.binding, "UNAVAILABLE", "ADMISSION_POINTER_UNAVAILABLE");
    verifyNoInteractions(f.terminal);
  }

  @ParameterizedTest
  @ValueSource(longs = {-1L, 120L})
  void canonicalJoinFutureOrExpiredScopeCannotReachMembershipCommit(long seconds) {
    var f = canonicalJoinFixture();
    f.compose(
        Clock.fixed(
            Instant.parse(f.scope.evaluatedAt()).plusSeconds(seconds), java.time.ZoneOffset.UTC));
    assertEquals(
        "CONNECT_SCOPE_INVALID",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    verifyNoInteractions(f.terminal);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void canonicalJoinExpiryAtCommitGatesDoesNotRewritePendingIntent(boolean afterCoordinator) {
    var f = canonicalJoinFixture();
    var clock = org.mockito.Mockito.mock(Clock.class);
    when(clock.instant())
        .thenAnswer(
            invocation ->
                Instant.parse(f.scope.evaluatedAt())
                    .plusSeconds(!afterCoordinator || f.joined.get() ? 120 : 1));
    f.compose(clock);

    assertEquals(
        "CONNECT_SCOPE_INVALID",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .insertCanonicalIntent(f.requestId, f.scope, f.binding);
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .finishCanonicalFailure(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .recordCanonicalAttemptFailure(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    // This mock-level proof observes rollback invocation, not physical SQL rollback.
    org.mockito.Mockito.verify(transactionManager).rollback(org.mockito.ArgumentMatchers.any());
    if (afterCoordinator) {
      org.mockito.Mockito.verify(f.terminal)
          .commitCanonicalJoin(f.scope, f.requestId, f.binding, null, null, null);
    } else {
      verifyNoInteractions(f.terminal);
    }
  }

  @Test
  void canonicalJoinReturnsCommittedReceiptWhenScopeExpiresAfterCommit() {
    var f = canonicalJoinFixture();
    var committed = new AtomicBoolean();
    var clock = org.mockito.Mockito.mock(Clock.class);
    when(clock.instant())
        .thenAnswer(
            invocation ->
                Instant.parse(f.scope.evaluatedAt()).plusSeconds(committed.get() ? 120 : 1));
    f.compose(clock);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (f.joined.get()) committed.set(true);
              return null;
            })
        .when(transactionManager)
        .commit(org.mockito.ArgumentMatchers.any());

    var result = f.browserJoin();

    assertTrue(committed.get());
    assertTrue(result.success());
    assertEquals("JOINED", result.outcomeCode());
    org.mockito.Mockito.verify(accountJoinOperationRepository, org.mockito.Mockito.never())
        .finishCanonicalFailure(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"AUTH_UNAVAILABLE", "ENTITLEMENT_UNAVAILABLE", "ADMISSION_POINTER_UNAVAILABLE"})
  void canonicalJoinPreservesTypedFailureWhenRecordingFails(String code) {
    var f = canonicalJoinFixture();
    var primary = new AuthenticationException(code, "controlled primary source failure");
    var recording =
        new org.springframework.dao.DataAccessResourceFailureException(
            "controlled recording failure");
    org.mockito.Mockito.doThrow(primary)
        .when(f.targets)
        .resolvePublishedRealmAdmissionTarget(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doThrow(recording)
        .when(accountJoinOperationRepository)
        .recordCanonicalAttemptFailure(f.requestId, f.scope, f.binding, "UNAVAILABLE", code);

    var failure = assertThrows(AuthenticationException.class, f::browserJoin);

    assertSame(primary, failure);
    assertEquals(code, failure.getCode());
    assertArrayEquals(new Throwable[] {recording}, failure.getSuppressed());
    verifyNoInteractions(f.terminal);
  }

  @Test
  void canonicalJoinPreservesRetryableMappingAndCauseWhenRecordingFails() {
    var f = canonicalJoinFixture();
    var primary = new IllegalStateException("controlled primary source failure");
    var recording = new AuthenticationException("ACCOUNT_LOCKED", "controlled recording refusal");
    org.mockito.Mockito.doThrow(primary)
        .when(f.sources)
        .readCurrentSourceSelection(f.scope.tenantId());
    org.mockito.Mockito.doThrow(recording)
        .when(accountJoinOperationRepository)
        .recordCanonicalAttemptFailure(
            f.requestId, f.scope, f.binding, "UNAVAILABLE", "ENTITLEMENT_UNAVAILABLE");

    var failure = assertThrows(AuthenticationException.class, f::browserJoin);

    assertEquals("ENTITLEMENT_UNAVAILABLE", failure.getCode());
    assertSame(primary, failure.getCause());
    assertArrayEquals(new Throwable[] {recording}, primary.getSuppressed());
    verifyNoInteractions(f.terminal);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void canonicalJoinRefusesAmbientTransactionsBeforeReadingTheCaller(boolean synchronization) {
    var f = canonicalJoinFixture();
    try {
      if (synchronization) TransactionSynchronizationManager.initSynchronization();
      else TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThrows(IllegalStateException.class, f::browserJoin);
      verifyNoInteractions(f.reader, f.targets, f.sources, f.demos, f.terminal);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "too-long"})
  void canonicalJoinRejectsInvalidRequestBeforeScopeOrIntentWork(String request) {
    var f = canonicalJoinFixture();
    String id = request.equals("too-long") ? "j".repeat(129) : request;
    assertEquals(
        request.equals("too-long") ? "INVALID_ARGUMENT" : "CONNECT_SCOPE_INVALID",
        assertThrows(
                AuthenticationException.class,
                () ->
                    service.joinPublicProduction(
                        f.bootstrap, new JoinPublicProductionRequest(f.scope.connectScopeId(), id)))
            .getCode());
    verifyNoInteractions(f.reader, accountJoinOperationRepository, f.terminal);
  }

  @Test
  void canonicalJoinAcceptsTheFull128CharacterRequestIdentity() {
    var f = canonicalJoinFixture("j".repeat(128));
    assertTrue(f.browserJoin().success());
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .insertCanonicalIntent(f.requestId, f.scope, f.binding);
  }

  @Test
  void canonicalJoinConflictingRequestDoesNotReplaceOriginalEvidence() {
    var f = canonicalJoinFixture();
    when(accountJoinOperationRepository.insertCanonicalIntent(f.requestId, f.scope, f.binding))
        .thenThrow(
            new AccountJoinOperationRepository.CanonicalJoinOperationConflictException(
                "controlled different caller or selector"));
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    verifyNoInteractions(f.sources, f.demos, f.terminal);
  }

  @ParameterizedTest
  @EnumSource(
      value = AccountLifecycleState.class,
      names = {"DEACTIVATED_PENDING_DELETE", "SECURITY_LOCKED", "DELETED"})
  void canonicalJoinCurrentAccountLifecycleBlocksAStillLiveBootstrap(AccountLifecycleState state) {
    var f = canonicalJoinFixture();
    f.account.setLifecycleState(state);
    assertEquals(
        state == AccountLifecycleState.SECURITY_LOCKED
            ? "AUTH_ACCOUNT_LOCKED"
            : "AUTH_INVALID_CREDENTIALS",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    verifyNoInteractions(f.terminal);
  }

  @Test
  void canonicalJoinRechecksTheBrowserRegistryAfterTargetReadsBeforeCommit() {
    var f = canonicalJoinFixture();
    when(sessionService.isAccountSessionActive(f.account.getId(), f.bootstrap))
        .thenReturn(true, false);
    assertEquals(
        "CONNECT_CONTEXT_INVALID",
        assertThrows(AuthenticationException.class, f::browserJoin).getCode());
    verifyNoInteractions(f.terminal);
  }

  @Test
  void canonicalDirectJoinUsesTheSameTerminalOwnerAndUuidTargetWithoutBootstrapSession()
      throws Exception {
    var f = canonicalJoinFixture();
    var caller = f.directCaller(f.requestId);
    f.binding = caller.sessionId();
    when(accountJoinOperationRepository.bindCanonicalPolicyEvidence(
            f.requestId, f.scope, caller.sessionId(), true, 1L))
        .thenReturn(f.pending);
    org.mockito.Mockito.clearInvocations(sessionService);
    var result =
        canonicalGameSessionPeer()
            .call(
                () ->
                    service.joinPublicProductionFromGameSession(
                        caller,
                        new JoinPublicProductionRequest(f.scope.connectScopeId(), f.requestId)));
    assertTrue(result.success());
    assertEquals(f.scope.tenantId(), result.tenantId());
    assertFalse(result.replayed());
    verifyNoInteractions(sessionService);
    org.mockito.Mockito.verify(accountJoinOperationRepository)
        .bindCanonicalPolicyEvidence(f.requestId, f.scope, caller.sessionId(), true, 1L);
    org.mockito.Mockito.verify(f.terminal)
        .commitCanonicalJoin(f.scope, f.requestId, f.binding, null, null, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "namespace", "service"})
  void canonicalDirectJoinRejectsWrongWorkloadBeforeReadingScope(String mismatch) throws Exception {
    var f = canonicalJoinFixture();
    var peer = io.grpc.Context.ROOT;
    if (!mismatch.equals("absent")) {
      String namespace = mismatch.equals("namespace") ? "other" : "test";
      String name =
          mismatch.equals("service") ? "world-management-service" : "game-session-service";
      peer =
          peer.withValue(
              net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY,
              new net.firedevops.firemud.common.grpc.GrpcPeerIdentity(
                  "spiffe://firemud/ns/" + namespace + "/sa/" + name, namespace, name));
    }
    assertEquals(
        "AUTH_UNAVAILABLE",
        peer.call(
                () ->
                    assertThrows(
                        AuthenticationException.class,
                        () ->
                            service.joinPublicProductionFromGameSession(
                                f.directCaller(f.requestId),
                                new JoinPublicProductionRequest(
                                    f.scope.connectScopeId(), f.requestId))))
            .getCode());
    verifyNoInteractions(f.reader, accountJoinOperationRepository, f.terminal);
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "realm", "namespace", "scope", "instance"})
  void canonicalDirectJoinRejectsAnyCallerTargetMismatchBeforeIntent(String field)
      throws Exception {
    var f = canonicalJoinFixture();
    var caller =
        new DirectTextCallerContext(
            f.scope.accountId(),
            field.equals("tenant") ? UUID.randomUUID() : f.scope.tenantId(),
            field.equals("realm") ? UUID.randomUUID() : f.scope.realmId(),
            field.equals("namespace") ? UUID.randomUUID() : f.scope.playableStateNamespaceId(),
            field.equals("scope") ? "ISOLATED" : f.scope.playableStateScope(),
            field.equals("instance") ? UUID.randomUUID() : f.scope.gameInstanceId(),
            "00042",
            f.requestId);
    assertEquals(
        "CONNECT_SCOPE_MISMATCH",
        canonicalGameSessionPeer()
            .call(
                () ->
                    assertThrows(
                        AuthenticationException.class,
                        () ->
                            service.joinPublicProductionFromGameSession(
                                caller,
                                new JoinPublicProductionRequest(
                                    f.scope.connectScopeId(), f.requestId))))
            .getCode());
    verifyNoInteractions(accountJoinOperationRepository, f.terminal);
  }

  private CanonicalJoinFixture canonicalJoinFixture() {
    return canonicalJoinFixture("canonical-request");
  }

  private CanonicalJoinFixture canonicalJoinFixture(String requestId) {
    return new CanonicalJoinFixture(requestId);
  }

  private static io.grpc.Context canonicalGameSessionPeer() {
    return io.grpc.Context.ROOT.withValue(
        net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY,
        new net.firedevops.firemud.common.grpc.GrpcPeerIdentity(
            "spiffe://firemud/ns/test/sa/game-session-service", "test", "game-session-service"));
  }

  private final class CanonicalJoinFixture {
    private final Account account = new Account();
    private final CanonicalJoinScopeV2 scope;
    private final String requestId;
    private final String bootstrap;
    private final AccountTenantMembership currentMembership = new AccountTenantMembership();
    private final AtomicBoolean joined = new AtomicBoolean();
    private String binding = UUID.randomUUID().toString();
    private final net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader
        reader =
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader
                    .class);
    private final CanonicalGameplayAdmissionDecisionReadClient targets =
        org.mockito.Mockito.mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    private final Target target = org.mockito.Mockito.mock(Target.class);
    private final net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
        roster =
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                    .class);
    private Target resolvedTarget = target;
    private byte[] policyBytes =
        "controlled-unit-policy-set".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final net.firedevops.firemud.accountservice.repository
            .AccountTenantAuthorityEventRepository
        sources =
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountTenantAuthorityEventRepository.class);
    private final net.firedevops.firemud.accountservice.repository
            .AccountTenantAuthorityEventRepository.SourceSelection
        source =
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountTenantAuthorityEventRepository.SourceSelection.class);
    private final net.firedevops.firemud.accountservice.repository
            .AccountDemoTenantEntitlementRepository
        demos =
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountDemoTenantEntitlementRepository.class);
    private final net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot demo =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot.class);
    private final net.firedevops.firemud.accountservice.repository
            .AccountHostingSubscriptionLifecycleRepository
        hosting =
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountHostingSubscriptionLifecycleRepository.class);
    private final net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver
        observer =
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver
                    .class);
    private final net.firedevops.firemud.accountservice.service
            .AccountCanonicalFirstJoinTerminalCoordinator
        terminal =
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.service
                    .AccountCanonicalFirstJoinTerminalCoordinator.class);
    private final AccountJoinOperationRepository.CanonicalJoinOperationEvidence pending =
        org.mockito.Mockito.mock(
            AccountJoinOperationRepository.CanonicalJoinOperationEvidence.class);
    private final AccountJoinOperationRepository.CanonicalJoinOperationEvidence committed =
        org.mockito.Mockito.mock(
            AccountJoinOperationRepository.CanonicalJoinOperationEvidence.class);

    private CanonicalJoinFixture(String requestId) {
      this.requestId = requestId;
      account.setId(11L);
      setPersistedAuthenticationIdentity(account);
      when(accountRepository.findByAccountUuidForUpdate(account.getAccountUuid()))
          .thenReturn(Optional.of(account));
      var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      scope =
          new CanonicalJoinScopeV2(
              "controlled-v2-token",
              account.getAccountUuid(),
              UUID.randomUUID(),
              UUID.fromString(REALM_ID),
              "tenant",
              "demo",
              "production",
              UUID.fromString(PLAYABLE_STATE_NAMESPACE_ID),
              "SHARED",
              UUID.randomUUID(),
              23L,
              17L,
              now.toString(),
              now.plusSeconds(120).toString());
      bootstrap =
          jwtUtil.generateToken(
              account.getAccountUuid().toString(),
              300000L,
              Map.of(
                  "aud",
                  "player-bootstrap",
                  "accountId",
                  account.getAccountUuid().toString(),
                  "jti",
                  binding));
      when(sessionService.isAccountSessionActive(account.getId(), bootstrap)).thenReturn(true);
      when(reader.read(scope.accountId(), scope.connectScopeId())).thenReturn(scope);
      when(target.targetNamespace()).thenReturn("test");
      when(target.canonicalTenantId()).thenReturn(scope.tenantId());
      when(target.tenantSlug()).thenReturn(scope.tenantSlug());
      when(target.worldSlug()).thenReturn(scope.worldSlug());
      when(target.realmId()).thenReturn(scope.realmId());
      when(target.realmSlug()).thenReturn(scope.realmSlug());
      when(target.playableStateNamespaceId()).thenReturn(scope.playableStateNamespaceId());
      when(target.playableStateScope()).thenReturn(scope.playableStateScope());
      when(target.canonicalGameInstanceId()).thenReturn(scope.gameInstanceId());
      when(target.canonicalVersionId()).thenReturn(UUID.randomUUID());
      when(target.catalogRevision()).thenReturn(scope.catalogRevision());
      when(target.pointerVersion()).thenReturn(scope.pointerVersion());
      when(target.activeWorldEpoch()).thenReturn(1L);
      when(target.characterCreationPolicy()).thenReturn("PRESEEDED_ONLY");
      when(target.ownerProofOutcome())
          .thenReturn(
              net.firedevops.firemud.common.gamesession
                  .CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome.COMMITTED);
      when(targets.resolvePublishedRealmAdmissionTarget(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(invocation -> resolve(invocation.getArgument(0), target));
      when(targets.readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              invocation -> {
                var strict =
                    invocation.getArgument(
                        0,
                        net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                            .Request.class);
                Target currentTarget = resolvedTarget;
                long activeWorldEpoch = currentTarget.activeWorldEpoch();
                var published =
                    org.mockito.Mockito.mock(
                        net.firedevops.firemud.common.gamesession
                            .CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence
                            .class);
                when(published.selector()).thenReturn(strict);
                when(published.currentTarget()).thenReturn(currentTarget);
                var sourceEvidence =
                    org.mockito.Mockito.mock(
                        net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                            .class);
                when(sourceEvidence.request()).thenReturn(strict);
                var policies =
                    org.mockito.Mockito.mock(
                        net.firedevops.firemud.common.publication
                            .PublishedRealmEntryPolicySetEvidence.class);
                when(policies.canonicalBytes()).thenReturn(policyBytes);
                when(sourceEvidence.publishedPolicySetEvidence()).thenReturn(policies);
                when(published.sourceEvidence()).thenReturn(sourceEvidence);
                var world =
                    org.mockito.Mockito.mock(
                        net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence
                            .class);
                when(world.lifecycleStatus()).thenReturn("ACTIVE");
                when(world.lifecycleEpoch()).thenReturn(activeWorldEpoch);
                when(published.worldLifecycleEvidence()).thenReturn(world);
                return published;
              });
      when(pending.status()).thenReturn("PENDING");
      when(accountJoinOperationRepository.findCanonicalEvidenceForUpdateByRequestId(requestId))
          .thenReturn(Optional.of(pending));
      when(accountJoinOperationRepository.bindCanonicalPolicyEvidence(
              org.mockito.ArgumentMatchers.eq(requestId),
              org.mockito.ArgumentMatchers.eq(scope),
              org.mockito.ArgumentMatchers.eq(binding),
              org.mockito.ArgumentMatchers.anyBoolean(),
              org.mockito.ArgumentMatchers.anyLong()))
          .thenReturn(pending);
      when(sources.readCurrentSourceSelection(scope.tenantId())).thenReturn(source);
      when(source.kind())
          .thenReturn(
              net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository
                  .SourceKind.NON_PAID_DEMO);
      when(source.matches(source.checkpoint())).thenReturn(true);
      when(demos.readCurrent(scope.tenantId())).thenReturn(demo);
      when(demos.revalidate(demo)).thenReturn(demo);
      when(demo.gameplayAvailable()).thenReturn(true);
      when(demo.allowPublicJoin()).thenReturn(true);
      when(demo.entitlementVersion()).thenReturn(1L);
      terminal("JOINED", 2L, 1L);
      when(accountTenantMembershipRepository.findFreshMembershipForUpdate(
              scope.accountId(), scope.tenantId()))
          .thenAnswer(
              invocation -> joined.get() ? Optional.of(currentMembership) : Optional.empty());
      when(terminal.commitCanonicalJoin(
              org.mockito.ArgumentMatchers.eq(scope),
              org.mockito.ArgumentMatchers.eq(requestId),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.isNull(),
              org.mockito.ArgumentMatchers.isNull(),
              org.mockito.ArgumentMatchers.isNull()))
          .thenAnswer(
              invocation -> {
                joined.set(true);
                return committed;
              });
      service.setAccountBootstrapRosterReadClient(roster);
      compose(Clock.fixed(now.plusSeconds(1), java.time.ZoneOffset.UTC));
    }

    private net.firedevops.firemud.common.gamesession
            .CanonicalGameplayAdmissionDecisionReadGrpcCodec.ResolvedPublishedTarget
        resolve(
            net.firedevops.firemud.common.gamesession
                    .CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector
                selector,
            Target current) {
      resolvedTarget = current;
      var resolved =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.common.gamesession
                  .CanonicalGameplayAdmissionDecisionReadGrpcCodec.ResolvedPublishedTarget.class);
      var strict =
          new net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request(
              selector.readRequestId(),
              selector.accountId(),
              selector.targetNamespace(),
              selector.tenantId(),
              selector.worldSlug(),
              selector.realmId(),
              selector.realmSlug(),
              selector.playableStateNamespaceId(),
              selector.playableStateScope(),
              selector.gameInstanceId(),
              current.canonicalVersionId(),
              selector.catalogRevision(),
              selector.pointerVersion(),
              current.activeWorldEpoch());
      when(resolved.selector()).thenReturn(selector);
      when(resolved.currentTarget()).thenReturn(current);
      when(resolved.strictSelector()).thenReturn(strict);
      return resolved;
    }

    private void terminal(String outcome, long version, long generation) {
      currentMembership.setId(701L);
      currentMembership.setAccount(account);
      currentMembership.setTenantUuid(scope.tenantId());
      currentMembership.setTenantProvenanceKind("FRESH_GAME_DESIGN");
      currentMembership.setAuthorityProvenance("EXPLICIT_JOIN");
      currentMembership.setLifecycleState("ACTIVE");
      currentMembership.setGameplayAdmissionAllowed(true);
      currentMembership.setMembershipVersion(version);
      currentMembership.setMembershipAuthorityGeneration(generation);
      when(committed.outcome()).thenReturn(outcome);
      when(committed.terminalProof())
          .thenReturn(
              new AccountJoinOperationRepository.CanonicalJoinTerminalProof(
                  "membership-stream",
                  1L,
                  UUID.randomUUID().toString(),
                  "sha256:" + "a".repeat(64),
                  outcome.equals("ALREADY_ACTIVE") ? null : UUID.randomUUID(),
                  outcome.equals("ALREADY_ACTIVE") ? null : "sha256:" + "b".repeat(64),
                  outcome.equals("ALREADY_ACTIVE") ? null : Instant.now(),
                  1L,
                  701L,
                  version,
                  generation));
    }

    private void compose(Clock clock) {
      service.setCanonicalScopeConsumers(
          reader, targets, sources, demos, hosting, observer, terminal, "test", clock);
    }

    private JoinPublicProductionResult browserJoin() {
      return service.joinPublicProduction(
          bootstrap, new JoinPublicProductionRequest(scope.connectScopeId(), requestId));
    }

    private List<net.firedevops.firemud.accountservice.dto.BootstrapCharacterDto> characters() {
      return service.listBootstrapCharacters(
          bootstrap, scope.worldSlug(), scope.realmSlug(), scope.connectScopeId());
    }

    private DirectTextCallerContext directCaller(String id) {
      return new DirectTextCallerContext(
          scope.accountId(),
          scope.tenantId(),
          scope.realmId(),
          scope.playableStateNamespaceId(),
          scope.playableStateScope(),
          scope.gameInstanceId(),
          "00042",
          id);
    }
  }

  @Test
  void
      canonicalBootstrapCharactersReturnsOnlyTheSelectedOwnersActorsSortedWithOwnerReportedLevels() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    UUID ada = UUID.randomUUID(), zed = UUID.randomUUID();
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(f.scope.accountId()),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                    .Actor(zed, "Zed", 37),
                new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                    .Actor(ada, "Ada", 12)));
    var actors = f.characters();
    assertEquals(
        List.of("Ada", "Zed"), actors.stream().map(value -> value.characterName()).toList());
    assertEquals(
        List.of(ada.toString(), zed.toString()),
        actors.stream().map(value -> value.characterId()).toList());
    assertEquals(List.of(12, 37), actors.stream().map(value -> value.level()).toList());
    assertTrue(
        actors.stream()
            .allMatch(
                value ->
                    "SHARED".equals(value.stateScope())
                        && "PRESEEDED_ONLY".equals(value.characterCreationPolicy())));
    var target =
        org.mockito.ArgumentCaptor.forClass(
            net.firedevops.firemud.common.gamesession
                .CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence.class);
    org.mockito.Mockito.verify(f.roster)
        .read(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq(f.scope.accountId()),
            target.capture());
    assertEquals(f.scope.accountId(), target.getValue().selector().canonicalAccountUuid());
    assertEquals(f.scope.tenantId(), target.getValue().selector().canonicalTenantUuid());
    assertEquals(f.scope.realmId(), target.getValue().selector().realmUuid());
    assertEquals(
        f.scope.playableStateNamespaceId(),
        target.getValue().selector().playableStateNamespaceUuid());
    assertEquals(
        f.scope.gameInstanceId(), target.getValue().selector().canonicalGameInstanceUuid());
    assertEquals(f.scope.catalogRevision(), target.getValue().selector().expectedCatalogRevision());
    assertEquals(f.scope.pointerVersion(), target.getValue().selector().expectedPointerVersion());
    org.mockito.Mockito.verify(f.targets, org.mockito.Mockito.atLeast(2))
        .readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @Test
  void canonicalBootstrapCharactersReturnsEmptyOnlyAfterSuccessfulSupportedOwnerRead() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(f.scope.accountId()),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
    assertTrue(f.characters().isEmpty());
    org.mockito.Mockito.verify(f.roster)
        .read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(f.scope.accountId()),
            org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @ParameterizedTest
  @CsvSource({
    "MISSING,true,JOIN_REQUIRED",
    "INACTIVE,true,JOIN_REQUIRED",
    "MISSING,false,PUBLIC_PRODUCTION_ADMISSION_DENIED",
    "INACTIVE,false,PUBLIC_PRODUCTION_ADMISSION_DENIED"
  })
  void canonicalBootstrapCharactersClassifiesPublicMembershipOnlyAfterFreshPolicy(
      String lifecycle, boolean open, String code) {
    var f = canonicalJoinFixture();
    f.joined.set(!lifecycle.equals("MISSING"));
    f.currentMembership.setLifecycleState(lifecycle);
    f.currentMembership.setGameplayAdmissionAllowed(false);
    when(f.demo.allowPublicJoin()).thenReturn(open);
    assertEquals(code, assertThrows(AuthenticationException.class, f::characters).getCode());
    org.mockito.Mockito.verify(f.demos, org.mockito.Mockito.atLeastOnce()).revalidate(f.demo);
    verifyNoInteractions(f.roster, accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @Test
  void canonicalBootstrapCharactersRejectsActiveControlOnlyMembershipWithoutRepair() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    f.currentMembership.setGameplayAdmissionAllowed(false);
    assertEquals(
        "PUBLIC_PRODUCTION_ADMISSION_DENIED",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(f.roster, accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @Test
  void canonicalBootstrapCharactersCannotInterpretBillingOutageAsMissingMembershipOrEmptyRoster() {
    var f = canonicalJoinFixture();
    when(f.demos.readCurrent(f.scope.tenantId()))
        .thenThrow(
            new DataAccessResourceFailureException("controlled canonical entitlement outage"));
    assertEquals(
        "ENTITLEMENT_UNAVAILABLE",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(f.roster, accountJoinOperationRepository);
  }

  @Test
  void canonicalBootstrapCharactersRejectsFreshBillingDenialBeforeDisclosure() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    when(f.demo.gameplayAvailable()).thenReturn(false);
    assertEquals(
        "TENANT_BILLING_BLOCKED",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(f.roster);
  }

  @ParameterizedTest
  @ValueSource(strings = {"world", "realm"})
  void canonicalBootstrapCharactersRequiresTheExactScopeRoute(String changed) {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    assertEquals(
        "CONNECT_SCOPE_MISMATCH",
        assertThrows(
                AuthenticationException.class,
                () ->
                    service.listBootstrapCharacters(
                        f.bootstrap,
                        changed.equals("world") ? "another-world" : f.scope.worldSlug(),
                        changed.equals("realm") ? "another-realm" : f.scope.realmSlug(),
                        f.scope.connectScopeId()))
            .getCode());
    verifyNoInteractions(f.roster);
  }

  @ParameterizedTest
  @ValueSource(strings = {"control-ui", "bootstrap-connect-scope"})
  void canonicalBootstrapCharactersRequiresTheBrowserBootstrapCredentialProfile(String audience) {
    var f = canonicalJoinFixture();
    String token =
        jwtUtil.generateToken(
            f.scope.accountId().toString(),
            Map.of(
                "aud",
                audience,
                "accountId",
                f.scope.accountId().toString(),
                "jti",
                UUID.randomUUID().toString()));
    assertEquals(
        "CONNECT_CONTEXT_INVALID",
        assertThrows(
                AuthenticationException.class,
                () ->
                    service.listBootstrapCharacters(
                        token, f.scope.worldSlug(), f.scope.realmSlug(), f.scope.connectScopeId()))
            .getCode());
    verifyNoInteractions(f.reader, f.targets, f.roster);
  }

  @Test
  void canonicalBootstrapCharactersRejectsInactiveBrowserSessionBeforeTargetOrRosterWork() {
    var f = canonicalJoinFixture();
    when(sessionService.isAccountSessionActive(f.account.getId(), f.bootstrap)).thenReturn(false);
    assertEquals(
        "CONNECT_CONTEXT_INVALID",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(f.reader, f.targets, f.roster);
  }

  @Test
  void canonicalBootstrapCharactersPreservesReaderIdentityRefusalBeforeRosterWork() {
    var f = canonicalJoinFixture();
    when(f.reader.read(f.scope.accountId(), f.scope.connectScopeId()))
        .thenThrow(
            new AuthenticationException(
                "CONNECT_SCOPE_INVALID", "controlled Account/scope mismatch"));
    assertEquals(
        "CONNECT_SCOPE_INVALID",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(f.targets, f.roster);
  }

  @Test
  void canonicalBootstrapCharactersRejectsTargetDriftDuringEntityReadInsteadOfReturningActors() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    var changed =
        org.mockito.Mockito.mock(Target.class, org.mockito.AdditionalAnswers.delegatesTo(f.target));
    when(changed.activeWorldEpoch()).thenReturn(2L);
    AtomicBoolean drift = new AtomicBoolean();
    org.mockito.Mockito.doAnswer(
            invocation -> f.resolve(invocation.getArgument(0), drift.get() ? changed : f.target))
        .when(f.targets)
        .resolvePublishedRealmAdmissionTarget(org.mockito.ArgumentMatchers.any());
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              drift.set(true);
              return List.of(
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(UUID.randomUUID(), "Ada", 12));
            });
    assertEquals(
        "CONNECT_SCOPE_MISMATCH",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @Test
  void canonicalBootstrapCharactersRechecksMembershipAfterEntityRead() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              f.currentMembership.setLifecycleState("INACTIVE");
              f.currentMembership.setGameplayAdmissionAllowed(false);
              return List.of(
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(UUID.randomUUID(), "Ada", 12));
            });
    var failure = assertThrows(AuthenticationException.class, f::characters);
    assertEquals("AUTH_UNAVAILABLE", failure.getCode());
    assertEquals("Bootstrap membership changed during discovery", failure.getMessage());
    org.mockito.Mockito.verify(f.roster)
        .read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "generation", "membership-id"})
  void canonicalBootstrapCharactersRejectsChangedMembershipSnapshotAfterEntityRead(String changed) {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              switch (changed) {
                case "version" ->
                    f.currentMembership.setMembershipVersion(
                        f.currentMembership.getMembershipVersion() + 1L);
                case "generation" ->
                    f.currentMembership.setMembershipAuthorityGeneration(
                        f.currentMembership.getMembershipAuthorityGeneration() + 1L);
                case "membership-id" -> f.currentMembership.setId(702L);
                default -> throw new AssertionError(changed);
              }
              return List.of(
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(UUID.randomUUID(), "Withheld", 12));
            });
    assertEquals(
        "AUTH_UNAVAILABLE", assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @Test
  void canonicalBootstrapCharactersRejectsChangedPublishedPolicyCaptureAfterEntityRead() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              f.policyBytes =
                  "changed-controlled-policy-set".getBytes(java.nio.charset.StandardCharsets.UTF_8);
              return List.of(
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(UUID.randomUUID(), "Withheld", 12));
            });
    assertEquals(
        "CONNECT_SCOPE_MISMATCH",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @Test
  void canonicalBootstrapCharactersRechecksCurrentEntitlementAfterEntityRead() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              when(f.demos.readCurrent(f.scope.tenantId()))
                  .thenThrow(
                      new DataAccessResourceFailureException("controlled late billing outage"));
              when(f.demos.revalidate(f.demo))
                  .thenThrow(
                      new DataAccessResourceFailureException("controlled late billing outage"));
              return List.of(
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(UUID.randomUUID(), "Ada", 12));
            });
    assertEquals(
        "ENTITLEMENT_UNAVAILABLE",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @Test
  void canonicalBootstrapCharactersCannotTranslateEntityOutageIntoSuccessfulEmptyDiscovery() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("controlled Entity discovery unavailable"));
    assertEquals(
        "AUTH_UNAVAILABLE", assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @Test
  void canonicalBootstrapCharactersRefusesScopeThatExpiresDuringEntityRead() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    var time =
        new java.util.concurrent.atomic.AtomicReference<>(
            Instant.parse(f.scope.evaluatedAt()).plusSeconds(1));
    f.compose(
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return time.get();
          }
        });
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              time.set(Instant.parse(f.scope.connectScopeExpiresAt()));
              return List.of(
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(UUID.randomUUID(), "Ada", 12));
            });
    assertEquals(
        "CONNECT_SCOPE_INVALID",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "realm", "namespace", "instance", "catalog", "pointer"})
  void canonicalBootstrapCharactersCannotDiscloseForAChangedRetainedTargetField(String field) {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    switch (field) {
      case "tenant" -> when(f.target.canonicalTenantId()).thenReturn(UUID.randomUUID());
      case "realm" -> when(f.target.realmId()).thenReturn(UUID.randomUUID());
      case "namespace" -> when(f.target.playableStateNamespaceId()).thenReturn(UUID.randomUUID());
      case "instance" -> when(f.target.canonicalGameInstanceId()).thenReturn(UUID.randomUUID());
      case "catalog" -> when(f.target.catalogRevision()).thenReturn(f.scope.catalogRevision() + 1L);
      case "pointer" -> when(f.target.pointerVersion()).thenReturn(f.scope.pointerVersion() + 1L);
      default -> throw new AssertionError(field);
    }
    assertEquals(
        "CONNECT_SCOPE_MISMATCH",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(f.roster);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void canonicalBootstrapCharactersRefusesAmbientSqlBeforeCallerOrRemoteRead(
      boolean synchronization) {
    var f = canonicalJoinFixture();
    try {
      if (synchronization) TransactionSynchronizationManager.initSynchronization();
      else TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThrows(IllegalStateException.class, f::characters);
      verifyNoInteractions(f.reader, f.targets, f.roster);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void canonicalBootstrapCharactersRechecksTheBrowserRegistryAfterEntityRead() {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              when(sessionService.isAccountSessionActive(f.account.getId(), f.bootstrap))
                  .thenReturn(false);
              return List.of(
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(UUID.randomUUID(), "Withheld", 12));
            });
    assertEquals(
        "CONNECT_CONTEXT_INVALID",
        assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @ParameterizedTest
  @CsvSource({"FAILED_PRECONDITION,CONNECT_SCOPE_MISMATCH", "UNAVAILABLE,AUTH_UNAVAILABLE"})
  void canonicalBootstrapCharactersPreservesEntityCurrentnessAndUnavailableFailureBoundaries(
      String status, String code) {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    when(f.roster.read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenThrow(
            io.grpc.Status.fromCode(io.grpc.Status.Code.valueOf(status)).asRuntimeException());
    assertEquals(code, assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  /**
   * ISOLATED positive discovery remains unfinished; a public V2 carrier cannot select its policy.
   */
  @Test
  void canonicalBootstrapCharactersExplicitlyRefusesUnsupportedIsolatedScope() {
    var f = canonicalJoinFixture();
    var isolated =
        new CanonicalJoinScopeV2(
            f.scope.connectScopeId(),
            f.scope.accountId(),
            f.scope.tenantId(),
            f.scope.realmId(),
            f.scope.tenantSlug(),
            f.scope.worldSlug(),
            f.scope.realmSlug(),
            f.scope.playableStateNamespaceId(),
            "ISOLATED",
            f.scope.gameInstanceId(),
            f.scope.catalogRevision(),
            f.scope.pointerVersion(),
            f.scope.evaluatedAt(),
            f.scope.connectScopeExpiresAt());
    when(f.reader.read(f.scope.accountId(), f.scope.connectScopeId())).thenReturn(isolated);
    assertEquals(
        "REALM_UNAVAILABLE", assertThrows(AuthenticationException.class, f::characters).getCode());
    verifyNoInteractions(f.targets, f.roster);
  }

  /**
   * Controlled late-owner refusal only; retained resolution still rejects unsupported policies.
   * PLAYER_CREATED/AUTO/private/ISOLATED positive branches remain unfinished.
   */
  @ParameterizedTest
  @ValueSource(strings = {"PLAYER_CREATED", "AUTO_PROVISIONED"})
  void canonicalBootstrapCharactersRejectsUnsupportedPolicyFromLateControlledOwnerRead(
      String policy) {
    var f = canonicalJoinFixture();
    f.joined.set(true);
    // Retained resolution admits PRESEEDED_ONLY only. Change this controlled owner response
    // after that check to exercise the facade's own unsupported-policy refusal, not the codec.
    when(f.targets.readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              when(f.target.characterCreationPolicy()).thenReturn(policy);
              var published =
                  org.mockito.Mockito.mock(
                      net.firedevops.firemud.common.gamesession
                          .CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence
                          .class);
              when(published.selector()).thenReturn(invocation.getArgument(0));
              when(published.currentTarget()).thenReturn(f.target);
              return published;
            });
    var failure = assertThrows(AuthenticationException.class, f::characters);
    assertEquals("REALM_UNAVAILABLE", failure.getCode());
    assertEquals(
        "Bootstrap actor discovery does not yet support this realm policy", failure.getMessage());
    org.mockito.Mockito.verify(f.targets)
        .readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(f.sources, f.demos, f.roster);
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

  @ParameterizedTest
  @EnumSource(AccountIdentityProvenance.class)
  void gameplayAuthenticationAcceptsEveryRecognizedPersistedUuidProvenance(
      AccountIdentityProvenance provenance) {
    Account account = new Account();
    account.setId(31L);
    account.setEmail("retained@example.com");
    account.setPasswordHash(hash("password"));
    setPersistedAuthenticationIdentity(account, provenance);
    when(accountRepository.findByEmail("retained@example.com")).thenReturn(Optional.of(account));

    AuthenticationResult result =
        service.authenticateForGameplay("retained@example.com", "password");
    var claims = new JwtUtil(JWT_SECRET, 3600000L).parseToken(result.authToken()).getPayload();

    assertEquals(account.getAccountUuid().toString(), result.accountId());
    assertEquals(account.getAccountUuid().toString(), claims.getSubject());
    assertEquals(account.getAccountUuid().toString(), claims.get("accountId", String.class));
    assertFalse(claims.containsKey("tenantId"));
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(31L, result.authToken(), jwtAuthProperties.getJwtExpirationMs());
  }

  @ParameterizedTest
  @EnumSource(InvalidAuthenticationIdentity.class)
  void gameplayAuthenticationRejectsUnprovedIdentityBeforeConsumingOtpOrMinting(
      InvalidAuthenticationIdentity invalidIdentity) {
    Account account = new Account();
    account.setId(32L);
    account.setEmail("unproved@example.com");
    assignInvalidAuthenticationIdentity(account, invalidIdentity);
    var challenge = new net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge();
    challenge.setId(8L);
    challenge.setAccountId(32L);
    challenge.setCodeHash(hash("123456"));
    challenge.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
    when(accountRepository.findByEmail("unproved@example.com")).thenReturn(Optional.of(account));
    when(accountEmailLoginChallengeRepository.findByAccountId(32L))
        .thenReturn(Optional.of(challenge));

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () -> service.authenticateForGameplay("unproved@example.com", "123456"));
    assertEquals("AUTH_UNAVAILABLE", unavailable.getCode());

    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).lockAccountChallenge(32L);
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).findByAccountId(32L);
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository, org.mockito.Mockito.never())
        .delete(challenge);
    verifyNoInteractions(sessionService);
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
    setPersistedAuthenticationIdentity(account);
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

  @Test
  void issuePlayerBootstrapReturnsShortLivedToken() {
    composeOrdinaryBootstrapWorldDiscovery();
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
    assertEquals(account.getAccountUuid().toString(), claims.get("accountId", String.class));
    assertFalse(claims.containsKey("tenantId"));
    assertEquals(300000L, claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    when(sessionService.isAccountSessionActive(7L, result.bootstrapToken())).thenReturn(true);
    var worlds = service.listBootstrapWorlds(result.bootstrapToken());
    assertEquals("demo", worlds.getFirst().worldSlug());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.times(3))
        .findByAccountUuid(account.getAccountUuid());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.times(2))
        .isAccountSessionActive(7L, result.bootstrapToken());
    verifyNoInteractions(accountEmailLoginChallengeRepository);
  }

  @ParameterizedTest
  @EnumSource(InvalidAuthenticationIdentity.class)
  void issuePlayerBootstrapRejectsUnprovedIdentityBeforeConsumingOtpOrMinting(
      InvalidAuthenticationIdentity invalidIdentity) {
    Account account = new Account();
    account.setId(50L);
    account.setUsername("demo");
    account.setLoginAuthModes("EMAIL_OTP");
    assignInvalidAuthenticationIdentity(account, invalidIdentity);
    net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge challenge =
        new net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge();
    challenge.setAccountId(50L);
    challenge.setCodeHash(hash("123456"));
    challenge.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    when(accountEmailLoginChallengeRepository.findByAccountId(50L))
        .thenReturn(Optional.of(challenge));

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class, () -> service.issuePlayerBootstrap("demo", "123456"));
    assertEquals("AUTH_UNAVAILABLE", unavailable.getCode());

    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).lockAccountChallenge(50L);
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository).findByAccountId(50L);
    org.mockito.Mockito.verify(accountEmailLoginChallengeRepository, org.mockito.Mockito.never())
        .delete(challenge);
    verifyNoInteractions(sessionService);
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
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(accountEmailLoginChallengeRepository);
    inOrder.verify(accountEmailLoginChallengeRepository).lockAccountChallenge(7L);
    inOrder.verify(accountEmailLoginChallengeRepository).findByAccountId(7L);
    inOrder.verify(accountEmailLoginChallengeRepository).delete(challenge);
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(7L, result.bootstrapToken(), 300000L);
  }

  private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
      invalidBootstrapAccountClaims() {
    String valid = "10000000-0000-4000-8000-00000000000a";
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("11", "11"),
        org.junit.jupiter.params.provider.Arguments.of((String) null, valid),
        org.junit.jupiter.params.provider.Arguments.of(
            valid.toUpperCase(java.util.Locale.ROOT), valid),
        org.junit.jupiter.params.provider.Arguments.of(" " + valid, valid),
        org.junit.jupiter.params.provider.Arguments.of(valid, (Object) null),
        org.junit.jupiter.params.provider.Arguments.of(valid, ""),
        org.junit.jupiter.params.provider.Arguments.of(valid, 11L),
        org.junit.jupiter.params.provider.Arguments.of(valid, "11"),
        org.junit.jupiter.params.provider.Arguments.of(
            valid, "00000000-0000-0000-0000-000000000000"),
        org.junit.jupiter.params.provider.Arguments.of(
            "00000000-0000-0000-0000-000000000000", valid),
        org.junit.jupiter.params.provider.Arguments.of(valid, "not-a-uuid"),
        org.junit.jupiter.params.provider.Arguments.of(
            valid, valid.toUpperCase(java.util.Locale.ROOT)),
        org.junit.jupiter.params.provider.Arguments.of(valid, " " + valid),
        org.junit.jupiter.params.provider.Arguments.of(
            valid, "10000000-0000-4000-8000-000000000002"));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("invalidBootstrapAccountClaims")
  void bootstrapReaderRejectsNonUuidOrContradictoryIdentityBeforeOwnerOrSessionUse(
      String subject, Object accountClaim) {
    composeOrdinaryBootstrapWorldDiscovery();
    Map<String, Object> claims = new java.util.HashMap<>();
    claims.put("aud", "player-bootstrap");
    if (accountClaim != null) {
      claims.put("accountId", accountClaim);
    }
    String token = new JwtUtil(JWT_SECRET, 300000L).generateToken(subject, claims);

    AuthenticationException denied =
        assertThrows(AuthenticationException.class, () -> service.listBootstrapWorlds(token));

    assertEquals("CONNECT_CONTEXT_INVALID", denied.getCode());
    verifyNoInteractions(accountRepository, sessionService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "wrong-owner-uuid", "unavailable"})
  void bootstrapReaderReportsOwnerReadbackFailureBeforeSessionUse(String failure) {
    composeOrdinaryBootstrapWorldDiscovery();
    UUID uuid = UUID.fromString("10000000-0000-4000-8000-000000000001");
    if (failure.equals("unavailable")) {
      when(accountRepository.findByAccountUuid(uuid))
          .thenThrow(
              new org.springframework.dao.DataAccessResourceFailureException("private detail"));
    } else if (failure.equals("wrong-owner-uuid")) {
      Account owner = new Account();
      owner.setId(11L);
      owner.setAccountUuid(UUID.randomUUID());
      owner.setAccountUuidSourceNumericId(11L);
      owner.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
      when(accountRepository.findByAccountUuid(uuid)).thenReturn(Optional.of(owner));
    }
    String token =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(
                uuid.toString(), Map.of("aud", "player-bootstrap", "accountId", uuid.toString()));

    AuthenticationException denied =
        assertThrows(AuthenticationException.class, () -> service.listBootstrapWorlds(token));

    assertEquals("AUTH_UNAVAILABLE", denied.getCode());
    assertEquals("Account authority is unavailable", denied.getMessage());
    verifyNoInteractions(sessionService);
  }

  @Test
  void bootstrapReaderResolvesUuidBeforeReadingPrivateAccountSession() {
    composeOrdinaryBootstrapWorldDiscovery();
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    String uuid = account.getAccountUuid().toString();
    String token =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(uuid, Map.of("aud", "player-bootstrap", "accountId", uuid));
    when(sessionService.isAccountSessionActive(11L, token)).thenReturn(true);

    assertFalse(service.listBootstrapWorlds(token).isEmpty());

    org.mockito.InOrder reads = org.mockito.Mockito.inOrder(accountRepository, sessionService);
    reads.verify(accountRepository).findByAccountUuid(account.getAccountUuid());
    reads.verify(sessionService).isAccountSessionActive(11L, token);
  }

  @Test
  void listBootstrapWorldsRejectsInactiveAccountBootstrapToken() {
    composeOrdinaryBootstrapWorldDiscovery();
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    String accountUuid = account.getAccountUuid().toString();
    String bootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(
                accountUuid, Map.of("aud", "player-bootstrap", "accountId", accountUuid));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class, () -> service.listBootstrapWorlds(bootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
    assertEquals("Bootstrap token expired", ex.getMessage());
    org.mockito.Mockito.verify(sessionService).isAccountSessionActive(11L, bootstrapToken);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-a-uuid",
        "abc",
        "0",
        "00000000-0000-0000-0000-000000000000",
        "4C4B57D8-E3A2-48FE-9977-E7DF0FDCE901",
        " 4c4b57d8-e3a2-48fe-9977-e7df0fdce901",
        "4c4b57d8-e3a2-48fe-9977-e7df0fdce901 "
      })
  void listBootstrapWorldsRejectsMalformedOrNoncanonicalBootstrapAccountUuid(String accountUuid) {
    composeOrdinaryBootstrapWorldDiscovery();
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(
                accountUuid, Map.of("aud", "player-bootstrap", "accountId", accountUuid));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
    verifyNoInteractions(accountRepository, sessionService);
  }

  @Test
  void listBootstrapWorldsRejectsBootstrapTokenAccountSubjectMismatch() {
    composeOrdinaryBootstrapWorldDiscovery();
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken(
                REALM_ID,
                Map.of("aud", "player-bootstrap", "accountId", PLAYABLE_STATE_NAMESPACE_ID));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
    verifyNoInteractions(accountRepository, sessionService);
  }

  @Test
  void listBootstrapWorldsRejectsLegacyNumericBootstrapTokenClaims() {
    composeOrdinaryBootstrapWorldDiscovery();
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L)
            .generateToken("11", Map.of("aud", "player-bootstrap", "accountId", "11"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
  }

  @Test
  void listBootstrapWorldsRejectsBootstrapTokenWithoutAudience() {
    composeOrdinaryBootstrapWorldDiscovery();
    String malformedBootstrapToken =
        new JwtUtil(JWT_SECRET, 300000L).generateToken("11", Map.of("accountId", "11"));

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(malformedBootstrapToken));

    assertEquals("CONNECT_CONTEXT_INVALID", ex.getCode());
  }

  @Test
  void listBootstrapWorldsMapsUnexpectedJwtParserFailureToUnavailableWithCause() {
    JwtUtil jwtUtil = org.mockito.Mockito.mock(JwtUtil.class);
    IllegalStateException parserFailure = new IllegalStateException("boom");
    when(jwtUtil.parseToken("boom-token")).thenThrow(parserFailure);
    this.jwtUtil = jwtUtil;
    service =
        new AccountServiceImpl(
            accountRepository,
            accountAuthorityGenerationRepository,
            accountAuthoritySourceEvidenceRepository,
            accountAuthorityOutboxRepository,
            accountPasswordResetOperationRepository,
            accountLogoutAllOperationRepository,
            accountSecurityStateOperationRepository,
            accountPasswordResetDraftSourceChangeRepository,
            accountAuditOutboxRepository,
            accountJoinOperationRepository,
            accountEmailLoginChallengeRepository,
            accountRealmAccessGrantRepository,
            accountTenantMembershipRepository,
            Mappers.getMapper(AccountMapper.class),
            profileRepository,
            profileMapper,
            paymentTransactionRepository,
            retainedSubscriptionEvidenceRepository,
            externalAccountRepository,
            passwordResetTokenRepository,
            emailVerificationTokenRepository,
            notificationService,
            emailService,
            mailProperties,
            tokenProperties,
            jwtAuthProperties,
            jwtUtil,
            sessionService,
            transactionManager);

    composeOrdinaryBootstrapWorldDiscovery();

    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class, () -> service.listBootstrapWorlds("boom-token"));

    assertEquals("AUTH_UNAVAILABLE", ex.getCode());
    assertSame(parserFailure, ex.getCause());
    verifyNoInteractions(
        accountRepository,
        sessionService,
        accountTenantMembershipRepository,
        accountAuditOutboxRepository);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CONNECT_SCOPE_INVALID",
        "CONNECT_SCOPE_MISMATCH",
        "ADMISSION_POINTER_UNAVAILABLE",
        "ENTITLEMENT_UNAVAILABLE",
        "AUTH_UNAVAILABLE"
      })
  void bootstrapRealmFacadePreservesCanonicalTargetAndPolicyRefusal(String code) {
    var owner = org.mockito.Mockito.mock(AccountCanonicalPublicRealmScopeOwner.class);
    service.setCanonicalPublicRealmScopeOwner(owner);
    var refusal = new AuthenticationException(code, "controlled current canonical source refusal");
    when(owner.listRealmScopes("bootstrap", "source-tenant", "demo")).thenThrow(refusal);
    assertSame(
        refusal,
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapRealms("bootstrap", "source-tenant", "demo")));
    verifyNoInteractions(accountTenantMembershipRepository, accountRealmAccessGrantRepository);
  }

  @Test
  void bootstrapRealmDiscoveryAndDirectTextScopeIssuanceLeaveTransactionsToCanonicalOwners()
      throws Exception {
    for (var method :
        List.of(
            AccountServiceImpl.class.getMethod(
                "listBootstrapRealms", String.class, String.class, String.class),
            AccountServiceImpl.class.getMethod(
                "issueDirectTextConnectScope",
                DirectTextCallerContext.class,
                DirectTextJoinTarget.class),
            AccountServiceImpl.class.getMethod(
                "joinPublicProduction", String.class, JoinPublicProductionRequest.class),
            AccountServiceImpl.class.getMethod(
                "listBootstrapCharacters",
                String.class,
                String.class,
                String.class,
                String.class))) {
      assertNull(method.getAnnotation(Transactional.class));
    }
  }

  @Test
  void canonicalScopeReaderRejectsRetiredNumericSignedCarrierBeforeScopeLookup() {
    var reader =
        new net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader(
            jwtUtil, accountConnectScopeRepository, transactionManager, Clock.systemUTC());
    String numeric =
        jwtUtil.generateToken(
            "11", 120000L, Map.of("aud", "bootstrap-connect-scope", "accountId", "11"));
    assertEquals(
        "CONNECT_SCOPE_INVALID",
        assertThrows(AuthenticationException.class, () -> reader.read(UUID.randomUUID(), numeric))
            .getCode());
    verifyNoInteractions(accountConnectScopeRepository);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "account",
        "tenant",
        "realm",
        "namespace",
        "instance",
        "catalog",
        "pointer",
        "target-class",
        "version",
        "audience",
        "time",
        "jti"
      })
  void canonicalScopeReaderRejectsMalformedSignedAuthorityBeforeRetainedLookup(String field) {
    UUID account = UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Map<String, Object> claims = new java.util.HashMap<>();
    claims.put("aud", "bootstrap-connect-scope");
    claims.put("accountId", account.toString());
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("targetClass", "PUBLIC_PRODUCTION");
    claims.put("scopeDigestVersion", 2);
    claims.put("tenantId", UUID.randomUUID().toString());
    claims.put("realmId", REALM_ID);
    claims.put("tenantSlug", "tenant");
    claims.put("worldSlug", "demo");
    claims.put("realmSlug", "production");
    claims.put("playableStateNamespaceId", PLAYABLE_STATE_NAMESPACE_ID);
    claims.put("playableStateScope", "SHARED");
    claims.put("gameInstanceId", UUID.randomUUID().toString());
    claims.put("catalogRevision", 23L);
    claims.put("pointerVersion", 17L);
    claims.put("evaluatedAt", now.toString());
    claims.put("connectScopeExpiresAt", now.plusSeconds(120).toString());
    switch (field) {
      case "account" -> claims.put("accountId", UUID.randomUUID().toString());
      case "tenant" -> claims.put("tenantId", "7");
      case "realm" -> claims.put("realmId", "not-a-uuid");
      case "namespace" -> claims.put("playableStateNamespaceId", "not-a-uuid");
      case "instance" -> claims.put("gameInstanceId", "44");
      case "catalog" -> claims.put("catalogRevision", 0L);
      case "pointer" -> claims.put("pointerVersion", -1L);
      case "target-class" -> claims.put("targetClass", "PRIVATE");
      case "version" -> claims.put("scopeDigestVersion", 1);
      case "audience" -> claims.put("aud", List.of("bootstrap-connect-scope", "control-ui"));
      case "time" -> claims.put("evaluatedAt", now.plusSeconds(60).toString());
      case "jti" -> claims.remove("jti");
      default -> throw new AssertionError(field);
    }
    String token = jwtUtil.generateToken(account.toString(), now.plusSeconds(120), claims);
    var reader =
        new net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader(
            jwtUtil,
            accountConnectScopeRepository,
            transactionManager,
            Clock.fixed(now.plusSeconds(1), java.time.ZoneOffset.UTC));
    assertEquals(
        "CONNECT_SCOPE_INVALID",
        assertThrows(AuthenticationException.class, () -> reader.read(account, token)).getCode());
    verifyNoInteractions(accountConnectScopeRepository);
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
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    AccountTenantMembership activeMembership = membership(account, 7L);
    activeMembership.setMembershipVersion(17L);
    activeMembership.setMembershipAuthorityGeneration(29L);
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(activeMembership));

    var dto = service.getTenantMembershipForRuntime(11L, 7L, "req-1");

    assertEquals(11L, dto.accountId());
    assertEquals(7L, dto.tenantId());
    assertTrue(dto.membershipExists());
    assertTrue(dto.gameplayAdmissionAllowed());
    assertEquals(17L, dto.membershipVersion());
    assertEquals("ACTIVE", dto.membershipLifecycleState());
    assertEquals(29L, dto.membershipAuthorityGeneration());
    assertNotNull(dto.evaluatedAt());
  }

  @Test
  void getTenantMembershipForRuntimeReturnsCoherentMissingMembershipForExistingAccount() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.empty());

    var dto = service.getTenantMembershipForRuntime(11L, 7L, "req-1");

    assertEquals(11L, dto.accountId());
    assertEquals(7L, dto.tenantId());
    assertFalse(dto.membershipExists());
    assertFalse(dto.gameplayAdmissionAllowed());
    assertEquals(0L, dto.membershipVersion());
    assertEquals("MISSING", dto.membershipLifecycleState());
    assertEquals(0L, dto.membershipAuthorityGeneration());
    assertNotNull(dto.evaluatedAt());
  }

  @Test
  void getTenantMembershipForRuntimeReturnsInactiveMembershipWithIndependentGenerations() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    AccountTenantMembership inactiveMembership = membership(account, 7L);
    inactiveMembership.setGameplayAdmissionAllowed(false);
    inactiveMembership.setLifecycleState("INACTIVE");
    inactiveMembership.setMembershipVersion(23L);
    inactiveMembership.setMembershipAuthorityGeneration(41L);
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(11L, 7L))
        .thenReturn(Optional.of(inactiveMembership));

    var dto = service.getTenantMembershipForRuntime(11L, 7L, "req-1");

    assertEquals(11L, dto.accountId());
    assertEquals(7L, dto.tenantId());
    assertTrue(dto.membershipExists());
    assertFalse(dto.gameplayAdmissionAllowed());
    assertEquals(23L, dto.membershipVersion());
    assertEquals("INACTIVE", dto.membershipLifecycleState());
    assertEquals(41L, dto.membershipAuthorityGeneration());
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
  void getTenantEntitlementsForRuntimeFailsClosedBeforeReadingLegacyEvidence() {
    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class,
            () -> service.getTenantEntitlementsForRuntime(7L, "req-current-entitlement"));

    assertEquals("ENTITLEMENT_UNAVAILABLE", exception.getCode());
    verifyNoInteractions(retainedSubscriptionEvidenceRepository);
  }

  @Test
  void directScopeFacadePublishesTheExactCanonicalOwnersArtifactWithoutReminting() {
    var f = canonicalJoinFixture();
    var owner = org.mockito.Mockito.mock(AccountCanonicalPublicRealmScopeOwner.class);
    var retained = org.mockito.Mockito.mock(RealmScope.class);
    when(retained.scope()).thenReturn(f.scope);
    var caller = f.directCaller(f.requestId);
    var target =
        new DirectTextJoinTarget(
            f.scope.tenantId(),
            f.scope.realmId(),
            f.scope.worldSlug(),
            f.scope.realmSlug(),
            f.scope.playableStateNamespaceId(),
            f.scope.playableStateScope(),
            f.scope.gameInstanceId(),
            f.scope.catalogRevision(),
            f.scope.pointerVersion());
    when(owner.issueDirectTextScope(caller, target)).thenReturn(retained);
    service.setCanonicalPublicRealmScopeOwner(owner);
    var result = service.issueDirectTextConnectScope(caller, target);
    assertEquals(f.scope.connectScopeId(), result.connectScopeId());
    assertEquals(f.scope.evaluatedAt(), result.evaluatedAt());
    assertEquals(f.scope.connectScopeExpiresAt(), result.connectScopeExpiresAt());
    org.mockito.Mockito.verify(owner).issueDirectTextScope(caller, target);
    verifyNoInteractions(
        sessionService, f.sources, accountJoinOperationRepository, accountAuditOutboxRepository);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CONNECT_SCOPE_INVALID",
        "CONNECT_SCOPE_MISMATCH",
        "AUTH_UNAVAILABLE",
        "ENTITLEMENT_UNAVAILABLE",
        "TENANT_BILLING_BLOCKED",
        "ADMISSION_POINTER_UNAVAILABLE"
      })
  void directScopeFacadePreservesCanonicalAuthenticationCurrentnessAndBillingRefusal(String code) {
    var owner = org.mockito.Mockito.mock(AccountCanonicalPublicRealmScopeOwner.class);
    service.setCanonicalPublicRealmScopeOwner(owner);
    var refusal = new AuthenticationException(code, "controlled canonical scope owner refusal");
    when(owner.issueDirectTextScope(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(refusal);
    var f = canonicalJoinFixture();
    assertSame(
        refusal,
        assertThrows(
            AuthenticationException.class,
            () ->
                service.issueDirectTextConnectScope(
                    f.directCaller(f.requestId),
                    new DirectTextJoinTarget(
                        f.scope.tenantId(),
                        f.scope.realmId(),
                        f.scope.worldSlug(),
                        f.scope.realmSlug(),
                        f.scope.playableStateNamespaceId(),
                        f.scope.playableStateScope(),
                        f.scope.gameInstanceId(),
                        f.scope.catalogRevision(),
                        f.scope.pointerVersion()))));
    verifyNoInteractions(
        accountJoinOperationRepository,
        accountTenantMembershipRepository,
        accountAuditOutboxRepository);
  }

  @Test
  void directScopeWithoutCanonicalCompositionRefusesBeforeAccountWork() {
    assertEquals(
        "AUTH_UNAVAILABLE",
        assertThrows(
                AuthenticationException.class,
                () -> service.issueDirectTextConnectScope(null, null))
            .getCode());
    verifyNoInteractions(accountRepository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"malformed", "", "numeric-signed"})
  void issueConnectTokenRejectsInvalidScopeWithoutIssuanceOrReplayMutation(String artifact) {
    var f = canonicalJoinFixture();
    service.setCanonicalScopeConsumers(
        new net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader(
            jwtUtil, accountConnectScopeRepository, transactionManager, Clock.systemUTC()),
        f.targets,
        f.sources,
        f.demos,
        f.hosting,
        f.observer,
        f.terminal,
        "test",
        Clock.systemUTC());
    String token =
        artifact.equals("numeric-signed")
            ? jwtUtil.generateToken(
                "11", Map.of("aud", "bootstrap-connect-scope", "accountId", "11"))
            : artifact;
    assertEquals(
        "CONNECT_SCOPE_INVALID",
        assertThrows(
                AuthenticationException.class,
                () ->
                    service.issueConnectToken(f.bootstrap, new ConnectTokenRequest(token, "held")))
            .getCode());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .getConnectTokenReplay(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    verifyNoInteractions(accountTenantMembershipRepository, accountAuditOutboxRepository);
  }

  @Test
  void issueConnectTokenValidatesCanonicalScopeThenKeepsIssuerHeldWithoutReplayOrSessionEffects() {
    var f = canonicalJoinFixture();
    assertEquals(
        "AUTH_UNAVAILABLE",
        assertThrows(
                AuthenticationException.class,
                () ->
                    service.issueConnectToken(
                        f.bootstrap,
                        new ConnectTokenRequest(f.scope.connectScopeId(), "held-issuer")))
            .getCode());
    org.mockito.Mockito.verify(f.reader).read(f.scope.accountId(), f.scope.connectScopeId());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .getConnectTokenReplay(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeConnectTokenReplay(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeSession(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    verifyNoInteractions(
        accountTenantMembershipRepository,
        accountAuditOutboxRepository,
        f.targets,
        f.sources,
        f.demos,
        f.hosting,
        f.observer,
        f.terminal);
  }

  @Test
  void listBootstrapWorldsReadsFreshEntitlementsOncePerCanonicalTenantPerInvocation() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    UUID demoTenant = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID sandboxTenant = UUID.fromString("33333333-3333-4333-8333-333333333333");
    var sourceTargets =
        List.of(
            publicDiscoveryTarget(sandboxTenant, "sandbox", "Sandbox World"),
            publicDiscoveryTarget(demoTenant, "demo", "Demo World"));
    var discovery = composeCanonicalWorldDiscovery(sourceTargets);
    var evaluatedAt = Instant.now().minusSeconds(1);
    when(discovery.entitlements().evaluate(demoTenant))
        .thenReturn(publicDiscoveryEntitlement(demoTenant, true, evaluatedAt));
    when(discovery.entitlements().evaluate(sandboxTenant))
        .thenReturn(publicDiscoveryEntitlement(sandboxTenant, true, evaluatedAt));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    when(accountRepository.findByAccountUuid(account.getAccountUuid()))
        .thenReturn(Optional.of(account));

    var firstCall = service.listBootstrapWorlds(bootstrap.bootstrapToken());
    var secondCall = service.listBootstrapWorlds(bootstrap.bootstrapToken());

    var expectedWorlds =
        List.of(
            new BootstrapWorldDto("tenant-demo", "demo", "Demo World"),
            new BootstrapWorldDto("tenant-sandbox", "sandbox", "Sandbox World"));
    assertEquals(expectedWorlds, firstCall);
    assertEquals(expectedWorlds, secondCall);
    org.mockito.Mockito.verify(discovery.entitlements(), org.mockito.Mockito.times(2))
        .evaluate(demoTenant);
    org.mockito.Mockito.verify(discovery.entitlements(), org.mockito.Mockito.times(2))
        .evaluate(sandboxTenant);
    org.mockito.Mockito.verify(discovery.targets(), org.mockito.Mockito.times(4))
        .listVisiblePublicProductionAdmissionTargets(org.mockito.ArgumentMatchers.any(UUID.class));
  }

  @Test
  void listBootstrapWorldsFiltersGameplayUnavailableTenantAndFailsWhenEntitlementIsUnavailable() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByUsername("demo")).thenReturn(Optional.of(account));
    UUID demoTenant = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID sandboxTenant = UUID.fromString("33333333-3333-4333-8333-333333333333");
    var sourceTargets =
        List.of(
            publicDiscoveryTarget(demoTenant, "demo", "Demo World"),
            publicDiscoveryTarget(sandboxTenant, "sandbox", "Sandbox World"));
    var discovery = composeCanonicalWorldDiscovery(sourceTargets);
    var evaluatedAt = Instant.now().minusSeconds(1);
    when(discovery.entitlements().evaluate(demoTenant))
        .thenReturn(publicDiscoveryEntitlement(demoTenant, true, evaluatedAt))
        .thenReturn(publicDiscoveryEntitlement(demoTenant, true, evaluatedAt))
        .thenThrow(
            new AccountPublicBootstrapEntitlementOwner.UnavailableException(
                new IllegalStateException("controlled current-source failure")));
    when(discovery.entitlements().evaluate(sandboxTenant))
        .thenReturn(publicDiscoveryEntitlement(sandboxTenant, false, evaluatedAt))
        .thenThrow(
            new AccountPublicBootstrapEntitlementOwner.UnavailableException(
                new IllegalStateException("controlled current-source failure")));

    PlayerBootstrapResult bootstrap = service.issuePlayerBootstrap("demo", "password");
    when(sessionService.isAccountSessionActive(11L, bootstrap.bootstrapToken())).thenReturn(true);
    when(accountRepository.findByAccountUuid(account.getAccountUuid()))
        .thenReturn(Optional.of(account));

    var worldsWithUnavailableTenant = service.listBootstrapWorlds(bootstrap.bootstrapToken());

    assertEquals(
        java.util.List.of("demo"),
        worldsWithUnavailableTenant.stream().map(world -> world.worldSlug()).toList());
    AuthenticationException unavailableSandbox =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(bootstrap.bootstrapToken()));

    assertEquals("ENTITLEMENT_UNAVAILABLE", unavailableSandbox.getCode());
    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () -> service.listBootstrapWorlds(bootstrap.bootstrapToken()));

    assertEquals("ENTITLEMENT_UNAVAILABLE", unavailable.getCode());
    org.mockito.Mockito.verify(discovery.entitlements(), org.mockito.Mockito.times(3))
        .evaluate(demoTenant);
    // Every discovery call joins all independent tenant reads, including a failed call.
    org.mockito.Mockito.verify(discovery.entitlements(), org.mockito.Mockito.times(3))
        .evaluate(sandboxTenant);
  }

  @Test
  void bootstrapPublicRealmFacadePreservesEmptyCanonicalOwnerResult() {
    var owner = org.mockito.Mockito.mock(AccountCanonicalPublicRealmScopeOwner.class);
    service.setCanonicalPublicRealmScopeOwner(owner);
    when(owner.listRealmScopes("bootstrap", "source-tenant", "demo")).thenReturn(List.of());
    assertTrue(service.listBootstrapRealms("bootstrap", "source-tenant", "demo").isEmpty());
    verifyNoInteractions(accountRealmAccessGrantRepository, accountTenantMembershipRepository);
  }

  @Test
  void grantRealmAccessUpsertsRuntimeGrant() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
  void revokeRealmAccessUsesDurableRepositoryRevocation() {
    service.revokeRealmAccess(11L, 7L, "demo", "preview");

    org.mockito.Mockito.verify(accountRealmAccessGrantRepository)
        .revokeByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(11L, 7L, "demo", "preview");
  }

  @Test
  void getRealmAccessGrantForRuntimeReturnsGrantState() {
    Account account = new Account();
    account.setId(11L);
    setPersistedAuthenticationIdentity(account);
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
  void getRealmAccessGrantForRuntimeDoesNotAuthorizeRetainedRevocationTombstone() {
    Account account = new Account();
    account.setId(11L);
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    AccountRealmAccessGrant revoked = new AccountRealmAccessGrant();
    revoked.setGrantVersion(7L);
    revoked.setGranted(false);
    when(accountRealmAccessGrantRepository.findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "preview"))
        .thenReturn(Optional.of(revoked));

    var result = service.getRealmAccessGrantForRuntime(11L, 7L, "demo", "preview", "req-grant-2");

    assertFalse(result.granted());
    assertEquals(7L, result.grantVersion());
  }

  @Test
  void grantRealmAccessReactivatesRetainedTombstoneAndAdvancesItsVersion() {
    Account account = new Account();
    account.setId(11L);
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));
    AccountRealmAccessGrant revoked = new AccountRealmAccessGrant();
    revoked.setId(19L);
    revoked.setAccount(account);
    revoked.setTenantId(7L);
    revoked.setWorldSlug("demo");
    revoked.setRealmSlug("preview");
    revoked.setGrantVersion(7L);
    revoked.setGranted(false);
    when(accountRealmAccessGrantRepository.findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
            11L, 7L, "demo", "preview"))
        .thenReturn(Optional.of(revoked));
    when(accountRealmAccessGrantRepository.save(
            org.mockito.ArgumentMatchers.any(AccountRealmAccessGrant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.grantRealmAccess(
            new RealmAccessGrantRequest(
                11L, 7L, "demo", "preview", "operator", "regrant", "req-grant-3"));

    assertTrue(result.granted());
    assertEquals(8L, result.grantVersion());
    assertTrue(revoked.isGranted());
    org.mockito.Mockito.verify(accountRealmAccessGrantRepository).save(revoked);
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
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));

    var dto = service.getProfile(1L, 2L);

    assertEquals("demo", dto.displayName());
    assertEquals(account.getAccountUuid().toString(), dto.accountId());
    org.mockito.Mockito.verify(accountRepository).findByAccountUuid(account.getAccountUuid());
  }

  @Test
  void getProfileRefusesDifferentPrivateOwnerBeforeProjection() {
    Account differentOwner = new Account();
    differentOwner.setId(3L);
    Profile profile = profile(differentOwner, 1L, "private");
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));

    assertThrows(AuthenticationException.class, () -> service.getProfile(1L, 2L));

    verifyNoInteractions(notificationService);
  }

  @Test
  void profileProjectionRedactsRetainedLegacyPolicyWithoutRewritingEntity() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setTenantId(1L);
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.HIDDEN_STAFF);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));

    var dto = service.getProfile(1L, 2L);

    assertEquals(ProfilePresenceVisibilityPolicy.PRIVATE, dto.presenceVisibilityPolicy());
    assertEquals(
        ProfilePresenceVisibilityPolicy.HIDDEN_STAFF, profile.getPresenceVisibilityPolicy());
    org.mockito.Mockito.verify(profileRepository, org.mockito.Mockito.never()).save(profile);
  }

  @Test
  void profileProjectionRereadsFullOwnerAndRejectsWrongOrUnavailableIdentity() {
    Account owner = new Account();
    owner.setId(2L);
    setPersistedAuthenticationIdentity(owner);
    Account partial = new Account();
    partial.setId(2L);
    Profile profile = profile(partial, 1L, "partial");
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));
    when(accountRepository.findById(2L)).thenReturn(Optional.of(owner));
    assertEquals(owner.getAccountUuid().toString(), service.getProfile(1L, 2L).accountId());
    partial.setId(3L);
    assertEquals(
        "AUTH_UNAVAILABLE",
        assertThrows(AuthenticationException.class, () -> service.getProfile(1L, 2L)).getCode());
    partial.setId(2L);
    owner.setAccountUuid(null);
    assertEquals(
        "AUTH_UNAVAILABLE",
        assertThrows(AuthenticationException.class, () -> service.getProfile(1L, 2L)).getCode());
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
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
    profile.setAccount(account);
    profile.setTenantId(1L);
    profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));
    when(profileRepository.save(profile)).thenReturn(profile);

    var dto =
        service.updateProfile(
            new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
                1L,
                account.getAccountUuid().toString(),
                "demo",
                "bio",
                ProfilePresenceVisibilityPolicy.PRIVATE));

    assertEquals("demo", dto.displayName());
    assertEquals(account.getAccountUuid().toString(), dto.accountId());
    assertEquals(ProfilePresenceVisibilityPolicy.PRIVATE, profile.getPresenceVisibilityPolicy());
    org.mockito.Mockito.verify(profileRepository).findByAccountIdAndTenantId(2L, 1L);
    org.mockito.Mockito.verify(notificationService).sendNotification(1L, 2L, "Profile updated");
  }

  @Test
  void profileReadAndUpdateFailClosedWithoutCurrentTenantMembership() {
    Account account = new Account();
    account.setId(2L);
    setPersistedAuthenticationIdentity(account);
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
                            1L,
                            account.getAccountUuid().toString(),
                            "demo",
                            "bio",
                            ProfilePresenceVisibilityPolicy.PRIVATE)))
            .getMessage());

    verifyNoInteractions(profileRepository, notificationService);
  }

  @Test
  void updateProfileRejectsReservedHiddenStaffPolicyBeforePersistence() {
    var request =
        new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
            1L,
            "d09f80cb-a46a-415e-8d35-c9e8068e49de",
            "demo",
            "bio",
            ProfilePresenceVisibilityPolicy.HIDDEN_STAFF);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(request));

    assertEquals("Profile presence visibility policy is not selectable", exception.getMessage());
    verifyNoInteractions(profileRepository, notificationService);
  }

  @Test
  void exportAccountDataIncludesProfilesAcrossTenants() {
    Account account = new Account();
    account.setId(2L);
    account.setAccountUuid(UUID.fromString("d09f80cb-a46a-415e-8d35-c9e8068e49de"));
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION);
    account.setAccountUuidSourceNumericId(2L);
    when(accountRepository.findByAccountUuid(account.getAccountUuid()))
        .thenReturn(Optional.of(account));
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    Profile tenantOne = profile(account, 1L, "one");
    Profile tenantTwo = profile(account, 2L, "two");
    when(accountRepository.findByAccountUuid(account.getAccountUuid()))
        .thenReturn(Optional.of(account));
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(profileRepository.findByAccountId(2L)).thenReturn(java.util.List.of(tenantOne, tenantTwo));

    var export = service.exportAccountData(2L);

    assertEquals("d09f80cb-a46a-415e-8d35-c9e8068e49de", export.account().id());
    assertEquals(2, export.profiles().size());
    export
        .profiles()
        .forEach(dto -> assertEquals(account.getAccountUuid().toString(), dto.accountId()));
    assertEquals(
        java.util.List.of(1L, 2L),
        export.profiles().stream()
            .map(net.firedevops.firemud.accountservice.dto.ProfileDto::tenantId)
            .toList());
    assertEquals(
        java.util.List.of("one", "two"),
        export.profiles().stream()
            .map(net.firedevops.firemud.accountservice.dto.ProfileDto::displayName)
            .toList());
    org.mockito.Mockito.verify(accountRepository).findById(2L);
    org.mockito.Mockito.verify(accountRepository).findByAccountUuid(account.getAccountUuid());

    Account foreignOwner = new Account();
    foreignOwner.setId(3L);
    when(profileRepository.findByAccountId(2L))
        .thenReturn(java.util.List.of(tenantOne, tenantTwo, profile(foreignOwner, 3L, "foreign")));
    assertEquals(
        "AUTH_UNAVAILABLE",
        assertThrows(AuthenticationException.class, () -> service.exportAccountData(2L)).getCode());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.times(2)).findById(2L);
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.times(2))
        .findByAccountUuid(account.getAccountUuid());

    when(accountRepository.findByAccountUuid(account.getAccountUuid()))
        .thenReturn(Optional.empty());
    assertEquals(
        "AUTH_UNAVAILABLE",
        assertThrows(AuthenticationException.class, () -> service.exportAccountData(2L)).getCode());
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
  void exportTenantDataUsesPublicAccountUuidAndRetainsTenantLocalFields() {
    Account account = new Account();
    account.setId(2L);
    account.setAccountUuid(UUID.fromString("d09f80cb-a46a-415e-8d35-c9e8068e49de"));
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION);
    account.setAccountUuidSourceNumericId(2L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(accountRepository.findByAccountUuid(account.getAccountUuid()))
        .thenReturn(Optional.of(account));
    when(profileRepository.findByAccountIdAndTenantId(2L, 7L)).thenReturn(Optional.empty());
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(2L, 7L)).thenReturn(true);

    var export = service.exportTenantData(7L, 2L);

    assertEquals("d09f80cb-a46a-415e-8d35-c9e8068e49de", export.account().id());
    assertEquals(7L, export.tenantId());
    assertNull(export.profile());
    org.mockito.Mockito.verify(accountRepository).findById(2L);
    org.mockito.Mockito.verify(accountRepository).findByAccountUuid(account.getAccountUuid());
  }

  @Test
  void deleteAccountRefusesNonterminalSubscription() {
    Account account = new Account();
    account.setId(2L);
    RetainedSubscriptionEvidence subscription =
        new RetainedSubscriptionEvidence(
            91L, 2L, "unqualified", "active", null, null, 7L, 19L, UUID.randomUUID());
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(retainedSubscriptionEvidenceRepository.findHistoryByAccountId(2L))
        .thenReturn(java.util.List.of(subscription));

    AccountLifecycleException ex =
        assertThrows(AccountLifecycleException.class, () -> service.deleteAccount(2L));
    assertEquals("ACCOUNT_DELETE_ACTIVE_BILLING_OWNER", ex.getCode());
    org.mockito.Mockito.verify(retainedSubscriptionEvidenceRepository).findHistoryByAccountId(2L);
  }

  @Test
  void deleteAccountRemainsUnavailableAfterTerminalSubscriptionsWithoutChildWrites() {
    Account account = new Account();
    account.setId(2L);
    RetainedSubscriptionEvidence subscription =
        new RetainedSubscriptionEvidence(
            91L,
            2L,
            "unqualified",
            "canceled",
            null,
            java.time.LocalDateTime.now(),
            7L,
            19L,
            UUID.randomUUID());
    when(accountRepository.findById(2L)).thenReturn(Optional.of(account));
    when(retainedSubscriptionEvidenceRepository.findHistoryByAccountId(2L))
        .thenReturn(java.util.List.of(subscription));

    AccountLifecycleException ex =
        assertThrows(AccountLifecycleException.class, () -> service.deleteAccount(2L));
    assertEquals("ACCOUNT_DELETE_UNAVAILABLE", ex.getCode());

    org.mockito.Mockito.verifyNoInteractions(
        emailVerificationTokenRepository,
        passwordResetTokenRepository,
        accountRealmAccessGrantRepository,
        externalAccountRepository,
        paymentTransactionRepository,
        profileRepository,
        accountTenantMembershipRepository);
    org.mockito.Mockito.verify(retainedSubscriptionEvidenceRepository).findHistoryByAccountId(2L);
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never()).delete(account);
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
  void missingRetainedSourceDeniesPasswordResetBeforeTokenConsumption() {
    var account = new Account();
    account.setId(2L);
    account.setAccountUuid(UUID.randomUUID());
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(2L);
    account.setPasswordHash("original-password-hash");
    var token = new net.firedevops.firemud.accountservice.entity.PasswordResetToken();
    token.setId(7L);
    token.setAccount(account);
    token.setToken("retained-reset-token");
    token.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(10));
    when(passwordResetTokenRepository.findByToken(token.getToken())).thenReturn(Optional.of(token));
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(accountPasswordResetOperationRepository.findByTokenHash(
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());
    when(accountPasswordResetDraftSourceChangeRepository.findAccountIdByTokenHash(
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(
            new net.firedevops.firemud.accountservice.repository
                .AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException())
        .when(accountAuthorityGenerationRepository)
        .read(
            net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
                .AuthorityScope.account(account.getAccountUuid()));
    assertThrows(
        net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository
            .SourceEvidenceUnavailableException.class,
        () ->
            service.completePasswordReset(
                new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                    token.getToken(), "new-password")));
    org.mockito.Mockito.verify(passwordResetTokenRepository, org.mockito.Mockito.never())
        .consumeIfUnexpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never()).save(account);
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void candidatePasswordVerifierIsPreparedBeforeWritableOwnerTransactionBegins() {
    AtomicBoolean candidateVerifierPrepared = new AtomicBoolean();
    Argon2 argon2 = org.mockito.Mockito.mock(Argon2.class);
    when(argon2.hash(
            org.mockito.ArgumentMatchers.eq(2),
            org.mockito.ArgumentMatchers.eq(65536),
            org.mockito.ArgumentMatchers.eq(1),
            org.mockito.ArgumentMatchers.any(char[].class)))
        .thenAnswer(
            invocation -> {
              candidateVerifierPrepared.set(true);
              return "prepared-candidate-verifier";
            });
    org.mockito.Mockito.doAnswer(
            invocation -> {
              assertTrue(
                  candidateVerifierPrepared.get(),
                  "Password hashing must complete before the owner transaction starts");
              return new SimpleTransactionStatus();
            })
        .when(transactionManager)
        .getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition.class));
    when(accountPasswordResetOperationRepository.findByTokenHash(
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());
    when(accountPasswordResetDraftSourceChangeRepository.findAccountIdByTokenHash(
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());
    when(passwordResetTokenRepository.findByToken(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());

    try (org.mockito.MockedStatic<Argon2Factory> argon2Factory =
        org.mockito.Mockito.mockStatic(Argon2Factory.class)) {
      argon2Factory.when(Argon2Factory::create).thenReturn(argon2);

      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.completePasswordReset(
                  new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                      "invalid-reset-token", "candidate-password")));
    }

    org.mockito.Mockito.verify(transactionManager, org.mockito.Mockito.atLeastOnce())
        .getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition.class));
    assertTrue(candidateVerifierPrepared.get());
  }

  @Test
  void passwordResetIssuanceFenceOverflowStopsBeforeTokenPasswordOrEventMutation() {
    UUID accountUuid = UUID.randomUUID();
    var account = new Account();
    account.setId(2L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(2L);
    account.setPasswordHash("original-password-hash");
    var token = new net.firedevops.firemud.accountservice.entity.PasswordResetToken();
    token.setId(7L);
    token.setAccount(account);
    token.setToken("overflow-reset-token");
    token.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(10));
    when(passwordResetTokenRepository.findByToken(token.getToken())).thenReturn(Optional.of(token));
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(accountPasswordResetOperationRepository.findByTokenHash(
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());
    when(accountPasswordResetDraftSourceChangeRepository.findAccountIdByTokenHash(
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());
    var accountScope =
        net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
            .AuthorityScope.account(accountUuid);
    when(accountAuthorityGenerationRepository.read(accountScope))
        .thenReturn(
            new net.firedevops.firemud.accountservice.repository
                .AccountAuthorityGenerationRepository.ScopeState(
                accountScope,
                1L,
                1L,
                new net.firedevops.firemud.accountservice.repository
                    .AccountAuthorityGenerationRepository.IssuanceFence(
                    accountUuid, Long.MAX_VALUE, 1L)));
    when(accountAuthorityOutboxRepository.readCheckpoint(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());

    org.jooq.DSLContext dsl = org.mockito.Mockito.mock(org.jooq.DSLContext.class);
    var sourceChanges = new AccountPasswordResetDraftSourceChangeRepository(dsl);
    when(accountPasswordResetDraftSourceChangeRepository.participate(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation ->
                sourceChanges.participate(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(5),
                    invocation.getArgument(6),
                    invocation.getArgument(7)));

    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThrows(
          ArithmeticException.class,
          () ->
              service.completePasswordReset(
                  new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                      token.getToken(), "overflow-password")));
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }

    org.mockito.Mockito.verify(passwordResetTokenRepository, org.mockito.Mockito.never())
        .consumeIfUnexpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(passwordResetTokenRepository, org.mockito.Mockito.never())
        .deleteExactAfterSourceAbort(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(accountPasswordResetOperationRepository, org.mockito.Mockito.never())
        .insert(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountAuthorityOutboxRepository, org.mockito.Mockito.never())
        .append(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(byte[].class));
    org.mockito.Mockito.verify(dsl, org.mockito.Mockito.never())
        .execute(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class));
  }

  @Test
  void abortedPasswordResetRetryReturnsInvalidTokenWithoutReplayingMutation() {
    UUID accountUuid = UUID.randomUUID();
    var account = new Account();
    account.setId(2L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(2L);
    account.setPasswordHash("original-password-hash");
    var abortedIntent =
        org.mockito.Mockito.mock(AccountPasswordResetDraftSourceChangeRepository.Intent.class);
    when(accountPasswordResetOperationRepository.findByTokenHash(
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());
    when(accountPasswordResetDraftSourceChangeRepository.findAccountIdByTokenHash(
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.of(account.getId()));
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(accountPasswordResetDraftSourceChangeRepository.findByTokenHash(
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.of(abortedIntent));
    when(abortedIntent.accountId()).thenReturn(account.getId());
    when(abortedIntent.accountUuid()).thenReturn(accountUuid);
    when(abortedIntent.status()).thenReturn("SOURCE_ABORTED");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.completePasswordReset(
                    new net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest(
                        "aborted-reset-token", "retry-password")));

    assertEquals("Invalid token", error.getMessage());
    verifyNoInteractions(accountAuthorityGenerationRepository, accountAuthorityOutboxRepository);
    org.mockito.Mockito.verify(passwordResetTokenRepository, org.mockito.Mockito.never())
        .findByToken(org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(passwordResetTokenRepository, org.mockito.Mockito.never())
        .consumeIfUnexpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(passwordResetTokenRepository, org.mockito.Mockito.never())
        .deleteExactAfterSourceAbort(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(
            accountPasswordResetDraftSourceChangeRepository, org.mockito.Mockito.never())
        .participate(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .updatePasswordHashForLockedAccount(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void requestPasswordResetUnknownEmailIsNeutral() {
    when(accountRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

    service.requestPasswordReset(new PasswordResetRequest("unknown@example.com"));

    org.mockito.Mockito.verifyNoInteractions(
        passwordResetTokenRepository, emailService, notificationService);
  }

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
  void verifyEmailClaimsTokenBeforeSavingOnlyTheFreshLockedAccount() {
    EmailVerificationToken token = emailVerificationToken();
    Account account = new Account();
    account.setId(1L);
    account.setAccountUuid(UUID.randomUUID());
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(1L);
    account.setUsername("current-name");
    account.setEmail("current@example.com");
    account.setPasswordHash("current-password");
    account.setRole("player");
    account.setLoginAuthModes("PASSWORD");
    Account expected = new Account();
    org.springframework.beans.BeanUtils.copyProperties(account, expected);
    expected.setEmailVerified(true);
    when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
    when(emailVerificationTokenRepository.consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(token), org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);

    service.verifyEmail(new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest("tok"));

    assertEquals(expected, account);
    assertFalse(token.getAccount().isEmailVerified());
    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(emailVerificationTokenRepository, accountRepository);
    order.verify(emailVerificationTokenRepository).findByToken("tok");
    order.verify(accountRepository).findByIdForUpdate(1L);
    order
        .verify(emailVerificationTokenRepository)
        .consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(token), org.mockito.ArgumentMatchers.any());
    order.verify(accountRepository).save(account);
    org.mockito.Mockito.verify(emailVerificationTokenRepository, org.mockito.Mockito.never())
        .delete(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void verifyEmailRejectsLostOrChangedTokenClaimBeforeAccountMutation() {
    EmailVerificationToken token = emailVerificationToken();
    Account current = new Account();
    current.setId(1L);
    when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
    when(emailVerificationTokenRepository.consumeIfUnexpired(
            org.mockito.ArgumentMatchers.eq(token), org.mockito.ArgumentMatchers.any()))
        .thenReturn(false);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.verifyEmail(
                new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest("tok")));

    assertFalse(current.isEmailVerified());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void verifyEmailRejectsExpiredAndExactlyExpiringTokenAfterAccountLock(boolean equality) {
    EmailVerificationToken token = emailVerificationToken();
    java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 11, 1, 12, 0);
    token.setExpiresAt(equality ? now : now.minusNanos(1));
    Account current = new Account();
    current.setId(1L);
    when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));

    try (org.mockito.MockedStatic<java.time.LocalDateTime> clock =
        org.mockito.Mockito.mockStatic(
            java.time.LocalDateTime.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      clock.when(java.time.LocalDateTime::now).thenReturn(now);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.verifyEmail(
                  new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest("tok")));
    }

    assertFalse(current.isEmailVerified());
    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(emailVerificationTokenRepository, accountRepository);
    order.verify(emailVerificationTokenRepository).findByToken("tok");
    order.verify(accountRepository).findByIdForUpdate(1L);
    org.mockito.Mockito.verify(emailVerificationTokenRepository, org.mockito.Mockito.never())
        .consumeIfUnexpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void verifyEmailRejectsMalformedTokenOwnerBeforeLockOrMutation() {
    EmailVerificationToken token = emailVerificationToken();
    token.getAccount().setId(null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.verifyEmail(
                new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest("tok")));

    verifyNoInteractions(accountRepository);
    org.mockito.Mockito.verify(emailVerificationTokenRepository, org.mockito.Mockito.never())
        .consumeIfUnexpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  private EmailVerificationToken emailVerificationToken() {
    Account locator = new Account();
    locator.setId(1L);
    EmailVerificationToken token = new EmailVerificationToken();
    token.setId(9L);
    token.setToken("tok");
    token.setAccount(locator);
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
    when(emailVerificationTokenRepository.findByToken("tok")).thenReturn(Optional.of(token));
    return token;
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

  private void setPersistedAuthenticationIdentity(Account account) {
    setPersistedAuthenticationIdentity(
        account, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
  }

  private void setPersistedAuthenticationIdentity(
      Account account, AccountIdentityProvenance provenance) {
    account.setAccountUuid(UUID.randomUUID());
    account.setAccountUuidProvenance(provenance);
    account.setAccountUuidSourceNumericId(account.getId());
    when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
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

  private void composeOrdinaryBootstrapWorldDiscovery() {
    UUID tenant = UUID.fromString("22222222-2222-4222-8222-222222222222");
    var fixture =
        composeCanonicalWorldDiscovery(
            List.of(publicDiscoveryTarget(tenant, "demo", "Demo World")));
    when(fixture.entitlements().evaluate(tenant))
        .thenReturn(publicDiscoveryEntitlement(tenant, true, Instant.now().minusSeconds(1)));
  }

  private record CanonicalWorldDiscoveryFixture(
      CanonicalGameplayAdmissionDecisionReadClient targets,
      AccountPublicBootstrapEntitlementOwner entitlements) {}

  private CanonicalWorldDiscoveryFixture composeCanonicalWorldDiscovery(List<Target> targets) {
    CanonicalGameplayAdmissionDecisionReadClient targetReader =
        org.mockito.Mockito.mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    var collection =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.common.gamesession
                .CanonicalGameplayAdmissionDecisionReadGrpcCodec.VisiblePublicProductionTargets
                .class);
    when(collection.targets()).thenReturn(targets);
    when(targetReader.listVisiblePublicProductionAdmissionTargets(
            org.mockito.ArgumentMatchers.any(UUID.class)))
        .thenReturn(collection);
    AccountPublicBootstrapEntitlementOwner entitlements =
        org.mockito.Mockito.mock(AccountPublicBootstrapEntitlementOwner.class);
    var callerVerifier =
        new AccountPlayerBootstrapCallerVerifier(jwtUtil, accountRepository, sessionService);
    service.setPublicBootstrapWorldsOwner(
        new AccountPublicBootstrapWorldsOwner(
            callerVerifier, targetReader, entitlements, Clock.systemUTC()));
    return new CanonicalWorldDiscoveryFixture(targetReader, entitlements);
  }

  private static Target publicDiscoveryTarget(UUID tenantId, String worldSlug, String displayName) {
    Target target = org.mockito.Mockito.mock(Target.class);
    when(target.canonicalTenantId()).thenReturn(tenantId);
    when(target.tenantSlug()).thenReturn("tenant-" + worldSlug);
    when(target.worldSlug()).thenReturn(worldSlug);
    when(target.worldDisplayName()).thenReturn(displayName);
    return target;
  }

  private static AccountPublicBootstrapEntitlementOwner.Evaluation publicDiscoveryEntitlement(
      UUID tenantId, boolean gameplayAvailable, Instant evaluatedAt) {
    return new AccountPublicBootstrapEntitlementOwner.Evaluation(
        tenantId,
        net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository
            .SourceKind.NON_PAID_DEMO,
        gameplayAvailable,
        false,
        1L,
        1L,
        evaluatedAt,
        evaluatedAt.plusSeconds(30));
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
