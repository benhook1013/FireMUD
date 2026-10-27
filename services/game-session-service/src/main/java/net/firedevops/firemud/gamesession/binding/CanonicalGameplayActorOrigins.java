package net.firedevops.firemud.gamesession.binding;

import java.util.UUID;
import net.firedevops.firemud.common.entity.CanonicalPlayerCreatedRuntimeReferenceEvidence;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRuntimeActorReference.ActorOrigin;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRuntimeActorReference.AssignmentOrigin;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRuntimeActorReference.CreationOrigin;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;

/** Purpose-preserving values only; the independent owner reads establish their provenance. */
public final class CanonicalGameplayActorOrigins {
  private CanonicalGameplayActorOrigins() {}

  public static ActorOrigin fromWorldRequest(CanonicalInitialPlayerLocationRequest request) {
    if (request.getSchemaVersion() == 1 && !request.hasPlayerCreatedOrigin()) {
      UUID operation = UUID.fromString(request.getAssignmentUuid());
      if (!operation.toString().equals(request.getAssignmentUuid())) throw invalid();
      return new AssignmentOrigin(operation, request.getAssignmentIntentDigest());
    }
    if (request.getSchemaVersion() == 2 && request.hasPlayerCreatedOrigin()
        && request.getAssignmentUuid().isEmpty() && request.getAssignmentIntentDigest().isEmpty()) {
      return new CreationOrigin(request.getPlayerCreatedOrigin());
    }
    throw invalid();
  }

  public static ActorOrigin fromStored(
      UUID assignmentId, String assignmentDigest, String creationJson) {
    if (assignmentId == null && assignmentDigest == null && creationJson == null) return null;
    if (creationJson == null && assignmentId != null && assignmentDigest != null) {
      return new AssignmentOrigin(assignmentId, assignmentDigest);
    }
    if (assignmentId == null && assignmentDigest == null && creationJson != null) {
      return new CreationOrigin(
          CanonicalPlayerCreatedRuntimeReferenceEvidence.originFromStoredJson(creationJson));
    }
    throw invalid();
  }

  public static UUID assignmentId(ActorOrigin origin) {
    return origin instanceof AssignmentOrigin assignment ? assignment.assignmentId() : null;
  }

  public static String assignmentDigest(ActorOrigin origin) {
    return origin instanceof AssignmentOrigin assignment ? assignment.intentDigest() : null;
  }

  public static String creationJson(ActorOrigin origin) {
    return origin instanceof CreationOrigin created
        ? CanonicalPlayerCreatedRuntimeReferenceEvidence.storedOriginJson(created.original()) : null;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Exactly one original Entity actor origin is required");
  }
}
