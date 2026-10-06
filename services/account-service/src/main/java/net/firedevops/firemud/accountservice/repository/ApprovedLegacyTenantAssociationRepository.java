package net.firedevops.firemud.accountservice.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.OwnerApprovedAccountTenantAssociation;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Account-owned exact source verification and immutable tenant-identity association readback. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring transaction collaborator.")
public class ApprovedLegacyTenantAssociationRepository {
  private final DSLContext dsl;
  private final LegacyTenantSourceEvidence sourceEvidence;
  private final String workloadNamespace;

  public ApprovedLegacyTenantAssociationRepository(
      DSLContext dsl,
      LegacyTenantSourceEvidence sourceEvidence,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.dsl = dsl;
    this.sourceEvidence = sourceEvidence;
    this.workloadNamespace = workloadNamespace;
  }

  @Transactional(isolation = Isolation.SERIALIZABLE)
  public ApprovedAssociation importOwnerApproved(
      long requestedLegacyTenantId, OwnerApprovedAccountTenantAssociation owner) {
    OwnerApprovedAccountTenantAssociation expected =
        requireExactOwnerEvidence(requestedLegacyTenantId, owner);
    LegacyTenantSourceEvidence.SourceProjection projection =
        sourceEvidence.projection(requestedLegacyTenantId);
    if (!projection.digest().equals(expected.accountEvidenceDigest())
        || !projection.capturedAt().equals(expected.sourceCapturedAt())) {
      throw new IllegalStateException(
          "approved Account source evidence differs from retained rows");
    }
    if (projection.capturedAt().isAfter(Instant.now())) {
      throw new IllegalStateException("approved Account source capture is future-dated");
    }
    Instant expiresAt = projection.capturedAt().plus(java.time.Duration.ofDays(30));
    if (!Instant.now().isBefore(expiresAt)) {
      throw new IllegalStateException("approved Account source evidence has expired");
    }

    Optional<ApprovedAssociation> found = findByLegacyTenantId(requestedLegacyTenantId);
    if (found.isPresent()) {
      ApprovedAssociation existing = found.orElseThrow();
      if (!existing.sameClaim(expected)) {
        throw new IllegalStateException(
            "approved Account tenant association conflicts with readback");
      }
      if (!payloadMatches(expected)) {
        throw new IllegalStateException(
            "approved Account association payload is unavailable or conflicts with replay evidence");
      }
      return existing;
    }

    if (hasConflictingClaim(expected)) {
      throw new IllegalStateException(
          "approved Account tenant association conflicts with another claim");
    }

    dsl.execute(
        "INSERT INTO account_approved_legacy_tenant_associations "
            + "(legacy_tenant_id, canonical_tenant_id, source_legacy_game_tenant_id, "
            + "source_game_row_id, operation_id, target_namespace, source_captured_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE))",
        expected.legacyAccountTenantId(),
        expected.canonicalTenantId(),
        expected.sourceLegacyGameTenantId(),
        expected.sourceGameRowId(),
        expected.operationId(),
        expected.targetNamespace(),
        expected.sourceCapturedAt().atOffset(java.time.ZoneOffset.UTC));
    dsl.execute(
        "INSERT INTO account_approved_legacy_tenant_association_payload "
            + "(operation_id, account_evidence_digest, manifest_digest, signer_key_id, "
            + "approved_by, approval_reference, signed_at, manifest_signature, "
            + "operation_entry_count, manifest_schema_version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        expected.operationId(),
        expected.accountEvidenceDigest(),
        expected.manifestDigest(),
        expected.signerKeyId(),
        expected.approvedBy(),
        expected.approvalReference(),
        expected.signedAt(),
        expected.manifestSignature(),
        expected.operationEntryCount(),
        expected.manifestSchemaVersion());

    ApprovedAssociation committed =
        findByLegacyTenantId(requestedLegacyTenantId)
            .orElseThrow(
                () -> new IllegalStateException("approved Account association readback is absent"));
    if (!committed.sameClaim(expected) || !payloadMatches(expected)) {
      throw new IllegalStateException("approved Account association readback differs");
    }
    return committed;
  }

  @Transactional(readOnly = true)
  public Optional<ApprovedAssociation> findByLegacyTenantId(long legacyTenantId) {
    if (legacyTenantId <= 0) {
      return Optional.empty();
    }
    Record row =
        dsl.fetchOne(
            "SELECT legacy_tenant_id, canonical_tenant_id, source_legacy_game_tenant_id, "
                + "source_game_row_id, operation_id, target_namespace, source_captured_at, "
                + "terminal_outcome "
                + "FROM account_approved_legacy_tenant_associations WHERE legacy_tenant_id = ?",
            legacyTenantId);
    return Optional.ofNullable(row).map(this::toAssociation);
  }

  @Transactional(readOnly = true)
  public Optional<ApprovedAssociation> findByCanonicalTenantId(UUID canonicalTenantId) {
    if (canonicalTenantId == null || canonicalTenantId.equals(new UUID(0L, 0L))) {
      return Optional.empty();
    }
    Record row =
        dsl.fetchOne(
            "SELECT legacy_tenant_id, canonical_tenant_id, source_legacy_game_tenant_id, "
                + "source_game_row_id, operation_id, target_namespace, source_captured_at, "
                + "terminal_outcome "
                + "FROM account_approved_legacy_tenant_associations WHERE canonical_tenant_id = ?",
            canonicalTenantId);
    return Optional.ofNullable(row).map(this::toAssociation);
  }

  /** Deletes only payload whose immutable association capture time is at least 30 days old. */
  @Transactional
  public int deleteExpiredApprovalPayloads(int batchSize) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("positive payload cleanup batch size is required");
    }
    return dsl.execute(
        "WITH expired AS (SELECT payload.operation_id "
            + "FROM account_approved_legacy_tenant_association_payload payload "
            + "JOIN account_approved_legacy_tenant_associations claim USING (operation_id) "
            + "WHERE claim.source_captured_at <= clock_timestamp() - INTERVAL '30 days' "
            + "AND NOT EXISTS (SELECT 1 FROM account_tenant_membership membership "
            + "WHERE membership.approved_tenant_payload_operation_id = payload.operation_id) "
            + "AND NOT EXISTS (SELECT 1 FROM account_connect_scope_records scope "
            + "WHERE scope.approved_tenant_payload_operation_id = payload.operation_id) "
            + "ORDER BY claim.source_captured_at, payload.operation_id "
            + "LIMIT ? FOR UPDATE OF payload SKIP LOCKED) "
            + "DELETE FROM account_approved_legacy_tenant_association_payload payload "
            + "USING expired WHERE payload.operation_id = expired.operation_id",
        batchSize);
  }

  private boolean payloadMatches(OwnerApprovedAccountTenantAssociation expected) {
    Record row =
        dsl.fetchOne(
            "SELECT account_evidence_digest, manifest_digest, signer_key_id, approved_by, "
                + "approval_reference, signed_at, manifest_signature, operation_entry_count, "
                + "manifest_schema_version "
                + "FROM account_approved_legacy_tenant_association_payload WHERE operation_id = ?",
            expected.operationId());
    return row != null
        && Objects.equals(row.get(0, String.class), expected.accountEvidenceDigest())
        && Objects.equals(row.get(1, String.class), expected.manifestDigest())
        && Objects.equals(row.get(2, String.class), expected.signerKeyId())
        && Objects.equals(row.get(3, String.class), expected.approvedBy())
        && Objects.equals(row.get(4, String.class), expected.approvalReference())
        && Objects.equals(row.get(5, String.class), expected.signedAt())
        && Objects.equals(row.get(6, String.class), expected.manifestSignature())
        && Objects.equals(row.get(7, Integer.class), expected.operationEntryCount())
        && Objects.equals(row.get(8, Integer.class), expected.manifestSchemaVersion());
  }

  private ApprovedAssociation toAssociation(Record row) {
    return new ApprovedAssociation(
        row.get(0, Long.class),
        row.get(1, UUID.class),
        row.get(2, String.class),
        row.get(3, Long.class),
        row.get(4, UUID.class),
        row.get(5, String.class),
        row.get(6, java.time.OffsetDateTime.class).toInstant(),
        row.get(7, String.class));
  }

  private boolean hasConflictingClaim(OwnerApprovedAccountTenantAssociation expected) {
    Table<?> associations = table(name("account_approved_legacy_tenant_associations"));
    Condition conflictingClaim =
        field(name("legacy_tenant_id"), Long.class)
            .ne(expected.legacyAccountTenantId())
            .and(
                field(name("canonical_tenant_id"), UUID.class)
                    .eq(expected.canonicalTenantId())
                    .or(
                        field(name("source_legacy_game_tenant_id"), String.class)
                            .eq(expected.sourceLegacyGameTenantId()))
                    .or(
                        field(name("source_game_row_id"), Long.class)
                            .eq(expected.sourceGameRowId()))
                    .or(field(name("operation_id"), UUID.class).eq(expected.operationId())));
    return dsl.fetchExists(associations, conflictingClaim);
  }

  private OwnerApprovedAccountTenantAssociation requireExactOwnerEvidence(
      long requestedLegacyTenantId, OwnerApprovedAccountTenantAssociation owner) {
    Objects.requireNonNull(owner, "Game Design owner evidence is required");
    if (requestedLegacyTenantId <= 0
        || owner.legacyAccountTenantId() != requestedLegacyTenantId
        || workloadNamespace == null
        || workloadNamespace.isBlank()
        || !workloadNamespace.equals(owner.targetNamespace())) {
      throw new IllegalArgumentException("Game Design Account tenant evidence is mismatched");
    }
    return owner;
  }

  public record ApprovedAssociation(
      long legacyTenantId,
      UUID canonicalTenantId,
      String sourceLegacyGameTenantId,
      long sourceGameRowId,
      UUID operationId,
      String targetNamespace,
      Instant sourceCapturedAt,
      String terminalOutcome) {
    private boolean sameClaim(OwnerApprovedAccountTenantAssociation owner) {
      return legacyTenantId == owner.legacyAccountTenantId()
          && canonicalTenantId.equals(owner.canonicalTenantId())
          && sourceLegacyGameTenantId.equals(owner.sourceLegacyGameTenantId())
          && sourceGameRowId == owner.sourceGameRowId()
          && operationId.equals(owner.operationId())
          && targetNamespace.equals(owner.targetNamespace())
          && sourceCapturedAt.equals(owner.sourceCapturedAt())
          && "ASSOCIATED".equals(terminalOutcome);
    }
  }
}
