package net.firedevops.firemud.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EntityAuthoredSourceInventoryDeclarationTest {
  private static final String FAMILIES =
      "\"ACTOR_BODY_LAYOUT_ASSIGNMENTS\":[],\"ARCHETYPE_ASSIGNMENTS\":[],"
          + "\"ARCHETYPE_CONSTRAINTS\":[],\"ARCHETYPE_ROOTS\":[],"
          + "\"BALANCE_CURVE_ATTACHMENTS\":[],\"BALANCE_CURVE_ROOTS\":[],"
          + "\"BODY_LAYOUT_MEMBERSHIPS\":[],\"BODY_LAYOUT_ROOTS\":[],"
          + "\"CRAFTING_INGREDIENT_BINDINGS\":[],\"CRAFTING_RECIPE_RESULT_BINDINGS\":[],"
          + "\"CRAFTING_RECIPE_ROOTS\":[],\"EQUIPMENT_ATTACHMENT_RULES\":[],"
          + "\"EQUIPMENT_CAPABILITIES\":[],\"EQUIPMENT_COMPATIBILITY_RULES\":[],"
          + "\"EQUIPMENT_OCCUPANCY_RULES\":[],"
          + "\"EQUIPMENT_SLOT_GROUPS\":[],\"EQUIPMENT_SLOT_ROOTS\":[],"
          + "\"INBOUND_LOOT_BINDINGS\":[],\"ITEM_TEMPLATE_ROOTS\":[],"
          + "\"LOOT_ITEM_MAPPINGS\":[],\"LOOT_TABLE_ROOTS\":[],"
          + "\"NPC_TEMPLATE_ROOTS\":[],\"OTHER_ACTOR_TEMPLATE_ROOTS\":[]";
  private static final String CANONICAL_EMPTY_DECLARATION =
      "{\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
          + FAMILIES
          + "},\"schema\":\"entity-authored-source-inventory/v1\"}";

  @Test
  void acceptsExplicitEmptyFamiliesAndRetainsCanonicalBytes() {
    var declaration =
        EntityAuthoredSourceInventoryDeclaration.parse(
            "{ \"schema\": \"entity-authored-source-inventory/v1\","
                + " \"equipmentApplicability\": \"NOT_APPLICABLE\", \"families\": {"
                + FAMILIES
                + " } }");

    assertThat(declaration.canonicalJson()).isEqualTo(CANONICAL_EMPTY_DECLARATION);
    assertThat(declaration.canonicalBytes())
        .containsExactly(CANONICAL_EMPTY_DECLARATION.getBytes(StandardCharsets.UTF_8));
    assertThat(declaration)
        .isEqualTo(EntityAuthoredSourceInventoryDeclaration.parse(CANONICAL_EMPTY_DECLARATION));

    byte[] exposed = declaration.canonicalBytes();
    exposed[0] = 'x';
    assertThat(declaration.canonicalJson()).isEqualTo(CANONICAL_EMPTY_DECLARATION);
  }

  @Test
  void rejectsMissingNullUnknownAndMalformedFields() {
    assertInvalid("{}");
    assertInvalid(
        "{\"schema\":\"entity-authored-source-inventory/v1\","
            + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{}}");
    assertInvalid(CANONICAL_EMPTY_DECLARATION.replace(",\"OTHER_ACTOR_TEMPLATE_ROOTS\":[]", ""));
    assertInvalid(
        "{\"schema\":\"entity-authored-source-inventory/v1\","
            + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":null}");
    assertInvalid(
        "{\"schema\":\"entity-authored-source-inventory/v1\","
            + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
            + FAMILIES.replace("\"ITEM_TEMPLATE_ROOTS\":[]", "\"ITEM_TEMPLATE_ROOTS\":null")
            + "}}");
    assertInvalid(
        "{\"schema\":\"entity-authored-source-inventory/v1\","
            + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
            + FAMILIES.replace("\"ITEM_TEMPLATE_ROOTS\":[]", "\"ITEM_TEMPLATE_ROOTS\":{}")
            + "}}");
    assertInvalid(
        "{\"schema\":\"entity-authored-source-inventory/v1\","
            + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
            + FAMILIES
            + ",\"UNSUPPORTED\":[]}}");
    assertInvalid(
        "{\"schema\":\"entity-authored-source-inventory/v1\","
            + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
            + FAMILIES
            + "},\"unsupported\":true}");
    assertInvalid(
        "{\"schema\":\"entity-authored-source-inventory/v2\","
            + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
            + FAMILIES
            + "}}");
  }

  @Test
  void requiresExplicitEquipmentNonApplicability() {
    assertInvalid(
        "{\"schema\":\"entity-authored-source-inventory/v1\",\"families\":{" + FAMILIES + "}}");
    assertInvalid(
        CANONICAL_EMPTY_DECLARATION.replace(
            "\"equipmentApplicability\":\"NOT_APPLICABLE\"",
            "\"equipmentApplicability\":\"APPLICABLE\""));
    assertInvalid(
        CANONICAL_EMPTY_DECLARATION.replace(
            "\"equipmentApplicability\":\"NOT_APPLICABLE\"", "\"equipmentApplicability\":false"));
  }

  @Test
  void rejectsDuplicateFieldsAndTrailingJson() {
    assertInvalid(
        "{\"schema\":\"entity-authored-source-inventory/v1\","
            + "\"schema\":\"entity-authored-source-inventory/v1\","
            + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
            + FAMILIES
            + "}}");
    assertInvalid(
        CANONICAL_EMPTY_DECLARATION.replace(
            "\"ITEM_TEMPLATE_ROOTS\":[]", "\"ITEM_TEMPLATE_ROOTS\":[],\"ITEM_TEMPLATE_ROOTS\":[]"));
    assertInvalid(CANONICAL_EMPTY_DECLARATION + " {}");
  }

  @Test
  void rejectsEveryNonemptyFamilyAsUnsupported() {
    for (String family : FAMILIES.split(",")) {
      String familyName = family.substring(1, family.indexOf('"', 1));
      String json =
          CANONICAL_EMPTY_DECLARATION.replace(
              "\"" + familyName + "\":[]", "\"" + familyName + "\":[null]");
      assertThatThrownBy(() -> EntityAuthoredSourceInventoryDeclaration.parse(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Nonempty Entity source family is unsupported: " + familyName);
    }
  }

  @Test
  void rejectsNullInputMalformedUnicodeAndOversizedInput() {
    assertThatThrownBy(() -> EntityAuthoredSourceInventoryDeclaration.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntityAuthoredSourceInventoryDeclaration.parse(
                    "\"" + String.valueOf((char) 0xD800) + "\""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntityAuthoredSourceInventoryDeclaration.parse(
                    " ".repeat(EntityAuthoredSourceInventoryDeclaration.MAX_JSON_BYTES + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large");
  }

  private static void assertInvalid(String json) {
    assertThatThrownBy(() -> EntityAuthoredSourceInventoryDeclaration.parse(json))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
