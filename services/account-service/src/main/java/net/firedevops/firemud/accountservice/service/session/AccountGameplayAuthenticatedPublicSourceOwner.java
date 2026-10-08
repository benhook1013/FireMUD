package net.firedevops.firemud.accountservice.service.session;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayPublicAdmissionSourceSnapshot;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.service.AccountGameplayPublicAdmissionSourceReader;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered, non-admitting composition of initial authentication and current Account sources.
 *
 * <p>JWT/JWKS and committed SQL/Redis authentication finish before this owner opens its SQL source
 * transaction. Only the real Account source reader runs inside that transaction. Its captured
 * sources are valid at that transaction alone; every later commit must revalidate. This does not
 * supply platform_access_ban, World, restriction, deadline, lease, or physical commit proof. The
 * absent platform_access_ban source keeps gameplay admission denied.
 */
public final class AccountGameplayAuthenticatedPublicSourceOwner {
  private final AccountGameplayAdmissionInitialTokenAuthenticator authenticator;
  private final AccountGameplayPublicAdmissionSourceReader sources;
  private final TransactionTemplate sourceTransaction;
  private final Clock clock;

  /** The timeout is trusted operational SQL configuration, never an authorization deadline. */
  public AccountGameplayAuthenticatedPublicSourceOwner(
      AccountGameplayAdmissionInitialTokenAuthenticator authenticator,
      AccountGameplayPublicAdmissionSourceReader sources,
      PlatformTransactionManager transactionManager,
      Clock clock,
      int sourceReadTimeoutSeconds) {
    this.authenticator = Objects.requireNonNull(authenticator);
    this.sources = Objects.requireNonNull(sources);
    this.clock = Objects.requireNonNull(clock);
    if (sourceReadTimeoutSeconds <= 0) {
      throw new IllegalArgumentException("Positive Account source transaction timeout is required");
    }
    sourceTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    sourceTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    sourceTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    sourceTransaction.setReadOnly(false);
    sourceTransaction.setTimeout(sourceReadTimeoutSeconds);
  }

  /** Target tenant is only a subject-bound owner lookup selector, never caller authority. */
  public CurrentPublicSourceObservation observeCurrent(String compactJwt, UUID tenantId) {
    try {
      requireNoAmbientTransaction();
      if (tenantId == null || tenantId.version() != 4 || tenantId.variant() != 2) throw denied();
      var authenticated = authenticator.authenticateInitialToken(compactJwt);
      var candidate = Objects.requireNonNull(authenticated).candidate();
      var pending = Objects.requireNonNull(candidate).identity();
      var authority = candidate.authoritySnapshot();
      if (!pending.accountId().equals(authority.accountId())) throw denied();
      TokenIdentity identity =
          new TokenIdentity(
              pending.accountId(),
              pending.operationId(),
              pending.requestId(),
              candidate.tokenSha256(),
              pending.tokenJti(),
              pending.notBeforeEpochSecond(),
              1L,
              authority.issuanceFence());
      requireTokenTime(candidate);
      requireNoAmbientTransaction();
      CurrentPublicSourceObservation result =
          sourceTransaction.execute(
              status -> {
                requireSourceTransaction();
                requireTokenTime(candidate);
                var snapshot = sources.readCurrent(identity.accountId(), tenantId, identity);
                requireMatchingSources(candidate, tenantId, identity, snapshot);
                long capturedAt = requireTokenTime(candidate);
                return new CurrentPublicSourceObservation(
                    identity,
                    pending.issuedAtEpochSecond(),
                    pending.expiresAtEpochSecond(),
                    snapshot,
                    capturedAt);
              });
      // A delayed transaction completion must not return an already-expired observation.
      requireTokenTime(candidate);
      return Objects.requireNonNull(result);
    } catch (RuntimeException failure) {
      throw denied();
    }
  }

  private static void requireMatchingSources(
      CommittedCandidateVerificationData candidate,
      UUID tenantId,
      TokenIdentity identity,
      AccountGameplayPublicAdmissionSourceSnapshot snapshot) {
    var membership = Objects.requireNonNull(snapshot).membershipSource();
    var member = membership.membership();
    var current = membership.currentAuthority();
    var ownerSources = membership.issuerAccountSources();
    var signed = candidate.authoritySnapshot();
    var issuerScope = AuthorityScope.issuer(GameSessionAccountDelegationProfile.ISSUER);
    var accountScope = AuthorityScope.account(identity.accountId());
    if (!identity.accountId().equals(member.accountId())
        || !tenantId.equals(member.tenantId())
        || snapshot.accountLifecycleState() != AccountLifecycleState.ACTIVE
        || !issuerScope.equals(ownerSources.issuer().scope())
        || !accountScope.equals(ownerSources.account().scope())
        || !issuerScope.equals(current.issuer().scope())
        || !accountScope.equals(current.account().scope())
        || current.issuer().generation() != signed.issuerGeneration()
        || current.account().generation() != signed.accountGeneration()
        || ownerSources.issuer().generation() != signed.issuerGeneration()
        || ownerSources.account().generation() != signed.accountGeneration()
        || !ownerSources.account().accountSecurityCutoff().equals(signed.accountSecurityCutoff())
        || !identity.accountId().equals(current.issuanceFence().accountId())
        || current.issuanceFence().value() != identity.issuanceFence()
        || !current.issuanceFence().equals(ownerSources.issuanceFence())
        || !current.issuanceFence().equals(ownerSources.account().issuanceFence())
        || !identity.equals(snapshot.tokenIdentityFence().identity())
        || snapshot.tokenIdentityFence().state() != State.ACTIVE
        || snapshot.tokenIdentityFence().tokenIdentityFence() != 1L) {
      throw denied();
    }
    // The initial JWT's empty membershipVersion is not a target membership baseline. Preserve
    // the reader's current membership, tenant/billing and source-event evidence unchanged.
  }

  private long requireTokenTime(CommittedCandidateVerificationData candidate) {
    var identity = candidate.identity();
    long now = clock.instant().getEpochSecond();
    if (now < identity.issuedAtEpochSecond()
        || now < identity.notBeforeEpochSecond()
        || now >= identity.expiresAtEpochSecond()) throw denied();
    return now;
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw denied();
  }

  private static void requireSourceTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_SERIALIZABLE)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw denied();
    }
  }

  private static PublicSourceObservationException denied() {
    return new PublicSourceObservationException();
  }

  public static final class PublicSourceObservationException extends SecurityException {
    private PublicSourceObservationException() {
      super("Current Account public source observation failed");
    }
  }

  /** Immutable captured evidence; cannot authorize admission, minting, or a later SQL commit. */
  public record CurrentPublicSourceObservation(
      TokenIdentity tokenIdentity,
      long tokenIssuedAtEpochSecond,
      long tokenExpiresAtEpochSecond,
      AccountGameplayPublicAdmissionSourceSnapshot sourceSnapshot,
      long capturedAtEpochSecond) {
    public CurrentPublicSourceObservation {
      Objects.requireNonNull(tokenIdentity);
      Objects.requireNonNull(sourceSnapshot);
    }

    @Override
    public String toString() {
      return "CurrentPublicSourceObservation[non-authorizing, redacted]";
    }
  }
}
