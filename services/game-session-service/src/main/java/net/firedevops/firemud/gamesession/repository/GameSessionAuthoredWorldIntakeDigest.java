package net.firedevops.firemud.gamesession.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;

/** Versioned local source-intake digests; neither digest authenticates Game Design. */
public final class GameSessionAuthoredWorldIntakeDigest {
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  private GameSessionAuthoredWorldIntakeDigest() {}

  public static String requestDigest(UUID intakeRequestId, AuthoredWorldSourceEvidence source) {
    requireIdentity(intakeRequestId, "intakeRequestId");
    Objects.requireNonNull(source, "source");
    return digest(
        "game-session-authored-world-intake-request/v1",
        intakeRequestId.toString(),
        source.evidenceDigest());
  }

  public static String receiptDigest(
      UUID localOperationId, String requestDigest, String evidenceDigest) {
    requireIdentity(localOperationId, "localOperationId");
    requireDigest(requestDigest);
    requireDigest(evidenceDigest);
    return digest(
        "game-session-authored-world-intake-receipt/v1",
        localOperationId.toString(),
        requestDigest,
        evidenceDigest);
  }

  private static String digest(String... values) {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      for (String value : values) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        hash.update((byte) ':');
        hash.update(bytes);
      }
      return "sha256:" + HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void requireIdentity(UUID value, String name) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private static void requireDigest(String value) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException("A canonical SHA-256 digest is required");
    }
  }
}
