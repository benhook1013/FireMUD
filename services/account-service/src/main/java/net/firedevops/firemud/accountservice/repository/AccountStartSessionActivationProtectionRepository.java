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
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
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
 * Immutable Account storage for distinct original StartSession World-activation source protection.
 *
 * <p>The caller must already be inside the existing authenticated Account currentness callback.
 * This repository accepts no producer authority: SQL rechecks the retained Account redemption,
 * committed V129 preparation, V131 attempt observation, source capture, target and finite lease
 * after canonical source locks. The retained Common request then verifies the exact Game Session
 * STARTING and World PREPARING evidence. Terminal settlement is separately append-only and never
 * mutates this original protection row.
 */
@Repository
public class AccountStartSessionActivationProtectionRepository {
  private static final String PROTECTIONS = "account_start_session_activation_protections";
  private static final String SOURCES = "account_start_session_activation_protection_sources";
  private static final String CAPTURES = "account_start_session_authority_captures";
  private static final String SETTLEMENTS =
      "account_start_session_activation_protection_settlements";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Set<String> PROTECTION_COLUMNS =
      Set.of(
          "activation_protection_id",
          "activation_protection_fence",
          "control_plane_request_id",
          "account_world_participation_id",
          "account_world_participation_fence",
          "original_post_authorization_tuple",
          "account_redemption_projection",
          "game_session_owner_mutation_id",
          "game_session_owner_attempt_id",
          "game_session_owner_fence",
          "original_lease_expires_at",
          "target_namespace",
          "canonical_tenant_id",
          "canonical_game_instance_id",
          "world_activation_request_id",
          "game_session_launch_read_evidence",
          "world_preparing_evidence",
          "capture_source_version",
          "capture_source_fence",
          "capture_sha256",
          "capture_reference_bytes",
          "capture_reference_sha256",
          "operation_identity_bytes",
          "operation_identity_digest",
          "request_binding_bytes",
          "request_binding_digest",
          "producer_xid",
          "acquired_at");
  private static final Set<String> SOURCE_COLUMNS =
      Set.of("activation_protection_id", "source_key", "source_evidence");
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
      Set.of(
          "activation_protection_id",
          "activation_protection_fence",
          "world_activation_request_id",
          "world_activation_fence",
          "outcome",
          "account_protection_evidence_bytes",
          "terminal_bytes",
          "terminal_digest",
          "settled_at");
  private static final Set<String> SNAPSHOT_FIELDS =
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
          "Preserve the injected DSLContext precondition; this transactional repository is "
              + "proxyable and owns no resources.")
  public AccountStartSessionActivationProtectionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Creates the first exact activation protection or returns its immutable winner.
   *
   * <p>Only the stable operation identity is used for retry equality. If either owner read uses a
   * new correlation UUID, this returns the first full request and receipt unchanged rather than
   * replacing its historical IDs. Database identity, fence and acquisition time are generated by
   * PostgreSQL.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountStartSessionActivationProtectionEvidence createOrReadExact(
      AccountStartSessionActivationProtectionRequest request,
      AccountStartSessionAuthorityCapture capture) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(request, "exact activation protection request is required");
    Objects.requireNonNull(capture, "exact current Account capture is required");

    assertCurrent(request);
    CaptureValue currentCapture = captureFromRow(selectCapture(request, false));
    requireCaptureMatches(capture, currentCapture, request);
    List<SourceEvidence> expectedSources = sourceVector(currentCapture.snapshotBytes(), request);

    Record existing = selectByRequestId(request.originalTuple().controlPlaneRequestId());
    if (existing != null) {
      return decodeAndRequireSameOperation(existing, request, currentCapture, expectedSources);
    }

    int inserted = insert(request, currentCapture);
    Record winner = selectByRequestId(request.originalTuple().controlPlaneRequestId());
    if (winner == null) throw unavailable();
    UUID protectionId = requiredUuid(winner, "activation_protection_id");
    if (inserted == 1) insertSources(protectionId, expectedSources);
    AccountStartSessionActivationProtectionEvidence evidence =
        decodeAndRequireSameOperation(winner, request, currentCapture, expectedSources);
    if (inserted == 1 && !protectionId.equals(evidence.accountProtectionId())) throw unavailable();
    return evidence;
  }

  /**
   * Lookup-only current read for an independent Account readback transaction. It never inserts,
   * renews or substitutes the retained first request, and refuses an expired original lease.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountStartSessionActivationProtectionEvidence> findExact(
      AccountStartSessionActivationProtectionRequest request) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(request, "exact activation protection request is required");
    assertCurrent(request);
    Record row = selectByRequestId(request.originalTuple().controlPlaneRequestId());
    if (row == null) return Optional.empty();
    CaptureValue capture = captureFromRow(selectCapture(request, false));
    List<SourceEvidence> expectedSources = sourceVector(capture.snapshotBytes(), request);
    return Optional.of(decodeAndRequireSameOperation(row, request, capture, expectedSources));
  }

  /**
   * Lookup-only historical read used solely to resolve an already acquired activation protection.
   * It validates the full immutable request, capture and source vector without checking
   * currentness, original lease expiry, or changing the source hold.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountStartSessionActivationProtectionEvidence> findHistoricalExact(
      UUID protectionId, long protectionFence) {
    requireWritableReadCommittedTransaction();
    if (protectionId == null || NIL_UUID.equals(protectionId) || protectionFence <= 0L) {
      throw unavailable();
    }
    Record row = selectById(protectionId, false);
    if (row == null) return Optional.empty();
    if (!protectionId.equals(requiredUuid(row, "activation_protection_id"))
        || protectionFence != positive(requiredLong(row, "activation_protection_fence"))) {
      throw unavailable();
    }
    AccountStartSessionActivationProtectionRequest request;
    try {
      request =
          AccountStartSessionActivationProtectionRequest.decode(
              requiredBytes(row, "request_binding_bytes"));
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
    Record captureRow = selectCapture(request, false);
    if (captureRow == null) throw unavailable();
    CaptureValue capture = captureFromRow(captureRow);
    if (!request.originalTuple().controlPlaneRequestId().equals(capture.controlPlaneRequestId())) {
      throw unavailable();
    }
    List<SourceEvidence> expectedSources = sourceVector(capture.snapshotBytes(), request);
    return Optional.of(decodeAndRequireSameOperation(row, request, capture, expectedSources));
  }

  /**
   * Appends the exact authenticated World terminal receipt after source-key locks, then returns the
   * immutable database winner. Historical settlement deliberately ignores lease expiry.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredSettlement settleExact(
      WorldOriginalStartSessionActivationTerminalEvidence terminal) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(terminal, "World activation terminal evidence is required");
    AccountStartSessionActivationProtectionEvidence expected = terminal.accountProtectionEvidence();
    UUID protectionId = expected.accountProtectionId();
    long protectionFence = expected.accountProtectionFence();

    lockOriginalSourceRows(expected);
    Record locked = selectById(protectionId, true);
    if (locked == null
        || !protectionId.equals(requiredUuid(locked, "activation_protection_id"))
        || protectionFence != positive(requiredLong(locked, "activation_protection_fence"))) {
      throw unavailable();
    }
    AccountStartSessionActivationProtectionEvidence retained =
        findHistoricalExact(protectionId, protectionFence)
            .orElseThrow(AccountStartSessionActivationProtectionRepository::unavailable);
    requireSameEvidence(expected, retained);

    dsl.execute(
        "INSERT INTO "
            + SETTLEMENTS
            + " (activation_protection_id, activation_protection_fence, "
            + "world_activation_request_id, world_activation_fence, outcome, "
            + "account_protection_evidence_bytes, terminal_bytes, terminal_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (activation_protection_id) DO NOTHING",
        protectionId,
        protectionFence,
        terminal.activationRequestId(),
        terminal.worldActivationFence(),
        terminal.outcome().name(),
        expected.canonicalBytes(),
        terminal.canonicalBytes(),
        terminal.terminalDigest());
    StoredSettlement stored =
        readSettlementExact(protectionId, protectionFence, retained)
            .orElseThrow(AccountStartSessionActivationProtectionRepository::unavailable);
    if (!Arrays.equals(terminal.canonicalBytes(), stored.terminal().canonicalBytes())
        || !terminal.terminalDigest().equals(stored.terminal().terminalDigest())
        || terminal.outcome() != stored.outcome()) {
      throw conflict();
    }
    return stored;
  }

  /** Historical exact settlement lookup; no currentness or lease-expiry check is performed. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredSettlement> findSettlementExact(UUID protectionId, long protectionFence) {
    requireWritableReadCommittedTransaction();
    Optional<AccountStartSessionActivationProtectionEvidence> evidence =
        findHistoricalExact(protectionId, protectionFence);
    if (evidence.isEmpty()) return Optional.empty();
    return readSettlementExact(protectionId, protectionFence, evidence.orElseThrow());
  }

  void assertCurrent(AccountStartSessionActivationProtectionRequest request) {
    if (!"STARTING"
            .equals(
                request.gameSessionLaunchReadEvidence().launch().currentGameInstanceStatus().name())
        || !"PREPARING".equals(request.worldActivationRequest().preparing().lifecycleStatus())) {
      throw unavailable();
    }
    byte[] operationBytes = request.operationIdentityBytes();
    String digest = digestPrefixed(operationBytes);
    if (!request.requestDigest().equals(digest)) throw conflict();
    dsl.execute(
        "SELECT account_ss_activation_assert_current(?, ?, ?, FALSE)",
        request.canonicalBytes(),
        operationBytes,
        digest);
  }

  private int insert(AccountStartSessionActivationProtectionRequest request, CaptureValue capture) {
    var tuple = request.originalTuple();
    var scope = tuple.preAuthorizationTuple().action().scope();
    var worldRequest = request.worldActivationRequest();
    return dsl.execute(
        "INSERT INTO "
            + PROTECTIONS
            + " (control_plane_request_id, account_world_participation_id, "
            + "account_world_participation_fence, original_post_authorization_tuple, "
            + "account_redemption_projection, game_session_owner_mutation_id, "
            + "game_session_owner_attempt_id, game_session_owner_fence, original_lease_expires_at, "
            + "target_namespace, canonical_tenant_id, canonical_game_instance_id, "
            + "world_activation_request_id, game_session_launch_read_evidence, "
            + "world_preparing_evidence, "
            + "capture_source_version, capture_source_fence, capture_sha256, "
            + "capture_reference_bytes, capture_reference_sha256, operation_identity_bytes, "
            + "operation_identity_digest, request_binding_bytes, request_binding_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, "
            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (control_plane_request_id) DO NOTHING",
        tuple.controlPlaneRequestId(),
        request.accountWorldParticipationId(),
        request.accountWorldParticipationFence(),
        request.originalPostAuthorizationTupleBytes(),
        request.accountRedemptionProjectionBytes(),
        request.gameSessionOwnerMutationId(),
        request.gameSessionOwnerAttemptId(),
        request.gameSessionOwnerFence(),
        OffsetDateTime.ofInstant(request.originalLeaseExpiresAt(), ZoneOffset.UTC),
        scope.targetNamespace(),
        scope.tenantId(),
        worldRequest.canonicalGameInstanceId(),
        worldRequest.activationRequestId(),
        request.gameSessionLaunchReadEvidence().canonicalBytes(),
        worldRequest.preparingEvidenceBytes(),
        capture.sourceVersion(),
        capture.sourceFence(),
        capture.canonicalSha256(),
        capture.canonicalBytes(),
        capture.canonicalSha256(),
        request.operationIdentityBytes(),
        digestPrefixed(request.operationIdentityBytes()),
        request.canonicalBytes(),
        digestPrefixed(request.canonicalBytes()));
  }

  private void insertSources(UUID protectionId, List<SourceEvidence> sources) {
    for (SourceEvidence source : sources) {
      dsl.execute(
          "INSERT INTO "
              + SOURCES
              + " (activation_protection_id, source_key, source_evidence) VALUES (?, ?, ?)",
          protectionId,
          source.key(),
          source.canonicalBytes());
    }
  }

  private AccountStartSessionActivationProtectionEvidence decodeAndRequireSameOperation(
      Record row,
      AccountStartSessionActivationProtectionRequest expectedRequest,
      CaptureValue expectedCapture,
      List<SourceEvidence> expectedSources) {
    try {
      requireColumns(row, PROTECTION_COLUMNS, "activation protection");
      UUID protectionId = requiredUuid(row, "activation_protection_id");
      long protectionFence = positive(requiredLong(row, "activation_protection_fence"));
      String requestId = requiredString(row, "control_plane_request_id");
      byte[] tupleBytes = requiredBytes(row, "original_post_authorization_tuple");
      byte[] projectionBytes = requiredBytes(row, "account_redemption_projection");
      UUID ownerMutation = requiredUuid(row, "game_session_owner_mutation_id");
      UUID ownerAttempt = requiredUuid(row, "game_session_owner_attempt_id");
      long ownerFence = positive(requiredLong(row, "game_session_owner_fence"));
      Instant leaseExpiry = requiredTimestamp(row, "original_lease_expires_at").toInstant();
      UUID participationId = requiredUuid(row, "account_world_participation_id");
      long participationFence = positive(requiredLong(row, "account_world_participation_fence"));
      String namespace = requiredString(row, "target_namespace");
      UUID tenant = requiredUuid(row, "canonical_tenant_id");
      UUID gameInstance = requiredUuid(row, "canonical_game_instance_id");
      UUID activationRequestId = requiredUuid(row, "world_activation_request_id");
      byte[] launchBytes = requiredBytes(row, "game_session_launch_read_evidence");
      byte[] preparingBytes = requiredBytes(row, "world_preparing_evidence");
      long captureVersion = positive(requiredLong(row, "capture_source_version"));
      long captureFence = positive(requiredLong(row, "capture_source_fence"));
      String captureDigest = requiredString(row, "capture_sha256");
      byte[] captureReference = requiredBytes(row, "capture_reference_bytes");
      String captureReferenceDigest = requiredString(row, "capture_reference_sha256");
      byte[] operationBytes = requiredBytes(row, "operation_identity_bytes");
      String operationDigest = requiredString(row, "operation_identity_digest");
      byte[] requestBytes = requiredBytes(row, "request_binding_bytes");
      String requestDigest = requiredString(row, "request_binding_digest");
      positive(requiredLong(row, "producer_xid"));
      Instant acquiredAt = requiredTimestamp(row, "acquired_at").toInstant();

      AccountStartSessionActivationProtectionRequest retainedRequest;
      try {
        retainedRequest = AccountStartSessionActivationProtectionRequest.decode(requestBytes);
      } catch (RuntimeException malformed) {
        throw unavailable();
      }
      requireSameOperationIdentity(expectedRequest.operationIdentityBytes(), operationBytes);
      requireSameOperationIdentity(
          expectedRequest.operationIdentityBytes(), retainedRequest.operationIdentityBytes());
      var tuple = retainedRequest.originalTuple();
      var worldRequest = retainedRequest.worldActivationRequest();
      if (!tuple.controlPlaneRequestId().equals(requestId)
          || !Arrays.equals(tupleBytes, retainedRequest.originalPostAuthorizationTupleBytes())
          || !Arrays.equals(projectionBytes, retainedRequest.accountRedemptionProjectionBytes())
          || !ownerMutation.equals(retainedRequest.gameSessionOwnerMutationId())
          || !ownerAttempt.equals(retainedRequest.gameSessionOwnerAttemptId())
          || ownerFence != retainedRequest.gameSessionOwnerFence()
          || !leaseExpiry.equals(retainedRequest.originalLeaseExpiresAt())
          || !participationId.equals(retainedRequest.accountWorldParticipationId())
          || participationFence != retainedRequest.accountWorldParticipationFence()
          || !namespace.equals(tuple.preAuthorizationTuple().action().scope().targetNamespace())
          || !tenant.equals(tuple.preAuthorizationTuple().action().scope().tenantId())
          || !gameInstance.equals(worldRequest.canonicalGameInstanceId())
          || !activationRequestId.equals(worldRequest.activationRequestId())
          || !Arrays.equals(
              launchBytes, retainedRequest.gameSessionLaunchReadEvidence().canonicalBytes())
          || !Arrays.equals(preparingBytes, worldRequest.preparingEvidenceBytes())
          || captureVersion != expectedCapture.sourceVersion()
          || captureFence != expectedCapture.sourceFence()
          || !captureDigest.equals(expectedCapture.canonicalSha256())
          || !Arrays.equals(captureReference, expectedCapture.canonicalBytes())
          || !captureReferenceDigest.equals(expectedCapture.canonicalSha256())
          || !Arrays.equals(requestBytes, retainedRequest.canonicalBytes())
          || !requestDigest.equals(digestPrefixed(requestBytes))
          || !operationDigest.equals(digestPrefixed(operationBytes))) {
        throw conflict();
      }

      List<SourceEvidence> storedSources = readSources(protectionId);
      requireExactSources(expectedSources, storedSources);
      return AccountStartSessionActivationProtectionEvidence.create(
          retainedRequest,
          protectionId,
          protectionFence,
          acquiredAt,
          captureReference,
          captureReferenceDigest,
          storedSources);
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalStateException state
          && ("Original StartSession activation protection is unavailable"
                  .equals(state.getMessage())
              || "Original StartSession activation protection conflicts with its immutable binding"
                  .equals(state.getMessage()))) {
        throw state;
      }
      throw unavailable();
    }
  }

  private List<SourceEvidence> readSources(UUID protectionId) {
    var rows =
        dsl.fetch("SELECT * FROM " + SOURCES + " WHERE activation_protection_id = ?", protectionId);
    List<SourceEvidence> sources = new ArrayList<>(rows.size());
    for (Record row : rows) {
      requireColumns(row, SOURCE_COLUMNS, "activation protection source");
      if (!protectionId.equals(requiredUuid(row, "activation_protection_id"))) throw unavailable();
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
      sources.add(source);
    }
    sources.sort(Comparator.comparing(SourceEvidence::key));
    if (sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()) {
      throw unavailable();
    }
    return List.copyOf(sources);
  }

  private Record selectByRequestId(String requestId) {
    return dsl.fetchOne(
        "SELECT * FROM " + PROTECTIONS + " WHERE control_plane_request_id = ?", requestId);
  }

  private Record selectById(UUID protectionId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + PROTECTIONS
            + " WHERE activation_protection_id = ?"
            + (forUpdate ? " FOR UPDATE NOWAIT" : ""),
        protectionId);
  }

  private void lockOriginalSourceRows(AccountStartSessionActivationProtectionEvidence evidence) {
    List<SourceEvidence> ordered =
        evidence.sourceEvidenceVector().stream()
            .sorted(Comparator.comparing(SourceEvidence::key))
            .toList();
    for (SourceEvidence source : ordered) {
      Record lock =
          dsl.fetchOne(
              "SELECT source_key FROM account_draft_authorization_source_locks "
                  + "WHERE source_key = ? FOR UPDATE NOWAIT",
              source.key());
      if (lock == null || !source.key().equals(requiredString(lock, "source_key"))) {
        throw unavailable();
      }
    }
  }

  private Optional<StoredSettlement> readSettlementExact(
      UUID protectionId,
      long protectionFence,
      AccountStartSessionActivationProtectionEvidence expectedEvidence) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + SETTLEMENTS + " WHERE activation_protection_id = ?", protectionId);
    if (row == null) return Optional.empty();
    try {
      requireColumns(row, SETTLEMENT_COLUMNS, "activation protection settlement");
      if (!protectionId.equals(requiredUuid(row, "activation_protection_id"))
          || protectionFence != positive(requiredLong(row, "activation_protection_fence"))) {
        throw unavailable();
      }
      UUID activationRequestId = requiredUuid(row, "world_activation_request_id");
      long worldFence = positive(requiredLong(row, "world_activation_fence"));
      String outcome = requiredString(row, "outcome");
      byte[] evidenceBytes = requiredBytes(row, "account_protection_evidence_bytes");
      byte[] terminalBytes = requiredBytes(row, "terminal_bytes");
      String digest = requiredString(row, "terminal_digest");
      Instant settledAt = requiredTimestamp(row, "settled_at").toInstant();
      if (!digest.equals(digestPrefixed(terminalBytes))
          || !MessageDigest.isEqual(expectedEvidence.canonicalBytes(), evidenceBytes)) {
        throw conflict();
      }
      WorldOriginalStartSessionActivationTerminalEvidence terminal =
          WorldOriginalStartSessionActivationTerminalEvidence.decode(terminalBytes);
      if (!Arrays.equals(terminalBytes, terminal.canonicalBytes())
          || !digest.equals(terminal.terminalDigest())
          || !activationRequestId.equals(terminal.activationRequestId())
          || worldFence != terminal.worldActivationFence()
          || !outcome.equals(terminal.outcome().name())
          || !MessageDigest.isEqual(
              evidenceBytes, terminal.accountProtectionEvidence().canonicalBytes())) {
        throw conflict();
      }
      requireSameEvidence(expectedEvidence, terminal.accountProtectionEvidence());
      return Optional.of(new StoredSettlement(terminal, settledAt));
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalStateException state
          && ("Original StartSession activation protection is unavailable"
                  .equals(state.getMessage())
              || "Original StartSession activation protection conflicts with its immutable binding"
                  .equals(state.getMessage()))) {
        throw state;
      }
      throw unavailable();
    }
  }

  private static void requireSameEvidence(
      AccountStartSessionActivationProtectionEvidence expected,
      AccountStartSessionActivationProtectionEvidence actual) {
    if (!MessageDigest.isEqual(expected.canonicalBytes(), actual.canonicalBytes()))
      throw conflict();
  }

  private Record selectCapture(
      AccountStartSessionActivationProtectionRequest request, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT control_plane_request_id, source_version, source_fence, snapshot_sha256, "
            + "canonical_sha256, "
            + "canonical_capture_bytes, canonical_snapshot_bytes FROM "
            + CAPTURES
            + " WHERE control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE NOWAIT" : ""),
        request.originalTuple().controlPlaneRequestId());
  }

  private CaptureValue captureFromRow(Record row) {
    requireColumns(row, CAPTURE_COLUMNS, "Account source capture");
    try {
      String requestId = requiredString(row, "control_plane_request_id");
      long version = positive(requiredLong(row, "source_version"));
      long fence = positive(requiredLong(row, "source_fence"));
      String snapshotDigest = requiredString(row, "snapshot_sha256");
      String captureDigest = requiredString(row, "canonical_sha256");
      byte[] reference = requiredBytes(row, "canonical_capture_bytes");
      byte[] snapshot = requiredBytes(row, "canonical_snapshot_bytes");
      return new CaptureValue(
          requestId, version, fence, snapshotDigest, captureDigest, reference, snapshot);
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static void requireCaptureMatches(
      AccountStartSessionAuthorityCapture expected,
      CaptureValue actual,
      AccountStartSessionActivationProtectionRequest request) {
    requireSameCaptureBinding(
        request.originalTuple().controlPlaneRequestId(),
        expected.sourceVersion(),
        expected.sourceFence(),
        expected.canonicalSha256(),
        actual.controlPlaneRequestId(),
        actual.sourceVersion(),
        actual.sourceFence(),
        actual.canonicalSha256());
    if (!expected.snapshotSha256().equals(actual.snapshotSha256())
        || !Arrays.equals(expected.snapshotBytes(), actual.snapshotBytes())
        || !Arrays.equals(expected.canonicalBytes(), actual.canonicalBytes())) {
      throw conflict();
    }
  }

  private static List<SourceEvidence> sourceVector(
      byte[] snapshotBytes, AccountStartSessionActivationProtectionRequest request) {
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
              .decode(java.nio.ByteBuffer.wrap(snapshotBytes))
              .toString();
      if (!Arrays.equals(snapshotBytes, Rfc8785CanonicalJson.canonicalizeUtf8(json)))
        throw unavailable();
      Object decoded = JSON.readValue(json, Object.class);
      if (!(decoded instanceof Map<?, ?> snapshot)
          || !SNAPSHOT_FIELDS.equals(snapshot.keySet())
          || !"account-start-session-authority-snapshot/v1".equals(snapshot.get("schema"))
          || !request
              .originalTuple()
              .controlPlaneRequestId()
              .equals(snapshot.get("controlPlaneRequestId"))
          || !(snapshot.get("sourceVector") instanceof List<?> encoded)
          || encoded.isEmpty()) {
        throw unavailable();
      }
      List<SourceEvidence> sources = new ArrayList<>(encoded.size());
      Set<String> keys = new HashSet<>();
      String previousKey = null;
      for (Object item : encoded) {
        if (!(item instanceof String base64)) throw unavailable();
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (!Base64.getEncoder().encodeToString(bytes).equals(base64)) throw unavailable();
        SourceEvidence source = SourceEvidence.fromStored(bytes);
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
    } catch (IOException | RuntimeException malformed) {
      if (malformed instanceof IllegalStateException state
          && "Original StartSession activation protection is unavailable"
              .equals(state.getMessage())) {
        throw state;
      }
      throw new IllegalStateException(
          "Original StartSession activation protection is unavailable", malformed);
    }
  }

  static void requireExactSources(List<SourceEvidence> expected, List<SourceEvidence> actual) {
    if (actual.size() != expected.size()) throw conflict();
    for (int index = 0; index < expected.size(); index++) {
      SourceEvidence left = expected.get(index);
      SourceEvidence right = actual.get(index);
      if (!left.key().equals(right.key())
          || !Arrays.equals(left.canonicalBytes(), right.canonicalBytes())) throw conflict();
    }
  }

  static void requireSameOperationIdentity(byte[] expected, byte[] actual) {
    if (expected == null
        || expected.length == 0
        || actual == null
        || !MessageDigest.isEqual(expected, actual)) {
      throw conflict();
    }
  }

  static void requireSameCaptureBinding(
      String expectedRequestId,
      long expectedVersion,
      long expectedFence,
      String expectedDigest,
      String actualRequestId,
      long actualVersion,
      long actualFence,
      String actualDigest) {
    if (expectedRequestId == null
        || !expectedRequestId.equals(actualRequestId)
        || expectedVersion <= 0L
        || expectedVersion != actualVersion
        || expectedFence <= 0L
        || expectedFence != actualFence
        || expectedDigest == null
        || !expectedDigest.equals(actualDigest)) {
      throw conflict();
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

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original StartSession activation protection is unavailable");
  }

  private static IllegalStateException conflict() {
    return new IllegalStateException(
        "Original StartSession activation protection conflicts with its immutable binding");
  }

  /** Immutable result of one authenticated World terminal resolution. */
  public record StoredSettlement(
      WorldOriginalStartSessionActivationTerminalEvidence terminal, Instant settledAt) {
    public StoredSettlement {
      Objects.requireNonNull(terminal, "terminal evidence is required");
      Objects.requireNonNull(settledAt, "settledAt is required");
    }

    public UUID protectionId() {
      return terminal.accountProtectionEvidence().accountProtectionId();
    }

    public long protectionFence() {
      return terminal.accountProtectionEvidence().accountProtectionFence();
    }

    public WorldCanonicalInstanceActivation.Outcome outcome() {
      return terminal.outcome();
    }
  }

  private record CaptureValue(
      String controlPlaneRequestId,
      long sourceVersion,
      long sourceFence,
      String snapshotSha256,
      String canonicalSha256,
      byte[] canonicalBytes,
      byte[] snapshotBytes) {
    private CaptureValue {
      if (controlPlaneRequestId == null
          || sourceVersion <= 0L
          || sourceFence <= 0L
          || snapshotSha256 == null
          || canonicalSha256 == null
          || canonicalBytes == null
          || canonicalBytes.length == 0
          || snapshotBytes == null
          || snapshotBytes.length == 0
          || !snapshotSha256.equals(digestHex(snapshotBytes))
          || !canonicalSha256.equals(digestHex(canonicalBytes))) {
        throw unavailable();
      }
      canonicalBytes = canonicalBytes.clone();
      snapshotBytes = snapshotBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    @Override
    public byte[] snapshotBytes() {
      return snapshotBytes.clone();
    }
  }
}
