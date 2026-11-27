package net.firedevops.firemud.common.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.erdtman.jcs.JsonCanonicalizer;

/** RFC 8785 JSON Canonicalization Scheme support shared across service boundaries. */
public final class Rfc8785CanonicalJson {
  private static final StreamReadConstraints STRICT_INPUT_LIMITS =
      StreamReadConstraints.builder()
          .maxNestingDepth(1_000)
          .maxNumberLength(1_000)
          .maxStringLength(20_000_000)
          .maxNameLength(50_000)
          .build();
  private static final JsonFactory STRICT_PROPERTY_NAMES =
      JsonFactory.builder()
          .streamReadConstraints(STRICT_INPUT_LIMITS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private Rfc8785CanonicalJson() {}

  /** Returns the RFC 8785 canonical UTF-8 bytes for a JSON object or array. */
  public static byte[] canonicalizeUtf8(String json) throws IOException {
    Objects.requireNonNull(json, "json must not be null");
    return new JsonCanonicalizer(json).getEncodedUTF8();
  }

  /**
   * Canonicalizes a JSON object or array with unique member names and rejects lossy UTF-8 encoding.
   * The streaming validation pass bounds nesting depth to 1,000, number length to 1,000 characters,
   * string length to 20,000,000 characters, and property-name length to 50,000 characters. These are
   * input resource limits, not RFC 8785 restrictions.
   */
  public static byte[] canonicalizeStrictUtf8(String json) throws IOException {
    Objects.requireNonNull(json, "json must not be null");
    // Check names while streaming, before canonicalization materializes objects.
    // Retain the original input for JCS so this pass never coerces numbers or values.
    try (var parser = STRICT_PROPERTY_NAMES.createParser(json)) {
      var token = parser.nextToken();
      while (token != null) {
        if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING) {
          requireInteroperableCharacters(parser.getText());
        }
        if (token == JsonToken.VALUE_STRING) {
          // Advancing past a string token can skip its contents without applying maxStringLength.
          STRICT_INPUT_LIMITS.validateStringLength(parser.getTextLength());
        } else if (token == JsonToken.VALUE_NUMBER_INT) {
          STRICT_INPUT_LIMITS.validateIntegerLength(parser.getTextLength());
        } else if (token == JsonToken.VALUE_NUMBER_FLOAT) {
          STRICT_INPUT_LIMITS.validateFPLength(parser.getTextLength());
        }
        token = parser.nextToken();
      }
    }
    var canonicalText = new JsonCanonicalizer(json).getEncodedString();
    // CharsetEncoder reports malformed input by default instead of replacing invalid scalars.
    var encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(canonicalText));
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    return bytes;
  }

  private static void requireInteroperableCharacters(String value) throws IOException {
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      if ((codePoint >= 0xfdd0 && codePoint <= 0xfdef) || (codePoint & 0xffff) >= 0xfffe) {
        throw new IOException("I-JSON names and values must not contain Unicode noncharacters");
      }
      offset += Character.charCount(codePoint);
    }
  }
}
