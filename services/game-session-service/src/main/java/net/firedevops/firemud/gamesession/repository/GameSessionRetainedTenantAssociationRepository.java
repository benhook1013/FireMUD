package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient.LegacyGameSessionTenantAssociationReceipt;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unwired immutable local receipt for an approved association to retained Game Session rows.
 *
 * <p>The Game Design receipt is obtained by the service before the owner transaction begins. This
 * repository only commits it after the local retained-row snapshot has been recaptured under its
 * source fence.
 */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Owner collaborators and namespace are validated before use; construction performs no"
            + " database or network I/O and the caller explicitly owns local activation.")
public class GameSessionRetainedTenantAssociationRepository {
  private static final int SCHEMA_VERSION = 2;
  private static final int SIGNATURE_BYTES = 64;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String REQUEST_DOMAIN =
      "game-session/retained-tenant-association-request/v1";
  private static final String RECEIPT_DOMAIN =
      "game-session/retained-tenant-association-receipt/v1";

  private static final Table<?> ASSOCIATION =
      DSL.table(DSL.name("game_session_retained_tenant_association"));
  private static final Table<?> ASSOCIATION_PAYLOAD =
      DSL.table(DSL.name("game_session_retained_tenant_association_payload"));
  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<UUID> ASSOCIATION_OPERATION_ID =
      DSL.field(DSL.name("game_session_retained_tenant_association", "operation_id"), UUID.class);
  private static final Field<UUID> PAYLOAD_OPERATION_ID = payloadField("operation_id", UUID.class);
  private static final Field<Integer> APPROVAL_SCHEMA_VERSION =
      payloadField("approval_schema_version", Integer.class);
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> ASSOCIATION_REQUEST_ID =
      DSL.field(DSL.name("association_request_id"), UUID.class);
  private static final Field<String> REQUEST_DIGEST = payloadField("request_digest", String.class);
  private static final Field<UUID> APPROVAL_OPERATION_ID =
      payloadField("approval_operation_id", UUID.class);
  private static final Field<String> SIGNER_KEY_ID = payloadField("signer_key_id", String.class);
  private static final Field<String> APPROVED_BY = payloadField("approved_by", String.class);
  private static final Field<String> APPROVAL_REFERENCE =
      payloadField("approval_reference", String.class);
  private static final Field<String> SIGNED_AT = payloadField("signed_at", String.class);
  private static final Field<Long> LEGACY_GAME_SESSION_TENANT_ID =
      DSL.field(DSL.name("legacy_game_session_tenant_id"), Long.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> PROVENANCE_KIND =
      DSL.field(DSL.name("provenance_kind"), String.class);
  private static final Field<OffsetDateTime> CAPTURED_AT =
      DSL.field(DSL.name("captured_at"), OffsetDateTime.class);
  private static final Field<String> TERMINAL_OUTCOME =
      DSL.field(DSL.name("terminal_outcome"), String.class);
  private static final Field<String> GAME_SESSION_EVIDENCE_DIGEST =
      payloadField("game_session_evidence_digest", String.class);
  private static final Field<String> GAME_SESSION_PROJECTION_DIGEST =
      payloadField("game_session_projection_digest", String.class);
  private static final Field<String> APPROVAL_MANIFEST_DIGEST =
      payloadField("approval_manifest_digest", String.class);
  private static final Field<String> APPROVAL_SIGNATURE =
      payloadField("approval_signature", String.class);
  private static final Field<String> SNAPSHOT_CANONICAL_JSON =
      payloadField("snapshot_canonical_json", String.class);
  private static final Field<String> SNAPSHOT_EVIDENCE_DIGEST =
      payloadField("snapshot_evidence_digest", String.class);
  private static final Field<String> RECEIPT_DIGEST = payloadField("receipt_digest", String.class);

  private final DSLContext dsl;
  private final GameSessionRetainedTenantSnapshotRepository snapshotRepository;
  private final String workloadNamespace;

  public GameSessionRetainedTenantAssociationRepository(
      DSLContext dsl,
      GameSessionRetainedTenantSnapshotRepository snapshotRepository,
      String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    this.snapshotRepository =
        Objects.requireNonNull(snapshotRepository, "snapshotRepository must not be null");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * Captures and commits one exact retained-tenant association under the caller's owner
   * transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AssociationReceipt register(
      UUID associationRequestId, LegacyGameSessionTenantAssociationReceipt sourceReceipt) {
    requireWritableReadCommittedOwnerTransaction();
    requireNonNil(associationRequestId, "associationRequestId");
    ValidatedApproval approval = validateApproval(sourceReceipt);
    GameSessionTenantAssociationEvidence evidence = approval.evidence();
    if (!workloadNamespace.equals(evidence.targetNamespace())) {
      throw new InvalidAssociationEvidenceException(
          "Game Design approval targets a different Game Session namespace");
    }

    String requestDigest =
        requestDigest(
            workloadNamespace,
            associationRequestId,
            approval.manifestDigest(),
            approval.signature());
    Record existing = findByRequest(workloadNamespace, associationRequestId);
    if (existing != null) {
      return requireExactRetry(existing, associationRequestId, requestDigest, approval);
    }

    GameSessionRetainedTenantSnapshot snapshot =
        snapshotRepository.capture(workloadNamespace, evidence.legacyGameSessionTenantId());
    requireCaptureStillUsable(evidence.sourceCapturedAt());
    GameSessionRetainedTenantSnapshot sourceBoundSnapshot =
        snapshot.withCapturedAt(evidence.sourceCapturedAt());
    requireSnapshotMatchesApproval(snapshot, sourceBoundSnapshot, evidence);

    UUID localOperationId = UUID.randomUUID();
    String receiptDigest =
        receiptDigest(
            localOperationId,
            requestDigest,
            approval.manifestDigest(),
            sourceBoundSnapshot.evidenceDigest());
    int inserted =
        dsl.insertInto(ASSOCIATION)
            .set(OPERATION_ID, localOperationId)
            .set(TARGET_NAMESPACE, workloadNamespace)
            .set(ASSOCIATION_REQUEST_ID, associationRequestId)
            .set(LEGACY_GAME_SESSION_TENANT_ID, evidence.legacyGameSessionTenantIdValue())
            .set(CANONICAL_TENANT_ID, evidence.canonicalTenantId())
            .set(SOURCE_GAME_ROW_ID, evidence.sourceGameRowIdValue())
            .set(SOURCE_GAME_TENANT_KEY, evidence.sourceGameTenantKey())
            .set(PROVENANCE_KIND, evidence.provenanceKind())
            .set(
                CAPTURED_AT,
                OffsetDateTime.ofInstant(
                    Instant.parse(evidence.sourceCapturedAt()), ZoneOffset.UTC))
            .set(TERMINAL_OUTCOME, "ASSOCIATED")
            .onConflictDoNothing()
            .execute();

    Record claimed = findByOperationId(localOperationId);
    if (claimed != null) {
      dsl.execute(
          "INSERT INTO game_session_retained_tenant_association_payload ("
              + "operation_id, request_digest, approval_operation_id, approval_schema_version, "
              + "signer_key_id, approved_by, approval_reference, signed_at, "
              + "game_session_projection_digest, game_session_evidence_digest, "
              + "approval_manifest_digest, approval_signature, snapshot_canonical_json, "
              + "snapshot_evidence_digest, receipt_digest) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          localOperationId,
          requestDigest,
          evidence.operationId(),
          evidence.schemaVersion(),
          evidence.signerKeyId(),
          evidence.approvedBy(),
          evidence.approvalReference(),
          evidence.signedAt(),
          evidence.gameSessionProjectionDigest(),
          evidence.gameSessionEvidenceDigest(),
          approval.manifestDigest(),
          approval.signature(),
          sourceBoundSnapshot.canonicalJson(),
          sourceBoundSnapshot.evidenceDigest(),
          receiptDigest);
    }

    Record byLocalOperation = findByOperationId(localOperationId);
    if (byLocalOperation != null) {
      AssociationReceipt stored = toValidatedReceipt(byLocalOperation);
      requireExactInsertedReceipt(
          stored, associationRequestId, requestDigest, approval, sourceBoundSnapshot);
      return stored;
    }

    Record racedRequest = findByRequest(workloadNamespace, associationRequestId);
    if (racedRequest != null) {
      return requireExactRetry(racedRequest, associationRequestId, requestDigest, approval);
    }
    if (inserted == 1) {
      throw new InvalidAssociationEvidenceException(
          "Committed retained-tenant association readback is missing");
    }
    throw new AssociationConflictException(
        "Canonical tenant or retained Game Session key is already associated");
  }

  /** Reads one exact committed association without source recapture, repair, or allocation. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<AssociationReceipt> read(
      UUID localOperationId,
      UUID associationRequestId,
      UUID canonicalTenantId,
      long legacyGameSessionTenantId,
      String exactNamespace) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Retained-tenant association read requires a committed-outcome owner read");
    }
    requireNonNil(localOperationId, "localOperationId");
    requireNonNil(associationRequestId, "associationRequestId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requirePositive(legacyGameSessionTenantId, "legacyGameSessionTenantId");
    requireNamespace(exactNamespace);
    if (!workloadNamespace.equals(exactNamespace)) {
      throw new IllegalArgumentException(
          "exactNamespace must match the configured Game Session workload namespace");
    }

    Record row = findByOperationId(localOperationId);
    if (row != null) {
      AssociationReceipt receipt = toValidatedReceipt(row);
      if (!receipt.operationId().equals(localOperationId)
          || !receipt.associationRequestId().equals(associationRequestId)
          || !receipt.approval().canonicalTenantId().equals(canonicalTenantId)
          || receipt.approval().legacyGameSessionTenantIdValue() != legacyGameSessionTenantId
          || !receipt.approval().targetNamespace().equals(exactNamespace)) {
        throw new InvalidAssociationEvidenceException(
            "Retained-tenant association readback contradicts its exact identity request");
      }
      return Optional.of(receipt);
    }

    if (findByRequest(exactNamespace, associationRequestId) != null
        || findByCanonicalTenant(exactNamespace, canonicalTenantId) != null
        || findByLegacyKey(exactNamespace, legacyGameSessionTenantId) != null) {
      throw new InvalidAssociationEvidenceException(
          "Retained-tenant association exists under contradictory local identity");
    }
    return Optional.empty();
  }

  /**
   * Reads only the immutable canonical-to-retained tenant mapping and its source provenance. This
   * proof remains available after the purpose-bound snapshot and approval payload expire.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<RetainedTenantAssociationIdentity> readMinimalAssociation(
      String exactNamespace, UUID canonicalTenantId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Retained tenant identity read requires a committed owner read");
    }
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNamespace(exactNamespace);
    if (!workloadNamespace.equals(exactNamespace)) {
      throw new IllegalArgumentException(
          "exactNamespace must match the configured Game Session workload namespace");
    }

    Record row = findByCanonicalTenant(exactNamespace, canonicalTenantId);
    if (row == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new RetainedTenantAssociationIdentity(
              exactNamespace,
              canonicalTenantId,
              Objects.requireNonNull(row.get(LEGACY_GAME_SESSION_TENANT_ID)),
              Objects.requireNonNull(row.get(SOURCE_GAME_ROW_ID)),
              Objects.requireNonNull(row.get(SOURCE_GAME_TENANT_KEY)),
              Objects.requireNonNull(row.get(PROVENANCE_KIND))));
    } catch (RuntimeException exception) {
      throw new InvalidAssociationEvidenceException(
          "Persisted retained tenant identity is invalid", exception);
    }
  }

  /** Deletes one bounded batch of expired raw snapshots and approval payloads. */
  public int purgeExpiredRawPayloads() {
    return purgeExpiredRawPayloads(dsl);
  }

  /** Executes one bounded payload purge using the caller's owner transaction. */
  public static int purgeExpiredRawPayloads(DSLContext dsl) {
    Objects.requireNonNull(dsl, "dsl must not be null");
    return dsl.execute(
        "WITH expired AS ("
            + " SELECT payload.operation_id"
            + " FROM game_session_retained_tenant_association_payload payload"
            + " JOIN game_session_retained_tenant_association association"
            + "   ON association.operation_id = payload.operation_id"
            + " WHERE association.terminal_outcome = 'ASSOCIATED'"
            + "   AND (association.captured_at IS NULL"
            + "        OR association.captured_at <= clock_timestamp() - INTERVAL '30 days')"
            + "   AND NOT EXISTS (SELECT 1"
            + "       FROM game_session_retained_tenant_association_legal_hold hold"
            + "       WHERE hold.operation_id = payload.operation_id AND hold.released_at IS NULL)"
            + " ORDER BY association.captured_at NULLS FIRST, payload.operation_id"
            + " LIMIT 100 FOR UPDATE OF payload SKIP LOCKED"
            + ") DELETE FROM game_session_retained_tenant_association_payload payload"
            + " USING expired WHERE payload.operation_id = expired.operation_id");
  }

  private AssociationReceipt requireExactRetry(
      Record row,
      UUID associationRequestId,
      String requestDigest,
      ValidatedApproval expectedApproval) {
    AssociationReceipt stored = toValidatedReceipt(row);
    if (!stored.associationRequestId().equals(associationRequestId)
        || !stored.requestDigest().equals(requestDigest)
        || !stored.approval().equals(expectedApproval.evidence())
        || !stored.approvalManifestDigest().equals(expectedApproval.manifestDigest())
        || !stored.approvalSignature().equals(expectedApproval.signature())) {
      throw new AssociationConflictException(
          "Retained-tenant association request was reused with changed signed evidence");
    }
    return stored;
  }

  private void requireExactInsertedReceipt(
      AssociationReceipt actual,
      UUID associationRequestId,
      String requestDigest,
      ValidatedApproval approval,
      GameSessionRetainedTenantSnapshot snapshot) {
    if (!actual.associationRequestId().equals(associationRequestId)
        || !actual.requestDigest().equals(requestDigest)
        || !actual.approval().equals(approval.evidence())
        || !actual.approvalManifestDigest().equals(approval.manifestDigest())
        || !actual.approvalSignature().equals(approval.signature())
        || !actual.snapshot().equals(snapshot)) {
      throw new InvalidAssociationEvidenceException(
          "Committed retained-tenant association differs from its exact registration input");
    }
  }

  private AssociationReceipt toValidatedReceipt(Record row) {
    try {
      UUID localOperationId = Objects.requireNonNull(row.get(ASSOCIATION_OPERATION_ID));
      UUID associationRequestId = Objects.requireNonNull(row.get(ASSOCIATION_REQUEST_ID));
      String namespace = Objects.requireNonNull(row.get(TARGET_NAMESPACE));
      if (!Integer.valueOf(SCHEMA_VERSION).equals(row.get(APPROVAL_SCHEMA_VERSION))) {
        throw new InvalidAssociationEvidenceException(
            "Persisted retained-tenant association schema is unsupported");
      }
      GameSessionTenantAssociationEvidence evidence =
          new GameSessionTenantAssociationEvidence(
              row.get(APPROVAL_SCHEMA_VERSION),
              Objects.requireNonNull(row.get(APPROVAL_OPERATION_ID)),
              namespace,
              Objects.requireNonNull(row.get(SIGNER_KEY_ID)),
              Objects.requireNonNull(row.get(APPROVED_BY)),
              Objects.requireNonNull(row.get(APPROVAL_REFERENCE)),
              Objects.requireNonNull(row.get(SIGNED_AT)),
              captureInstant(row),
              Long.toString(Objects.requireNonNull(row.get(LEGACY_GAME_SESSION_TENANT_ID))),
              Objects.requireNonNull(row.get(CANONICAL_TENANT_ID)),
              Long.toString(Objects.requireNonNull(row.get(SOURCE_GAME_ROW_ID))),
              Objects.requireNonNull(row.get(SOURCE_GAME_TENANT_KEY)),
              Objects.requireNonNull(row.get(PROVENANCE_KIND)),
              Objects.requireNonNull(row.get(GAME_SESSION_PROJECTION_DIGEST)),
              Objects.requireNonNull(row.get(GAME_SESSION_EVIDENCE_DIGEST)));
      requireCaptureStillUsable(evidence.sourceCapturedAt());
      String manifestDigest = Objects.requireNonNull(row.get(APPROVAL_MANIFEST_DIGEST));
      String signature = Objects.requireNonNull(row.get(APPROVAL_SIGNATURE));
      requireSignatureShape(signature);
      if (!evidence.manifestDigest().equals(manifestDigest)) {
        throw new InvalidAssociationEvidenceException(
            "Persisted retained-tenant approval digest does not match its closed manifest");
      }

      GameSessionRetainedTenantSnapshot snapshot =
          new GameSessionRetainedTenantSnapshot(
              namespace,
              evidence.legacyGameSessionTenantId(),
              evidence.sourceCapturedAt(),
              Objects.requireNonNull(row.get(SNAPSHOT_CANONICAL_JSON)),
              Objects.requireNonNull(row.get(SNAPSHOT_EVIDENCE_DIGEST)),
              evidence.gameSessionProjectionDigest());
      requireSnapshotMatchesApproval(snapshot, snapshot, evidence);

      String requestDigest = Objects.requireNonNull(row.get(REQUEST_DIGEST));
      if (!requestDigest.equals(
          requestDigest(namespace, associationRequestId, manifestDigest, signature))) {
        throw new InvalidAssociationEvidenceException(
            "Persisted retained-tenant association request digest is invalid");
      }
      String receiptDigest = Objects.requireNonNull(row.get(RECEIPT_DIGEST));
      if (!receiptDigest.equals(
          receiptDigest(
              localOperationId, requestDigest, manifestDigest, snapshot.evidenceDigest()))) {
        throw new InvalidAssociationEvidenceException(
            "Persisted retained-tenant association receipt digest is invalid");
      }
      requireNonNil(localOperationId, "localOperationId");
      requireNonNil(associationRequestId, "associationRequestId");
      return new AssociationReceipt(
          localOperationId,
          associationRequestId,
          requestDigest,
          evidence,
          manifestDigest,
          signature,
          snapshot,
          receiptDigest);
    } catch (InvalidAssociationEvidenceException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new InvalidAssociationEvidenceException(
          "Persisted retained-tenant association evidence is invalid", exception);
    }
  }

  private ValidatedApproval validateApproval(
      LegacyGameSessionTenantAssociationReceipt sourceReceipt) {
    if (sourceReceipt == null || sourceReceipt.evidence() == null) {
      throw new InvalidAssociationEvidenceException("Game Design approval receipt is missing");
    }
    GameSessionTenantAssociationEvidence source = sourceReceipt.evidence();
    GameSessionTenantAssociationEvidence evidence;
    try {
      evidence =
          new GameSessionTenantAssociationEvidence(
              source.schemaVersion(),
              source.operationId(),
              source.targetNamespace(),
              source.signerKeyId(),
              source.approvedBy(),
              source.approvalReference(),
              source.signedAt(),
              source.sourceCapturedAt(),
              source.legacyGameSessionTenantId(),
              source.canonicalTenantId(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind(),
              source.gameSessionProjectionDigest(),
              source.gameSessionEvidenceDigest());
    } catch (RuntimeException exception) {
      throw new InvalidAssociationEvidenceException(
          "Game Design approval manifest is invalid", exception);
    }
    String signature = sourceReceipt.ed25519Signature();
    requireSignatureShape(signature);
    String manifestDigest = sourceReceipt.manifestDigest();
    if (manifestDigest == null || !evidence.manifestDigest().equals(manifestDigest)) {
      throw new InvalidAssociationEvidenceException(
          "Game Design approval digest does not match its closed manifest");
    }
    return new ValidatedApproval(evidence, manifestDigest, signature);
  }

  private void requireSnapshotMatchesApproval(
      GameSessionRetainedTenantSnapshot currentSnapshot,
      GameSessionRetainedTenantSnapshot sourceBoundSnapshot,
      GameSessionTenantAssociationEvidence evidence) {
    if (currentSnapshot == null
        || sourceBoundSnapshot == null
        || !workloadNamespace.equals(currentSnapshot.targetNamespace())
        || !evidence.legacyGameSessionTenantId().equals(currentSnapshot.legacyGameSessionTenantId())
        || !evidence.gameSessionProjectionDigest().equals(currentSnapshot.projectionDigest())
        || !evidence.sourceCapturedAt().equals(sourceBoundSnapshot.capturedAt())
        || !evidence.gameSessionEvidenceDigest().equals(sourceBoundSnapshot.evidenceDigest())) {
      throw new InvalidAssociationEvidenceException(
          "Retained Game Session snapshot does not match the approved owner evidence");
    }
  }

  private void requireCaptureStillUsable(String capturedAt) {
    Record expiry =
        dsl.fetchOne(
            "SELECT clock_timestamp() >= (?::timestamptz + INTERVAL '30 days') AS expired",
            capturedAt);
    if (expiry == null || Boolean.TRUE.equals(expiry.get("expired", Boolean.class))) {
      throw new InvalidAssociationEvidenceException(
          "Retained-tenant approval payload is expired and must be recaptured");
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Retained-tenant association registration requires an active owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Retained-tenant association registration requires a writable owner transaction");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equals(settings.get("transaction_isolation", String.class))
        || !"off".equals(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "Retained-tenant association registration requires writable READ COMMITTED");
    }
  }

  private Record findByOperationId(UUID operationId) {
    return selectCompleteAssociation()
        .where(ASSOCIATION_OPERATION_ID.eq(operationId).and(TARGET_NAMESPACE.eq(workloadNamespace)))
        .fetchOne();
  }

  private Record findByRequest(String namespace, UUID requestId) {
    return selectCompleteAssociation()
        .where(TARGET_NAMESPACE.eq(namespace).and(ASSOCIATION_REQUEST_ID.eq(requestId)))
        .fetchOne();
  }

  private SelectJoinStep<Record> selectCompleteAssociation() {
    return dsl.select(ASSOCIATION.asterisk(), ASSOCIATION_PAYLOAD.asterisk())
        .from(ASSOCIATION)
        .leftJoin(ASSOCIATION_PAYLOAD)
        .on(ASSOCIATION_OPERATION_ID.eq(PAYLOAD_OPERATION_ID));
  }

  private Record findByCanonicalTenant(String namespace, UUID tenantId) {
    return dsl.selectFrom(ASSOCIATION)
        .where(TARGET_NAMESPACE.eq(namespace).and(CANONICAL_TENANT_ID.eq(tenantId)))
        .fetchOne();
  }

  private Record findByLegacyKey(String namespace, long legacyKey) {
    return dsl.selectFrom(ASSOCIATION)
        .where(TARGET_NAMESPACE.eq(namespace).and(LEGACY_GAME_SESSION_TENANT_ID.eq(legacyKey)))
        .fetchOne();
  }

  private static String requestDigest(
      String exactNamespace, UUID associationRequestId, String manifestDigest, String signature) {
    return framedDigest(
        REQUEST_DOMAIN, exactNamespace, associationRequestId.toString(), manifestDigest, signature);
  }

  private static <T> Field<T> payloadField(String name, Class<T> type) {
    return DSL.field(DSL.name("game_session_retained_tenant_association_payload", name), type);
  }

  private static String captureInstant(Record row) {
    OffsetDateTime capturedAt = row.get(CAPTURED_AT);
    if (capturedAt == null) {
      throw new InvalidAssociationEvidenceException(
          "Retained-tenant evidence has no authoritative source capture time");
    }
    return capturedAt.toInstant().toString();
  }

  private static String receiptDigest(
      UUID localOperationId,
      String requestDigest,
      String manifestDigest,
      String snapshotEvidenceDigest) {
    return framedDigest(
        RECEIPT_DOMAIN,
        localOperationId.toString(),
        requestDigest,
        manifestDigest,
        snapshotEvidenceDigest);
  }

  private static String framedDigest(String... segments) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (String segment : segments) {
      byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
      output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
      output.write(':');
      output.writeBytes(bytes);
    }
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray()));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void requireSignatureShape(String signature) {
    if (signature == null) {
      throw new InvalidAssociationEvidenceException("Game Design approval signature is missing");
    }
    final byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(signature);
    } catch (IllegalArgumentException exception) {
      throw new InvalidAssociationEvidenceException(
          "Game Design approval signature is not canonical Ed25519 base64", exception);
    }
    if (bytes.length != SIGNATURE_BYTES
        || !Base64.getEncoder().encodeToString(bytes).equals(signature)) {
      throw new InvalidAssociationEvidenceException(
          "Game Design approval signature is not canonical 64-byte Ed25519 base64");
    }
  }

  private static void requireNamespace(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("exactNamespace must be one canonical DNS label");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static void requirePositive(long value, String label) {
    if (value <= 0) {
      throw new IllegalArgumentException(label + " must be a positive BIGINT");
    }
  }

  private record ValidatedApproval(
      GameSessionTenantAssociationEvidence evidence, String manifestDigest, String signature) {}

  /** Complete immutable local evidence; it contains no credential or approval private key. */
  public record AssociationReceipt(
      UUID operationId,
      UUID associationRequestId,
      String requestDigest,
      GameSessionTenantAssociationEvidence approval,
      String approvalManifestDigest,
      String approvalSignature,
      GameSessionRetainedTenantSnapshot snapshot,
      String receiptDigest) {}

  /** Minimal immutable proof consumed by owner-local tenant identity readers. */
  public record RetainedTenantAssociationIdentity(
      String targetNamespace,
      UUID canonicalTenantId,
      long legacyGameSessionTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind) {
    public RetainedTenantAssociationIdentity {
      requireNamespace(targetNamespace);
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requirePositive(legacyGameSessionTenantId, "legacyGameSessionTenantId");
      requirePositive(sourceGameRowId, "sourceGameRowId");
      if (sourceGameTenantKey == null || sourceGameTenantKey.isBlank()) {
        throw new IllegalArgumentException("sourceGameTenantKey is required");
      }
      if (!"NEW_GAME_ROW".equals(provenanceKind) && !"RETAINED_GAME_V29".equals(provenanceKind)) {
        throw new IllegalArgumentException("provenanceKind is not recognized");
      }
    }
  }

  public static class AssociationConflictException extends RuntimeException {
    public AssociationConflictException(String message) {
      super(message);
    }
  }

  public static class InvalidAssociationEvidenceException extends RuntimeException {
    public InvalidAssociationEvidenceException(String message) {
      super(message);
    }

    public InvalidAssociationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
