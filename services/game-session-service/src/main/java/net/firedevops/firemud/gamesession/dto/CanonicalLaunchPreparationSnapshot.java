package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;

/** Exact immutable source/catalog/descriptor evidence consumed by one launch preparation. */
public record CanonicalLaunchPreparationSnapshot(
    UUID operationId,
    CreateCanonicalLaunchPreparationRequest request,
    CanonicalRealmCatalogSnapshot catalogSnapshot,
    IntakeReceipt sourceIntakeReceipt,
    AuthoredWorldLaunchDescriptorEvidence launchDescriptorEvidence,
    String requestDigest,
    String receiptDigest) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  public CanonicalLaunchPreparationSnapshot {
    requireNonNil(operationId, "operationId");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
    Objects.requireNonNull(sourceIntakeReceipt, "sourceIntakeReceipt");
    Objects.requireNonNull(launchDescriptorEvidence, "launchDescriptorEvidence");
    requireDigest(requestDigest, "requestDigest");
    requireDigest(receiptDigest, "receiptDigest");
    requireConsumedEvidence(
        request, catalogSnapshot, sourceIntakeReceipt, launchDescriptorEvidence);
  }

  /** Revalidates the complete closed shared descriptor and all local identity joins. */
  public void requireValid() {
    requireConsumedEvidence(
        request, catalogSnapshot, sourceIntakeReceipt, launchDescriptorEvidence);
    launchDescriptorEvidence.requireValid();
  }

  private static void requireConsumedEvidence(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source,
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    if (!request.targetNamespace().equals(catalog.targetNamespace())
        || !request.canonicalTenantId().equals(catalog.tenantId())
        || !request.realmId().equals(catalog.realmId())
        || !request.catalogCreationRequestId().equals(catalog.creationRequestId())
        || request.catalogRevision() != catalog.catalogRevision()
        || !request.sourceIntakeOperationId().equals(source.operationId())
        || !source.equals(catalog.sourceIntakeReceipt())
        || !"NEW_GAME_ROW".equals(source.source().provenanceKind())
        || !descriptor.request().equals(request.descriptorRequest(catalog, source.source()))) {
      throw new IllegalArgumentException(
          "Launch preparation evidence does not match its exact request, catalog, and source");
    }
    descriptor.requireValid();
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private static void requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 digest");
    }
  }
}
