package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.PendingCandidateCredential;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.SealedCandidateObservation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account-local orchestration for sealing one exact PENDING delegation candidate.
 *
 * <p>This service does not authenticate the supplied caller, invoke signing, reconcile a registry,
 * transition lifecycle state, decrypt an envelope, or return a credential. Its result is only a
 * non-authorizing metadata observation for the owning issuance workflow.
 */
@Service
public class AccountGameplayDelegationResponseEnvelopeService {
  private final AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopes;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Spring transaction proxies require an extensible service; this constructor retains only"
              + " a repository reference, with no sensitive or native partially initialized"
              + " resources or finalizer")
  public AccountGameplayDelegationResponseEnvelopeService(
      AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopes) {
    this.responseEnvelopes =
        Objects.requireNonNull(
            responseEnvelopes, "Account response-envelope repository is required");
  }

  /** Persists the exact candidate only when every immutable owner identity still matches. */
  @Transactional(propagation = Propagation.MANDATORY)
  public SealedCandidateObservation sealPendingCandidate(
      UUID requestId, CallerIdentity caller, String exactCompactJwt) {
    return responseEnvelopes.sealPendingCandidate(requestId, caller, exactCompactJwt);
  }

  /** Opens exact durable PENDING bytes only for the internal signer verification retry. */
  @Transactional(propagation = Propagation.MANDATORY)
  public PendingCandidateCredential openPendingCandidate(UUID requestId, CallerIdentity caller) {
    return responseEnvelopes.openPendingCandidate(requestId, caller);
  }
}
