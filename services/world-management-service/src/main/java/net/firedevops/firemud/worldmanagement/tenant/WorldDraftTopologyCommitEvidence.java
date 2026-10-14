package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;

/**
 * Immutable component storage evidence; never Account permission, APPLIED, or fence release proof.
 */
public final class WorldDraftTopologyCommitEvidence {
  public static final String STATUS = "STORED_PERMISSION_UNVERIFIED";

  private final DraftCommitBinding binding;
  private final OwnerBinding ownerBinding;
  private final byte[] graphBytes;
  private final byte[] resultBytes;

  WorldDraftTopologyCommitEvidence(
      DraftCommitBinding binding,
      OwnerBinding ownerBinding,
      byte[] graphBytes,
      byte[] resultBytes) {
    this.binding = Objects.requireNonNull(binding, "binding");
    this.ownerBinding = Objects.requireNonNull(ownerBinding, "ownerBinding");
    this.graphBytes = Objects.requireNonNull(graphBytes, "graphBytes").clone();
    this.resultBytes = Objects.requireNonNull(resultBytes, "resultBytes").clone();
    if (graphBytes.length == 0 || resultBytes.length == 0) {
      throw new IllegalArgumentException("Complete graph and storage result bytes are required");
    }
  }

  public DraftCommitBinding binding() {
    return binding;
  }

  public OwnerBinding ownerBinding() {
    return ownerBinding;
  }

  public byte[] graphBytes() {
    return graphBytes.clone();
  }

  public byte[] resultBytes() {
    return resultBytes.clone();
  }

  public String status() {
    return STATUS;
  }
}
