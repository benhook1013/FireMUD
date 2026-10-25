package net.firedevops.firemud.worldmanagement.client;

import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentReceiptStatus;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptResponse;

/** Closed response shape shared by World readers of Entity's immutable assignment receipt. */
public final class PreseededActorAssignmentReceiptShape {
  private static final int MAX_WIRE_BYTES = 16 * 1024;

  private PreseededActorAssignmentReceiptShape() {}

  public static boolean matches(
      ReadPreseededActorAssignmentReceiptRequest request,
      ReadPreseededActorAssignmentReceiptResponse response) {
    return request != null
        && request.hasExpectedTarget()
        && response != null
        && response.getSerializedSize() <= MAX_WIRE_BYTES
        && response.getUnknownFields().asMap().isEmpty()
        && response.getSchemaVersion() == 1
        && response.getPlacementOperationUuid().equals(request.getPlacementOperationUuid())
        && response.getAssignmentUuid().equals(request.getAssignmentUuid())
        && response.getCanonicalAccountUuid().equals(request.getCanonicalAccountUuid())
        && response.getCharacterUuid().equals(request.getCharacterUuid())
        && response.getIntentDigest().equals(request.getIntentDigest())
        && response.hasExpectedTarget()
        && response.getExpectedTarget().equals(request.getExpectedTarget())
        && response.getExpectedTarget().getUnknownFields().asMap().isEmpty()
        && response.hasCorePayload()
        && response.getCorePayload().getUnknownFields().asMap().isEmpty()
        && response.getCorePayload().getActorKind()
            == CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER
        && validDisplayName(response.getCorePayload().getDisplayName())
        && response.getStatus()
            == PreseededActorAssignmentReceiptStatus
                .PRESEEDED_ACTOR_ASSIGNMENT_RECEIPT_STATUS_ASSIGNED;
  }

  private static boolean validDisplayName(String value) {
    return value != null
        && !value.isBlank()
        && value.equals(value.trim())
        && value.codePointCount(0, value.length()) <= 100
        && value.codePoints().noneMatch(Character::isISOControl);
  }
}
