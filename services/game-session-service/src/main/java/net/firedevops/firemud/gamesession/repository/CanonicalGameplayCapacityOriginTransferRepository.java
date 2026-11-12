package net.firedevops.firemud.gamesession.repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/** Frozen pre-Account custody. An origin is never converted into an invented reservation. */
final class CanonicalGameplayCapacityOriginTransferRepository {
  private static final String ORIGIN = "game_session_original_new_capacity_origin";
  private static final String ITEM = "game_session_capacity_origin_transfer_item";
  private static final String TRANSFER = "game_session_region_owner_transfer";
  private final CanonicalGameplayRegionOwnerTransferRepository transfers;

  CanonicalGameplayCapacityOriginTransferRepository(
      CanonicalGameplayRegionOwnerTransferRepository transfers) {
    this.transfers = Objects.requireNonNull(transfers);
  }

  // SQL DENIED can precede a lost physical DENY reply. All states still require transfer fencing;
  // only a future independently retained terminal/cleanup proof may narrow this inventory.
  static boolean pending(DSLContext tx, UUID tenant, UUID game, UUID region) {
    return exists(
        tx,
        "SELECT 1 FROM " + ORIGIN + " WHERE tenant_id=? AND game_instance_id=? AND region_id=?",
        tenant,
        game,
        region);
  }

  /** One enrollment page under inventory -> runtime -> origin locks; Account is not consulted. */
  void capture(UUID transferId) {
    transfers
        .sql()
        .transaction(
            configuration -> {
              var tx = DSL.using(configuration);
              Record transfer =
                  CanonicalGameplayRegionOwnerTransferRepository.current(tx, transferId, true);
              var rows =
                  tx.fetch(
                      "SELECT o.* FROM "
                          + ORIGIN
                          + " o WHERE o.tenant_id=?"
                          + " AND o.game_instance_id=? AND o.region_id=?"
                          + " AND NOT EXISTS (SELECT 1 FROM "
                          + ITEM
                          + " i WHERE i.transfer_id=? AND i.origin_digest=o.origin_digest)"
                          + " ORDER BY o.origin_digest LIMIT ? FOR UPDATE OF o",
                      transfer.get("tenant_id", UUID.class),
                      transfer.get("game_instance_id", UUID.class),
                      transfer.get("region_id", UUID.class),
                      transferId,
                      CanonicalGameplayRegionOwnerTransferRepository.PAGE_SIZE);
              long ordinal =
                  tx.fetchSingle(
                          "SELECT coalesce(max(target_ordinal),-1) FROM "
                              + ITEM
                              + " WHERE transfer_id=?",
                          transferId)
                      .get(0, Long.class);
              for (Record row : rows) {
                var origin = decodeOrigin(row);
                if (!transfer
                        .get("old_owner_id", String.class)
                        .equals(row.get("custody_owner_id", String.class))
                    || !transfer
                        .get("old_owner_fence", Long.class)
                        .equals(row.get("custody_owner_fence", Long.class))) throw held();
                var assignment = transfers.assignmentForOrigin(tx, origin.transitionId());
                if (!assignment.tenantGameplayTag().equals(origin.tenantGameplayTag()))
                  throw held();
                ordinal = Math.incrementExact(ordinal);
                if (tx.execute(
                        "INSERT INTO "
                            + ITEM
                            + " (transfer_id,target_ordinal,origin_digest,canonical_origin,captured_state,old_owner_id,old_owner_fence,account_resolution_bytes,account_resolution_digest,tenant_gameplay_tag,workload_namespace,allocation_version)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        transferId,
                        ordinal,
                        origin.digest(),
                        origin.canonicalBytes(),
                        row.get("state", String.class),
                        row.get("custody_owner_id", String.class),
                        row.get("custody_owner_fence", Long.class),
                        row.get("account_resolution_bytes", byte[].class),
                        row.get("account_resolution_digest", String.class),
                        assignment.tenantGameplayTag(),
                        assignment.workloadNamespace(),
                        assignment.allocationVersion())
                    != 1) throw held();
              }
            });
  }

  void requireCaptured(UUID transferId) {
    requireCaptured(transfers.sql(), transferId);
  }

  static void requireCaptured(DSLContext tx, UUID transferId) {
    Record transfer = CanonicalGameplayRegionOwnerTransferRepository.current(tx, transferId, false);
    if (exists(
        tx,
        "SELECT 1 FROM "
            + ORIGIN
            + " o WHERE o.tenant_id=?"
            + " AND o.game_instance_id=? AND o.region_id=?"
            + " AND NOT EXISTS (SELECT 1 FROM "
            + ITEM
            + " i WHERE i.transfer_id=? AND i.origin_digest=o.origin_digest"
            + " AND i.canonical_origin=o.canonical_origin AND i.captured_state=o.state"
            + " AND i.old_owner_id=o.custody_owner_id AND i.old_owner_fence=o.custody_owner_fence"
            + " AND i.account_resolution_bytes IS NOT DISTINCT FROM o.account_resolution_bytes"
            + " AND i.account_resolution_digest IS NOT DISTINCT FROM o.account_resolution_digest)",
        transfer.get("tenant_id", UUID.class),
        transfer.get("game_instance_id", UUID.class),
        transfer.get("region_id", UUID.class),
        transferId)) throw held();
  }

  boolean hasItems(UUID transferId) {
    return exists(transfers.sql(), "SELECT 1 FROM " + ITEM + " WHERE transfer_id=?", transferId);
  }

  List<Item> unfenced(UUID transferId) {
    return page(transferId, -1, true);
  }

  List<Item> page(UUID transferId, long afterOrdinal) {
    return page(transferId, afterOrdinal, false);
  }

  private List<Item> page(UUID transferId, long afterOrdinal, boolean unfenced) {
    Record transfer =
        CanonicalGameplayRegionOwnerTransferRepository.observationCurrent(
            transfers.sql(), transferId);
    var result = new ArrayList<Item>();
    for (Record row :
        transfers
            .sql()
            .fetch(
                "SELECT o.*,i.target_ordinal,i.captured_state,i.old_owner_id,i.old_owner_fence,i.canonical_origin AS captured_origin,i.account_resolution_bytes AS captured_resolution,i.account_resolution_digest AS captured_resolution_digest,i.tenant_gameplay_tag AS captured_tag,i.workload_namespace,i.allocation_version FROM "
                    + ITEM
                    + " i JOIN "
                    + ORIGIN
                    + " o ON o.origin_digest=i.origin_digest WHERE i.transfer_id=? AND i.target_ordinal>?"
                    + (unfenced ? " AND i.fence_readback IS NULL" : "")
                    + " ORDER BY i.target_ordinal LIMIT ?",
                transferId,
                afterOrdinal,
                CanonicalGameplayRegionOwnerTransferRepository.PAGE_SIZE)) {
      result.add(load(transfer, row));
    }
    return List.copyOf(result);
  }

  private Item load(Record transfer, Record row) {
    var origin = decodeOrigin(row);
    boolean completed = "COMPLETED".equals(transfer.get("state", String.class));
    var capturedState =
        CanonicalGameplayCapacityOrigin.State.valueOf(row.get("captured_state", String.class));
    var currentState =
        CanonicalGameplayCapacityOrigin.State.valueOf(row.get("state", String.class));
    byte[] capturedResolution = row.get("captured_resolution", byte[].class);
    byte[] currentResolution = row.get("account_resolution_bytes", byte[].class);
    boolean unchangedClaim =
        capturedState == currentState
            && Arrays.equals(currentResolution, capturedResolution)
            && Objects.equals(
                row.get("account_resolution_digest", String.class),
                row.get("captured_resolution_digest", String.class));
    boolean successorClaim =
        completed
            && capturedState == CanonicalGameplayCapacityOrigin.State.OPEN
            && capturedResolution == null
            && row.get("captured_resolution_digest", String.class) == null
            && currentResolution != null
            && (currentState == CanonicalGameplayCapacityOrigin.State.CLOSURE_CLAIMED
                || currentState == CanonicalGameplayCapacityOrigin.State.DENIED);
    if (!Arrays.equals(origin.canonicalBytes(), row.get("captured_origin", byte[].class))
        || !origin.tenantGameplayTag().equals(row.get("captured_tag", String.class))
        || (!unchangedClaim && !successorClaim)) throw held();
    var assignment = transfers.assignmentForOrigin(transfers.sql(), origin.transitionId());
    if (!assignment.tenantGameplayTag().equals(row.get("tenant_gameplay_tag", String.class))
        || !assignment.workloadNamespace().equals(row.get("workload_namespace", String.class))
        || assignment.allocationVersion() != row.get("allocation_version", Integer.class))
      throw held();
    var binding = origin.candidate().bindingIdentity();
    if (!binding.tenantId().equals(transfer.get("tenant_id", UUID.class))
        || !binding.gameInstanceId().equals(transfer.get("game_instance_id", UUID.class))
        || !binding.regionId().equals(transfer.get("region_id", UUID.class))
        || binding.regionEpoch().longValueExact() != transfer.get("region_epoch", Long.class))
      throw held();
    String newOwner = transfer.get("successor_owner_id", String.class);
    Long newFence = transfer.get("successor_owner_fence", Long.class);
    if (newOwner == null || newFence == null) throw held();
    String expectedOwner =
        "FROZEN".equals(transfer.get("state", String.class))
            ? row.get("old_owner_id", String.class)
            : newOwner;
    long expectedFence =
        "FROZEN".equals(transfer.get("state", String.class))
            ? row.get("old_owner_fence", Long.class)
            : newFence;
    if (!expectedOwner.equals(row.get("custody_owner_id", String.class))
        || expectedFence != row.get("custody_owner_fence", Long.class)
        || (completed
            && !transfer
                .get("transfer_id", UUID.class)
                .equals(row.get("custody_transfer_id", UUID.class)))) throw held();
    return new Item(
        transfer.get("transfer_id", UUID.class),
        row.get("target_ordinal", Long.class),
        origin,
        row.get("old_owner_id", String.class),
        row.get("old_owner_fence", Long.class),
        newOwner,
        newFence,
        currentState);
  }

  Context readCurrent(UUID transferId, long targetOrdinal, long deadline) {
    return transfers.within(
        deadline,
        budget -> {
          if (deadline < budget.absoluteDeadlineMillis()) throw held();
          Record transfer =
              CanonicalGameplayRegionOwnerTransferRepository.observationCurrent(
                  transfers.sql(), transferId);
          Record row =
              transfers
                  .sql()
                  .fetchOne(
                      "SELECT o.*,i.target_ordinal,i.captured_state,i.old_owner_id,i.old_owner_fence,i.canonical_origin AS captured_origin,i.account_resolution_bytes AS captured_resolution,i.account_resolution_digest AS captured_resolution_digest,i.tenant_gameplay_tag AS captured_tag,i.workload_namespace,i.allocation_version FROM "
                          + ITEM
                          + " i JOIN "
                          + ORIGIN
                          + " o ON o.origin_digest=i.origin_digest WHERE i.transfer_id=? AND i.target_ordinal=?",
                      transferId,
                      targetOrdinal);
          if (row == null) throw held();
          var item = load(transfer, row);
          var binding = item.origin().candidate().bindingIdentity();
          String completion =
              "COMPLETED".equals(transfer.get("state", String.class))
                  ? digest(
                      (transferId
                              + "/"
                              + item.newOwner()
                              + "/"
                              + item.newFence()
                              + "/"
                              + transfer.get("successor_lease_correlation", String.class))
                          .getBytes(StandardCharsets.UTF_8))
                  : null;
          return new Context(
              transferId,
              targetOrdinal,
              transfer.get("intent_digest", String.class),
              transfer.get("source_digest", String.class),
              item.oldOwner(),
              item.oldFence(),
              binding.tenantId(),
              binding.gameInstanceId(),
              binding.regionId(),
              transfer.get("region_epoch", Long.class),
              item.origin().transitionId(),
              item.origin().request().identity().requestId(),
              binding.accountId(),
              binding.playableStateNamespaceId(),
              item.origin().digest(),
              item.origin().candidateDigest(),
              item.origin().tenantGameplayTag(),
              row.get("workload_namespace", String.class),
              row.get("allocation_version", Integer.class),
              item.state(),
              item.newOwner(),
              item.newFence(),
              transfer.get("successor_lease_correlation", String.class),
              transfer.get("state", String.class),
              completion);
        });
  }

  CanonicalGameplayTenantGameplayTagRepository.Assignment readAssignment(
      UUID transitionId, CanonicalGameplayTenantGameplayTagRepository tags, long deadline) {
    return transfers.readAssignment(transitionId, tags, deadline);
  }

  void retainFence(
      Item item,
      CanonicalGameplayCapacityOriginRedisOwner.Readback actual,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after,
      long deadline) {
    requireObservation(item, before, after, deadline);
    byte[] readback = exactReadback(item, actual);
    transfers
        .sql()
        .transaction(
            configuration -> {
              var tx = DSL.using(configuration);
              Record transfer =
                  CanonicalGameplayRegionOwnerTransferRepository.current(
                      tx, item.transferId(), true);
              String incarnation = transfer.get("incarnation_digest", String.class);
              if (incarnation != null && !incarnation.equals(after.incarnationDigest()))
                throw held();
              tx.execute(
                  "UPDATE "
                      + TRANSFER
                      + " SET incarnation_digest=? WHERE transfer_id=? AND incarnation_digest IS NULL",
                  after.incarnationDigest(),
                  item.transferId());
              Record row =
                  tx.fetchSingle(
                      "SELECT * FROM "
                          + ITEM
                          + " WHERE transfer_id=? AND target_ordinal=? FOR UPDATE",
                      item.transferId(),
                      item.ordinal());
              if (row.get("fence_readback", byte[].class) != null
                  && !Arrays.equals(readback, row.get("fence_readback", byte[].class)))
                throw held();
              if (!item.origin().digest().equals(row.get("origin_digest", String.class)))
                throw held();
              tx.execute(
                  "UPDATE "
                      + ITEM
                      + " SET fence_readback=?,incarnation_digest=?,before_payload_digest=?,after_payload_digest=?"
                      + " WHERE transfer_id=? AND target_ordinal=? AND fence_readback IS NULL",
                  readback,
                  after.incarnationDigest(),
                  before.payloadDigest(),
                  after.payloadDigest(),
                  item.transferId(),
                  item.ordinal());
            });
  }

  UUID beginVerification(UUID transferId) {
    requireComplete(transfers.sql(), transferId);
    UUID round = UUID.randomUUID();
    transfers
        .sql()
        .execute(
            "UPDATE "
                + TRANSFER
                + " SET origin_verification_round=? WHERE transfer_id=? AND state='FROZEN'",
            round,
            transferId);
    return round;
  }

  void retainVerification(
      Item item,
      UUID round,
      CanonicalGameplayCapacityOriginRedisOwner.Readback actual,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after,
      long deadline) {
    requireObservation(item, before, after, deadline);
    byte[] readback = exactReadback(item, actual);
    transfers
        .sql()
        .transaction(
            configuration -> {
              var tx = DSL.using(configuration);
              Record transfer =
                  CanonicalGameplayRegionOwnerTransferRepository.current(
                      tx, item.transferId(), true);
              if (!round.equals(transfer.get("origin_verification_round", UUID.class))
                  || !after
                      .incarnationDigest()
                      .equals(transfer.get("incarnation_digest", String.class))) throw held();
              Record retained =
                  tx.fetchSingle(
                      "SELECT origin_digest,fence_readback FROM "
                          + ITEM
                          + " WHERE transfer_id=? AND target_ordinal=? FOR UPDATE",
                      item.transferId(),
                      item.ordinal());
              if (!item.origin().digest().equals(retained.get("origin_digest", String.class))
                  || !Arrays.equals(readback, retained.get("fence_readback", byte[].class)))
                throw held();
              tx.execute(
                  "UPDATE "
                      + ITEM
                      + " SET verified_round=? WHERE transfer_id=? AND target_ordinal=?",
                  round,
                  item.transferId(),
                  item.ordinal());
            });
  }

  void requireRetainedPhysical(
      Item item,
      CanonicalGameplayCapacityOriginRedisOwner.Readback actual,
      String incarnationDigest) {
    requireExactMarker(item, actual);
    Record retained =
        transfers
            .sql()
            .fetchOne(
                "SELECT i.captured_state,i.fence_readback FROM "
                    + ITEM
                    + " i JOIN "
                    + TRANSFER
                    + " t ON t.transfer_id=i.transfer_id JOIN "
                    + ORIGIN
                    + " o ON o.origin_digest=i.origin_digest"
                    + " WHERE i.transfer_id=? AND i.target_ordinal=? AND i.origin_digest=?"
                    + " AND i.incarnation_digest=? AND t.state='COMPLETED'"
                    + " AND t.incarnation_digest=i.incarnation_digest"
                    + " AND i.verified_round=t.origin_verification_round"
                    + " AND o.canonical_origin=i.canonical_origin AND o.custody_transfer_id=t.transfer_id"
                    + " AND o.custody_owner_id=t.successor_owner_id"
                    + " AND o.custody_owner_fence=t.successor_owner_fence AND o.state=?",
                item.transferId(),
                item.ordinal(),
                item.origin().digest(),
                incarnationDigest,
                item.state().name());
    if (retained == null
        || !Arrays.equals(
            retained.get("fence_readback", byte[].class),
            encodeReadback(item, retained.get("captured_state", String.class)))) throw held();
  }

  static void requireComplete(DSLContext tx, UUID transferId) {
    requireCaptured(tx, transferId);
    Record transfer = CanonicalGameplayRegionOwnerTransferRepository.current(tx, transferId, false);
    if (exists(
        tx,
        "SELECT 1 FROM "
            + ITEM
            + " WHERE transfer_id=? AND (fence_readback IS NULL OR incarnation_digest IS DISTINCT FROM ?)",
        transferId,
        transfer.get("incarnation_digest", String.class))) throw held();
  }

  static void requireVerified(DSLContext tx, UUID transferId, UUID round) {
    requireComplete(tx, transferId);
    if (round == null) {
      // The complete anti-join above proves that there is no retained origin cohort to verify.
      // A missing round cannot qualify any captured origin item.
      if (exists(tx, "SELECT 1 FROM " + ITEM + " WHERE transfer_id=?", transferId)) throw held();
      return;
    }
    Record transfer = CanonicalGameplayRegionOwnerTransferRepository.current(tx, transferId, false);
    if (!round.equals(transfer.get("origin_verification_round", UUID.class))
        || exists(
            tx,
            "SELECT 1 FROM " + ITEM + " WHERE transfer_id=? AND verified_round IS DISTINCT FROM ?",
            transferId,
            round)) throw held();
  }

  /** Called only by actual runtime successor installation, inside its same SQL transaction. */
  static void promote(DSLContext tx, UUID transferId, UUID round, String newOwner, long newFence) {
    requireVerified(tx, transferId, round);
    long count =
        tx.fetchSingle("SELECT count(*) FROM " + ITEM + " WHERE transfer_id=?", transferId)
            .get(0, Long.class);
    int changed =
        tx.execute(
            "UPDATE "
                + ORIGIN
                + " o SET custody_owner_id=?,custody_owner_fence=?,custody_transfer_id=?"
                + " FROM "
                + ITEM
                + " i WHERE i.transfer_id=? AND i.origin_digest=o.origin_digest"
                + " AND i.canonical_origin=o.canonical_origin AND i.captured_state=o.state"
                + " AND i.old_owner_id=o.custody_owner_id AND i.old_owner_fence=o.custody_owner_fence"
                + " AND i.account_resolution_bytes IS NOT DISTINCT FROM o.account_resolution_bytes"
                + " AND i.account_resolution_digest IS NOT DISTINCT FROM o.account_resolution_digest",
            newOwner,
            newFence,
            transferId,
            transferId);
    if (changed != count) throw held();
  }

  Context requireCurrent(Item item, long deadline) {
    var context = readCurrent(item.transferId(), item.ordinal(), deadline);
    if (!context.originDigest().equals(item.origin().digest())
        || !context.oldRegionOwnerId().equals(item.oldOwner())
        || context.oldRegionOwnerFence() != item.oldFence()
        || !context.newOwner().equals(item.newOwner())
        || context.newFence() != item.newFence()
        || context.state() != item.state()) throw held();
    return context;
  }

  private void requireObservation(
      Item item,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after,
      long deadline) {
    var context = requireCurrent(item, deadline);
    if (before.ownerKind()
            != CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.ORIGIN_OWNER_TRANSFER
        || after.ownerKind()
            != CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.ORIGIN_OWNER_TRANSFER
        || !context.equals(before.originTransferContext())
        || !context.equals(after.originTransferContext())
        || !before.incarnationDigest().equals(after.incarnationDigest())
        || transfers.nowMillis() >= before.expiresAtEpochMillis()
        || transfers.nowMillis() >= after.expiresAtEpochMillis()) throw held();
  }

  /** No absence, TTL or partial marker can be serialized as a retained fence. */
  static byte[] exactReadback(
      Item item, CanonicalGameplayCapacityOriginRedisOwner.Readback actual) {
    requireExactMarker(item, actual);
    return encodeReadback(item, actual.state().name());
  }

  private static void requireExactMarker(
      Item item, CanonicalGameplayCapacityOriginRedisOwner.Readback actual) {
    if (actual == null
        || !item.origin().digest().equals(actual.originDigest())
        || !item.newOwner().equals(actual.owner())
        || item.newFence() != actual.fence()
        || item.state() != actual.state()) throw held();
  }

  private static byte[] encodeReadback(Item item, String state) {
    var output = new java.io.ByteArrayOutputStream();
    for (String value :
        List.of(
            "capacity-origin-transfer-readback/v1",
            item.origin().digest(),
            item.newOwner(),
            Long.toString(item.newFence()),
            state)) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
      output.write(':');
      output.writeBytes(bytes);
    }
    return output.toByteArray();
  }

  static CanonicalGameplayCapacityOrigin decodeOrigin(Record row) {
    var request =
        CanonicalGameplayRegionOwnerTransferRepository.decodeRequest(
            row.get("canonical_request", byte[].class), row.get("mutation_digest", String.class));
    var candidate =
        CanonicalGameplayOriginalNewCandidate.parseCanonical(
            row.get("canonical_candidate", byte[].class));
    var origin =
        new CanonicalGameplayCapacityOrigin(
            request,
            candidate,
            row.get("tenant_gameplay_tag", String.class),
            row.get("selected_owner_id", String.class),
            row.get("selected_owner_fence", Long.class));
    var binding = candidate.bindingIdentity();
    if (!origin.digest().equals(row.get("origin_digest", String.class))
        || !origin.candidateDigest().equals(row.get("candidate_digest", String.class))
        || !Arrays.equals(origin.canonicalBytes(), row.get("canonical_origin", byte[].class))
        || !request.identity().tenantId().equals(row.get("tenant_id", UUID.class))
        || !request.identity().requestId().equals(row.get("request_id", UUID.class))
        || !request.identity().admissionId().equals(row.get("admission_id", UUID.class))
        || !request.admissionAttemptId().equals(row.get("admission_attempt_id", UUID.class))
        || request.capacityRearmFence() != row.get("capacity_rearm_fence", Long.class)
        || !origin.transitionId().equals(row.get("transition_id", UUID.class))
        || !binding.gameInstanceId().equals(row.get("game_instance_id", UUID.class))
        || !binding.regionId().equals(row.get("region_id", UUID.class))) throw held();
    byte[] resolution = row.get("account_resolution_bytes", byte[].class);
    String resolutionDigest = row.get("account_resolution_digest", String.class);
    if ((resolution == null) != (resolutionDigest == null)
        || (resolution != null && !digest(resolution).equals(resolutionDigest))) throw held();
    return origin;
  }

  record Item(
      UUID transferId,
      long ordinal,
      CanonicalGameplayCapacityOrigin origin,
      String oldOwner,
      long oldFence,
      String newOwner,
      long newFence,
      CanonicalGameplayCapacityOrigin.State state) {
    Item {
      Objects.requireNonNull(transferId);
      Objects.requireNonNull(origin);
      Objects.requireNonNull(state);
      if (new UUID(0, 0).equals(transferId)
          || ordinal < 0
          || oldOwner == null
          || !oldOwner.matches("[A-Za-z0-9._:-]{1,120}")
          || newOwner == null
          || !newOwner.matches("[A-Za-z0-9._:-]{1,120}")
          || oldFence <= 0
          || newFence <= oldFence) throw held();
    }
  }

  record Context(
      UUID transferId,
      long targetOrdinal,
      String transferIntentDigest,
      String frozenSourceDigest,
      String oldRegionOwnerId,
      long oldRegionOwnerFence,
      UUID tenantId,
      UUID gameInstanceId,
      UUID regionId,
      long regionEpoch,
      UUID transitionId,
      UUID requestId,
      UUID accountId,
      UUID playableStateNamespaceId,
      String originDigest,
      String candidateDigest,
      String tenantGameplayTag,
      String workloadNamespace,
      int allocationVersion,
      CanonicalGameplayCapacityOrigin.State state,
      String newOwner,
      long newFence,
      String newLease,
      String controlState,
      String completionDigest) {
    Context {
      Objects.requireNonNull(transferId);
      Objects.requireNonNull(tenantId);
      Objects.requireNonNull(gameInstanceId);
      Objects.requireNonNull(regionId);
      Objects.requireNonNull(transitionId);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(accountId);
      Objects.requireNonNull(playableStateNamespaceId);
      Objects.requireNonNull(state);
      for (UUID value :
          List.of(
              transferId,
              tenantId,
              gameInstanceId,
              regionId,
              transitionId,
              requestId,
              accountId,
              playableStateNamespaceId)) if (new UUID(0, 0).equals(value)) throw held();
      if (targetOrdinal < 0
          || regionEpoch <= 0
          || oldRegionOwnerFence <= 0
          || newFence <= oldRegionOwnerFence
          || oldRegionOwnerId == null
          || !oldRegionOwnerId.matches("[A-Za-z0-9._:-]{1,120}")
          || newOwner == null
          || !newOwner.matches("[A-Za-z0-9._:-]{1,120}")
          || allocationVersion <= 0
          || !net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(
              workloadNamespace)) throw held();
      for (String digest :
          List.of(transferIntentDigest, frozenSourceDigest, originDigest, candidateDigest))
        if (!digest.matches("sha256:[0-9a-f]{64}")) throw held();
      if (newLease == null || !newLease.matches("[0-9a-f]{64}")) throw held();
      CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(tenantGameplayTag);
      if (!("FROZEN".equals(controlState)
          ? completionDigest == null
          : "COMPLETED".equals(controlState)
              && completionDigest != null
              && completionDigest.matches("sha256:[0-9a-f]{64}"))) throw held();
    }
  }

  private static String digest(byte[] value) {
    return "sha256:" + OriginalNewCapacityExecution.sha256(value);
  }

  private static boolean exists(DSLContext tx, String query, Object... args) {
    return Boolean.TRUE.equals(
        tx.fetchSingle("SELECT EXISTS (" + query + ")", args).get(0, Boolean.class));
  }

  private static IllegalStateException held() {
    return new IllegalStateException("Exact capacity origin transfer custody is held");
  }
}
