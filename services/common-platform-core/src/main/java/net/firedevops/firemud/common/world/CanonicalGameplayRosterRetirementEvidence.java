package net.firedevops.firemud.common.world;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState;
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
    if (observed.getState()
        != CanonicalGameplayRosterReferenceState.CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED) {
      throw new IllegalArgumentException("Complete exact owner retirement proof required");
    }
    GetCanonicalGameplayRosterReferenceRequest observedRequest = observed.getRequest();
    CanonicalGameplayRosterResponse observedRoster =
        CanonicalGameplayRosterReferenceEvidence.requireResponseSnapshot(observedRequest, observed);
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
      CanonicalGameplayRosterResponse roster = observedRoster;
      if (roster == null) {
        CanonicalGameplayRosterReferenceEvidence.snapshotReceipt(observed);
        throw new IllegalArgumentException(
            "Observed retirement does not bind the exact roster snapshot");
      }
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
    return digest(requireAcknowledgement(request));
  }

  public static CanonicalGameplayRosterRetirementAcknowledgementResponse response(
      CanonicalGameplayRosterRetirementAcknowledgementRequest request,
      UUID acknowledgementId,
      long effectiveMinimum) {
    var validatedRequest = requireAcknowledgement(request);
    requireUuid(acknowledgementId, "acknowledgementId");
    if (effectiveMinimum < validatedRequest.getMinimumRetentionMillis()) {
      throw new IllegalArgumentException(
          "Effective retention minimum is below the requested minimum");
    }
    String requestDigest = digest(validatedRequest);
    var response =
        CanonicalGameplayRosterRetirementAcknowledgementResponse.newBuilder()
            .setSchemaVersion(1)
            .setObservationUuid(validatedRequest.getObservationUuid())
            .setAcknowledgementUuid(acknowledgementId.toString())
            .setRequestDigest(requestDigest)
            .setMinimumRetentionMillis(effectiveMinimum)
            .build();
    return requireAcknowledgementResponseValidated(validatedRequest, response, requestDigest);
  }

  public static CanonicalGameplayRosterRetirementAcknowledgementResponse
      requireAcknowledgementResponse(
          CanonicalGameplayRosterRetirementAcknowledgementRequest request,
          CanonicalGameplayRosterRetirementAcknowledgementResponse response) {
    var validatedRequest = requireAcknowledgement(request);
    return requireAcknowledgementResponseValidated(
        validatedRequest, response, digest(validatedRequest));
  }

  private static CanonicalGameplayRosterRetirementAcknowledgementResponse
      requireAcknowledgementResponseValidated(
          CanonicalGameplayRosterRetirementAcknowledgementRequest request,
          CanonicalGameplayRosterRetirementAcknowledgementResponse response,
          String requestDigest) {
    Objects.requireNonNull(response, "response");
    if (response.getSchemaVersion() != 1
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.getObservationUuid().equals(request.getObservationUuid())
        || !response.getRequestDigest().equals(requestDigest)
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
    var confirmation =
        CanonicalGameplayRosterRetirementConfirmationRequest.newBuilder()
            .setSchemaVersion(1)
            .setAcknowledgementRequest(request)
            .setAcknowledgement(response)
            .build();
    requireConfirmationShape(confirmation);
    requireConfirmationPolicyBound(confirmation);
    return confirmation;
  }

  public static CanonicalGameplayRosterRetirementConfirmationRequest requireConfirmation(
      CanonicalGameplayRosterRetirementConfirmationRequest confirmation) {
    requireConfirmationShape(confirmation);
    requireAcknowledgementResponse(
        confirmation.getAcknowledgementRequest(), confirmation.getAcknowledgement());
    requireConfirmationPolicyBound(confirmation);
    return confirmation;
  }

  private static void requireConfirmationShape(
      CanonicalGameplayRosterRetirementConfirmationRequest confirmation) {
    Objects.requireNonNull(confirmation, "confirmation");
    if (confirmation.getSchemaVersion() != 1
        || !confirmation.getUnknownFields().asMap().isEmpty()
        || confirmation.getSerializedSize() > MAX_PLAYER_CREATED_CONFIRMATION_BYTES
        || !confirmation.hasAcknowledgementRequest()
        || !confirmation.hasAcknowledgement()) {
      throw new IllegalArgumentException("Closed bounded roster-retirement confirmation required");
    }
  }

  private static void requireConfirmationPolicyBound(
      CanonicalGameplayRosterRetirementConfirmationRequest confirmation) {
    if (confirmation.getSerializedSize()
        > acknowledgementLimit(confirmation.getAcknowledgementRequest().getOriginalSelection())
            + MAX_RESPONSE_BYTES
            + 64) {
      throw new IllegalArgumentException(
          "Retirement confirmation exceeds its policy-specific bound");
    }
  }

  public static CanonicalGameplayRosterRetirementConfirmationResponse confirmationResponse(
      CanonicalGameplayRosterRetirementConfirmationRequest confirmation) {
    var validatedConfirmation = requireConfirmation(confirmation);
    String requestDigest = digest(validatedConfirmation);
    var response =
        CanonicalGameplayRosterRetirementConfirmationResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequestDigest(requestDigest)
            .build();
    return requireConfirmationResponseValidated(response, requestDigest);
  }

  public static CanonicalGameplayRosterRetirementConfirmationResponse requireConfirmationResponse(
      CanonicalGameplayRosterRetirementConfirmationRequest confirmation,
      CanonicalGameplayRosterRetirementConfirmationResponse response) {
    var validatedConfirmation = requireConfirmation(confirmation);
    return requireConfirmationResponseValidated(response, digest(validatedConfirmation));
  }

  private static CanonicalGameplayRosterRetirementConfirmationResponse
      requireConfirmationResponseValidated(
          CanonicalGameplayRosterRetirementConfirmationResponse response, String requestDigest) {
    Objects.requireNonNull(response, "response");
    if (response.getSchemaVersion() != 1
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.getRequestDigest().equals(requestDigest)) {
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

  private static String digest(MessageLite message) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (DigestOutputStream digestOutput =
          new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
        CodedOutputStream codedOutput = CodedOutputStream.newInstance(digestOutput);
        message.writeTo(codedOutput);
        codedOutput.flush();
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    } catch (IOException failure) {
      throw new IllegalStateException("Unable to hash canonical protobuf", failure);
    }
  }
}
