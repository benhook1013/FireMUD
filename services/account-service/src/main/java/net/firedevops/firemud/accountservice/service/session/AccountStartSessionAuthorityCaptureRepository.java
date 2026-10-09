package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.Snapshot;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account-local immutable capture of the complete current ControlUI source for StartSession.
 *
 * <p>This repository has no transport or crypto responsibilities. Call it only inside the callback
 * of AccountControlUiActorService.withCurrent or withCurrentCommitted, before creating or
 * fingerprinting the operator reference or encrypting its response. A capture is non-authorizing:
 * the actor must be current again when the issuer locks it for finalization or redemption.
 */
public final class AccountStartSessionAuthorityCaptureRepository {
  private static final String TABLE = "account_start_session_authority_captures";
  private static final String SOURCE_VERSION_ALLOCATOR =
      "account_start_session_capture_source_version_allocator";
  private static final String SOURCE_FENCE_ALLOCATOR =
      "account_start_session_capture_source_fence_allocator";
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern LOGGING_WORKLOAD =
      Pattern.compile(
          "^spiffe://firemud/ns/([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)/sa/logging-admin-service$");

  private final DSLContext dsl;

  public AccountStartSessionAuthorityCaptureRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Persists the current source snapshot before private issuance work, or returns the exact
   * original capture when every source and request binding is unchanged.
   */
  public AccountStartSessionAuthorityCapture prepareOrReadExact(
      AccountControlUiActorService.Current current,
      StartSessionPreAuthorizationReservationTuple tuple,
      String loggingWorkloadUri,
      UUID reservationOwnerId,
      long reservationClaimFence) {
    requireOwnerTransaction();
    Objects.requireNonNull(current, "current committed ControlUI actor is required");
    RequestSnapshot candidate =
        requestSnapshot(
            current, tuple, loggingWorkloadUri, reservationOwnerId, reservationClaimFence);

    Record prior = lockByRequestId(candidate.requestId());
    if (prior != null) {
      AccountStartSessionAuthorityCapture stored = decode(prior);
      requireSameRequestAndSnapshot(candidate, stored, prior);
      return stored;
    }

    Allocation allocation = allocate(candidate.requestId());
    AccountStartSessionAuthorityCapture proposed =
        AccountStartSessionAuthorityCapture.create(
            candidate.requestId(),
            allocation.sourceVersion(),
            allocation.sourceFence(),
            allocation.linearization(),
            allocation.capturedAt(),
            candidate.snapshotBytes());
    int inserted = insert(candidate, proposed);
    Record row = lockByRequestId(candidate.requestId());
    if (row == null) throw unavailable();
    AccountStartSessionAuthorityCapture stored = decode(row);
    requireSameRequestAndSnapshot(candidate, stored, row);
    if (inserted == 1 && !proposed.sameStoredValue(stored)) {
      throw unavailable();
    }
    return stored;
  }

  /** Locks the immutable original capture and requires the exact current source and tuple again. */
  public AccountStartSessionAuthorityCapture lockExactCurrent(
      AccountStartSessionAuthorityCapture expected,
      AccountControlUiActorService.Current current,
      StartSessionPreAuthorizationReservationTuple tuple,
      String loggingWorkloadUri,
      UUID reservationOwnerId,
      long reservationClaimFence) {
    requireOwnerTransaction();
    Objects.requireNonNull(expected, "original Account source capture is required");
    Objects.requireNonNull(current, "current committed ControlUI actor is required");
    RequestSnapshot observed =
        requestSnapshot(
            current, tuple, loggingWorkloadUri, reservationOwnerId, reservationClaimFence);
    Record row = lockByRequestId(expected.controlPlaneRequestId());
    if (row == null) throw unavailable();
    AccountStartSessionAuthorityCapture stored = decode(row);
    requireSameRequestAndSnapshot(observed, stored, row);
    if (!expected.sameStoredValue(stored)) throw unavailable();
    return stored;
  }

  private int insert(RequestSnapshot request, AccountStartSessionAuthorityCapture capture) {
    byte[] tupleBytes = request.tupleBytes();
    byte[] snapshotBytes = capture.snapshotBytes();
    byte[] captureBytes = capture.canonicalBytes();
    return dsl.execute(
        "INSERT INTO "
            + TABLE
            + " (control_plane_request_id, account_uuid, tenant_uuid, target_owner, "
            + "logging_workload_uri, reservation_owner_id, reservation_claim_fence, "
            + "pre_authorization_tuple, mutation_digest, control_ui_operation_id, "
            + "control_ui_token_jti, control_ui_token_hash, control_ui_signer_receipt_sha256, "
            + "issuance_fence, issuance_fence_source_version, captured_at, snapshot_sha256, "
            + "canonical_snapshot_bytes, source_version, source_fence, linearization, "
            + "canonical_sha256, canonical_capture_bytes) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (control_plane_request_id) DO NOTHING",
        request.requestId(),
        request.accountId(),
        request.tenantId(),
        request.targetOwner(),
        request.loggingWorkloadUri(),
        request.reservationOwnerId(),
        request.reservationClaimFence(),
        tupleBytes,
        request.mutationDigest(),
        request.controlUiOperationId(),
        request.controlUiTokenJti(),
        request.controlUiTokenHash(),
        request.controlUiSignerReceiptSha256(),
        request.issuanceFence(),
        request.issuanceFenceSourceVersion(),
        capture.capturedAt(),
        capture.snapshotSha256(),
        snapshotBytes,
        capture.sourceVersion(),
        capture.sourceFence(),
        capture.linearization(),
        capture.canonicalSha256(),
        captureBytes);
  }

  private Allocation allocate(String requestId) {
    Record sourceVersion =
        dsl.fetchOne(
            "UPDATE "
                + SOURCE_VERSION_ALLOCATOR
                + " SET last_source_version = last_source_version + 1, "
                + "last_control_plane_request_id = ?, "
                + "last_transaction_id = pg_current_xact_id()::text "
                + "WHERE allocator_id = TRUE AND last_source_version < 9223372036854775807 "
                + "RETURNING last_source_version, last_control_plane_request_id, last_transaction_id",
            requestId);
    Record sourceFence =
        dsl.fetchOne(
            "UPDATE "
                + SOURCE_FENCE_ALLOCATOR
                + " SET last_source_fence = last_source_fence + 1, "
                + "last_control_plane_request_id = ?, "
                + "last_transaction_id = pg_current_xact_id()::text "
                + "WHERE allocator_id = TRUE AND last_source_fence < 9223372036854775807 "
                + "RETURNING last_source_fence, last_control_plane_request_id, last_transaction_id",
            requestId);
    Record observedAt =
        dsl.fetchOne(
            "SELECT to_char(date_trunc('milliseconds', clock_timestamp()) AT TIME ZONE 'UTC', "
                + "'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') AS captured_at");
    if (sourceVersion == null || sourceFence == null || observedAt == null) throw unavailable();
    String versionTransaction = sourceVersion.get("last_transaction_id", String.class);
    String fenceTransaction = sourceFence.get("last_transaction_id", String.class);
    if (!requestId.equals(sourceVersion.get("last_control_plane_request_id", String.class))
        || !requestId.equals(sourceFence.get("last_control_plane_request_id", String.class))
        || versionTransaction == null
        || !versionTransaction.equals(fenceTransaction)) {
      throw unavailable();
    }
    Long version = sourceVersion.get("last_source_version", Long.class);
    Long fence = sourceFence.get("last_source_fence", Long.class);
    if (version == null || version <= 0L || fence == null || fence <= 0L) throw unavailable();
    String capturedAt = observedAt.get("captured_at", String.class);
    if (capturedAt == null) throw unavailable();
    return new Allocation(version, fence, versionTransaction, capturedAt);
  }

  private Record lockByRequestId(String requestId) {
    return dsl.fetchOne(
        "SELECT control_plane_request_id, account_uuid, tenant_uuid, target_owner, "
            + "logging_workload_uri, reservation_owner_id, reservation_claim_fence, "
            + "pre_authorization_tuple, mutation_digest, control_ui_operation_id, "
            + "control_ui_token_jti, control_ui_token_hash, control_ui_signer_receipt_sha256, "
            + "issuance_fence, issuance_fence_source_version, "
            + "to_char(captured_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') AS captured_at, snapshot_sha256, "
            + "canonical_snapshot_bytes, source_version, source_fence, linearization, "
            + "canonical_sha256, canonical_capture_bytes FROM "
            + TABLE
            + " WHERE control_plane_request_id = ? FOR UPDATE",
        requestId);
  }

  private AccountStartSessionAuthorityCapture decode(Record row) {
    try {
      String requestId = row.get("control_plane_request_id", String.class);
      Long version = row.get("source_version", Long.class);
      Long fence = row.get("source_fence", Long.class);
      String linearization = row.get("linearization", String.class);
      byte[] snapshot = row.get("canonical_snapshot_bytes", byte[].class);
      String snapshotDigest = row.get("snapshot_sha256", String.class);
      byte[] captureBytes = row.get("canonical_capture_bytes", byte[].class);
      String captureDigest = row.get("canonical_sha256", String.class);
      var capture =
          AccountStartSessionAuthorityCapture.fromStorage(
              requestId,
              version,
              fence,
              linearization,
              row.get("captured_at", String.class),
              snapshot,
              snapshotDigest,
              captureBytes,
              captureDigest);
      requireStoredColumnsMatchPayload(row, capture);
      return capture;
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static void requireStoredColumnsMatchPayload(
      Record row, AccountStartSessionAuthorityCapture capture) {
    Map<?, ?> snapshot = parseObject(capture.snapshotBytes());
    Map<?, ?> reference = parseObject(capture.canonicalBytes());
    Map<?, ?> bundleReference = (Map<?, ?>) reference.get("bundleReference");
    if (!capture.controlPlaneRequestId().equals(row.get("control_plane_request_id", String.class))
        || !Long.toString(capture.sourceVersion()).equals(bundleReference.get("sourceVersion"))
        || !Long.toString(capture.sourceFence()).equals(bundleReference.get("sourceFence"))
        || !capture.linearization().equals(bundleReference.get("linearization"))
        || !capture.capturedAt().equals(reference.get("capturedAt"))
        || !capture.capturedAt().equals(row.get("captured_at", String.class))
        || !capture.snapshotSha256().equals(row.get("snapshot_sha256", String.class))
        || !Objects.equals(snapshot.get("accountId"), uuid(row, "account_uuid"))
        || !Objects.equals(snapshot.get("tenantId"), uuid(row, "tenant_uuid"))
        || !Objects.equals(snapshot.get("targetOwner"), row.get("target_owner", String.class))
        || !Objects.equals(
            snapshot.get("loggingWorkloadUri"), row.get("logging_workload_uri", String.class))
        || !Objects.equals(snapshot.get("reservationOwnerId"), uuid(row, "reservation_owner_id"))
        || !Objects.equals(
            snapshot.get("reservationClaimFence"),
            Long.toString(number(row, "reservation_claim_fence")))
        || !Objects.equals(snapshot.get("mutationDigest"), row.get("mutation_digest", String.class))
        || !Objects.equals(
            snapshot.get("controlUiOperationId"), uuid(row, "control_ui_operation_id"))
        || !Objects.equals(snapshot.get("controlUiTokenJti"), uuid(row, "control_ui_token_jti"))
        || !Objects.equals(
            snapshot.get("controlUiTokenHash"), row.get("control_ui_token_hash", String.class))
        || !Objects.equals(
            snapshot.get("controlUiSignerReceiptSha256"),
            row.get("control_ui_signer_receipt_sha256", String.class))
        || !Objects.equals(
            snapshot.get("issuanceFence"), Long.toString(number(row, "issuance_fence")))
        || !Objects.equals(
            snapshot.get("issuanceFenceSourceVersion"),
            Long.toString(number(row, "issuance_fence_source_version")))) {
      throw unavailable();
    }
  }

  private static RequestSnapshot requestSnapshot(
      AccountControlUiActorService.Current current,
      StartSessionPreAuthorizationReservationTuple tuple,
      String loggingWorkloadUri,
      UUID reservationOwnerId,
      long reservationClaimFence) {
    Objects.requireNonNull(tuple, "exact typed StartSession tuple is required");
    Objects.requireNonNull(current.stored(), "original committed ControlUI issuance is required");
    Snapshot source =
        Objects.requireNonNull(current.source(), "current Account snapshot is required");
    var stored = current.stored();
    byte[] tupleBytes = tuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
    if (!tuple.actor().accountId().equals(stored.accountId)
        || !tuple.actor().accountId().equals(source.actor())
        || !tuple.action().scope().tenantId().equals(stored.tenantId)
        || !tuple.action().scope().tenantId().equals(source.tenant())
        || !"game-session-service".equals(tuple.targetOwner())
        || !"COMMITTED".equals(stored.status)
        || !canonicalTuple(tupleBytes, tuple)
        || !canonicalLoggingWorkload(loggingWorkloadUri, tuple.action().scope().targetNamespace())
        || reservationOwnerId == null
        || reservationOwnerId.equals(new UUID(0L, 0L))
        || reservationClaimFence <= 0L
        || stored.operationId == null
        || stored.jti == null
        || stored.tokenHash == null
        || !SHA256.matcher(stored.tokenHash).matches()
        || stored.signerReceipt == null
        || stored.signerReceipt.length == 0
        || source.issuanceFence() <= 0L
        || source.issuanceFenceSourceVersion() <= 0L
        || source.evidence() == null
        || source.evidence().length == 0
        || source.sources() == null
        || source.sources().isEmpty()
        || source.outboxCheckpoints() == null) {
      throw unavailable();
    }
    byte[] signerReceipt = stored.signerReceipt.clone();
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schema", "account-start-session-authority-snapshot/v1");
    snapshot.put("controlPlaneRequestId", tuple.controlPlaneRequestId());
    snapshot.put("preAuthorizationTuple", Base64.getEncoder().encodeToString(tupleBytes));
    snapshot.put("mutationDigest", tuple.mutationDigest());
    snapshot.put("accountId", stored.accountId.toString());
    snapshot.put("tenantId", stored.tenantId.toString());
    snapshot.put("targetOwner", tuple.targetOwner());
    snapshot.put("loggingWorkloadUri", loggingWorkloadUri);
    snapshot.put("reservationOwnerId", reservationOwnerId.toString());
    snapshot.put("reservationClaimFence", Long.toString(reservationClaimFence));
    snapshot.put("controlUiOperationId", stored.operationId.toString());
    snapshot.put("controlUiTokenJti", stored.jti.toString());
    snapshot.put("controlUiTokenHash", stored.tokenHash);
    snapshot.put("controlUiSignerReceipt", Base64.getEncoder().encodeToString(signerReceipt));
    snapshot.put("controlUiSignerReceiptSha256", sha256(signerReceipt));
    snapshot.put("sourceVectorEvidence", Base64.getEncoder().encodeToString(source.evidence()));
    List<String> sourceVector = new ArrayList<>();
    for (SourceEvidence evidence : source.sources()) {
      if (evidence == null || evidence.canonicalBytes().length == 0) throw unavailable();
      sourceVector.add(Base64.getEncoder().encodeToString(evidence.canonicalBytes()));
    }
    snapshot.put("sourceVector", List.copyOf(sourceVector));
    snapshot.put("outboxCheckpoints", source.outboxCheckpoints());
    snapshot.put("authorityTuple", source.authorityTuple());
    snapshot.put("membershipVersion", source.membershipVersion());
    snapshot.put("accountIdentitySource", source.accountIdentitySource());
    snapshot.put("issuanceFence", Long.toString(source.issuanceFence()));
    snapshot.put("issuanceFenceSourceVersion", Long.toString(source.issuanceFenceSourceVersion()));
    byte[] snapshotBytes = AccountControlUiAuthority.canonical(Map.copyOf(snapshot));
    if (snapshotBytes.length > AccountStartSessionAuthorityCapture.MAX_SNAPSHOT_BYTES) {
      throw unavailable();
    }
    return new RequestSnapshot(
        tuple.controlPlaneRequestId(),
        tuple.actor().accountId(),
        tuple.action().scope().tenantId(),
        tuple.targetOwner(),
        loggingWorkloadUri,
        reservationOwnerId,
        reservationClaimFence,
        tupleBytes,
        tuple.mutationDigest(),
        stored.operationId,
        stored.jti,
        stored.tokenHash,
        sha256(signerReceipt),
        source.issuanceFence(),
        source.issuanceFenceSourceVersion(),
        snapshotBytes);
  }

  private static void requireSameRequestAndSnapshot(
      RequestSnapshot request, AccountStartSessionAuthorityCapture capture, Record storedRow) {
    requireStoredColumnsMatchPayload(storedRow, capture);
    Map<?, ?> snapshot = parseObject(capture.snapshotBytes());
    if (!request.requestId().equals(capture.controlPlaneRequestId())
        || !MessageDigest.isEqual(request.snapshotBytes(), capture.snapshotBytes())
        || !Objects.equals(
            snapshot.get("preAuthorizationTuple"),
            Base64.getEncoder().encodeToString(request.tupleBytes()))
        || !Objects.equals(
            snapshot.get("controlUiOperationId"), request.controlUiOperationId().toString())
        || !Objects.equals(
            snapshot.get("controlUiTokenJti"), request.controlUiTokenJti().toString())
        || !Objects.equals(snapshot.get("controlUiTokenHash"), request.controlUiTokenHash())
        || !Objects.equals(
            snapshot.get("controlUiSignerReceiptSha256"), request.controlUiSignerReceiptSha256())
        || !Objects.equals(
            snapshot.get("reservationOwnerId"), request.reservationOwnerId().toString())
        || !Objects.equals(
            snapshot.get("reservationClaimFence"),
            Long.toString(request.reservationClaimFence()))) {
      throw unavailable();
    }
  }

  private static boolean canonicalTuple(
      byte[] tupleBytes, StartSessionPreAuthorizationReservationTuple tuple) {
    try {
      String json = new String(tupleBytes, StandardCharsets.UTF_8);
      var parsed = StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(json);
      return MessageDigest.isEqual(
              tupleBytes, parsed.canonicalJson().getBytes(StandardCharsets.UTF_8))
          && parsed.controlPlaneRequestId().equals(tuple.controlPlaneRequestId())
          && parsed.mutationDigest().equals(tuple.mutationDigest());
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static boolean canonicalLoggingWorkload(String uri, String targetNamespace) {
    if (uri == null) return false;
    var match = LOGGING_WORKLOAD.matcher(uri);
    if (!match.matches() || !match.group(1).equals(targetNamespace)) return false;
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && uri.equals(peer.uri())
        && "logging-admin-service".equals(peer.service())
        && targetNamespace.equals(peer.namespace());
  }

  private static Map<?, ?> parseObject(byte[] bytes) {
    try {
      Object value =
          tools.jackson.databind.json.JsonMapper.builder()
              .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .build()
              .readValue(new String(bytes, StandardCharsets.UTF_8), Object.class);
      if (value instanceof Map<?, ?> map) return map;
      throw unavailable();
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static UUID uuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    return value == null ? null : value;
  }

  private static long number(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null) throw unavailable();
    return value;
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("A writable Account owner transaction is required");
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Account StartSession source capture is unavailable");
  }

  private record Allocation(
      long sourceVersion, long sourceFence, String linearization, String capturedAt) {}

  private record RequestSnapshot(
      String requestId,
      UUID accountId,
      UUID tenantId,
      String targetOwner,
      String loggingWorkloadUri,
      UUID reservationOwnerId,
      long reservationClaimFence,
      byte[] tupleBytes,
      String mutationDigest,
      UUID controlUiOperationId,
      UUID controlUiTokenJti,
      String controlUiTokenHash,
      String controlUiSignerReceiptSha256,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      byte[] snapshotBytes) {
    private RequestSnapshot {
      tupleBytes = tupleBytes.clone();
      snapshotBytes = snapshotBytes.clone();
    }

    @Override
    public byte[] tupleBytes() {
      return tupleBytes.clone();
    }

    @Override
    public byte[] snapshotBytes() {
      return snapshotBytes.clone();
    }
  }
}
