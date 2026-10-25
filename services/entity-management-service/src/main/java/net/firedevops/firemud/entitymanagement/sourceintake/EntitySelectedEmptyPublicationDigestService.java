package net.firedevops.firemud.entitymanagement.sourceintake;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.SelectedEmptyPublicationDigestSupport;
import net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService.EntityDraftDesignDigest;

/** Unregistered selected-empty full-version digest reader over Entity's retained owner receipt. */
public final class EntitySelectedEmptyPublicationDigestService {
  public static final int DIGEST_SCHEMA_VERSION = 3;
  private static final String DIGEST_DOMAIN = "entity-selected-empty-publication-digest/v1";

  private final String configuredNamespace;
  private final EntityEmptySelectedSourceIntakeRepository repository;

  public EntitySelectedEmptyPublicationDigestService(
      String configuredNamespace, EntityEmptySelectedSourceIntakeRepository repository) {
    if (!GrpcPeerIdentity.isValidNamespace(configuredNamespace)) {
      throw new IllegalArgumentException("Configured Entity namespace is required");
    }
    this.configuredNamespace = configuredNamespace;
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /** Returns a selected-empty digest only for one exact canonical tenant and GD Version row. */
  public EntityDraftDesignDigest getDraftDesignDigest(
      PublicationDigestRequestBinding publicationRequest) {
    Objects.requireNonNull(publicationRequest, "publication request is required");
    if (publicationRequest.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      throw new IllegalArgumentException(
          "Entity selected-empty publication supports full versions only");
    }
    UUID canonicalTenantId =
        SelectedEmptyPublicationDigestSupport.canonicalUuid(
            publicationRequest.tenantId(), "tenantId");
    long gameDesignVersionRowId =
        SelectedEmptyPublicationDigestSupport.canonicalPositiveLong(
            publicationRequest.versionId(), "versionId");

    EntityEmptySelectedSourceIntakeReceipt receipt =
        repository.readPublicationScope(
            configuredNamespace, canonicalTenantId, gameDesignVersionRowId);
    var target = receipt.authorizationBinding().selected().target();
    if (!configuredNamespace.equals(receipt.targetNamespace())
        || !canonicalTenantId.equals(receipt.canonicalTenantId())
        || !canonicalTenantId.equals(target.canonicalTenantId())
        || target.gameDesignVersionRowId() != gameDesignVersionRowId
        || !receipt.canonicalVersionId().equals(target.canonicalVersionId())) {
      throw new IllegalStateException(
          "Entity selected-empty receipt differs from publication scope");
    }

    var inputs = receipt.inputs();
    SelectedEmptyPublicationDigestSupport.requireOriginalPublicationRequest(
        publicationRequest,
        canonicalTenantId,
        gameDesignVersionRowId,
        receipt.canonicalVersionId(),
        receipt.selectedCommitId(),
        inputs.worldInventoryReadEvidence().request().freezeEvidence(),
        "Entity");
    if (inputs.authorizationBinding().owner() != Owner.ENTITY_MANAGEMENT) {
      throw new IllegalStateException("Entity receipt contains a different owner declaration");
    }
    requireEmptyReceipt(receipt);
    String inventoryJson =
        inputs.ownerSourceInventoryDeclaration().entityInventory().canonicalJson();
    String contentDigest =
        contentDigest(
            canonicalTenantId, gameDesignVersionRowId, receipt.canonicalVersionId(), inventoryJson);
    return new EntityDraftDesignDigest(
        publicationRequest.tenantId(),
        publicationRequest.versionId(),
        receipt.selectedCommitId().toString(),
        contentDigest,
        DIGEST_SCHEMA_VERSION);
  }

  static String contentDigest(
      UUID canonicalTenantId,
      long gameDesignVersionRowId,
      UUID canonicalVersionId,
      String canonicalInventoryJson) {
    if (gameDesignVersionRowId <= 0L) {
      throw new IllegalArgumentException("Game Design Version row must be positive");
    }
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
    Objects.requireNonNull(canonicalInventoryJson, "canonicalInventoryJson");
    return SelectedEmptyPublicationDigestSupport.framedSha256(
        DIGEST_DOMAIN,
        DIGEST_SCHEMA_VERSION,
        canonicalTenantId.toString(),
        Long.toString(gameDesignVersionRowId),
        canonicalVersionId.toString(),
        canonicalInventoryJson);
  }

  private static void requireEmptyReceipt(EntityEmptySelectedSourceIntakeReceipt receipt) {
    if (receipt.familyStates().size() != 23
        || receipt.familyStates().stream()
            .anyMatch(
                state ->
                    state.state() != EntityEmptySelectedSourceIntakeReceipt.FamilyState.EMPTY
                        || state.rowCount() != 0L
                        || state.unqualifiedRowCount() != 0L
                        || state.retainedRowCount() != 0L
                        || state.selectedScopeRowCount() != 0L
                        || state.referenceCount() != 0L)) {
      throw new IllegalStateException("Entity selected-empty receipt is incomplete or nonempty");
    }
  }
}
