package net.firedevops.firemud.common.account.sourceintake;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Preliminary Account confirmation of the exact Entity/Automation source-read scope. */
public record SelectedOwnerIntakeSourceReadEvidence(Request request) {
  public static final int SCHEMA_VERSION = 1;

  public SelectedOwnerIntakeSourceReadEvidence {
    Objects.requireNonNull(request, "source-read request is required");
  }

  /**
   * This request transports integrity input only; it does not assert finalized retention rights.
   */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      String intendedReader,
      String purpose,
      SelectedOwnerIntakeSourceReadScope scope) {
    public Request {
      if (schemaVersion != SCHEMA_VERSION
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || scope == null) {
        throw invalid();
      }
      DraftAuthorizationFenceBinding.requireUuid(readRequestId);
      if ((scope.owner() != Owner.ENTITY_MANAGEMENT && scope.owner() != Owner.AUTOMATION_SCRIPTING)
          || !targetNamespace.equals(scope.targetNamespace())
          || !scope.intendedReader().equals(intendedReader)
          || !scope.purpose().equals(purpose)) {
        throw invalid();
      }
      if (readRequestId.equals(scope.operationId())
          || readRequestId.equals(scope.fenceId())
          || readRequestId.equals(scope.intakeRequestId())
          || readRequestId.equals(scope.actorAccountId())
          || readRequestId.equals(scope.selected().requestId())
          || readRequestId.equals(scope.selected().commitId())) {
        throw invalid();
      }
    }

    public static Request create(String namespace, SelectedOwnerIntakeSourceReadScope scope) {
      Objects.requireNonNull(scope, "source-read scope is required");
      UUID readRequestId;
      do {
        readRequestId = UUID.randomUUID();
      } while (sameIdentity(readRequestId, scope));
      return new Request(
          SCHEMA_VERSION, namespace, readRequestId, scope.intendedReader(), scope.purpose(), scope);
    }

    private static boolean sameIdentity(UUID candidate, SelectedOwnerIntakeSourceReadScope scope) {
      return candidate.equals(scope.operationId())
          || candidate.equals(scope.fenceId())
          || candidate.equals(scope.intakeRequestId())
          || candidate.equals(scope.actorAccountId())
          || candidate.equals(scope.selected().requestId())
          || candidate.equals(scope.selected().commitId());
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid preliminary selected-owner source-read evidence");
  }
}
