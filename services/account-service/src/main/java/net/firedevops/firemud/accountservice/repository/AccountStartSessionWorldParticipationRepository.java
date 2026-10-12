package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionAuthorityCapture;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
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
 * Immutable Account storage for the distinct World StartSession receiving participation.
 *
 * <p>This repository is not a transport authenticator, authority issuer, currentness proof, or
 * execution-admission decision. Acquisition callers must authenticate the same-namespace World peer
 * before parsing, and must establish the original actor/JTI, live Account source, original redeemed
 * Game Session attempt/fence, and exact retained capture inside the existing {@code
 * withCurrentCommitted} source-protection callback before calling this repository. Remote
 * environment observations belong outside SQL. Terminal callers must separately authenticate the
 * original World operation before passing its typed terminal here.
 *
 * <p>All operations require the caller's writable READ COMMITTED Account transaction. In
 * particular, an exact create retry is not admitted by the database's insert-only expiry trigger:
 * the caller must re-establish currentness before retrying. Historical reads and terminal
 * settlement intentionally do not renew or recheck reference expiry.
 */
@Repository
public class AccountStartSessionWorldParticipationRepository {
  private static final String PARTICIPATIONS = "account_start_session_world_participations";
  private static final String SOURCES = "account_start_session_world_participation_sources";
  private static final String SETTLEMENTS = "account_start_session_world_participation_settlements";
  private static final String CAPTURES = "account_start_session_authority_captures";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern PREPARATION_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern CAPTURED_AT =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();
  private static final Set<String> PARTICIPATION_COLUMNS =
      Set.of(
          "participation_id",
          "participation_fence",
          "control_plane_request_id",
          "original_post_authorization_tuple",
          "target_namespace",
          "canonical_tenant_id",
          "canonical_game_instance_id",
          "game_session_owner_attempt_id",
          "game_session_owner_fence",
          "preparation_input_json",
          "preparation_input_digest",
          "producer_xid",
          "created_at");
  private static final Set<String> SOURCE_COLUMNS =
      Set.of("participation_id", "source_key", "source_evidence");
  private static final Set<String> SETTLEMENT_COLUMNS =
      Set.of(
          "participation_id",
          "outcome",
          "world_execution_fence",
          "terminal_bytes",
          "terminal_digest",
          "settled_at");
  private static final Set<String> CAPTURE_COLUMNS =
      Set.of(
          "control_plane_request_id",
          "account_uuid",
          "tenant_uuid",
          "target_owner",
          "logging_workload_uri",
          "reservation_owner_id",
          "reservation_claim_fence",
          "pre_authorization_tuple",
          "mutation_digest",
          "control_ui_operation_id",
          "control_ui_token_jti",
          "control_ui_token_hash",
          "control_ui_signer_receipt_sha256",
          "issuance_fence",
          "issuance_fence_source_version",
          "captured_at",
          "snapshot_sha256",
          "canonical_snapshot_bytes",
          "source_version",
          "source_fence",
          "linearization",
          "canonical_sha256",
          "canonical_capture_bytes",
          "created_at");
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
  private static final Set<String> CAPTURE_REFERENCE_FIELDS =
      Set.of("schema", "controlPlaneRequestId", "capturedAt", "bundleReference", "snapshotSha256");
  private static final Set<String> BUNDLE_REFERENCE_FIELDS =
      Set.of("bundleVersion", "sourceVersion", "sourceFence", "linearization");

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve the injected DSLContext precondition; this transactional Spring repository must remain proxyable, owns no resources, and declares no finalizer.")
  public AccountStartSessionWorldParticipationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Inserts the exact participation and every child from the already retained source capture, or
   * returns the immutable winner for an exact request retry.
   *
   * <p>Call only after the service has revalidated currentness, original redemption, and the exact
   * capture inside {@code withCurrentCommitted}. The reference expiry check in V129 runs on an
   * attempted insert; it cannot establish admission for a duplicate lookup.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredParticipation createOrReadExact(Candidate candidate) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(candidate, "exact participation candidate is required");
    List<SourceEvidence> expectedSources =
        sourceVector(candidate.capture().snapshotBytes(), candidate.controlPlaneRequestId());
    requireCaptureMatches(
        candidate.capture(),
        candidate.originalTuple(),
        selectCapture(candidate.controlPlaneRequestId(), true));

    UUID proposedId = UUID.randomUUID();
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + PARTICIPATIONS
                + " (participation_id, control_plane_request_id, original_post_authorization_tuple, "
                + "target_namespace, canonical_tenant_id, canonical_game_instance_id, "
                + "game_session_owner_attempt_id, game_session_owner_fence, preparation_input_json, "
                + "preparation_input_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (control_plane_request_id) DO NOTHING",
            proposedId,
            candidate.controlPlaneRequestId(),
            candidate.originalPostAuthorizationTuple(),
            candidate.targetNamespace(),
            candidate.canonicalTenantId(),
            candidate.canonicalGameInstanceId(),
            candidate.gameSessionOwnerAttemptId(),
            candidate.gameSessionOwnerFence(),
            candidate.preparationInputJson(),
            candidate.preparationInputDigest());

    if (inserted == 1) {
      for (SourceEvidence source : expectedSources) {
        dsl.execute(
            "INSERT INTO "
                + SOURCES
                + " (participation_id, source_key, source_evidence) VALUES (?, ?, ?)",
            proposedId,
            source.key(),
            source.canonicalBytes());
      }
    }

    Record row = selectByRequestId(candidate.controlPlaneRequestId(), true);
    if (row == null) throw unavailable();
    StoredParticipation stored = decodeParticipation(row, readSources(candidateId(row)));
    requireSameCandidate(candidate, stored);
    requireExactSources(expectedSources, stored.sources());
    if (inserted == 1 && !proposedId.equals(stored.participationId())) throw unavailable();
    if (readSettlement(stored.participationId()) != null) throw unavailable();
    return stored;
  }

  /**
   * Lookup-only read used after the caller independently establishes current Account authority.
   *
   * <p>This method never creates a capture or participation, never authenticates the peer, and
   * refuses a settled participation. The currentness proof and capture lock belong to the
   * surrounding service callback.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public java.util.Optional<StoredParticipation> findCurrentExact(Candidate expected) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(expected, "exact current participation binding is required");
    List<SourceEvidence> expectedSources =
        sourceVector(expected.capture().snapshotBytes(), expected.controlPlaneRequestId());
    requireCaptureMatches(
        expected.capture(),
        expected.originalTuple(),
        selectCapture(expected.controlPlaneRequestId(), false));
    Record row = selectByRequestId(expected.controlPlaneRequestId(), false);
    if (row == null) return java.util.Optional.empty();
    StoredParticipation stored = decodeParticipation(row, readSources(candidateId(row)));
    requireSameCandidate(expected, stored);
    requireExactSources(expectedSources, stored.sources());
    if (readSettlement(stored.participationId()) != null) throw unavailable();
    return java.util.Optional.of(stored);
  }

  /**
   * Exact historical lookup for recovery only; it proves no fresh admission or expiry extension.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public java.util.Optional<StoredParticipation> findHistoricalExact(
      UUID participationId, long participationFence) {
    requireWritableReadCommittedTransaction();
    requireNonnil(participationId, "participationId");
    requirePositive(participationFence, "participationFence");
    Record row = selectById(participationId, false);
    if (row == null) return java.util.Optional.empty();
    StoredParticipation parent = decodeParticipation(row, List.of());
    if (parent.participationFence() != participationFence) throw unavailable();
    Record capture = selectCapture(parent.controlPlaneRequestId(), false);
    List<SourceEvidence> capturedSources = decodeHistoricalCapture(capture, parent);
    List<SourceEvidence> children = readSources(participationId);
    requireExactSources(capturedSources, children);
    return java.util.Optional.of(withSources(parent, children));
  }

  /**
   * Resolves the retained World participation for the exact original StartSession tuple.
   *
   * <p>The canonical tuple's stable request identity is only a unique historical lookup key. The
   * complete tuple is compared with the retained parent, and the historical Account capture and
   * every source child are verified before its participation identity and fence are returned. This
   * supplies coordinates only; callers must independently establish fresh Account currentness
   * before using the result.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public java.util.Optional<StoredParticipation> findHistoricalForOriginalTuple(
      byte[] originalPostAuthorizationTuple) {
    requireWritableReadCommittedTransaction();
    if (originalPostAuthorizationTuple == null || originalPostAuthorizationTuple.length == 0) {
      throw unavailable();
    }

    StartSessionPostAuthorizationExecutionTuple expectedTuple;
    try {
      expectedTuple =
          StartSessionPostAuthorizationExecutionTuple.decode(originalPostAuthorizationTuple);
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
    if (!Arrays.equals(originalPostAuthorizationTuple, expectedTuple.canonicalBytes())) {
      throw unavailable();
    }

    String requestId = expectedTuple.controlPlaneRequestId();
    Record row = selectByRequestId(requestId, false);
    if (row == null) return java.util.Optional.empty();

    StoredParticipation parent = decodeParticipation(row, List.of());
    if (!MessageDigest.isEqual(
        originalPostAuthorizationTuple, parent.originalPostAuthorizationTuple())) {
      throw unavailable();
    }
    List<SourceEvidence> capturedSources =
        decodeHistoricalCapture(selectCapture(requestId, false), parent);
    List<SourceEvidence> children = readSources(parent.participationId());
    requireExactSources(capturedSources, children);
    return java.util.Optional.of(withSources(parent, children));
  }

  /**
   * Lookup-only post-commit readback of an immutable terminal receipt for exact historical
   * participation identity.
   *
   * <p>A missing participation or receipt returns empty. This does not admit a new operation, renew
   * reference expiry, or require a current actor; it validates the retained historical parent,
   * capture, complete source vector, and terminal binding only. It never inserts.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public java.util.Optional<StoredSettlement> findSettlementExact(
      UUID participationId, long participationFence) {
    requireWritableReadCommittedTransaction();
    java.util.Optional<StoredParticipation> participation =
        findHistoricalExact(participationId, participationFence);
    if (participation.isEmpty()) return java.util.Optional.empty();
    Record row = readSettlement(participationId);
    if (row == null) return java.util.Optional.empty();
    return java.util.Optional.of(decodeSettlement(row, participation.orElseThrow()));
  }

  /**
   * Appends or reads back the exact typed terminal for an existing participation.
   *
   * <p>The caller authenticates the original World operation before invoking this method. This
   * storage boundary accepts no raw outcome or fence and does not require a current actor or an
   * unexpired reference; it only binds immutable historical identity and terminal bytes.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredSettlement settleExact(WorldStartSessionExecutionTerminal terminal) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(terminal, "authenticated typed World terminal is required");
    StoredParticipation participation =
        findHistoricalExact(
                terminal.accountWorldParticipationId(), terminal.accountWorldParticipationFence())
            .orElseThrow(AccountStartSessionWorldParticipationRepository::unavailable);
    requireTerminalMatches(participation, terminal);

    byte[] terminalBytes = terminal.canonicalBytes();
    dsl.execute(
        "INSERT INTO "
            + SETTLEMENTS
            + " (participation_id, outcome, world_execution_fence, terminal_bytes, terminal_digest) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (participation_id) DO NOTHING",
        participation.participationId(),
        terminal.outcome().name(),
        terminal.worldExecutionFence(),
        terminalBytes,
        terminal.digest());

    Record row = readSettlement(participation.participationId());
    if (row == null) throw unavailable();
    StoredSettlement stored = decodeSettlement(row, participation);
    requireSameTerminal(stored, terminal);
    return stored;
  }

  private Record selectByRequestId(String requestId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + PARTICIPATIONS
            + " WHERE control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        requestId);
  }

  private Record selectById(UUID id, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + PARTICIPATIONS
            + " WHERE participation_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        id);
  }

  private Record selectCapture(String requestId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + CAPTURES
            + " WHERE control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        requestId);
  }

  private List<SourceEvidence> readSources(UUID participationId) {
    var rows =
        dsl.fetch("SELECT * FROM " + SOURCES + " WHERE participation_id = ?", participationId);
    List<SourceEvidence> sources = new ArrayList<>(rows.size());
    for (Record row : rows) {
      requireColumns(row, SOURCE_COLUMNS, "participation source");
      UUID storedId = typedUuid(row, "participation_id");
      String key = requiredString(row, "source_key");
      byte[] bytes = requiredBytes(row, "source_evidence");
      if (!participationId.equals(storedId)) throw unavailable();
      SourceEvidence source = decodeSource(key, bytes);
      sources.add(source);
    }
    sources.sort(Comparator.comparing(SourceEvidence::key));
    if (sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()) {
      throw unavailable();
    }
    return List.copyOf(sources);
  }

  static StoredParticipation decodeParticipation(Record row, List<SourceEvidence> sources) {
    try {
      requireColumns(row, PARTICIPATION_COLUMNS, "participation");
      UUID id = typedUuid(row, "participation_id");
      long fence = positive(number(row, "participation_fence"), "participation_fence");
      String requestId = requiredString(row, "control_plane_request_id");
      byte[] tupleBytes = requiredBytes(row, "original_post_authorization_tuple");
      StartSessionPostAuthorizationExecutionTuple tuple =
          StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
      String namespace = requiredString(row, "target_namespace");
      UUID tenant = typedUuid(row, "canonical_tenant_id");
      UUID instance = typedUuid(row, "canonical_game_instance_id");
      UUID ownerAttempt = typedUuid(row, "game_session_owner_attempt_id");
      long ownerFence =
          positive(number(row, "game_session_owner_fence"), "game_session_owner_fence");
      String preparationJson = requiredString(row, "preparation_input_json");
      String preparationDigest = requiredString(row, "preparation_input_digest");
      long producerXid = positive(number(row, "producer_xid"), "producer_xid");
      OffsetDateTime createdAt =
          Objects.requireNonNull(row.get("created_at", OffsetDateTime.class));
      if (id.equals(NIL_UUID)
          || !requestId.equals(tuple.controlPlaneRequestId())
          || !namespace.equals(tuple.preAuthorizationTuple().action().scope().targetNamespace())
          || !tenant.equals(tuple.preAuthorizationTuple().action().scope().tenantId())
          || !"game-session-service".equals(tuple.preAuthorizationTuple().targetOwner())
          || !PREPARATION_DIGEST.matcher(preparationDigest).matches()
          || !preparationDigest.equals(
              digestPrefixed(preparationJson.getBytes(StandardCharsets.UTF_8)))) {
        throw unavailable();
      }
      return new StoredParticipation(
          id,
          fence,
          requestId,
          tupleBytes,
          namespace,
          tenant,
          instance,
          ownerAttempt,
          ownerFence,
          preparationJson,
          preparationDigest,
          producerXid,
          createdAt,
          sources);
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalStateException state
          && "Exact StartSession World participation evidence unavailable"
              .equals(state.getMessage())) {
        throw state;
      }
      throw unavailable();
    }
  }

  private static StoredParticipation withSources(
      StoredParticipation parent, List<SourceEvidence> sources) {
    return new StoredParticipation(
        parent.participationId(),
        parent.participationFence(),
        parent.controlPlaneRequestId(),
        parent.originalPostAuthorizationTuple(),
        parent.targetNamespace(),
        parent.canonicalTenantId(),
        parent.canonicalGameInstanceId(),
        parent.gameSessionOwnerAttemptId(),
        parent.gameSessionOwnerFence(),
        parent.preparationInputJson(),
        parent.preparationInputDigest(),
        parent.producerXid(),
        parent.createdAt(),
        sources);
  }

  private static List<SourceEvidence> sourceVector(byte[] snapshotBytes, String requestId) {
    Map<String, Object> snapshot = parseCanonicalObject(snapshotBytes, "Account capture snapshot");
    if (!CAPTURE_SNAPSHOT_FIELDS.equals(snapshot.keySet())
        || !"account-start-session-authority-snapshot/v1".equals(snapshot.get("schema"))
        || !requestId.equals(snapshot.get("controlPlaneRequestId"))) {
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
  }

  private static SourceEvidence decodeSource(String key, byte[] bytes) {
    try {
      SourceEvidence source = SourceEvidence.fromStored(bytes);
      if (!key.equals(source.key()) || bytes.length > 131_072) throw unavailable();
      return source;
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static void requireCaptureMatches(
      AccountStartSessionAuthorityCapture capture,
      StartSessionPostAuthorizationExecutionTuple tuple,
      Record retainedRow) {
    Objects.requireNonNull(capture, "exact retained Account capture is required");
    Objects.requireNonNull(tuple, "exact original StartSession tuple is required");
    if (!capture.controlPlaneRequestId().equals(tuple.controlPlaneRequestId())) throw conflict();
    if (retainedRow == null) throw unavailable();
    Map<String, Object> snapshot =
        parseCanonicalObject(capture.snapshotBytes(), "Account capture snapshot");
    if (!CAPTURE_SNAPSHOT_FIELDS.equals(snapshot.keySet())
        || !"account-start-session-authority-snapshot/v1".equals(snapshot.get("schema"))
        || !tuple.controlPlaneRequestId().equals(snapshot.get("controlPlaneRequestId"))
        || !Base64.getEncoder()
            .encodeToString(
                tuple.preAuthorizationTuple().canonicalJson().getBytes(StandardCharsets.UTF_8))
            .equals(snapshot.get("preAuthorizationTuple"))
        || !tuple.mutationDigest().equals(snapshot.get("mutationDigest"))
        || !tuple
            .preAuthorizationTuple()
            .actor()
            .accountId()
            .toString()
            .equals(snapshot.get("accountId"))
        || !tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .tenantId()
            .toString()
            .equals(snapshot.get("tenantId"))
        || !"game-session-service".equals(snapshot.get("targetOwner"))
        || !tuple.authenticatedWorkloadIdentity().equals(snapshot.get("loggingWorkloadUri"))
        || !tuple.reservationOwnerId().toString().equals(snapshot.get("reservationOwnerId"))
        || !Long.toString(tuple.reservationClaimFence())
            .equals(snapshot.get("reservationClaimFence"))
        || !tuple.issuanceFence().equals(snapshot.get("issuanceFence"))
        || !capture.bundleReference().equals(tuple.bundleReference())) {
      throw conflict();
    }
    sourceVector(capture.snapshotBytes(), tuple.controlPlaneRequestId());
    requireCaptureRow(retainedRow, capture, tuple, snapshot);
  }

  private static void requireCaptureRow(
      Record row,
      AccountStartSessionAuthorityCapture capture,
      StartSessionPostAuthorizationExecutionTuple tuple,
      Map<String, Object> snapshot) {
    requireColumns(row, CAPTURE_COLUMNS, "Account capture");
    requireCaptureColumnsMatchSnapshot(row, snapshot);
    OffsetDateTime capturedAt = requiredTimestamp(row, "captured_at");
    if (!tuple.controlPlaneRequestId().equals(requiredString(row, "control_plane_request_id"))
        || !Instant.parse(capture.capturedAt()).equals(capturedAt.toInstant())
        || !capture.controlPlaneRequestId().equals(requiredString(row, "control_plane_request_id"))
        || capture.sourceVersion() != number(row, "source_version")
        || capture.sourceFence() != number(row, "source_fence")
        || !capture.linearization().equals(requiredString(row, "linearization"))
        || !capture.snapshotSha256().equals(requiredString(row, "snapshot_sha256"))
        || !Arrays.equals(capture.snapshotBytes(), requiredBytes(row, "canonical_snapshot_bytes"))
        || !capture.canonicalSha256().equals(requiredString(row, "canonical_sha256"))
        || !Arrays.equals(
            capture.canonicalBytes(), requiredBytes(row, "canonical_capture_bytes"))) {
      throw unavailable();
    }
  }

  private static void requireCaptureColumnsMatchSnapshot(Record row, Map<String, Object> snapshot) {
    if (!Objects.equals(requiredUuidString(row, "account_uuid"), snapshot.get("accountId"))
        || !Objects.equals(requiredUuidString(row, "tenant_uuid"), snapshot.get("tenantId"))
        || !Objects.equals(requiredString(row, "target_owner"), snapshot.get("targetOwner"))
        || !Objects.equals(
            requiredString(row, "logging_workload_uri"), snapshot.get("loggingWorkloadUri"))
        || !Objects.equals(
            requiredUuidString(row, "reservation_owner_id"), snapshot.get("reservationOwnerId"))
        || !Objects.equals(
            Long.toString(number(row, "reservation_claim_fence")),
            snapshot.get("reservationClaimFence"))
        || !Arrays.equals(
            requiredBytes(row, "pre_authorization_tuple"),
            strictBase64(string(snapshot.get("preAuthorizationTuple"))))
        || !Objects.equals(requiredString(row, "mutation_digest"), snapshot.get("mutationDigest"))
        || !Objects.equals(
            requiredUuidString(row, "control_ui_operation_id"),
            snapshot.get("controlUiOperationId"))
        || !Objects.equals(
            requiredUuidString(row, "control_ui_token_jti"), snapshot.get("controlUiTokenJti"))
        || !Objects.equals(
            requiredString(row, "control_ui_token_hash"), snapshot.get("controlUiTokenHash"))
        || !Objects.equals(
            requiredString(row, "control_ui_signer_receipt_sha256"),
            snapshot.get("controlUiSignerReceiptSha256"))
        || !Objects.equals(
            Long.toString(number(row, "issuance_fence")), snapshot.get("issuanceFence"))
        || !Objects.equals(
            Long.toString(number(row, "issuance_fence_source_version")),
            snapshot.get("issuanceFenceSourceVersion"))) {
      throw unavailable();
    }
    byte[] receipt = strictBase64(string(snapshot.get("controlUiSignerReceipt")));
    if (!digestHex(receipt).equals(snapshot.get("controlUiSignerReceiptSha256")))
      throw unavailable();
  }

  private static List<SourceEvidence> decodeHistoricalCapture(
      Record row, StoredParticipation parent) {
    requireColumns(row, CAPTURE_COLUMNS, "Account capture");
    byte[] snapshotBytes = requiredBytes(row, "canonical_snapshot_bytes");
    byte[] canonicalCaptureBytes = requiredBytes(row, "canonical_capture_bytes");
    if (!digestHex(snapshotBytes).equals(requiredString(row, "snapshot_sha256"))
        || !digestHex(canonicalCaptureBytes).equals(requiredString(row, "canonical_sha256"))) {
      throw unavailable();
    }
    Map<String, Object> snapshot = parseCanonicalObject(snapshotBytes, "Account capture snapshot");
    Map<String, Object> reference =
        parseCanonicalObject(canonicalCaptureBytes, "Account capture reference");
    if (!CAPTURE_SNAPSHOT_FIELDS.equals(snapshot.keySet())
        || !CAPTURE_REFERENCE_FIELDS.equals(reference.keySet())
        || !"account-start-session-authority-snapshot/v1".equals(snapshot.get("schema"))
        || !"account-start-session-authority-capture/v1".equals(reference.get("schema"))
        || !parent.controlPlaneRequestId().equals(requiredString(row, "control_plane_request_id"))
        || !parent.controlPlaneRequestId().equals(snapshot.get("controlPlaneRequestId"))
        || !parent.controlPlaneRequestId().equals(reference.get("controlPlaneRequestId"))
        || !requiredString(row, "snapshot_sha256").equals(reference.get("snapshotSha256"))
        || !CAPTURED_AT.matcher(string(reference.get("capturedAt"))).matches()
        || !Instant.parse(string(reference.get("capturedAt")))
            .equals(requiredTimestamp(row, "captured_at").toInstant())
        || !referenceMatchesCaptureRow(reference, row)) {
      throw unavailable();
    }
    requireCaptureColumnsMatchSnapshot(row, snapshot);
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(parent.originalPostAuthorizationTuple());
    if (!Base64.getEncoder()
            .encodeToString(
                tuple.preAuthorizationTuple().canonicalJson().getBytes(StandardCharsets.UTF_8))
            .equals(snapshot.get("preAuthorizationTuple"))
        || !tuple.mutationDigest().equals(snapshot.get("mutationDigest"))
        || !tuple
            .preAuthorizationTuple()
            .actor()
            .accountId()
            .toString()
            .equals(snapshot.get("accountId"))
        || !parent.canonicalTenantId().toString().equals(snapshot.get("tenantId"))
        || !tuple.authenticatedWorkloadIdentity().equals(snapshot.get("loggingWorkloadUri"))
        || !tuple.reservationOwnerId().toString().equals(snapshot.get("reservationOwnerId"))
        || !Long.toString(tuple.reservationClaimFence())
            .equals(snapshot.get("reservationClaimFence"))
        || !tuple.issuanceFence().equals(snapshot.get("issuanceFence"))
        || !tuple.preAuthorizationTuple().targetOwner().equals(snapshot.get("targetOwner"))
        || !referenceMatchesTuple(reference, tuple)
        || !parent
            .targetNamespace()
            .equals(tuple.preAuthorizationTuple().action().scope().targetNamespace())
        || !parent
            .canonicalTenantId()
            .equals(tuple.preAuthorizationTuple().action().scope().tenantId())) {
      throw unavailable();
    }
    return sourceVector(snapshotBytes, parent.controlPlaneRequestId());
  }

  private static boolean referenceMatchesCaptureRow(Map<String, Object> reference, Record row) {
    Object bundleValue = reference.get("bundleReference");
    if (!(bundleValue instanceof Map<?, ?> bundle)
        || !BUNDLE_REFERENCE_FIELDS.equals(bundle.keySet())) return false;
    return StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION.equals(bundle.get("bundleVersion"))
        && Long.toString(number(row, "source_version")).equals(bundle.get("sourceVersion"))
        && Long.toString(number(row, "source_fence")).equals(bundle.get("sourceFence"))
        && requiredString(row, "linearization").equals(bundle.get("linearization"));
  }

  private static boolean referenceMatchesTuple(
      Map<String, Object> reference, StartSessionPostAuthorizationExecutionTuple tuple) {
    Object bundleValue = reference.get("bundleReference");
    if (!(bundleValue instanceof Map<?, ?> bundle)) return false;
    var expected = tuple.bundleReference();
    return expected.bundleVersion().equals(bundle.get("bundleVersion"))
        && expected.sourceVersion().equals(bundle.get("sourceVersion"))
        && expected.sourceFence().equals(bundle.get("sourceFence"))
        && expected.linearization().equals(bundle.get("linearization"));
  }

  static void requireSameCandidate(Candidate candidate, StoredParticipation stored) {
    if (!candidate.controlPlaneRequestId().equals(stored.controlPlaneRequestId())
        || !Arrays.equals(
            candidate.originalPostAuthorizationTuple(), stored.originalPostAuthorizationTuple())
        || !candidate.targetNamespace().equals(stored.targetNamespace())
        || !candidate.canonicalTenantId().equals(stored.canonicalTenantId())
        || !candidate.canonicalGameInstanceId().equals(stored.canonicalGameInstanceId())
        || !candidate.gameSessionOwnerAttemptId().equals(stored.gameSessionOwnerAttemptId())
        || candidate.gameSessionOwnerFence() != stored.gameSessionOwnerFence()
        || !candidate.preparationInputJson().equals(stored.preparationInputJson())
        || !candidate.preparationInputDigest().equals(stored.preparationInputDigest())) {
      throw conflict();
    }
  }

  static void requireExactSources(List<SourceEvidence> expected, List<SourceEvidence> actual) {
    if (expected == null
        || actual == null
        || expected.isEmpty()
        || expected.size() != actual.size()) {
      throw unavailable();
    }
    List<SourceEvidence> expectedSorted =
        expected.stream().sorted(Comparator.comparing(SourceEvidence::key)).toList();
    List<SourceEvidence> actualSorted =
        actual.stream().sorted(Comparator.comparing(SourceEvidence::key)).toList();
    Set<String> expectedKeys = new HashSet<>();
    for (int index = 0; index < expectedSorted.size(); index++) {
      SourceEvidence left = expectedSorted.get(index);
      SourceEvidence right = actualSorted.get(index);
      if (!expectedKeys.add(left.key())
          || !left.key().equals(right.key())
          || !Arrays.equals(left.canonicalBytes(), right.canonicalBytes())) {
        throw unavailable();
      }
    }
  }

  static void requireTerminalMatches(
      StoredParticipation participation, WorldStartSessionExecutionTerminal terminal) {
    if (!participation.participationId().equals(terminal.accountWorldParticipationId())
        || participation.participationFence() != terminal.accountWorldParticipationFence()
        || !Arrays.equals(
            participation.originalPostAuthorizationTuple(),
            terminal.originalPostAuthorizationTuple())
        || !participation.gameSessionOwnerAttemptId().equals(terminal.gameSessionOwnerAttemptId())
        || participation.gameSessionOwnerFence() != terminal.gameSessionOwnerFence()
        || !participation.targetNamespace().equals(terminal.targetNamespace())
        || !participation.canonicalTenantId().equals(terminal.canonicalTenantId())
        || !participation.controlPlaneRequestId().equals(terminal.controlPlaneRequestId())
        || !participation.canonicalGameInstanceId().equals(terminal.canonicalGameInstanceId())
        || !participation.preparationInputDigest().equals(terminal.preparationInputDigest())
        || !participation.preparationInputJson().equals(terminal.preparationInputJson())) {
      throw conflict();
    }
  }

  private Record readSettlement(UUID participationId) {
    return dsl.fetchOne(
        "SELECT * FROM " + SETTLEMENTS + " WHERE participation_id = ?", participationId);
  }

  private static StoredSettlement decodeSettlement(Record row, StoredParticipation participation) {
    try {
      requireColumns(row, SETTLEMENT_COLUMNS, "participation settlement");
      UUID id = typedUuid(row, "participation_id");
      String outcomeValue = requiredString(row, "outcome");
      WorldStartSessionExecutionTerminal.Outcome outcome =
          WorldStartSessionExecutionTerminal.Outcome.valueOf(outcomeValue);
      long worldFence = positive(number(row, "world_execution_fence"), "world_execution_fence");
      byte[] terminalBytes = requiredBytes(row, "terminal_bytes");
      String terminalDigest = requiredString(row, "terminal_digest");
      OffsetDateTime settledAt = requiredTimestamp(row, "settled_at");
      if (!id.equals(participation.participationId())
          || !PREPARATION_DIGEST.matcher(terminalDigest).matches()
          || !terminalDigest.equals(digestPrefixed(terminalBytes))) {
        throw unavailable();
      }
      WorldStartSessionExecutionTerminal terminal =
          WorldStartSessionExecutionTerminal.fromStored(terminalBytes);
      requireTerminalMatches(participation, terminal);
      if (terminal.outcome() != outcome || terminal.worldExecutionFence() != worldFence) {
        throw unavailable();
      }
      return new StoredSettlement(
          id, outcome, worldFence, terminalBytes, terminalDigest, settledAt);
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static void requireSameTerminal(
      StoredSettlement stored, WorldStartSessionExecutionTerminal terminal) {
    if (stored.outcome() != terminal.outcome()
        || stored.worldExecutionFence() != terminal.worldExecutionFence()
        || !stored.terminalDigest().equals(terminal.digest())
        || !Arrays.equals(stored.terminalBytes(), terminal.canonicalBytes())) {
      throw conflict();
    }
  }

  private static Map<String, Object> parseCanonicalObject(byte[] bytes, String field) {
    try {
      String json = strictUtf8(bytes);
      if (!Arrays.equals(Rfc8785CanonicalJson.canonicalizeUtf8(json), bytes)) throw unavailable();
      Object decoded = JSON.readValue(json, Object.class);
      if (!(decoded instanceof Map<?, ?> raw)) throw unavailable();
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : raw.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw unavailable();
        result.put(key, entry.getValue());
      }
      return Map.copyOf(result);
    } catch (RuntimeException | java.io.IOException malformed) {
      throw unavailable();
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
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw unavailable();
    }
  }

  private static void requireColumns(Record row, Set<String> expected, String kind) {
    if (row == null) throw unavailable();
    Set<String> actual = new HashSet<>();
    Arrays.stream(row.fields()).forEach(field -> actual.add(field.getName()));
    if (!actual.equals(expected)) throw unavailable();
  }

  private static UUID candidateId(Record row) {
    return typedUuid(row, "participation_id");
  }

  private static UUID typedUuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || value.equals(NIL_UUID)) throw unavailable();
    return value;
  }

  private static String requiredUuidString(Record row, String field) {
    return typedUuid(row, field).toString();
  }

  private static String requiredString(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw unavailable();
    return value;
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null) throw unavailable();
    return value.clone();
  }

  private static OffsetDateTime requiredTimestamp(Record row, String field) {
    OffsetDateTime value = row.get(field, OffsetDateTime.class);
    if (value == null) throw unavailable();
    return value;
  }

  private static long number(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null) throw unavailable();
    return value;
  }

  private static long positive(long value, String field) {
    if (value <= 0L) throw unavailable();
    return value;
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0L) throw new IllegalArgumentException(field + " must be positive");
  }

  private static void requireNonnil(UUID value, String field) {
    if (value == null || value.equals(NIL_UUID))
      throw new IllegalArgumentException(field + " must be nonnil");
  }

  private static String string(Object value) {
    if (!(value instanceof String text)) throw unavailable();
    return text;
  }

  private static String digestPrefixed(byte[] bytes) {
    return "sha256:" + digestHex(bytes);
  }

  private static String digestHex(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
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

  private static IllegalStateException conflict() {
    return new IllegalStateException(
        "StartSession World participation conflicts with its immutable binding");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Exact StartSession World participation evidence unavailable");
  }

  public record Candidate(
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      AccountStartSessionAuthorityCapture capture,
      UUID canonicalGameInstanceId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      String preparationInputJson,
      String preparationInputDigest) {
    public Candidate {
      Objects.requireNonNull(originalTuple, "typed original StartSession tuple is required");
      Objects.requireNonNull(capture, "exact retained Account capture is required");
      requireNonnil(canonicalGameInstanceId, "canonicalGameInstanceId");
      requireNonnil(gameSessionOwnerAttemptId, "gameSessionOwnerAttemptId");
      requirePositive(gameSessionOwnerFence, "gameSessionOwnerFence");
      Objects.requireNonNull(preparationInputJson, "exact preparation input JSON is required");
      Objects.requireNonNull(preparationInputDigest, "preparation input digest is required");
      if (!"StartSession".equals(originalTuple.preAuthorizationTuple().actionFamily())
          || !"game-session-service".equals(originalTuple.preAuthorizationTuple().targetOwner())
          || !capture.controlPlaneRequestId().equals(originalTuple.controlPlaneRequestId())
          || preparationInputJson.isBlank()
          || !StandardCharsets.UTF_8.newEncoder().canEncode(preparationInputJson)
          || preparationInputJson.getBytes(StandardCharsets.UTF_8).length
              > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES
          || !PREPARATION_DIGEST.matcher(preparationInputDigest).matches()
          || !digestPrefixed(preparationInputJson.getBytes(StandardCharsets.UTF_8))
              .equals(preparationInputDigest)) {
        throw new IllegalArgumentException(
            "StartSession World participation candidate is malformed");
      }
      sourceVector(capture.snapshotBytes(), originalTuple.controlPlaneRequestId());
    }

    public byte[] originalPostAuthorizationTuple() {
      return originalTuple.canonicalBytes();
    }

    public String controlPlaneRequestId() {
      return originalTuple.controlPlaneRequestId();
    }

    public String targetNamespace() {
      return originalTuple.preAuthorizationTuple().action().scope().targetNamespace();
    }

    public UUID canonicalTenantId() {
      return originalTuple.preAuthorizationTuple().action().scope().tenantId();
    }

    @Override
    public String toString() {
      return "Candidate[requestId=" + controlPlaneRequestId() + ", preparationInput=<redacted>]";
    }
  }

  public record StoredParticipation(
      UUID participationId,
      long participationFence,
      String controlPlaneRequestId,
      byte[] originalPostAuthorizationTuple,
      String targetNamespace,
      UUID canonicalTenantId,
      UUID canonicalGameInstanceId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      String preparationInputJson,
      String preparationInputDigest,
      long producerXid,
      OffsetDateTime createdAt,
      List<SourceEvidence> sources) {
    public StoredParticipation {
      requireNonnil(participationId, "participationId");
      requirePositive(participationFence, "participationFence");
      Objects.requireNonNull(controlPlaneRequestId);
      originalPostAuthorizationTuple = originalPostAuthorizationTuple.clone();
      requireNonnil(canonicalTenantId, "canonicalTenantId");
      requireNonnil(canonicalGameInstanceId, "canonicalGameInstanceId");
      requireNonnil(gameSessionOwnerAttemptId, "gameSessionOwnerAttemptId");
      requirePositive(gameSessionOwnerFence, "gameSessionOwnerFence");
      requirePositive(producerXid, "producerXid");
      Objects.requireNonNull(preparationInputJson);
      Objects.requireNonNull(preparationInputDigest);
      Objects.requireNonNull(createdAt);
      sources = List.copyOf(sources);
    }

    @Override
    public byte[] originalPostAuthorizationTuple() {
      return originalPostAuthorizationTuple.clone();
    }

    @Override
    public String toString() {
      return "StoredParticipation[id="
          + participationId
          + ", fence="
          + participationFence
          + ", requestId="
          + controlPlaneRequestId
          + ", sources="
          + sources.size()
          + "]";
    }

    public List<SourceEvidence> sourceVector() {
      return sources;
    }
  }

  public record StoredSettlement(
      UUID participationId,
      WorldStartSessionExecutionTerminal.Outcome outcome,
      long worldExecutionFence,
      byte[] terminalBytes,
      String terminalDigest,
      OffsetDateTime settledAt) {
    public StoredSettlement {
      requireNonnil(participationId, "participationId");
      Objects.requireNonNull(outcome);
      requirePositive(worldExecutionFence, "worldExecutionFence");
      terminalBytes = terminalBytes.clone();
      Objects.requireNonNull(terminalDigest);
      Objects.requireNonNull(settledAt);
    }

    @Override
    public byte[] terminalBytes() {
      return terminalBytes.clone();
    }

    @Override
    public String toString() {
      return "StoredSettlement[id="
          + participationId
          + ", outcome="
          + outcome
          + ", fence="
          + worldExecutionFence
          + "]";
    }
  }
}
