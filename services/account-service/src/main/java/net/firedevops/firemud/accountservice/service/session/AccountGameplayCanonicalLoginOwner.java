package net.firedevops.firemud.accountservice.service.session;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginResult;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBinding;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayReadback;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayState;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource.CredentialDigestKey;
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit Account owner for request-bound gameplay LOGIN, activated only by its protected
 * default-inactive Spring composition.
 *
 * <p>Credential verification and optional one-time challenge consumption occur in the same SQL
 * transaction as the immutable keyed PENDING operation. Signing, registry writes, commit, response
 * recovery, and credential release run only after that transaction closes, through their existing
 * owner boundaries. Construction requires the protected key source and complete issuance owners;
 * {@link net.firedevops.firemud.accountservice.config.AccountCanonicalGameplayLoginConfiguration}
 * supplies the explicit, default-inactive runtime composition.
 */
public final class AccountGameplayCanonicalLoginOwner {
  private final AccountRepository accounts;
  private final AccountGameplayDelegationIssuanceRepository issuance;
  private final AccountGameplayCredentialRequestDigestKeySource digestKeys;
  private final AccountGameplayDelegationIssuanceCommitService commitOwner;
  private final AccountGameplayDelegationResponseRecoveryOwner responseOwner;
  private final TransactionTemplate accountTransaction;
  private final Clock clock;

  public AccountGameplayCanonicalLoginOwner(
      AccountRepository accounts,
      AccountGameplayDelegationIssuanceRepository issuance,
      AccountGameplayCredentialRequestDigestKeySource digestKeys,
      AccountGameplayDelegationIssuanceCommitService commitOwner,
      AccountGameplayDelegationResponseRecoveryOwner responseOwner,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.accounts = Objects.requireNonNull(accounts, "Account repository is required");
    this.issuance = Objects.requireNonNull(issuance, "Account issuance owner is required");
    this.digestKeys = Objects.requireNonNull(digestKeys, "Account digest key source is required");
    this.commitOwner = Objects.requireNonNull(commitOwner, "Account commit owner is required");
    this.responseOwner =
        Objects.requireNonNull(responseOwner, "Account response owner is required");
    this.accountTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
    this.clock = Objects.requireNonNull(clock, "Clock is required");
  }

  /**
   * Authenticates, prepares or replays one exact operation, then returns its verified credential
   * and immutable, non-authorizing Account evidence bundle.
   */
  public InitialGameplayLoginResult authenticate(
      CanonicalGameplayLoginRequest request,
      String verifiedGameSessionWorkload,
      CredentialVerifier credentialVerifier) {
    requireRequest(request, verifiedGameSessionWorkload, credentialVerifier);
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();

    String normalizedEmail = EmailCanonicalization.normalize(request.email());
    LoginPreparation preparation =
        accountTransaction.execute(
            status ->
                prepareOrReplay(
                    request, normalizedEmail, verifiedGameSessionWorkload, credentialVerifier));
    if (preparation == null) throw unavailable();
    if (preparation.authenticationFailure() != null) {
      throw preparation.authenticationFailure();
    }
    UUID accountId = preparation.accountId();
    if (accountId == null) throw unavailable();

    // These existing owners close the signing -> durable commit -> activation -> decryption path.
    // None is invoked while an Account SQL transaction or Account authority lock remains held.
    commitOwner.commitPendingCandidate(request.requestId());
    RecoveredCredential recovered =
        responseOwner.recoverInitialLoginResponse(
            request.requestId(),
            accountId,
            new CallerIdentity(verifiedGameSessionWorkload, request.sourceContext().contextId()));
    return InitialGameplayLoginResult.fromRecoveredCredential(recovered);
  }

  private LoginPreparation prepareOrReplay(
      CanonicalGameplayLoginRequest request,
      String normalizedEmail,
      String callerWorkload,
      CredentialVerifier credentialVerifier) {
    Optional<GameplayLoginReplayReadback> existing =
        issuance.readCurrentGameplayLoginReplay(request.requestId());
    if (existing.isPresent()) {
      return LoginPreparation.authenticated(
          requireExactReplay(request, normalizedEmail, callerWorkload, existing.orElseThrow()));
    }

    Account found =
        accounts
            .findByEmail(normalizedEmail)
            .orElseThrow(AccountGameplayCanonicalLoginOwner::invalidCredentials);
    Account account =
        accounts
            .findByIdForUpdate(found.getId())
            .orElseThrow(AccountGameplayCanonicalLoginOwner::invalidCredentials);
    if (account.getAccountUuid() == null
        || !normalizedEmail.equals(EmailCanonicalization.normalize(account.getEmail()))) {
      throw invalidCredentials();
    }

    // Account row serialization ensures a concurrent exact request can win and consume an OTP
    // before this invocation verifies it. Re-read the idempotency owner after obtaining that lock.
    existing = issuance.readCurrentGameplayLoginReplay(request.requestId());
    if (existing.isPresent()) {
      return LoginPreparation.authenticated(
          requireExactReplay(request, normalizedEmail, callerWorkload, existing.orElseThrow()));
    }

    long now = Math.floorDiv(clock.millis(), 1_000L);
    long requiredExpiry;
    try {
      requiredExpiry =
          Math.addExact(now, GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS + 2L);
    } catch (ArithmeticException ex) {
      throw unavailable();
    }
    CredentialDigestKey key = digestKeys.currentKey();
    requireKeyRetainedThrough(key, Instant.ofEpochSecond(requiredExpiry));
    AccountGameplayCredentialRequestBinding binding =
        AccountGameplayCredentialRequestDigester.bind(
            key, request, normalizedEmail, callerWorkload, account.getAccountUuid());

    CredentialVerification credential;
    try {
      credential =
          Objects.requireNonNull(
              credentialVerifier.verify(account, request.credential()),
              "Account credential verification result is required");
    } catch (AuthenticationException denial) {
      if (!AuthenticationErrorCodes.INVALID_CREDENTIALS.equals(denial.getCode())) {
        throw denial;
      }
      // Invalid credentials may have durably incremented or expired an OTP challenge. Return
      // this denial from the callback so the owner transaction commits that evidence, then rethrow
      // only after TransactionTemplate completes. No issuance intent exists yet.
      return LoginPreparation.denied(denial);
    }
    if (!account.getAccountUuid().equals(credential.accountId())) throw unavailable();

    GameplayLoginReplayReadback pending =
        issuance.beginPendingForAccount(
            request.requestId(),
            account.getAccountUuid(),
            callerWorkload,
            request.sourceContext().contextId(),
            binding);
    if (pending.created() && credential.oneTimeChallengeId() != null) {
      credentialVerifier.consumeOneTimeCredential(account, credential);
    }
    return LoginPreparation.authenticated(account.getAccountUuid());
  }

  private record LoginPreparation(UUID accountId, AuthenticationException authenticationFailure) {
    private LoginPreparation {
      if ((accountId == null) == (authenticationFailure == null)) {
        throw unavailable();
      }
    }

    private static LoginPreparation authenticated(UUID accountId) {
      return new LoginPreparation(Objects.requireNonNull(accountId), null);
    }

    private static LoginPreparation denied(AuthenticationException failure) {
      return new LoginPreparation(null, Objects.requireNonNull(failure));
    }
  }

  private UUID requireExactReplay(
      CanonicalGameplayLoginRequest request,
      String normalizedEmail,
      String callerWorkload,
      GameplayLoginReplayReadback replay) {
    var identity = replay.identity();
    if (!identity.callerWorkload().equals(callerWorkload)
        || !identity.callerContextId().equals(request.sourceContext().contextId())) {
      throw conflict();
    }
    CredentialDigestKey key =
        digestKeys.requireKey(identity.credentialRequestBinding().digestKeyId());
    if (key == null || !identity.credentialRequestBinding().digestKeyId().equals(key.keyId())) {
      throw unavailable();
    }
    requireKeyRetainedThrough(key, Instant.ofEpochSecond(identity.expiresAtEpochSecond()));
    AccountGameplayCredentialRequestBinding candidate =
        AccountGameplayCredentialRequestDigester.bind(
            key, request, normalizedEmail, callerWorkload, identity.accountId());
    if (!AccountGameplayCredentialRequestDigester.matches(
        identity.credentialRequestBinding(), candidate)) {
      throw conflict();
    }
    if (replay.state() != GameplayLoginReplayState.PENDING
        && replay.state() != GameplayLoginReplayState.COMMITTED) {
      throw unavailable();
    }
    return identity.accountId();
  }

  private static void requireKeyRetainedThrough(CredentialDigestKey key, Instant horizon) {
    if (key == null || key.retainedUntil().isBefore(horizon)) throw unavailable();
  }

  private static void requireRequest(
      CanonicalGameplayLoginRequest request, String workload, CredentialVerifier verifier) {
    Objects.requireNonNull(request, "Canonical gameplay LOGIN request is required");
    Objects.requireNonNull(verifier, "Account credential verifier is required");
    if (workload == null
        || !workload.matches(
            "^spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/game-session-service$")) {
      throw new IllegalArgumentException("Verified Game Session workload identity is required");
    }
  }

  private static AuthenticationException invalidCredentials() {
    return new AuthenticationException(
        AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials");
  }

  private static IdempotencyConflictException conflict() {
    return new IdempotencyConflictException();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Account gameplay LOGIN is unavailable");
  }

  /** Account's existing password/OTP authority; no credential value is retained in its result. */
  public interface CredentialVerifier {
    CredentialVerification verify(Account account, String presentedCredential);

    void consumeOneTimeCredential(Account account, CredentialVerification verification);
  }

  public record CredentialVerification(UUID accountId, Long oneTimeChallengeId) {
    public CredentialVerification {
      Objects.requireNonNull(accountId, "Verified Account identity is required");
      if (oneTimeChallengeId != null && oneTimeChallengeId <= 0L) {
        throw new IllegalArgumentException("One-time challenge identity is malformed");
      }
    }

    @Override
    public String toString() {
      return "CredentialVerification[redacted]";
    }
  }
}
