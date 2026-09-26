package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest.Entry;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest.Signed;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifestVerifier;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifestVerifier.Verified;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Guarded, immutable Game Design owner assertion for an approved legacy tenant manifest. */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring transaction collaborator.")
public class TenantAssociationMigrationService {
  private final DSLContext dsl;
  private final GameRepository gameRepository;

  public TenantAssociationMigrationService(DSLContext dsl, GameRepository gameRepository) {
    this.dsl = dsl;
    this.gameRepository = gameRepository;
  }

  @Transactional
  public List<ApprovedAssociation> apply(
      Signed signed, Map<String, String> trustedPublicKeys, String exactNamespace) {
    Verified verified = TenantAssociationManifestVerifier.verify(signed, trustedPublicKeys);
    TenantAssociationManifest manifest = Objects.requireNonNull(verified).manifest();
    if (exactNamespace == null || !exactNamespace.equals(manifest.targetNamespace())) {
      throw new IllegalArgumentException("manifest targets a different namespace");
    }
    List<ApprovedAssociation> existing = findByOperation(manifest.operationId());
    if (!existing.isEmpty() || operationExists(manifest.operationId())) {
      assertExactReadback(verified, signed.ed25519Signature(), existing);
      return existing;
    }

    for (Entry entry : manifest.entries()) {
      requireExactSource(
          entry.legacyGameTenantId(), entry.canonicalTenantId(), entry.sourceGameRowId());
    }

    int inserted =
        dsl.execute(
            "INSERT INTO legacy_account_tenant_association_operations "
                + "(operation_id, manifest_digest, signature, target_namespace, signer_key_id, approved_by, "
                + "approval_reference, signed_at, entry_count, schema_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (operation_id) DO NOTHING",
            manifest.operationId(),
            verified.manifestDigest(),
            signed.ed25519Signature(),
            manifest.targetNamespace(),
            manifest.signerKeyId(),
            manifest.approvedBy(),
            manifest.approvalReference(),
            manifest.signedAt().toString(),
            manifest.entries().size(),
            manifest.schemaVersion());
    if (inserted == 0) {
      List<ApprovedAssociation> concurrent = findByOperation(manifest.operationId());
      assertExactReadback(verified, signed.ed25519Signature(), concurrent);
      return concurrent;
    }
    for (Entry entry : manifest.entries()) {
      dsl.execute(
          "INSERT INTO legacy_account_tenant_associations "
              + "(legacy_account_tenant_id, legacy_game_tenant_id, canonical_tenant_id, "
              + "source_game_row_id, account_evidence_digest, operation_id) "
              + "VALUES (?, ?, ?, ?, ?, ?)",
          entry.legacyAccountTenantId(),
          entry.legacyGameTenantId(),
          entry.canonicalTenantId(),
          entry.sourceGameRowId(),
          entry.accountEvidenceDigest(),
          manifest.operationId());
    }
    List<ApprovedAssociation> committed = findByOperation(manifest.operationId());
    assertExactReadback(verified, signed.ed25519Signature(), committed);
    return committed;
  }

  @Transactional(readOnly = true)
  public Optional<ApprovedAssociation> findByLegacyAccountTenantId(long legacyAccountTenantId) {
    if (legacyAccountTenantId <= 0) {
      return Optional.empty();
    }
    Record row =
        dsl.fetchOne(
            "SELECT a.legacy_account_tenant_id, a.legacy_game_tenant_id, "
                + "a.canonical_tenant_id, a.source_game_row_id, a.account_evidence_digest, "
                + "o.operation_id, o.manifest_digest, o.signature, o.target_namespace, o.signer_key_id, "
                + "o.approved_by, o.approval_reference, o.signed_at, o.entry_count, "
                + "o.schema_version "
                + "FROM legacy_account_tenant_associations a "
                + "JOIN legacy_account_tenant_association_operations o "
                + "ON o.operation_id = a.operation_id "
                + "WHERE a.legacy_account_tenant_id = ?",
            legacyAccountTenantId);
    return Optional.ofNullable(row)
        .map(this::map)
        .map(
            association -> {
              requireExactSource(
                  association.legacyGameTenantId(),
                  association.canonicalTenantId(),
                  association.sourceGameRowId());
              return association;
            });
  }

  private void requireExactSource(String legacyGameTenantId, UUID canonicalTenantId, long gameId) {
    GameTenantIdentity identity =
        gameRepository
            .findTenantIdentityByLegacyTenantId(legacyGameTenantId)
            .orElseThrow(() -> new IllegalStateException("manifest Game Design source is absent"));
    if (!canonicalTenantId.equals(identity.canonicalTenantId())
        || !legacyGameTenantId.equals(identity.sourceLegacyTenantId())
        || !Long.valueOf(gameId).equals(identity.sourceGameId())) {
      throw new IllegalStateException("manifest Game Design source identity changed");
    }
  }

  private boolean operationExists(UUID operationId) {
    return Boolean.TRUE.equals(
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT EXISTS (SELECT 1 FROM legacy_account_tenant_association_operations "
                        + "WHERE operation_id = ?)",
                    operationId))
            .get(0, Boolean.class));
  }

  private List<ApprovedAssociation> findByOperation(UUID operationId) {
    List<ApprovedAssociation> result = new ArrayList<>();
    for (Record row :
        dsl.fetch(
            "SELECT a.legacy_account_tenant_id, a.legacy_game_tenant_id, "
                + "a.canonical_tenant_id, a.source_game_row_id, a.account_evidence_digest, "
                + "o.operation_id, o.manifest_digest, o.signature, o.target_namespace, o.signer_key_id, "
                + "o.approved_by, o.approval_reference, o.signed_at, o.entry_count, "
                + "o.schema_version "
                + "FROM legacy_account_tenant_associations a "
                + "JOIN legacy_account_tenant_association_operations o "
                + "ON o.operation_id = a.operation_id "
                + "WHERE o.operation_id = ? ORDER BY a.legacy_account_tenant_id",
            operationId)) {
      result.add(map(row));
    }
    return List.copyOf(result);
  }

  private ApprovedAssociation map(Record row) {
    return new ApprovedAssociation(
        row.get(0, Long.class),
        row.get(1, String.class),
        row.get(2, UUID.class),
        row.get(3, Long.class),
        row.get(4, String.class),
        row.get(5, UUID.class),
        row.get(6, String.class),
        row.get(7, String.class),
        row.get(8, String.class),
        row.get(9, String.class),
        row.get(10, String.class),
        row.get(11, String.class),
        row.get(12, String.class),
        row.get(13, Integer.class),
        row.get(14, Integer.class));
  }

  private void assertExactReadback(
      Verified verified, String signature, List<ApprovedAssociation> rows) {
    TenantAssociationManifest manifest = verified.manifest();
    if (rows.size() != manifest.entries().size()) {
      throw new IllegalStateException("manifest operation readback count differs");
    }
    for (int index = 0; index < rows.size(); index++) {
      Entry expected = manifest.entries().get(index);
      ApprovedAssociation actual = rows.get(index);
      if (actual.legacyAccountTenantId() != expected.legacyAccountTenantId()
          || !actual.legacyGameTenantId().equals(expected.legacyGameTenantId())
          || !actual.canonicalTenantId().equals(expected.canonicalTenantId())
          || actual.sourceGameRowId() != expected.sourceGameRowId()
          || !actual.accountEvidenceDigest().equals(expected.accountEvidenceDigest())
          || !actual.operationId().equals(manifest.operationId())
          || !actual.manifestDigest().equals(verified.manifestDigest())
          || !actual.signature().equals(signature)
          || !actual.targetNamespace().equals(manifest.targetNamespace())
          || !actual.signerKeyId().equals(manifest.signerKeyId())
          || !actual.approvedBy().equals(manifest.approvedBy())
          || !actual.approvalReference().equals(manifest.approvalReference())
          || !actual.signedAt().equals(manifest.signedAt().toString())
          || actual.entryCount() != manifest.entries().size()
          || actual.schemaVersion() != manifest.schemaVersion()) {
        throw new IllegalStateException("manifest operation readback differs");
      }
      requireExactSource(
          actual.legacyGameTenantId(), actual.canonicalTenantId(), actual.sourceGameRowId());
    }
  }

  public record ApprovedAssociation(
      long legacyAccountTenantId,
      String legacyGameTenantId,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String accountEvidenceDigest,
      UUID operationId,
      String manifestDigest,
      String signature,
      String targetNamespace,
      String signerKeyId,
      String approvedBy,
      String approvalReference,
      String signedAt,
      int entryCount,
      int schemaVersion) {}
}
