package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Resolves retained numeric tenant keys through an approved, provenance-backed UUID association. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories are internal Spring transaction collaborators.")
public class AccountTenantIdentityResolver {
  private final ApprovedLegacyTenantAssociationRepository associations;
  private final LegacyTenantSourceEvidence sourceEvidence;
  private final String workloadNamespace;

  public AccountTenantIdentityResolver(
      ApprovedLegacyTenantAssociationRepository associations,
      LegacyTenantSourceEvidence sourceEvidence,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.associations = associations;
    this.sourceEvidence = sourceEvidence;
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * Returns the canonical UUID and complete immutable migration provenance for a retained key.
   * The association and current retained-row evidence are read in the caller's transaction. The
   * caller must provide the isolation and fence for its complete authority snapshot; this read is
   * not itself a membership-authority snapshot.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ApprovedAssociation resolve(long legacyTenantId) {
    if (legacyTenantId <= 0) {
      throw new IllegalArgumentException("positive retained Account tenant key is required");
    }
    if (workloadNamespace == null || workloadNamespace.isBlank()) {
      throw new IllegalStateException("Account workload namespace is not configured");
    }

    Optional<ApprovedAssociation> stored = associations.findByLegacyTenantId(legacyTenantId);
    if (stored.isEmpty()) {
      throw new IllegalStateException("approved Account tenant association is absent");
    }
    ApprovedAssociation association = stored.orElseThrow();
    if (association.legacyTenantId() != legacyTenantId
        || association.canonicalTenantId() == null
        || !workloadNamespace.equals(association.targetNamespace())) {
      throw new IllegalStateException("approved Account tenant association is mismatched");
    }

    String currentEvidenceDigest = sourceEvidence.digest(legacyTenantId);
    if (!Objects.equals(association.accountEvidenceDigest(), currentEvidenceDigest)) {
      throw new IllegalStateException("approved Account source evidence differs from retained rows");
    }
    return association;
  }
}
