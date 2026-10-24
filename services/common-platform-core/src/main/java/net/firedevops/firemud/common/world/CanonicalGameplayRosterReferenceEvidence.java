package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;

/** Closed original-operation carrier. Neither RESERVED nor RECORDED is retirement authority. */
public final class CanonicalGameplayRosterReferenceEvidence {
  public static final int MAX_REQUEST_BYTES = 4096;
  public static final int MAX_RECEIPT_BYTES = 64 * 1024;
  public static final int MAX_SELECTED_ASSIGNMENT_BYTES = 8192;
  public static final int MAX_RESPONSE_BYTES = 128 * 1024;
  public static final int MAX_PLAYER_CREATED_RECEIPT_BYTES =
      CanonicalPlayerCreatedRosterEvidence.MAX_ROSTER_RECEIPT_BYTES;
  public static final int MAX_SELECTED_PLAYER_CREATED_BYTES =
      CanonicalPlayerCreatedRosterEvidence.MAX_SELECTED_RECEIPT_BYTES;
  public static final int MAX_PLAYER_CREATED_RESPONSE_BYTES = 194 * 1024 * 1024;

  private CanonicalGameplayRosterReferenceEvidence() {}

  public static GetCanonicalGameplayRosterReferenceRequest request(
      UUID operation,
      UUID account,
      UUID session,
      String namespace,
      CanonicalGameplayRosterTarget target) {
    requirePreseeded(target);
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

  public static GetCanonicalGameplayRosterReferenceRequest playerCreatedRequest(
      UUID operation,
      UUID account,
      UUID session,
      String namespace,
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity source) {
    requireCreated(target);
    return requireRequest(
        GetCanonicalGameplayRosterReferenceRequest.newBuilder()
            .setSchemaVersion(1)
            .setOperationUuid(operation.toString())
            .setCanonicalAccountUuid(account.toString())
            .setSessionUuid(session.toString())
            .setTargetNamespace(namespace)
            .setCanonicalTarget(target.toByteString())
            .setCanonicalPlayerCreatedSourceIdentity(
                CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(source).toByteString())
            .build());
  }

  public static GetCanonicalGameplayRosterReferenceRequest requireRequest(
      GetCanonicalGameplayRosterReferenceRequest request) {
    requireRequestTarget(request);
    return request;
  }

  private static CanonicalGameplayRosterTarget requireRequestTarget(
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
    CanonicalGameplayRosterTarget target = target(request);
    if (isCreated(target)) {
      playerCreatedSourceIdentity(request);
    } else if (!request.getCanonicalPlayerCreatedSourceIdentity().isEmpty()) {
      throw new IllegalArgumentException(
          "PRESEEDED_ONLY original cannot carry PLAYER_CREATED source identity");
    }
    return target;
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
          || (target.getEntryPolicy()
                  != CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY
              && !isCreated(target))
          || (isCreated(target)
              && target.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
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
    requirePreseeded(target(requireRequest(request)));
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

  public static GetCanonicalGameplayRosterReferenceResponse playerCreatedResponse(
      GetCanonicalGameplayRosterReferenceRequest request,
      long fence,
      UUID targetFence,
      CanonicalPlayerCreatedRosterResponse receipt) {
    requireCreated(target(requireRequest(request)));
    var response =
        GetCanonicalGameplayRosterReferenceResponse.newBuilder()
            .setRequest(request)
            .setOwnerFence(fence)
            .setTargetFenceUuid(targetFence.toString())
            .setState(
                receipt == null
                    ? CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RESERVED
                    : CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RECORDED);
    if (receipt != null) response.setCanonicalPlayerCreatedRosterReceipt(receipt.toByteString());
    return requireResponse(request, response.build());
  }

  public static CanonicalPlayerCreatedRosterSourceIdentity playerCreatedSourceIdentity(
      GetCanonicalGameplayRosterReferenceRequest request) {
    requireCreated(target(request));
    try {
      var bytes = request.getCanonicalPlayerCreatedSourceIdentity();
      if (bytes.isEmpty()
          || bytes.size() > CanonicalPlayerCreatedRosterEvidence.MAX_SOURCE_IDENTITY_BYTES) {
        throw new IllegalArgumentException(
            "Complete canonical PLAYER_CREATED source identity required");
      }
      var source =
          CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(
              CanonicalPlayerCreatedRosterSourceIdentity.parseFrom(bytes));
      if (!bytes.equals(source.toByteString())) {
        throw new IllegalArgumentException(
            "PLAYER_CREATED source identity bytes are not canonical");
      }
      return source;
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException("PLAYER_CREATED source identity is malformed", invalid);
    }
  }

  public static CanonicalPlayerCreatedRosterResponse playerCreatedReceipt(
      GetCanonicalGameplayRosterReferenceResponse response) {
    requireCreated(target(requireRequest(response.getRequest())));
    if (!response.getCanonicalRosterReceipt().isEmpty()
        || !response.getCanonicalSelectedAssignmentReceipt().isEmpty()) {
      throw new IllegalArgumentException("PLAYER_CREATED receipt cannot use PRESEEDED_ONLY fields");
    }
    try {
      var bytes = response.getCanonicalPlayerCreatedRosterReceipt();
      if (bytes.isEmpty() || bytes.size() > MAX_PLAYER_CREATED_RECEIPT_BYTES) {
        throw new IllegalArgumentException(
            "Complete bounded PLAYER_CREATED roster receipt required");
      }
      var roster = CanonicalPlayerCreatedRosterResponse.parseFrom(bytes);
      if (!bytes.equals(roster.toByteString())) {
        throw new IllegalArgumentException("PLAYER_CREATED roster receipt bytes are not canonical");
      }
      return CanonicalPlayerCreatedRosterEvidence.requireResponse(
          createdRosterRequest(response.getRequest()), roster);
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException("PLAYER_CREATED roster receipt is malformed", invalid);
    }
  }

  /** Exact validated generic snapshot projection; this does not authenticate its producer. */
  public static CanonicalGameplayRosterResponse snapshotReceipt(
      GetCanonicalGameplayRosterReferenceResponse response) {
    return isCreated(target(response.getRequest()))
        ? playerCreatedReceipt(response).getRoster()
        : receipt(response);
  }

  public static CanonicalSelectedPlayerCreatedCharacterResponse
      selectedPlayerCreatedCharacterReceipt(GetCanonicalGameplayRosterReferenceResponse response) {
    var roster = playerCreatedReceipt(response).getRoster();
    return selectedPlayerCreatedCharacterReceipt(response, roster);
  }

  private static CanonicalSelectedPlayerCreatedCharacterResponse
      selectedPlayerCreatedCharacterReceipt(
          GetCanonicalGameplayRosterReferenceResponse response,
          CanonicalGameplayRosterResponse roster) {
    try {
      var bytes = response.getCanonicalSelectedPlayerCreatedCharacterReceipt();
      if (bytes.isEmpty() || bytes.size() > MAX_SELECTED_PLAYER_CREATED_BYTES) {
        throw new IllegalArgumentException(
            "Complete bounded selected PLAYER_CREATED terminal required");
      }
      var selected = CanonicalSelectedPlayerCreatedCharacterResponse.parseFrom(bytes);
      if (!bytes.equals(selected.toByteString())) {
        throw new IllegalArgumentException(
            "Selected PLAYER_CREATED receipt bytes are not canonical");
      }
      var original = response.getRequest();
      var expected =
          CanonicalSelectedPlayerCreatedCharacterRequest.newBuilder()
              .setRequestUuid(original.getOperationUuid())
              .setCanonicalAccountUuid(original.getCanonicalAccountUuid())
              .setSelectedCharacterUuid(response.getSelectedCharacterUuid())
              .setExpectedTarget(target(original))
              .setExpectedSource(playerCreatedSourceIdentity(original))
              .setExpectedSnapshot(
                  CanonicalGameplayRosterSnapshotReference.newBuilder()
                      .setSnapshotUuid(roster.getSnapshotUuid())
                      .setSnapshotDigest(roster.getSnapshotDigest()))
              .setPlayerExecutionContext(
                  comparisonContext(original).setCharacterId(response.getSelectedCharacterUuid()))
              .build();
      var validated =
          CanonicalPlayerCreatedRosterEvidence.requireSelectedResponse(expected, selected);
      if (!validated
          .getTerminal()
          .getOriginalRequest()
          .getOriginal()
          .getTargetNamespace()
          .equals(original.getTargetNamespace())) {
        throw new IllegalArgumentException(
            "Selected creation terminal changed original workload namespace");
      }
      return validated;
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException("Selected PLAYER_CREATED terminal is malformed", invalid);
    }
  }

  private static CanonicalPlayerCreatedRosterRequest createdRosterRequest(
      GetCanonicalGameplayRosterReferenceRequest original) {
    return CanonicalPlayerCreatedRosterRequest.newBuilder()
        .setRequestUuid(original.getOperationUuid())
        .setCanonicalAccountUuid(original.getCanonicalAccountUuid())
        .setExpectedTarget(target(original))
        .setExpectedSource(playerCreatedSourceIdentity(original))
        .setPlayerExecutionContext(comparisonContext(original))
        .build();
  }

  // Reconstruct comparison values only. This is never authenticated execution-context custody.
  private static PlayerExecutionContext.Builder comparisonContext(
      GetCanonicalGameplayRosterReferenceRequest original) {
    var target = target(original);
    return PlayerExecutionContext.newBuilder()
        .setRequestId(original.getOperationUuid())
        .setAccountId(original.getCanonicalAccountUuid())
        .setSessionId(original.getSessionUuid())
        .setTenantId(target.getTenantUuid())
        .setRealmId(target.getRealmUuid())
        .setPlayableStateNamespaceId(target.getPlayableStateNamespaceUuid())
        .setPlayableStateScope("SHARED")
        .setGameInstanceId(target.getGameInstanceUuid());
  }

  private static boolean isCreated(CanonicalGameplayRosterTarget target) {
    return target.getEntryPolicy()
        == CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED;
  }

  private static void requireCreated(CanonicalGameplayRosterTarget target) {
    if (!isCreated(target)
        || target.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED) {
      throw new IllegalArgumentException("Distinct SHARED PLAYER_CREATED carrier required");
    }
  }

  private static void requirePreseeded(CanonicalGameplayRosterTarget target) {
    if (target.getEntryPolicy()
        != CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY) {
      throw new IllegalArgumentException("Distinct PRESEEDED_ONLY carrier required");
    }
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
    requirePreseeded(target(requireRequest(response.getRequest())));
    rejectCreatedFields(response);
    if (response.getCanonicalSelectedAssignmentReceipt().isEmpty()
        || response.getCanonicalSelectedAssignmentReceipt().size()
            > MAX_SELECTED_ASSIGNMENT_BYTES) {
      throw new IllegalArgumentException("Complete original selected assignment receipt required");
    }
    return selectedAssignmentReceipt(response, receipt(response));
  }

  private static net.firedevops.firemud.entitymanagement.v1
          .CanonicalGameplayRosterSelectedAssignmentResponse
      selectedAssignmentReceipt(
          GetCanonicalGameplayRosterReferenceResponse response,
          CanonicalGameplayRosterResponse roster) {
    try {
      if (response.getCanonicalSelectedAssignmentReceipt().isEmpty()
          || response.getCanonicalSelectedAssignmentReceipt().size()
              > MAX_SELECTED_ASSIGNMENT_BYTES) {
        throw new IllegalArgumentException(
            "Complete original selected assignment receipt required");
      }
      var selected =
          net.firedevops.firemud.entitymanagement.v1
              .CanonicalGameplayRosterSelectedAssignmentResponse.parseFrom(
              response.getCanonicalSelectedAssignmentReceipt());
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
    return validateResponse(expected, response).response();
  }

  static CanonicalGameplayRosterResponse requireResponseSnapshot(
      GetCanonicalGameplayRosterReferenceRequest expected,
      GetCanonicalGameplayRosterReferenceResponse response) {
    return validateResponse(expected, response).snapshotReceipt();
  }

  private static ValidatedResponse validateResponse(
      GetCanonicalGameplayRosterReferenceRequest expected,
      GetCanonicalGameplayRosterReferenceResponse response) {
    CanonicalGameplayRosterTarget expectedTarget = requireRequestTarget(expected);
    Objects.requireNonNull(response, "response");
    boolean created = isCreated(expectedTarget);
    if (created) {
      if (!response.getCanonicalRosterReceipt().isEmpty()
          || !response.getCanonicalSelectedAssignmentReceipt().isEmpty()) {
        throw new IllegalArgumentException(
            "PLAYER_CREATED original cannot carry PRESEEDED_ONLY receipts");
      }
    } else {
      rejectCreatedFields(response);
    }
    if (response.getSerializedSize()
            > (created ? MAX_PLAYER_CREATED_RESPONSE_BYTES : MAX_RESPONSE_BYTES)
        || response.getCanonicalPlayerCreatedRosterReceipt().size()
            > MAX_PLAYER_CREATED_RECEIPT_BYTES
        || response.getCanonicalSelectedPlayerCreatedCharacterReceipt().size()
            > MAX_SELECTED_PLAYER_CREATED_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || response.getOwnerFence() <= 0
        || response.getCanonicalRosterReceipt().size() > MAX_RECEIPT_BYTES) {
      throw new IllegalArgumentException("Original roster selection owner binding is invalid");
    }
    if (!expected.equals(response.getRequest())) {
      throw new IllegalArgumentException("Original roster selection owner binding is invalid");
    }
    boolean hasRoster =
        !response.getCanonicalRosterReceipt().isEmpty()
            || !response.getCanonicalPlayerCreatedRosterReceipt().isEmpty();
    CanonicalGameplayRosterResponse validatedRoster = hasRoster ? snapshotReceipt(response) : null;
    switch (response.getState()) {
      case CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RESERVED -> {
        if (hasRoster) {
          throw new IllegalArgumentException("Reserved original selection cannot carry a receipt");
        }
      }
      case CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RECORDED -> {
        if (!hasRoster) snapshotReceipt(response);
      }
      case CANONICAL_GAMEPLAY_ROSTER_REFERENCE_REPLAY_CLOSED -> {
        if (response.getTerminalOutcome().isEmpty()) {
          throw new IllegalArgumentException("Closed replay requires original terminal outcome");
        }
      }
      case CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED ->
          requireRetirementValidated(response, expectedTarget);
      default -> throw new IllegalArgumentException("Unknown original selection state");
    }
    if (response.getState()
        != CanonicalGameplayRosterReferenceState.CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED) {
      uuid(response.getTargetFenceUuid());
    }
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
          || validatedRoster == null
          || validatedRoster.getActorsList().stream()
                  .filter(
                      actor -> actor.getCharacterUuid().equals(response.getSelectedCharacterUuid()))
                  .count()
              != 1L) {
        throw new IllegalArgumentException(
            "Original chosen actor must occur once in its recorded roster");
      }
    }
    if (!response.getCanonicalSelectedAssignmentReceipt().isEmpty()
        || !response.getCanonicalSelectedPlayerCreatedCharacterReceipt().isEmpty()) {
      if (response.getSelectedCharacterUuid().isEmpty()) {
        throw new IllegalArgumentException(
            "Original assignment receipt requires a frozen chosen actor");
      }
      if (created) selectedPlayerCreatedCharacterReceipt(response, validatedRoster);
      else selectedAssignmentReceipt(response, validatedRoster);
    }
    return new ValidatedResponse(response, validatedRoster);
  }

  private record ValidatedResponse(
      GetCanonicalGameplayRosterReferenceResponse response,
      CanonicalGameplayRosterResponse snapshotReceipt) {}

  public static CanonicalGameplayRosterResponse receipt(
      GetCanonicalGameplayRosterReferenceResponse response) {
    requirePreseeded(target(requireRequest(response.getRequest())));
    rejectCreatedFields(response);
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

  private static void rejectCreatedFields(GetCanonicalGameplayRosterReferenceResponse response) {
    if (!response.getCanonicalPlayerCreatedRosterReceipt().isEmpty()
        || !response.getCanonicalSelectedPlayerCreatedCharacterReceipt().isEmpty()) {
      throw new IllegalArgumentException(
          "PRESEEDED_ONLY original cannot carry PLAYER_CREATED receipts");
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
    var sealed = unsigned.toBuilder().setRetirementProofDigest(digest(unsigned)).build();
    return requireResponse(response.getRequest(), sealed);
  }

  public static GetCanonicalGameplayRosterReferenceResponse requireRetirement(
      GetCanonicalGameplayRosterReferenceResponse response) {
    CanonicalGameplayRosterTarget target = requireRequestTarget(response.getRequest());
    return requireRetirementValidated(response, target);
  }

  private static GetCanonicalGameplayRosterReferenceResponse requireRetirementValidated(
      GetCanonicalGameplayRosterReferenceResponse response, CanonicalGameplayRosterTarget target) {
    if (!response.getUnknownFields().asMap().isEmpty()
        || response.getOwnerFence() <= 0
        || response.getState()
            != CanonicalGameplayRosterReferenceState.CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED
        || !java.util.Set.of("MENU", "SELECTED", "DENIED").contains(response.getTerminalOutcome())
        || !response.getSupersedingPointerDigest().matches("[0-9a-f]{64}")
        || response.getSupersedingPointerVersion() <= target.getPointerVersion()
        || response.getSupersedingCatalogRevision() < target.getCatalogRevision()
        || response.getSupersedingWorldEpoch() < target.getActiveWorldEpoch()
        || !response
            .getRetirementProofDigest()
            .equals(digest(response.toBuilder().clearRetirementProofDigest().build()))) {
      throw new IllegalArgumentException("Complete exact owner retirement proof required");
    }
    uuid(response.getRetirementUuid());
    uuid(response.getTargetFenceUuid());
    return response;
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
      throw new IllegalStateException(unavailable);
    } catch (IOException failure) {
      throw new IllegalStateException("Unable to hash canonical protobuf", failure);
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
