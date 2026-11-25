package net.firedevops.firemud.common.json;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.erdtman.jcs.JsonCanonicalizer;

/** RFC 8785 JSON Canonicalization Scheme support shared across service boundaries. */
public final class Rfc8785CanonicalJson {
  private Rfc8785CanonicalJson() {}

  /** Returns the RFC 8785 canonical UTF-8 bytes for a JSON object or array. */
  public static byte[] canonicalizeUtf8(String json) throws IOException {
    Objects.requireNonNull(json, "json must not be null");
    return new JsonCanonicalizer(json).getEncodedUTF8();
  }

  /** Canonicalizes JSON and rejects UTF-16 text that cannot be encoded losslessly as UTF-8. */
  public static byte[] canonicalizeStrictUtf8(String json) throws IOException {
    Objects.requireNonNull(json, "json must not be null");
    var canonicalText = new JsonCanonicalizer(json).getEncodedString();
    // CharsetEncoder reports malformed input by default instead of replacing invalid scalars.
    var encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(canonicalText));
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    return bytes;
  }
}
