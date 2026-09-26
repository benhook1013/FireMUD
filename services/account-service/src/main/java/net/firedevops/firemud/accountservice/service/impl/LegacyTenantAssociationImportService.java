package net.firedevops.firemud.accountservice.service.impl;

import net.firedevops.firemud.accountservice.client.GameDesignTenantIdentityClient;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operator-invoked import; neither lookup nor evidence enumeration grants membership admission. */
@Service
public class LegacyTenantAssociationImportService {
  private final GameDesignTenantIdentityClient ownerClient;
  private final ApprovedLegacyTenantAssociationRepository repository;
  private final LegacyTenantSourceEvidence sourceEvidence;

  public LegacyTenantAssociationImportService(
      GameDesignTenantIdentityClient ownerClient,
      ApprovedLegacyTenantAssociationRepository repository,
      LegacyTenantSourceEvidence sourceEvidence) {
    this.ownerClient = ownerClient;
    this.repository = repository;
    this.sourceEvidence = sourceEvidence;
  }

  @Transactional(readOnly = true)
  public String retainedEvidenceDigest(long legacyTenantId) {
    return sourceEvidence.digest(legacyTenantId);
  }

  public ApprovedAssociation importApproved(long legacyTenantId) {
    return repository.importApproved(
        legacyTenantId, ownerClient.resolveApprovedAssociation(legacyTenantId));
  }
}
