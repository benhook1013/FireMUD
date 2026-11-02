package net.firedevops.firemud.accountservice.service.realm;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.accountservice.client.GameSessionCurrentPrivatePlaytestLifecycleReadClient;
import net.firedevops.firemud.accountservice.client.GameSessionCurrentPrivatePlaytestLifecycleReadClient.Verified;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository.CurrentGrant;
import net.firedevops.firemud.common.account.authority.GrantAuthorityScopeCodec.Scope;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account-owned, current-lifecycle-qualified grant read.
 *
 * <p>The actual protected composing workflow owns caller authorization. Game Session source is
 * obtained outside SQL; Account then reads its own exact five-field grant in a separate bounded
 * transaction. This is a truthful as-of source-plus-grant observation. Game Session may select a
 * replacement afterward; this read provides no cross-owner hold, mutation-commit protection or
 * admission. PREPARING source and all results remain non-admissible even when a grant is active.
 */
public final class AccountCurrentLifecycleRealmGrantReadOwner {
  private final GameSessionCurrentPrivatePlaytestLifecycleReadClient source;
  private final AccountLifecycleBoundRealmGrantRepository grants;
  private final TransactionTemplate transaction;
  private final String workloadNamespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Explicit owner collaborators are intentionally shared.")
  public AccountCurrentLifecycleRealmGrantReadOwner(
      GameSessionCurrentPrivatePlaytestLifecycleReadClient source,
      AccountLifecycleBoundRealmGrantRepository grants,
      PlatformTransactionManager transactions,
      String workloadNamespace) {
    this.source = Objects.requireNonNull(source);
    this.grants = Objects.requireNonNull(grants);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    this.workloadNamespace = workloadNamespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(true);
    transaction.setTimeout(5);
  }

  public Result read(Scope expectedGrant) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Current-lifecycle grant read requires no ambient SQL or synchronization");
    }
    Objects.requireNonNull(expectedGrant, "expectedGrant");
    var requestedSource =
        new CurrentPrivatePlaytestLifecycleReadEvidence.Scope(
            workloadNamespace,
            expectedGrant.tenantId(),
            expectedGrant.worldSlug(),
            expectedGrant.realmSlug());
    Verified verified =
        Objects.requireNonNull(
            source.read(requestedSource), "Actual Game Session source read is required");
    var evidence = verified.evidence();
    if (!requestedSource.equals(evidence.request().scope()))
      throw new IllegalStateException("Game Session source differs from exact Account selector");
    if (evidence.current().isEmpty())
      return new Result(expectedGrant, Status.SOURCE_ABSENT, verified, Optional.empty());
    var current = evidence.current().orElseThrow();
    if (!requestedSource.equals(current.scope()))
      throw new IllegalStateException(
          "Current private playtest scope differs from Account selector");
    if (!expectedGrant.playtestLifecycleId().equals(current.playtestLifecycleId())) {
      return new Result(
          expectedGrant, Status.CURRENT_LIFECYCLE_MISMATCH, verified, Optional.empty());
    }
    // The closed, peer-qualified source binds its owner-allocated namespace, generation and
    // exact private-policy identity. No caller policy, role or namespace identifier is accepted.
    Optional<CurrentGrant> grant =
        Objects.requireNonNull(
            transaction.execute(
                status -> grants.readCurrent(expectedGrant, current.forkExpiresAt())));
    if (grant.isEmpty()) return new Result(expectedGrant, Status.GRANT_ABSENT, verified, grant);
    CurrentGrant actual = grant.orElseThrow();
    if (!expectedGrant.equals(actual.receipt().scope()))
      throw new IllegalStateException("Account grant read differs from exact lifecycle scope");
    // An already expired source observation never becomes active because the Account clock is
    // behind it. The attached grant remains the unmodified Account database observation.
    Status outcome =
        actual.receipt().state() == AccountLifecycleBoundRealmGrantRepository.State.REVOKED
            ? Status.REVOKED
            : current.readState() == CurrentPrivatePlaytestLifecycleReadEvidence.ReadState.EXPIRED
                    || !actual.active()
                ? Status.EXPIRED
                : Status.ACTIVE_AS_OF_READ;
    return new Result(expectedGrant, outcome, verified, grant);
  }

  public enum Status {
    SOURCE_ABSENT,
    CURRENT_LIFECYCLE_MISMATCH,
    GRANT_ABSENT,
    REVOKED,
    EXPIRED,
    ACTIVE_AS_OF_READ
  }

  public record Result(
      Scope grantScope, Status status, Verified lifecycleSource, Optional<CurrentGrant> grant) {
    public boolean admissionAllowed() {
      return false;
    }
  }
}
