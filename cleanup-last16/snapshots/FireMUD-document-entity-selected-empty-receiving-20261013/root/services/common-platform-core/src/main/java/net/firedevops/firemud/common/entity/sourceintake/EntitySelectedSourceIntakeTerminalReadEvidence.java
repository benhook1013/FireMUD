package net.firedevops.firemud.common.entity.sourceintake;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact lookup correlation and the original committed Entity receipt. */
public record EntitySelectedSourceIntakeTerminalReadEvidence(
    Request request, EntityEmptySelectedSourceIntakeReceipt receipt) {
  public static final int SCHEMA_VERSION = 1;
  public static final String TERMINAL_READ_PURPOSE = "ENTITY_INTAKE_TERMINAL_READ";

  public EntitySelectedSourceIntakeTerminalReadEvidence {
    Objects.requireNonNull(request, "terminal-read request is required");
    Objects.requireNonNull(receipt, "committed Entity receipt is required");
    if (!request.targetNamespace().equals(receipt.targetNamespace())
        || !Arrays.equals(request.binding().canonicalBytes(), receipt.authorizationBindingBytes())
        || !"COMMITTED_EMPTY".equals(receipt.outcome())) {
      throw new IllegalArgumentException(
          "Entity receipt differs from the complete original terminal-read binding");
    }
  }

  /** The correlation changes per transport read and never changes the retained operation. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      SelectedOwnerIntakeAuthorizationBinding binding) {
    public Request {
      if (schemaVersion != SCHEMA_VERSION
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || binding == null
          || !targetNamespace.equals(binding.targetNamespace())
          || binding.owner() != Owner.ENTITY_MANAGEMENT
          || !"account-entity-intake-authorization/v1".equals(binding.schema())
          || !"ENTITY_INTAKE_RETENTION".equals(binding.purpose())
          || binding.canonicalBytes().length == 0
          || binding.canonicalBytes().length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
        throw invalid();
      }
      DraftAuthorizationFenceBinding.requireUuid(readRequestId);
      if (collides(readRequestId, binding)) throw invalid();
    }

    public static Request create(
        String namespace, SelectedOwnerIntakeAuthorizationBinding binding) {
      Objects.requireNonNull(binding, "original finalized Account binding is required");
      UUID correlation;
      do {
        correlation = UUID.randomUUID();
      } while (collides(correlation, binding));
      return new Request(SCHEMA_VERSION, namespace, correlation, binding);
    }

    /** Account is the only intended reader of this Entity-owned terminal. */
    public String intendedReader() {
      return "spiffe://firemud/ns/" + targetNamespace + "/sa/account-service";
    }

    public String terminalReadPurpose() {
      return TERMINAL_READ_PURPOSE;
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof Request other
          && schemaVersion == other.schemaVersion
          && targetNamespace.equals(other.targetNamespace)
          && readRequestId.equals(other.readRequestId)
          && Arrays.equals(binding.canonicalBytes(), other.binding.canonicalBytes());
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          schemaVersion, targetNamespace, readRequestId, Arrays.hashCode(binding.canonicalBytes()));
    }

    private static boolean collides(
        UUID candidate, SelectedOwnerIntakeAuthorizationBinding binding) {
      return candidate.equals(binding.operationId())
          || candidate.equals(binding.fenceId())
          || candidate.equals(binding.intakeRequestId());
    }

    private static IllegalArgumentException invalid() {
      return new IllegalArgumentException("Invalid Entity selected-source terminal request");
    }
  }
}
