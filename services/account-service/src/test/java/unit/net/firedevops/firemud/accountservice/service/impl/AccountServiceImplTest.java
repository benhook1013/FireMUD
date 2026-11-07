package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
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
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.EmailService;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.accountservice.service.exception.AccountAlreadyExistsException;
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.ReloadableJwtUtil;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AccountServiceImplTest {
  private static final String JWT_SECRET = "mysecretkey123456789012345678901";
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

  private enum PublicGameplayOperation {
    WORLDS,
    REALMS,
    CHARACTERS,
    BROWSER_JOIN,
    DIRECT_SCOPE,
    DIRECT_JOIN,
    CONNECT_TOKEN
  }

  /** The complete canonical positive fixtures remain in the public-facade successor. */
  @ParameterizedTest
  @EnumSource(PublicGameplayOperation.class)
  void publicGameplayRemainsUnavailableBeforeReadingAnyCallerOrAuthority(
      PublicGameplayOperation operation) {
    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () -> invokePublicGameplayOperation(operation, null, null));

    assertEquals("AUTH_UNAVAILABLE", unavailable.getCode());
    verifyPublicGameplayHasNoIo();
  }

  @ParameterizedTest
  @EnumSource(
      value = PublicGameplayOperation.class,
      names = "DIRECT_SCOPE",
      mode = EnumSource.Mode.EXCLUDE)
  void retainedNumericSignedCarriersCannotReenablePublicGameplay(
      PublicGameplayOperation operation) {
    String bootstrap =
        jwtUtil.generateToken("11", 300000L, Map.of("aud", "player-bootstrap", "accountId", "11"));
    String scope =
        jwtUtil.generateToken(
            "11",
            120000L,
            Map.of(
                "aud",
                "bootstrap-connect-scope",
                "accountId",
                "11",
                "tenantId",
                "7",
                "gameInstanceId",
                "44"));
    org.mockito.Mockito.clearInvocations(jwtUtil);

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () -> invokePublicGameplayOperation(operation, bootstrap, scope));

    assertEquals("AUTH_UNAVAILABLE", unavailable.getCode());
    verifyPublicGameplayHasNoIo();
  }

  private void invokePublicGameplayOperation(
      PublicGameplayOperation operation, String bootstrap, String scope) {
    JoinPublicProductionRequest join =
        scope == null ? null : new JoinPublicProductionRequest(scope, UUID.randomUUID().toString());
    switch (operation) {
      case WORLDS -> service.listBootstrapWorlds(bootstrap);
      case REALMS -> service.listBootstrapRealms(bootstrap, "demo");
      case CHARACTERS -> service.listBootstrapCharacters(bootstrap, "demo", "production", scope);
      case BROWSER_JOIN -> service.joinPublicProduction(bootstrap, join);
      case DIRECT_SCOPE -> service.issueDirectTextConnectScope(null, null);
      case DIRECT_JOIN -> service.joinPublicProductionFromGameSession(null, join);
      case CONNECT_TOKEN ->
          service.issueConnectToken(
              bootstrap,
              scope == null ? null : new ConnectTokenRequest(scope, UUID.randomUUID().toString()));
    }
  }

  private void verifyPublicGameplayHasNoIo() {
    verifyNoInteractions(
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
        retainedSubscriptionEvidenceRepository,
        paymentTransactionRepository,
        profileRepository,
        externalAccountRepository,
        passwordResetTokenRepository,
        emailVerificationTokenRepository,
        notificationService,
        emailService,
        sessionService,
        transactionManager,
        jwtUtil);
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
    org.mockito.Mockito.verify(accountRepository).findByAccountUuid(account.getAccountUuid());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .isAccountSessionActive(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
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
