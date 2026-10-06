package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.GameplayCredentialSourceContext;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginResult;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBinding;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayReadback;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayState;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource.CredentialDigestKey;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigester;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mock-based composition-unit proof; this class does not claim PostgreSQL or Redis execution. */
class AccountGameplayCanonicalLoginOwnerTest {
  private static final String WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String OTHER_WORKLOAD = "spiffe://firemud/ns/other/sa/game-session-service";
  private static final long ACCOUNT_ROW_ID = 42L;
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final UUID CONTEXT_ID = UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436");
  private static final UUID OTHER_CONTEXT_ID =
      UUID.fromString("e46c59e8-4dd8-49a7-9d26-2db49f0d0ecb");
  private static final UUID OPERATION_ID = UUID.fromString("5414e55d-0393-4561-ac3d-cb916a08d3f0");
  private static final UUID TOKEN_JTI = UUID.fromString("2921ba03-bf74-49ac-b24b-a9255f5de308");
  private static final long OTP_CHALLENGE_ID = 15L;
  private static final Instant NOW = Instant.parse("2027-01-15T12:00:00Z");
  private static final String KEY_ID = "account-login-key-v1";
  private static final byte[] EXACT_JWT =
      "header.payload.signature".getBytes(StandardCharsets.US_ASCII);

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactRetryReturnsOriginalCredentialAndConsumesOtpOnlyInItsPendingTransaction() {
    Fixture fixture = new Fixture();
    CredentialDigestKey key = key(NOW.plusSeconds(86_400L));
    when(fixture.digestKeys.currentKey()).thenReturn(key);
    when(fixture.digestKeys.requireKey(KEY_ID)).thenReturn(key);
    Account account = account();
    when(fixture.accounts.findByEmail("player@example.com")).thenReturn(Optional.of(account));
    when(fixture.accounts.findByIdForUpdate(ACCOUNT_ROW_ID)).thenReturn(Optional.of(account));

    CanonicalGameplayLoginRequest request = request("246810");
    AccountGameplayCredentialRequestBinding binding =
        AccountGameplayCredentialRequestDigester.bind(
            key, request, "player@example.com", WORKLOAD, ACCOUNT_ID);
    GameplayLoginReplayReadback newlyPending = mock(GameplayLoginReplayReadback.class);
    when(newlyPending.created()).thenReturn(true);
    GameplayLoginReplayReadback exactRetry = replay(binding);
    when(fixture.issuance.readCurrentGameplayLoginReplay(REQUEST_ID))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(exactRetry));
    when(fixture.issuance.beginPendingForAccount(
            eq(REQUEST_ID), eq(ACCOUNT_ID), eq(WORKLOAD), eq(CONTEXT_ID), eq(binding)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              fixture.transactions.events.add("pending-operation");
              return newlyPending;
            });

    AccountGameplayCanonicalLoginOwner.CredentialVerifier verifier =
        new AccountGameplayCanonicalLoginOwner.CredentialVerifier() {
          @Override
          public AccountGameplayCanonicalLoginOwner.CredentialVerification verify(
              Account candidate, String credential) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(candidate).isSameAs(account);
            assertThat(credential).isEqualTo("246810");
            fixture.transactions.events.add("credential-verify");
            return new AccountGameplayCanonicalLoginOwner.CredentialVerification(
                ACCOUNT_ID, OTP_CHALLENGE_ID);
          }

          @Override
          public void consumeOneTimeCredential(
              Account candidate,
              AccountGameplayCanonicalLoginOwner.CredentialVerification verification) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(candidate).isSameAs(account);
            assertThat(verification.oneTimeChallengeId()).isEqualTo(OTP_CHALLENGE_ID);
            fixture.transactions.events.add("otp-consume");
          }
        };

    RecoveredCredential response = recoveredCredential();
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              fixture.transactions.events.add("sign-registry-commit");
              return null;
            })
        .when(fixture.commitOwner)
        .commitPendingCandidate(REQUEST_ID);
    when(fixture.responseOwner.recoverInitialLoginResponse(
            eq(REQUEST_ID), eq(ACCOUNT_ID), any(CallerIdentity.class)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              fixture.transactions.events.add("activate-recover-response");
              return response;
            });

    InitialGameplayLoginResult first = fixture.owner.authenticate(request, WORKLOAD, verifier);
    InitialGameplayLoginResult retry = fixture.owner.authenticate(request, WORKLOAD, verifier);

    assertThat(first.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(new String(first.compactJwtBytes(), StandardCharsets.US_ASCII))
        .isEqualTo("header.payload.signature");
    assertThat(retry.compactJwtBytes()).containsExactly(first.compactJwtBytes());
    assertThat(first.requestId()).isEqualTo(REQUEST_ID);
    assertThat(first.callerContextId()).isEqualTo(CONTEXT_ID);
    assertThat(first.authorityTupleCanonicalJson())
        .containsExactly(retry.authorityTupleCanonicalJson());
    assertThat(first.outboxCheckpointsCanonicalJson())
        .containsExactly(retry.outboxCheckpointsCanonicalJson());
    assertThat(first.outboxSourceEventEvidenceCanonicalJson())
        .containsExactly(retry.outboxSourceEventEvidenceCanonicalJson());
    assertThat(fixture.transactions.events)
        .containsSubsequence(
            "credential-verify",
            "pending-operation",
            "otp-consume",
            "sql-commit",
            "sign-registry-commit",
            "activate-recover-response",
            "sql-commit",
            "sign-registry-commit",
            "activate-recover-response");
    verify(fixture.issuance, times(1))
        .beginPendingForAccount(REQUEST_ID, ACCOUNT_ID, WORKLOAD, CONTEXT_ID, binding);
    verify(fixture.commitOwner, times(2)).commitPendingCandidate(REQUEST_ID);
    verify(fixture.responseOwner, times(2))
        .recoverInitialLoginResponse(eq(REQUEST_ID), eq(ACCOUNT_ID), any(CallerIdentity.class));
  }

  @Test
  void changedCredentialEmailContextAndCallerConflictBeforeCredentialVerification() {
    CanonicalGameplayLoginRequest original = request("original-secret");
    assertReplayConflict(request("changed-secret"), WORKLOAD);
    assertReplayConflict(
        new CanonicalGameplayLoginRequest(
            REQUEST_ID, "other@example.com", original.credential(), original.sourceContext()),
        WORKLOAD);
    assertReplayConflict(
        new CanonicalGameplayLoginRequest(
            REQUEST_ID,
            original.email(),
            original.credential(),
            new GameplayCredentialSourceContext(OTHER_CONTEXT_ID, "203.0.113.5", "TLS_TCP")),
        WORKLOAD);
    assertReplayConflict(
        new CanonicalGameplayLoginRequest(
            REQUEST_ID,
            original.email(),
            original.credential(),
            new GameplayCredentialSourceContext(CONTEXT_ID, "203.0.113.6", "TLS_TCP")),
        WORKLOAD);
    assertReplayConflict(original, OTHER_WORKLOAD);
  }

  @Test
  void credentialDenialCommitsOtpAttemptEvidenceWithoutCreatingAnIssuance() {
    Fixture fixture = new Fixture();
    CredentialDigestKey key = key(NOW.plusSeconds(86_400L));
    when(fixture.digestKeys.currentKey()).thenReturn(key);
    stubNewLoginAccount(fixture);
    when(fixture.issuance.readCurrentGameplayLoginReplay(REQUEST_ID))
        .thenReturn(Optional.empty(), Optional.empty());
    var verifier =
        new AccountGameplayCanonicalLoginOwner.CredentialVerifier() {
          @Override
          public AccountGameplayCanonicalLoginOwner.CredentialVerification verify(
              Account account, String credential) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            fixture.transactions.events.add("otp-attempt-increment");
            throw new AuthenticationException(
                AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials");
          }

          @Override
          public void consumeOneTimeCredential(
              Account account,
              AccountGameplayCanonicalLoginOwner.CredentialVerification verification) {
            throw new AssertionError("Denied credentials must not consume a challenge");
          }
        };

    assertThatThrownBy(() -> fixture.owner.authenticate(request("wrong-code"), WORKLOAD, verifier))
        .isInstanceOf(AuthenticationException.class)
        .hasMessage("Invalid credentials");

    assertThat(fixture.transactions.events)
        .containsSubsequence("otp-attempt-increment", "sql-commit")
        .doesNotContain("sql-rollback");
    verify(fixture.issuance, never()).beginPendingForAccount(any(), any(), any(), any(), any());
    verifyNoInteractions(fixture.commitOwner, fixture.responseOwner);
  }

  @Test
  void nonCredentialAuthenticationDenialsRollBackWithoutIssuanceOrCredentialRelease() {
    assertNonCredentialAuthenticationFailureRollsBack(AuthenticationErrorCodes.ACCOUNT_LOCKED);
    assertNonCredentialAuthenticationFailureRollsBack(AuthenticationErrorCodes.UNAVAILABLE);
  }

  @Test
  void invalidCallerAndUnavailableDigestKeysFailBeforeCredentialOwner() {
    Fixture invalidCaller = new Fixture();
    var verifier = mock(AccountGameplayCanonicalLoginOwner.CredentialVerifier.class);
    assertThatThrownBy(() -> invalidCaller.owner.authenticate(request("secret"), "", verifier))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(invalidCaller.issuance, invalidCaller.accounts, verifier);

    Fixture missingKey = new Fixture();
    stubNewLoginAccount(missingKey);
    when(missingKey.issuance.readCurrentGameplayLoginReplay(REQUEST_ID))
        .thenReturn(Optional.empty(), Optional.empty());
    when(missingKey.digestKeys.currentKey()).thenReturn(null);
    assertUnavailableBeforeCredentialVerification(missingKey, verifier);

    Fixture shortRetention = new Fixture();
    stubNewLoginAccount(shortRetention);
    when(shortRetention.issuance.readCurrentGameplayLoginReplay(REQUEST_ID))
        .thenReturn(Optional.empty(), Optional.empty());
    when(shortRetention.digestKeys.currentKey()).thenReturn(key(NOW.plusSeconds(1L)));
    assertUnavailableBeforeCredentialVerification(shortRetention, verifier);

    Fixture missingReplayKey = replayFixture();
    when(missingReplayKey.digestKeys.requireKey(KEY_ID)).thenReturn(null);
    assertUnavailableReplayKey(missingReplayKey, verifier);

    Fixture shortReplayKey = replayFixture();
    when(shortReplayKey.digestKeys.requireKey(KEY_ID)).thenReturn(key(NOW.plusSeconds(1L)));
    assertUnavailableReplayKey(shortReplayKey, verifier);
  }

  @Test
  void commitOrRecoveryFailureNeverReturnsTheCredential() {
    Fixture commitFailure = replayFixture();
    doThrow(new IllegalStateException("private signer or registry detail"))
        .when(commitFailure.commitOwner)
        .commitPendingCandidate(REQUEST_ID);
    assertThatThrownBy(
            () ->
                commitFailure.owner.authenticate(
                    request("original-secret"), WORKLOAD, unusedVerifier()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("private signer or registry detail");
    verify(commitFailure.responseOwner, never()).recoverInitialLoginResponse(any(), any(), any());

    Fixture recoveryFailure = replayFixture();
    doThrow(new IllegalStateException("private recovery detail"))
        .when(recoveryFailure.responseOwner)
        .recoverInitialLoginResponse(any(), any(), any());
    assertThatThrownBy(
            () ->
                recoveryFailure.owner.authenticate(
                    request("original-secret"), WORKLOAD, unusedVerifier()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("private recovery detail");
    verify(recoveryFailure.commitOwner).commitPendingCandidate(REQUEST_ID);
    verify(recoveryFailure.responseOwner)
        .recoverInitialLoginResponse(eq(REQUEST_ID), eq(ACCOUNT_ID), any(CallerIdentity.class));
  }

  private static void assertReplayConflict(
      CanonicalGameplayLoginRequest candidate, String callerWorkload) {
    Fixture fixture = replayFixture();
    assertThatThrownBy(
            () -> fixture.owner.authenticate(candidate, callerWorkload, unusedVerifier()))
        .isInstanceOf(IdempotencyConflictException.class);
    verify(fixture.commitOwner, never()).commitPendingCandidate(any());
    verifyNoInteractions(fixture.responseOwner, fixture.accounts);
  }

  private static void assertNonCredentialAuthenticationFailureRollsBack(String errorCode) {
    Fixture fixture = new Fixture();
    when(fixture.digestKeys.currentKey()).thenReturn(key(NOW.plusSeconds(86_400L)));
    stubNewLoginAccount(fixture);
    when(fixture.issuance.readCurrentGameplayLoginReplay(REQUEST_ID))
        .thenReturn(Optional.empty(), Optional.empty());
    var verifier =
        new AccountGameplayCanonicalLoginOwner.CredentialVerifier() {
          @Override
          public AccountGameplayCanonicalLoginOwner.CredentialVerification verify(
              Account account, String credential) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            fixture.transactions.events.add("non-credential-denial");
            throw new AuthenticationException(errorCode, "Private authentication detail");
          }

          @Override
          public void consumeOneTimeCredential(
              Account account,
              AccountGameplayCanonicalLoginOwner.CredentialVerification verification) {
            throw new AssertionError("Denied authentication must not consume a challenge");
          }
        };

    AuthenticationException observed =
        org.junit.jupiter.api.Assertions.assertThrows(
            AuthenticationException.class,
            () -> fixture.owner.authenticate(request("secret"), WORKLOAD, verifier));
    assertThat(observed).hasMessage("Private authentication detail");
    assertThat(observed.getCode()).isEqualTo(errorCode);

    assertThat(fixture.transactions.events)
        .containsSubsequence("non-credential-denial", "sql-rollback")
        .doesNotContain("sql-commit");
    verify(fixture.issuance, never()).beginPendingForAccount(any(), any(), any(), any(), any());
    verifyNoInteractions(fixture.commitOwner, fixture.responseOwner);
  }

  private static void assertUnavailableBeforeCredentialVerification(
      Fixture fixture, AccountGameplayCanonicalLoginOwner.CredentialVerifier verifier) {
    assertThatThrownBy(() -> fixture.owner.authenticate(request("secret"), WORKLOAD, verifier))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account gameplay LOGIN is unavailable");
    verify(verifier, never()).verify(any(), any());
    verify(verifier, never()).consumeOneTimeCredential(any(), any());
    verify(fixture.issuance, never()).beginPendingForAccount(any(), any(), any(), any(), any());
    verify(fixture.commitOwner, never()).commitPendingCandidate(any());
    verifyNoInteractions(fixture.responseOwner);
  }

  private static void assertUnavailableReplayKey(
      Fixture fixture, AccountGameplayCanonicalLoginOwner.CredentialVerifier verifier) {
    assertThatThrownBy(
            () -> fixture.owner.authenticate(request("original-secret"), WORKLOAD, verifier))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account gameplay LOGIN is unavailable");
    verify(verifier, never()).verify(any(), any());
    verify(verifier, never()).consumeOneTimeCredential(any(), any());
    verify(fixture.commitOwner, never()).commitPendingCandidate(any());
    verifyNoInteractions(fixture.responseOwner, fixture.accounts);
  }

  private static Fixture replayFixture() {
    Fixture fixture = new Fixture();
    CredentialDigestKey key = key(NOW.plusSeconds(86_400L));
    when(fixture.digestKeys.requireKey(KEY_ID)).thenReturn(key);
    CanonicalGameplayLoginRequest request = request("original-secret");
    AccountGameplayCredentialRequestBinding binding =
        AccountGameplayCredentialRequestDigester.bind(
            key, request, "player@example.com", WORKLOAD, ACCOUNT_ID);
    GameplayLoginReplayReadback replay = replay(binding);
    when(fixture.issuance.readCurrentGameplayLoginReplay(REQUEST_ID))
        .thenReturn(Optional.of(replay));
    return fixture;
  }

  private static GameplayLoginReplayReadback replay(
      AccountGameplayCredentialRequestBinding binding) {
    GameplayLoginReplayReadback replay = mock(GameplayLoginReplayReadback.class);
    when(replay.identity())
        .thenReturn(
            new AccountGameplayDelegationPendingIdentity(
                OPERATION_ID,
                REQUEST_ID,
                ACCOUNT_ID,
                WORKLOAD,
                CONTEXT_ID,
                binding,
                "a".repeat(64),
                TOKEN_JTI,
                NOW.getEpochSecond(),
                NOW.getEpochSecond(),
                NOW.getEpochSecond()
                    + net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile
                        .MAX_TOKEN_LIFETIME_SECONDS));
    when(replay.state()).thenReturn(GameplayLoginReplayState.PENDING);
    return replay;
  }

  private static void stubNewLoginAccount(Fixture fixture) {
    Account account = account();
    when(fixture.accounts.findByEmail("player@example.com")).thenReturn(Optional.of(account));
    when(fixture.accounts.findByIdForUpdate(ACCOUNT_ROW_ID)).thenReturn(Optional.of(account));
  }

  private static Account account() {
    Account account = new Account();
    account.setId(ACCOUNT_ROW_ID);
    account.setAccountUuid(ACCOUNT_ID);
    account.setEmail("player@example.com");
    return account;
  }

  private static RecoveredCredential recoveredCredential() {
    RecoveredCredential response = mock(RecoveredCredential.class);
    when(response.compactJwtBytes()).thenReturn(EXACT_JWT.clone());
    when(response.accountId()).thenReturn(ACCOUNT_ID);
    when(response.tokenJti()).thenReturn(TOKEN_JTI);
    when(response.tokenSha256()).thenReturn("a".repeat(64));
    when(response.profile()).thenReturn(GameSessionAccountDelegationProfile.PROFILE);
    when(response.expiresAtEpochSecond())
        .thenReturn(
            NOW.getEpochSecond()
                + net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile
                    .MAX_TOKEN_LIFETIME_SECONDS);
    when(response.authEvidenceBundle()).thenReturn(loginEvidenceBundle());
    return response;
  }

  private static AccountAuthEvidenceBundle loginEvidenceBundle() {
    String accountStream = "account:auth-authority:v1:account/" + ACCOUNT_ID;
    String issuerStream = "account:auth-authority:v1:issuer/firemud-account-service";
    return AccountAuthEvidenceBundle.fromOwnerEvaluation(
        new AccountAuthEvidenceBundle.OwnerEvaluation(
            new AccountAuthEvidenceBundle.BundleReference("1", "1", "1", "12345678"),
            "b".repeat(64),
            "c".repeat(64),
            ACCOUNT_ID,
            new AccountAuthEvidenceBundle.OperationIdentity(
                OPERATION_ID, REQUEST_ID, "d".repeat(64), WORKLOAD, CONTEXT_ID, ACCOUNT_ID),
            new AccountAuthEvidenceBundle.TokenIdentity(
                TOKEN_JTI,
                1L,
                NOW.getEpochSecond(),
                NOW.getEpochSecond(),
                NOW.getEpochSecond()
                    + GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS),
            GameSessionAccountDelegationProfile.authorityTuple(1L, 1L),
            1L,
            new AccountAuthEvidenceBundle.AuthoritySourceVersions(1L, 1L, 1L),
            new AccountAuthEvidenceBundle.AccountIdentitySource(
                42L, AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT, 42L),
            List.of(
                new AccountAuthEvidenceBundle.OutboxCheckpoint(accountStream, 0L, null, null),
                new AccountAuthEvidenceBundle.OutboxCheckpoint(
                    issuerStream, 1L, "issuer-event-1", "sha256:" + "e".repeat(64)))));
  }

  private static CanonicalGameplayLoginRequest request(String credential) {
    return new CanonicalGameplayLoginRequest(
        REQUEST_ID,
        "Player@Example.com",
        credential,
        new GameplayCredentialSourceContext(CONTEXT_ID, "203.0.113.5", "TLS_TCP"));
  }

  private static CredentialDigestKey key(Instant retainedUntil) {
    return new CredentialDigestKey(
        KEY_ID, new SecretKeySpec(new byte[32], "HmacSHA256"), retainedUntil);
  }

  private static AccountGameplayCanonicalLoginOwner.CredentialVerifier unusedVerifier() {
    return new AccountGameplayCanonicalLoginOwner.CredentialVerifier() {
      @Override
      public AccountGameplayCanonicalLoginOwner.CredentialVerification verify(
          Account account, String presentedCredential) {
        throw new AssertionError("Credential owner must not run for a denied replay");
      }

      @Override
      public void consumeOneTimeCredential(
          Account account, AccountGameplayCanonicalLoginOwner.CredentialVerification verification) {
        throw new AssertionError("Credential owner must not consume a denied replay");
      }
    };
  }

  private static final class Fixture {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountGameplayDelegationIssuanceRepository issuance =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    private final AccountGameplayCredentialRequestDigestKeySource digestKeys =
        mock(AccountGameplayCredentialRequestDigestKeySource.class);
    private final AccountGameplayDelegationIssuanceCommitService commitOwner =
        mock(AccountGameplayDelegationIssuanceCommitService.class);
    private final AccountGameplayDelegationResponseRecoveryOwner responseOwner =
        mock(AccountGameplayDelegationResponseRecoveryOwner.class);
    private final RecordingTransactionManager transactions = new RecordingTransactionManager();
    private final AccountGameplayCanonicalLoginOwner owner =
        new AccountGameplayCanonicalLoginOwner(
            accounts,
            issuance,
            digestKeys,
            commitOwner,
            responseOwner,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final List<String> events = new ArrayList<>();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        throw new IllegalStateException("Unexpected ambient Account transaction");
      }
      events.add("sql-begin");
      TransactionSynchronizationManager.setActualTransactionActive(true);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      events.add("sql-commit");
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Override
    public void rollback(TransactionStatus status) {
      events.add("sql-rollback");
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
