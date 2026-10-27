package net.firedevops.firemud.gamesession.client;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.entity.CanonicalPlayerCreatedRuntimeReferenceEvidence;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorOrigin;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;

/** Constructed only by the exact authenticated, purpose-specific Entity response readers. */
public sealed interface CanonicalGameplayRuntimeActorReference
    permits CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference,
        CanonicalPlayerCreatedRuntimeReferenceClient.RuntimeActorReference {
  String workloadNamespace();

  CanonicalRuntimeEntityId runtimeEntityId();

  long projectionGeneration();

  long projectionFence();

  UUID canonicalAccountUuid();

  UUID characterUuid();

  UUID playableStateNamespaceId();

  ActorOrigin origin();

  boolean matchesWorldRequest(CanonicalInitialPlayerLocationRequest request);

  byte[] canonicalEvidenceBytes();

  sealed interface ActorOrigin permits AssignmentOrigin, CreationOrigin {}

  record AssignmentOrigin(UUID assignmentId, String intentDigest) implements ActorOrigin {
    public AssignmentOrigin {
      Objects.requireNonNull(assignmentId, "assignmentId");
      if (assignmentId.equals(new UUID(0L, 0L))
          || intentDigest == null || !intentDigest.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Exact original assignment identity required");
      }
    }
  }

  record CreationOrigin(PlayerCreatedActorOrigin original) implements ActorOrigin {
    public CreationOrigin {
      CanonicalPlayerCreatedRuntimeReferenceEvidence.requireOrigin(original);
    }
  }
}
