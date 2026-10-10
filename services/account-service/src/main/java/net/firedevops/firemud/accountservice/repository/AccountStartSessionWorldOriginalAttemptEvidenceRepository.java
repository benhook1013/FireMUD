package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable storage for the exact observation of the original live Game Session StartSession
 * attempt that is attached to an existing Account World participation.
 *
 * <p>This repository does not authenticate Game Session, continuously establish owner authority,
 * issue or renew an Account reference, or authorize World admission. The attached response is a
 * point-in-time observation. Callers must obtain it from the exact same-namespace Game Session read
 * and independently perform the current Account authority checks required by the owning service
 * path.
 *
 * <p>All operations require the caller's writable READ COMMITTED Account transaction. Current
 * retention and lookup lock the exact immutable participation before checking settlement and the
 * original lease against the Account database clock. Historical readback is lookup-only and may
 * return expired evidence; it cannot be used as current admission evidence.
 */
@Repository
public class AccountStartSessionWorldOriginalAttemptEvidenceRepository {
  private static final String EVIDENCE = "account_start_session_world_original_attempt_evidence";
  private static final String PARTICIPATIONS = "account_start_session_world_participations";
  private static final String SOURCES = "account_start_session_world_participation_sources";
  private static final String SETTLEMENTS = "account_start_session_world_participation_settlements";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Set<String> EVIDENCE_COLUMNS =
      Set.of(
          "participation_id",
          "target_namespace",
          "game_session_owner_attempt_id",
          "game_session_owner_mutation_id",
          "game_session_owner_fence",
          "original_lease_expires_at",
          "original_response_bytes",
          "original_response_digest");
  private static final Set<String> SOURCE_COLUMNS =
      Set.of("participation_id", "source_key", "source_evidence");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve the injected DSLContext precondition; this transactional Spring repository must remain proxyable, owns no resources, and declares no finalizer.")
  public AccountStartSessionWorldOriginalAttemptEvidenceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Appends the first complete observation for an existing unsettled participation, or reads back
   * its immutable winner for an exact semantic retry.
   *
   * <p>A new transport correlation UUID is allowed on retry. The original tuple, target namespace,
   * owner attempt, owner mutation UUID, owner fence, original expiry, and exact Account projection
   * must remain unchanged. The first encoded complete response and its digest are retained.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredEvidence retainCurrentExact(StoredParticipation participation, Result observation) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(participation, "exact Account World participation is required");
    Objects.requireNonNull(observation, "exact current Game Session observation is required");

    byte[] responseBytes =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observation).toByteArray();
    Result decoded = decodeResponse(observation.request(), responseBytes, participation);

    StoredParticipation locked = lockExactParent(participation);
    requirePending(locked.participationId());
    requireLeaseCurrent(decoded.originalLeaseExpiresAt());

    dsl.execute(
        "INSERT INTO "
            + EVIDENCE
            + " (participation_id, target_namespace, game_session_owner_attempt_id, "
            + "game_session_owner_mutation_id, game_session_owner_fence, original_lease_expires_at, "
            + "original_response_bytes, original_response_digest) "
            + "VALUES (?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?) "
            + "ON CONFLICT (participation_id) DO NOTHING",
        locked.participationId(),
        decoded.request().targetNamespace(),
        decoded.request().expectedOwnerAttemptId(),
        decoded.request().expectedOwnerMutationId(),
        decoded.request().expectedOwnerFence(),
        OffsetDateTime.ofInstant(decoded.originalLeaseExpiresAt(), java.time.ZoneOffset.UTC),
        responseBytes,
        digestPrefixed(responseBytes));

    Record row = selectEvidence(locked.participationId(), false);
    if (row == null) throw unavailable();
    StoredEvidence stored = decodeStoredEvidence(row, locked);
    requireSameSemanticObservation(decoded, stored.result());
    // Recheck after row-lock waits and readback work. The migration's INSERT guard independently
    // checks the database clock after its parent lock as well.
    requireLeaseCurrent(stored.originalLeaseExpiresAt());
    return stored;
  }

  /**
   * Lookup-only current read. It locks the exact pending Account participation and rechecks the
   * retained original lease against the database clock after the lock wait.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredEvidence> findCurrentExact(
      StoredParticipation participation, Request expectedRequest) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(participation, "exact Account World participation is required");
    Objects.requireNonNull(expectedRequest, "exact current-attempt request is required");
    StoredParticipation locked = lockExactParent(participation);
    requirePending(locked.participationId());
    Record row = selectEvidence(locked.participationId(), false);
    if (row == null) return Optional.empty();
    StoredEvidence stored = decodeStoredEvidence(row, locked);
    requireSameSemanticRequest(expectedRequest, stored.result().request());
    requireLeaseCurrent(stored.originalLeaseExpiresAt());
    return Optional.of(stored);
  }

  /**
   * Exact historical readback for recovery. It neither locks for admission nor checks current
   * expiry or settlement; the returned observation remains historical evidence only.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredEvidence> findHistoricalExact(
      StoredParticipation participation, Request expectedRequest) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(participation, "exact Account World participation is required");
    Objects.requireNonNull(expectedRequest, "exact historical current-attempt request is required");
    StoredParticipation historical = readExactParent(participation, false);
    if (historical == null) return Optional.empty();
    Record row = selectEvidence(historical.participationId(), false);
    if (row == null) return Optional.empty();
    StoredEvidence stored = decodeStoredEvidence(row, historical);
    requireSameSemanticRequest(expectedRequest, stored.result().request());
    return Optional.of(stored);
  }

  private StoredParticipation lockExactParent(StoredParticipation expected) {
    StoredParticipation stored = readExactParent(expected, true);
    if (stored == null) throw unavailable();
    return stored;
  }

  private StoredParticipation readExactParent(StoredParticipation expected, boolean forUpdate) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + PARTICIPATIONS
                + " WHERE participation_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            expected.participationId());
    if (row == null) return null;
    StoredParticipation actual =
        AccountStartSessionWorldParticipationRepository.decodeParticipation(row, List.of());
    requireSameParent(expected, actual);
    requireSameParentSources(expected);
    return actual;
  }

  private void requireSameParentSources(StoredParticipation expected) {
    var rows =
        dsl.fetch(
            "SELECT * FROM " + SOURCES + " WHERE participation_id = ? ORDER BY source_key",
            expected.participationId());
    List<SourceRow> actual = new ArrayList<>(rows.size());
    for (Record row : rows) {
      requireColumns(row, SOURCE_COLUMNS, "participation source");
      if (!expected.participationId().equals(requiredUuid(row, "participation_id"))) {
        throw unavailable();
      }
      actual.add(
          new SourceRow(requiredString(row, "source_key"), requiredBytes(row, "source_evidence")));
    }
    actual.sort(Comparator.comparing(SourceRow::key));
    List<SourceEvidence> expectedSources =
        expected.sources().stream().sorted(Comparator.comparing(SourceEvidence::key)).toList();
    if (actual.size() != expectedSources.size()) throw conflict();
    for (int index = 0; index < actual.size(); index++) {
      SourceRow left = actual.get(index);
      SourceEvidence right = expectedSources.get(index);
      if (!left.key().equals(right.key())
          || !Arrays.equals(left.canonicalBytes(), right.canonicalBytes())) {
        throw conflict();
      }
    }
  }

  private static void requireSameParent(StoredParticipation expected, StoredParticipation actual) {
    if (!expected.participationId().equals(actual.participationId())
        || expected.participationFence() != actual.participationFence()
        || !expected.controlPlaneRequestId().equals(actual.controlPlaneRequestId())
        || !Arrays.equals(
            expected.originalPostAuthorizationTuple(), actual.originalPostAuthorizationTuple())
        || !expected.targetNamespace().equals(actual.targetNamespace())
        || !expected.canonicalTenantId().equals(actual.canonicalTenantId())
        || !expected.canonicalGameInstanceId().equals(actual.canonicalGameInstanceId())
        || !expected.gameSessionOwnerAttemptId().equals(actual.gameSessionOwnerAttemptId())
        || expected.gameSessionOwnerFence() != actual.gameSessionOwnerFence()
        || !expected.preparationInputJson().equals(actual.preparationInputJson())
        || !expected.preparationInputDigest().equals(actual.preparationInputDigest())
        || expected.producerXid() != actual.producerXid()
        || !expected.createdAt().equals(actual.createdAt())) {
      throw conflict();
    }
  }

  private void requirePending(UUID participationId) {
    if (dsl.fetchOne(
            "SELECT participation_id FROM " + SETTLEMENTS + " WHERE participation_id = ?",
            participationId)
        != null) {
      throw unavailable();
    }
  }

  private Record selectEvidence(UUID participationId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + EVIDENCE
            + " WHERE participation_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        participationId);
  }

  private StoredEvidence decodeStoredEvidence(Record row, StoredParticipation parent) {
    try {
      requireColumns(row, EVIDENCE_COLUMNS, "original attempt evidence");
      UUID participationId = requiredUuid(row, "participation_id");
      String namespace = requiredString(row, "target_namespace");
      UUID ownerAttempt = requiredUuid(row, "game_session_owner_attempt_id");
      UUID ownerMutation = requiredUuid(row, "game_session_owner_mutation_id");
      long ownerFence = positive(requiredLong(row, "game_session_owner_fence"));
      OffsetDateTime expiry = requiredTimestamp(row, "original_lease_expires_at");
      byte[] responseBytes = requiredBytes(row, "original_response_bytes");
      String responseDigest = requiredString(row, "original_response_digest");
      if (!parent.participationId().equals(participationId)
          || !parent.targetNamespace().equals(namespace)
          || !parent.gameSessionOwnerAttemptId().equals(ownerAttempt)
          || parent.gameSessionOwnerFence() != ownerFence
          || !SHA256.matcher(responseDigest).matches()
          || !responseDigest.equals(digestPrefixed(responseBytes))) {
        throw unavailable();
      }

      Result result = decodeResponseFromStoredBytes(responseBytes, parent);
      if (!ownerMutation.equals(result.request().expectedOwnerMutationId())
          || !ownerAttempt.equals(result.request().expectedOwnerAttemptId())
          || ownerFence != result.request().expectedOwnerFence()
          || !namespace.equals(result.request().targetNamespace())
          || !expiry.toInstant().equals(result.originalLeaseExpiresAt())) {
        throw unavailable();
      }
      return new StoredEvidence(
          participationId,
          ownerMutation,
          expiry.toInstant(),
          responseBytes,
          responseDigest,
          result);
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalStateException state
          && ("Exact original StartSession attempt evidence unavailable".equals(state.getMessage())
              || "Original StartSession attempt evidence conflicts with its immutable binding"
                  .equals(state.getMessage()))) {
        throw state;
      }
      throw unavailable();
    }
  }

  private static Result decodeResponse(
      Request expectedRequest, byte[] responseBytes, StoredParticipation parent) {
    return validateResponse(expectedRequest, responseBytes, parent);
  }

  static Result validateResponse(
      Request expectedRequest, byte[] responseBytes, StoredParticipation parent) {
    Objects.requireNonNull(expectedRequest, "exact current-attempt request is required");
    Objects.requireNonNull(responseBytes, "complete current-attempt response bytes are required");
    if (responseBytes.length == 0
        || responseBytes.length
            > OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.MAX_RESPONSE_BYTES) {
      throw conflict();
    }
    try {
      ReadOriginalStartSessionCurrentAttemptResponse response =
          ReadOriginalStartSessionCurrentAttemptResponse.parseFrom(responseBytes);
      Result result =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
              expectedRequest, response);
      if (!Arrays.equals(
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result).toByteArray(),
          responseBytes)) {
        throw conflict();
      }
      requireObservationBinding(parent, result);
      return result;
    } catch (IOException | RuntimeException malformed) {
      if (malformed instanceof IllegalStateException state
          && "Original StartSession attempt evidence conflicts with its immutable binding"
              .equals(state.getMessage())) {
        throw state;
      }
      throw conflict();
    }
  }

  private static Result decodeResponseFromStoredBytes(
      byte[] responseBytes, StoredParticipation parent) {
    if (responseBytes.length == 0
        || responseBytes.length
            > OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.MAX_RESPONSE_BYTES) {
      throw unavailable();
    }
    try {
      ReadOriginalStartSessionCurrentAttemptResponse response =
          ReadOriginalStartSessionCurrentAttemptResponse.parseFrom(responseBytes);
      if (!response.hasRequest()) throw unavailable();
      Request echoedRequest =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(response.getRequest());
      Result result =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(echoedRequest, response);
      if (!Arrays.equals(
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result).toByteArray(),
          responseBytes)) {
        throw unavailable();
      }
      requireObservationBinding(parent, result);
      return result;
    } catch (IOException | RuntimeException malformed) {
      if (malformed instanceof IllegalStateException state
          && "Exact original StartSession attempt evidence unavailable"
              .equals(state.getMessage())) {
        throw state;
      }
      throw unavailable();
    }
  }

  private static void requireObservationBinding(StoredParticipation parent, Result result) {
    Request request = result.request();
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(parent.originalPostAuthorizationTuple());
    if (!"StartSession".equals(tuple.preAuthorizationTuple().actionFamily())
        || !"game-session-service".equals(tuple.preAuthorizationTuple().targetOwner())
        || !parent.controlPlaneRequestId().equals(tuple.controlPlaneRequestId())
        || !parent.targetNamespace().equals(request.targetNamespace())
        || !parent
            .targetNamespace()
            .equals(tuple.preAuthorizationTuple().action().scope().targetNamespace())
        || !Arrays.equals(
            parent.originalPostAuthorizationTuple(), request.canonicalPostAuthorizationTuple())
        || !parent.gameSessionOwnerAttemptId().equals(request.expectedOwnerAttemptId())
        || parent.gameSessionOwnerFence() != request.expectedOwnerFence()
        || !accountProjectionMatches(tuple, result.accountRedemptionProjection())
        || result.originalLeaseExpiresAt().getNano() % 1_000 != 0) {
      throw conflict();
    }
  }

  static void requireSameSemanticObservation(Result expected, Result actual) {
    requireSameSemanticRequest(expected.request(), actual.request());
    if (!expected.phaseState().equals(actual.phaseState())
        || !expected.originalLeaseExpiresAt().equals(actual.originalLeaseExpiresAt())
        || !Arrays.equals(
            expected.accountRedemptionProjection(), actual.accountRedemptionProjection())) {
      throw conflict();
    }
  }

  static void requireSameSemanticRequest(Request expected, Request actual) {
    if (!expected.targetNamespace().equals(actual.targetNamespace())
        || !Arrays.equals(
            expected.canonicalPostAuthorizationTuple(), actual.canonicalPostAuthorizationTuple())
        || !expected.expectedOwnerAttemptId().equals(actual.expectedOwnerAttemptId())
        || !expected.expectedOwnerMutationId().equals(actual.expectedOwnerMutationId())
        || expected.expectedOwnerFence() != actual.expectedOwnerFence()) {
      throw conflict();
    }
  }

  private static boolean accountProjectionMatches(
      StartSessionPostAuthorizationExecutionTuple tuple, byte[] projectionBytes) {
    try {
      StartSessionAuthorityEvidenceBundle bundle =
          StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
      long issuanceFence = Long.parseLong(tuple.issuanceFence());
      if (issuanceFence <= 0L) return false;
      byte[] expected =
          Rfc8785CanonicalJson.canonicalizeUtf8(
              JSON.writeValueAsString(
                  Map.of(
                      "projectionSchemaId",
                      "accountStartSessionRedemptionProjection",
                      "projectionSchemaVersion",
                      "1",
                      "authorizationReferenceFingerprint",
                      tuple.authorizationReferenceFingerprint(),
                      "authorityEvidenceBundle",
                      bundle.jsonValue(),
                      "issuanceOperationId",
                      bundle.issuanceOperationId().toString(),
                      "issuanceFence",
                      issuanceFence)));
      return Arrays.equals(expected, projectionBytes);
    } catch (IOException | RuntimeException malformed) {
      return false;
    }
  }

  private void requireLeaseCurrent(Instant expiry) {
    Record row = dsl.fetchOne("SELECT clock_timestamp()");
    if (row == null) throw unavailable();
    OffsetDateTime now = Objects.requireNonNull(row.get(0, OffsetDateTime.class));
    if (!expiry.isAfter(now.toInstant())) throw unavailable();
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
    Set<String> actual =
        Arrays.stream(row.fields())
            .map(field -> field.getName())
            .collect(java.util.stream.Collectors.toSet());
    if (!actual.equals(expected)) throw unavailable();
  }

  private static UUID requiredUuid(Record row, String column) {
    UUID value = Objects.requireNonNull(row.get(column, UUID.class), column + " is required");
    if (NIL_UUID.equals(value)) throw unavailable();
    return value;
  }

  private static String requiredString(Record row, String column) {
    String value = Objects.requireNonNull(row.get(column, String.class), column + " is required");
    if (value.isBlank()) throw unavailable();
    return value;
  }

  private static byte[] requiredBytes(Record row, String column) {
    byte[] value = Objects.requireNonNull(row.get(column, byte[].class), column + " is required");
    return value.clone();
  }

  private static long requiredLong(Record row, String column) {
    return Objects.requireNonNull(row.get(column, Long.class), column + " is required");
  }

  private static OffsetDateTime requiredTimestamp(Record row, String column) {
    return Objects.requireNonNull(row.get(column, OffsetDateTime.class), column + " is required");
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
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalStateException conflict() {
    return new IllegalStateException(
        "Original StartSession attempt evidence conflicts with its immutable binding");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Exact original StartSession attempt evidence unavailable");
  }

  private record SourceRow(String key, byte[] canonicalBytes) {
    private SourceRow {
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }

  public record StoredEvidence(
      UUID participationId,
      UUID gameSessionOwnerMutationId,
      Instant originalLeaseExpiresAt,
      byte[] originalResponseBytes,
      String originalResponseDigest,
      Result result) {
    public StoredEvidence {
      Objects.requireNonNull(participationId);
      Objects.requireNonNull(gameSessionOwnerMutationId);
      Objects.requireNonNull(originalLeaseExpiresAt);
      originalResponseBytes = Objects.requireNonNull(originalResponseBytes).clone();
      Objects.requireNonNull(originalResponseDigest);
      Objects.requireNonNull(result);
      if (!SHA256.matcher(originalResponseDigest).matches()
          || !originalResponseDigest.equals(digestPrefixed(originalResponseBytes))) {
        throw new IllegalArgumentException(
            "Stored original response digest differs from its bytes");
      }
    }

    @Override
    public byte[] originalResponseBytes() {
      return originalResponseBytes.clone();
    }

    @Override
    public String toString() {
      return "StoredEvidence[participationId="
          + participationId
          + ", ownerMutationId="
          + gameSessionOwnerMutationId
          + ", responseBytes=<redacted>, projection=<redacted>]";
    }
  }
}
