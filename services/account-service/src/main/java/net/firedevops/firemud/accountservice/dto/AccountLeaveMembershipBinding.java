package net.firedevops.firemud.accountservice.dto;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Exact caller-bound LEAVE intent. This value is not caller authentication or authority. */
public record AccountLeaveMembershipBinding(
    String requestId, UUID accountId, UUID tenantId, String callerBinding) {
  public AccountLeaveMembershipBinding {
    requireUuid(accountId);
    requireUuid(tenantId);
    requireText(requestId, 256);
    requireText(callerBinding, 512);
  }

  public String digest() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (String value :
        new String[] {
          "account-leave-membership/v1",
          "LEAVE",
          requestId,
          accountId.toString(),
          tenantId.toString(),
          callerBinding
        }) {
      byte[] encoded = utf8(value);
      bytes.writeBytes(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
      bytes.write(':');
      bytes.writeBytes(encoded);
    }
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void requireUuid(UUID value) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Canonical LEAVE account and tenant UUIDs required");
    }
  }

  private static void requireText(String value, int maxCodePoints) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > maxCodePoints
        || value.indexOf(0) >= 0) {
      throw new IllegalArgumentException("Bounded exact LEAVE request and caller binding required");
    }
    utf8(value);
  }

  private static byte[] utf8(String value) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] result = new byte[encoded.remaining()];
      encoded.get(result);
      return result;
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("LEAVE binding requires well-formed Unicode", exception);
    }
  }
}
