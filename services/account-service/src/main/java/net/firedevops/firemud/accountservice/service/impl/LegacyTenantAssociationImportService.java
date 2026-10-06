package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.accountservice.client.GameDesignAccountTenantAssociationOwnerClient;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit, nonactivating import boundary. No Spring bean or owner client is registered until Game
 * Design supplies its Account-specific authenticated producer operation.
 */
public final class LegacyTenantAssociationImportService {
  private final GameDesignAccountTenantAssociationOwnerClient ownerClient;
  private final ApprovedLegacyTenantAssociationRepository repository;
  private final LegacyTenantSourceEvidence sourceEvidence;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected owner client, repository, and evidence reader are internal service collaborators.")
  public LegacyTenantAssociationImportService(
      GameDesignAccountTenantAssociationOwnerClient ownerClient,
      ApprovedLegacyTenantAssociationRepository repository,
      LegacyTenantSourceEvidence sourceEvidence) {
    this.ownerClient = ownerClient;
    this.repository = repository;
    this.sourceEvidence = sourceEvidence;
  }

  @Transactional(readOnly = true)
  public LegacyTenantSourceEvidence.SourceProjection retainedSourceProjection(long legacyTenantId) {
    return sourceEvidence.projection(legacyTenantId);
  }

  public ApprovedAssociation importApproved(long legacyTenantId) {
    return repository.importOwnerApproved(
        legacyTenantId, ownerClient.resolveApprovedAssociation(legacyTenantId));
  }
}
