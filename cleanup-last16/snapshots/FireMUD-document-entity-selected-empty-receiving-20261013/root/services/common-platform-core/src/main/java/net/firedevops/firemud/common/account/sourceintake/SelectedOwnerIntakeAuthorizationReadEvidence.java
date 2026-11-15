package net.firedevops.firemud.common.account.sourceintake;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact lookup identity for reading a finalized selected-owner Account authorization. */
public record SelectedOwnerIntakeAuthorizationReadEvidence(Request request) {
  public static final int SCHEMA_VERSION = 1;

  public SelectedOwnerIntakeAuthorizationReadEvidence {
    Objects.requireNonNull(request, "authorization-read request is required");
  }

  /** The binding is lookup evidence only; Account authentication establishes the HELD result. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      SelectedOwnerIntakeAuthorizationBinding binding) {
    public Request {
      if (schemaVersion != SCHEMA_VERSION
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || binding == null
          || !targetNamespace.equals(binding.targetNamespace())) {
        throw invalid();
      }
      DraftAuthorizationFenceBinding.requireUuid(readRequestId);
      if (readRequestId.equals(binding.operationId())
          || readRequestId.equals(binding.fenceId())
          || readRequestId.equals(binding.intakeRequestId())) {
        throw invalid();
      }
      // These accessors reject any owner outside the closed Entity and Automation domains.
      Objects.requireNonNull(binding.intendedReader());
      Objects.requireNonNull(binding.purpose());
    }

    public static Request create(
        String namespace, SelectedOwnerIntakeAuthorizationBinding binding) {
      Objects.requireNonNull(binding, "finalized selected-owner authorization is required");
      UUID correlation;
      do {
        correlation = UUID.randomUUID();
      } while (sameIdentity(correlation, binding));
      return new Request(SCHEMA_VERSION, namespace, correlation, binding);
    }

    public String intendedReader() {
      return binding.intendedReader();
    }

    public String retentionPurpose() {
      return binding.purpose();
    }

    private static boolean sameIdentity(
        UUID candidate, SelectedOwnerIntakeAuthorizationBinding binding) {
      return candidate.equals(binding.operationId())
          || candidate.equals(binding.fenceId())
          || candidate.equals(binding.intakeRequestId());
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid selected-owner authorization-read evidence");
  }
}
