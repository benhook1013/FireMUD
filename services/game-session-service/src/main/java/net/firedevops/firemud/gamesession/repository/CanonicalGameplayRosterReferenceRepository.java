package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact owner-local reservation and write-once receipt. No caller supplies terminal authority. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "DSLContext is the owner transaction collaborator")
public final class CanonicalGameplayRosterReferenceRepository {
  private final DSLContext dsl;
  private final net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy
      policy;

  public CanonicalGameplayRosterReferenceRepository(
      DSLContext dsl,
      net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy policy) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /**
   * Exact original absence, distinct from malformed retained state or an owner invariant failure.
   */
  public static final class OriginalUnavailableException extends IllegalStateException {
    public OriginalUnavailableException() {
      super("CANONICAL_ROSTER_ORIGINAL_UNAVAILABLE");
    }
  }

  public GetCanonicalGameplayRosterReferenceResponse begin(
      GetCanonicalGameplayRosterReferenceRequest request) {
    requireWritableTransaction();
    CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
    byte[] binding = targetBinding(request);
    String digest = digest(binding);
    dsl.execute(
        "INSERT INTO game_session_canonical_roster_target_fences "
            + "(target_fence_uuid, target_binding_digest, target_binding, minimum_retention_millis, replay_horizon_millis) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        java.util.UUID.randomUUID(),
        digest,
        binding,
        policy.minimumRetention().toMillis(),
        policy.replayHorizonMillis());
    Record fence =
        dsl.fetchSingle(
            "SELECT * FROM game_session_canonical_roster_target_fences WHERE target_binding_digest = ? FOR UPDATE",
            digest);
    if (!Arrays.equals(binding, fence.get("target_binding", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_TARGET_BINDING_CONFLICT");
    }
    if (!"ACTIVE".equals(fence.get("fence_state", String.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_TARGET_FENCE_RETIRED");
    }
    dsl.execute(
        "INSERT INTO game_session_canonical_roster_selection (operation_uuid, original_request, target_fence_uuid) "
            + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()),
        request.toByteArray(),
        fence.get("target_fence_uuid", java.util.UUID.class));
    Record replay =
        dsl.fetchSingle(
            "SELECT terminal_at IS NOT NULL AND terminal_at + ? * interval '1 millisecond' <= clock_timestamp() AS closed "
                + "FROM game_session_canonical_roster_selection s JOIN game_session_canonical_roster_target_fences f USING (target_fence_uuid) WHERE operation_uuid = ?",
            fence.get("replay_horizon_millis", Long.class),
            CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()));
    if (Boolean.TRUE.equals(replay.get("closed", Boolean.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_REPLAY_WINDOW_CLOSED");
    }
    return readExact(request, true);
  }

  /** Looks up an exact retained original without reserving any new row or fence. */
  public java.util.Optional<GetCanonicalGameplayRosterReferenceResponse> find(
      GetCanonicalGameplayRosterReferenceRequest request) {
    CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
    Record row =
        dsl.fetchOne(
            "SELECT original_request FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()));
    if (row == null) {
      return java.util.Optional.empty();
    }
    if (!Arrays.equals(request.toByteArray(), row.get("original_request", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_REQUEST_CONFLICT");
    }
    return java.util.Optional.of(readExact(request, false));
  }

  /** Replays only an existing original; a concurrent purge never turns this into a reservation. */
  public GetCanonicalGameplayRosterReferenceResponse replay(
      GetCanonicalGameplayRosterReferenceRequest request) {
    requireWritableTransaction();
    CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
    return readExact(request, true);
  }

  public GetCanonicalGameplayRosterReferenceResponse read(
      GetCanonicalGameplayRosterReferenceRequest request) {
    CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
    return readExact(request, false);
  }

  public GetCanonicalGameplayRosterReferenceResponse record(
      GetCanonicalGameplayRosterReferenceRequest request, CanonicalGameplayRosterResponse receipt) {
    requireWritableTransaction();
    var original = readExact(request, true);
    CanonicalGameplayRosterReferenceEvidence.response(
        request,
        original.getOwnerFence(),
        java.util.UUID.fromString(original.getTargetFenceUuid()),
        receipt);
    if (!original.getCanonicalRosterReceipt().isEmpty()) {
      if (!original.getCanonicalRosterReceipt().equals(receipt.toByteString())) {
        throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_CONFLICT");
      }
      return original;
    }
    int changed =
        dsl.execute(
            "UPDATE game_session_canonical_roster_selection "
                + "SET canonical_roster_receipt = ?, receipt_recorded_at = clock_timestamp() "
                + "WHERE operation_uuid = ? AND owner_fence = ? AND canonical_roster_receipt IS NULL",
            receipt.toByteArray(),
            CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()),
            original.getOwnerFence());
    if (changed != 1) {
      throw new IllegalStateException("CANONICAL_ROSTER_OWNER_FENCE_CHANGED");
    }
    return readExact(request, true);
  }

  public void reserveSelectionInput(
      GetCanonicalGameplayRosterReferenceRequest request,
      net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput input) {
    requireWritableTransaction();
    CanonicalGameplayRosterReferenceEvidence.requireSelectionInput(input);
    var original = readExact(request, true);
    if (original.hasSelectionInput()) {
      if (!input.equals(original.getSelectionInput())) {
        throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_SELECTION_INPUT_CONFLICT");
      }
      return;
    }
    if (!original.getCanonicalRosterReceipt().isEmpty()) {
      throw new IllegalStateException("CANONICAL_ROSTER_SELECTION_INPUT_NOT_RESERVED_BEFORE_READ");
    }
    dsl.execute(
        "UPDATE game_session_canonical_roster_selection SET selection_input = ? WHERE operation_uuid = ? AND owner_fence = ?",
        input.toByteArray(),
        CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()),
        original.getOwnerFence());
  }

  public GetCanonicalGameplayRosterReferenceResponse selectOriginal(
      GetCanonicalGameplayRosterReferenceRequest request, java.util.UUID character) {
    requireWritableTransaction();
    var original = readExact(request, true);
    if (!original.hasSelectionInput()) {
      throw new IllegalStateException("CANONICAL_ROSTER_SELECTION_INPUT_UNAVAILABLE");
    }
    if (!original.getTerminalOutcome().isEmpty()
        && !"SELECTED".equals(original.getTerminalOutcome())) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_TERMINAL_NOT_SELECTED");
    }
    var roster = CanonicalGameplayRosterReferenceEvidence.receipt(original);
    var input = original.getSelectionInput();
    int ordinal = 1;
    if (input.getSelector().isBlank()) {
      if (roster.getActorsCount() != 1) {
        throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_SELECTION_INPUT_CONFLICT");
      }
    } else {
      if (!input.getSelector().matches("[1-9][0-9]{0,2}")
          || !input.getMenuSnapshotUuid().equals(roster.getSnapshotUuid())
          || !input.getMenuSnapshotDigest().equals(roster.getSnapshotDigest())) {
        throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_SELECTION_INPUT_CONFLICT");
      }
      ordinal = Integer.parseInt(input.getSelector());
    }
    if (ordinal > roster.getActorsCount()
        || !roster.getActors(ordinal - 1).getCharacterUuid().equals(character.toString())) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_SELECTED_ACTOR_CONFLICT");
    }
    var candidate = original.toBuilder().setSelectedCharacterUuid(character.toString()).build();
    CanonicalGameplayRosterReferenceEvidence.requireResponse(request, candidate);
    if (!original.getSelectedCharacterUuid().isEmpty()
        && !original.getSelectedCharacterUuid().equals(character.toString())) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_SELECTED_ACTOR_CONFLICT");
    }
    if (original.getSelectedCharacterUuid().isEmpty()) {
      dsl.execute(
          "UPDATE game_session_canonical_roster_selection SET selected_character_uuid = ? WHERE operation_uuid = ? AND owner_fence = ?",
          character,
          CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()),
          original.getOwnerFence());
    }
    return readExact(request, true);
  }

  public void recordSelectedAssignment(
      GetCanonicalGameplayRosterReferenceRequest request,
      net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentResponse
          receipt) {
    requireWritableTransaction();
    var original = readExact(request, true);
    CanonicalGameplayRosterReferenceEvidence.requireResponse(
        request,
        original.toBuilder().setCanonicalSelectedAssignmentReceipt(receipt.toByteString()).build());
    if (!original.getCanonicalSelectedAssignmentReceipt().isEmpty()) {
      if (!original.getCanonicalSelectedAssignmentReceipt().equals(receipt.toByteString())) {
        throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_ASSIGNMENT_RECEIPT_CONFLICT");
      }
      return;
    }
    dsl.execute(
        "UPDATE game_session_canonical_roster_selection SET canonical_selected_assignment_receipt = ? WHERE operation_uuid = ? AND owner_fence = ?",
        receipt.toByteArray(),
        CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()),
        original.getOwnerFence());
  }

  private GetCanonicalGameplayRosterReferenceResponse readExact(
      GetCanonicalGameplayRosterReferenceRequest request, boolean lock) {
    if (lock) {
      lockActiveTarget(request);
    }
    Record row =
        dsl.fetchOne(
            "SELECT s.*, f.fence_state, f.retirement_uuid, f.superseding_pointer_digest, f.superseding_pointer_version, "
                + "f.superseding_catalog_revision, f.superseding_world_epoch, "
                + "s.terminal_at IS NOT NULL AND s.terminal_at + f.replay_horizon_millis * interval '1 millisecond' <= clock_timestamp() AS replay_closed "
                + "FROM game_session_canonical_roster_selection s JOIN game_session_canonical_roster_target_fences f USING (target_fence_uuid) WHERE operation_uuid = ?"
                + (lock ? " FOR UPDATE OF s" : ""),
            CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()));
    if (row == null) {
      throw new OriginalUnavailableException();
    }
    if (!Arrays.equals(request.toByteArray(), row.get("original_request", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_REQUEST_CONFLICT");
    }
    if (lock && Boolean.TRUE.equals(row.get("replay_closed", Boolean.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_REPLAY_WINDOW_CLOSED");
    }
    if (lock && row.get("recovery_uuid", java.util.UUID.class) != null) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECOVERY_FENCED");
    }
    byte[] receipt = row.get("canonical_roster_receipt", byte[].class);
    try {
      var result =
          CanonicalGameplayRosterReferenceEvidence.response(
              request,
              row.get("owner_fence", Long.class),
              row.get("target_fence_uuid", java.util.UUID.class),
              receipt == null ? null : CanonicalGameplayRosterResponse.parseFrom(receipt))
              .toBuilder();
      byte[] input = row.get("selection_input", byte[].class);
      if (input != null) {
        result.setSelectionInput(
            net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.parseFrom(
                input));
      }
      java.util.UUID character = row.get("selected_character_uuid", java.util.UUID.class);
      if (character != null) {
        result.setSelectedCharacterUuid(character.toString());
      }
      byte[] selectedReceipt = row.get("canonical_selected_assignment_receipt", byte[].class);
      if (selectedReceipt != null) {
        result.setCanonicalSelectedAssignmentReceipt(
            com.google.protobuf.ByteString.copyFrom(selectedReceipt));
      }
      java.util.UUID recovery = row.get("recovery_uuid", java.util.UUID.class);
      if (recovery != null) {
        result.setRecoveryUuid(recovery.toString());
      }
      String outcome = row.get("terminal_outcome", String.class);
      if (outcome != null) {
        result.setTerminalOutcome(outcome);
      }
      if ("RETIRED".equals(row.get("fence_state", String.class))) {
        result
            .setState(
                net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                    .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
            .setRetirementUuid(row.get("retirement_uuid", java.util.UUID.class).toString())
            .setSupersedingPointerDigest(row.get("superseding_pointer_digest", String.class))
            .setSupersedingPointerVersion(row.get("superseding_pointer_version", Long.class))
            .setSupersedingCatalogRevision(row.get("superseding_catalog_revision", Long.class))
            .setSupersedingWorldEpoch(row.get("superseding_world_epoch", Long.class));
        return CanonicalGameplayRosterReferenceEvidence.sealRetirement(result.build());
      }
      if (Boolean.TRUE.equals(row.get("replay_closed", Boolean.class))) {
        result.setState(
            net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_REPLAY_CLOSED);
      }
      return CanonicalGameplayRosterReferenceEvidence.requireResponse(request, result.build());
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_INVALID", invalid);
    }
  }

  public void markRosterReadStarted(GetCanonicalGameplayRosterReferenceRequest request) {
    requireWritableTransaction();
    var original = readExact(request, true);
    if (original.getCanonicalRosterReceipt().isEmpty()) {
      dsl.execute(
          "UPDATE game_session_canonical_roster_selection SET roster_read_started = TRUE WHERE operation_uuid = ? AND NOT roster_read_started AND terminal_outcome IS NULL",
          CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()));
    }
  }

  public enum SelectionOutcome {
    MENU,
    SELECTED,
    DENIED
  }

  public void completeUnissuedDenied(GetCanonicalGameplayRosterReferenceRequest request) {
    requireWritableTransaction();
    lockActiveTarget(request);
    dsl.execute(
        "UPDATE game_session_canonical_roster_selection SET terminal_outcome = 'DENIED', terminal_at = clock_timestamp() "
            + "WHERE operation_uuid = ? AND original_request = ? AND NOT roster_read_started AND terminal_outcome IS NULL AND recovery_uuid IS NULL "
            + "AND canonical_roster_receipt IS NULL AND selected_character_uuid IS NULL",
        CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()),
        request.toByteArray());
  }

  public void completeSelection(
      GetCanonicalGameplayRosterReferenceRequest request, SelectionOutcome outcome) {
    requireWritableTransaction();
    readExact(request, true);
    Record row =
        dsl.fetchSingle(
            "SELECT * FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()));
    String retained = row.get("terminal_outcome", String.class);
    if (retained != null) {
      if (!retained.equals(outcome.name())) {
        throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_OUTCOME_CONFLICT");
      }
      return;
    }
    if (row.get("selection_input", byte[].class) == null
        || (Boolean.TRUE.equals(row.get("roster_read_started", Boolean.class))
            && row.get("canonical_roster_receipt", byte[].class) == null)
        || (row.get("selected_character_uuid", java.util.UUID.class) != null
            && row.get("canonical_selected_assignment_receipt", byte[].class) == null)
        || (outcome == SelectionOutcome.SELECTED
            && row.get("canonical_selected_assignment_receipt", byte[].class) == null)
        || (outcome == SelectionOutcome.MENU
            && (row.get("canonical_roster_receipt", byte[].class) == null
                || row.get("selected_character_uuid", java.util.UUID.class) != null))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECOVERY_REQUIRED");
    }
    dsl.execute(
        "UPDATE game_session_canonical_roster_selection SET terminal_outcome = ?, terminal_at = clock_timestamp() WHERE operation_uuid = ? AND terminal_outcome IS NULL",
        outcome.name(),
        CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()));
  }

  public void lockActiveTarget(GetCanonicalGameplayRosterReferenceRequest request) {
    requireWritableTransaction();
    Record fence =
        dsl.fetchOne(
            "SELECT target_fence_uuid, fence_state, target_binding FROM game_session_canonical_roster_target_fences WHERE target_binding_digest = ? FOR UPDATE",
            digest(targetBinding(request)));
    if (fence == null
        || !Arrays.equals(targetBinding(request), fence.get("target_binding", byte[].class))
        || !"ACTIVE".equals(fence.get("fence_state", String.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_TARGET_FENCE_UNAVAILABLE_OR_RETIRED");
    }
  }

  public boolean sealTargetRetirement(
      GetCanonicalGameplayRosterReferenceRequest request,
      net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget current) {
    lockActiveTarget(request);
    if (!isSupersedingTarget(request, current)) {
      return false;
    }
    int sealed =
        dsl.execute(
            "UPDATE game_session_canonical_roster_target_fences f SET fence_state = 'RETIRED', "
                + "retirement_uuid = ?, retired_at = clock_timestamp(), superseding_pointer_digest = ?, superseding_pointer_version = ?, "
                + "superseding_catalog_revision = ?, superseding_world_epoch = ? WHERE target_binding_digest = ? AND fence_state = 'ACTIVE' "
                + "AND NOT EXISTS (SELECT 1 FROM game_session_canonical_roster_selection s WHERE s.target_fence_uuid = f.target_fence_uuid "
                + "AND (s.terminal_at IS NULL OR s.terminal_at + f.minimum_retention_millis * interval '1 millisecond' > clock_timestamp() "
                + "OR ((s.roster_read_started AND s.canonical_roster_receipt IS NULL "
                + "OR s.selected_character_uuid IS NOT NULL AND s.canonical_selected_assignment_receipt IS NULL) "
                + "AND NOT (s.recovery_uuid IS NOT NULL AND s.recovery_receipt IS NOT NULL AND s.terminal_outcome = 'DENIED'))))",
            java.util.UUID.randomUUID(),
            current.admissionPointerSnapshotDigest(),
            current.pointerVersion(),
            current.catalogRevision(),
            current.activeWorldEpoch(),
            digest(targetBinding(request)));
    return sealed == 1;
  }

  private static boolean isSupersedingTarget(
      GetCanonicalGameplayRosterReferenceRequest request,
      net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget current) {
    var original = CanonicalGameplayRosterReferenceEvidence.target(request);
    if (!request.getTargetNamespace().equals(current.targetNamespace())
        || !original.getTenantUuid().equals(current.canonicalTenantId().toString())
        || !original.getRealmUuid().equals(current.realmId().toString())
        || !original.getWorldSlug().equals(current.worldSlug())
        || !original.getRealmSlug().equals(current.realmSlug())
        || !original
            .getPlayableStateNamespaceUuid()
            .equals(current.playableStateNamespaceId().toString())
        || !"SHARED".equals(current.playableStateScope())
        || current.pointerVersion() <= original.getPointerVersion()
        || current.catalogRevision() < original.getCatalogRevision()
        || current.activeWorldEpoch() < original.getActiveWorldEpoch()) {
      return false;
    }
    return true;
  }

  /** Durably fences a bounded page before any recovery RPC, including interrupted retries. */
  public java.util.List<
          net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest>
      beginRecoveries(
          GetCanonicalGameplayRosterReferenceRequest request,
          net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget current) {
    lockActiveTarget(request);
    if (!isSupersedingTarget(request, current)) {
      return java.util.List.of();
    }
    var candidates =
        dsl.fetch(
            "SELECT s.* FROM game_session_canonical_roster_selection s "
                + "JOIN game_session_canonical_roster_target_fences f USING (target_fence_uuid) "
                + "WHERE f.target_binding_digest = ? AND s.terminal_outcome IS NULL "
                + "AND (s.recovery_uuid IS NOT NULL OR s.recorded_at + f.replay_horizon_millis * interval '1 millisecond' <= clock_timestamp()) "
                + "ORDER BY s.recovery_started_at NULLS LAST, s.operation_uuid LIMIT ? FOR UPDATE OF s",
            digest(targetBinding(request)),
            policy.batchSize());
    return candidates.map(
        row -> {
          java.util.UUID recovery = row.get("recovery_uuid", java.util.UUID.class);
          if (recovery == null) {
            recovery = java.util.UUID.randomUUID();
            dsl.execute(
                "UPDATE game_session_canonical_roster_selection SET recovery_uuid = ?, recovery_started_at = clock_timestamp() "
                    + "WHERE operation_uuid = ? AND recovery_uuid IS NULL AND terminal_outcome IS NULL",
                recovery,
                row.get("operation_uuid", java.util.UUID.class));
          }
          try {
            return net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence
                .requireRequest(
                    net.firedevops.firemud.entitymanagement.v1
                        .CanonicalGameplayRosterRecoveryRequest.newBuilder()
                        .setSchemaVersion(1)
                        .setOriginalSelection(
                            GetCanonicalGameplayRosterReferenceRequest.parseFrom(
                                row.get("original_request", byte[].class)))
                        .setRecoveryUuid(recovery.toString())
                        .build());
          } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_REQUEST_INVALID", invalid);
          }
        });
  }

  /** Only the authenticated Entity client can construct this positive nonce-closure capability. */
  public void finishRecovery(
      net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient
              .VerifiedRecovery
          verified) {
    requireWritableTransaction();
    var recovery =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence.requireRequest(
            verified.request());
    var response =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence.requireResponse(
            recovery, verified.response());
    var request = recovery.getOriginalSelection();
    lockActiveTarget(request);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_session_canonical_roster_selection WHERE operation_uuid = ? FOR UPDATE",
            CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()));
    if (row == null) {
      throw new OriginalUnavailableException();
    }
    var original = readExact(request, false);
    net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence
        .requireOwnerRecovery(recovery, original);
    if (original.getOwnerFence() != response.getOwnerFence()
        || !original.getTargetFenceUuid().equals(response.getTargetFenceUuid())) {
      throw new IllegalStateException("CANONICAL_ROSTER_RECOVERY_FENCE_CONFLICT");
    }
    byte[] retainedRecovery = row.get("recovery_receipt", byte[].class);
    if (retainedRecovery != null) {
      if (!Arrays.equals(retainedRecovery, response.toByteArray())) {
        throw new IllegalStateException("CANONICAL_ROSTER_RECOVERY_RECEIPT_CONFLICT");
      }
      return;
    }
    if (!original.getTerminalOutcome().isEmpty()) {
      throw new IllegalStateException("CANONICAL_ROSTER_RECOVERY_OUTCOME_CONFLICT");
    }
    if (!original.getCanonicalRosterReceipt().isEmpty()
        && !original.getCanonicalRosterReceipt().equals(response.getCanonicalRosterReceipt())) {
      throw new IllegalStateException("CANONICAL_ROSTER_RECOVERY_ROSTER_CONFLICT");
    }
    byte[] receipt =
        response.getCanonicalRosterReceipt().isEmpty()
            ? null
            : response.getCanonicalRosterReceipt().toByteArray();
    dsl.execute(
        "UPDATE game_session_canonical_roster_selection SET "
            + "canonical_roster_receipt = COALESCE(canonical_roster_receipt, ?::bytea), "
            + "receipt_recorded_at = CASE WHEN canonical_roster_receipt IS NULL AND ?::bytea IS NOT NULL THEN clock_timestamp() ELSE receipt_recorded_at END, "
            + "recovery_receipt = ?, terminal_outcome = 'DENIED', terminal_at = clock_timestamp() "
            + "WHERE operation_uuid = ? AND recovery_uuid = ? AND terminal_outcome IS NULL",
        receipt,
        receipt,
        response.toByteArray(),
        CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid()),
        CanonicalGameplayRosterReferenceEvidence.uuid(recovery.getRecoveryUuid()));
  }

  public record TargetCandidate(
      java.util.UUID fenceUuid, GetCanonicalGameplayRosterReferenceRequest original, String state) {
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public GetCanonicalGameplayRosterReferenceRequest original() {
      return original;
    }
  }

  public java.util.List<TargetCandidate> targetCandidates(java.util.UUID afterFence, int limit) {
    requireWritableTransaction();
    if (limit <= 0) {
      throw new IllegalArgumentException("Bounded target candidate page required");
    }
    return dsl.fetch(
            "SELECT DISTINCT ON (f.target_fence_uuid) f.target_fence_uuid, f.fence_state, s.original_request "
                + "FROM game_session_canonical_roster_target_fences f JOIN game_session_canonical_roster_selection s USING (target_fence_uuid) "
                + "WHERE (?::uuid IS NULL OR f.target_fence_uuid > ?::uuid) ORDER BY f.target_fence_uuid, s.operation_uuid LIMIT ?",
            afterFence,
            afterFence,
            limit)
        .map(
            row -> {
              try {
                return new TargetCandidate(
                    row.get("target_fence_uuid", java.util.UUID.class),
                    GetCanonicalGameplayRosterReferenceRequest.parseFrom(
                        row.get("original_request", byte[].class)),
                    row.get("fence_state", String.class));
              } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(invalid);
              }
            });
  }

  /**
   * Records only an independently checked, authenticated Entity observation of a sealed original.
   */
  public CanonicalGameplayRosterRetirementAcknowledgementResponse acknowledgeRetirement(
      CanonicalGameplayRosterRetirementAcknowledgementRequest request,
      String authenticatedEntityUri) {
    requireWritableTransaction();
    CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(request);
    requireEntityPrincipal(request.getOriginalSelection(), authenticatedEntityUri);
    var operation =
        CanonicalGameplayRosterReferenceEvidence.uuid(
            request.getOriginalSelection().getOperationUuid());
    Record lookup =
        dsl.fetchOne(
            "SELECT target_fence_uuid FROM game_session_canonical_roster_retirement_transfers WHERE operation_uuid = ?",
            operation);
    if (lookup == null) {
      throw new OriginalUnavailableException();
    }
    // Same fence-before-original/transfer order as retirement and deletion. A retained ACK may
    // outlive that fence; only its exact immutable replay remains available then.
    dsl.fetchOne(
        "SELECT target_fence_uuid FROM game_session_canonical_roster_target_fences WHERE target_fence_uuid = ? FOR UPDATE",
        lookup.get("target_fence_uuid", java.util.UUID.class));
    Record transfer =
        dsl.fetchOne(
            "SELECT * FROM game_session_canonical_roster_retirement_transfers WHERE operation_uuid = ? FOR UPDATE",
            operation);
    if (transfer == null) {
      throw new OriginalUnavailableException();
    }
    if (transfer.get("acknowledgement_uuid") != null) {
      return exactAcknowledgement(request, authenticatedEntityUri, transfer);
    }
    if (!Arrays.equals(
        request.getOriginalSelection().toByteArray(),
        transfer.get("original_request", byte[].class))) {
      throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_ORIGINAL_CONFLICT");
    }
    var anchorRequest = request.getObservedRetirement().getRequest();
    Record anchorRow =
        dsl.fetchOne(
            "SELECT original_request FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            CanonicalGameplayRosterReferenceEvidence.uuid(anchorRequest.getOperationUuid()));
    if (anchorRow == null) {
      throw new OriginalUnavailableException();
    }
    if (!Arrays.equals(
        anchorRequest.toByteArray(), anchorRow.get("original_request", byte[].class))) {
      throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_ANCHOR_CONFLICT");
    }
    var original = readExact(request.getOriginalSelection(), false);
    var anchor = readExact(request.getObservedRetirement().getRequest(), false);
    CanonicalGameplayRosterReferenceEvidence.requireRetirement(original);
    if (!anchor.equals(request.getObservedRetirement())
        || !original.getTargetFenceUuid().equals(anchor.getTargetFenceUuid())
        || original.getOwnerFence() != anchor.getOwnerFence()
        || !original.getRetirementUuid().equals(anchor.getRetirementUuid())
        || !original
            .getTargetFenceUuid()
            .equals(transfer.get("target_fence_uuid", java.util.UUID.class).toString())
        || !original
            .getRetirementUuid()
            .equals(transfer.get("retirement_uuid", java.util.UUID.class).toString())
        || !Arrays.equals(
            request.getOriginalSelection().toByteArray(),
            transfer.get("original_request", byte[].class))) {
      throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_OBSERVATION_CONFLICT");
    }
    if (request.getSnapshotUuid().isEmpty()) {
      if (!original.getCanonicalRosterReceipt().isEmpty()) {
        throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_SNAPSHOT_REQUIRED");
      }
    } else {
      var roster = CanonicalGameplayRosterReferenceEvidence.receipt(original);
      if (!roster.getSnapshotUuid().equals(request.getSnapshotUuid())
          || !roster.getSnapshotDigest().equals(request.getSnapshotDigest())) {
        throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_SNAPSHOT_CONFLICT");
      }
    }
    Record retained =
        dsl.fetchSingle(
            "SELECT recovery_receipt FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            operation);
    byte[] recoveryBytes = retained.get("recovery_receipt", byte[].class);
    if (recoveryBytes != null) {
      try {
        var recovery =
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse
                .parseFrom(recoveryBytes);
        if (!recovery.getAdmissionUuid().equals(request.getAdmissionUuid())) {
          throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_ADMISSION_CONFLICT");
        }
      } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
        throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_RECOVERY_INVALID", invalid);
      }
    }
    var acknowledgement =
        CanonicalGameplayRosterRetirementEvidence.response(
            request,
            java.util.UUID.randomUUID(),
            Math.max(
                request.getMinimumRetentionMillis(),
                transfer.get("minimum_retention_millis", Long.class)));
    dsl.execute(
        "UPDATE game_session_canonical_roster_retirement_transfers SET acknowledgement_uuid = ?, request_digest = ?, "
            + "observation_uuid = ?, admission_uuid = ?, snapshot_uuid = ?::uuid, snapshot_digest = ?, entity_principal = ?, "
            + "effective_minimum_millis = ?, received_at = clock_timestamp() WHERE operation_uuid = ?",
        java.util.UUID.fromString(acknowledgement.getAcknowledgementUuid()),
        acknowledgement.getRequestDigest(),
        java.util.UUID.fromString(request.getObservationUuid()),
        java.util.UUID.fromString(request.getAdmissionUuid()),
        request.getSnapshotUuid().isEmpty() ? null : request.getSnapshotUuid(),
        request.getSnapshotDigest().isEmpty() ? null : request.getSnapshotDigest(),
        authenticatedEntityUri,
        acknowledgement.getMinimumRetentionMillis(),
        operation);
    return acknowledgement;
  }

  public CanonicalGameplayRosterRetirementConfirmationResponse confirmRetirementAcknowledgement(
      CanonicalGameplayRosterRetirementConfirmationRequest confirmation,
      String authenticatedEntityUri) {
    requireWritableTransaction();
    CanonicalGameplayRosterRetirementEvidence.requireConfirmation(confirmation);
    var request = confirmation.getAcknowledgementRequest();
    requireEntityPrincipal(request.getOriginalSelection(), authenticatedEntityUri);
    var operation =
        CanonicalGameplayRosterReferenceEvidence.uuid(
            request.getOriginalSelection().getOperationUuid());
    Record transfer =
        dsl.fetchOne(
            "SELECT * FROM game_session_canonical_roster_retirement_transfers WHERE operation_uuid = ? FOR UPDATE",
            operation);
    // Absence is a harmless confirmation response, never a first ACK or a release operation.
    if (transfer != null) {
      if (!exactAcknowledgement(request, authenticatedEntityUri, transfer)
          .equals(confirmation.getAcknowledgement())) {
        throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_CONFIRMATION_CONFLICT");
      }
      dsl.execute(
          "UPDATE game_session_canonical_roster_retirement_transfers SET confirmed_at = COALESCE(confirmed_at, clock_timestamp()) WHERE operation_uuid = ?",
          operation);
    }
    return CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation);
  }

  private static CanonicalGameplayRosterRetirementAcknowledgementResponse exactAcknowledgement(
      CanonicalGameplayRosterRetirementAcknowledgementRequest request,
      String principal,
      Record transfer) {
    if (transfer.get("acknowledgement_uuid") == null
        || !CanonicalGameplayRosterRetirementEvidence.requestDigest(request)
            .equals(transfer.get("request_digest", String.class))
        || !principal.equals(transfer.get("entity_principal", String.class))
        || !Arrays.equals(
            request.getOriginalSelection().toByteArray(),
            transfer.get("original_request", byte[].class))) {
      throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_ACKNOWLEDGEMENT_CONFLICT");
    }
    return CanonicalGameplayRosterRetirementEvidence.response(
        request,
        transfer.get("acknowledgement_uuid", java.util.UUID.class),
        transfer.get("effective_minimum_millis", Long.class));
  }

  private static void requireEntityPrincipal(
      GetCanonicalGameplayRosterReferenceRequest original, String principal) {
    if (!("spiffe://firemud/ns/" + original.getTargetNamespace() + "/sa/entity-management-service")
        .equals(principal)) {
      throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_ENTITY_PRINCIPAL_REQUIRED");
    }
  }

  public int purgeConfirmedRetirementAcknowledgements(int limit) {
    requireWritableTransaction();
    if (limit <= 0) {
      throw new IllegalArgumentException("Bounded retirement receipt page required");
    }
    return dsl.execute(
        "DELETE FROM game_session_canonical_roster_retirement_transfers WHERE operation_uuid IN ("
            + "SELECT t.operation_uuid FROM game_session_canonical_roster_retirement_transfers t "
            + "WHERE confirmed_at IS NOT NULL AND target_purged_at IS NOT NULL "
            + "AND GREATEST(confirmed_at, target_purged_at) + effective_minimum_millis * interval '1 millisecond' <= clock_timestamp() "
            + "AND NOT EXISTS (SELECT 1 FROM game_session_canonical_roster_target_fences f WHERE f.target_fence_uuid = t.target_fence_uuid) "
            + "AND NOT EXISTS (SELECT 1 FROM game_session_canonical_roster_selection s WHERE s.target_fence_uuid = t.target_fence_uuid) "
            + "ORDER BY operation_uuid LIMIT ? FOR UPDATE SKIP LOCKED)",
        limit);
  }

  public record TransferHealth(long oldestPendingSeconds, long cleanupLagSeconds) {}

  public TransferHealth transferHealth() {
    requireWritableTransaction();
    Record row =
        dsl.fetchSingle(
            "SELECT COALESCE(max(extract(epoch FROM clock_timestamp() - recorded_at)) FILTER (WHERE confirmed_at IS NULL), 0)::bigint AS pending, "
                + "COALESCE(max(GREATEST(0, extract(epoch FROM clock_timestamp() - GREATEST(confirmed_at, target_purged_at) "
                + "- effective_minimum_millis * interval '1 millisecond'))) FILTER (WHERE confirmed_at IS NOT NULL AND target_purged_at IS NOT NULL), 0)::bigint AS lag "
                + "FROM game_session_canonical_roster_retirement_transfers");
    return new TransferHealth(row.get("pending", Long.class), row.get("lag", Long.class));
  }

  public boolean purgeRetiredTarget(TargetCandidate candidate) {
    requireWritableTransaction();
    Record fence =
        dsl.fetchOne(
            "SELECT retired_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() AS horizon_passed "
                + "FROM game_session_canonical_roster_target_fences WHERE target_fence_uuid = ? AND fence_state = 'RETIRED' FOR UPDATE",
            candidate.fenceUuid());
    if (fence == null || !Boolean.TRUE.equals(fence.get("horizon_passed", Boolean.class))) {
      return false;
    }
    // A first ACK may have committed while this fence lock was waiting. Read its inventory
    // in a new statement after acquiring the lock, rather than using the earlier MVCC snapshot.
    if (!Boolean.TRUE.equals(
        dsl.fetchSingle(
                "SELECT gs_roster_retirement_observations_complete(?)", candidate.fenceUuid())
            .get(0, Boolean.class))) {
      return false;
    }
    dsl.execute(
        "UPDATE game_session_canonical_roster_retirement_transfers SET target_purged_at = COALESCE(target_purged_at, clock_timestamp()) WHERE target_fence_uuid = ?",
        candidate.fenceUuid());
    dsl.execute(
        "DELETE FROM game_session_canonical_roster_selection WHERE target_fence_uuid = ?",
        candidate.fenceUuid());
    return dsl.execute(
            "DELETE FROM game_session_canonical_roster_target_fences WHERE target_fence_uuid = ? AND fence_state = 'RETIRED'",
            candidate.fenceUuid())
        == 1;
  }

  public record Health(long oldestBlockingSeconds, long cleanupLagSeconds) {}

  public Health health() {
    requireWritableTransaction();
    Record row =
        dsl.fetchSingle(
            "SELECT COALESCE(max(extract(epoch FROM clock_timestamp() - s.recorded_at)) FILTER (WHERE f.fence_state = 'ACTIVE'), 0)::bigint AS oldest_blocking, "
                + "COALESCE(max(GREATEST(0, extract(epoch FROM clock_timestamp() - f.retired_at - f.minimum_retention_millis * interval '1 millisecond'))) "
                + "FILTER (WHERE f.fence_state = 'RETIRED'), 0)::bigint AS cleanup_lag "
                + "FROM game_session_canonical_roster_target_fences f JOIN game_session_canonical_roster_selection s USING (target_fence_uuid)");
    return new Health(row.get("oldest_blocking", Long.class), row.get("cleanup_lag", Long.class));
  }

  private static byte[] targetBinding(GetCanonicalGameplayRosterReferenceRequest request) {
    return request.toBuilder().clearOperationUuid().clearSessionUuid().build().toByteArray();
  }

  private static String digest(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  private static void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("CANONICAL_ROSTER_OWNER_TRANSACTION_REQUIRED");
    }
  }
}
