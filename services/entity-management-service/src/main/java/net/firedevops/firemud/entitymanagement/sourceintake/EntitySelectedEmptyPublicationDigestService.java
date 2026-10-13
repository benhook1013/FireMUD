package net.firedevops.firemud.entitymanagement.sourceintake;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
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
    UUID canonicalTenantId = canonicalUuid(publicationRequest.tenantId(), "tenantId");
    long gameDesignVersionRowId =
        canonicalPositiveLong(publicationRequest.versionId(), "versionId");

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
    ByteArrayOutputStream preimage = new ByteArrayOutputStream();
    appendSegment(preimage, DIGEST_DOMAIN);
    appendSegment(preimage, Integer.toString(DIGEST_SCHEMA_VERSION));
    appendSegment(preimage, canonicalTenantId.toString());
    appendSegment(preimage, Long.toString(gameDesignVersionRowId));
    appendSegment(preimage, canonicalVersionId.toString());
    appendSegment(preimage, canonicalInventoryJson);
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
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

  private static UUID canonicalUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || new UUID(0L, 0L).equals(parsed)) {
        throw new IllegalArgumentException(field + " must be a canonical nonzero UUID");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(field + " must be a canonical nonzero UUID", malformed);
    }
  }

  private static long canonicalPositiveLong(String value, String field) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
        throw new IllegalArgumentException(field + " must be a canonical positive decimal");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(
          field + " must be a canonical positive decimal", malformed);
    }
  }

  private static void appendSegment(ByteArrayOutputStream output, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }
}
