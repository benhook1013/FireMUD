package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.gamedesign.maintenance.GameSessionTenantAssociationManifestVerifier;
import net.firedevops.firemud.gamedesign.maintenance.GameSessionTenantAssociationManifestVerifier.Signed;
import net.firedevops.firemud.gamedesign.maintenance.GameSessionTenantAssociationManifestVerifier.Verified;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable Game Design owner receipt for an audited retained Game Session tenant association. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext and GameRepository are internal owner collaborators.")
public class GameSessionTenantAssociationRepository {
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Field<Long> GAME_ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<UUID> GAME_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);

  private static final Table<?> ASSOCIATION =
      DSL.table(DSL.name("game_design_game_session_tenant_association_operations"));
  private static final Table<?> ASSOCIATION_PAYLOAD =
      DSL.table(DSL.name("game_design_game_session_tenant_association_payload"));
  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<Integer> SCHEMA_VERSION =
      DSL.field(
          DSL.name("game_design_game_session_tenant_association_payload", "schema_version"),
          Integer.class);
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
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
  private static final Field<String> GAME_SESSION_EVIDENCE_DIGEST =
      payloadField("game_session_evidence_digest", String.class);
  private static final Field<String> GAME_SESSION_PROJECTION_DIGEST =
      payloadField("game_session_projection_digest", String.class);
  private static final Field<String> MANIFEST_DIGEST =
      payloadField("manifest_digest", String.class);
  private static final Field<String> SIGNATURE = payloadField("signature", String.class);
  private static final Field<OffsetDateTime> CAPTURED_AT =
      DSL.field(DSL.name("captured_at"), OffsetDateTime.class);
  private static final Field<String> TERMINAL_OUTCOME =
      DSL.field(DSL.name("terminal_outcome"), String.class);
  private static final Field<UUID> ASSOCIATION_OPERATION_ID =
      DSL.field(
          DSL.name("game_design_game_session_tenant_association_operations", "operation_id"),
          UUID.class);
  private static final Field<UUID> PAYLOAD_OPERATION_ID = payloadField("operation_id", UUID.class);

  private final DSLContext dsl;
  private final GameRepository gameRepository;

  public GameSessionTenantAssociationRepository(DSLContext dsl, GameRepository gameRepository) {
    this.dsl = dsl;
    this.gameRepository = gameRepository;
  }

  /**
   * Verifies and atomically records the approved association inside its caller's owner transaction.
   *
   * <p>The actual canonical-UUID game row is locked before source validation and the one-row claim.
   * A uniqueness conflict is resolved by exact persisted operation readback after the insert
   * attempt, so no preflight lookup can race into a false successful claim.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AssociationReceipt apply(
      Signed signed, Map<String, String> trustedPublicKeys, String exactNamespace) {
    Verified verified =
        GameSessionTenantAssociationManifestVerifier.verify(signed, trustedPublicKeys);
    GameSessionTenantAssociationEvidence manifest = verified.manifest();
    if (exactNamespace == null || !exactNamespace.equals(manifest.targetNamespace())) {
      throw new IllegalArgumentException("manifest targets a different namespace");
    }
    requireActiveOwnerTransaction();
    requireCaptureStillUsable(manifest.sourceCapturedAt());

    GameTenantIdentity source = lockAndReadSource(manifest.canonicalTenantId());
    requireManifestSource(manifest, source);

    int inserted =
        dsl.insertInto(ASSOCIATION)
            .set(OPERATION_ID, manifest.operationId())
            .set(TARGET_NAMESPACE, manifest.targetNamespace())
            .set(LEGACY_GAME_SESSION_TENANT_ID, manifest.legacyGameSessionTenantIdValue())
            .set(CANONICAL_TENANT_ID, manifest.canonicalTenantId())
            .set(SOURCE_GAME_ROW_ID, manifest.sourceGameRowIdValue())
            .set(SOURCE_GAME_TENANT_KEY, manifest.sourceGameTenantKey())
            .set(PROVENANCE_KIND, manifest.provenanceKind())
            .set(
                CAPTURED_AT,
                OffsetDateTime.ofInstant(
                    Instant.parse(manifest.sourceCapturedAt()), ZoneOffset.UTC))
            .set(TERMINAL_OUTCOME, "ASSOCIATED")
            .onConflictDoNothing()
            .execute();

    if (inserted == 1) {
      dsl.execute(
          "INSERT INTO game_design_game_session_tenant_association_payload ("
              + "operation_id, schema_version, signer_key_id, approved_by, approval_reference, "
              + "signed_at, game_session_projection_digest, game_session_evidence_digest, "
              + "manifest_digest, signature) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          manifest.operationId(),
          manifest.schemaVersion(),
          manifest.signerKeyId(),
          manifest.approvedBy(),
          manifest.approvalReference(),
          manifest.signedAt(),
          manifest.gameSessionProjectionDigest(),
          manifest.gameSessionEvidenceDigest(),
          verified.manifestDigest(),
          signed.ed25519Signature());
    }

    Record persisted = findByOperationId(manifest.operationId());
    if (persisted == null) {
      if (inserted == 0) {
        throw new AssociationConflictException(
            "canonical tenant or retained Game Session key is already associated");
      }
      throw new InvalidAssociationEvidenceException(
          "committed Game Session tenant association operation is missing");
    }

    AssociationReceipt receipt = toValidatedReceipt(persisted);
    requireExactReceipt(verified, signed.ed25519Signature(), receipt);
    requireManifestSource(receipt.manifest(), source);
    return receipt;
  }

  /**
   * Reads exact committed owner evidence without allocation, repair, or signature reauthorization.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<AssociationReceipt> read(
      UUID operationId,
      UUID canonicalTenantId,
      long legacyGameSessionTenantId,
      String exactNamespace) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Session tenant association read requires a committed-outcome owner read");
    }
    if (operationId == null || operationId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("operationId must be a canonical non-nil UUID");
    }
    if (canonicalTenantId == null || canonicalTenantId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("canonicalTenantId must be a canonical non-nil UUID");
    }
    if (legacyGameSessionTenantId <= 0) {
      throw new IllegalArgumentException("legacy Game Session tenant key must be positive");
    }
    if (exactNamespace == null || exactNamespace.isBlank()) {
      throw new IllegalArgumentException("exact namespace is required");
    }

    Record row =
        selectCompleteAssociation()
            .where(
                ASSOCIATION_OPERATION_ID
                    .eq(operationId)
                    .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                    .and(LEGACY_GAME_SESSION_TENANT_ID.eq(legacyGameSessionTenantId))
                    .and(TARGET_NAMESPACE.eq(exactNamespace)))
            .fetchOne();
    if (row == null) {
      return Optional.empty();
    }

    AssociationReceipt receipt = toValidatedReceipt(row);
    GameTenantIdentity source =
        gameRepository
            .findRuntimeTenantIdentityByCanonicalTenantId(receipt.manifest().canonicalTenantId())
            .orElseThrow(
                () ->
                    new InvalidAssociationEvidenceException(
                        "persisted Game Session tenant association source is missing"));
    requireManifestSource(receipt.manifest(), source);
    return Optional.of(receipt);
  }

  /** Deletes one bounded batch of expired Game Session approval payloads. */
  @Transactional(propagation = Propagation.REQUIRED)
  public int purgeExpiredRawPayloads() {
    return dsl.execute(
        "WITH expired AS ("
            + " SELECT payload.operation_id"
            + " FROM game_design_game_session_tenant_association_payload payload"
            + " JOIN game_design_game_session_tenant_association_operations association"
            + "   ON association.operation_id = payload.operation_id"
            + " WHERE association.terminal_outcome = 'ASSOCIATED'"
            + "   AND (association.captured_at IS NULL"
            + "        OR association.captured_at <= clock_timestamp() - INTERVAL '30 days')"
            + "   AND NOT EXISTS (SELECT 1"
            + "       FROM game_design_game_session_tenant_association_legal_hold hold"
            + "       WHERE hold.operation_id = payload.operation_id AND hold.released_at IS NULL)"
            + " ORDER BY association.captured_at NULLS FIRST, payload.operation_id"
            + " LIMIT 100 FOR UPDATE OF payload SKIP LOCKED"
            + ") DELETE FROM game_design_game_session_tenant_association_payload payload"
            + " USING expired WHERE payload.operation_id = expired.operation_id");
  }

  private GameTenantIdentity lockAndReadSource(UUID canonicalTenantId) {
    Long lockedGameId =
        dsl.select(GAME_ID)
            .from(GAME)
            .where(GAME_CANONICAL_TENANT_ID.eq(canonicalTenantId))
            .forUpdate()
            .fetchOne(GAME_ID);
    if (lockedGameId == null) {
      throw new AssociationConflictException("canonical tenant source game row is missing");
    }
    GameTenantIdentity source =
        gameRepository
            .findRuntimeTenantIdentityByCanonicalTenantId(canonicalTenantId)
            .orElseThrow(
                () ->
                    new AssociationConflictException(
                        "canonical tenant source game row is missing"));
    if (source.sourceGameId() == null || !lockedGameId.equals(source.sourceGameId())) {
      throw new InvalidAssociationEvidenceException(
          "locked Game Design source row changed during canonical tenant readback");
    }
    return source;
  }

  private Record findByOperationId(UUID operationId) {
    return selectCompleteAssociation().where(ASSOCIATION_OPERATION_ID.eq(operationId)).fetchOne();
  }

  private SelectJoinStep<Record> selectCompleteAssociation() {
    return dsl.select(ASSOCIATION.asterisk(), ASSOCIATION_PAYLOAD.asterisk())
        .from(ASSOCIATION)
        .leftJoin(ASSOCIATION_PAYLOAD)
        .on(ASSOCIATION_OPERATION_ID.eq(PAYLOAD_OPERATION_ID));
  }

  private void requireCaptureStillUsable(String capturedAt) {
    Record expiry =
        dsl.fetchOne(
            "SELECT clock_timestamp() >= (?::timestamptz + INTERVAL '30 days') AS expired",
            capturedAt);
    if (expiry == null || Boolean.TRUE.equals(expiry.get("expired", Boolean.class))) {
      throw new InvalidAssociationEvidenceException(
          "Game Session tenant approval is expired and requires a new source capture");
    }
  }

  private static String captureInstant(Record row) {
    OffsetDateTime capturedAt = row.get(CAPTURED_AT);
    if (capturedAt == null) {
      throw new InvalidAssociationEvidenceException(
          "Game Session tenant association has no authoritative source capture time");
    }
    return capturedAt.toInstant().toString();
  }

  private static <T> Field<T> payloadField(String name, Class<T> type) {
    return DSL.field(DSL.name("game_design_game_session_tenant_association_payload", name), type);
  }

  private AssociationReceipt toValidatedReceipt(Record row) {
    try {
      GameSessionTenantAssociationEvidence manifest =
          new GameSessionTenantAssociationEvidence(
              row.get(SCHEMA_VERSION),
              row.get(ASSOCIATION_OPERATION_ID),
              row.get(TARGET_NAMESPACE),
              row.get(SIGNER_KEY_ID),
              row.get(APPROVED_BY),
              row.get(APPROVAL_REFERENCE),
              row.get(SIGNED_AT),
              captureInstant(row),
              Long.toString(row.get(LEGACY_GAME_SESSION_TENANT_ID)),
              row.get(CANONICAL_TENANT_ID),
              Long.toString(row.get(SOURCE_GAME_ROW_ID)),
              row.get(SOURCE_GAME_TENANT_KEY),
              row.get(PROVENANCE_KIND),
              row.get(GAME_SESSION_PROJECTION_DIGEST),
              row.get(GAME_SESSION_EVIDENCE_DIGEST));
      String signature = row.get(SIGNATURE);
      requireCaptureStillUsable(manifest.sourceCapturedAt());
      requireCanonicalSignatureShape(signature);
      String persistedDigest = row.get(MANIFEST_DIGEST);
      if (!manifest.manifestDigest().equals(persistedDigest)) {
        throw new InvalidAssociationEvidenceException(
            "persisted Game Session tenant association digest does not match its manifest");
      }
      return new AssociationReceipt(manifest, signature, persistedDigest);
    } catch (InvalidAssociationEvidenceException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new InvalidAssociationEvidenceException(
          "persisted Game Session tenant association evidence is invalid", exception);
    }
  }

  private void requireManifestSource(
      GameSessionTenantAssociationEvidence manifest, GameTenantIdentity source) {
    if (source == null) {
      throw new InvalidAssociationEvidenceException(
          "Game Session tenant association source no longer matches its Game Design game row");
    }
    Long sourceGameId = source.sourceGameId();
    GameTenantIdentity.ProvenanceKind provenanceKind = source.provenanceKind();
    if (sourceGameId == null
        || provenanceKind == null
        || !manifest.canonicalTenantId().equals(source.canonicalTenantId())
        || manifest.sourceGameRowIdValue() != sourceGameId
        || !manifest.sourceGameTenantKey().equals(source.sourceLegacyTenantId())
        || !manifest.provenanceKind().equals(provenanceKind.name())) {
      throw new InvalidAssociationEvidenceException(
          "Game Session tenant association source no longer matches its Game Design game row");
    }
  }

  private void requireExactReceipt(Verified verified, String signature, AssociationReceipt actual) {
    if (!verified.manifest().equals(actual.manifest())
        || !verified.manifestDigest().equals(actual.manifestDigest())
        || !signature.equals(actual.ed25519Signature())) {
      throw new AssociationConflictException(
          "Game Session tenant association operation was reused with changed signed evidence");
    }
  }

  private void requireCanonicalSignatureShape(String signature) {
    if (signature == null || signature.isBlank()) {
      throw new InvalidAssociationEvidenceException(
          "persisted Game Session tenant association signature is missing");
    }
    byte[] signatureBytes;
    try {
      signatureBytes = Base64.getDecoder().decode(signature);
    } catch (IllegalArgumentException exception) {
      throw new InvalidAssociationEvidenceException(
          "persisted Game Session tenant association signature is not canonical Ed25519",
          exception);
    }
    if (signatureBytes.length != 64
        || !Base64.getEncoder().encodeToString(signatureBytes).equals(signature)) {
      throw new InvalidAssociationEvidenceException(
          "persisted Game Session tenant association signature is not canonical Ed25519");
    }
  }

  private void requireActiveOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Session tenant association apply requires an active Game Design owner transaction");
    }
  }

  public record AssociationReceipt(
      GameSessionTenantAssociationEvidence manifest,
      String ed25519Signature,
      String manifestDigest) {
    public AssociationReceipt {
      Objects.requireNonNull(manifest, "manifest");
      Objects.requireNonNull(ed25519Signature, "ed25519Signature");
      Objects.requireNonNull(manifestDigest, "manifestDigest");
    }
  }

  public static final class AssociationConflictException extends IllegalStateException {
    public AssociationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidAssociationEvidenceException extends IllegalStateException {
    public InvalidAssociationEvidenceException(String message) {
      super(message);
    }

    public InvalidAssociationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
