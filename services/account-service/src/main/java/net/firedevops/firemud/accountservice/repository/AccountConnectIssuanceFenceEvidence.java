package net.firedevops.firemud.accountservice.repository;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.ConnectTokenReceiptClassifier.Lookup;

/** Immutable Account-local issuance fence captured for the original Connect operation. */
public record AccountConnectIssuanceFenceEvidence(
    UUID operationId, Lookup identity, IssuanceFence fence, String digest) {

  static final String SCHEMA_NAME = "account-connect-issuance-fence/v1";
  private static final HexFormat HEX = HexFormat.of();

  public AccountConnectIssuanceFenceEvidence {
    if (!calculateDigest(operationId, identity, fence).equals(digest)) {
      throw new IllegalArgumentException("Account issuance-fence capture digest does not match");
    }
  }

  static AccountConnectIssuanceFenceEvidence capture(
      UUID operationId, Lookup identity, IssuanceFence fence) {
    return new AccountConnectIssuanceFenceEvidence(
        operationId, identity, fence, calculateDigest(operationId, identity, fence));
  }

  private static String calculateDigest(UUID operationId, Lookup identity, IssuanceFence fence) {
    Objects.requireNonNull(identity);
    Objects.requireNonNull(fence);
    if (operationId == null
        || operationId.equals(new UUID(0, 0))
        || !identity.accountId().equals(fence.accountId())) {
      throw new IllegalArgumentException("Exact Connect operation and fence owner are required");
    }

    // Preserve the donor domain and byte-length-framed component order for the v1 capture.
    // Lookup validates UTF-8, bounds, and the sole supported request digest version before hashing.
    String scopeHash =
        "sha256:" + sha256(identity.connectScopeId().getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream preimage = new ByteArrayOutputStream();
    append(preimage, SCHEMA_NAME);
    append(preimage, operationId.toString());
    append(preimage, identity.accountId().toString());
    append(preimage, identity.tenantId().toString());
    append(preimage, scopeHash);
    append(preimage, identity.requestId());
    append(preimage, identity.requestDigest().substring("sha256:".length()));
    append(preimage, Long.toString(fence.value()));
    append(preimage, Long.toString(fence.sourceVersion()));
    return sha256(preimage.toByteArray());
  }

  private static void append(ByteArrayOutputStream output, String component) {
    byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
