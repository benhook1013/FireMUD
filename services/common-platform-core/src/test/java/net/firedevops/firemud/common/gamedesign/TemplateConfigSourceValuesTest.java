package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration;
import org.junit.jupiter.api.Test;

class TemplateConfigSourceValuesTest {
  private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = UUID.fromString("22222222-2222-4222-8222-222222222222");
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

  private static AutomationAuthoredSourceInventoryDeclaration automation() {
    return AutomationAuthoredSourceInventoryDeclaration.parse(
        "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
            + "\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],\"SCRIPT_PATCH_SOURCES\":[]}}");
  }

  private static EntityAuthoredSourceInventoryDeclaration entity() {
    return EntityAuthoredSourceInventoryDeclaration.parse(
        "{\"schema\":\"entity-authored-source-inventory/v1\","
            + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
            + FAMILIES
            + "}}");
  }

  @Test
  void roundTripsBothOwnerInventoryPayloadsAndStoredDeclarations() {
    String automationPayload =
        TemplateConfigSourceValues.ownerInventoryPayload(Owner.AUTOMATION_SCRIPTING, automation());
    String entityPayload =
        TemplateConfigSourceValues.ownerInventoryPayload(Owner.ENTITY_MANAGEMENT, entity());
    var binding = binding(automationPayload, entityPayload);
    var mutations = TemplateConfigSourceValues.mutations(binding);
    assertThat(mutations).hasSize(2);
    assertThat(mutations.get(0).payload()).isEqualTo(automationPayload);
    assertThat(mutations.get(1).payload()).isEqualTo(entityPayload);
    assertThat(mutations.get(0).inventory()).isEqualTo(automation());
    assertThat(mutations.get(1).entityInventory()).isEqualTo(entity());
    for (var mutation : mutations) {
      assertThat(mutation.changesTemplateRow()).isFalse();
      var declaration = mutation.ownerInventoryDeclaration();
      assertThat(
              TemplateConfigOwnerSourceInventoryDeclaration.fromStored(
                  TemplateConfigSourceJson.tree(
                      TemplateConfigSourceJson.canonical(declaration.object()))))
          .isEqualTo(declaration);
    }
  }

  @Test
  void rejectsWrongSchemaForEachOwner() {
    String automationPayload =
        TemplateConfigSourceValues.ownerInventoryPayload(Owner.AUTOMATION_SCRIPTING, automation());
    String entityPayload =
        TemplateConfigSourceValues.ownerInventoryPayload(Owner.ENTITY_MANAGEMENT, entity());
    assertThatThrownBy(
            () ->
                TemplateConfigSourceValues.mutations(
                    binding(
                        automationPayload.replace("\"schemaVersion\":2", "\"schemaVersion\":3"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Automation");
    assertThatThrownBy(
            () ->
                TemplateConfigSourceValues.mutations(
                    binding(entityPayload.replace("\"schemaVersion\":3", "\"schemaVersion\":2"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Entity");
  }

  @Test
  void rejectsDuplicateDeclarationsForEitherOwnerAndUnsupportedOwner() {
    for (String payload :
        List.of(
            TemplateConfigSourceValues.ownerInventoryPayload(
                Owner.AUTOMATION_SCRIPTING, automation()),
            TemplateConfigSourceValues.ownerInventoryPayload(Owner.ENTITY_MANAGEMENT, entity()))) {
      assertThatThrownBy(() -> TemplateConfigSourceValues.mutations(binding(payload, payload)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("one declaration");
    }
    String unsupported =
        TemplateConfigSourceValues.ownerInventoryPayload(Owner.AUTOMATION_SCRIPTING, automation())
            .replace("AUTOMATION_SCRIPTING", "WORLD_MANAGEMENT");
    assertThatThrownBy(() -> TemplateConfigSourceValues.mutations(binding(unsupported)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("supported owner");
    assertThatThrownBy(
            () ->
                TemplateConfigSourceValues.ownerInventoryPayload(
                    Owner.ENTITY_MANAGEMENT, automation()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TemplateConfigSourceValues.ownerInventoryPayload(
                    Owner.AUTOMATION_SCRIPTING, entity()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingOrMismatchedRevisionUsesArgumentFailureForBothDeclarationsAndRowMutation() {
    String payload =
        TemplateConfigSourceValues.ownerInventoryPayload(Owner.AUTOMATION_SCRIPTING, automation());
    var binding = binding(payload);
    UUID revisionId = binding.revisions().getFirst().revisionId();
    assertThatThrownBy(
            () ->
                new TemplateConfigOwnerSourceInventoryDeclaration(
                    Owner.AUTOMATION_SCRIPTING, automation(), binding, "1", revisionId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exact original");
    assertThatThrownBy(
            () ->
                new TemplateConfigOwnerSourceInventoryDeclaration(
                    Owner.ENTITY_MANAGEMENT, entity(), binding, "0", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exact original");
    assertThatThrownBy(
            () ->
                new TemplateConfigSourceValues.Mutation(
                    binding,
                    "1",
                    revisionId,
                    TemplateConfigSourceValues.OperationKind.DELETE,
                    "1",
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exact original");
    var wrongOwner =
        DraftCommitBinding.create(
            binding.target(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base-0",
            List.of(new RevisionPayload("0", revisionId, Owner.WORLD_MANAGEMENT, payload)),
            List.of(
                new AffectedUnit(Owner.WORLD_MANAGEMENT, "inventory", "1", "inventory", "1", "0")));
    assertThatThrownBy(
            () ->
                new TemplateConfigOwnerSourceInventoryDeclaration(
                    Owner.AUTOMATION_SCRIPTING, automation(), wrongOwner, "0", revisionId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exact original");
  }

  @Test
  void rejectsBlankAutomationBindingIdAndScopeType() {
    for (String blank : List.of("", "   ")) {
      assertThatThrownBy(
              () -> new TemplateConfigSourceValues.Config(templateConfig(blank, "GLOBAL", "")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> new TemplateConfigSourceValues.Config(templateConfig("binding", blank, "")))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void requiresTextualTargetScopeIdButAllowsAnEmptyValue() {
    String validWithEmptyTargetId = templateConfig("binding", "GLOBAL", "");
    assertThat(new TemplateConfigSourceValues.Config(validWithEmptyTargetId).canonicalJson())
        .contains("\"targetScopeId\":\"\"");

    assertThatThrownBy(
            () ->
                new TemplateConfigSourceValues.Config(
                    validWithEmptyTargetId.replace(",\"targetScopeId\":\"\"", "")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TemplateConfigSourceValues.Config(
                    validWithEmptyTargetId.replace(
                        "\"targetScopeId\":\"\"", "\"targetScopeId\":1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String templateConfig(
      String bindingId, String targetScopeType, String targetScopeId) {
    return "{\"schemaVersion\":1,\"baseVersionId\":\""
        + VERSION
        + "\",\"world\":{\"regions\":[],\"rooms\":[]},\"entity\":{\"items\":[],\"npcs\":[]},"
        + "\"gameLogic\":{\"inputs\":[]},\"automation\":{\"scripts\":[{\"scriptId\":\"script\","
        + "\"eventBindings\":[{\"eventType\":\"onLoad\",\"eventSchemaVersion\":\"1\","
        + "\"bindingId\":\""
        + bindingId
        + "\",\"targetScopeType\":\""
        + targetScopeType
        + "\",\"targetScopeId\":\""
        + targetScopeId
        + "\"}]}],\"scriptPatch\":{\"presence\":\"ABSENT\"}},\"supportedSettings\":[]}";
  }

  private static DraftCommitBinding binding(String... payloads) {
    var revisions =
        IntStream.range(0, payloads.length)
            .mapToObj(
                i ->
                    new RevisionPayload(
                        Integer.toString(i),
                        UUID.randomUUID(),
                        Owner.GAME_DESIGN_CONTROL_PLANE,
                        payloads[i]))
            .toList();
    return DraftCommitBinding.create(
        new TargetProof(
            TENANT, VERSION, 23L, "private-owner-key", 42L, "private-owner-key", "NEW_GAME_ROW"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-0",
        revisions,
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                TemplateConfigSourceValues.SCOPE,
                VERSION.toString(),
                TemplateConfigSourceValues.SCOPE,
                TemplateConfigSourceValues.SCOPE_ID,
                "0")));
  }
}
