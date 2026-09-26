package net.firedevops.firemud.gamedesign.maintenance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One approved, exact legacy Account-key to Game Design tenant association operation. */
public record TenantAssociationManifest(
    int schemaVersion,
    UUID operationId,
    String targetNamespace,
    String signerKeyId,
    String approvedBy,
    String approvalReference,
    Instant signedAt,
    List<Entry> entries) {
  public TenantAssociationManifest {
    if (entries != null) {
      entries = List.copyOf(entries);
    }
  }

  @Override
  public List<Entry> entries() {
    return entries == null ? null : List.copyOf(entries);
  }

  public record Entry(
      long legacyAccountTenantId,
      String legacyGameTenantId,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String accountEvidenceDigest) {}

  public record Signed(TenantAssociationManifest manifest, String ed25519Signature) {}
}
