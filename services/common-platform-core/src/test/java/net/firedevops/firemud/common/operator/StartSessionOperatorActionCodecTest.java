package net.firedevops.firemud.common.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class StartSessionOperatorActionCodecTest {
  private static final String VECTOR_RESOURCE =
      "/operator/start-session-mutation-digest-v1-vectors.json";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void matchesSharedActionVectors() throws IOException {
    JsonNode vectors = fixture().path("vectors");
    String baseDigest = null;
    String changedTargetDigest = null;
    String changedScopeDigest = null;
    String changedReasonDigest = null;
    String composedDigest = null;
    String decomposedDigest = null;
    String absentClientIpDigest = null;
    String emptyClientIpDigest = null;

    for (JsonNode vector : vectors) {
      byte[] raw = rawVectorBytes(vector);
      String name = vector.path("name").asText();
      if (!vector.path("accepted").asBoolean()) {
        assertThatThrownBy(() -> StartSessionOperatorActionCodec.decode(raw))
            .as("vector %s", name)
            .isInstanceOf(IllegalArgumentException.class);
        continue;
      }

      StartSessionOperatorAction action = StartSessionOperatorActionCodec.decode(raw);
      byte[] preimage = StartSessionOperatorActionCodec.canonicalPreimage(action);
      String digest = StartSessionOperatorActionCodec.mutationDigest(action);
      assertThat(hex(preimage))
          .as("preimage for %s", name)
          .isEqualTo(vector.path("preimageHex").asText());
      assertThat(digest).as("digest for %s", name).isEqualTo(vector.path("digest").asText());
      switch (name) {
        case "base" -> baseDigest = digest;
        case "changed-target" -> changedTargetDigest = digest;
        case "changed-scope" -> changedScopeDigest = digest;
        case "changed-audit-reason" -> changedReasonDigest = digest;
        case "client-ip-absent" -> absentClientIpDigest = digest;
        case "client-ip-empty" -> emptyClientIpDigest = digest;
        case "unicode-composed" -> composedDigest = digest;
        case "unicode-decomposed" -> decomposedDigest = digest;
        default -> {
          // Individual golden values above prove the remaining mutation vectors.
        }
      }
    }

    assertThat(absentClientIpDigest).isNotEqualTo(emptyClientIpDigest);
    assertThat(composedDigest).isEqualTo(decomposedDigest);
    assertThat(baseDigest)
        .isNotEqualTo(absentClientIpDigest)
        .isNotEqualTo(changedTargetDigest)
        .isNotEqualTo(changedScopeDigest)
        .isNotEqualTo(changedReasonDigest);
  }

  @Test
  void matchesSharedGenericValueGrammarVectors() throws IOException {
    java.util.Map<String, String> encodings = new java.util.HashMap<>();
    for (JsonNode vector : fixture().path("grammarVectors")) {
      String name = vector.path("name").asText();
      String kind = vector.path("kind").asText();
      if ("numberLexeme".equals(kind)) {
        String rawLexeme = vector.path("rawLexeme").asText();
        if (!vector.path("accepted").asBoolean()) {
          assertThatThrownBy(
                  () -> StartSessionOperatorActionCodec.canonicalNumberLexemeForTest(rawLexeme))
              .as("numeric lexeme vector %s", name)
              .isInstanceOf(IllegalArgumentException.class);
          continue;
        }
        String encoded =
            hex(StartSessionOperatorActionCodec.canonicalNumberLexemeForTest(rawLexeme));
        assertThat(encoded)
            .as("numeric lexeme vector %s", name)
            .isEqualTo(vector.path("canonicalValueHex").asText());
        encodings.put(name, encoded);
        continue;
      }
      if ("reject".equals(kind)) {
        byte[] raw = vector.path("rawValue").asText().getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StartSessionOperatorActionCodec.canonicalGrammarValueForTest(raw))
            .as("grammar vector %s", name)
            .isInstanceOf(IllegalArgumentException.class);
        continue;
      }

      byte[] encoded;
      if ("absent".equals(kind)) {
        encoded = StartSessionOperatorActionCodec.absentGrammarValueForTest();
      } else {
        byte[] raw = vector.path("rawValue").asText().getBytes(StandardCharsets.UTF_8);
        encoded = StartSessionOperatorActionCodec.canonicalGrammarValueForTest(raw);
      }
      String encodedHex = hex(encoded);
      assertThat(encodedHex)
          .as("canonical value for %s", name)
          .isEqualTo(vector.path("canonicalValueHex").asText());
      encodings.put(name, encodedHex);
    }

    assertThat(encodings.get("absent")).isNotEqualTo(encodings.get("null"));
    assertThat(encodings.get("number-one")).isNotEqualTo(encodings.get("string-one"));
    assertThat(encodings.get("number-signed-zero")).isEqualTo(encodings.get("number-zero"));
    assertThat(encodings.get("unicode-key-composed"))
        .isEqualTo(encodings.get("unicode-key-decomposed"));
  }

  @Test
  void exposesOnlyTypedImmutableStartSessionFields() throws IOException {
    JsonNode base = findVector("base");
    StartSessionOperatorAction action =
        StartSessionOperatorActionCodec.decode(rawVectorBytes(base));

    assertThat(StartSessionOperatorAction.OWNER_SERVICE).isEqualTo("game-session-service");
    assertThat(action.targetOwner()).isEqualTo("game-session-service");
    assertThat(action.actionFamilySchemaId()).isEqualTo("firemud.game-session.start-session");
    assertThat(action.actionFamilySchemaVersion()).isEqualTo("1");
    assertThat(action.expectedVersion())
        .isEqualTo(StartSessionOperatorAction.ExpectedVersion.ABSENT);
    assertThat(action.scope().tenantId().toString())
        .isEqualTo("22222222-2222-4222-8222-222222222222");
    assertThat(action.target().gameTemplateId()).isEqualTo(42L);
    assertThat(action.mutation().clientIp())
        .isInstanceOf(StartSessionOperatorAction.StringClientIp.class);
    assertThat(action.auditReason()).isEqualTo("scheduled launch");
  }

  @Test
  void rejectsRawUtf8AndActionSpecificOversizeInputs() throws IOException {
    assertThatThrownBy(
            () ->
                StartSessionOperatorActionCodec.decode(
                    new byte[] {'{', '"', (byte) 0xff, '"', ':', '1', '}'}))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] oversizedRaw = new byte[StartSessionOperatorActionCodec.MAX_RAW_BYTES + 1];
    assertThatThrownBy(() -> StartSessionOperatorActionCodec.decode(oversizedRaw))
        .isInstanceOf(IllegalArgumentException.class);

    String base = findVector("base").path("input").asText();
    String oversizedReason = base.replace("scheduled launch", "x".repeat(1_001));
    assertThatThrownBy(
            () ->
                StartSessionOperatorActionCodec.decode(
                    oversizedReason.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);

    String oversizedClientIp = base.replace("192.0.2.1", "x".repeat(129));
    assertThatThrownBy(
            () ->
                StartSessionOperatorActionCodec.decode(
                    oversizedClientIp.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void auditReasonUsesExplicitSharedWhitespaceSet() throws IOException {
    int[] sharedWhitespaceCodepoints = {
      0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0, 0x1680,
      0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008,
      0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000
    };
    String base = findVector("base").path("input").asText();
    for (int codePoint : sharedWhitespaceCodepoints) {
      String blankReason = new String(Character.toChars(codePoint));
      String blankInput = replaceAuditReason(base, blankReason);
      assertThatThrownBy(
              () ->
                  StartSessionOperatorActionCodec.decode(
                      blankInput.getBytes(StandardCharsets.UTF_8)))
          .as("blank audit reason U+%04X", codePoint)
          .isInstanceOf(IllegalArgumentException.class);

      String nonblankInput = replaceAuditReason(base, blankReason + "x");
      assertThat(
              StartSessionOperatorActionCodec.decode(
                  nonblankInput.getBytes(StandardCharsets.UTF_8)))
          .as("nonblank audit reason containing U+%04X", codePoint)
          .isNotNull();
    }

    for (int codePoint : new int[] {0x001C, 0x200B}) {
      String outsideWhitespaceSetInput =
          replaceAuditReason(base, new String(Character.toChars(codePoint)));
      assertThat(
              StartSessionOperatorActionCodec.decode(
                  outsideWhitespaceSetInput.getBytes(StandardCharsets.UTF_8)))
          .as("nonblank audit reason U+%04X outside the shared set", codePoint)
          .isNotNull();
    }
  }

  @Test
  void rejectsObjectMemberAndArrayElementLimitViolations() {
    String tooManyMembers = "{" + String.join(",", Collections.nCopies(17, "\"k\":0")) + "}";
    assertThatThrownBy(
            () ->
                StartSessionOperatorActionCodec.canonicalGrammarValueForTest(
                    tooManyMembers.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);

    String tooManyElements = "[" + String.join(",", Collections.nCopies(65, "0")) + "]";
    assertThatThrownBy(
            () ->
                StartSessionOperatorActionCodec.canonicalGrammarValueForTest(
                    tooManyElements.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);

    String tooDeep = "[".repeat(9) + "0" + "]".repeat(9);
    assertThatThrownBy(
            () ->
                StartSessionOperatorActionCodec.canonicalGrammarValueForTest(
                    tooDeep.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static JsonNode fixture() throws IOException {
    try (InputStream stream =
        StartSessionOperatorActionCodecTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
      if (stream == null) {
        throw new IOException("missing shared StartSession digest vector resource");
      }
      return JSON.readTree(stream);
    }
  }

  private static JsonNode findVector(String name) throws IOException {
    for (JsonNode vector : fixture().path("vectors")) {
      if (name.equals(vector.path("name").asText())) {
        return vector;
      }
    }
    throw new IOException("missing vector: " + name);
  }

  private static String replaceAuditReason(String input, String auditReason) {
    return input.replace("\"scheduled launch\"", JSON.valueToTree(auditReason).toString());
  }

  private static byte[] rawVectorBytes(JsonNode vector) {
    if (vector.has("rawHex")) {
      return java.util.HexFormat.of().parseHex(vector.path("rawHex").asText());
    }
    return vector.path("input").asText().getBytes(StandardCharsets.UTF_8);
  }

  private static String hex(byte[] bytes) {
    return java.util.HexFormat.of().formatHex(bytes);
  }
}
