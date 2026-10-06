package net.firedevops.firemud.common.redis.contracts;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Pure issuer partition key codec; never chooses a bindingRef partition or proves coverage. */
public final class GameplayIssuerLayoutRedisKeyCodec {
  public static final String RESERVATIONS_FIELD = "@firemud:issuer-reservations:v1";

  private GameplayIssuerLayoutRedisKeyCodec() {}

  public static String layoutTag(String layoutVersion, String issuerId, int partitionId) {
    if (layoutVersion == null
        || !layoutVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        || issuerId == null
        || !issuerId.matches("[a-z0-9][a-z0-9._-]{0,127}")
        || partitionId < 0) throw invalid();
    try {
      ByteArrayOutputStream framed = new ByteArrayOutputStream();
      for (String value :
          java.util.List.of(
              "issuerIndexLayoutTag/v1", layoutVersion, issuerId, Integer.toString(partitionId))) {
        ByteBuffer encoded =
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        framed.writeBytes((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
        framed.writeBytes(bytes);
      }
      return "gpi1-"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(framed.toByteArray()));
    } catch (Exception malformed) {
      throw invalid();
    }
  }

  public static String partitionKey(String layoutVersion, String issuerId, int partitionId) {
    return "session:game:index:issuer:{"
        + layoutTag(layoutVersion, issuerId, partitionId)
        + "}:"
        + issuerId
        + ":"
        + partitionId;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid issuer partition layout identity");
  }
}
