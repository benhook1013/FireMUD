package net.firedevops.firemud.common.world;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;

/** Closed value checks for transferring a sealed roster-retirement observation to Game Session. */
public final class CanonicalGameplayRosterRetirementEvidence {
  public static final int MAX_REQUEST_BYTES = 128 * 1024;
  public static final int MAX_RESPONSE_BYTES = 4096;
  public static final int MAX_PLAYER_CREATED_REQUEST_BYTES = 195 * 1024 * 1024;
  public static final int MAX_CONFIRMATION_BYTES = MAX_REQUEST_BYTES + MAX_RESPONSE_BYTES + 64;
  public static final int MAX_PLAYER_CREATED_CONFIRMATION_BYTES =
      MAX_PLAYER_CREATED_REQUEST_BYTES + MAX_RESPONSE_BYTES + 64;

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  private CanonicalGameplayRosterRetirementEvidence() {}

  public static CanonicalGameplayRosterRetirementAcknowledgementRequest requireAcknowledgement(
      CanonicalGameplayRosterRetirementAcknowledgementRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSchemaVersion() != 1
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSerializedSize() > MAX_PLAYER_CREATED_REQUEST_BYTES
        || !request.hasOriginalSelection()
        || !request.hasObservedRetirement()
        || request.getMinimumRetentionMillis() <= 0) {
      throw new IllegalArgumentException(
          "Closed bounded roster-retirement acknowledgement required");
    }
    GetCanonicalGameplayRosterReferenceRequest original =
        CanonicalGameplayRosterReferenceEvidence.requireRequest(request.getOriginalSelection());
    if (request.getSerializedSize() > acknowledgementLimit(original)) {
      throw new IllegalArgumentException(
          "Retirement acknowledgement exceeds its policy-specific bound");
    }
    GetCanonicalGameplayRosterReferenceResponse observed = request.getObservedRetirement();
    if (!observed.hasRequest()) {
      throw new IllegalArgumentException("Complete observed retirement proof required");
    }
    GetCanonicalGameplayRosterReferenceRequest observedRequest =
        CanonicalGameplayRosterReferenceEvidence.requireRequest(observed.getRequest());
    CanonicalGameplayRosterReferenceEvidence.requireResponse(observedRequest, observed);
    CanonicalGameplayRosterReferenceEvidence.requireRetirement(observed);
    CanonicalGameplayRosterRetirementEvidence.uuid(request.getObservationUuid());
    CanonicalGameplayRosterRetirementEvidence.uuid(request.getAdmissionUuid());

    if (!original.getTargetNamespace().equals(observedRequest.getTargetNamespace())
        || !original.getCanonicalAccountUuid().equals(observedRequest.getCanonicalAccountUuid())
        || !original.getCanonicalTarget().equals(observedRequest.getCanonicalTarget())
        || !original
            .getCanonicalPlayerCreatedSourceIdentity()
            .equals(observedRequest.getCanonicalPlayerCreatedSourceIdentity())) {
      throw new IllegalArgumentException("Retirement proof changed exact account or target fence");
    }
    boolean sameOperation = original.getOperationUuid().equals(observedRequest.getOperationUuid());
    if (sameOperation && !original.equals(observedRequest)) {
      throw new IllegalArgumentException("Same retirement operation changed its original request");
    }

    boolean hasSnapshotUuid = !request.getSnapshotUuid().isEmpty();
    boolean hasSnapshotDigest = !request.getSnapshotDigest().isEmpty();
    if (hasSnapshotUuid != hasSnapshotDigest) {
      throw new IllegalArgumentException(
          "Roster snapshot UUID and digest must be supplied together");
    }
    if (hasSnapshotUuid) {
      CanonicalGameplayRosterReferenceEvidence.uuid(request.getSnapshotUuid());
      requireDigest(request.getSnapshotDigest());
      CanonicalGameplayRosterResponse roster =
          CanonicalGameplayRosterReferenceEvidence.snapshotReceipt(observed);
      if (!roster.getSnapshotUuid().equals(request.getSnapshotUuid())
          || !roster.getSnapshotDigest().equals(request.getSnapshotDigest())
          || !roster.getCanonicalAccountUuid().equals(original.getCanonicalAccountUuid())
          || !roster
              .getTarget()
              .equals(CanonicalGameplayRosterReferenceEvidence.target(original))) {
        throw new IllegalArgumentException(
            "Observed retirement does not bind the exact roster snapshot");
      }
      // A different original may anchor this ACK only through the exact shared snapshot and
      // the already validated sealed account/target fence above.
    } else if (!sameOperation
        || !original.equals(observedRequest)
        || !observed.getCanonicalRosterReceipt().isEmpty()
        || !observed.getCanonicalPlayerCreatedRosterReceipt().isEmpty()) {
      throw new IllegalArgumentException(
          "Unbound retirement acknowledgement requires its own original proof and no roster receipt");
    }
    return request;
  }

  public static String requestDigest(
      CanonicalGameplayRosterRetirementAcknowledgementRequest request) {
    return digest(requireAcknowledgement(request).toByteArray());
  }

  public static CanonicalGameplayRosterRetirementAcknowledgementResponse response(
      CanonicalGameplayRosterRetirementAcknowledgementRequest request,
      UUID acknowledgementId,
      long effectiveMinimum) {
    requireAcknowledgement(request);
    requireUuid(acknowledgementId, "acknowledgementId");
    if (effectiveMinimum < request.getMinimumRetentionMillis()) {
      throw new IllegalArgumentException(
          "Effective retention minimum is below the requested minimum");
    }
    return requireAcknowledgementResponse(
        request,
        CanonicalGameplayRosterRetirementAcknowledgementResponse.newBuilder()
            .setSchemaVersion(1)
            .setObservationUuid(request.getObservationUuid())
            .setAcknowledgementUuid(acknowledgementId.toString())
            .setRequestDigest(requestDigest(request))
            .setMinimumRetentionMillis(effectiveMinimum)
            .build());
  }

  public static CanonicalGameplayRosterRetirementAcknowledgementResponse
      requireAcknowledgementResponse(
          CanonicalGameplayRosterRetirementAcknowledgementRequest request,
          CanonicalGameplayRosterRetirementAcknowledgementResponse response) {
    requireAcknowledgement(request);
    Objects.requireNonNull(response, "response");
    if (response.getSchemaVersion() != 1
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.getObservationUuid().equals(request.getObservationUuid())
        || !response.getRequestDigest().equals(requestDigest(request))
        || response.getMinimumRetentionMillis() < request.getMinimumRetentionMillis()) {
      throw new IllegalArgumentException("Retirement acknowledgement response binding is invalid");
    }
    uuid(response.getObservationUuid());
    uuid(response.getAcknowledgementUuid());
    requireDigest(response.getRequestDigest());
    return response;
  }

  public static CanonicalGameplayRosterRetirementConfirmationRequest confirmation(
      CanonicalGameplayRosterRetirementAcknowledgementRequest request,
      CanonicalGameplayRosterRetirementAcknowledgementResponse response) {
    requireAcknowledgementResponse(request, response);
    return requireConfirmation(
        CanonicalGameplayRosterRetirementConfirmationRequest.newBuilder()
            .setSchemaVersion(1)
            .setAcknowledgementRequest(request)
            .setAcknowledgement(response)
            .build());
  }

  public static CanonicalGameplayRosterRetirementConfirmationRequest requireConfirmation(
      CanonicalGameplayRosterRetirementConfirmationRequest confirmation) {
    Objects.requireNonNull(confirmation, "confirmation");
    if (confirmation.getSchemaVersion() != 1
        || !confirmation.getUnknownFields().asMap().isEmpty()
        || confirmation.getSerializedSize() > MAX_PLAYER_CREATED_CONFIRMATION_BYTES
        || !confirmation.hasAcknowledgementRequest()
        || !confirmation.hasAcknowledgement()) {
      throw new IllegalArgumentException("Closed bounded roster-retirement confirmation required");
    }
    requireAcknowledgementResponse(
        confirmation.getAcknowledgementRequest(), confirmation.getAcknowledgement());
    if (confirmation.getSerializedSize()
        > acknowledgementLimit(confirmation.getAcknowledgementRequest().getOriginalSelection())
            + MAX_RESPONSE_BYTES
            + 64) {
      throw new IllegalArgumentException(
          "Retirement confirmation exceeds its policy-specific bound");
    }
    return confirmation;
  }

  public static CanonicalGameplayRosterRetirementConfirmationResponse confirmationResponse(
      CanonicalGameplayRosterRetirementConfirmationRequest confirmation) {
    requireConfirmation(confirmation);
    return requireConfirmationResponse(
        confirmation,
        CanonicalGameplayRosterRetirementConfirmationResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequestDigest(digest(confirmation.toByteArray()))
            .build());
  }

  public static CanonicalGameplayRosterRetirementConfirmationResponse requireConfirmationResponse(
      CanonicalGameplayRosterRetirementConfirmationRequest confirmation,
      CanonicalGameplayRosterRetirementConfirmationResponse response) {
    requireConfirmation(confirmation);
    Objects.requireNonNull(response, "response");
    if (response.getSchemaVersion() != 1
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.getRequestDigest().equals(digest(confirmation.toByteArray()))) {
      throw new IllegalArgumentException("Retirement confirmation response binding is invalid");
    }
    requireDigest(response.getRequestDigest());
    return response;
  }

  private static int acknowledgementLimit(GetCanonicalGameplayRosterReferenceRequest original) {
    return CanonicalGameplayRosterReferenceEvidence.target(original).getEntryPolicy()
            == CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED
        ? MAX_PLAYER_CREATED_REQUEST_BYTES
        : MAX_REQUEST_BYTES;
  }

  private static UUID uuid(String value) {
    return CanonicalGameplayRosterReferenceEvidence.uuid(value);
  }

  private static void requireUuid(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(label + " must be non-nil");
    }
  }

  private static void requireDigest(String digest) {
    if (!SHA256.matcher(digest).matches()) {
      throw new IllegalArgumentException("Lowercase SHA-256 digest required");
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }
}
