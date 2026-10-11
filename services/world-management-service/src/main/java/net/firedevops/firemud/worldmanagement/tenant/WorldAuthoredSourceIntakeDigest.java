package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;

/** Versioned World-owned request and receipt digests for fresh authored-source intake. */
public final class WorldAuthoredSourceIntakeDigest {
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private WorldAuthoredSourceIntakeDigest() {}

  public static String requestDigest(
      String targetNamespace, UUID intakeRequestId, AuthoredWorldSourceEvidence source) {
    requireNonNil(intakeRequestId, "intakeRequestId");
    requireFreshSource(targetNamespace, source);
    return digest(
        "world-authored-source-intake-request/v1",
        targetNamespace,
        intakeRequestId.toString(),
        source.canonicalTenantId().toString(),
        source.worldSlug(),
        source.operationId().toString(),
        source.evidenceDigest());
  }

  public static String receiptDigest(
      String targetNamespace,
      UUID operationId,
      String requestDigest,
      AuthoredWorldSourceEvidence source,
      long localTenantKey) {
    requireNonNil(operationId, "operationId");
    requireDigest(requestDigest, "requestDigest");
    requireFreshSource(targetNamespace, source);
    if (localTenantKey <= 0) {
      throw new IllegalArgumentException("localTenantKey must be positive");
    }
    return digest(
        "world-authored-source-intake-receipt/v1",
        targetNamespace,
        operationId.toString(),
        requestDigest,
        source.canonicalTenantId().toString(),
        source.worldSlug(),
        source.operationId().toString(),
        source.evidenceDigest(),
        Long.toString(localTenantKey));
  }

  static void requireFreshSource(String targetNamespace, AuthoredWorldSourceEvidence source) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(source, "source");
    if (!"NEW_GAME_ROW".equals(source.provenanceKind())) {
      throw new IllegalArgumentException(
          "Fresh World authored-source intake requires NEW_GAME_ROW provenance");
    }
    if (!targetNamespace.equals(source.targetNamespace())) {
      throw new IllegalArgumentException("Source namespace differs from the intake namespace");
    }
    AuthoredWorldSourceDigest.validateReadSelector(
        targetNamespace, source.canonicalTenantId(), source.worldSlug());
    new AuthoredWorldSourceEvidence(
        source.schemaVersion(),
        source.targetNamespace(),
        source.registrationRequestId(),
        source.operationId(),
        source.requestDigest(),
        source.canonicalTenantId(),
        source.tenantSlug(),
        source.worldSlug(),
        source.worldDisplayName(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.evidenceDigest());
  }

  private static String digest(String... segments) {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      for (String segment : segments) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        hash.update((byte) ':');
        hash.update(bytes);
      }
      return "sha256:" + HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static void requireDigest(String value, String label) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a canonical SHA-256 digest");
    }
  }
}
