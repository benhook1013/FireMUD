package net.firedevops.firemud.common.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AutomationAuthoredSourceInventoryDeclarationTest {
  private static final String CANONICAL_EMPTY_DECLARATION =
      "{\"families\":{\"EVENT_BINDINGS\":[],\"SCRIPT_DEFINITIONS\":[],"
          + "\"SCRIPT_PATCH_SOURCES\":[]},\"schema\":\"automation-authored-source-inventory/v1\"}";

  @Test
  void acceptsExplicitEmptyFamiliesAndRetainsCanonicalBytes() {
    var declaration =
        AutomationAuthoredSourceInventoryDeclaration.parse(
            "{ \"schema\": \"automation-authored-source-inventory/v1\","
                + " \"families\": { \"SCRIPT_PATCH_SOURCES\": [],"
                + " \"SCRIPT_DEFINITIONS\": [], \"EVENT_BINDINGS\": [] } }");

    assertThat(declaration.canonicalJson()).isEqualTo(CANONICAL_EMPTY_DECLARATION);
    assertThat(declaration.canonicalBytes())
        .containsExactly(CANONICAL_EMPTY_DECLARATION.getBytes(StandardCharsets.UTF_8));
    assertThat(declaration)
        .isEqualTo(AutomationAuthoredSourceInventoryDeclaration.parse(CANONICAL_EMPTY_DECLARATION));

    byte[] exposed = declaration.canonicalBytes();
    exposed[0] = 'x';
    assertThat(declaration.canonicalJson()).isEqualTo(CANONICAL_EMPTY_DECLARATION);
  }

  @Test
  void rejectsMissingNullUnknownAndMalformedFields() {
    assertInvalid("{}");
    assertInvalid(
        "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
            + "\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[]}}");
    assertInvalid("{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":null}");
    assertInvalid(
        "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
            + "\"SCRIPT_DEFINITIONS\":null,\"EVENT_BINDINGS\":[],"
            + "\"SCRIPT_PATCH_SOURCES\":[]}}");
    assertInvalid(
        "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
            + "\"SCRIPT_DEFINITIONS\":{},\"EVENT_BINDINGS\":[],"
            + "\"SCRIPT_PATCH_SOURCES\":[]}}");
    assertInvalid(
        "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
            + "\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
            + "\"SCRIPT_PATCH_SOURCES\":[],\"UNSUPPORTED\":[]}}");
    assertInvalid(
        "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
            + "\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
            + "\"SCRIPT_PATCH_SOURCES\":[]},\"unsupported\":true}");
    assertInvalid(
        "{\"schema\":\"automation-authored-source-inventory/v2\",\"families\":{"
            + "\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
            + "\"SCRIPT_PATCH_SOURCES\":[]}}");
  }

  @Test
  void rejectsDuplicateFieldsAndTrailingJson() {
    assertInvalid(
        "{\"schema\":\"automation-authored-source-inventory/v1\","
            + "\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
            + "\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
            + "\"SCRIPT_PATCH_SOURCES\":[]}}");
    assertInvalid(
        "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
            + "\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
            + "\"EVENT_BINDINGS\":[],\"SCRIPT_PATCH_SOURCES\":[]}}");
    assertInvalid(CANONICAL_EMPTY_DECLARATION + " {}");
  }

  @Test
  void rejectsEveryNonemptyFamilyAsUnsupported() {
    assertUnsupported("SCRIPT_DEFINITIONS", "{}", "SCRIPT_DEFINITIONS");
    assertUnsupported("EVENT_BINDINGS", "{}", "EVENT_BINDINGS");
    assertUnsupported("SCRIPT_PATCH_SOURCES", "{}", "SCRIPT_PATCH_SOURCES");
  }

  @Test
  void rejectsNullInputMalformedUnicodeAndOversizedInput() {
    assertThatThrownBy(() -> AutomationAuthoredSourceInventoryDeclaration.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AutomationAuthoredSourceInventoryDeclaration.parse(
                    "\"" + String.valueOf((char) 0xD800) + "\""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AutomationAuthoredSourceInventoryDeclaration.parse(
                    " ".repeat(AutomationAuthoredSourceInventoryDeclaration.MAX_JSON_BYTES + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large");
  }

  private static void assertInvalid(String json) {
    assertThatThrownBy(() -> AutomationAuthoredSourceInventoryDeclaration.parse(json))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertUnsupported(String field, String entry, String family) {
    String json =
        "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
            + "\"SCRIPT_DEFINITIONS\":"
            + (field.equals("SCRIPT_DEFINITIONS") ? "[" + entry + "]" : "[]")
            + ",\"EVENT_BINDINGS\":"
            + (field.equals("EVENT_BINDINGS") ? "[" + entry + "]" : "[]")
            + ",\"SCRIPT_PATCH_SOURCES\":"
            + (field.equals("SCRIPT_PATCH_SOURCES") ? "[" + entry + "]" : "[]")
            + "}}";
    assertThatThrownBy(() -> AutomationAuthoredSourceInventoryDeclaration.parse(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Nonempty Automation source family is unsupported: " + family);
  }
}
