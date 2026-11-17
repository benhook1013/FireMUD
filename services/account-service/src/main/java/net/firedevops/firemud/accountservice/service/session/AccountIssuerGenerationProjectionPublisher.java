package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered issuer-only informational reprojection. No Account lookup/enrollment/key, token,
 * authority grant, repair of malformed Redis evidence, or runtime activation is supplied.
 */
public final class AccountIssuerGenerationProjectionPublisher {
  private final AccountAuthoritySourceEvidenceRepository sources;
  private final TransactionTemplate transaction;
  private final AccountGameplayDelegationRedisClient redis;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Retain the existing issuer source owner; copying an injected repository would break its transaction-bound SQL collaboration.")
  public AccountIssuerGenerationProjectionPublisher(
      AccountAuthoritySourceEvidenceRepository sources,
      PlatformTransactionManager transactions,
      AccountGameplayDelegationRedisClient redis) {
    this.sources = Objects.requireNonNull(sources);
    this.redis = Objects.requireNonNull(redis);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
  }

  /** One issuer-key CAS followed by exact Redis and fresh SQL readback, never implicit retry. */
  public Publication refreshCurrent(String issuerId) {
    IssuerGenerationProjection.keyForIssuer(issuerId);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Issuer-only projection cannot inherit owner SQL context");
    }
    var before = read(issuerId);
    var candidate = IssuerGenerationProjection.fromSource(before);
    var outcome = redis.publishIssuerAuthorityProjection(candidate);
    var after = read(issuerId);
    if (!before.source().equals(after.source())
        || !candidate.equals(IssuerGenerationProjection.fromSource(after))) {
      throw new IllegalStateException(
          "Issuer source changed during projection; explicit retry required");
    }
    return new Publication(candidate.key(), candidate.toJson(), outcome);
  }

  private CanonicalIssuerSourceSnapshot read(String issuerId) {
    return Objects.requireNonNull(
        transaction.execute(ignored -> sources.readCurrentCanonicalIssuerSource(issuerId)),
        "Current existing issuer source required");
  }

  /** Exact observed cache values, never an authorization or freshness receipt for later use. */
  public record Publication(
      String key,
      String json,
      AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome outcome) {
    public Publication {
      Objects.requireNonNull(key);
      Objects.requireNonNull(json);
      Objects.requireNonNull(outcome);
    }
  }
}
