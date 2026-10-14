package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.account.v1.InitialGameplayLoginTokenWitness;
import net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest;

/**
 * Non-admitting retained lifecycle evidence. Neither a witness nor a previous receipt proves
 * current physical availability, authenticates a socket, or reconstructs a credential.
 */
public record InitialGameplayLoginJournalSnapshot(
    InitialGameplayLoginAttemptCoordinates coordinates,
    boolean abandoned,
    Optional<InitialGameplayLoginTokenWitness> verifiedWitness,
    Optional<RetireInitialGameplayLoginRequest> frozenRetirementRequest,
    Optional<InitialGameplayLoginDisposalResult> retainedReceipt) {
  public InitialGameplayLoginJournalSnapshot {
    Objects.requireNonNull(coordinates, "coordinates");
    Objects.requireNonNull(verifiedWitness, "verifiedWitness");
    Objects.requireNonNull(frozenRetirementRequest, "frozenRetirementRequest");
    Objects.requireNonNull(retainedReceipt, "retainedReceipt");
    verifiedWitness.ifPresent(
        witness ->
            InitialGameplayLoginDisposalResult.requireOriginalRequest(
                request(coordinates, witness)));
    frozenRetirementRequest.ifPresent(
        request -> {
          InitialGameplayLoginDisposalResult.requireOriginalRequest(request);
          requireOriginal(coordinates, request.getRequestId(), request.getCallerContextId());
          if (!abandoned
              || request.hasTokenWitness()
                  && !verifiedWitness.filter(request.getTokenWitness()::equals).isPresent()) {
            throw new IllegalArgumentException(
                "Invalid retained original LOGIN retirement request");
          }
        });
    retainedReceipt.ifPresent(
        receipt -> {
          requireOriginal(
              coordinates, receipt.requestId().toString(), receipt.callerContextId().toString());
          if (!abandoned || frozenRetirementRequest.isEmpty()) {
            throw new IllegalArgumentException(
                "Original LOGIN receipt requires frozen abandonment");
          }
        });
  }

  public static RetireInitialGameplayLoginRequest request(
      InitialGameplayLoginAttemptCoordinates coordinates,
      InitialGameplayLoginTokenWitness witness) {
    var builder =
        RetireInitialGameplayLoginRequest.newBuilder()
            .setRequestId(coordinates.requestId().toString())
            .setCallerContextId(coordinates.callerContextId().toString());
    if (witness != null) {
      builder.setTokenWitness(witness);
    }
    var request = builder.build();
    InitialGameplayLoginDisposalResult.requireOriginalRequest(request);
    return request;
  }

  private static void requireOriginal(
      InitialGameplayLoginAttemptCoordinates coordinates, String requestId, String contextId) {
    if (!coordinates.requestId().toString().equals(requestId)
        || !coordinates.callerContextId().toString().equals(contextId)) {
      throw new IllegalArgumentException(
          "Retained LOGIN evidence differs from its original coordinates");
    }
  }
}
