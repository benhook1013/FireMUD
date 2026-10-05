package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.GameDesignFreshTenantIdentityClient;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired internal Account composition for fresh Game Design tenant identity enrollment. It
 * persists and reads back Account identity before initializing the UUID tenant generation only.
 * That generation is an owner-local fence, not a membership or admission grant; this composition
 * does not create credentials, membership, entitlement, grant, actor assignment, or admission.
 */
public final class FreshTenantIdentityEnrollmentService {
  private final GameDesignFreshTenantIdentityClient gameDesignClient;
  private final FreshTenantIdentityAssociationRepository associationRepository;
  private final AccountAuthorityGenerationRepository generationRepository;
  private final TransactionTemplate transactionTemplate;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Trusted internal client, repository, generation, and transaction collaborators are "
              + "retained privately and never exposed by this unwired service.")
  public FreshTenantIdentityEnrollmentService(
      GameDesignFreshTenantIdentityClient gameDesignClient,
      FreshTenantIdentityAssociationRepository associationRepository,
      AccountAuthorityGenerationRepository generationRepository,
      TransactionTemplate transactionTemplate) {
    this.gameDesignClient = Objects.requireNonNull(gameDesignClient);
    this.associationRepository = Objects.requireNonNull(associationRepository);
    this.generationRepository = Objects.requireNonNull(generationRepository);
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
  }

  /**
   * Resolves authenticated owner evidence before opening the single Account write transaction, then
   * imports, reads back, and initializes the UUID tenant generation without resetting it.
   */
  public FreshTenantCreationEvidence enroll(UUID creationRequestId, String expectedDigest) {
    if (creationRequestId == null
        || creationRequestId.equals(new UUID(0L, 0L))
        || !GameTenantCreationDigest.isDigest(expectedDigest)) {
      throw new IllegalArgumentException("Fresh tenant request identity or digest is invalid");
    }

    FreshTenantCreationEvidence ownerEvidence =
        Objects.requireNonNull(
            gameDesignClient.resolveCreation(creationRequestId, expectedDigest),
            "Game Design creation evidence is required");
    if (!creationRequestId.equals(ownerEvidence.creationRequestId())
        || !expectedDigest.equals(ownerEvidence.requestDigest())) {
      throw new IllegalStateException(
          "Game Design creation evidence differs from the requested operation");
    }

    FreshTenantCreationEvidence committed =
        transactionTemplate.execute(
            status -> {
              FreshTenantCreationEvidence imported =
                  associationRepository.importVerified(ownerEvidence);
              FreshTenantCreationEvidence readback =
                  associationRepository
                      .read(ownerEvidence.canonicalTenantId())
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Fresh Account tenant association readback is absent"));
              if (!ownerEvidence.equals(imported) || !ownerEvidence.equals(readback)) {
                throw new IllegalStateException(
                    "Fresh Account tenant association readback differs from owner evidence");
              }
              generationRepository.initializeTenantIfAbsent(readback.canonicalTenantId());
              return readback;
            });
    return Objects.requireNonNull(
        committed, "Fresh tenant enrollment transaction returned no result");
  }
}
