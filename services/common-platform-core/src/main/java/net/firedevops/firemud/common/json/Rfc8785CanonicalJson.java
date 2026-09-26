package net.firedevops.firemud.common.json;

import java.io.IOException;
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
}
