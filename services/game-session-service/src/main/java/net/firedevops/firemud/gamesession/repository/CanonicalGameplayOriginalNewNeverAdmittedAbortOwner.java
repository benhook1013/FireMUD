package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionNeverAdmittedGrpcCodec;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import org.jooq.DSLContext;

/** Manual negative-purpose continuation; it never turns PREPARED into admitting evidence. */
public final class CanonicalGameplayOriginalNewNeverAdmittedAbortOwner {
  private final CanonicalGameplayBindingInventoryRepository inventory;
  private final CanonicalGameplayOriginalNewCandidateClosureRedisOwner closure;
  private final AccountGameplayAdmissionLeaseClient account;
  private final CanonicalGameplayIssuerBindingLocalOperationRepository originals;
  private final CanonicalGameplayIssuerBindingLocalRedisOwner issuer;
  private final CanonicalGameplayIssuerBindingLocalReleaseOperationRepository releases;
  private final CanonicalGameplayIssuerBindingLocalReleaseRedisOwner releaseRedis;
  private final CanonicalGameplayIssuerBindingLocalRetirementOperationRepository retirements;
  private final CanonicalGameplayIssuerBindingLocalRetirementRedisOwner retirementRedis;

  CanonicalGameplayOriginalNewNeverAdmittedAbortOwner(
      DSLContext dsl,
      RedisClient redis,
      RedisScriptCatalog catalog,
      CanonicalGameplayTenantGameplayTagRepository tags,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource issuerSource,
      CanonicalGameplayOriginalNewClosureQualification qualification,
      AccountGameplayAdmissionLeaseClient account) {
    this.inventory = new CanonicalGameplayBindingInventoryRepository(Objects.requireNonNull(dsl));
    this.closure =
        new CanonicalGameplayOriginalNewCandidateClosureRedisOwner(
            redis, catalog, inventory, tags, qualification);
    this.account = Objects.requireNonNull(account);
    this.originals = new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, issuerSource);
    var projections =
        new CanonicalGameplayIssuerBindingLocalProjectionRepository(dsl, issuerSource);
    this.issuer =
        new CanonicalGameplayIssuerBindingLocalRedisOwner(
            redis, catalog, projections, issuerSource);
    this.releases =
        new CanonicalGameplayIssuerBindingLocalReleaseOperationRepository(dsl, issuerSource);
    this.releaseRedis =
        new CanonicalGameplayIssuerBindingLocalReleaseRedisOwner(
            redis, catalog, projections, issuerSource);
    this.retirements =
        new CanonicalGameplayIssuerBindingLocalRetirementOperationRepository(dsl, issuerSource);
    this.retirementRedis =
        new CanonicalGameplayIssuerBindingLocalRetirementRedisOwner(
            redis, catalog, projections, retirements, issuerSource);
  }

  public CanonicalGameplayOriginalNewAbandonmentSource abort(
      UUID transitionId, String presentedInitialGsACompactJwt) {
    Objects.requireNonNull(transitionId);
    return closure.withClosedCandidate(
        transitionId,
        physical -> {
          var selected = inventory.readOriginalNewAbandonmentSource(transitionId);
          if (!physical.matches(selected)) throw unavailable();
          var response =
              account.abortNeverAdmitted(
                  presentedInitialGsACompactJwt,
                  selected.candidate().leaseEvidence(),
                  transitionId,
                  selected.abandonmentId());
          var actual = new AccountAbortReadback(response, physical);
          var terminal = inventory.recordNeverAdmittedAccountAbort(selected, actual);
          if (!actual.matches(terminal)) throw unavailable();
          if (terminal.inventory().lifecycle()
              == CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL) {
            return terminal;
          }
          var source = inventory.readIssuerCleanupSource(transitionId);
          if (!source.neverAdmitted()) throw unavailable();
          // Finish only the original issued capacity intent. A missing original source is unknown,
          // never evidence of absent capacity or permission to invent a replacement operation.
          var original = originals.readOriginalForIssuerRelease(source);
          issuer.reconcileNeverAdmittedCapacity(original);
          var release = releases.prepareOrResume(transitionId);
          releases.completeWithReadback(release, releaseRedis.releaseAndRead(release));
          var retirement = retirements.prepareOrResume(transitionId);
          retirementRedis.retireAndRead(retirement);
          if (!physical.matches(inventory.readOriginalNewAbandonmentSource(transitionId))) {
            throw unavailable();
          }
          return inventory.completeOriginalNewAbandonmentCleanup(transitionId);
        });
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original NEW negative settlement is unavailable");
  }

  /** Only this owner creates the capability, from its actual authenticated Account RPC result. */
  public static final class AccountAbortReadback {
    private final AccountGameplayAdmissionNeverAdmittedGrpcCodec.AbortEvidence evidence;
    private final CanonicalGameplayOriginalNewCandidateClosureRedisOwner.Readback physical;

    private AccountAbortReadback(
        AccountGameplayAdmissionNeverAdmittedGrpcCodec.AbortEvidence evidence,
        CanonicalGameplayOriginalNewCandidateClosureRedisOwner.Readback physical) {
      this.evidence = Objects.requireNonNull(evidence);
      this.physical = Objects.requireNonNull(physical);
    }

    public boolean matches(CanonicalGameplayOriginalNewAbandonmentSource expected) {
      return physical.matches(expected)
          && expected.transition().transitionId().equals(evidence.transitionId())
          && expected.abandonmentId().equals(evidence.abandonmentId())
          && expected
              .candidate()
              .leaseEvidence()
              .canonicalJson()
              .equals(evidence.originalLeaseEvidence().canonicalJson())
          && CanonicalGameplayOriginalNewCandidateClosureRedisOwner.candidateDigest(
                  expected.candidate())
              .substring(7)
              .equals(evidence.candidateDigest())
          && physical.digest().substring(7).equals(evidence.closureDigest());
    }

    public AccountGameplayAdmissionNeverAdmittedGrpcCodec.AbortEvidence evidence() {
      physical.digest(); // Refuse use after the qualified callback has ended.
      return evidence;
    }

    @Override
    public String toString() {
      return "NeverAdmittedAccountAbortReadback[redacted]";
    }
  }
}
