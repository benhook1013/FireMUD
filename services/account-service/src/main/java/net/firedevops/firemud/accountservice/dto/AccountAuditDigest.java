package net.firedevops.firemud.accountservice.dto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Receiver-version-1 digest over exact persisted UTF-8 payload bytes. */
public final class AccountAuditDigest {
  private static final Pattern CANONICAL_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  private AccountAuditDigest() {}

  public static String ofPayload(String payload) {
    if (payload == null) {
      throw new IllegalArgumentException("Account audit payload is required");
    }
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(strictUtf8(payload)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static byte[] strictUtf8(String payload) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(payload));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException ex) {
      throw new IllegalArgumentException("Account audit payload is not valid Unicode", ex);
    }
  }

  public static boolean isValid(String digest) {
    return digest != null && CANONICAL_DIGEST.matcher(digest).matches();
  }
}
