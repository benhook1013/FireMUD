package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Account-owned exact retained-row verification and immutable association readback. */
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
  public ApprovedAssociation importApproved(
      long requestedLegacyTenantId, ResolveLegacyAccountTenantAssociationResponse ownerResponse) {
    ApprovedAssociation expected = requireExactResponse(requestedLegacyTenantId, ownerResponse);
    String actualEvidenceDigest = sourceEvidence.digest(requestedLegacyTenantId);
    if (!actualEvidenceDigest.equals(expected.accountEvidenceDigest())) {
      throw new IllegalStateException(
          "approved Account source evidence differs from retained rows");
    }
    Optional<ApprovedAssociation> existing = findByLegacyTenantId(requestedLegacyTenantId);
    if (existing.isPresent()) {
      if (!existing.orElseThrow().equals(expected)) {
        throw new IllegalStateException(
            "approved Account tenant association conflicts with readback");
      }
      return existing.orElseThrow();
    }

    dsl.execute(
        "INSERT INTO account_approved_legacy_tenant_associations "
            + "(legacy_tenant_id, canonical_tenant_id, source_legacy_game_tenant_id, "
            + "source_game_row_id, account_evidence_digest, operation_id, manifest_digest, "
            + "manifest_signature, target_namespace, signer_key_id, approved_by, "
            + "approval_reference, signed_at, operation_entry_count, manifest_schema_version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (legacy_tenant_id) DO NOTHING",
        expected.legacyTenantId(),
        expected.canonicalTenantId(),
        expected.sourceLegacyGameTenantId(),
        expected.sourceGameRowId(),
        expected.accountEvidenceDigest(),
        expected.operationId(),
        expected.manifestDigest(),
        expected.manifestSignature(),
        expected.targetNamespace(),
        expected.signerKeyId(),
        expected.approvedBy(),
        expected.approvalReference(),
        expected.signedAt(),
        expected.operationEntryCount(),
        expected.manifestSchemaVersion());
    ApprovedAssociation committed =
        findByLegacyTenantId(requestedLegacyTenantId)
            .orElseThrow(
                () -> new IllegalStateException("approved association readback is absent"));
    if (!committed.equals(expected)) {
      throw new IllegalStateException("approved association readback differs");
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
                + "source_game_row_id, account_evidence_digest, operation_id, manifest_digest, "
                + "manifest_signature, target_namespace, signer_key_id, approved_by, "
                + "approval_reference, signed_at, operation_entry_count, "
                + "manifest_schema_version "
                + "FROM account_approved_legacy_tenant_associations WHERE legacy_tenant_id = ?",
            legacyTenantId);
    return Optional.ofNullable(row)
        .map(
            result ->
                new ApprovedAssociation(
                    result.get(0, Long.class),
                    result.get(1, UUID.class),
                    result.get(2, String.class),
                    result.get(3, Long.class),
                    result.get(4, String.class),
                    result.get(5, UUID.class),
                    result.get(6, String.class),
                    result.get(7, String.class),
                    result.get(8, String.class),
                    result.get(9, String.class),
                    result.get(10, String.class),
                    result.get(11, String.class),
                    result.get(12, String.class),
                    result.get(13, Integer.class),
                    result.get(14, Integer.class)));
  }

  private ApprovedAssociation requireExactResponse(
      long requestedLegacyTenantId, ResolveLegacyAccountTenantAssociationResponse response) {
    Objects.requireNonNull(response, "Game Design owner response is required");
    if (requestedLegacyTenantId <= 0
        || response.getLegacyAccountTenantId() != requestedLegacyTenantId
        || workloadNamespace == null
        || workloadNamespace.isBlank()
        || !workloadNamespace.equals(response.getTargetNamespace())
        || response.getSourceLegacyGameTenantId().isBlank()
        || response.getSourceLegacyGameTenantId().length() > 36
        || response.getSourceGameRowId() <= 0
        || !digest(response.getAccountEvidenceDigest())
        || !digest(response.getManifestDigest())
        || response.getSignerKeyId().isBlank()
        || response.getApprovedBy().isBlank()
        || response.getApprovalReference().isBlank()
        || response.getOperationEntryCount() <= 0
        || response.getManifestSchemaVersion() != 1) {
      throw new IllegalArgumentException(
          "Game Design owner association is incomplete or mismatched");
    }
    try {
      UUID canonicalTenantId = UUID.fromString(response.getCanonicalTenantId());
      UUID operationId = UUID.fromString(response.getOperationId());
      Instant.parse(response.getSignedAt());
      byte[] signature = Base64.getDecoder().decode(response.getManifestSignature());
      if (signature.length != 64
          || !Base64.getEncoder()
              .encodeToString(signature)
              .equals(response.getManifestSignature())) {
        throw new IllegalArgumentException("owner manifest signature has the wrong length");
      }
      return new ApprovedAssociation(
          requestedLegacyTenantId,
          canonicalTenantId,
          response.getSourceLegacyGameTenantId(),
          response.getSourceGameRowId(),
          response.getAccountEvidenceDigest(),
          operationId,
          response.getManifestDigest(),
          response.getManifestSignature(),
          response.getTargetNamespace(),
          response.getSignerKeyId(),
          response.getApprovedBy(),
          response.getApprovalReference(),
          response.getSignedAt(),
          response.getOperationEntryCount(),
          response.getManifestSchemaVersion());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "Game Design owner association has invalid identity evidence", ex);
    }
  }

  private static boolean digest(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  public record ApprovedAssociation(
      long legacyTenantId,
      UUID canonicalTenantId,
      String sourceLegacyGameTenantId,
      long sourceGameRowId,
      String accountEvidenceDigest,
      UUID operationId,
      String manifestDigest,
      String manifestSignature,
      String targetNamespace,
      String signerKeyId,
      String approvedBy,
      String approvalReference,
      String signedAt,
      int operationEntryCount,
      int manifestSchemaVersion) {}
}
