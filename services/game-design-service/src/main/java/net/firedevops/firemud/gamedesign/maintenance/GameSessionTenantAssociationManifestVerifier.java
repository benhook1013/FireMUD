package net.firedevops.firemud.gamedesign.maintenance;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;

/** Verifies Game Design owner approval for one retained Game Session tenant association. */
public final class GameSessionTenantAssociationManifestVerifier {
  private GameSessionTenantAssociationManifestVerifier() {}

  /**
   * Verifies canonical signed evidence with the protected, purpose-specific Game Design owner
   * approval public-key allowlist. This map is not an Account key ring or a general JWKS.
   */
  public static Verified verify(Signed signed, Map<String, String> trustedOwnerApprovalPublicKeys) {
    if (signed == null || signed.manifest() == null || trustedOwnerApprovalPublicKeys == null) {
      throw new IllegalArgumentException("signed Game Session tenant association is required");
    }
    GameSessionTenantAssociationEvidence manifest = signed.manifest();
    String encodedKey = trustedOwnerApprovalPublicKeys.get(manifest.signerKeyId());
    if (encodedKey == null || encodedKey.isBlank()) {
      throw new IllegalArgumentException("manifest signer is not trusted for owner approval");
    }

    try {
      byte[] encodedKeyBytes = Base64.getDecoder().decode(encodedKey);
      if (!Base64.getEncoder().encodeToString(encodedKeyBytes).equals(encodedKey)) {
        throw new IllegalArgumentException("owner approval public key encoding is not canonical");
      }
      PublicKey publicKey =
          KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encodedKeyBytes));
      byte[] preimage = manifest.preimage();
      byte[] signatureBytes = decodeCanonicalSignature(signed.ed25519Signature());
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(preimage);
      if (!verifier.verify(signatureBytes)) {
        throw new IllegalArgumentException("manifest owner signature is invalid");
      }
      return new Verified(manifest, manifest.manifestDigest());
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalArgumentException("manifest owner signature cannot be verified", exception);
    }
  }

  private static byte[] decodeCanonicalSignature(String encodedSignature) {
    if (encodedSignature == null || encodedSignature.isBlank()) {
      throw new IllegalArgumentException("manifest owner signature is required");
    }
    byte[] signatureBytes = Base64.getDecoder().decode(encodedSignature);
    if (signatureBytes.length != 64
        || !Base64.getEncoder().encodeToString(signatureBytes).equals(encodedSignature)) {
      throw new IllegalArgumentException(
          "manifest owner signature must be canonical 64-byte Ed25519");
    }
    return signatureBytes;
  }

  public record Signed(GameSessionTenantAssociationEvidence manifest, String ed25519Signature) {}

  public record Verified(GameSessionTenantAssociationEvidence manifest, String manifestDigest) {}
}
