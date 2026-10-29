package net.firedevops.firemud.gamesession.repository;

import io.grpc.Context;
import io.grpc.Deadline;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.FinalResult;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.Selector;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBindingLocalAcknowledgement.Evidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manual read-only owner for Account's ORIGINAL_NEW final-admission result read.
 *
 * <p>This source reads the retained transition candidate, its actual durable account/issuer/region
 * acknowledgements, and the physical persistent schema-2 session. It never writes or adopts
 * session/index state and never accepts a caller-supplied payload.
 */
public final class CanonicalGameplayOriginalNewFinalAdmissionReadRepository {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final int MAX_SESSION_BYTES = 1024 * 1024;
  private static final long REDIS_TIMEOUT_MILLIS = 5_000L;
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final String TRANSITION = "game_session_canonical_binding_transition";
  private static final String INVENTORY = "game_session_canonical_gameplay_binding_inventory";
  private static final String RESERVATION = "game_session_canonical_issuer_partition_reservation";
  private static final String ACCOUNT_OBLIGATION =
      "game_session_canonical_binding_account_index_obligation";
  private static final String ACCOUNT_MEMBER_REPAIR =
      "game_session_canonical_account_member_repair_operation";
  private static final String ACCOUNT_COVERAGE_CONTROL =
      "game_session_canonical_account_coverage_control";
  private static final String ISSUER_OBLIGATION =
      "game_session_canonical_binding_issuer_index_obligation";
  private static final String REGION_OBLIGATION =
      "game_session_canonical_binding_region_bridge_obligation";
  private static final String ISSUER_ACK =
      "game_session_canonical_issuer_binding_local_acknowledgement";
  private static final String TENANT_TAG = "game_session_canonical_tenant_gameplay_tag";

  private final DSLContext dsl;
  private final RedisClient redisClient;
  private final CanonicalGameplayTenantGameplayTagRepository tenantTags;
  private final String workloadNamespace;
  private final Clock clock;

  public CanonicalGameplayOriginalNewFinalAdmissionReadRepository(
      DSLContext dsl,
      RedisClient redisClient,
      CanonicalGameplayTenantGameplayTagRepository tenantTags,
      String workloadNamespace,
      Clock clock) {
    this.dsl = Objects.requireNonNull(dsl, "Game Session DSLContext required");
    this.redisClient = Objects.requireNonNull(redisClient, "Coordination Redis client required");
    this.tenantTags = Objects.requireNonNull(tenantTags, "Persisted tenant-tag owner required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    this.clock = Objects.requireNonNull(clock, "Readback clock required");
  }

  /**
   * Returns actual readback only after full source, physical Redis, and post-read SQL agreement.
   */
  public FinalResult readActualOriginalFinalAdmission(
      Selector selector, String requestedNamespace) {
    Objects.requireNonNull(selector, "Account selector required");
    if (!workloadNamespace.equals(requestedNamespace)) {
      throw conflict("Original NEW read namespace differs from the configured workload");
    }
    requireNoAmbientTransaction();
    SourceSnapshot before = readCurrentSource(selector);

    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment;
    try {
      // readCurrentSource has proved the immutable mapping already exists. This call only resolves
      // that persisted row; a missing mapping fails before the tag owner can allocate anything.
      assignment = tenantTags.assignmentForTransition(selector.bindingDecisionId());
    } catch (RuntimeException unavailable) {
      throw conflict("Persisted tenant gameplay-tag assignment is unavailable");
    }
    requireAssignment(assignment, before.candidate());

    byte[] physical = readPersistentSession(assignment, before.candidate());
    try {
      FinalResult result = parseAndMatchFinalSession(selector, before, physical);
      SourceSnapshot after = readCurrentSource(selector);
      if (!before.fingerprint().equals(after.fingerprint())
          || !before.candidate().equals(after.candidate())) {
        throw unavailable("Original NEW retained SQL source changed during Redis readback");
      }
      return result;
    } finally {
      wipe(physical);
    }
  }

  private SourceSnapshot readCurrentSource(Selector selector) {
    requireNoAmbientTransaction();
    SourceRows rows =
        dsl.transactionResult(
            configuration -> {
              DSLContext tx = DSL.using(configuration);
              tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
              Record transition =
                  tx.fetchOne(
                      "SELECT * FROM " + TRANSITION + " WHERE transition_id = ?",
                      selector.bindingDecisionId());
              if (transition == null) throw conflict("Original NEW transition is unavailable");

              byte[] bindingRef = requiredBytes(transition, "candidate_binding_ref");
              Record candidateRow =
                  tx.fetchOne("SELECT * FROM " + INVENTORY + " WHERE binding_ref = ?", bindingRef);
              Record reservationRow =
                  tx.fetchOne(
                      "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ?",
                      requiredUuid(transition, "issuer_reservation_id"));
              byte[] candidateBytes = requiredBytes(transition, "original_new_candidate_bytes");
              CanonicalGameplayOriginalNewCandidate candidate =
                  CanonicalGameplayOriginalNewCandidate.parseCanonical(candidateBytes);
              AccountGameplayAdmissionLeaseEvidence lease =
                  requireOriginalSources(selector, workloadNamespace, transition, candidate);
              requireCandidateAndReservationRows(
                  transition, candidateRow, reservationRow, candidate);

              List<Record> accountRows =
                  tx.fetch(
                      "SELECT * FROM "
                          + ACCOUNT_OBLIGATION
                          + " WHERE transition_id = ? ORDER BY obligation_ordinal",
                      selector.bindingDecisionId());
              List<Record> accountRepairRows =
                  tx.fetch(
                      "SELECT * FROM "
                          + ACCOUNT_MEMBER_REPAIR
                          + " WHERE transition_id = ? AND obligation_ordinal = 0",
                      selector.bindingDecisionId());
              Record accountControlRow =
                  tx.fetchOne(
                      "SELECT * FROM " + ACCOUNT_COVERAGE_CONTROL + " WHERE account_id = ?",
                      candidate.bindingIdentity().accountId());
              List<Record> issuerObligationRows =
                  tx.fetch(
                      "SELECT * FROM "
                          + ISSUER_OBLIGATION
                          + " WHERE transition_id = ? AND binding_ref = ?",
                      selector.bindingDecisionId(),
                      bindingRef);
              Record regionRow =
                  tx.fetchOne(
                      "SELECT * FROM " + REGION_OBLIGATION + " WHERE transition_id = ?",
                      selector.bindingDecisionId());
              List<Record> issuerAckRows =
                  tx.fetch(
                      "SELECT * FROM "
                          + ISSUER_ACK
                          + " WHERE transition_id = ? AND binding_ref = ?",
                      selector.bindingDecisionId(),
                      bindingRef);
              Record tagRow =
                  tx.fetchOne(
                      "SELECT allocation_version, tenant_gameplay_tag FROM "
                          + TENANT_TAG
                          + " WHERE tenant_id = ?",
                      candidate.bindingIdentity().tenantId());

              requireAccountAcknowledgement(
                  accountRows, accountRepairRows, accountControlRow, candidate, transition);
              requireIssuerObligation(issuerObligationRows, candidate, transition);
              requireRegionAcknowledgement(regionRow, candidate, transition);
              requirePersistedTag(tagRow);
              if (issuerAckRows.size() != 1) {
                throw conflict("Exact original NEW issuer acknowledgement is unavailable");
              }
              UUID issuerAckOperationId = requiredUuid(issuerAckRows.getFirst(), "operation_id");
              String sourceFingerprint =
                  fingerprint(
                      Map.ofEntries(
                          Map.entry("transition", rowFields("transition", transition)),
                          Map.entry("candidate", rowFields("candidate", candidateRow)),
                          Map.entry("reservation", rowFields("reservation", reservationRow)),
                          Map.entry("account", rowFields("account", accountRows.getFirst())),
                          Map.entry(
                              "accountRepair",
                              rowFields("accountRepair", accountRepairRows.getFirst())),
                          Map.entry(
                              "accountControl", rowFields("accountControl", accountControlRow)),
                          Map.entry(
                              "issuerObligation",
                              rowFields("issuerObligation", issuerObligationRows.getFirst())),
                          Map.entry("region", rowFields("region", regionRow)),
                          Map.entry("issuerAck", rowFields("issuerAck", issuerAckRows.getFirst())),
                          Map.entry("tenantTag", rowFields("tenantTag", tagRow)),
                          Map.entry(
                              "candidateBytes", Base64.getEncoder().encodeToString(candidateBytes)),
                          Map.entry("leaseSha256", lease.sha256())));
              wipe(candidateBytes);
              return new SourceRows(
                  candidate,
                  issuerAckOperationId,
                  sourceFingerprint,
                  new FinalReceipt(
                      requiredText(transition, "original_new_final_session_sha256"),
                      requiredLong(transition, "original_new_admission_at_ms"),
                      requiredLong(transition, "original_new_continuity_expires_at_ms"),
                      requiredLong(transition, "original_new_session_duration_ms")));
            });

    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBindingLocalAcknowledgement issuerAck =
        CanonicalGameplayIssuerBindingLocalAcknowledgementRepository.readHistorical(
            dsl, rows.issuerAckOperationId());
    if (issuerAck == null)
      throw conflict("Exact original NEW issuer acknowledgement is unavailable");
    requireIssuerAcknowledgement(issuerAck, issuerAck.evidence(), rows.candidate(), selector);

    String fingerprint =
        CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.canonicalJsonSha256(
            Map.of(
                "sqlSource", rows.fingerprint(),
                "issuerAcknowledgementSha256", issuerAck.digest()));
    return new SourceSnapshot(rows.candidate(), fingerprint, rows.finalReceipt());
  }

  private static AccountGameplayAdmissionLeaseEvidence requireOriginalSources(
      Selector selector,
      String workloadNamespace,
      Record transition,
      CanonicalGameplayOriginalNewCandidate candidate) {
    String status = requiredText(transition, "status");
    if (!"COMMITTED".equals(status)) {
      throw conflict("Original NEW transition is not final-readable");
    }
    if (!"READBACK_CONFIRMED".equals(requiredText(transition, "original_new_candidate_state"))
        || !PREFIXED_SHA256
            .matcher(requiredText(transition, "original_new_candidate_readback_digest"))
            .matches()) {
      throw conflict("Original NEW candidate physical readback marker is absent");
    }
    byte[] storedCandidate = requiredBytes(transition, "original_new_candidate_bytes");
    try {
      if (!candidate.exactlyMatches(storedCandidate)
          || !prefixedSha256(storedCandidate)
              .equals(requiredText(transition, "original_new_candidate_digest"))) {
        throw conflict("Original NEW retained candidate bytes or digest differ");
      }
    } finally {
      wipe(storedCandidate);
    }

    if (optionalBytes(transition, "expected_prior_binding_ref") != null
        || decimalNullable(transition, "expected_prior_binding_generation") != null
        || !candidate.bindingDecisionId().equals(selector.bindingDecisionId())
        || !candidate.transition().transitionId().equals(selector.bindingDecisionId())
        || !candidate.bindingIdentity().tenantId().equals(requiredUuid(transition, "tenant_id"))
        || !candidate
            .bindingIdentity()
            .playableStateNamespaceId()
            .equals(requiredUuid(transition, "playable_state_namespace_id"))
        || !candidate
            .bindingIdentity()
            .characterId()
            .equals(requiredUuid(transition, "character_id"))
        || !Arrays.equals(
            candidate.bindingIdentity().bindingRef().bytes(),
            requiredBytes(transition, "candidate_binding_ref"))
        || !candidate
            .transition()
            .bindingGeneration()
            .equals(decimal(transition, "candidate_binding_generation"))
        || !candidate
            .transition()
            .issuerReservationId()
            .equals(requiredUuid(transition, "issuer_reservation_id"))
        || !candidate
            .transition()
            .candidateAccountIndexFence()
            .equals(requiredUuid(transition, "candidate_account_index_fence"))) {
      throw conflict("Original NEW transition differs from its retained candidate identity");
    }
    requireNoActiveTransitionAccountControl(transition);

    String evidenceJson = requiredText(transition, "admission_lease_evidence");
    AccountGameplayAdmissionLeaseEvidence lease;
    try {
      lease = AccountGameplayAdmissionLeaseEvidence.parseCanonical(evidenceJson);
    } catch (RuntimeException malformed) {
      throw conflict("Original NEW transition has malformed retained Account lease evidence");
    }
    Map<String, Object> carrier = lease.carrier();
    if (!candidate.leaseEvidence().hasSameIdentity(lease)
        || !candidate.leaseEvidence().canonicalJson().equals(evidenceJson)
        || !candidate
            .leaseEvidence()
            .sha256()
            .equals(requiredText(transition, "admission_lease_digest"))
        || lease.leaseKind() != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
        || !"NEW_BINDING".equals(requiredText(transition, "admission_lease_kind"))
        || !lease.leaseFence().equals(decimal(transition, "admission_lease_fence"))
        || !leaseUuid(carrier, "requestId").equals(requiredUuid(transition, "admission_request_id"))
        || !leaseUuid(carrier, "leaseId").equals(requiredUuid(transition, "admission_lease_id"))
        || !selector.originalLeaseId().equals(leaseUuid(carrier, "leaseId"))
        || selector.originalLeaseFence() != longExact(lease.leaseFence(), "lease fence")
        || !selector.originalLeaseEvidenceSha256().equals(lease.sha256())
        || !selector.requestId().equals(leaseUuid(carrier, "requestId"))
        || !selector.accountId().equals(candidate.bindingIdentity().accountId())
        || !selector.tenantId().equals(candidate.bindingIdentity().tenantId())
        || !selector.sessionId().toString().equals(candidate.bindingIdentity().sessionId())
        || !workloadNamespace.equals(carrier.get("targetNamespace"))
        || !("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")
            .equals(carrier.get("callerWorkload"))
        || !GameSessionAccountDelegationProfile.ISSUER.equals(
            candidate.bindingIdentity().issuerId())
        || !candidate.targetEvidence().targetNamespace().equals(workloadNamespace)
        || !matchesTargetColumns(transition, candidate.targetEvidence())) {
      throw conflict("Original NEW Account lease or target differs from retained SQL decision");
    }
    return lease;
  }

  private static void requireIssuerAcknowledgement(
      CanonicalGameplayIssuerBindingLocalAcknowledgement acknowledgement,
      Evidence evidence,
      CanonicalGameplayOriginalNewCandidate candidate,
      Selector selector) {
    var reservation = candidate.reservation();
    if (!acknowledgement.transitionId().equals(candidate.transition().transitionId())
        || !evidence.transitionId().equals(candidate.transition().transitionId())
        || !evidence
            .candidateAccountIndexFence()
            .equals(candidate.transition().candidateAccountIndexFence())
        || !evidence.issuerId().equals(candidate.bindingIdentity().issuerId())
        || !Arrays.equals(evidence.bindingRef(), candidate.bindingIdentity().bindingRef().bytes())
        || !evidence.bindingGeneration().equals(candidate.transition().bindingGeneration())
        || !evidence.reservation().reservationId().equals(reservation.reservationId())
        || !evidence.reservation().transitionId().equals(reservation.transitionId())
        || !evidence.reservation().reservationFence().equals(reservation.reservationFence())
        || !evidence.reservation().partitionId().equals(reservation.partitionId())
        || !evidence
            .reservation()
            .issuerCoverageOperationId()
            .equals(reservation.issuerCoverageOperationId())
        || !evidence
            .reservation()
            .issuerCoverageOperationFence()
            .equals(reservation.issuerCoverageOperationFence())
        || !evidence.reservation().coverageFence().equals(reservation.coverageFence())
        || !evidence
            .reservation()
            .inventorySnapshotRevision()
            .equals(reservation.inventorySnapshotRevision())
        || !selector.bindingDecisionId().equals(candidate.bindingDecisionId())) {
      throw conflict("Retained issuer acknowledgement differs from the exact original candidate");
    }
  }

  private static boolean matchesTargetColumns(Record row, CanonicalPlayableTarget target) {
    OffsetDateTime terminalAt = row.get("admission_target_terminal_at", OffsetDateTime.class);
    return target.targetNamespace().equals(row.get("admission_target_namespace", String.class))
        && target.tenantSlug().equals(row.get("admission_target_tenant_slug", String.class))
        && target.canonicalTenantId().equals(row.get("admission_target_tenant_id", UUID.class))
        && target.worldSlug().equals(row.get("admission_target_world_slug", String.class))
        && target
            .worldDisplayName()
            .equals(row.get("admission_target_world_display_name", String.class))
        && target.realmId().equals(row.get("admission_target_realm_id", UUID.class))
        && target.realmSlug().equals(row.get("admission_target_realm_slug", String.class))
        && target
            .realmDisplayName()
            .equals(row.get("admission_target_realm_display_name", String.class))
        && target.gameSessionTenantId()
            == requiredLong(row, "admission_target_game_session_tenant_id")
        && target.gameInstanceId() == requiredLong(row, "admission_target_game_instance_id")
        && target
            .playableStateNamespaceId()
            .equals(row.get("admission_target_playable_state_namespace_id", UUID.class))
        && target
            .playableStateScope()
            .equals(row.get("admission_target_playable_state_scope", String.class))
        && target
            .canonicalGameInstanceId()
            .equals(row.get("admission_target_canonical_game_instance_id", UUID.class))
        && target
            .canonicalVersionId()
            .equals(row.get("admission_target_canonical_version_id", UUID.class))
        && target.runtimeVersionId() == requiredLong(row, "admission_target_runtime_version_id")
        && target.catalogRevision() == requiredLong(row, "admission_target_catalog_revision")
        && target.pointerVersion() == requiredLong(row, "admission_target_pointer_version")
        && target
            .admissionPointerSnapshotDigest()
            .equals(row.get("admission_target_pointer_snapshot_digest", String.class))
        && target.activeWorldEpoch() == requiredLong(row, "admission_target_active_world_epoch")
        && target
            .initialAdmissionRequestId()
            .equals(row.get("admission_target_initial_admission_request_id", String.class))
        && target
            .initialAdmissionRequestDigest()
            .equals(row.get("admission_target_initial_admission_request_digest", String.class))
        && target
            .initialAdmissionOriginKind()
            .name()
            .equals(row.get("admission_target_origin_kind", String.class))
        && Objects.equals(
            target.expectedPriorPointerVersion(),
            row.get("admission_target_expected_prior_pointer_version", Long.class))
        && target.holdId().equals(row.get("admission_target_hold_id", UUID.class))
        && target.holdFence().equals(row.get("admission_target_hold_fence", UUID.class))
        && target
            .holdBindingDigest()
            .equals(row.get("admission_target_hold_binding_digest", String.class))
        && target.auditEventId() == requiredLong(row, "admission_target_audit_event_id")
        && target
            .ownerProofDigest()
            .equals(row.get("admission_target_owner_proof_digest", String.class))
        && target
            .ownerProofOutcome()
            .name()
            .equals(row.get("admission_target_owner_proof_outcome", String.class))
        && !target.positiveDurableAbort()
        && Boolean.FALSE.equals(row.get("admission_target_positive_durable_abort", Boolean.class))
        && terminalAt != null
        && target.ownerProofTerminalAt().equals(terminalAt.toInstant())
        && target
            .characterCreationPolicy()
            .equals(row.get("admission_target_character_creation_policy", String.class));
  }

  private static void requireCandidateAndReservationRows(
      Record transition,
      Record candidateRow,
      Record reservationRow,
      CanonicalGameplayOriginalNewCandidate candidate) {
    if (candidateRow == null || reservationRow == null) {
      throw conflict("Original NEW candidate or reservation row is unavailable");
    }
    CanonicalGameplayBindingIdentity identity = candidate.bindingIdentity();
    String status = requiredText(transition, "status");
    String candidateLifecycle = "COMMITTED".equals(status) ? "ACTIVE" : "PROVISIONAL";
    String reservationLifecycle = "COMMITTED".equals(status) ? "BOUND" : "RESERVED";
    if (!Arrays.equals(requiredBytes(candidateRow, "binding_ref"), identity.bindingRef().bytes())
        || !readIdentity(candidateRow).equals(identity)
        || !identity.bindingRef().equals(candidate.transition().bindingRef())
        || !identity.bindingRef().equals(requiredRef(reservationRow, "binding_ref"))
        || !identity.accountId().equals(requiredUuid(reservationRow, "account_id"))
        || !identity.tenantId().equals(requiredUuid(reservationRow, "tenant_id"))
        || !identity
            .playableStateNamespaceId()
            .equals(requiredUuid(reservationRow, "playable_state_namespace_id"))
        || !identity.characterId().equals(requiredUuid(reservationRow, "character_id"))
        || !identity
            .playableStateScope()
            .equals(requiredText(reservationRow, "playable_state_scope"))
        || !identity.gameInstanceId().equals(requiredUuid(reservationRow, "game_instance_id"))
        || identity.runtimeGameInstanceId()
            != requiredLong(reservationRow, "runtime_game_instance_id")
        || !identity.sessionId().equals(requiredText(reservationRow, "session_id"))
        || !identity.regionId().equals(requiredUuid(reservationRow, "region_id"))
        || !identity.regionEpoch().equals(decimal(reservationRow, "region_epoch"))
        || !identity.issuerId().equals(requiredText(reservationRow, "issuer_id"))
        || !identity
            .issuerAuthGeneration()
            .equals(decimal(reservationRow, "issuer_auth_generation"))
        || !identity
            .issuerIndexLayoutVersion()
            .equals(decimal(reservationRow, "issuer_index_layout_version"))
        || !identity
            .issuerIndexPartitionCount()
            .equals(decimal(reservationRow, "issuer_index_partition_count"))
        || !identity
            .issuerIndexPartitionCapacity()
            .equals(decimal(reservationRow, "issuer_index_partition_capacity"))
        || !candidate
            .transition()
            .bindingGeneration()
            .equals(decimal(candidateRow, "binding_generation"))
        || !candidate
            .reservation()
            .partitionId()
            .equals(decimal(candidateRow, "issuer_partition_id"))
        || !candidate
            .transition()
            .bindingGeneration()
            .equals(decimal(reservationRow, "binding_generation"))
        || !candidate
            .transition()
            .transitionId()
            .equals(requiredUuid(candidateRow, "transition_id"))
        || !candidate
            .transition()
            .transitionId()
            .equals(requiredUuid(reservationRow, "transition_id"))
        || !candidate
            .transition()
            .issuerReservationId()
            .equals(requiredUuid(candidateRow, "issuer_reservation_id"))
        || !candidate
            .transition()
            .issuerReservationId()
            .equals(requiredUuid(reservationRow, "reservation_id"))
        || !candidate
            .transition()
            .candidateAccountIndexFence()
            .equals(requiredUuid(candidateRow, "account_index_fence"))
        || !candidate
            .transition()
            .reservationFence()
            .equals(requiredUuid(reservationRow, "reservation_fence"))
        || !candidate.reservation().partitionId().equals(decimal(reservationRow, "partition_id"))
        || !candidate
            .reservation()
            .issuerCoverageOperationId()
            .equals(requiredUuid(reservationRow, "issuer_coverage_operation_id"))
        || !candidate
            .reservation()
            .issuerCoverageOperationFence()
            .equals(decimal(reservationRow, "issuer_coverage_operation_fence"))
        || !candidate
            .reservation()
            .coverageFence()
            .equals(decimal(reservationRow, "coverage_fence"))
        || !candidate
            .reservation()
            .inventorySnapshotRevision()
            .equals(decimal(reservationRow, "inventory_snapshot_revision"))
        || !candidateLifecycle.equals(requiredText(candidateRow, "lifecycle"))
        || !accountIndexStateFor(status).equals(requiredText(candidateRow, "account_index_state"))
        || !issuerIndexStateFor(status).equals(requiredText(candidateRow, "issuer_index_state"))
        || !reservationLifecycle.equals(requiredText(reservationRow, "lifecycle"))
        || !decimal(transition, "inventory_revision")
            .equals(decimal(candidateRow, "inventory_revision"))
        || !decimal(transition, "inventory_revision")
            .equals(decimal(reservationRow, "inventory_revision"))) {
      throw conflict("Original NEW candidate or reservation differs from the retained transition");
    }
  }

  private static void requireAccountAcknowledgement(
      List<Record> rows,
      List<Record> repairRows,
      Record control,
      CanonicalGameplayOriginalNewCandidate candidate,
      Record transition) {
    if (rows.size() != 1 || repairRows.size() != 1 || control == null) {
      throw conflict("Exact original NEW account acknowledgement or control is unavailable");
    }
    Record row = rows.getFirst();
    Record repair = repairRows.getFirst();
    String expectedMember =
        new CanonicalGameplayAccountIndexMember(
                candidate.bindingIdentity().bindingRef(),
                candidate.transition().bindingGeneration(),
                candidate.transition().candidateAccountIndexFence())
            .value();
    if (requiredInt(row, "obligation_ordinal") != 0
        || !candidate.bindingIdentity().bindingRef().equals(requiredRef(row, "binding_ref"))
        || !candidate.bindingIdentity().accountId().equals(requiredUuid(row, "account_id"))
        || !candidate.bindingIdentity().tenantId().equals(requiredUuid(row, "tenant_id"))
        || !"ADD_OR_RETAIN".equals(requiredText(row, "action"))
        || !candidate.transition().bindingGeneration().equals(decimal(row, "binding_generation"))
        || !candidate
            .transition()
            .candidateAccountIndexFence()
            .equals(requiredUuid(row, "account_index_fence"))
        || !"BEFORE_FINAL_CAS".equals(requiredText(row, "execution_phase"))
        || !"ACKNOWLEDGED".equals(requiredText(row, "status"))
        || !"PRESENT_AWAITING_COVERAGE".equals(requiredText(row, "projection_state"))
        || !expectedMember.equals(requiredText(row, "projection_member"))
        || decimal(row, "projection_inventory_revision").signum() <= 0
        || decimal(row, "projection_inventory_revision")
                .compareTo(decimal(row, "inventory_revision"))
            > 0
        || decimal(row, "inventory_revision").compareTo(decimal(transition, "inventory_revision"))
            > 0
        || !candidate.transition().transitionId().equals(requiredUuid(repair, "transition_id"))
        || requiredInt(repair, "obligation_ordinal") != 0
        || !candidate.bindingIdentity().accountId().equals(requiredUuid(repair, "account_id"))
        || !candidate.bindingIdentity().tenantId().equals(requiredUuid(repair, "tenant_id"))
        || !candidate.bindingIdentity().bindingRef().equals(requiredRef(repair, "binding_ref"))
        || !candidate.transition().bindingGeneration().equals(decimal(repair, "binding_generation"))
        || !candidate
            .transition()
            .candidateAccountIndexFence()
            .equals(requiredUuid(repair, "account_index_fence"))
        || !"ADD_OR_RETAIN".equals(requiredText(repair, "original_action"))
        || decimalNullable(repair, "original_expected_prior_generation") != null
        || !"BEFORE_FINAL_CAS".equals(requiredText(repair, "original_execution_phase"))
        || !"REQUIRED".equals(requiredText(repair, "original_status"))
        || !originalAccountProjectionMatchesRepair(row, repair, expectedMember)
        || decimal(repair, "operation_fence").signum() <= 0
        || requiredUuid(repair, "coverage_fence").equals(new UUID(0L, 0L))
        || decimal(repair, "inventory_snapshot_revision").signum() <= 0
        || decimal(repair, "original_inventory_revision")
                .compareTo(decimal(repair, "inventory_snapshot_revision"))
            > 0
        || !"ADD_OR_RETAIN".equals(requiredText(repair, "desired_action"))
        || !expectedMember.equals(requiredText(repair, "member"))
        || !"NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(requiredText(repair, "account_control_state"))
        || !"ACKNOWLEDGED".equals(requiredText(repair, "status"))
        || !"PRESENT".equals(requiredText(repair, "readback_state"))
        || !expectedMember.equals(requiredText(repair, "readback_member"))
        || decimal(repair, "coverage_generation")
                .compareTo(decimal(repair, "inventory_snapshot_revision"))
            <= 0
        || decimal(repair, "completed_inventory_revision")
                .compareTo(decimal(repair, "inventory_snapshot_revision"))
            <= 0
        || decimal(repair, "completed_inventory_revision")
                .compareTo(decimal(transition, "inventory_revision"))
            > 0
        || !"NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(requiredText(control, "state"))
        || !candidate.bindingIdentity().accountId().equals(requiredUuid(control, "account_id"))
        || !decimal(control, "last_operation_fence")
            .equals(decimal(repair, "account_control_epoch"))
        || control.get("current_operation_id", UUID.class) != null
        || decimalNullable(control, "current_operation_fence") != null
        || control.get("account_admission_fence", UUID.class) != null
        || control.get("historical_account_admission_fence", UUID.class) != null
        || control.get("coverage_fence", UUID.class) != null
        || decimalNullable(control, "inventory_snapshot_revision") != null
        || decimalNullable(control, "coverage_generation") != null) {
      throw conflict("Retained account member acknowledgement differs from the exact candidate");
    }
  }

  static boolean originalAccountProjectionMatchesRepair(
      Record obligation, Record repair, String expectedMember) {
    String state = requiredText(repair, "original_projection_state");
    String member = repair.get("original_projection_member", String.class);
    BigInteger revision = decimalNullable(repair, "original_projection_inventory_revision");
    boolean exactOriginal =
        ("REQUIRED".equals(state) && member == null && revision == null)
            || ("PRESENT_AWAITING_COVERAGE".equals(state)
                && expectedMember.equals(member)
                && revision != null
                && revision.signum() > 0);
    return exactOriginal && projectionRevisionMatchesRepair(obligation, repair);
  }

  private static boolean projectionRevisionMatchesRepair(Record obligation, Record repair) {
    BigInteger originalProjectionRevision =
        decimalNullable(repair, "original_projection_inventory_revision");
    BigInteger expected =
        originalProjectionRevision == null
            ? decimal(repair, "completed_inventory_revision")
            : originalProjectionRevision;
    return expected.equals(decimal(obligation, "projection_inventory_revision"));
  }

  private static void requireNoActiveTransitionAccountControl(Record transition) {
    if (!"NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(requiredText(transition, "account_coverage_state"))
        || transition.get("account_coverage_operation_id", UUID.class) != null
        || decimalNullable(transition, "account_coverage_operation_fence") != null
        || transition.get("account_coverage_lifecycle", String.class) != null
        || !"NOT_APPLICABLE".equals(requiredText(transition, "account_admission_fence_kind"))
        || transition.get("account_admission_fence", UUID.class) != null
        || !"NOT_APPLICABLE"
            .equals(requiredText(transition, "historical_account_admission_fence_kind"))
        || transition.get("historical_account_admission_fence", UUID.class) != null
        || !"NOT_APPLICABLE".equals(requiredText(transition, "resolved_scope_kind"))
        || transition.get("resolved_scope_account_id", UUID.class) != null
        || transition.get("account_coverage_fence", UUID.class) != null
        || decimalNullable(transition, "account_coverage_generation") != null
        || decimalNullable(transition, "account_inventory_snapshot_revision") != null) {
      throw conflict(
          "Original NEW transition no longer retains its no-active Account control source");
    }
  }

  private static void requireIssuerObligation(
      List<Record> rows, CanonicalGameplayOriginalNewCandidate candidate, Record transition) {
    if (rows.size() != 1) throw conflict("Exact original NEW issuer obligation is unavailable");
    Record row = rows.getFirst();
    if (!candidate.transition().transitionId().equals(requiredUuid(row, "transition_id"))
        || !candidate.bindingIdentity().bindingRef().equals(requiredRef(row, "binding_ref"))
        || !candidate.transition().bindingGeneration().equals(decimal(row, "binding_generation"))
        || !candidate.transition().issuerReservationId().equals(requiredUuid(row, "reservation_id"))
        || !issuerObligationStateFor(requiredText(transition, "status"))
            .equals(requiredText(row, "status"))
        || row.get("local_release_operation_id", UUID.class) != null
        || decimal(row, "inventory_revision").signum() <= 0
        || decimal(row, "inventory_revision").compareTo(decimal(transition, "inventory_revision"))
            > 0) {
      throw conflict("Retained issuer obligation differs from the exact candidate");
    }
  }

  private static void requireRegionAcknowledgement(
      Record row, CanonicalGameplayOriginalNewCandidate candidate, Record transition) {
    if (row == null
        || !candidate.transition().transitionId().equals(requiredUuid(row, "transition_id"))
        || !candidate.bindingIdentity().bindingRef().equals(requiredRef(row, "binding_ref"))
        || !candidate.transition().bindingGeneration().equals(decimal(row, "binding_generation"))
        || !candidate.bindingIdentity().accountId().equals(requiredUuid(row, "account_id"))
        || !candidate.bindingIdentity().tenantId().equals(requiredUuid(row, "tenant_id"))
        || !candidate
            .bindingIdentity()
            .gameInstanceId()
            .equals(requiredUuid(row, "game_instance_id"))
        || candidate.bindingIdentity().runtimeGameInstanceId()
            != requiredLong(row, "runtime_game_instance_id")
        || !candidate.bindingIdentity().sessionId().equals(requiredText(row, "session_id"))
        || !candidate.bindingIdentity().regionId().equals(requiredUuid(row, "region_id"))
        || !candidate.bindingIdentity().regionEpoch().equals(decimal(row, "region_epoch"))
        || !"ACKNOWLEDGED".equals(requiredText(row, "status"))
        || !"ABSENT".equals(requiredText(row, "bridge_preimage_state"))
        || row.get("bridge_operation_id", UUID.class) == null
        || requiredLong(row, "runtime_entity_id") <= 0L
        || requiredLong(row, "entity_projection_generation") <= 0L
        || requiredLong(row, "entity_projection_fence") <= 0L
        || row.get("assignment_uuid", UUID.class) == null
        || decimal(row, "executor_fence").signum() <= 0
        || !SHA256.matcher(requiredText(row, "lease_token_correlation")).matches()
        || !SHA256.matcher(requiredText(row, "redis_readback_digest")).matches()
        || row.get("acknowledged_at", OffsetDateTime.class) == null
        || !SHA256.matcher(requiredText(row, "assignment_intent_digest")).matches()
        || !SHA256.matcher(requiredText(row, "entity_reference_digest")).matches()
        || !SHA256.matcher(requiredText(row, "world_source_digest")).matches()
        || !PREFIXED_SHA256.matcher(requiredText(row, "world_preparation_input_digest")).matches()
        || !SHA256.matcher(requiredText(row, "world_graph_sha256")).matches()
        || requiredUuid(row, "canonical_region_id").equals(new UUID(0L, 0L))
        || row.get("world_capture_id", UUID.class) == null
        || decimal(row, "world_lifecycle_epoch").signum() <= 0
        || decimal(row, "world_lifecycle_row_version").signum() < 0
        || decimal(row, "inventory_revision").signum() <= 0
        || decimal(row, "inventory_revision").compareTo(decimal(transition, "inventory_revision"))
            > 0) {
      throw conflict("Retained region bridge acknowledgement differs from the exact candidate");
    }
  }

  private static void requirePersistedTag(Record row) {
    if (row == null
        || requiredInt(row, "allocation_version") <= 0
        || !requiredText(row, "tenant_gameplay_tag").matches("g[1-9][0-9]{0,9}-[0-9a-f]{32}")) {
      throw conflict("Persisted tenant gameplay-tag mapping is unavailable");
    }
  }

  private static CanonicalGameplayBindingIdentity readIdentity(Record row) {
    return new CanonicalGameplayBindingIdentity(
        requiredUuid(row, "account_id"),
        requiredUuid(row, "tenant_id"),
        requiredUuid(row, "playable_state_namespace_id"),
        requiredUuid(row, "character_id"),
        requiredText(row, "playable_state_scope"),
        requiredUuid(row, "game_instance_id"),
        requiredLong(row, "runtime_game_instance_id"),
        requiredText(row, "session_id"),
        requiredUuid(row, "region_id"),
        decimal(row, "region_epoch"),
        requiredText(row, "issuer_id"),
        decimal(row, "issuer_auth_generation"),
        decimal(row, "issuer_index_layout_version"),
        decimal(row, "issuer_index_partition_count"),
        decimal(row, "issuer_index_partition_capacity"));
  }

  private void requireAssignment(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayOriginalNewCandidate candidate) {
    CanonicalGameplayBindingIdentity identity = candidate.bindingIdentity();
    if (!assignment.isBindingTransition()
        || !candidate.transition().transitionId().equals(assignment.transitionId())
        || !identity.tenantId().equals(assignment.tenantId())
        || !identity.accountId().equals(assignment.accountId())
        || !identity.playableStateNamespaceId().equals(assignment.playableStateNamespaceId())
        || !identity.characterId().equals(assignment.characterId())
        || !identity.gameInstanceId().equals(assignment.gameInstanceId())
        || !identity.sessionId().equals(assignment.sessionId())
        || !workloadNamespace.equals(assignment.workloadNamespace())) {
      throw conflict("Resolved tenant gameplay tag differs from retained binding target");
    }
    CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(
        assignment.tenantGameplayTag());
  }

  private byte[] readPersistentSession(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayOriginalNewCandidate candidate) {
    requireNoAmbientTransaction();
    String textKey =
        CanonicalGameplaySessionRedisKeys.session(
            assignment, candidate.bindingIdentity().bindingRef());
    if (textKey.length() > 512) throw conflict("Canonical session key exceeds its bound");
    byte[] key = textKey.getBytes(StandardCharsets.UTF_8);
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      if (!"string".equals(await(commands.type(key)))
          || !Long.valueOf(-1L).equals(await(commands.pttl(key)))) {
        throw conflict("Finalized canonical session is absent, non-string, or expiring");
      }
      byte[] physical = await(commands.get(key));
      if (physical == null || physical.length == 0 || physical.length > MAX_SESSION_BYTES) {
        wipe(physical);
        throw conflict("Finalized canonical session is absent or exceeds its bound");
      }
      return physical;
    } catch (CanonicalGameplayBindingInventoryConflictException conflict) {
      throw conflict;
    } catch (RuntimeException unavailable) {
      throw unavailable("Finalized canonical session Redis readback is unavailable", unavailable);
    } finally {
      wipe(key);
    }
  }

  private FinalResult parseAndMatchFinalSession(
      Selector selector, SourceSnapshot source, byte[] physical) {
    CanonicalGameplayOriginalNewCandidate expectedCandidate = source.candidate();
    try (CanonicalGameplayOriginalNewAuthenticatedSession.Finalized finalized =
        CanonicalGameplayOriginalNewAuthenticatedSession.parseFinalized(physical, clock)) {
      CanonicalGameplayOriginalNewCandidate actualCandidate = finalized.candidate();
      if (!expectedCandidate.equals(actualCandidate)) {
        throw conflict("Finalized session candidate differs from the retained original candidate");
      }
      AccountGameplayAdmissionLeaseEvidence lease = expectedCandidate.leaseEvidence();
      Map<String, Object> carrier = lease.carrier();
      if (!selector.requestId().toString().equals(carrier.get("requestId"))
          || !selector.accountId().equals(expectedCandidate.bindingIdentity().accountId())
          || !selector.tenantId().equals(expectedCandidate.bindingIdentity().tenantId())
          || !selector
              .sessionId()
              .toString()
              .equals(expectedCandidate.bindingIdentity().sessionId())
          || !selector.bindingDecisionId().equals(expectedCandidate.bindingDecisionId())
          || !selector.originalLeaseId().toString().equals(carrier.get("leaseId"))
          || selector.originalLeaseFence() != longExact(lease.leaseFence(), "original lease fence")
          || !selector.originalLeaseEvidenceSha256().equals(lease.sha256())
          || !selector.tokenSha256().equals(finalized.authTokenHash())
          || selector.tokenGeneration() != 1L
          || !"1".equals(finalized.tokenGeneration())
          || selector.issuanceFence()
              != positiveLong(carrier.get("issuanceFence"), "issuance fence")
          || !Long.toString(selector.issuanceFence()).equals(finalized.issuanceFence())
          || !selector
              .bindingScopeSha256()
              .equals(
                  CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.canonicalJsonSha256(
                      carrier.get("bindingScope")))
          || !selector
              .membershipBaselineSha256()
              .equals(
                  CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.canonicalJsonSha256(
                      carrier.get("membershipBaseline")))
          || !selector
              .checkpointsSha256()
              .equals(
                  CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.canonicalJsonSha256(
                      carrier.get("outboxCheckpoints")))) {
        throw conflict(
            "Finalized session authentication values differ from Account's original anchors");
      }
      // The final token is Account's new bound lineage, not the earlier LOGIN token
      // retained in the immutable admission lease. Account supplies its own exact bound
      // token hash; the SQL receipt independently anchors the complete physical value.
      FinalReceipt receipt = source.finalReceipt();
      if (!SHA256.matcher(receipt.sessionSha256()).matches()
          || !receipt.sessionSha256().equals(sha256(physical))
          || receipt.admissionAtMillis() != finalized.admissionAtMillis()
          || receipt.continuityExpiresAtMillis() != finalized.continuityExpiresAtMillis()
          || receipt.durationMillis() != finalized.canonicalSessionDurationMillis()) {
        throw conflict("Finalized session differs from the committed original controller receipt");
      }

      byte[] handle = finalized.opaqueRebindHandle();
      try {
        if (!selector.rebindHandleSha256().equals(sha256(handle))) {
          throw conflict("Finalized session rebind handle digest differs from Account's anchor");
        }
      } finally {
        wipe(handle);
      }

      long admissionAtMillis = finalized.admissionAtMillis();
      long continuityExpiryMillis = finalized.continuityExpiresAtMillis();
      long nowMillis = clock.millis();
      BigInteger leaseExpiryMillis = positiveBigInteger(carrier.get("expiresAt"), "lease expiry");
      if (nowMillis <= 0L
          || admissionAtMillis > nowMillis
          || BigInteger.valueOf(admissionAtMillis).compareTo(leaseExpiryMillis) > 0
          || nowMillis >= continuityExpiryMillis) {
        throw conflict("Finalized original admission is outside its retained current time bounds");
      }
      return new FinalResult(
          selector,
          admissionAtMillis,
          continuityExpiryMillis,
          finalized.canonicalSessionDurationMillis(),
          sha256(physical));
    } catch (CanonicalGameplayBindingInventoryConflictException conflict) {
      throw conflict;
    } catch (RuntimeException malformed) {
      throw conflict("Physical finalized original session does not match its retained source");
    }
  }

  private static RedisAsyncCommands<byte[], byte[]> requireCommands(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null || !connection.isOpen()) {
      throw unavailable("Coordination Redis connection is closed");
    }
    return connection.async();
  }

  private static void requirePrincipal(String principal) {
    if (!PRINCIPAL.equals(principal)) {
      throw unavailable("Coordination Redis ACL principal is not the exact Game Session owner");
    }
  }

  private static <T> T await(RedisFuture<T> future) {
    try {
      long timeoutMillis = REDIS_TIMEOUT_MILLIS;
      Deadline requestDeadline = Context.current().getDeadline();
      if (requestDeadline != null) {
        long remainingMillis = requestDeadline.timeRemaining(TimeUnit.MILLISECONDS);
        if (remainingMillis <= 0L) throw unavailable("Original NEW final read deadline expired");
        timeoutMillis = Math.min(timeoutMillis, remainingMillis);
      }
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw unavailable("Coordination Redis readback was interrupted", interrupted);
    } catch (ExecutionException | TimeoutException | RuntimeException failure) {
      future.cancel(true);
      throw unavailable("Coordination Redis readback did not return bounded evidence", failure);
    }
  }

  private static long positiveLong(Object value, String name) {
    return positiveBigInteger(value, name).longValueExact();
  }

  private static BigInteger positiveBigInteger(Object value, String name) {
    if (!(value instanceof String encoded) || !encoded.matches("[1-9][0-9]{0,18}")) {
      throw conflict("Retained Account lease has an invalid " + name);
    }
    try {
      BigInteger parsed = new BigInteger(encoded);
      if (parsed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
        throw conflict("Retained Account lease " + name + " exceeds its bound");
      return parsed;
    } catch (NumberFormatException malformed) {
      throw conflict("Retained Account lease has an invalid " + name);
    }
  }

  private static long longExact(BigInteger value, String name) {
    try {
      return value.longValueExact();
    } catch (ArithmeticException malformed) {
      throw conflict("Retained source " + name + " exceeds its bound");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String prefixedSha256(byte[] bytes) {
    return "sha256:" + sha256(bytes);
  }

  private static String requiredText(Record row, String name) {
    String value = row.get(name, String.class);
    if (value == null || value.isEmpty()) throw conflict("Retained source is missing " + name);
    return value;
  }

  private static UUID requiredUuid(Record row, String name) {
    UUID value = row.get(name, UUID.class);
    if (value == null) throw conflict("Retained source is missing " + name);
    return value;
  }

  private static long requiredLong(Record row, String name) {
    Long value = row.get(name, Long.class);
    if (value == null) throw conflict("Retained source is missing " + name);
    return value;
  }

  private static int requiredInt(Record row, String name) {
    Integer value = row.get(name, Integer.class);
    if (value == null) throw conflict("Retained source is missing " + name);
    return value;
  }

  private static BigInteger decimal(Record row, String name) {
    BigDecimal value = row.get(name, BigDecimal.class);
    if (value == null) throw conflict("Retained source is missing " + name);
    try {
      return value.toBigIntegerExact();
    } catch (ArithmeticException malformed) {
      throw conflict("Retained source has a fractional " + name);
    }
  }

  private static BigInteger decimalNullable(Record row, String name) {
    BigDecimal value = row.get(name, BigDecimal.class);
    if (value == null) return null;
    try {
      return value.toBigIntegerExact();
    } catch (ArithmeticException malformed) {
      throw conflict("Retained source has a fractional " + name);
    }
  }

  private static byte[] requiredBytes(Record row, String name) {
    byte[] value = row.get(name, byte[].class);
    if (value == null || value.length == 0) throw conflict("Retained source is missing " + name);
    return value.clone();
  }

  private static byte[] optionalBytes(Record row, String name) {
    byte[] value = row.get(name, byte[].class);
    return value == null ? null : value.clone();
  }

  private static net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef requiredRef(
      Record row, String name) {
    byte[] bytes = requiredBytes(row, name);
    try {
      return net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef.fromExactBytes(
          bytes);
    } finally {
      wipe(bytes);
    }
  }

  private static UUID leaseUuid(Map<String, Object> carrier, String name) {
    Object value = carrier.get(name);
    if (!(value instanceof String encoded)) throw conflict("Retained lease has an invalid " + name);
    try {
      UUID uuid = UUID.fromString(encoded);
      if (!uuid.toString().equals(encoded) || uuid.version() != 4 || uuid.variant() != 2) {
        throw conflict("Retained lease has an invalid " + name);
      }
      return uuid;
    } catch (IllegalArgumentException malformed) {
      throw conflict("Retained lease has an invalid " + name);
    }
  }

  private static String fingerprint(Map<String, String> values) {
    return CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.canonicalJsonSha256(values);
  }

  private static String rowFields(String label, Record row) {
    if (row == null) throw conflict("Retained " + label + " source is unavailable");
    java.util.TreeMap<String, String> fields = new java.util.TreeMap<>();
    for (org.jooq.Field<?> field : row.fields()) {
      Object value = row.get(field);
      String normalized;
      if (value == null) normalized = "<null>";
      else if (value instanceof byte[] bytes)
        normalized = "base64:" + Base64.getEncoder().encodeToString(bytes);
      else normalized = value.toString();
      fields.put(field.getName(), normalized);
    }
    return CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.canonicalJsonSha256(
        Map.of("label", label, "fields", fields));
  }

  private static void wipe(byte[] value) {
    if (value != null) Arrays.fill(value, (byte) 0);
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw conflict("Original NEW final read cannot hold an ambient SQL transaction");
    }
  }

  private static CanonicalGameplayBindingInventoryConflictException conflict(String message) {
    return new CanonicalGameplayBindingInventoryConflictException(message);
  }

  private static IllegalStateException unavailable(String message) {
    return new IllegalStateException(message);
  }

  private static IllegalStateException unavailable(String message, Throwable cause) {
    return new IllegalStateException(message, cause);
  }

  private static String accountIndexStateFor(String transitionStatus) {
    if ("PROVISIONAL".equals(transitionStatus) || "COMMITTED".equals(transitionStatus)) {
      return "ACKNOWLEDGED";
    }
    throw conflict("Original NEW transition status is not final-readable");
  }

  private static String issuerIndexStateFor(String transitionStatus) {
    if ("PROVISIONAL".equals(transitionStatus)) return "REPAIR_REQUIRED";
    if ("COMMITTED".equals(transitionStatus)) return "ACKNOWLEDGED";
    throw conflict("Original NEW transition status is not final-readable");
  }

  private static String issuerObligationStateFor(String transitionStatus) {
    if ("PROVISIONAL".equals(transitionStatus)) return "REQUIRED";
    if ("COMMITTED".equals(transitionStatus)) return "ACKNOWLEDGED";
    throw conflict("Original NEW transition status is not final-readable");
  }

  private record SourceRows(
      CanonicalGameplayOriginalNewCandidate candidate,
      UUID issuerAckOperationId,
      String fingerprint,
      FinalReceipt finalReceipt) {}

  private record FinalReceipt(
      String sessionSha256,
      long admissionAtMillis,
      long continuityExpiresAtMillis,
      long durationMillis) {}

  private record SourceSnapshot(
      CanonicalGameplayOriginalNewCandidate candidate,
      String fingerprint,
      FinalReceipt finalReceipt) {}
}
