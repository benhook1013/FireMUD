package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient;
import net.firedevops.firemud.gamesession.repository.FreshGameSessionTenantAssociationRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit, non-bean boundary for exact authenticated fresh Game Design identity and its Game
 * Session-owned local association. This is identity ownership only, not admission authority.
 */
public final class FreshGameSessionTenantAssociationService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final GameDesignRuntimeTenantIdentityClient sourceClient;
  private final FreshGameSessionTenantAssociationRepository repository;
  private final String workloadNamespace;

  public FreshGameSessionTenantAssociationService(
      GameDesignRuntimeTenantIdentityClient sourceClient,
      FreshGameSessionTenantAssociationRepository repository,
      String workloadNamespace) {
    this.sourceClient = Objects.requireNonNull(sourceClient, "sourceClient");
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Reads exact owner evidence before the repository opens its short local owner transaction. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public FreshGameSessionTenantAssociation associate(
      UUID associationRequestId, UUID canonicalTenantId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Design runtime identity read must finish before the local owner transaction");
    }
    requireNonNil(associationRequestId, "associationRequestId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");

    RuntimeTenantIdentityEvidence evidence =
        sourceClient.resolveRuntimeTenantIdentity(
            canonicalTenantId.toString(), associationRequestId.toString());
    if (evidence == null
        || !workloadNamespace.equals(evidence.targetNamespace())
        || !associationRequestId.equals(evidence.requestId())
        || !canonicalTenantId.equals(evidence.canonicalTenantId())
        || !"NEW_GAME_ROW".equals(evidence.provenanceKind())) {
      throw new IllegalStateException(
          "Authenticated Game Design runtime identity does not match the exact fresh request");
    }
    return repository.registerAndReadback(evidence);
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }
}
