package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Persists and reads one immutable Account association for a fresh Game Design tenant UUID. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Account transaction collaborator.")
public class FreshTenantIdentityAssociationRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private final DSLContext dsl;
  private final String workloadNamespace;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve the injected DSLContext precondition; Spring must proxy this non-final repository.")
  public FreshTenantIdentityAssociationRepository(
      DSLContext dsl, @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl);
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * Imports only exact evidence already authenticated by the same-namespace Game Design owner
   * client. A retry is accepted only when the immutable canonical UUID claim and source row read
   * back as the exact same tuple; conflicting request, operation, source, or UUID claims fail.
   *
   * <p>Callers must perform this insert, exact readback, and any dependent Account generation
   * initialization in one Account transaction. This repository does not perform the owner RPC or
   * create membership, entitlement, grant, actor-assignment, or admission state.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public FreshTenantCreationEvidence importVerified(FreshTenantCreationEvidence ownerEvidence) {
    requireOwnerTransaction();
    requireNamespace();
    Objects.requireNonNull(ownerEvidence, "authenticated Game Design owner evidence is required");
    if (!workloadNamespace.equals(ownerEvidence.targetNamespace())) {
      throw new InvalidTenantIdentityEvidenceException(
          "Fresh Game Design evidence belongs to a different workload namespace");
    }

    int inserted =
        dsl.execute(
            "INSERT INTO account_fresh_tenant_identity_associations ("
                + "schema_version, target_namespace, creation_request_id, operation_id, "
                + "request_digest, canonical_tenant_id, source_game_row_id, "
                + "source_game_tenant_key, provenance_kind, evidence_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            ownerEvidence.schemaVersion(),
            ownerEvidence.targetNamespace(),
            ownerEvidence.creationRequestId(),
            ownerEvidence.operationId(),
            ownerEvidence.requestDigest(),
            ownerEvidence.canonicalTenantId(),
            ownerEvidence.sourceGameRowId(),
            ownerEvidence.sourceGameTenantKey(),
            ownerEvidence.provenanceKind(),
            ownerEvidence.evidenceDigest());
    if (inserted < 0 || inserted > 1) {
      throw new InvalidTenantIdentityEvidenceException(
          "Fresh Account tenant identity import affected an ambiguous row count");
    }

    FreshTenantCreationEvidence readback =
        read(ownerEvidence.canonicalTenantId())
            .orElseThrow(
                () ->
                    new InvalidTenantIdentityEvidenceException(
                        "Fresh Account tenant identity import did not read back"));
    if (!ownerEvidence.equals(readback)) {
      throw new InvalidTenantIdentityEvidenceException(
          "Fresh Account tenant identity import conflicts with immutable stored evidence");
    }
    return readback;
  }

  /**
   * Reads existing source evidence and its canonical UUID claim without enrolling membership or
   * mutating anything. Only the internal enrollment composition imports evidence returned by the
   * authenticated Game Design owner client; this repository does not authenticate caller-supplied
   * evidence by itself.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<FreshTenantCreationEvidence> read(UUID canonicalTenantId) {
    requireOwnerTransaction();
    requireNamespace();
    if (canonicalTenantId == null || NIL_UUID.equals(canonicalTenantId)) {
      throw new IllegalArgumentException("Canonical tenant UUID must be non-nil");
    }

    Record row =
        dsl.fetchOne(
            "SELECT fresh.schema_version, fresh.target_namespace, fresh.creation_request_id, "
                + "fresh.operation_id, fresh.request_digest, fresh.canonical_tenant_id, "
                + "fresh.source_game_row_id, fresh.source_game_tenant_key, "
                + "fresh.provenance_kind, fresh.evidence_digest, "
                + "claim.canonical_tenant_id AS claim_canonical_tenant_id, "
                + "claim.identity_kind AS claim_identity_kind, "
                + "claim.source_operation_id AS claim_source_operation_id, "
                + "claim.source_account_legacy_tenant_id AS claim_legacy_tenant_id, "
                + "claim.source_target_namespace AS claim_target_namespace, "
                + "claim.source_creation_request_id AS claim_creation_request_id, "
                + "claim.source_request_digest AS claim_request_digest, "
                + "claim.source_game_row_id AS claim_game_row_id, "
                + "claim.source_game_tenant_key AS claim_game_tenant_key, "
                + "claim.source_provenance_kind AS claim_provenance_kind, "
                + "claim.source_evidence_digest AS claim_evidence_digest "
                + "FROM account_fresh_tenant_identity_associations fresh "
                + "LEFT JOIN account_canonical_tenant_identity_claims claim "
                + "ON claim.canonical_tenant_id = fresh.canonical_tenant_id "
                + "WHERE fresh.canonical_tenant_id = ?",
            canonicalTenantId);
    if (row == null) {
      if (dsl.fetchOne(
              "SELECT 1 FROM account_canonical_tenant_identity_claims "
                  + "WHERE canonical_tenant_id = ? AND identity_kind = 'FRESH_GAME_DESIGN'",
              canonicalTenantId)
          != null) {
        throw new InvalidTenantIdentityEvidenceException(
            "Fresh canonical tenant claim has no Account source association");
      }
      return Optional.empty();
    }

    try {
      FreshTenantCreationEvidence evidence =
          new FreshTenantCreationEvidence(
              required(row, "schema_version", Integer.class),
              required(row, "target_namespace", String.class),
              required(row, "creation_request_id", UUID.class),
              required(row, "operation_id", UUID.class),
              required(row, "request_digest", String.class),
              required(row, "canonical_tenant_id", UUID.class),
              required(row, "source_game_row_id", Long.class),
              required(row, "source_game_tenant_key", String.class),
              required(row, "provenance_kind", String.class),
              required(row, "evidence_digest", String.class));
      if (!canonicalTenantId.equals(evidence.canonicalTenantId())
          || !workloadNamespace.equals(evidence.targetNamespace())
          || !claimMatches(row, evidence)) {
        throw new InvalidTenantIdentityEvidenceException(
            "Fresh Account association differs from its canonical source claim");
      }
      return Optional.of(evidence);
    } catch (IllegalArgumentException exception) {
      throw new InvalidTenantIdentityEvidenceException(
          "Fresh Account association contains invalid owner evidence", exception);
    }
  }

  private boolean claimMatches(Record row, FreshTenantCreationEvidence evidence) {
    return evidence.canonicalTenantId().equals(row.get("claim_canonical_tenant_id", UUID.class))
        && "FRESH_GAME_DESIGN".equals(row.get("claim_identity_kind", String.class))
        && evidence.operationId().equals(row.get("claim_source_operation_id", UUID.class))
        && row.get("claim_legacy_tenant_id", Long.class) == null
        && evidence.targetNamespace().equals(row.get("claim_target_namespace", String.class))
        && evidence.creationRequestId().equals(row.get("claim_creation_request_id", UUID.class))
        && evidence.requestDigest().equals(row.get("claim_request_digest", String.class))
        && Objects.equals(
            Long.valueOf(evidence.sourceGameRowId()), row.get("claim_game_row_id", Long.class))
        && evidence.sourceGameTenantKey().equals(row.get("claim_game_tenant_key", String.class))
        && evidence.provenanceKind().equals(row.get("claim_provenance_kind", String.class))
        && evidence.evidenceDigest().equals(row.get("claim_evidence_digest", String.class));
  }

  private static <T> T required(Record row, String name, Class<T> type) {
    T value = row.get(name, type);
    if (value == null) {
      throw new InvalidTenantIdentityEvidenceException(
          "Fresh Account association is missing required source evidence: " + name);
    }
    return value;
  }

  private void requireNamespace() {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalStateException("Account workload namespace is missing or invalid");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh Account tenant identity read requires an active owner transaction");
    }
  }

  /** Stored association or canonical UUID claim is incomplete or contradictory. */
  public static final class InvalidTenantIdentityEvidenceException extends IllegalStateException {
    public InvalidTenantIdentityEvidenceException(String message) {
      super(message);
    }

    public InvalidTenantIdentityEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
