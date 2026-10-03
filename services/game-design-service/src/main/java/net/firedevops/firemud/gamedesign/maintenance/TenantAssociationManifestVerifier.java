package net.firedevops.firemud.gamedesign.maintenance;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest.Entry;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest.Signed;

/** Verifies owner approval over a versioned, byte-length-framed manifest preimage. */
public final class TenantAssociationManifestVerifier {
  private static final String DOMAIN = "firemud/legacy-tenant-association-manifest/v1";
  private static final int MAX_ENTRIES = 5000;

  private TenantAssociationManifestVerifier() {}

  public static Verified verify(Signed signed, Map<String, String> trustedPublicKeys) {
    if (signed == null || signed.manifest() == null || trustedPublicKeys == null) {
      throw new IllegalArgumentException("approved tenant-association manifest is required");
    }
    TenantAssociationManifest manifest = signed.manifest();
    validate(manifest);
    String encodedKey = trustedPublicKeys.get(manifest.signerKeyId());
    if (encodedKey == null || encodedKey.isBlank()) {
      throw new IllegalArgumentException("manifest signer is not trusted");
    }
    byte[] preimage = preimage(manifest);
    try {
      PublicKey publicKey =
          KeyFactory.getInstance("Ed25519")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)));
      Signature signature = Signature.getInstance("Ed25519");
      signature.initVerify(publicKey);
      signature.update(preimage);
      byte[] signatureBytes =
          signed.ed25519Signature() == null
              ? new byte[0]
              : Base64.getDecoder().decode(signed.ed25519Signature());
      if (signatureBytes.length != 64
          || !Base64.getEncoder().encodeToString(signatureBytes).equals(signed.ed25519Signature())
          || !signature.verify(signatureBytes)) {
        throw new IllegalArgumentException("manifest owner signature is invalid");
      }
      String digest =
          "sha256:"
              + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(preimage));
      return new Verified(manifest, digest);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("manifest owner signature cannot be verified", ex);
    }
  }

  /** Exact bytes the offline Game Design owner signs for this manifest version. */
  public static byte[] preimage(TenantAssociationManifest manifest) {
    validate(manifest);
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      write(output, DOMAIN);
      output.writeInt(manifest.schemaVersion());
      write(output, manifest.operationId().toString());
      write(output, manifest.targetNamespace());
      write(output, manifest.signerKeyId());
      write(output, manifest.approvedBy());
      write(output, manifest.approvalReference());
      write(output, manifest.signedAt().toString());
      output.writeInt(manifest.entries().size());
      for (Entry entry : manifest.entries()) {
        output.writeLong(entry.legacyAccountTenantId());
        write(output, entry.legacyGameTenantId());
        write(output, entry.canonicalTenantId().toString());
        output.writeLong(entry.sourceGameRowId());
        write(output, entry.accountEvidenceDigest());
      }
      return bytes.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("in-memory manifest framing failed", ex);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static void validate(TenantAssociationManifest manifest) {
    if (manifest.schemaVersion() != 1
        || manifest.operationId() == null
        || !valid(manifest.targetNamespace(), 63)
        || !valid(manifest.signerKeyId(), 128)
        || !valid(manifest.approvedBy(), 256)
        || !valid(manifest.approvalReference(), 512)
        || manifest.signedAt() == null
        || manifest.entries() == null
        || manifest.entries().isEmpty()
        || manifest.entries().size() > MAX_ENTRIES) {
      throw new IllegalArgumentException("manifest scope, approval, or version is invalid");
    }
    Set<String> gameKeys = new HashSet<>();
    Set<UUID> canonicalIds = new HashSet<>();
    Set<Long> gameRows = new HashSet<>();
    long previousAccountKey = 0;
    for (Entry entry : manifest.entries()) {
      if (entry == null
          || entry.legacyAccountTenantId() <= previousAccountKey
          || !valid(entry.legacyGameTenantId(), 36)
          || entry.canonicalTenantId() == null
          || entry.sourceGameRowId() <= 0
          || entry.accountEvidenceDigest() == null
          || !entry.accountEvidenceDigest().matches("sha256:[0-9a-f]{64}")
          || !gameKeys.add(entry.legacyGameTenantId())
          || !canonicalIds.add(entry.canonicalTenantId())
          || !gameRows.add(entry.sourceGameRowId())) {
        throw new IllegalArgumentException("manifest entries are malformed or ambiguous");
      }
      previousAccountKey = entry.legacyAccountTenantId();
    }
  }

  private static boolean valid(String value, int maxLength) {
    return value != null
        && !value.isBlank()
        && value.length() <= maxLength
        && value.chars().noneMatch(Character::isISOControl);
  }

  public record Verified(TenantAssociationManifest manifest, String manifestDigest) {}
}
