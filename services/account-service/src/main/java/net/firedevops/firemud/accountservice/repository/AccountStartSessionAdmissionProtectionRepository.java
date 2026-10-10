package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionAuthorityCapture;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionSettlement;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable Account storage for the original StartSession admission source protection.
 *
 * <p>Every operation requires a writable READ COMMITTED Account transaction. The producer
 * authenticates Game Session and establishes original actor/JTI/reference currentness before
 * acquisition; SQL revalidates the full retained source, World participation, owner observation,
 * lease and request bindings. Historical lookup is integrity-only and does not authenticate a
 * caller, establish currentness, release protection, or settle a terminal outcome.
 */
@Repository
public class AccountStartSessionAdmissionProtectionRepository {
  private static final String PROTECTIONS = "account_start_session_admission_protections";
  private static final String SOURCES = "account_start_session_admission_protection_sources";
  private static final String SETTLEMENTS =
      "account_start_session_admission_protection_settlements";
  private static final String CAPTURES = "account_start_session_authority_captures";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Set<String> PROTECTION_COLUMNS =
      Set.of(
          "protection_id",
          "protection_fence",
          "control_plane_request_id",
          "original_post_authorization_tuple",
          "account_redemption_projection",
          "game_session_owner_mutation_id",
          "game_session_owner_attempt_id",
          "game_session_owner_fence",
          "original_lease_expires_at",
          "account_world_participation_id",
          "account_world_participation_fence",
          "target_namespace",
          "canonical_tenant_id",
          "canonical_game_instance_id",
          "capture_source_version",
          "capture_source_fence",
          "capture_sha256",
          "world_admission_hold_identity_bytes",
          "request_binding_bytes",
          "request_binding_digest",
          "producer_xid",
          "created_at");
  private static final Set<String> SOURCE_COLUMNS =
      Set.of("protection_id", "source_key", "source_evidence");
  private static final Set<String> CAPTURE_COLUMNS =
      Set.of(
          "control_plane_request_id",
          "source_version",
          "source_fence",
          "snapshot_sha256",
          "canonical_sha256",
          "canonical_capture_bytes",
          "canonical_snapshot_bytes");
  private static final Set<String> SETTLEMENT_COLUMNS =
      Set.of("protection_id", "outcome", "terminal_bytes", "terminal_digest", "settled_at");
  private static final Set<String> CAPTURE_SNAPSHOT_FIELDS =
      Set.of(
          "schema",
          "controlPlaneRequestId",
          "preAuthorizationTuple",
          "mutationDigest",
          "accountId",
          "tenantId",
          "targetOwner",
          "loggingWorkloadUri",
          "reservationOwnerId",
          "reservationClaimFence",
          "controlUiOperationId",
          "controlUiTokenJti",
          "controlUiTokenHash",
          "controlUiSignerReceipt",
          "controlUiSignerReceiptSha256",
          "sourceVectorEvidence",
          "sourceVector",
          "outboxCheckpoints",
          "authorityTuple",
          "membershipVersion",
          "accountIdentitySource",
          "issuanceFence",
          "issuanceFenceSourceVersion");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve the injected DSLContext precondition; this transactional Spring repository must remain proxyable, owns no resources, and declares no finalizer.")
  public AccountStartSessionAdmissionProtectionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Inserts the exact original protection and complete captured source vector, or returns its
   * immutable winner on an exact request retry.
   *
   * <p>The caller invokes this inside the Account currentness callback, after the independent Game
   * Session observation and the original World participation/attempt comparison. A duplicate never
   * replaces the retained identity; its complete row and source children are checked only after
   * database locks have revalidated currentness and the finite original expiry.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountStartSessionAdmissionProtectionEvidence createOrReadExact(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAuthorityCapture capture) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(request, "exact admission protection request is required");
    Objects.requireNonNull(capture, "exact current Account capture is required");
    CaptureValue expectedCapture = CaptureValue.from(capture);
    List<SourceEvidence> expectedSources =
        sourceVector(expectedCapture.snapshotBytes(), request.originalTuple());
    requireCaptureMatches(capture, request.originalTuple(), selectCapture(capture, false));

    Record existing = selectByRequestId(request.originalTuple().controlPlaneRequestId(), false);
    if (existing != null) {
      UUID existingId = requiredUuid(existing, "protection_id");
      long existingFence = positive(requiredLong(existing, "protection_fence"));
      assertCurrent(existingId, existingFence, request.canonicalBytes());
      Record locked = selectById(existingId, true);
      if (locked == null) throw unavailable();
      return decodeAndRequireExact(locked, request, expectedCapture, expectedSources);
    }

    UUID proposedId = UUID.randomUUID();
    int inserted = insert(request, capture, expectedSources, proposedId);
    if (inserted == 1) insertSources(proposedId, expectedSources);

    Record winner = selectByRequestId(request.originalTuple().controlPlaneRequestId(), false);
    if (winner == null) throw unavailable();
    UUID protectionId = requiredUuid(winner, "protection_id");
    long protectionFence = positive(requiredLong(winner, "protection_fence"));
    assertCurrent(protectionId, protectionFence, request.canonicalBytes());
    Record locked = selectById(protectionId, true);
    if (locked == null) throw unavailable();
    AccountStartSessionAdmissionProtectionEvidence evidence =
        decodeAndRequireExact(locked, request, expectedCapture, expectedSources);
    if (inserted == 1 && !proposedId.equals(evidence.accountProtectionId())) throw unavailable();
    return evidence;
  }

  /**
   * Lookup-only current read for independent post-commit readback. It never inserts or renews a
   * lease, and locks the source/capture/World/owner/protection chain before exact comparison.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountStartSessionAdmissionProtectionEvidence> findCurrentExact(
      AccountStartSessionAdmissionProtectionRequest request) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(request, "exact admission protection request is required");
    Record observed = selectByRequestId(request.originalTuple().controlPlaneRequestId(), false);
    if (observed == null) return Optional.empty();
    UUID protectionId = requiredUuid(observed, "protection_id");
    long protectionFence = positive(requiredLong(observed, "protection_fence"));
    assertCurrent(protectionId, protectionFence, request.canonicalBytes());

    Record captureRow = selectCaptureForOriginalTuple(request.originalTuple(), false);
    if (captureRow == null) throw unavailable();
    CaptureValue capture = captureFromRow(captureRow);
    List<SourceEvidence> expectedSources =
        sourceVector(capture.snapshotBytes(), request.originalTuple());
    Record locked = selectById(protectionId, true);
    if (locked == null) throw unavailable();
    return Optional.of(decodeAndRequireExact(locked, request, capture, expectedSources));
  }

  /**
   * Lookup-only read of one immutable protection for later independently authenticated terminal
   * verification.
   *
   * <p>This method validates the retained identity, canonical request, capture and complete source
   * vector, but performs no authentication or currentness check. It does not consult lease expiry,
   * lock or renew the protection, insert a settlement, or release source protection.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountStartSessionAdmissionProtectionEvidence> findHistoricalExact(
      UUID protectionId, long protectionFence) {
    requireWritableReadCommittedTransaction();
    if (protectionId == null || NIL_UUID.equals(protectionId) || protectionFence <= 0L) {
      throw unavailable();
    }

    Record row = selectById(protectionId, false);
    if (row == null) return Optional.empty();

    UUID retainedId = requiredUuid(row, "protection_id");
    long retainedFence = positive(requiredLong(row, "protection_fence"));
    if (!protectionId.equals(retainedId) || protectionFence != retainedFence) {
      throw unavailable();
    }

    AccountStartSessionAdmissionProtectionRequest request;
    try {
      request =
          AccountStartSessionAdmissionProtectionRequest.decode(
              requiredBytes(row, "request_binding_bytes"));
    } catch (RuntimeException malformed) {
      throw unavailable();
    }

    Record captureRow = selectCaptureForOriginalTuple(request.originalTuple(), false);
    if (captureRow == null) throw unavailable();
    CaptureValue capture = captureFromRow(captureRow);
    if (!request.originalTuple().controlPlaneRequestId().equals(capture.controlPlaneRequestId())) {
      throw unavailable();
    }
    List<SourceEvidence> expectedSources =
        sourceVector(capture.snapshotBytes(), request.originalTuple());
    return Optional.of(decodeAndRequireExact(row, request, capture, expectedSources));
  }

  /**
   * Inserts an exact terminal settlement or returns the immutable winner on an exact retry.
   *
   * <p>This runs only after the authenticated owner terminal read has completed outside SQL. It
   * locks the original canonical source-lock rows before the immutable protection row, compares the
   * complete historical Account evidence without consulting live expiry, and writes the outcome
   * derived from the typed owner proof. The database trigger repeats the closed structural binding
   * checks; neither layer authenticates the remote producer.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredSettlement settleExact(AccountStartSessionAdmissionProtectionSettlement settlement) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(settlement, "exact admission protection settlement is required");
    AccountStartSessionAdmissionProtectionEvidence expectedEvidence =
        settlement.protectionEvidence();
    UUID protectionId = expectedEvidence.accountProtectionId();
    long protectionFence = expectedEvidence.accountProtectionFence();

    lockOriginalSourceRows(expectedEvidence);
    Record locked = selectById(protectionId, true);
    if (locked == null
        || !protectionId.equals(requiredUuid(locked, "protection_id"))
        || protectionFence != positive(requiredLong(locked, "protection_fence"))) {
      throw unavailable();
    }
    AccountStartSessionAdmissionProtectionEvidence retainedEvidence =
        findHistoricalExact(protectionId, protectionFence)
            .orElseThrow(AccountStartSessionAdmissionProtectionRepository::unavailable);
    requireSameEvidence(expectedEvidence, retainedEvidence);

    byte[] terminalBytes = settlement.canonicalBytes();
    dsl.execute(
        "INSERT INTO "
            + SETTLEMENTS
            + " (protection_id, outcome, terminal_bytes, terminal_digest) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (protection_id) DO NOTHING",
        protectionId,
        settlement.outcome().name(),
        terminalBytes,
        settlement.digest());
    StoredSettlement stored =
        readSettlementExact(protectionId, protectionFence, retainedEvidence)
            .orElseThrow(AccountStartSessionAdmissionProtectionRepository::unavailable);
    AccountStartSessionAdmissionProtectionSettlement storedSettlement = stored.settlement();
    if (!Arrays.equals(terminalBytes, storedSettlement.canonicalBytes())
        || !settlement.digest().equals(storedSettlement.digest())
        || settlement.outcome() != stored.outcome()) {
      throw conflict();
    }
    return stored;
  }

  /**
   * Lookup-only historical settlement read. It validates the full retained protection and
   * settlement binding but never checks current authority or either original expiry.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredSettlement> findSettlementExact(UUID protectionId, long protectionFence) {
    requireWritableReadCommittedTransaction();
    if (protectionId == null || NIL_UUID.equals(protectionId) || protectionFence <= 0L) {
      throw unavailable();
    }
    Optional<AccountStartSessionAdmissionProtectionEvidence> evidence =
        findHistoricalExact(protectionId, protectionFence);
    if (evidence.isEmpty()) return Optional.empty();
    return readSettlementExact(protectionId, protectionFence, evidence.get());
  }

  private void lockOriginalSourceRows(
      AccountStartSessionAdmissionProtectionEvidence expectedEvidence) {
    UUID protectionId = expectedEvidence.accountProtectionId();
    long protectionFence = expectedEvidence.accountProtectionFence();
    Record observed = selectById(protectionId, false);
    if (observed == null
        || !protectionId.equals(requiredUuid(observed, "protection_id"))
        || protectionFence != positive(requiredLong(observed, "protection_fence"))) {
      throw unavailable();
    }

    AccountStartSessionAdmissionProtectionRequest retainedRequest;
    try {
      retainedRequest =
          AccountStartSessionAdmissionProtectionRequest.decode(
              requiredBytes(observed, "request_binding_bytes"));
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
    if (!MessageDigest.isEqual(
        retainedRequest.canonicalBytes(), expectedEvidence.request().canonicalBytes())) {
      throw conflict();
    }
    Record captureRow = selectCaptureForOriginalTuple(retainedRequest.originalTuple(), false);
    if (captureRow == null) throw unavailable();
    CaptureValue capture = captureFromRow(captureRow);
    List<SourceEvidence> expectedSources =
        sourceVector(capture.snapshotBytes(), retainedRequest.originalTuple());
    requireExactSources(expectedSources, expectedEvidence.sourceEvidenceVector());

    // The captured vector is already in canonical Java string order. Lock one existing source
    // lock row at a time in that order, matching the V129 owner-before-protection lock discipline.
    for (SourceEvidence source : expectedSources) {
      Record sourceLock =
          dsl.fetchOne(
              "SELECT source_key FROM account_draft_authorization_source_locks "
                  + "WHERE source_key = ? FOR UPDATE NOWAIT",
              source.key());
      if (sourceLock == null || !source.key().equals(requiredString(sourceLock, "source_key"))) {
        throw unavailable();
      }
    }
  }

  private Optional<StoredSettlement> readSettlementExact(
      UUID protectionId,
      long protectionFence,
      AccountStartSessionAdmissionProtectionEvidence expectedEvidence) {
    Record row =
        dsl.fetchOne("SELECT * FROM " + SETTLEMENTS + " WHERE protection_id = ?", protectionId);
    if (row == null) return Optional.empty();
    try {
      requireColumns(row, SETTLEMENT_COLUMNS, "admission protection settlement");
      if (!protectionId.equals(requiredUuid(row, "protection_id"))) throw unavailable();
      String outcome = requiredString(row, "outcome");
      byte[] terminalBytes = requiredBytes(row, "terminal_bytes");
      String terminalDigest = requiredString(row, "terminal_digest");
      Instant settledAt = requiredTimestamp(row, "settled_at").toInstant();
      if (!terminalDigest.equals(digestPrefixed(terminalBytes))) throw unavailable();

      AccountStartSessionAdmissionProtectionSettlement settlement;
      try {
        settlement = AccountStartSessionAdmissionProtectionSettlement.decode(terminalBytes);
      } catch (RuntimeException malformed) {
        throw unavailable();
      }
      AccountStartSessionAdmissionProtectionEvidence retainedEvidence =
          settlement.protectionEvidence();
      if (!Arrays.equals(terminalBytes, settlement.canonicalBytes())
          || !terminalDigest.equals(settlement.digest())
          || !outcome.equals(settlement.outcome().name())
          || !protectionId.equals(retainedEvidence.accountProtectionId())
          || protectionFence != retainedEvidence.accountProtectionFence()) {
        throw conflict();
      }
      requireSameEvidence(expectedEvidence, retainedEvidence);
      return Optional.of(new StoredSettlement(settlement, settledAt));
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalStateException state
          && ("StartSession admission protection is unavailable".equals(state.getMessage())
              || "StartSession admission protection conflicts with its immutable binding"
                  .equals(state.getMessage()))) {
        throw state;
      }
      throw unavailable();
    }
  }

  private static void requireSameEvidence(
      AccountStartSessionAdmissionProtectionEvidence expected,
      AccountStartSessionAdmissionProtectionEvidence actual) {
    if (!MessageDigest.isEqual(expected.canonicalBytes(), actual.canonicalBytes()))
      throw conflict();
  }

  /** Immutable database receipt for one exact original Game Session terminal value. */
  public record StoredSettlement(
      AccountStartSessionAdmissionProtectionSettlement settlement, Instant settledAt) {
    public StoredSettlement {
      Objects.requireNonNull(settlement, "settlement is required");
      Objects.requireNonNull(settledAt, "settledAt is required");
    }

    public UUID protectionId() {
      return settlement.protectionEvidence().accountProtectionId();
    }

    public long protectionFence() {
      return settlement.protectionEvidence().accountProtectionFence();
    }

    public GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome() {
      return settlement.outcome();
    }
  }

  private int insert(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAuthorityCapture capture,
      List<SourceEvidence> sources,
      UUID protectionId) {
    var tuple = request.originalTuple();
    var hold = request.worldAdmissionHoldIdentity();
    return dsl.execute(
        "INSERT INTO "
            + PROTECTIONS
            + " (protection_id, control_plane_request_id, original_post_authorization_tuple, "
            + "account_redemption_projection, game_session_owner_mutation_id, "
            + "game_session_owner_attempt_id, game_session_owner_fence, original_lease_expires_at, "
            + "account_world_participation_id, account_world_participation_fence, target_namespace, "
            + "canonical_tenant_id, canonical_game_instance_id, capture_source_version, "
            + "capture_source_fence, capture_sha256, world_admission_hold_identity_bytes, "
            + "request_binding_bytes, request_binding_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (control_plane_request_id) DO NOTHING",
        protectionId,
        tuple.controlPlaneRequestId(),
        request.originalPostAuthorizationTupleBytes(),
        request.accountRedemptionProjectionBytes(),
        request.gameSessionOwnerMutationId(),
        request.gameSessionOwnerAttemptId(),
        request.gameSessionOwnerFence(),
        OffsetDateTime.ofInstant(request.originalLeaseExpiresAt(), ZoneOffset.UTC),
        request.accountWorldParticipationId(),
        request.accountWorldParticipationFence(),
        tuple.preAuthorizationTuple().action().scope().targetNamespace(),
        tuple.preAuthorizationTuple().action().scope().tenantId(),
        hold.request().canonicalGameInstanceId(),
        capture.sourceVersion(),
        capture.sourceFence(),
        capture.canonicalSha256(),
        request.worldAdmissionHoldIdentityBytes(),
        request.canonicalBytes(),
        digestPrefixed(request.canonicalBytes()));
  }

  private void insertSources(UUID protectionId, List<SourceEvidence> sources) {
    for (SourceEvidence source : sources) {
      byte[] bytes = source.canonicalBytes();
      dsl.execute(
          "INSERT INTO "
              + SOURCES
              + " (protection_id, source_key, source_evidence) VALUES (?, ?, ?)",
          protectionId,
          source.key(),
          bytes);
    }
  }

  private AccountStartSessionAdmissionProtectionEvidence decodeAndRequireExact(
      Record row,
      AccountStartSessionAdmissionProtectionRequest expectedRequest,
      CaptureValue expectedCapture,
      List<SourceEvidence> expectedSources) {
    try {
      requireColumns(row, PROTECTION_COLUMNS, "admission protection");
      UUID protectionId = requiredUuid(row, "protection_id");
      long protectionFence = positive(requiredLong(row, "protection_fence"));
      String requestId = requiredString(row, "control_plane_request_id");
      byte[] tupleBytes = requiredBytes(row, "original_post_authorization_tuple");
      byte[] projectionBytes = requiredBytes(row, "account_redemption_projection");
      UUID ownerMutation = requiredUuid(row, "game_session_owner_mutation_id");
      UUID ownerAttempt = requiredUuid(row, "game_session_owner_attempt_id");
      long ownerFence = positive(requiredLong(row, "game_session_owner_fence"));
      Instant originalExpiry = requiredTimestamp(row, "original_lease_expires_at").toInstant();
      UUID participationId = requiredUuid(row, "account_world_participation_id");
      long participationFence = positive(requiredLong(row, "account_world_participation_fence"));
      String namespace = requiredString(row, "target_namespace");
      UUID tenant = requiredUuid(row, "canonical_tenant_id");
      UUID gameInstance = requiredUuid(row, "canonical_game_instance_id");
      long captureVersion = positive(requiredLong(row, "capture_source_version"));
      long captureFence = positive(requiredLong(row, "capture_source_fence"));
      String captureDigest = requiredString(row, "capture_sha256");
      byte[] holdBytes = requiredBytes(row, "world_admission_hold_identity_bytes");
      byte[] requestBytes = requiredBytes(row, "request_binding_bytes");
      String requestDigest = requiredString(row, "request_binding_digest");
      positive(requiredLong(row, "producer_xid"));
      requiredTimestamp(row, "created_at");

      var tuple = expectedRequest.originalTuple();
      var hold = expectedRequest.worldAdmissionHoldIdentity();
      if (!tuple.controlPlaneRequestId().equals(requestId)
          || !Arrays.equals(tupleBytes, expectedRequest.originalPostAuthorizationTupleBytes())
          || !Arrays.equals(projectionBytes, expectedRequest.accountRedemptionProjectionBytes())
          || !ownerMutation.equals(expectedRequest.gameSessionOwnerMutationId())
          || !ownerAttempt.equals(expectedRequest.gameSessionOwnerAttemptId())
          || ownerFence != expectedRequest.gameSessionOwnerFence()
          || !originalExpiry.equals(expectedRequest.originalLeaseExpiresAt())
          || !participationId.equals(expectedRequest.accountWorldParticipationId())
          || participationFence != expectedRequest.accountWorldParticipationFence()
          || !namespace.equals(tuple.preAuthorizationTuple().action().scope().targetNamespace())
          || !tenant.equals(tuple.preAuthorizationTuple().action().scope().tenantId())
          || !gameInstance.equals(hold.request().canonicalGameInstanceId())
          || captureVersion != expectedCapture.sourceVersion()
          || captureFence != expectedCapture.sourceFence()
          || !captureDigest.equals(expectedCapture.canonicalSha256())
          || !Arrays.equals(holdBytes, expectedRequest.worldAdmissionHoldIdentityBytes())
          || !MessageDigest.isEqual(requestBytes, expectedRequest.canonicalBytes())
          || !requestDigest.equals(digestPrefixed(requestBytes))) {
        throw conflict();
      }

      List<SourceEvidence> storedSources = readSources(protectionId);
      requireExactSources(expectedSources, storedSources);
      AccountStartSessionAdmissionProtectionEvidence evidence =
          AccountStartSessionAdmissionProtectionEvidence.create(
              expectedRequest,
              protectionId,
              protectionFence,
              expectedCapture.canonicalBytes(),
              expectedCapture.canonicalSha256(),
              storedSources);
      return evidence;
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalStateException state
          && ("StartSession admission protection is unavailable".equals(state.getMessage())
              || "StartSession admission protection conflicts with its immutable binding"
                  .equals(state.getMessage()))) {
        throw state;
      }
      throw unavailable();
    }
  }

  private List<SourceEvidence> readSources(UUID protectionId) {
    var rows =
        dsl.fetch(
            "SELECT * FROM " + SOURCES + " WHERE protection_id = ? ORDER BY source_key",
            protectionId);
    List<SourceEvidence> result = new ArrayList<>(rows.size());
    for (Record row : rows) {
      requireColumns(row, SOURCE_COLUMNS, "admission protection source");
      if (!protectionId.equals(requiredUuid(row, "protection_id"))) throw unavailable();
      String key = requiredString(row, "source_key");
      byte[] bytes = requiredBytes(row, "source_evidence");
      SourceEvidence source;
      try {
        source = SourceEvidence.fromStored(bytes);
      } catch (RuntimeException malformed) {
        throw unavailable();
      }
      if (!key.equals(source.key()) || bytes.length == 0 || bytes.length > 131_072) {
        throw unavailable();
      }
      result.add(source);
    }
    result.sort(Comparator.comparing(SourceEvidence::key));
    if (result.stream().map(SourceEvidence::key).distinct().count() != result.size()) {
      throw unavailable();
    }
    return List.copyOf(result);
  }

  private Record selectByRequestId(String requestId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + PROTECTIONS
            + " WHERE control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        requestId);
  }

  private Record selectById(UUID protectionId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + PROTECTIONS
            + " WHERE protection_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        protectionId);
  }

  void assertCurrent(UUID protectionId, long protectionFence, byte[] exactRequestBytes) {
    if (exactRequestBytes == null || exactRequestBytes.length == 0) throw unavailable();
    Record checked =
        dsl.fetchOne(
            "SELECT * FROM account_ss_admission_read_current_exact(?, ?, ?)",
            protectionId,
            protectionFence,
            exactRequestBytes);
    if (checked == null) throw unavailable();
  }

  private Record selectCaptureForOriginalTuple(
      StartSessionPostAuthorizationExecutionTuple tuple, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT control_plane_request_id, source_version, source_fence, snapshot_sha256, canonical_sha256, "
            + "canonical_capture_bytes, canonical_snapshot_bytes FROM "
            + CAPTURES
            + " WHERE control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        tuple.controlPlaneRequestId());
  }

  private Record selectCapture(AccountStartSessionAuthorityCapture capture, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT control_plane_request_id, source_version, source_fence, snapshot_sha256, canonical_sha256, "
            + "canonical_capture_bytes, canonical_snapshot_bytes FROM "
            + CAPTURES
            + " WHERE control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        capture.controlPlaneRequestId());
  }

  private CaptureValue captureFromRow(Record row) {
    requireColumns(row, CAPTURE_COLUMNS, "Account source capture");
    try {
      String requestId = requiredString(row, "control_plane_request_id");
      long sourceVersion = positive(requiredLong(row, "source_version"));
      long sourceFence = positive(requiredLong(row, "source_fence"));
      String snapshotDigest = requiredString(row, "snapshot_sha256");
      String digest = requiredString(row, "canonical_sha256");
      byte[] reference = requiredBytes(row, "canonical_capture_bytes");
      byte[] snapshot = requiredBytes(row, "canonical_snapshot_bytes");
      return new CaptureValue(
          requestId, sourceVersion, sourceFence, snapshot, snapshotDigest, reference, digest);
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private void requireCaptureMatches(
      AccountStartSessionAuthorityCapture capture,
      StartSessionPostAuthorizationExecutionTuple tuple,
      Record row) {
    if (row == null) throw unavailable();
    requireColumns(row, CAPTURE_COLUMNS, "Account source capture");
    if (!tuple.controlPlaneRequestId().equals(capture.controlPlaneRequestId())
        || !tuple.controlPlaneRequestId().equals(requiredString(row, "control_plane_request_id"))
        || capture.sourceVersion() != positive(requiredLong(row, "source_version"))
        || capture.sourceFence() != positive(requiredLong(row, "source_fence"))
        || !capture.snapshotSha256().equals(requiredString(row, "snapshot_sha256"))
        || !capture.canonicalSha256().equals(requiredString(row, "canonical_sha256"))
        || !Arrays.equals(capture.canonicalBytes(), requiredBytes(row, "canonical_capture_bytes"))
        || !Arrays.equals(
            capture.snapshotBytes(), requiredBytes(row, "canonical_snapshot_bytes"))) {
      throw conflict();
    }
  }

  private static List<SourceEvidence> sourceVector(
      byte[] snapshotBytes, StartSessionPostAuthorizationExecutionTuple tuple) {
    Map<?, ?> snapshot = parseCanonicalObject(snapshotBytes, "Account source snapshot");
    if (!CAPTURE_SNAPSHOT_FIELDS.equals(snapshot.keySet())
        || !"account-start-session-authority-snapshot/v1".equals(snapshot.get("schema"))
        || !tuple.controlPlaneRequestId().equals(snapshot.get("controlPlaneRequestId"))) {
      throw unavailable();
    }
    Object value = snapshot.get("sourceVector");
    if (!(value instanceof List<?> encoded) || encoded.isEmpty()) throw unavailable();
    List<SourceEvidence> sources = new ArrayList<>(encoded.size());
    Set<String> keys = new HashSet<>();
    String previousKey = null;
    for (Object element : encoded) {
      if (!(element instanceof String base64)) throw unavailable();
      byte[] bytes = strictBase64(base64);
      SourceEvidence source;
      try {
        source = SourceEvidence.fromStored(bytes);
      } catch (RuntimeException malformed) {
        throw unavailable();
      }
      if (bytes.length == 0
          || bytes.length > 131_072
          || !keys.add(source.key())
          || (previousKey != null && previousKey.compareTo(source.key()) >= 0)) {
        throw unavailable();
      }
      previousKey = source.key();
      sources.add(source);
    }
    return List.copyOf(sources);
  }

  static void requireExactSources(List<SourceEvidence> expected, List<SourceEvidence> actual) {
    if (actual.size() != expected.size()) throw conflict();
    for (int index = 0; index < expected.size(); index++) {
      SourceEvidence left = expected.get(index);
      SourceEvidence right = actual.get(index);
      if (!left.key().equals(right.key())
          || !Arrays.equals(left.canonicalBytes(), right.canonicalBytes())) {
        throw conflict();
      }
    }
  }

  private static Map<?, ?> parseCanonicalObject(byte[] bytes, String label) {
    try {
      String text = strictUtf8(bytes);
      if (!Arrays.equals(
          bytes, net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(text))) {
        throw unavailable();
      }
      Object value = JSON.readValue(text, Object.class);
      if (!(value instanceof Map<?, ?> object)) throw unavailable();
      return object;
    } catch (IOException | RuntimeException malformed) {
      if (malformed instanceof IllegalStateException expected) throw expected;
      throw new IllegalStateException(label + " is unavailable", malformed);
    }
  }

  private static byte[] strictBase64(String value) {
    try {
      byte[] decoded = Base64.getDecoder().decode(value);
      if (!Base64.getEncoder().encodeToString(decoded).equals(value)) throw unavailable();
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw unavailable();
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException malformed) {
      throw unavailable();
    }
  }

  private record CaptureValue(
      String controlPlaneRequestId,
      long sourceVersion,
      long sourceFence,
      byte[] snapshotBytes,
      String snapshotSha256,
      byte[] canonicalBytes,
      String canonicalSha256) {
    private CaptureValue {
      if (controlPlaneRequestId == null
          || sourceVersion <= 0L
          || sourceFence <= 0L
          || snapshotBytes == null
          || snapshotBytes.length == 0
          || snapshotBytes.length > AccountStartSessionAuthorityCapture.MAX_SNAPSHOT_BYTES
          || snapshotSha256 == null
          || canonicalBytes == null
          || canonicalBytes.length == 0
          || canonicalBytes.length > AccountStartSessionAuthorityCapture.MAX_CAPTURE_BYTES
          || canonicalSha256 == null) {
        throw unavailable();
      }
      snapshotBytes = snapshotBytes.clone();
      canonicalBytes = canonicalBytes.clone();
      if (!snapshotSha256.equals(digestHex(snapshotBytes))
          || !canonicalSha256.equals(digestHex(canonicalBytes))) {
        throw unavailable();
      }
      Map<?, ?> reference = parseCanonicalObject(canonicalBytes, "Account capture reference");
      if (!reference
              .keySet()
              .equals(
                  Set.of(
                      "schema",
                      "controlPlaneRequestId",
                      "capturedAt",
                      "bundleReference",
                      "snapshotSha256"))
          || !AccountStartSessionAuthorityCapture.SCHEMA.equals(reference.get("schema"))
          || !controlPlaneRequestId.equals(reference.get("controlPlaneRequestId"))
          || !snapshotSha256.equals(reference.get("snapshotSha256"))
          || !(reference.get("bundleReference") instanceof Map<?, ?> bundle)
          || !bundle
              .keySet()
              .equals(Set.of("bundleVersion", "sourceVersion", "sourceFence", "linearization"))
          || !StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION.equals(bundle.get("bundleVersion"))
          || !Long.toString(sourceVersion).equals(bundle.get("sourceVersion"))
          || !Long.toString(sourceFence).equals(bundle.get("sourceFence"))) {
        throw unavailable();
      }
    }

    private static CaptureValue from(AccountStartSessionAuthorityCapture capture) {
      return new CaptureValue(
          capture.controlPlaneRequestId(),
          capture.sourceVersion(),
          capture.sourceFence(),
          capture.snapshotBytes(),
          capture.snapshotSha256(),
          capture.canonicalBytes(),
          capture.canonicalSha256());
    }

    @Override
    public byte[] snapshotBytes() {
      return snapshotBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }

  private void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account READ_COMMITTED transaction required");
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (declaredIsolation != null && declaredIsolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException("Writable Account READ_COMMITTED transaction required");
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("Writable Account READ_COMMITTED transaction required");
          }
        });
  }

  private static void requireColumns(Record row, Set<String> expected, String label) {
    Set<String> actual = new HashSet<>();
    Arrays.stream(row.fields()).forEach(field -> actual.add(field.getName()));
    if (!actual.equals(expected)) {
      throw new IllegalStateException(label + " has an unknown or missing database column");
    }
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || NIL_UUID.equals(value)) throw unavailable();
    return value;
  }

  private static String requiredString(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw unavailable();
    return value;
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null) throw unavailable();
    return value;
  }

  private static Long requiredLong(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null) throw unavailable();
    return value;
  }

  private static OffsetDateTime requiredTimestamp(Record row, String field) {
    OffsetDateTime value = row.get(field, OffsetDateTime.class);
    if (value == null) throw unavailable();
    return value;
  }

  private static long positive(long value) {
    if (value <= 0L) throw unavailable();
    return value;
  }

  private static String digestPrefixed(byte[] bytes) {
    return "sha256:" + digestHex(bytes);
  }

  private static String digestHex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalStateException conflict() {
    return new IllegalStateException(
        "StartSession admission protection conflicts with its immutable binding");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("StartSession admission protection is unavailable");
  }
}
