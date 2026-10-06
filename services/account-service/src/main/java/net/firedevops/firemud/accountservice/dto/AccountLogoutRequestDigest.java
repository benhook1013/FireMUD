package net.firedevops.firemud.accountservice.dto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Canonical, bounded request digests for the two Account token-logout operations. */
public final class AccountLogoutRequestDigest {
  private static final String DOMAIN = "requestDigest/v1";
  private static final HexFormat HEX = HexFormat.of();

  private AccountLogoutRequestDigest() {}

  public static String tokenLogout(UUID subjectAccountId, String tokenProfile, String tokenHash) {
    return digest(OperationKind.TOKEN_LOGOUT, subjectAccountId, tokenProfile, tokenHash);
  }

  public static String accountLogoutAll(
      UUID subjectAccountId, String tokenProfile, String presentedTokenHash) {
    return digest(
        OperationKind.ACCOUNT_LOGOUT_ALL, subjectAccountId, tokenProfile, presentedTokenHash);
  }

  /** Validates the exact closed token-profile vocabulary accepted by logout request bindings. */
  public static void validateTokenProfile(String tokenProfile) {
    if (!"control-ui".equals(tokenProfile) && !"player-bootstrap".equals(tokenProfile)) {
      throw new IllegalArgumentException(
          "Logout request token profile must be control-ui or player-bootstrap");
    }
  }

  /** Validates a SHA-256 token identity rendered as lowercase hexadecimal without a prefix. */
  public static void validateTokenHash(String tokenHash) {
    if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Logout request token hash must be lowercase SHA-256 hex");
    }
  }

  public static String tokenLogoutPreimageHex(
      UUID subjectAccountId, String tokenProfile, String tokenHash) {
    validateBindings(subjectAccountId, tokenProfile, tokenHash);
    return HEX.formatHex(
        preimage(OperationKind.TOKEN_LOGOUT, subjectAccountId, tokenProfile, tokenHash));
  }

  public static String accountLogoutAllPreimageHex(
      UUID subjectAccountId, String tokenProfile, String presentedTokenHash) {
    validateBindings(subjectAccountId, tokenProfile, presentedTokenHash);
    return HEX.formatHex(
        preimage(
            OperationKind.ACCOUNT_LOGOUT_ALL, subjectAccountId, tokenProfile, presentedTokenHash));
  }

  private static String digest(
      OperationKind operationKind, UUID subjectAccountId, String tokenProfile, String tokenHash) {
    validateBindings(subjectAccountId, tokenProfile, tokenHash);
    Objects.requireNonNull(operationKind, "A closed logout operation kind is required");
    byte[] preimage = preimage(operationKind, subjectAccountId, tokenProfile, tokenHash);
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(preimage));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void validateBindings(
      UUID subjectAccountId, String tokenProfile, String tokenHash) {
    if (subjectAccountId == null || new UUID(0L, 0L).equals(subjectAccountId)) {
      throw new IllegalArgumentException("A canonical non-nil Account UUID is required");
    }
    validateTokenProfile(tokenProfile);
    validateTokenHash(tokenHash);
  }

  private static byte[] preimage(
      OperationKind operationKind, UUID subjectAccountId, String tokenProfile, String tokenHash) {
    String tokenIdentityField =
        operationKind == OperationKind.ACCOUNT_LOGOUT_ALL ? "presentedTokenHash" : "tokenHash";
    ByteArrayOutputStream output = new ByteArrayOutputStream(256);
    appendSegment(output, DOMAIN);
    appendStringField(output, "operationKind", operationKind.wireValue);
    appendStringField(output, "subjectAccountId", subjectAccountId.toString());
    appendStringField(output, "tokenProfile", tokenProfile);
    appendStringField(output, tokenIdentityField, tokenHash);
    return output.toByteArray();
  }

  private static void appendStringField(ByteArrayOutputStream output, String name, String value) {
    appendSegment(output, name);
    appendSegment(output, "string");
    output.write('1');
    appendSegment(output, value);
  }

  private static void appendSegment(ByteArrayOutputStream output, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private enum OperationKind {
    TOKEN_LOGOUT("TOKEN_LOGOUT"),
    ACCOUNT_LOGOUT_ALL("ACCOUNT_LOGOUT_ALL");

    private final String wireValue;

    OperationKind(String wireValue) {
      this.wireValue = wireValue;
    }
  }
}
