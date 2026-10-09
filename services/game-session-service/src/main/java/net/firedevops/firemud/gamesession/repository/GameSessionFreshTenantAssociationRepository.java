package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Game Session's owner for immutable fresh canonical-tenant to private tenant-key association.
 *
 * <p>This producer consumes only a committed Game Session source-intake receipt. It does not create
 * a runtime instance, admission pointer, grant, or external authority.
 */
@Repository
public class GameSessionFreshTenantAssociationRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String FRESH_KIND = "FRESH_SOURCE_BOUND";

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Fail-fast validation is local; no resources or finalizer are acquired. The repository"
              + " remains non-final for transaction proxying.")
  public GameSessionFreshTenantAssociationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
  }

  /**
   * Persists the fresh source-bound tenant mapping or returns its exact durable replay.
   *
   * <p>The association operation ID is the caller's stable mutation identity. The expected receipt
   * is re-read, digest-validated, and compared with its immutable tenant-source binding under this
   * same writable READ COMMITTED transaction before any private tenant ID is allocated.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public FreshGameSessionTenantAssociation associateFresh(
      UUID associationOperationId, String targetNamespace, IntakeReceipt expectedSourceReceipt) {
    requireWritableReadCommittedOwnerTransaction();
    requireNonNil(associationOperationId, "associationOperationId");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
    }
    Objects.requireNonNull(expectedSourceReceipt, "expectedSourceReceipt");
    if (!targetNamespace.equals(expectedSourceReceipt.source().targetNamespace())) {
      throw new FreshTenantAssociationConflictException(
          "Source receipt namespace differs from the requested association namespace");
    }

    lockAssociationOperation(associationOperationId);
    IntakeReceipt persistedSource =
        readPersistedSourceReceipt(targetNamespace, expectedSourceReceipt.operationId());
    if (!persistedSource.equals(expectedSourceReceipt)) {
      throw new InvalidFreshTenantSourceException(
          "Persisted authored-world source receipt differs from the requested exact receipt");
    }
    RuntimeTenantIdentityEvidence runtimeSource =
        requireFreshSource(targetNamespace, persistedSource.source());

    Record existingAssociation = findAssociationByOperation(associationOperationId);
    if (existingAssociation != null) {
      return requireExactAssociation(
          existingAssociation, associationOperationId, targetNamespace, runtimeSource);
    }

    if (findClaimByOperation(associationOperationId) != null) {
      throw new FreshTenantAssociationConflictException(
          "Association operation ID is already owned by another tenant claim");
    }
    if (findClaimByCanonicalTenant(targetNamespace, runtimeSource.canonicalTenantId()) != null) {
      throw new FreshTenantAssociationConflictException(
          "Canonical tenant already has a Game Session tenant association");
    }
    if (findClaimByAssociationRequest(
            targetNamespace, persistedSource.source().registrationRequestId())
        != null) {
      throw new FreshTenantAssociationConflictException(
          "Source registration request is already owned by another tenant association");
    }

    long privateTenantId = allocateFreshPrivateTenantId();
    insertCanonicalClaim(
        targetNamespace,
        runtimeSource.canonicalTenantId(),
        privateTenantId,
        associationOperationId,
        persistedSource.source().registrationRequestId());
    insertFreshAssociation(
        associationOperationId, targetNamespace, privateTenantId, persistedSource.source());

    Record committedAssociation = findAssociationByOperation(associationOperationId);
    if (committedAssociation == null) {
      throw new IllegalStateException("Committed fresh tenant association is missing");
    }
    return requireExactAssociation(
        committedAssociation, associationOperationId, targetNamespace, runtimeSource);
  }

  private void lockAssociationOperation(UUID associationOperationId) {
    // Hash collisions only serialize unrelated operations; they cannot merge their owner records.
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(?)", associationOperationId.getMostSignificantBits());
  }

  private IntakeReceipt readPersistedSourceReceipt(String namespace, UUID intakeOperationId) {
    Record row =
        dsl.fetchOne(
            "SELECT intake.operation_id AS operation_id, "
                + "intake.schema_version AS schema_version, "
                + "intake.target_namespace AS target_namespace, "
                + "intake.intake_request_id AS intake_request_id, "
                + "intake.request_digest AS request_digest, "
                + "intake.source_schema_version AS source_schema_version, "
                + "intake.source_registration_request_id AS source_registration_request_id, "
                + "intake.source_operation_id AS source_operation_id, "
                + "intake.source_request_digest AS source_request_digest, "
                + "intake.canonical_tenant_id AS canonical_tenant_id, "
                + "intake.tenant_slug AS tenant_slug, intake.world_slug AS world_slug, "
                + "intake.world_display_name AS world_display_name, "
                + "intake.source_game_row_id AS source_game_row_id, "
                + "intake.source_game_tenant_key AS source_game_tenant_key, "
                + "intake.source_provenance_kind AS source_provenance_kind, "
                + "intake.source_evidence_digest AS source_evidence_digest, "
                + "intake.receipt_digest AS receipt_digest, "
                + "binding.target_namespace AS binding_target_namespace, "
                + "binding.canonical_tenant_id AS binding_canonical_tenant_id, "
                + "binding.tenant_slug AS binding_tenant_slug, "
                + "binding.source_game_row_id AS binding_source_game_row_id, "
                + "binding.source_game_tenant_key AS binding_source_game_tenant_key, "
                + "binding.provenance_kind AS binding_provenance_kind "
                + "FROM game_session_authored_world_source_intake intake "
                + "JOIN game_session_authored_world_tenant_source_binding binding "
                + "ON binding.target_namespace = intake.target_namespace "
                + "AND binding.canonical_tenant_id = intake.canonical_tenant_id "
                + "AND binding.tenant_slug = intake.tenant_slug "
                + "AND binding.source_game_row_id = intake.source_game_row_id "
                + "AND binding.source_game_tenant_key = intake.source_game_tenant_key "
                + "AND binding.provenance_kind = intake.source_provenance_kind "
                + "WHERE intake.target_namespace = ? AND intake.operation_id = ? "
                + "FOR UPDATE OF intake, binding",
            namespace,
            intakeOperationId);
    if (row == null) {
      Record otherNamespace =
          dsl.fetchOne(
              "SELECT target_namespace FROM game_session_authored_world_source_intake "
                  + "WHERE operation_id = ?",
              intakeOperationId);
      if (otherNamespace != null) {
        throw new FreshTenantAssociationConflictException(
            "Source intake operation belongs to a different target namespace");
      }
      throw new InvalidFreshTenantSourceException(
          "Exact committed Game Session source intake receipt is missing");
    }

    String provenanceKind = required(row.get("source_provenance_kind", String.class));
    if (!"NEW_GAME_ROW".equals(provenanceKind)) {
      throw new InvalidFreshTenantSourceException(
          "Fresh tenant association requires NEW_GAME_ROW source provenance");
    }
    if (!Integer.valueOf(1).equals(row.get("schema_version", Integer.class))) {
      throw new InvalidFreshTenantSourceException(
          "Unsupported Game Session source-intake receipt schema version");
    }

    try {
      AuthoredWorldSourceEvidence source =
          new AuthoredWorldSourceEvidence(
              required(row.get("source_schema_version", Integer.class)),
              required(row.get("target_namespace", String.class)),
              required(row.get("source_registration_request_id", UUID.class)),
              required(row.get("source_operation_id", UUID.class)),
              required(row.get("source_request_digest", String.class)),
              required(row.get("canonical_tenant_id", UUID.class)),
              required(row.get("tenant_slug", String.class)),
              required(row.get("world_slug", String.class)),
              required(row.get("world_display_name", String.class)),
              required(row.get("source_game_row_id", Long.class)),
              required(row.get("source_game_tenant_key", String.class)),
              provenanceKind,
              required(row.get("source_evidence_digest", String.class)));
      requireExactTenantSourceBinding(row, source);
      return new IntakeReceipt(
          required(row.get("operation_id", UUID.class)),
          required(row.get("intake_request_id", UUID.class)),
          required(row.get("request_digest", String.class)),
          source,
          required(row.get("receipt_digest", String.class)));
    } catch (RuntimeException exception) {
      if (exception instanceof InvalidFreshTenantSourceException) {
        throw exception;
      }
      throw new InvalidFreshTenantSourceException(
          "Persisted authored-world intake receipt failed exact evidence validation", exception);
    }
  }

  private void requireExactTenantSourceBinding(Record row, AuthoredWorldSourceEvidence source) {
    if (!Objects.equals(row.get("binding_target_namespace", String.class), source.targetNamespace())
        || !Objects.equals(
            row.get("binding_canonical_tenant_id", UUID.class), source.canonicalTenantId())
        || !Objects.equals(row.get("binding_tenant_slug", String.class), source.tenantSlug())
        || !Objects.equals(
            row.get("binding_source_game_row_id", Long.class), source.sourceGameRowId())
        || !Objects.equals(
            row.get("binding_source_game_tenant_key", String.class), source.sourceGameTenantKey())
        || !Objects.equals(
            row.get("binding_provenance_kind", String.class), source.provenanceKind())) {
      throw new InvalidFreshTenantSourceException(
          "Authored-world source intake differs from its locked tenant-source binding");
    }
  }

  private RuntimeTenantIdentityEvidence requireFreshSource(
      String targetNamespace, AuthoredWorldSourceEvidence source) {
    if (!targetNamespace.equals(source.targetNamespace())) {
      throw new InvalidFreshTenantSourceException(
          "Authored-world source namespace differs from the association namespace");
    }
    if (!"NEW_GAME_ROW".equals(source.provenanceKind())) {
      throw new InvalidFreshTenantSourceException(
          "Fresh tenant association requires NEW_GAME_ROW source provenance");
    }
    try {
      return new RuntimeTenantIdentityEvidence(
          source.schemaVersion(),
          source.targetNamespace(),
          source.registrationRequestId(),
          source.canonicalTenantId(),
          source.sourceGameRowId(),
          source.sourceGameTenantKey(),
          source.provenanceKind());
    } catch (IllegalArgumentException exception) {
      throw new InvalidFreshTenantSourceException(
          "Authored-world source cannot establish a fresh runtime tenant identity", exception);
    }
  }

  private Record findAssociationByOperation(UUID associationOperationId) {
    return dsl.fetchOne(
        "SELECT * FROM game_session_fresh_tenant_association "
            + "WHERE association_operation_id = ? FOR KEY SHARE",
        associationOperationId);
  }

  private Record findClaimByOperation(UUID associationOperationId) {
    return dsl.fetchOne(
        "SELECT * FROM game_session_tenant_canonical_claim "
            + "WHERE association_operation_id = ? FOR KEY SHARE",
        associationOperationId);
  }

  private Record findClaimByCanonicalTenant(String namespace, UUID canonicalTenantId) {
    return dsl.fetchOne(
        "SELECT * FROM game_session_tenant_canonical_claim "
            + "WHERE target_namespace = ? AND canonical_tenant_id = ? FOR UPDATE",
        namespace,
        canonicalTenantId);
  }

  private Record findClaimByAssociationRequest(String namespace, UUID associationRequestId) {
    return dsl.fetchOne(
        "SELECT * FROM game_session_tenant_canonical_claim "
            + "WHERE target_namespace = ? AND association_request_id = ? FOR UPDATE",
        namespace,
        associationRequestId);
  }

  private long allocateFreshPrivateTenantId() {
    Record reservation =
        dsl.fetchOne(
            "INSERT INTO game_session_tenant_scope_reservation (reservation_kind) "
                + "VALUES ('FRESH_SOURCE_BOUND') RETURNING game_session_tenant_id");
    if (reservation == null || reservation.get("game_session_tenant_id", Long.class) == null) {
      throw new IllegalStateException("Game Session tenant ID reservation returned no identity");
    }
    long privateTenantId = reservation.get("game_session_tenant_id", Long.class);
    if (privateTenantId <= 0) {
      throw new IllegalStateException("Game Session tenant ID reservation was not positive");
    }
    return privateTenantId;
  }

  private void insertCanonicalClaim(
      String namespace,
      UUID canonicalTenantId,
      long privateTenantId,
      UUID associationOperationId,
      UUID associationRequestId) {
    int inserted =
        dsl.execute(
            "INSERT INTO game_session_tenant_canonical_claim "
                + "(target_namespace, canonical_tenant_id, legacy_game_session_tenant_id, "
                + "association_kind, reservation_kind, association_operation_id, "
                + "association_request_id) "
                + "VALUES (?, ?, ?, 'FRESH_SOURCE_BOUND', 'FRESH_SOURCE_BOUND', ?, ?)",
            namespace,
            canonicalTenantId,
            privateTenantId,
            associationOperationId,
            associationRequestId);
    if (inserted != 1) {
      throw new IllegalStateException("Game Session canonical tenant claim was not persisted");
    }
  }

  private void insertFreshAssociation(
      UUID associationOperationId,
      String namespace,
      long privateTenantId,
      AuthoredWorldSourceEvidence source) {
    int inserted =
        dsl.execute(
            "INSERT INTO game_session_fresh_tenant_association "
                + "(association_operation_id, target_namespace, association_request_id, "
                + "source_schema_version, source_target_namespace, source_request_id, "
                + "source_canonical_tenant_id, canonical_tenant_id, "
                + "legacy_game_session_tenant_id, source_game_row_id, source_game_tenant_key, "
                + "provenance_kind, association_kind) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', "
                + "'FRESH_SOURCE_BOUND')",
            associationOperationId,
            namespace,
            source.registrationRequestId(),
            source.schemaVersion(),
            namespace,
            source.registrationRequestId(),
            source.canonicalTenantId(),
            source.canonicalTenantId(),
            privateTenantId,
            source.sourceGameRowId(),
            source.sourceGameTenantKey());
    if (inserted != 1) {
      throw new IllegalStateException("Game Session fresh tenant association was not persisted");
    }
  }

  private FreshGameSessionTenantAssociation requireExactAssociation(
      Record association,
      UUID expectedOperationId,
      String expectedNamespace,
      RuntimeTenantIdentityEvidence expectedSource) {
    UUID associationOperationId = required(association.get("association_operation_id", UUID.class));
    long privateTenantId = required(association.get("legacy_game_session_tenant_id", Long.class));
    if (!expectedOperationId.equals(associationOperationId)
        || !expectedNamespace.equals(association.get("target_namespace", String.class))
        || !expectedSource.requestId().equals(association.get("association_request_id", UUID.class))
        || !Integer.valueOf(expectedSource.schemaVersion())
            .equals(association.get("source_schema_version", Integer.class))
        || !expectedNamespace.equals(association.get("source_target_namespace", String.class))
        || !expectedSource.requestId().equals(association.get("source_request_id", UUID.class))
        || !expectedSource
            .canonicalTenantId()
            .equals(association.get("source_canonical_tenant_id", UUID.class))
        || !expectedSource
            .canonicalTenantId()
            .equals(association.get("canonical_tenant_id", UUID.class))
        || !Objects.equals(
            expectedSource.sourceGameRowId(), association.get("source_game_row_id", Long.class))
        || !expectedSource
            .sourceGameTenantKey()
            .equals(association.get("source_game_tenant_key", String.class))
        || !expectedSource.provenanceKind().equals(association.get("provenance_kind", String.class))
        || !FRESH_KIND.equals(association.get("association_kind", String.class))) {
      throw new FreshTenantAssociationConflictException(
          "Association operation ID is already bound to different source or namespace evidence");
    }
    return new FreshGameSessionTenantAssociation(
        associationOperationId, privateTenantId, expectedSource);
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Fresh tenant association requires a writable owner transaction");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Fresh tenant association requires writable READ COMMITTED isolation");
          }
          return null;
        });
    Record transaction =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('transaction_read_only') AS read_only");
    if (transaction == null
        || !"read committed".equals(transaction.get("isolation", String.class))
        || !"off".equals(transaction.get("read_only", String.class))) {
      throw new IllegalStateException(
          "Fresh tenant association requires a writable READ COMMITTED owner transaction");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static <T> T required(T value) {
    return Objects.requireNonNull(value, "Persisted Game Session owner evidence is incomplete");
  }

  public static final class InvalidFreshTenantSourceException extends IllegalStateException {
    public InvalidFreshTenantSourceException(String message) {
      super(message);
    }

    public InvalidFreshTenantSourceException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class FreshTenantAssociationConflictException extends IllegalStateException {
    public FreshTenantAssociationConflictException(String message) {
      super(message);
    }
  }
}
