package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.GameplayCredentialSourceContext;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayReadback;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayState;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner.CredentialVerification;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource.CredentialDigestKey;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigester;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayPrimaryCredentialVerifier;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real Account primary helpers and Argon2; SQL collaborators are mocked, not physical SQL proof.
 */
class AccountGameplayPrimaryCredentialVerifierTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final UUID CONTEXT_ID = UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436");
  private static final String WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final Instant NOW = Instant.parse("2027-01-15T12:00:00Z");
  private static final String PASSWORD_HASH = hash("password");
  private static final String OTP_HASH = hash("246810");
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final AccountEmailLoginChallengeRepository challenges =
      mock(AccountEmailLoginChallengeRepository.class);
  private final JwtUtil jwt = mock(JwtUtil.class);
  private final SessionService sessions = mock(SessionService.class);
  private final RecordingTransactions transactions = new RecordingTransactions();
  private final AccountServiceImpl authority =
      new AccountServiceImpl(
          accounts,
          mock(
              net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
                  .class),
          mock(
              net.firedevops.firemud.accountservice.repository
                  .AccountAuthoritySourceEvidenceRepository.class),
          mock(
              net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
                  .class),
          mock(
              net.firedevops.firemud.accountservice.repository
                  .AccountPasswordResetOperationRepository.class),
          mock(
              net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository
                  .class),
          mock(
              net.firedevops.firemud.accountservice.repository
                  .AccountSecurityStateOperationRepository.class),
          mock(
              net.firedevops.firemud.accountservice.service
                  .AccountPasswordResetDraftSourceChangeRepository.class),
          mock(net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository.class),
          mock(
              net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.class),
          mock(
              net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository
                  .class),
          challenges,
          mock(
              net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository
                  .class),
          mock(
              net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository
                  .class),
          org.mapstruct.factory.Mappers.getMapper(
              net.firedevops.firemud.accountservice.mapper.AccountMapper.class),
          mock(net.firedevops.firemud.accountservice.repository.ProfileRepository.class),
          mock(net.firedevops.firemud.accountservice.mapper.ProfileMapper.class),
          mock(net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository.class),
          mock(net.firedevops.firemud.accountservice.repository.SubscriptionRepository.class),
          mock(net.firedevops.firemud.accountservice.repository.ExternalAccountRepository.class),
          mock(net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository.class),
          mock(
              net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository
                  .class),
          mock(net.firedevops.firemud.accountservice.service.NotificationService.class),
          mock(net.firedevops.firemud.accountservice.service.EmailService.class),
          mock(net.firedevops.firemud.accountservice.config.MailProperties.class),
          mock(net.firedevops.firemud.accountservice.config.AccountTokenProperties.class),
          mock(net.firedevops.firemud.common.security.JwtAuthProperties.class),
          mock(net.firedevops.firemud.accountservice.client.GameSessionClient.class),
          mock(net.firedevops.firemud.accountservice.client.EntityManagementClient.class),
          jwt,
          sessions,
          transactions);
  private final AccountGameplayPrimaryCredentialVerifier verifier =
      new AccountGameplayPrimaryCredentialVerifier(authority);
  private Account account;
  private AccountEmailLoginChallenge challenge;

  @BeforeEach
  void setup() {
    account = new Account();
    account.setId(42L);
    account.setAccountUuid(ACCOUNT_ID);
    account.setAccountUuidSourceNumericId(42L);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    account.setEmail("player@example.com");
    account.setPasswordHash(PASSWORD_HASH);
    challenge = new AccountEmailLoginChallenge();
    challenge.setId(15L);
    challenge.setAccountId(42L);
    challenge.setCodeHash(OTP_HASH);
    challenge.setExpiresAt(LocalDateTime.now().plusMinutes(10));
    when(challenges.findByAccountId(42L)).thenReturn(Optional.of(challenge));
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void cleanup() {
    verifyNoInteractions(jwt, sessions);
    verify(accounts, never()).save(any(Account.class));
    TransactionSynchronizationManager.clear();
  }

  @Test
  void hashFactoryFailureStillWipesAlreadyAllocatedCredentialArray() throws Exception {
    char[] material = "factory-failure-credential".toCharArray();
    var hash = AccountServiceImpl.class.getDeclaredMethod("hashPassword", char[].class);
    hash.setAccessible(true);
    IllegalStateException failure = new IllegalStateException("factory unavailable");
    try (var factory = mockStatic(Argon2Factory.class)) {
      factory.when(Argon2Factory::create).thenThrow(failure);
      assertThatThrownBy(() -> hash.invoke(authority, (Object) material)).hasCause(failure);
      assertThat(material).containsOnly('\0');
      verifyNoInteractions(accounts, challenges);
    }
  }

  @Test
  void nativeWipeFailureStillWipesCredentialArrayInJava() throws Exception {
    char[] material = "native-wipe-failure-credential".toCharArray();
    var hash = AccountServiceImpl.class.getDeclaredMethod("hashPassword", char[].class);
    hash.setAccessible(true);
    Argon2 crypto = mock(Argon2.class);
    IllegalStateException failure = new IllegalStateException("native wipe unavailable");
    try (var factory = mockStatic(Argon2Factory.class)) {
      factory.when(Argon2Factory::create).thenReturn(crypto);
      when(crypto.hash(eq(2), eq(65536), eq(1), any(char[].class))).thenReturn("verifier");
      doThrow(failure).when(crypto).wipeArray(material);
      assertThatThrownBy(() -> hash.invoke(authority, (Object) material)).hasCause(failure);
      assertThat(material).containsOnly('\0');
      verify(crypto).wipeArray(material);
      verifyNoInteractions(accounts, challenges);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"password", "246810"})
  void existingControlUiPrimaryHookRetainsItsImmediateOtpConsumption(String secret) {
    when(accounts.findByEmail("player@example.com")).thenReturn(Optional.of(account));
    when(accounts.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    assertThat(
            authority
                .authenticateControlUiPrimaryIdentity("player@example.com", secret)
                .accountId())
        .isEqualTo(ACCOUNT_ID);
    var ownerOrder = org.mockito.Mockito.inOrder(accounts, challenges);
    ownerOrder.verify(accounts).findByEmail("player@example.com");
    ownerOrder.verify(accounts).findByIdForUpdate(account.getId());
    ownerOrder.verify(challenges).lockAccountChallenge(account.getId());
    if (secret.equals("246810")) verify(challenges).delete(challenge);
    else verify(challenges, never()).delete(any());
  }

  @ParameterizedTest
  @CsvSource({
    "PASSWORD,false,password,false",
    "PASSWORD,false,wrong,true",
    "EMAIL_OTP,true,246810,false",
    "EMAIL_OTP,true,wrong,true",
    "EMAIL_OTP,false,246810,true",
    "'PASSWORD,EMAIL_OTP',true,password,false",
    "'PASSWORD,EMAIL_OTP',true,246810,false",
    "'PASSWORD,EMAIL_OTP',true,wrong,true"
  })
  void canonicalModesAndDenialsPerformExactlyTwoKdfVerifications(
      String modes, boolean activeChallenge, String secret, boolean denied) {
    account.setLoginAuthModes(modes);
    if (!activeChallenge) challenge.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    Argon2 crypto = mock(Argon2.class);
    try (var factory = mockStatic(Argon2Factory.class)) {
      factory.when(Argon2Factory::create).thenReturn(crypto);
      when(crypto.hash(eq(2), eq(65536), eq(1), any(char[].class)))
          .thenReturn("process-local-decoy");
      when(crypto.verify(anyString(), any(char[].class)))
          .thenAnswer(
              call -> {
                String storedHash = call.getArgument(0);
                String presented = new String((char[]) call.getArgument(1));
                return (storedHash.equals(PASSWORD_HASH) && presented.equals("password"))
                    || (storedHash.equals(OTP_HASH) && presented.equals("246810"));
              });
      if (denied)
        assertDenial(
            () -> verifier.verify(account, secret), AuthenticationErrorCodes.INVALID_CREDENTIALS);
      else assertThat(verifier.verify(account, secret).accountId()).isEqualTo(ACCOUNT_ID);
      verify(crypto, times(2)).verify(anyString(), any(char[].class));
      verify(crypto).hash(eq(2), eq(65536), eq(1), any(char[].class));
      verify(crypto, times(3)).wipeArray(any(char[].class));
    }
  }

  @Test
  void absentAccountAlwaysPerformsTwoNonAuthorizingDecoyVerifications() {
    Argon2 crypto = mock(Argon2.class);
    try (var factory = mockStatic(Argon2Factory.class)) {
      factory.when(Argon2Factory::create).thenReturn(crypto);
      when(crypto.hash(eq(2), eq(65536), eq(1), any(char[].class)))
          .thenReturn("process-local-decoy");
      // Even a positive decoy comparison has no authentication result or state effects.
      when(crypto.verify(eq("process-local-decoy"), any(char[].class))).thenReturn(true);
      verifier.verifyAbsentAccountCredential("secret");
      verifier.verifyAbsentAccountCredential("other-secret");
      verify(crypto, times(4)).verify(eq("process-local-decoy"), any(char[].class));
      verify(crypto).hash(eq(2), eq(65536), eq(1), any(char[].class));
      verify(crypto, times(5)).wipeArray(any(char[].class));
      verifyNoInteractions(accounts, challenges);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"password", "246810"})
  void lockedIdentityDenialStillCompletesTwoKdfVerifications(String secret) {
    account.setLifecycleState(AccountLifecycleState.SECURITY_LOCKED);
    Argon2 crypto = mock(Argon2.class);
    try (var factory = mockStatic(Argon2Factory.class)) {
      factory.when(Argon2Factory::create).thenReturn(crypto);
      when(crypto.hash(eq(2), eq(65536), eq(1), any(char[].class)))
          .thenReturn("process-local-decoy");
      when(crypto.verify(anyString(), any(char[].class)))
          .thenAnswer(
              call -> {
                String storedHash = call.getArgument(0);
                return secret.equals("246810")
                    ? storedHash.equals(OTP_HASH)
                    : storedHash.equals(PASSWORD_HASH);
              });
      assertDenial(() -> verifier.verify(account, secret), AuthenticationErrorCodes.ACCOUNT_LOCKED);
      verify(crypto, times(2)).verify(anyString(), any(char[].class));
    }
  }

  @Test
  void passwordSuccessPreservesActiveOtpAndReturnsExactCanonicalIdentity() {
    assertThat(verifier.verify(account, "password"))
        .isEqualTo(new CredentialVerification(ACCOUNT_ID, null));
    verify(challenges).lockAccountChallenge(42L);
    verify(challenges, never()).delete(any());
    verify(challenges, never()).save(any());
  }

  @Test
  void otpSuccessDoesNotConsumeUntilOwnerRequestsIt() {
    var result = verifier.verify(account, "246810");
    assertThat(result).isEqualTo(new CredentialVerification(ACCOUNT_ID, 15L));
    verify(challenges, never()).delete(any());
    verifier.consumeOneTimeCredential(account, result);
    verify(challenges).delete(challenge);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PASSWORD", "EMAIL_OTP"})
  void disabledModeCannotAuthenticate(String enabledMode) {
    account.setLoginAuthModes(enabledMode);
    String disabledSecret = enabledMode.equals("PASSWORD") ? "246810" : "password";
    assertDenial(
        () -> verifier.verify(account, disabledSecret),
        AuthenticationErrorCodes.INVALID_CREDENTIALS);
    verify(challenges, never()).delete(any());
  }

  @Test
  void invalidOtpUpdatesExistingAttemptAccounting() {
    assertDenial(
        () -> verifier.verify(account, "wrong"), AuthenticationErrorCodes.INVALID_CREDENTIALS);
    assertThat(challenge.getInvalidAttemptCount()).isEqualTo(1);
    assertThat(challenge.getUpdatedAt()).isNotNull();
    verify(challenges).save(challenge);
    verify(challenges, never()).delete(any());
  }

  @Test
  void failedFinalAttemptDeletesExistingChallenge() {
    challenge.setInvalidAttemptCount(4);
    assertDenial(
        () -> verifier.verify(account, "wrong"), AuthenticationErrorCodes.INVALID_CREDENTIALS);
    assertThat(challenge.getInvalidAttemptCount()).isEqualTo(5);
    verify(challenges).delete(challenge);
  }

  @Test
  void expiryUsesExistingCleanupAndCannotAuthenticateOtp() {
    challenge.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    assertDenial(
        () -> verifier.verify(account, "246810"), AuthenticationErrorCodes.INVALID_CREDENTIALS);
    assertThat(challenge.getInvalidAttemptCount()).isEqualTo(1);
    verify(challenges).delete(challenge);
  }

  @Test
  void expiredChallengeStillAllowsPasswordUnderExistingAuthority() {
    challenge.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    assertThat(verifier.verify(account, "password"))
        .isEqualTo(new CredentialVerification(ACCOUNT_ID, null));
    verify(challenges).delete(challenge);
  }

  @ParameterizedTest
  @CsvSource({
    "SECURITY_LOCKED,AUTH_ACCOUNT_LOCKED",
    "DEACTIVATED_PENDING_DELETE,AUTH_INVALID_CREDENTIALS",
    "DELETED,AUTH_INVALID_CREDENTIALS"
  })
  void provenIdentityLifecycleDenialsKeepCanonicalCodes(AccountLifecycleState state, String code) {
    account.setLifecycleState(state);
    assertDenial(() -> verifier.verify(account, "password"), code);
    assertDenial(() -> verifier.verify(account, "246810"), code);
    verify(challenges, never()).delete(any());
  }

  @Test
  void invalidCredentialDoesNotDiscloseLockedAccount() {
    account.setLifecycleState(AccountLifecycleState.SECURITY_LOCKED);
    assertDenial(
        () -> verifier.verify(account, "wrong"), AuthenticationErrorCodes.INVALID_CREDENTIALS);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void verificationAndConsumptionRequireActualWritableTransaction(boolean readOnly) {
    TransactionSynchronizationManager.setActualTransactionActive(readOnly);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
    assertThatThrownBy(() -> verifier.verify(account, "password"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                verifier.consumeOneTimeCredential(
                    account, new CredentialVerification(ACCOUNT_ID, 15L)))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(challenges);
  }

  @Test
  void consumptionRejectsMismatchedAccountIdentityBeforeReadingChallenge() {
    assertThatThrownBy(
            () ->
                verifier.consumeOneTimeCredential(
                    account, new CredentialVerification(UUID.randomUUID(), 15L)))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(challenges);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void consumptionRequiresExactChallengeIdentityAndOwnership(boolean wrongOwner) {
    if (wrongOwner) challenge.setAccountId(43L);
    else challenge.setId(16L);
    assertThatThrownBy(
            () ->
                verifier.consumeOneTimeCredential(
                    account, new CredentialVerification(ACCOUNT_ID, 15L)))
        .isInstanceOf(IllegalStateException.class);
    verify(challenges, never()).delete(any());
  }

  @Test
  void consumptionRequiresChallengeStillPresent() {
    when(challenges.findByAccountId(42L)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                verifier.consumeOneTimeCredential(
                    account, new CredentialVerification(ACCOUNT_ID, 15L)))
        .isInstanceOf(IllegalStateException.class);
    verify(challenges, never()).delete(any());
  }

  @Test
  void passwordConsumptionTouchesNoChallengeState() {
    verifier.consumeOneTimeCredential(account, new CredentialVerification(ACCOUNT_ID, null));
    verifyNoInteractions(challenges);
  }

  @Test
  void missingPersistedCanonicalIdentityCannotAuthenticate() {
    account.setAccountUuidSourceNumericId(43L);
    assertThatThrownBy(() -> verifier.verify(account, "password"))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(challenges);
  }

  @Test
  void adapterIsUnregisteredAndAbsentAccountWorkCannotAuthenticateOrTouchState() {
    assertThat(
            AnnotatedElementUtils.hasAnnotation(
                AccountGameplayPrimaryCredentialVerifier.class, Component.class))
        .isFalse();
    verifier.verifyAbsentAccountCredential("password");
    verifier.verifyAbsentAccountCredential("246810");
    verifyNoInteractions(accounts, challenges);
  }

  @Test
  void realCanonicalOwnerAbsentAccountKeepsCredentialDenialWithoutIssuance() {
    OwnerFixture fixture = new OwnerFixture();
    when(accounts.findByEmail("player@example.com")).thenReturn(Optional.empty());
    assertDenial(
        () -> fixture.owner.authenticate(request("password"), WORKLOAD, verifier),
        AuthenticationErrorCodes.INVALID_CREDENTIALS);
    assertThat(transactions.events).containsExactly("begin", "rollback");
    verify(accounts, never()).findByIdForUpdate(any());
    verifyNoInteractions(challenges, fixture.commit, fixture.response);
    verify(fixture.issuance, never()).beginPendingForAccount(any(), any(), any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void realCanonicalOwnerCommitsInvalidAttemptOrExpiryEvidenceBeforeReturningDenial(
      boolean expired) {
    if (expired) challenge.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    OwnerFixture fixture = new OwnerFixture();
    assertDenial(
        () -> fixture.owner.authenticate(request("wrong"), WORKLOAD, verifier),
        AuthenticationErrorCodes.INVALID_CREDENTIALS);
    assertThat(transactions.events).containsExactly("begin", "commit");
    assertThat(challenge.getInvalidAttemptCount()).isEqualTo(1);
    if (expired) verify(challenges).delete(challenge);
    else verify(challenges).save(challenge);
    verify(fixture.issuance, never()).beginPendingForAccount(any(), any(), any(), any(), any());
    verifyNoInteractions(fixture.commit, fixture.response);
  }

  @Test
  void realCanonicalOwnerConsumesOnlyAfterNewPendingAndExactRetrySkipsConsumedOtp() {
    OwnerFixture fixture = new OwnerFixture();
    CanonicalGameplayLoginRequest request = request("246810");
    var binding =
        AccountGameplayCredentialRequestDigester.bind(
            fixture.key, request, account.getEmail(), WORKLOAD, ACCOUNT_ID);
    var identity =
        new AccountGameplayDelegationPendingIdentity(
            UUID.randomUUID(),
            REQUEST_ID,
            ACCOUNT_ID,
            WORKLOAD,
            CONTEXT_ID,
            binding,
            "a".repeat(64),
            UUID.randomUUID(),
            NOW.getEpochSecond(),
            NOW.getEpochSecond(),
            NOW.plusSeconds(60).getEpochSecond());
    GameplayLoginReplayReadback pending = mock(GameplayLoginReplayReadback.class);
    when(pending.created()).thenReturn(true);
    GameplayLoginReplayReadback replay = mock(GameplayLoginReplayReadback.class);
    when(replay.identity()).thenReturn(identity);
    when(replay.state()).thenReturn(GameplayLoginReplayState.PENDING);
    when(fixture.issuance.readCurrentGameplayLoginReplay(REQUEST_ID))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(replay));
    when(fixture.issuance.beginPendingForAccount(any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              verify(challenges, never()).delete(any());
              transactions.events.add("pending");
              return pending;
            });
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(transactions.events).containsExactly("begin", "pending");
              transactions.events.add("consume");
              when(challenges.findByAccountId(42L)).thenReturn(Optional.empty());
              return null;
            })
        .when(challenges)
        .delete(challenge);
    // Response recovery is outside this bounded credential proof. Simulate response loss after
    // SQL commit to prove a retry never verifies or consumes the already consumed challenge.
    IllegalStateException responseUnavailable =
        new IllegalStateException("response proof boundary");
    when(fixture.response.recoverInitialLoginResponse(any(), any(), any()))
        .thenThrow(responseUnavailable);
    assertThatThrownBy(() -> fixture.owner.authenticate(request, WORKLOAD, verifier))
        .isSameAs(responseUnavailable);
    assertThatThrownBy(() -> fixture.owner.authenticate(request, WORKLOAD, verifier))
        .isSameAs(responseUnavailable);
    assertThat(transactions.events)
        .containsExactly("begin", "pending", "consume", "commit", "begin", "commit");
    verify(challenges).delete(challenge);
    verify(challenges, times(2)).findByAccountId(42L);
    verify(fixture.issuance).beginPendingForAccount(any(), any(), any(), any(), any());
  }

  private final class OwnerFixture {
    private final AccountGameplayDelegationIssuanceRepository issuance =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    private final AccountGameplayCredentialRequestDigestKeySource keys =
        mock(AccountGameplayCredentialRequestDigestKeySource.class);
    private final AccountGameplayDelegationIssuanceCommitService commit =
        mock(AccountGameplayDelegationIssuanceCommitService.class);
    private final AccountGameplayDelegationResponseRecoveryOwner response =
        mock(AccountGameplayDelegationResponseRecoveryOwner.class);
    private final CredentialDigestKey key =
        new CredentialDigestKey(
            "key-v1", new SecretKeySpec(new byte[32], "HmacSHA256"), NOW.plusSeconds(600));
    private final AccountGameplayCanonicalLoginOwner owner =
        new AccountGameplayCanonicalLoginOwner(
            accounts,
            issuance,
            keys,
            commit,
            response,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private OwnerFixture() {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      when(accounts.findByEmail("player@example.com")).thenReturn(Optional.of(account));
      when(accounts.findByIdForUpdate(42L)).thenReturn(Optional.of(account));
      when(issuance.readCurrentGameplayLoginReplay(REQUEST_ID)).thenReturn(Optional.empty());
      when(keys.currentKey()).thenReturn(key);
      when(keys.requireKey("key-v1")).thenReturn(key);
    }
  }

  private static String hash(String secret) {
    var argon2 = Argon2Factory.create();
    char[] chars = secret.toCharArray();
    try {
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }

  private static CanonicalGameplayLoginRequest request(String secret) {
    return new CanonicalGameplayLoginRequest(
        REQUEST_ID,
        "player@example.com",
        secret,
        new GameplayCredentialSourceContext(CONTEXT_ID, "203.0.113.5", "TLS_TCP"));
  }

  private static void assertDenial(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOf(AuthenticationException.class)
        .satisfies(
            failure -> assertThat(((AuthenticationException) failure).getCode()).isEqualTo(code));
  }

  private static final class RecordingTransactions implements PlatformTransactionManager {
    private final List<String> events = new ArrayList<>();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      events.add("begin");
      TransactionSynchronizationManager.setActualTransactionActive(true);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      events.add("commit");
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Override
    public void rollback(TransactionStatus status) {
      events.add("rollback");
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
