package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository.RetainedTenantAssociationIdentity;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindCatalogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal, non-startup owner path that composes a complete Game Design policy set with the
 * committed local tenant association before Game Session materializes its immutable catalog.
 */
@Service
@Lazy
public final class PublishedRealmCatalogOwnerService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final GameDesignPublishedRealmPolicyClient gameDesignClient;
  private final GameSessionRetainedTenantAssociationRepository retainedAssociationRepository;
  private final InitialAdmissionBindCatalogRepository catalogRepository;
  private final String workloadNamespace;
  private final TransactionTemplate ownerTransaction;

  public PublishedRealmCatalogOwnerService(
      GameDesignPublishedRealmPolicyClient gameDesignClient,
      GameSessionRetainedTenantAssociationRepository retainedAssociationRepository,
      InitialAdmissionBindCatalogRepository catalogRepository,
      PlatformTransactionManager transactionManager,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.gameDesignClient = Objects.requireNonNull(gameDesignClient, "gameDesignClient");
    this.retainedAssociationRepository =
        Objects.requireNonNull(retainedAssociationRepository, "retainedAssociationRepository");
    this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Reads Game Design and the committed retained-tenant mapping before opening the local owner
   * transaction. The returned immutable policy snapshot may stage unresolved private entries;
   * admission must select an exact visible entry and that entry must independently carry resolved
   * namespace authority before it can be used.
   */
  public PublishedRealmCatalogSnapshot materializePublishedSnapshot(
      UUID canonicalTenantId, long versionId) {
    requireOutsideOwnerTransaction();
    requireCanonicalTenantId(canonicalTenantId);
    if (versionId <= 0) {
      throw new IllegalArgumentException("Published version ID must be positive");
    }

    var association =
        retainedAssociationRepository
            .readMinimalAssociation(workloadNamespace, canonicalTenantId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "PUBLISHED_REALM_CATALOG_TENANT_ASSOCIATION_MISSING: committed local identity is required"));
    requireAssociationIdentity(association, canonicalTenantId);

    PublishedRealmEntryPolicySetEvidence policySet =
        gameDesignClient.listPublishedRealmEntryPolicies(canonicalTenantId.toString(), versionId);
    requirePolicySetMatchesAssociation(policySet, association);

    return ownerTransaction.execute(
        status ->
            catalogRepository.materializePublishedSnapshot(
                workloadNamespace,
                association.legacyGameSessionTenantId(),
                association.canonicalTenantId(),
                association.sourceGameRowId(),
                association.sourceGameTenantKey(),
                association.provenanceKind(),
                policySet));
  }

  private void requireAssociationIdentity(
      RetainedTenantAssociationIdentity association, UUID canonicalTenantId) {
    if (!workloadNamespace.equals(association.targetNamespace())
        || !canonicalTenantId.equals(association.canonicalTenantId())
        || association.legacyGameSessionTenantId() <= 0
        || association.sourceGameRowId() <= 0
        || association.sourceGameTenantKey() == null
        || association.sourceGameTenantKey().isBlank()
        || !("NEW_GAME_ROW".equals(association.provenanceKind())
            || "RETAINED_GAME_V29".equals(association.provenanceKind()))) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_TENANT_ASSOCIATION_INVALID: committed local mapping is contradictory");
    }
  }

  private void requirePolicySetMatchesAssociation(
      PublishedRealmEntryPolicySetEvidence policySet,
      RetainedTenantAssociationIdentity association) {
    if (policySet == null
        || !policySet.hasValidDigest(OBJECT_MAPPER)
        || !association.canonicalTenantId().equals(policySet.canonicalTenantId())) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_OWNER_SET_INVALID: complete authenticated owner set is required");
    }
    for (PublishedRealmEntryPolicyEvidence evidence : policySet.policies()) {
      if (!association.canonicalTenantId().equals(evidence.canonicalTenantId())
          || association.sourceGameRowId() != evidence.sourceGameRowId()
          || !association.sourceGameTenantKey().equals(evidence.sourceGameTenantKey())
          || !association.provenanceKind().equals(evidence.tenantIdentityProvenanceKind())) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_SOURCE_MISMATCH: Game Design provenance differs from the committed tenant association");
      }
    }
  }

  private static void requireCanonicalTenantId(UUID canonicalTenantId) {
    if (canonicalTenantId == null || NIL_UUID.equals(canonicalTenantId)) {
      throw new IllegalArgumentException("Canonical tenant UUID must be non-nil");
    }
  }

  private static void requireOutsideOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Published realm owner reads must complete before the local catalog transaction starts");
    }
  }
}
