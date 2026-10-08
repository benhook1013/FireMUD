package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountActorStagingEligibilityRepository;
import net.firedevops.firemud.accountservice.repository.AccountActorStagingEligibilityRepository.CurrentSnapshot;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence.Purpose;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Produces current, non-admitting Account evidence for public-production actor staging. */
@Service
public class AccountActorStagingEligibilityService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final AccountActorStagingEligibilityRepository sourceRepository;
  private final String workloadNamespace;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Spring must proxy the read service and its source repository is required.")
  public AccountActorStagingEligibilityService(
      AccountActorStagingEligibilityRepository sourceRepository,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.sourceRepository = Objects.requireNonNull(sourceRepository);
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * Resolves one exact request inside a single repeatable-read owner snapshot. The result is only
   * staging evidence; it neither enrolls a member nor grants JOIN, PLAY, or admission authority.
   */
  @Transactional(
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ,
      propagation = Propagation.REQUIRES_NEW)
  public AccountActorStagingEligibilityEvidence resolve(
      int schemaVersion,
      String targetNamespace,
      UUID requestId,
      UUID accountUuid,
      UUID tenantUuid,
      Purpose purpose) {
    if (schemaVersion != AccountActorStagingEligibilityEvidence.SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported actor-staging eligibility schema version");
    }
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || !workloadNamespace.equals(targetNamespace)
        || !GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException(
          "Actor-staging eligibility target must match the configured Account namespace");
    }
    requireCanonicalUuid(requestId, "request ID");
    requireCanonicalUuid(accountUuid, "Account UUID");
    requireCanonicalUuid(tenantUuid, "tenant UUID");
    if (purpose != Purpose.PUBLIC_PRODUCTION_STAGING_ONLY) {
      throw new IllegalArgumentException("Only public-production actor staging is supported");
    }

    CurrentSnapshot snapshot = sourceRepository.readCurrent(accountUuid, tenantUuid);
    if (!accountUuid.equals(snapshot.accountUuid()) || !tenantUuid.equals(snapshot.tenantUuid())) {
      throw new IllegalStateException("Account owner snapshot differs from the requested scope");
    }
    try {
      return AccountActorStagingEligibilityEvidence.seal(
          targetNamespace,
          requestId,
          accountUuid,
          tenantUuid,
          purpose,
          Instant.now(),
          snapshot.accountUuidProvenance().name(),
          snapshot.accountLifecycleState(),
          snapshot.membershipLifecycleState(),
          snapshot.gameplayAdmissionAllowed(),
          snapshot.membershipAuthorityProvenance(),
          snapshot.membershipVersion(),
          snapshot.membershipAuthorityGeneration(),
          snapshot.tenantProvenanceKind(),
          snapshot.tenantSourceOperationId(),
          snapshot.tenantProvenanceDigest(),
          snapshot.membershipEventSequence(),
          snapshot.membershipEventId(),
          snapshot.membershipEventDigest(),
          snapshot.lastTransitionInvalidated());
    } catch (IllegalArgumentException invalidOwnerEvidence) {
      throw new IllegalStateException(
          "Account owner snapshot cannot produce valid staging evidence", invalidOwnerEvidence);
    }
  }

  private static void requireCanonicalUuid(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical nonnil UUID");
    }
  }
}
