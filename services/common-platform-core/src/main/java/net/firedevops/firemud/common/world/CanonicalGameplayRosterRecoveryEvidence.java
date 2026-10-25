package net.firedevops.firemud.common.world;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;

/**
 * Integrity of exact original-selection recovery carriers; decoding does not authenticate peers.
 */
public final class CanonicalGameplayRosterRecoveryEvidence {
  public static final int MAX_REQUEST_BYTES = 8192;
  public static final int MAX_RESPONSE_BYTES = 128 * 1024;

  private CanonicalGameplayRosterRecoveryEvidence() {}

  public static CanonicalGameplayRosterRecoveryRequest requireRequest(
      CanonicalGameplayRosterRecoveryRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSchemaVersion() != 1
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasOriginalSelection()
        || request.getSerializedSize() > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException("Closed original-selection recovery request required");
    }
    CanonicalGameplayRosterReferenceEvidence.requireRequest(request.getOriginalSelection());
    CanonicalGameplayRosterReferenceEvidence.uuid(request.getRecoveryUuid());
    return request;
  }

  public static GetCanonicalGameplayRosterReferenceResponse requireOwnerRecovery(
      CanonicalGameplayRosterRecoveryRequest request,
      GetCanonicalGameplayRosterReferenceResponse owner) {
    requireRequest(request);
    CanonicalGameplayRosterReferenceEvidence.requireResponse(request.getOriginalSelection(), owner);
    if (!request.getRecoveryUuid().equals(owner.getRecoveryUuid())) {
      throw new IllegalArgumentException("Original owner recovery fence differs");
    }
    return owner;
  }

  public static CanonicalGameplayRosterRecoveryResponse response(
      CanonicalGameplayRosterRecoveryRequest request,
      UUID admission,
      long ownerFence,
      UUID targetFence,
      CanonicalGameplayRosterResponse receipt) {
    var result =
        CanonicalGameplayRosterRecoveryResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(requireRequest(request))
            .setAdmissionUuid(admission.toString())
            .setOwnerFence(ownerFence)
            .setTargetFenceUuid(targetFence.toString());
    if (receipt != null) result.setCanonicalRosterReceipt(receipt.toByteString());
    return requireResponse(request, result.build());
  }

  public static CanonicalGameplayRosterRecoveryResponse requireResponse(
      CanonicalGameplayRosterRecoveryRequest expected,
      CanonicalGameplayRosterRecoveryResponse response) {
    requireRequest(expected);
    Objects.requireNonNull(response, "response");
    if (response.getSchemaVersion() != 1
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.hasRequest()
        || !expected.equals(requireRequest(response.getRequest()))
        || response.getOwnerFence() <= 0) {
      throw new IllegalArgumentException("Closed exact admission recovery response required");
    }
    CanonicalGameplayRosterReferenceEvidence.uuid(response.getAdmissionUuid());
    CanonicalGameplayRosterReferenceEvidence.uuid(response.getTargetFenceUuid());
    if (!response.getCanonicalRosterReceipt().isEmpty()) {
      // Reuse the canonical original-receipt parser and exact target/account binding.
      CanonicalGameplayRosterReferenceEvidence.receipt(
          GetCanonicalGameplayRosterReferenceResponse.newBuilder()
              .setRequest(expected.getOriginalSelection())
              .setCanonicalRosterReceipt(response.getCanonicalRosterReceipt())
              .build());
    }
    return response;
  }
}
