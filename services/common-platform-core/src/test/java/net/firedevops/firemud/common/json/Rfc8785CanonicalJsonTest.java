package net.firedevops.firemud.common.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Rfc8785CanonicalJsonTest {
  @Test
  void sortsObjectKeysByUtf16CodeUnitsAndPreservesArrayOrder() throws Exception {
    String input =
        """
        [
          56,
          {
            "d": true,
            "10": null,
            "1": []
          }
        ]
        """;

    assertThat(Rfc8785CanonicalJson.canonicalizeUtf8(input))
        .isEqualTo("[56,{\"1\":[],\"10\":null,\"d\":true}]".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void strictCanonicalizationPreservesNullAndCanonicalBytesAcrossObjectScopes() throws Exception {
    String input = "{\"z\":{\"same\":1E30},\"same\":null,\"a\":[{\"same\":4.50}]}";
    String expected = "{\"a\":[{\"same\":4.5}],\"same\":null,\"z\":{\"same\":1e+30}}";

    assertThat(Rfc8785CanonicalJson.canonicalizeStrictUtf8(input))
        .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void strictCanonicalizationRequiresUniqueNamesWithinEachObject() {
    String malformedRecord = "{\"label\":\"example\",\"label\":\"other\",\"note\":null}";

    assertThatThrownBy(() -> Rfc8785CanonicalJson.canonicalizeStrictUtf8(malformedRecord))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("Duplicate field 'label'");
  }

  @Test
  void strictCanonicalizationRejectsNumbersBeyondItsDocumentedInputLimit() {
    String oversizedNumber = "1." + "0".repeat(1_000);

    assertThatThrownBy(() -> Rfc8785CanonicalJson.canonicalizeStrictUtf8(oversizedNumber))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  void strictCanonicalizationRejectsDuplicateNamesAfterNullValues() {
    String duplicateAfterNull = "{\"label\":null,\"label\":\"other\"}";

    assertThatThrownBy(() -> Rfc8785CanonicalJson.canonicalizeStrictUtf8(duplicateAfterNull))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  void sortsUnicodeObjectKeysByRawUtf16CodeUnits() throws Exception {
    String input =
        """
        {
          "\\u20ac": "Euro Sign",
          "\\r": "Carriage Return",
          "\\u000a": "Newline",
          "1": "One",
          "\\u0080": "Control\\u007f",
          "\\ud83d\\ude02": "Smiley",
          "\\u00f6": "Latin Small Letter O With Diaeresis",
          "\\ufb33": "Hebrew Letter Dalet With Dagesh",
          "</script>": "Browser Challenge"
        }
        """;
    String expected =
        "{\"\\n\":\"Newline\",\"\\r\":\"Carriage Return\",\"1\":\"One\","
            + "\"</script>\":\"Browser Challenge\",\"\u0080\":\"Control\u007f\","
            + "\"\u00f6\":\"Latin Small Letter O With Diaeresis\",\"\u20ac\":\"Euro Sign\","
            + "\"\ud83d\ude02\":\"Smiley\",\"\ufb33\":\"Hebrew Letter Dalet With Dagesh\"}";

    assertThat(Rfc8785CanonicalJson.canonicalizeUtf8(input))
        .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void preservesUnicodeCodePointsWithoutNormalization() throws Exception {
    String input = "{\"decomposed\":\"e\\u0301\",\"composed\":\"\\u00e9\"}";
    String expected = "{\"composed\":\"\u00e9\",\"decomposed\":\"e\u0301\"}";

    assertThat(Rfc8785CanonicalJson.canonicalizeUtf8(input))
        .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rejectsDuplicateObjectProperties() {
    assertThatThrownBy(() -> Rfc8785CanonicalJson.canonicalizeUtf8("{\"a\":1,\"a\":2}"))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("Duplicate property: a");
  }

  @Test
  void rendersNumbersUsingJcsEcmascriptRules() throws Exception {
    String input =
        "{\"numbers\":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001]}";
    String expected = "{\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27]}";

    assertThat(Rfc8785CanonicalJson.canonicalizeUtf8(input))
        .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
  }
}
