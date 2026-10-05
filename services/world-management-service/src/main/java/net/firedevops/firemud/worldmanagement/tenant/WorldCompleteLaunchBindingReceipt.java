package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;

/**
 * Internal World receipt for a retained authored-world descriptor and complete release pair.
 *
 * <p>This is non-admitting released-content evidence. It does not represent local content,
 * PREPARING, ACTIVE/current authority, or activation.
 */
public record WorldCompleteLaunchBindingReceipt(
    int schemaVersion,
    UUID operationId,
    String targetNamespace,
    UUID canonicalTenantId,
    String worldSlug,
    String controlPlaneRequestId,
    WorldAuthoredSourceIntakeReceipt sourceIntakeReceipt,
    CompleteLaunchBindingEvidence evidence) {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public WorldCompleteLaunchBindingReceipt {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported World complete launch binding version");
    }
    requireNonNil(operationId, "operationId");
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(worldSlug, "worldSlug");
    Objects.requireNonNull(controlPlaneRequestId, "controlPlaneRequestId");
    Objects.requireNonNull(sourceIntakeReceipt, "sourceIntakeReceipt");
    Objects.requireNonNull(evidence, "evidence");

    AuthoredWorldLaunchDescriptorEvidence descriptor = evidence.descriptor();
    descriptor.requireValid();
    evidence.releaseAttestation().requireValid(descriptor);
    if (!targetNamespace.equals(descriptor.targetNamespace())
        || !canonicalTenantId.equals(descriptor.canonicalTenantId())
        || !worldSlug.equals(descriptor.worldSlug())
        || !controlPlaneRequestId.equals(descriptor.controlPlaneRequestId())
        || !targetNamespace.equals(sourceIntakeReceipt.targetNamespace())
        || !canonicalTenantId.equals(sourceIntakeReceipt.canonicalTenantId())
        || !worldSlug.equals(sourceIntakeReceipt.worldSlug())
        || !descriptor
            .authoredWorldSourceOperationId()
            .equals(sourceIntakeReceipt.sourceOperationId())
        || !descriptor
            .authoredWorldSourceEvidenceDigest()
            .equals(sourceIntakeReceipt.sourceEvidenceDigest())) {
      throw new IllegalArgumentException(
          "World complete launch binding differs from its exact authored-source intake");
    }
  }

  public AuthoredWorldLaunchDescriptorEvidence descriptor() {
    return evidence.descriptor();
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }
}
