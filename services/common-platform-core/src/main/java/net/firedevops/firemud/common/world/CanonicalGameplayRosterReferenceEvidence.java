package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;

/** Closed original-operation carrier. Neither RESERVED nor RECORDED is retirement authority. */
public final class CanonicalGameplayRosterReferenceEvidence {
  public static final int MAX_REQUEST_BYTES = 4096;
  public static final int MAX_RECEIPT_BYTES = 64 * 1024;

  private CanonicalGameplayRosterReferenceEvidence() {}

  public static GetCanonicalGameplayRosterReferenceRequest request(
      UUID operation,
      UUID account,
      UUID session,
      String namespace,
      CanonicalGameplayRosterTarget target) {
    return requireRequest(
        GetCanonicalGameplayRosterReferenceRequest.newBuilder()
            .setSchemaVersion(1)
            .setOperationUuid(operation.toString())
            .setCanonicalAccountUuid(account.toString())
            .setSessionUuid(session.toString())
            .setTargetNamespace(namespace)
            .setCanonicalTarget(target.toByteString())
            .build());
  }

  public static GetCanonicalGameplayRosterReferenceRequest requireRequest(
      GetCanonicalGameplayRosterReferenceRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != 1
        || !GrpcPeerIdentity.isValidNamespace(request.getTargetNamespace())) {
      throw new IllegalArgumentException("Closed bounded original roster selection required");
    }
    uuid(request.getOperationUuid());
    uuid(request.getCanonicalAccountUuid());
    uuid(request.getSessionUuid());
    target(request);
    return request;
  }

  public static CanonicalGameplayRosterTarget target(
      GetCanonicalGameplayRosterReferenceRequest request) {
    try {
      CanonicalGameplayRosterTarget target =
          CanonicalGameplayRosterTarget.parseFrom(request.getCanonicalTarget());
      if (!target.getUnknownFields().asMap().isEmpty()
          || !Arrays.equals(target.toByteArray(), request.getCanonicalTarget().toByteArray())
          || target.getCatalogRevision() <= 0
          || target.getPointerVersion() <= 0
          || target.getActiveWorldEpoch() <= 0
          || (target.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
              && target.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
          || target.getEntryPolicy()
              != CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY
          || !target.getWorldSlug().matches("[a-z0-9][a-z0-9-]{0,63}")
          || !target.getRealmSlug().matches("[a-z0-9][a-z0-9-]{0,63}")
          || !target.getPublishedPolicyDigest().matches("[0-9a-f]{64}")
          || !target.getPublishedOwnerProofDigest().matches("[0-9a-f]{64}")
          || !target.getAdmissionPointerSnapshotDigest().matches("[0-9a-f]{64}")
          || target.getPublishedReleaseBundleRef().isBlank()
          || target.getPublishedReleaseBundleRef().length() > 1024
          || !target
              .getPublishedReleaseBundleRef()
              .equals(target.getPublishedReleaseBundleRef().trim())
          || target.getPublishedReleaseBundleRef().codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Complete canonical roster target required");
      }
      uuid(target.getTenantUuid());
      uuid(target.getRealmUuid());
      uuid(target.getGameInstanceUuid());
      uuid(target.getCanonicalVersionUuid());
      uuid(target.getPlayableStateNamespaceUuid());
      return target;
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException("Canonical roster target is malformed", invalid);
    }
  }

  public static GetCanonicalGameplayRosterReferenceResponse response(
      GetCanonicalGameplayRosterReferenceRequest request,
      long fence,
      UUID targetFence,
      CanonicalGameplayRosterResponse receipt) {
    var response =
        GetCanonicalGameplayRosterReferenceResponse.newBuilder()
            .setRequest(requireRequest(request))
            .setOwnerFence(fence)
            .setTargetFenceUuid(targetFence.toString())
            .setState(
                receipt == null
                    ? CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RESERVED
                    : CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RECORDED);
    if (receipt != null) {
      response.setCanonicalRosterReceipt(receipt.toByteString());
    }
    return requireResponse(request, response.build());
  }

  public static net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput
      requireSelectionInput(
          net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput input) {
    Objects.requireNonNull(input, "input");
    if (input.getSchemaVersion() != 1
        || !input.getUnknownFields().asMap().isEmpty()
        || input.getSerializedSize() > 1024
        || input.getSelector().length() > 128
        || input.getMenuSnapshotUuid().isEmpty() != input.getMenuSnapshotDigest().isEmpty()) {
      throw new IllegalArgumentException("Closed bounded original selection input required");
    }
    if (!input.getMenuSnapshotUuid().isEmpty()) {
      uuid(input.getMenuSnapshotUuid());
      if (!input.getMenuSnapshotDigest().matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Exact menu snapshot digest required");
      }
    }
    return input;
  }

  public static net.firedevops.firemud.entitymanagement.v1
          .CanonicalGameplayRosterSelectedAssignmentResponse
      selectedAssignmentReceipt(GetCanonicalGameplayRosterReferenceResponse response) {
    try {
      if (response.getCanonicalSelectedAssignmentReceipt().isEmpty()
          || response.getCanonicalSelectedAssignmentReceipt().size() > 8192) {
        throw new IllegalArgumentException(
            "Complete original selected assignment receipt required");
      }
      var selected =
          net.firedevops.firemud.entitymanagement.v1
              .CanonicalGameplayRosterSelectedAssignmentResponse.parseFrom(
              response.getCanonicalSelectedAssignmentReceipt());
      var roster = receipt(response);
      if (!selected.getUnknownFields().asMap().isEmpty()
          || !selected.getSnapshot().getUnknownFields().asMap().isEmpty()
          || !selected.toByteString().equals(response.getCanonicalSelectedAssignmentReceipt())
          || !selected.getRequestUuid().equals(response.getRequest().getOperationUuid())
          || !selected
              .getCanonicalAccountUuid()
              .equals(response.getRequest().getCanonicalAccountUuid())
          || !selected.getSelectedCharacterUuid().equals(response.getSelectedCharacterUuid())
          || !selected.getTarget().equals(roster.getTarget())
          || !selected.getSnapshot().getSnapshotUuid().equals(roster.getSnapshotUuid())
          || !selected.getSnapshot().getSnapshotDigest().equals(roster.getSnapshotDigest())
          || !selected.getIntentDigest().matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Original selected assignment receipt changed binding");
      }
      uuid(selected.getAssignmentUuid());
      return selected;
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException(
          "Original selected assignment receipt is malformed", invalid);
    }
  }

  public static GetCanonicalGameplayRosterReferenceResponse requireResponse(
      GetCanonicalGameplayRosterReferenceRequest expected,
      GetCanonicalGameplayRosterReferenceResponse response) {
    requireRequest(expected);
    Objects.requireNonNull(response, "response");
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || !expected.equals(requireRequest(response.getRequest()))
        || response.getOwnerFence() <= 0
        || response.getCanonicalRosterReceipt().size() > MAX_RECEIPT_BYTES) {
      throw new IllegalArgumentException("Original roster selection owner binding is invalid");
    }
    switch (response.getState()) {
      case CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RESERVED -> {
        if (!response.getCanonicalRosterReceipt().isEmpty()) {
          throw new IllegalArgumentException("Reserved original selection cannot carry a receipt");
        }
      }
      case CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RECORDED -> receipt(response);
      case CANONICAL_GAMEPLAY_ROSTER_REFERENCE_REPLAY_CLOSED -> {
        if (response.getTerminalOutcome().isEmpty()) {
          throw new IllegalArgumentException("Closed replay requires original terminal outcome");
        }
      }
      case CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED -> requireRetirement(response);
      default -> throw new IllegalArgumentException("Unknown original selection state");
    }
    uuid(response.getTargetFenceUuid());
    if (!response.getRecoveryUuid().isEmpty()) {
      uuid(response.getRecoveryUuid());
    }
    if (!response.getTerminalOutcome().isEmpty()
        && !java.util.Set.of("MENU", "SELECTED", "DENIED")
            .contains(response.getTerminalOutcome())) {
      throw new IllegalArgumentException("Unknown original selection terminal outcome");
    }
    if (response.getState()
            != CanonicalGameplayRosterReferenceState.CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED
        && (!response.getRetirementUuid().isEmpty()
            || !response.getRetirementProofDigest().isEmpty()
            || !response.getSupersedingPointerDigest().isEmpty()
            || response.getSupersedingPointerVersion() != 0
            || response.getSupersedingCatalogRevision() != 0
            || response.getSupersedingWorldEpoch() != 0)) {
      throw new IllegalArgumentException("Unretired selection cannot supply retirement metadata");
    }
    if (response.hasSelectionInput()) {
      requireSelectionInput(response.getSelectionInput());
    }
    if (!response.getSelectedCharacterUuid().isEmpty()) {
      uuid(response.getSelectedCharacterUuid());
      if (!response.hasSelectionInput()
          || receipt(response).getActorsList().stream()
                  .filter(
                      actor -> actor.getCharacterUuid().equals(response.getSelectedCharacterUuid()))
                  .count()
              != 1L) {
        throw new IllegalArgumentException(
            "Original chosen actor must occur once in its recorded roster");
      }
    }
    if (!response.getCanonicalSelectedAssignmentReceipt().isEmpty()) {
      if (response.getSelectedCharacterUuid().isEmpty()) {
        throw new IllegalArgumentException(
            "Original assignment receipt requires a frozen chosen actor");
      }
      selectedAssignmentReceipt(response);
    }
    return response;
  }

  public static CanonicalGameplayRosterResponse receipt(
      GetCanonicalGameplayRosterReferenceResponse response) {
    try {
      ByteString bytes = response.getCanonicalRosterReceipt();
      if (bytes.isEmpty() || bytes.size() > MAX_RECEIPT_BYTES) {
        throw new IllegalArgumentException("Complete bounded roster receipt required");
      }
      var receipt = CanonicalGameplayRosterResponse.parseFrom(bytes);
      if (!receipt.getUnknownFields().asMap().isEmpty()
          || !bytes.equals(receipt.toByteString())
          || !receipt
              .getCanonicalAccountUuid()
              .equals(response.getRequest().getCanonicalAccountUuid())
          || !receipt.getTarget().equals(target(response.getRequest()))
          || !receipt.getSnapshotDigest().matches("[0-9a-f]{64}")
          || receipt.getActorsCount() > 100) {
        throw new IllegalArgumentException("Original selection receipt changed its binding");
      }
      uuid(receipt.getSnapshotUuid());
      java.util.Set<UUID> characters = new java.util.HashSet<>();
      for (var actor : receipt.getActorsList()) {
        if (!characters.add(uuid(actor.getCharacterUuid()))
            || actor.getActorKind()
                != net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                    .CANONICAL_ROSTER_ACTOR_KIND_PLAYER
            || actor.getDisplayName().isBlank()
            || !actor.getDisplayName().equals(actor.getDisplayName().trim())
            || actor.getDisplayName().codePointCount(0, actor.getDisplayName().length()) > 100
            || actor.getDisplayName().codePoints().anyMatch(Character::isISOControl)
            || !actor.getUnknownFields().asMap().isEmpty()) {
          throw new IllegalArgumentException("Closed actor receipt required");
        }
      }
      return receipt;
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException("Roster receipt is malformed", invalid);
    }
  }

  public static void requireDereference(GetCanonicalGameplayRosterReferenceResponse response) {
    requireResponse(response.getRequest(), response);
    if (!response.getRecoveryUuid().isEmpty()
        || "DENIED".equals(response.getTerminalOutcome())
        || (response.getState()
                != CanonicalGameplayRosterReferenceState
                    .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RESERVED
            && response.getState()
                != CanonicalGameplayRosterReferenceState
                    .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RECORDED)) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_DEREFERENCE_CLOSED");
    }
  }

  public static GetCanonicalGameplayRosterReferenceResponse sealRetirement(
      GetCanonicalGameplayRosterReferenceResponse response) {
    var unsigned = response.toBuilder().clearRetirementProofDigest().build();
    var sealed =
        unsigned.toBuilder().setRetirementProofDigest(digest(unsigned.toByteArray())).build();
    return requireResponse(response.getRequest(), sealed);
  }

  public static GetCanonicalGameplayRosterReferenceResponse requireRetirement(
      GetCanonicalGameplayRosterReferenceResponse response) {
    requireRequest(response.getRequest());
    if (!response.getUnknownFields().asMap().isEmpty()
        || response.getOwnerFence() <= 0
        || response.getState()
            != CanonicalGameplayRosterReferenceState.CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED
        || !java.util.Set.of("MENU", "SELECTED", "DENIED").contains(response.getTerminalOutcome())
        || !response.getSupersedingPointerDigest().matches("[0-9a-f]{64}")
        || response.getSupersedingPointerVersion()
            <= target(response.getRequest()).getPointerVersion()
        || response.getSupersedingCatalogRevision()
            < target(response.getRequest()).getCatalogRevision()
        || response.getSupersedingWorldEpoch() < target(response.getRequest()).getActiveWorldEpoch()
        || !response
            .getRetirementProofDigest()
            .equals(
                digest(response.toBuilder().clearRetirementProofDigest().build().toByteArray()))) {
      throw new IllegalArgumentException("Complete exact owner retirement proof required");
    }
    uuid(response.getRetirementUuid());
    uuid(response.getTargetFenceUuid());
    return response;
  }

  private static String digest(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  public static UUID uuid(String value) {
    UUID parsed = UUID.fromString(Objects.requireNonNull(value, "uuid"));
    if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Canonical non-nil UUID required");
    }
    return parsed;
  }
}
