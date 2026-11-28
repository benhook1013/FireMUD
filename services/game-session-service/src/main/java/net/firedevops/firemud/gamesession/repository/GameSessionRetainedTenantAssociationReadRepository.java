package net.firedevops.firemud.gamesession.repository;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads the original, signed Game Session tenant association for one exact owner-selected route.
 */
public final class GameSessionRetainedTenantAssociationReadRepository {
  private static final UUID NIL = new UUID(0, 0);
  private static final String MANIFEST_DOMAIN =
      "game-design/game-session-retained-tenant-association/v1";
  private static final String REQUEST_DOMAIN =
      "game-session/retained-tenant-association-request/v1";
  private static final String RECEIPT_DOMAIN =
      "game-session/retained-tenant-association-receipt/v1";
  private static final String FIND_ORIGINAL =
      """
      SELECT route.id AS pointer_id, association.operation_id, association.association_request_id,
             association.canonical_tenant_id, association.source_game_row_id,
             association.source_game_tenant_key, association.provenance_kind,
             payload.approval_operation_id, payload.approval_schema_version, payload.signer_key_id,
             payload.approved_by, payload.approval_reference, payload.signed_at,
             payload.game_session_evidence_digest, payload.approval_manifest_digest,
             payload.approval_signature, payload.snapshot_canonical_json,
             payload.snapshot_evidence_digest, payload.request_digest, payload.receipt_digest
        FROM gameplay_admission_pointer route
        JOIN game_instances instance
          ON instance.id = route.game_instance_id AND instance.tenant_id = route.tenant_id
        JOIN game_session_retained_tenant_association association
          ON association.legacy_game_session_tenant_id = route.tenant_id
        JOIN game_session_retained_tenant_association_payload payload
          ON payload.operation_id = association.operation_id
        JOIN game_session_tenant_canonical_claim claim
          ON claim.target_namespace = association.target_namespace
         AND claim.canonical_tenant_id = association.canonical_tenant_id
         AND claim.legacy_game_session_tenant_id = association.legacy_game_session_tenant_id
         AND claim.association_operation_id = association.operation_id
         AND claim.association_request_id = association.association_request_id
         AND claim.association_kind = 'RETAINED_V8_1'
         AND claim.reservation_kind = 'LEGACY_OCCUPIED'
       WHERE association.target_namespace = ?
         AND association.terminal_outcome = 'ASSOCIATED'
         AND route.representation_version = 1
         AND route.tenant_id = ? AND route.game_instance_id = ?
         AND route.world_slug = ? AND route.realm_slug = ?
         AND route.pointer_version = ? AND route.catalog_revision = ?
         AND route.realm_id = ? AND route.playable_state_namespace_id = ?
         AND route.visible = ? AND route.public_production_realm = ?
         AND route.requires_character_selection = ?
         AND route.state_scope = ? AND route.character_creation_policy = ?
      """;

  private final DSLContext database;
  private final String workloadNamespace;
  private final Map<String, PublicKey> trustedRetainedAssociationSigners;

  /** The supplied key map must be provisioned specifically for retained association approvals. */
  public GameSessionRetainedTenantAssociationReadRepository(
      DSLContext database,
      String workloadNamespace,
      Map<String, PublicKey> trustedRetainedAssociationSigners) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("A canonical Game Session workload namespace is required");
    }
    this.database = Objects.requireNonNull(database, "database");
    this.workloadNamespace = workloadNamespace;
    this.trustedRetainedAssociationSigners = Map.copyOf(trustedRetainedAssociationSigners);
  }

  /** Reads a selected route within this owner's provisioned workload namespace. */
  public Optional<UUID> readCanonicalTenantId(GameplayAdmissionPointerSnapshot target) {
    return readCanonicalTenantId(workloadNamespace, target);
  }

  /**
   * Observes one committed owner snapshot. The result identifies the retained tenant; it carries no
   * current Account entitlement or admission decision.
   */
  public Optional<UUID> readCanonicalTenantId(
      String exactNamespace, GameplayAdmissionPointerSnapshot target) {
    if (!workloadNamespace.equals(exactNamespace) || !hasExactTarget(target)) {
      return Optional.empty();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Retained association requires an independent owner read");
    }
    Optional<Record> original =
        database.connectionResult(
            connection -> {
              if (!connection.getAutoCommit()) {
                throw new IllegalStateException(
                    "Retained association requires a committed database snapshot");
              }
              return DSL.using(connection, database.dialect())
                  .fetchOptional(
                      FIND_ORIGINAL,
                      workloadNamespace,
                      target.tenantId(),
                      target.gameInstanceId(),
                      target.worldSlug(),
                      target.realmSlug(),
                      target.pointerVersion(),
                      target.catalogRevision(),
                      target.realmId(),
                      target.playableStateNamespaceId(),
                      target.visible(),
                      target.publicProductionRealm(),
                      target.requiresCharacterSelection(),
                      target.stateScope(),
                      target.characterCreationPolicy());
            });
    return original.flatMap(row -> qualify(row, target));
  }

  private Optional<UUID> qualify(Record row, GameplayAdmissionPointerSnapshot target) {
    // These are the exact v1 provenance labels signed for the installed V12 format.
    String provenanceKind = row.get("provenance_kind", String.class);
    if (!Integer.valueOf(1).equals(row.get("approval_schema_version", Integer.class))
        || (!"NEW_GAME_ROW".equals(provenanceKind)
            && !"RETAINED_GAME_V29".equals(provenanceKind))) {
      return Optional.empty();
    }
    UUID tenantId = row.get("canonical_tenant_id", UUID.class);
    UUID operationId = row.get("operation_id", UUID.class);
    UUID requestId = row.get("association_request_id", UUID.class);
    UUID approvalId = row.get("approval_operation_id", UUID.class);
    String signerId = row.get("signer_key_id", String.class);
    String approvedBy = row.get("approved_by", String.class);
    String approvalReference = row.get("approval_reference", String.class);
    String signedAt = row.get("signed_at", String.class);
    String sourceTenantKey = row.get("source_game_tenant_key", String.class);
    Long sourceRowId = row.get("source_game_row_id", Long.class);
    String signedSnapshotDigest = row.get("game_session_evidence_digest", String.class);
    if (!nonNil(tenantId)
        || !nonNil(operationId)
        || !nonNil(requestId)
        || !nonNil(approvalId)
        || sourceRowId == null
        || sourceRowId <= 0
        || !validLabel(signerId, 128)
        || !validLabel(approvedBy, 256)
        || !validLabel(approvalReference, 512)
        || !isCanonicalInstant(signedAt)
        || sourceTenantKey == null
        || sourceTenantKey.isBlank()
        || sourceTenantKey.codePointCount(0, sourceTenantKey.length()) > 36
        || !GameTenantCreationDigest.isDigest(signedSnapshotDigest)) {
      return Optional.empty();
    }
    PublicKey trustedSigner = trustedRetainedAssociationSigners.get(signerId);
    if (trustedSigner == null) {
      return Optional.empty();
    }

    try {
      return verify(
              row,
              target,
              operationId,
              requestId,
              signedSnapshotDigest,
              trustedSigner,
              GameSessionRetainedTenantSnapshot.frame(
                  MANIFEST_DOMAIN,
                  "1",
                  approvalId.toString(),
                  workloadNamespace,
                  signerId,
                  approvedBy,
                  approvalReference,
                  signedAt,
                  Long.toString(target.tenantId()),
                  tenantId.toString(),
                  sourceRowId.toString(),
                  sourceTenantKey,
                  provenanceKind,
                  signedSnapshotDigest))
          ? Optional.of(tenantId)
          : Optional.empty();
    } catch (IllegalArgumentException | GeneralSecurityException invalidEvidence) {
      return Optional.empty();
    }
  }

  private boolean verify(
      Record row,
      GameplayAdmissionPointerSnapshot target,
      UUID operationId,
      UUID requestId,
      String signedSnapshotDigest,
      PublicKey trustedSigner,
      byte[] preimage)
      throws GeneralSecurityException {
    String manifestDigest = GameSessionRetainedTenantSnapshot.sha256(preimage);
    String encodedSignature = row.get("approval_signature", String.class);
    String snapshotDigest = row.get("snapshot_evidence_digest", String.class);
    if (encodedSignature == null
        || !manifestDigest.equals(row.get("approval_manifest_digest", String.class))
        || !signedSnapshotDigest.equals(snapshotDigest)) {
      return false;
    }

    byte[] signature = Base64.getDecoder().decode(encodedSignature);
    if (signature.length != 64
        || !Base64.getEncoder().encodeToString(signature).equals(encodedSignature)) {
      return false;
    }
    String requestDigest =
        GameSessionRetainedTenantSnapshot.framedDigest(
            REQUEST_DOMAIN,
            workloadNamespace,
            requestId.toString(),
            manifestDigest,
            encodedSignature);
    String receiptDigest =
        GameSessionRetainedTenantSnapshot.framedDigest(
            RECEIPT_DOMAIN, operationId.toString(), requestDigest, manifestDigest, snapshotDigest);
    if (!requestDigest.equals(row.get("request_digest", String.class))
        || !receiptDigest.equals(row.get("receipt_digest", String.class))) {
      return false;
    }

    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(trustedSigner);
    verifier.update(preimage);
    if (!verifier.verify(signature)) {
      return false;
    }
    var snapshot =
        new GameSessionRetainedTenantSnapshot(
            workloadNamespace,
            Long.toString(target.tenantId()),
            row.get("snapshot_canonical_json", String.class),
            snapshotDigest);
    Long pointerId = row.get("pointer_id", Long.class);
    return pointerId != null && snapshot.containsPointer(pointerId, target);
  }

  private static boolean hasExactTarget(GameplayAdmissionPointerSnapshot target) {
    return target != null
        && target.tenantId() > 0
        && target.gameInstanceId() > 0
        && target.pointerVersion() > 0
        && target.catalogRevision() > 0
        && nonNil(target.realmId())
        && nonNil(target.playableStateNamespaceId())
        && target.worldSlug() != null
        && !target.worldSlug().isBlank()
        && target.realmSlug() != null
        && !target.realmSlug().isBlank()
        && target.stateScope() != null
        && target.characterCreationPolicy() != null;
  }

  private static boolean validLabel(String text, int maxBytes) {
    return text != null
        && !text.isBlank()
        && GameTenantCreationDigest.utf8ByteLength(text) <= maxBytes
        && text.codePoints().noneMatch(Character::isISOControl);
  }

  private static boolean isCanonicalInstant(String text) {
    try {
      return text != null && Instant.parse(text).toString().equals(text);
    } catch (DateTimeParseException invalidTimestamp) {
      return false;
    }
  }

  private static boolean nonNil(UUID value) {
    return value != null && !NIL.equals(value);
  }
}
