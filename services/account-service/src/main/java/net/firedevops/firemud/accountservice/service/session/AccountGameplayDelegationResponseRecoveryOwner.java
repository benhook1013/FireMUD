package net.firedevops.firemud.accountservice.service.session;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveryPreflight;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.ResponseRecoveryExpiredException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly unwired Account owner for response recovery after initial gameplay LOGIN issuance.
 *
 * <p>The caller must already have authenticated the exact Game Session mTLS workload and must
 * confirm that the original pre-auth socket context is still open and current. The supplied UUID is
 * only compared with the immutable Account operation; it is not caller authentication. This owner
 * first performs a small caller/account precheck, then delegates the exact COMMITTED-to-ACTIVE
 * Redis transition to {@link AccountGameplayDelegationCommittedIssuanceOwner}, and only afterward
 * asks the SQL/crypto owner to read and decrypt the original response envelope. It neither signs
 * nor mints a token and does not establish Game Session context or a gameplay binding.
 */
public final class AccountGameplayDelegationResponseRecoveryOwner {
  private final AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopes;
  private final AccountGameplayDelegationCommittedIssuanceOwner committedIssuanceOwner;
  private final TransactionTemplate accountTransaction;

  public AccountGameplayDelegationResponseRecoveryOwner(
      AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopes,
      AccountGameplayDelegationCommittedIssuanceOwner committedIssuanceOwner,
      PlatformTransactionManager transactionManager) {
    this.responseEnvelopes =
        Objects.requireNonNull(
            responseEnvelopes, "Account response-envelope repository is required");
    this.committedIssuanceOwner =
        Objects.requireNonNull(
            committedIssuanceOwner, "Account committed-issuance owner is required");
    this.accountTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
  }

  /**
   * Returns the exact compact JWT from an already committed operation after activation and a fresh
   * owner read. The caller identity is a comparison input sourced from the authenticated Game
   * Session boundary; this owner does not authenticate a UUID or trust caller-supplied workload
   * text as proof of mTLS.
   */
  public RecoveredCredential recoverInitialLoginResponse(
      UUID requestId, UUID expectedAccountId, CallerIdentity caller) {
    requireRequest(requestId, expectedAccountId, caller);
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    try {
      RecoveryPreflight preflight =
          accountTransaction.execute(
              status ->
                  responseEnvelopes.preflightCommittedRecovery(
                      requestId, expectedAccountId, caller));
      if (preflight == null
          || !requestId.equals(preflight.requestId())
          || !expectedAccountId.equals(preflight.accountId())
          || !caller.workload().equals(preflight.callerWorkload())
          || !caller.contextId().equals(preflight.callerContextId())) {
        throw unavailable();
      }

      // This owner performs current signer/SQL checks around the pinned Redis CAS/readback and
      // WAITAOF. It deliberately runs without an Account SQL transaction held by this owner.
      AccountGameplayDelegationCommittedIssuanceOwner.ActiveCommittedIssuanceObservation active =
          committedIssuanceOwner.activateCommittedIssuance(requestId);
      if (active == null) throw unavailable();

      RecoveredCredential credential =
          accountTransaction.execute(
              status ->
                  responseEnvelopes.recoverCommittedResponse(
                      requestId, expectedAccountId, caller, active));
      if (credential == null) throw unavailable();
      return credential;
    } catch (IdempotencyConflictException | ResponseRecoveryExpiredException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static void requireRequest(
      UUID requestId, UUID expectedAccountId, CallerIdentity caller) {
    if (!isV4(requestId) || !isV4(expectedAccountId) || caller == null) throw unavailable();
  }

  private static boolean isV4(UUID value) {
    return value != null && value.version() == 4 && value.variant() == 2;
  }

  private static OwnerUnavailableException unavailable() {
    return new OwnerUnavailableException();
  }

  /** Retryable fail-closed result; no implementation cause or credential bytes are retained. */
  public static final class OwnerUnavailableException extends IllegalStateException {
    public OwnerUnavailableException() {
      super("Account gameplay delegation response recovery is unavailable");
    }

    public String errorCode() {
      return "AUTH_UNAVAILABLE";
    }
  }
}
