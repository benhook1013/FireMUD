package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Resolves a retained numeric Account tenant only through its durable approved UUID claim. */
@Repository
public class AccountTenantIdentityResolver {
  private static final UUID NIL = new UUID(0L, 0L);

  private final ApprovedLegacyTenantAssociationRepository associations;
  private final String workloadNamespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected association repository is an internal Spring collaborator and is not exposed.")
  public AccountTenantIdentityResolver(
      ApprovedLegacyTenantAssociationRepository associations,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.associations = associations;
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * Returns identity provenance only. This is not an Account membership or gameplay-admission
   * snapshot. It intentionally does not read or re-digest V26 rows or expired signed payload.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public ApprovedLegacyTenantAssociationRepository.ApprovedAssociation resolve(
      long legacyTenantId) {
    if (legacyTenantId <= 0) {
      throw new IllegalArgumentException("positive retained Account tenant key is required");
    }
    requireNamespace();
    Optional<ApprovedLegacyTenantAssociationRepository.ApprovedAssociation> stored =
        associations.findByLegacyTenantId(legacyTenantId);
    if (stored.isEmpty()) {
      throw new IllegalStateException("approved Account tenant association is absent");
    }
    var association = stored.orElseThrow();
    validate(
        association.legacyTenantId(),
        association.canonicalTenantId(),
        association.targetNamespace());
    if (association.legacyTenantId() != legacyTenantId) {
      throw new IllegalStateException("approved Account tenant association is mismatched");
    }
    return association;
  }

  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public ApprovedLegacyTenantAssociationRepository.ApprovedAssociation resolve(
      UUID canonicalTenantId) {
    if (canonicalTenantId == null || NIL.equals(canonicalTenantId)) {
      throw new IllegalArgumentException("non-nil canonical tenant UUID is required");
    }
    requireNamespace();
    Optional<ApprovedLegacyTenantAssociationRepository.ApprovedAssociation> stored =
        associations.findByCanonicalTenantId(canonicalTenantId);
    if (stored.isEmpty()) {
      throw new IllegalStateException("approved Account tenant association is absent");
    }
    var association = stored.orElseThrow();
    validate(
        association.legacyTenantId(),
        association.canonicalTenantId(),
        association.targetNamespace());
    if (!canonicalTenantId.equals(association.canonicalTenantId())) {
      throw new IllegalStateException("approved Account tenant association is mismatched");
    }
    return association;
  }

  private void requireNamespace() {
    if (workloadNamespace == null || workloadNamespace.isBlank()) {
      throw new IllegalStateException("Account workload namespace is not configured");
    }
  }

  private void validate(long legacyTenantId, UUID canonicalTenantId, String targetNamespace) {
    if (legacyTenantId <= 0
        || canonicalTenantId == null
        || NIL.equals(canonicalTenantId)
        || !workloadNamespace.equals(targetNamespace)) {
      throw new IllegalStateException("approved Account tenant association is mismatched");
    }
  }
}
