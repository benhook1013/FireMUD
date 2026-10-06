package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit owner repository for immutable fresh source identity and a Game Session-private key. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "The explicit owner boundary validates trusted collaborators and performs no I/O while"
            + " constructing; this repository is deliberately not a Spring bean.")
public class FreshGameSessionTenantAssociationRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String RESERVATION_TABLE = "game_session_tenant_scope_reservation";
  private static final String CLAIM_TABLE = "game_session_tenant_canonical_claim";
  private static final String ASSOCIATION_TABLE = "game_session_fresh_tenant_association";

  private final DSLContext dsl;
  private final String workloadNamespace;
  private final TransactionTemplate ownerTransaction;
  private final TransactionTemplate committedRead;

  public FreshGameSessionTenantAssociationRepository(
      DSLContext dsl, PlatformTransactionManager transactionManager, String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    Objects.requireNonNull(transactionManager, "transactionManager must not be null");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.committedRead = new TransactionTemplate(transactionManager);
    this.committedRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    this.committedRead.setReadOnly(true);
  }

  /** Commits one exact source identity mapping, then verifies it in a separate committed read. */
  public FreshGameSessionTenantAssociation registerAndReadback(
      RuntimeTenantIdentityEvidence sourceEvidence) {
    Objects.requireNonNull(sourceEvidence, "sourceEvidence");
    requireOutsideTransaction();
    requireFreshEvidence(sourceEvidence);

    FreshGameSessionTenantAssociation committed;
    try {
      committed = ownerTransaction.execute(status -> registerInTransaction(sourceEvidence));
    } catch (FreshTenantAssociationConflictException conflict) {
      Optional<FreshGameSessionTenantAssociation> racedOutcome =
          readByRequestCommitted(sourceEvidence.requestId());
      if (racedOutcome.isPresent() && racedOutcome.get().sourceEvidence().equals(sourceEvidence)) {
        return racedOutcome.get();
      }
      throw conflict;
    }
    if (committed == null) {
      throw new IllegalStateException("Fresh Game Session tenant association was not committed");
    }
    return readCommitted(
        committed.associationOperationId(), sourceEvidence.requestId(), sourceEvidence);
  }

  /** Reads one exact committed local mapping without allocation, repair, or source calls. */
  public Optional<FreshGameSessionTenantAssociation> readByRequestCommitted(
      UUID associationRequestId) {
    requireOutsideTransaction();
    requireNonNil(associationRequestId, "associationRequestId");
    return committedRead.execute(
        status -> {
          Record row =
              dsl.fetchOne(
                  "SELECT association_operation_id FROM "
                      + ASSOCIATION_TABLE
                      + " WHERE target_namespace = ? AND association_request_id = ?",
                  workloadNamespace,
                  associationRequestId);
          if (row == null) {
            return Optional.empty();
          }
          return Optional.of(
              readByOperationInTransaction(row.get("association_operation_id", UUID.class)));
        });
  }

  private FreshGameSessionTenantAssociation registerInTransaction(
      RuntimeTenantIdentityEvidence evidence) {
    requireWritableReadCommittedOwnerTransaction();
    FreshGameSessionTenantAssociation exactRetry =
        findByRequestInTransaction(evidence.requestId()).orElse(null);
    if (exactRetry != null) {
      if (!exactRetry.sourceEvidence().equals(evidence)) {
        throw new FreshTenantAssociationConflictException(
            "Fresh tenant association request was reused with changed source identity");
      }
      return exactRetry;
    }

    acquireRequestFence(evidence.requestId());
    exactRetry = findByRequestInTransaction(evidence.requestId()).orElse(null);
    if (exactRetry != null) {
      if (!exactRetry.sourceEvidence().equals(evidence)) {
        throw new FreshTenantAssociationConflictException(
            "Fresh tenant association request was reused with changed source identity");
      }
      return exactRetry;
    }

    UUID associationOperationId = UUID.randomUUID();
    long privateTenantId = allocateFreshReservation(evidence);
    int claimInserted =
        dsl.execute(
            "INSERT INTO "
                + CLAIM_TABLE
                + " (target_namespace, canonical_tenant_id, legacy_game_session_tenant_id, "
                + "association_kind, reservation_kind, association_operation_id, "
                + "association_request_id) "
                + "VALUES (?, ?, ?, 'FRESH_SOURCE_BOUND', 'FRESH_SOURCE_BOUND', ?, ?) "
                + "ON CONFLICT DO NOTHING",
            workloadNamespace,
            evidence.canonicalTenantId(),
            privateTenantId,
            associationOperationId,
            evidence.requestId());
    if (claimInserted != 1) {
      throw new FreshTenantAssociationConflictException(
          "Canonical tenant already has a Game Session owner mapping");
    }

    int associationInserted =
        dsl.execute(
            "INSERT INTO "
                + ASSOCIATION_TABLE
                + " (association_operation_id, target_namespace, association_request_id, "
                + "source_schema_version, source_target_namespace, source_request_id, "
                + "source_canonical_tenant_id, canonical_tenant_id, "
                + "legacy_game_session_tenant_id, source_game_row_id, "
                + "source_game_tenant_key, provenance_kind, association_kind) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FRESH_SOURCE_BOUND') "
                + "ON CONFLICT DO NOTHING",
            associationOperationId,
            workloadNamespace,
            evidence.requestId(),
            evidence.schemaVersion(),
            evidence.targetNamespace(),
            evidence.requestId(),
            evidence.canonicalTenantId(),
            evidence.canonicalTenantId(),
            privateTenantId,
            evidence.sourceGameRowId(),
            evidence.sourceGameTenantKey(),
            evidence.provenanceKind());
    if (associationInserted != 1) {
      throw new FreshTenantAssociationConflictException(
          "Fresh tenant request or source identity conflicts with an existing mapping");
    }
    return new FreshGameSessionTenantAssociation(associationOperationId, privateTenantId, evidence);
  }

  private long allocateFreshReservation(RuntimeTenantIdentityEvidence evidence) {
    Long numericSourceTenantKey = numericSourceTenantKey(evidence.sourceGameTenantKey());
    for (int attempt = 0; attempt < 1024; attempt++) {
      Record reserved =
          dsl.fetchOne(
              "SELECT nextval(pg_get_serial_sequence('"
                  + RESERVATION_TABLE
                  + "', 'game_session_tenant_id')) AS candidate");
      Long candidate = reserved == null ? null : reserved.get("candidate", Long.class);
      if (candidate == null || candidate <= 0) {
        throw new IllegalStateException("Game Session tenant reservation returned an invalid key");
      }
      if (candidate == evidence.sourceGameRowId() || candidate.equals(numericSourceTenantKey)) {
        continue;
      }
      int inserted =
          dsl.execute(
              "INSERT INTO "
                  + RESERVATION_TABLE
                  + " (game_session_tenant_id, reservation_kind) "
                  + "VALUES (?, 'FRESH_SOURCE_BOUND') "
                  + "ON CONFLICT (game_session_tenant_id) DO NOTHING",
              candidate);
      if (inserted == 1) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Game Session tenant reservation sequence repeatedly collided with occupied scopes");
  }

  private static Long numericSourceTenantKey(String sourceGameTenantKey) {
    if (sourceGameTenantKey == null
        || sourceGameTenantKey.length() > 19
        || !sourceGameTenantKey.matches("[1-9][0-9]*")) {
      return null;
    }
    try {
      return Long.valueOf(sourceGameTenantKey);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private void acquireRequestFence(UUID associationRequestId) {
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ?, 0))",
        workloadNamespace,
        associationRequestId.toString());
  }

  private Optional<FreshGameSessionTenantAssociation> findByRequestInTransaction(
      UUID associationRequestId) {
    Record row =
        dsl.fetchOne(
            "SELECT association_operation_id FROM "
                + ASSOCIATION_TABLE
                + " WHERE target_namespace = ? AND association_request_id = ?",
            workloadNamespace,
            associationRequestId);
    return row == null
        ? Optional.empty()
        : Optional.of(
            readByOperationInTransaction(row.get("association_operation_id", UUID.class)));
  }

  private FreshGameSessionTenantAssociation readCommitted(
      UUID operationId, UUID requestId, RuntimeTenantIdentityEvidence expectedEvidence) {
    requireOutsideTransaction();
    requireNonNil(operationId, "operationId");
    requireNonNil(requestId, "requestId");
    return committedRead.execute(
        status -> {
          FreshGameSessionTenantAssociation association = readByOperationInTransaction(operationId);
          if (!association.sourceEvidence().equals(expectedEvidence)
              || !association.sourceEvidence().requestId().equals(requestId)) {
            throw new IllegalStateException(
                "Committed fresh tenant association contradicts its exact source identity");
          }
          return association;
        });
  }

  private FreshGameSessionTenantAssociation readByOperationInTransaction(UUID operationId) {
    Record row =
        dsl.fetchOne(
            "SELECT association.association_operation_id, association.association_kind, "
                + "association.association_request_id, association.source_schema_version, "
                + "association.source_target_namespace, association.source_request_id, "
                + "association.source_canonical_tenant_id, association.canonical_tenant_id, "
                + "association.legacy_game_session_tenant_id, association.source_game_row_id, "
                + "association.source_game_tenant_key, association.provenance_kind, "
                + "reservation.reservation_kind, claim.association_kind AS claim_kind, "
                + "claim.association_operation_id AS claim_operation_id, "
                + "claim.association_request_id AS claim_request_id, "
                + "claim.legacy_game_session_tenant_id AS claim_tenant_id "
                + "FROM "
                + ASSOCIATION_TABLE
                + " association JOIN "
                + RESERVATION_TABLE
                + " reservation ON reservation.game_session_tenant_id = "
                + "association.legacy_game_session_tenant_id JOIN "
                + CLAIM_TABLE
                + " claim ON claim.target_namespace = association.target_namespace "
                + "AND claim.canonical_tenant_id = association.canonical_tenant_id "
                + "AND claim.legacy_game_session_tenant_id = "
                + "association.legacy_game_session_tenant_id "
                + "AND claim.association_operation_id = association.association_operation_id "
                + "AND claim.association_kind = association.association_kind "
                + "WHERE association.target_namespace = ? "
                + "AND association.association_operation_id = ?",
            workloadNamespace,
            operationId);
    if (row == null) {
      throw new IllegalStateException("Committed fresh tenant association readback is missing");
    }

    try {
      UUID storedOperationId = row.get("association_operation_id", UUID.class);
      UUID requestId = row.get("association_request_id", UUID.class);
      long privateTenantId = row.get("legacy_game_session_tenant_id", Long.class);
      if (!operationId.equals(storedOperationId)
          || privateTenantId <= 0
          || !requestId.equals(row.get("source_request_id", UUID.class))
          || !requestId.equals(row.get("claim_request_id", UUID.class))
          || !row.get("canonical_tenant_id", UUID.class)
              .equals(row.get("source_canonical_tenant_id", UUID.class))
          || privateTenantId != row.get("claim_tenant_id", Long.class)
          || !"FRESH_SOURCE_BOUND".equals(row.get("association_kind", String.class))
          || !"FRESH_SOURCE_BOUND".equals(row.get("reservation_kind", String.class))
          || !"FRESH_SOURCE_BOUND".equals(row.get("claim_kind", String.class))
          || !operationId.equals(row.get("claim_operation_id", UUID.class))) {
        throw new IllegalStateException(
            "Committed fresh tenant association owner fence is invalid");
      }
      RuntimeTenantIdentityEvidence evidence =
          new RuntimeTenantIdentityEvidence(
              row.get("source_schema_version", Integer.class),
              row.get("source_target_namespace", String.class),
              row.get("source_request_id", UUID.class),
              row.get("source_canonical_tenant_id", UUID.class),
              row.get("source_game_row_id", Long.class),
              row.get("source_game_tenant_key", String.class),
              row.get("provenance_kind", String.class));
      if (!workloadNamespace.equals(evidence.targetNamespace())
          || !requestId.equals(evidence.requestId())
          || !"NEW_GAME_ROW".equals(evidence.provenanceKind())) {
        throw new IllegalStateException("Committed fresh tenant source identity is invalid");
      }
      return new FreshGameSessionTenantAssociation(storedOperationId, privateTenantId, evidence);
    } catch (RuntimeException exception) {
      if (exception instanceof IllegalStateException) {
        throw exception;
      }
      throw new IllegalStateException("Committed fresh tenant association is invalid", exception);
    }
  }

  private void requireFreshEvidence(RuntimeTenantIdentityEvidence evidence) {
    if (!workloadNamespace.equals(evidence.targetNamespace())
        || !"NEW_GAME_ROW".equals(evidence.provenanceKind())) {
      throw new IllegalArgumentException(
          "Game Design identity must target this namespace and prove NEW_GAME_ROW provenance");
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Fresh tenant association requires a writable owner transaction");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equals(settings.get("transaction_isolation", String.class))
        || !"off".equals(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "Fresh tenant association requires writable READ COMMITTED isolation");
    }
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh tenant source lookup and committed readback must run outside owner transactions");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  public static class FreshTenantAssociationConflictException extends RuntimeException {
    public FreshTenantAssociationConflictException(String message) {
      super(message);
    }
  }
}
