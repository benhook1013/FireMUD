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

/**
 * Persists and reads back Account's immutable association for an authenticated fresh tenant. This
 * primitive does not enroll membership or make the association a runtime selector.
 */
@Repository
public class FreshTenantIdentityAssociationRepository {
  private final DSLContext dsl;
  private final String workloadNamespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The injected owner DSLContext is retained privately, never exposed or copied.")
  public FreshTenantIdentityAssociationRepository(
      DSLContext dsl, @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.dsl = dsl;
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * Stores one exact Game Design operation receipt and returns its immutable Account readback.
   *
   * <p>The caller owns the transaction boundary so association persistence can be composed with the
   * subsequent Account tenant-generation initialization. A Spring proxy's mandatory propagation is
   * supplemented by an explicit active-transaction check for direct construction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public FreshTenantCreationEvidence importVerified(FreshTenantCreationEvidence expected) {
    requireExpectedNamespace(expected);
    requireActiveTransaction();

    int inserted =
        dsl.execute(
            "INSERT INTO account_fresh_tenant_identity_associations "
                + "(schema_version, target_namespace, creation_request_id, operation_id, "
                + "request_digest, canonical_tenant_id, source_game_row_id, "
                + "source_game_tenant_key, provenance_kind, evidence_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            expected.schemaVersion(),
            expected.targetNamespace(),
            expected.creationRequestId(),
            expected.operationId(),
            expected.requestDigest(),
            expected.canonicalTenantId(),
            expected.sourceGameRowId(),
            expected.sourceGameTenantKey(),
            expected.provenanceKind(),
            expected.evidenceDigest());
    if (inserted < 0 || inserted > 1) {
      throw new IllegalStateException("Fresh Account tenant association insert was ambiguous");
    }

    FreshTenantCreationEvidence committed =
        readVerified(expected.canonicalTenantId())
            .orElseThrow(
                () ->
                    new IdentityAssociationConflictException(
                        "Fresh Account tenant identity conflicts with an existing canonical claim"));
    if (!committed.equals(expected)) {
      throw new IdentityAssociationConflictException(
          "Fresh Account tenant association conflicts with exact operation readback");
    }
    return committed;
  }

  /** Reads only a complete fresh association whose canonical UUID claim and source tuple agree. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<FreshTenantCreationEvidence> read(UUID canonicalTenantId) {
    Objects.requireNonNull(canonicalTenantId, "Canonical tenant UUID is required");
    if (canonicalTenantId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Canonical tenant UUID must be non-nil");
    }
    requireWorkloadNamespace();
    requireActiveTransaction();
    return readVerified(canonicalTenantId);
  }

  private Optional<FreshTenantCreationEvidence> readVerified(UUID canonicalTenantId) {
    Record association =
        dsl.fetchOne(
            "SELECT schema_version, target_namespace, creation_request_id, operation_id, "
                + "request_digest, canonical_tenant_id, source_game_row_id, "
                + "source_game_tenant_key, provenance_kind, evidence_digest "
                + "FROM account_fresh_tenant_identity_associations "
                + "WHERE canonical_tenant_id = ?",
            canonicalTenantId);
    Record claim =
        dsl.fetchOne(
            "SELECT canonical_tenant_id, identity_kind, source_operation_id, "
                + "source_account_legacy_tenant_id, "
                + "source_target_namespace, source_creation_request_id, source_request_digest, "
                + "source_game_row_id, source_game_tenant_key, source_provenance_kind, "
                + "source_evidence_digest, source_manifest_digest "
                + "FROM account_canonical_tenant_identity_claims WHERE canonical_tenant_id = ?",
            canonicalTenantId);

    if (association == null) {
      if (claim != null && "FRESH_GAME_DESIGN".equals(claim.get("identity_kind", String.class))) {
        throw new InvalidTenantIdentityEvidenceException(
            "Fresh canonical tenant claim has no Account source association");
      }
      return Optional.empty();
    }

    Integer schemaVersion = association.get("schema_version", Integer.class);
    String targetNamespace = association.get("target_namespace", String.class);
    UUID creationRequestId = association.get("creation_request_id", UUID.class);
    UUID operationId = association.get("operation_id", UUID.class);
    String requestDigest = association.get("request_digest", String.class);
    UUID storedCanonicalTenantId = association.get("canonical_tenant_id", UUID.class);
    Long sourceGameRowId = association.get("source_game_row_id", Long.class);
    String sourceGameTenantKey = association.get("source_game_tenant_key", String.class);
    String provenanceKind = association.get("provenance_kind", String.class);
    String evidenceDigest = association.get("evidence_digest", String.class);
    if (schemaVersion == null
        || targetNamespace == null
        || creationRequestId == null
        || operationId == null
        || requestDigest == null
        || storedCanonicalTenantId == null
        || sourceGameRowId == null
        || sourceGameTenantKey == null
        || provenanceKind == null
        || evidenceDigest == null) {
      throw new InvalidTenantIdentityEvidenceException(
          "Fresh Account association is missing required operation evidence");
    }
    if (claim == null || !claimMatches(association, claim)) {
      throw new InvalidTenantIdentityEvidenceException(
          "Fresh Account association does not match its canonical tenant claim");
    }

    try {
      FreshTenantCreationEvidence evidence =
          new FreshTenantCreationEvidence(
              schemaVersion,
              targetNamespace,
              creationRequestId,
              operationId,
              requestDigest,
              storedCanonicalTenantId,
              sourceGameRowId,
              sourceGameTenantKey,
              provenanceKind,
              evidenceDigest);
      if (!workloadNamespace.equals(evidence.targetNamespace())) {
        throw new InvalidTenantIdentityEvidenceException(
            "Fresh Account association namespace differs from the configured namespace");
      }
      return Optional.of(evidence);
    } catch (IllegalArgumentException exception) {
      throw new InvalidTenantIdentityEvidenceException(
          "Fresh Account association contains invalid operation evidence", exception);
    }
  }

  private boolean claimMatches(Record association, Record claim) {
    return "FRESH_GAME_DESIGN".equals(claim.get("identity_kind", String.class))
        && Objects.equals(
            association.get("canonical_tenant_id", UUID.class),
            claim.get("canonical_tenant_id", UUID.class))
        && Objects.equals(
            association.get("target_namespace", String.class),
            claim.get("source_target_namespace", String.class))
        && Objects.equals(
            association.get("creation_request_id", UUID.class),
            claim.get("source_creation_request_id", UUID.class))
        && Objects.equals(
            association.get("operation_id", UUID.class),
            claim.get("source_operation_id", UUID.class))
        && Objects.equals(
            association.get("request_digest", String.class),
            claim.get("source_request_digest", String.class))
        && Objects.equals(
            association.get("source_game_row_id", Long.class),
            claim.get("source_game_row_id", Long.class))
        && Objects.equals(
            association.get("source_game_tenant_key", String.class),
            claim.get("source_game_tenant_key", String.class))
        && Objects.equals(
            association.get("provenance_kind", String.class),
            claim.get("source_provenance_kind", String.class))
        && Objects.equals(
            association.get("evidence_digest", String.class),
            claim.get("source_evidence_digest", String.class))
        && claim.get("source_account_legacy_tenant_id", Long.class) == null
        && claim.get("source_manifest_digest", String.class) == null;
  }

  private void requireExpectedNamespace(FreshTenantCreationEvidence expected) {
    Objects.requireNonNull(expected, "Game Design creation evidence is required");
    requireWorkloadNamespace();
    if (!workloadNamespace.equals(expected.targetNamespace())) {
      throw new IllegalArgumentException(
          "Game Design creation evidence namespace differs from the configured namespace");
    }
  }

  private void requireWorkloadNamespace() {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalStateException("Account workload namespace is missing or invalid");
    }
  }

  private void requireActiveTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh Account tenant association access requires an active owner transaction");
    }
    if (dsl == null) {
      throw new IllegalStateException("Fresh Account tenant association DSLContext is unavailable");
    }
  }

  /** A conflicting identity/request/operation cannot be imported as a fresh association. */
  public static final class IdentityAssociationConflictException extends IllegalStateException {
    public IdentityAssociationConflictException(String message) {
      super(message);
    }
  }

  /** Stored association, source claim, or receipt evidence is corrupt or incomplete. */
  public static final class InvalidTenantIdentityEvidenceException extends IllegalStateException {
    public InvalidTenantIdentityEvidenceException(String message) {
      super(message);
    }

    public InvalidTenantIdentityEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
