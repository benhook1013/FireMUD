package net.firedevops.firemud.common.account.sourceintake;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact lookup identity for World to read a finalized selected-owner Account authorization. */
public record SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence(Request request) {
  public static final int SCHEMA_VERSION = 1;

  public SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence {
    Objects.requireNonNull(request, "World closure authorization-read request is required");
  }

  /** The original binding is lookup evidence only; Account authentication establishes HELD. */
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
      Objects.requireNonNull(binding.owner(), "closed Entity or Automation owner is required");
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
      return "spiffe://firemud/ns/" + targetNamespace + "/sa/world-management-service";
    }

    public String closureReadPurpose() {
      return switch (binding.owner()) {
        case ENTITY_MANAGEMENT -> "ENTITY_INTAKE_WORLD_CLOSURE_READ";
        case AUTOMATION_SCRIPTING -> "AUTOMATION_INTAKE_WORLD_CLOSURE_READ";
        default -> throw invalid();
      };
    }

    private static boolean sameIdentity(
        UUID candidate, SelectedOwnerIntakeAuthorizationBinding binding) {
      return candidate.equals(binding.operationId())
          || candidate.equals(binding.fenceId())
          || candidate.equals(binding.intakeRequestId());
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Invalid selected-owner World closure authorization-read evidence");
  }
}
