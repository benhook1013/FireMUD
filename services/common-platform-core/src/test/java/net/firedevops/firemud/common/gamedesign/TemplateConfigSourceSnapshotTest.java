package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TemplateConfigSourceSnapshotTest {
  private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID GENESIS = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void directDecoderPreservesV1BytesAndMissingDeclarationEvidence() throws Exception {
    var config = new TemplateConfigSourceValues.Config(config());
    var original = binding(TemplateConfigSourceValues.createPayload("Starter", config));
    var entry = entry(original, "7", "Starter", config);
    var selected = binding();
    String json = canonical(snapshotObject(selected, List.of(entry.object()), null));
    var decoded = TemplateConfigSourceSnapshot.fromStored(json);
    assertThat(decoded.binding()).isEqualTo(selected);
    assertThat(decoded.entries().getFirst().sourceBinding()).isEqualTo(original);
    assertThat(decoded.ownerSourceInventoryDeclarations()).isEmpty();
    assertThat(decoded.canonicalJson()).isEqualTo(json);
    assertThat(decoded.canonicalBytes()).isEqualTo(json.getBytes(StandardCharsets.UTF_8));
    assertThat(decoded.digest()).isEqualTo(digest(json));
  }

  @Test
  void directV2DecoderKeepsOriginalDeclarationsSeparateFromSelectedIdentity() throws Exception {
    var automation = AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    var entity = EntityAuthoredSourceInventoryDeclaration.parse(entityInventory());
    var authored =
        binding(
            TemplateConfigSourceValues.ownerInventoryPayload(
                Owner.AUTOMATION_SCRIPTING, automation),
            TemplateConfigSourceValues.ownerInventoryPayload(Owner.ENTITY_MANAGEMENT, entity));
    var declarations =
        TemplateConfigSourceValues.mutations(authored).stream()
            .map(TemplateConfigSourceValues.Mutation::ownerInventoryDeclaration)
            .toList();
    var selected = binding();
    String json =
        canonical(
            snapshotObject(
                selected,
                List.of(),
                declarations.stream()
                    .map(TemplateConfigOwnerSourceInventoryDeclaration::object)
                    .toList()));
    var decoded = TemplateConfigSourceSnapshot.fromStored(json);
    assertThat(decoded.binding()).isEqualTo(selected);
    assertThat(decoded.ownerSourceInventoryDeclarations()).hasSize(2);
    assertThat(decoded.ownerSourceInventoryDeclarations())
        .extracting(TemplateConfigOwnerSourceInventoryDeclaration::sourceBinding)
        .containsExactly(authored, authored);
    assertThat(decoded.ownerSourceInventoryDeclarations().get(0).inventory()).isEqualTo(automation);
    assertThat(decoded.ownerSourceInventoryDeclarations().get(1).entityInventory())
        .isEqualTo(entity);
    assertThat(decoded.canonicalJson()).isEqualTo(json);
    assertThat(decoded.digest()).isEqualTo(digest(json));
  }

  @Test
  void configRejectsIncompleteAmbiguousAndUnsupportedGrammar() {
    String valid = config();
    for (String invalid :
        List.of(
            valid.replace("\"rooms\":[]", "\"rooms\":null"),
            valid.replace("\"rooms\":[]", "\"unknown\":[]"),
            valid.replace("\"inputs\":[]", "\"inputs\":[],\"receipt\":{}"),
            valid.replace("\"inputs\":[]", "\"inputs\":[],\"commitDigest\":\"recursive\""),
            valid.replace("\"inputs\":[]", "\"inputs\":[],\"bindingJson\":\"recursive\""),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace(VERSION.toString(), "00000000-0000-0000-0000-000000000000"),
            valid.replace("\"items\":[]", "\"items\":[{\"entityTemplateId\":\"7\"}]"),
            valid.replace("\"supportedSettings\":[]", "\"supportedSettings\":[{}]"),
            valid.replace("\"presence\":\"ABSENT\"", "\"presence\":\"UNKNOWN\""),
            valid.replace("\"presence\":\"ABSENT\"", "\"presence\":\"PRESENT\""),
            valid + "{}")) {
      assertThatThrownBy(() -> new TemplateConfigSourceValues.Config(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void ownerReferencePredicatesInspectEveryCollectionAndPresentPatchWithoutQualification() {
    var empty = new TemplateConfigSourceValues.Config(config());
    assertThat(empty.hasWorldReferences()).isFalse();
    assertThat(empty.hasEntityReferences()).isFalse();
    assertThat(empty.hasAutomationReferences()).isFalse();
    for (String collection : List.of("regions", "rooms")) {
      String key = collection.equals("regions") ? "regionTemplateId" : "roomTemplateId";
      assertThat(
              new TemplateConfigSourceValues.Config(
                      config()
                          .replace(
                              "\"" + collection + "\":[]",
                              "\"" + collection + "\":[{\"" + key + "\":\"" + GENESIS + "\"}]"))
                  .hasWorldReferences())
          .isTrue();
    }
    for (String collection : List.of("items", "npcs"))
      assertThat(
              new TemplateConfigSourceValues.Config(
                      config()
                          .replace(
                              "\"" + collection + "\":[]",
                              "\""
                                  + collection
                                  + "\":[{\"entityTemplateId\":\""
                                  + GENESIS
                                  + "\"}]"))
                  .hasEntityReferences())
          .isTrue();
    assertThat(
            new TemplateConfigSourceValues.Config(
                    config()
                        .replace(
                            "\"scripts\":[]",
                            "\"scripts\":[{\"scriptId\":\"raw-script\",\"eventBindings\":[]}]"))
                .hasAutomationReferences())
        .isTrue();
    assertThat(
            new TemplateConfigSourceValues.Config(
                    config()
                        .replace(
                            "\"presence\":\"ABSENT\"",
                            "\"presence\":\"PRESENT\",\"scriptPatchVersion\":\" raw-patch \""))
                .hasAutomationReferences())
        .isTrue();
  }

  @Test
  void configRejectsDuplicateReferencesInputsScriptsAndBindings() {
    String item = "{\"entityTemplateId\":\"" + GENESIS + "\"}";
    String region = "{\"regionTemplateId\":\"" + GENESIS + "\"}";
    String input =
        "{\"family\":\"ADMISSION_TAGS\",\"key\":\"entry\",\"revisionId\":\"" + GENESIS + "\"}";
    String event =
        "{\"eventType\":\"onEnter\",\"eventSchemaVersion\":\"1\","
            + "\"bindingId\":\"\",\"targetScopeType\":\"\",\"targetScopeId\":\"\"}";
    String script = "{\"scriptId\":\"onEnter\",\"eventBindings\":[]}";
    String duplicateEvents =
        "{\"scriptId\":\"onEnter\",\"eventBindings\":[" + event + "," + event + "]}";
    for (String invalid :
        List.of(
            config().replace("\"items\":[]", "\"items\":[" + item + "," + item + "]"),
            config().replace("\"regions\":[]", "\"regions\":[" + region + "," + region + "]"),
            config().replace("\"inputs\":[]", "\"inputs\":[" + input + "," + input + "]"),
            config().replace("\"scripts\":[]", "\"scripts\":[" + script + "," + script + "]"),
            config().replace("\"scripts\":[]", "\"scripts\":[" + duplicateEvents + "]"),
            config()
                .replace(
                    "\"scripts\":[]", "\"scripts\":[{\"scriptId\":\"x\",\"eventBindings\":null}]"),
            config()
                .replace(
                    "\"inputs\":[]",
                    "\"inputs\":[{\"family\":\"UNKNOWN\",\"key\":\"entry\",\"revisionId\":\""
                        + GENESIS
                        + "\"}]"))) {
      assertThatThrownBy(() -> new TemplateConfigSourceValues.Config(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    // Empty raw binding keys remain accepted exactly as in the existing GD grammar.
    var valid =
        new TemplateConfigSourceValues.Config(
            config()
                .replace(
                    "\"scripts\":[]",
                    "\"scripts\":[{\"scriptId\":\"onEnter\",\"eventBindings\":[" + event + "]}]"));
    assertThat(valid.hasAutomationReferences()).isTrue();
  }

  @Test
  void storedSnapshotRejectsClosedFieldsCollectionsCanonicalFormAndSchemaMismatch()
      throws Exception {
    var selected = binding();
    var value = snapshotObject(selected, List.of(), null);
    String valid = canonical(value);
    List<String> invalid =
        new ArrayList<>(
            List.of(
                valid + "{}",
                " " + valid,
                valid.replace("\"sourceEpoch\":\"1\"", "\"sourceEpoch\":\"01\""),
                valid.replace("\"entries\":[]", "\"entries\":null"),
                valid.replace("\"inheritedCommitId\":\"\"", "\"inheritedCommitId\":null"),
                valid.replace(GENESIS.toString(), "00000000-0000-0000-0000-000000000000"),
                valid.replace(TemplateConfigSourceSnapshot.SCHEMA, "unknown/v1"),
                valid.replace("\"schema\":", "\"unknown\":0,\"schema\":"),
                valid.replace(
                    "\"sourceEpoch\":\"1\"", "\"sourceEpoch\":\"1\",\"sourceEpoch\":\"1\"")));
    var missing = new LinkedHashMap<>(value);
    missing.remove("genesisReceiptId");
    invalid.add(canonical(missing));
    var emptyV2 = snapshotObject(selected, List.of(), List.of());
    invalid.add(canonical(emptyV2));
    var malformedV2 = new LinkedHashMap<>(emptyV2);
    malformedV2.put("ownerSourceInventoryDeclarations", Map.of());
    invalid.add(canonical(malformedV2));
    var v1WithDeclarations = new LinkedHashMap<>(emptyV2);
    v1WithDeclarations.put("schema", TemplateConfigSourceSnapshot.SCHEMA);
    invalid.add(canonical(v1WithDeclarations));
    var wrongDigest = new LinkedHashMap<>(value);
    wrongDigest.put("bindingDigest", "sha256:" + "0".repeat(64));
    String corruptBinding = canonical(wrongDigest);
    assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(corruptBinding))
        .isInstanceOf(IllegalStateException.class);
    for (String json : invalid)
      assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(json))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void storedEntryRejectsOrderingDuplicatesTargetAndRevisionSubstitution() throws Exception {
    var config = new TemplateConfigSourceValues.Config(config());
    var original = binding(TemplateConfigSourceValues.createPayload("Starter", config));
    var first = entry(original, "7", "Starter", config);
    var second = entry(original, "8", "Starter", config);
    var selected = binding();
    for (List<Map<String, Object>> entries :
        List.of(
            List.of(first.object(), first.object()), List.of(second.object(), first.object()))) {
      String json = canonical(snapshotObject(selected, entries, null));
      assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(json))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var altered = new LinkedHashMap<>(first.object());
    altered.put("revisionId", GENESIS.toString());
    String wrongRevision = canonical(snapshotObject(selected, List.of(altered), null));
    assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(wrongRevision))
        .isInstanceOf(java.util.NoSuchElementException.class);
    altered.put("revisionId", first.revisionId().toString());
    altered.put("createdName", "Substituted");
    String wrongPayload = canonical(snapshotObject(selected, List.of(altered), null));
    assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(wrongPayload))
        .isInstanceOf(IllegalArgumentException.class);
    for (String key :
        List.of(
            "templateId",
            "configJson",
            "sourceBindingJson",
            "sourceBindingDigest",
            "revisionOrder",
            "revisionId",
            "createdName")) {
      var missing = new LinkedHashMap<>(first.object());
      missing.remove(key);
      String missingField = canonical(snapshotObject(selected, List.of(missing), null));
      assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(missingField))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var missingOperation = new LinkedHashMap<>(first.object());
    missingOperation.put("createdName", 1);
    String nontextOperation = canonical(snapshotObject(selected, List.of(missingOperation), null));
    assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(nontextOperation))
        .isInstanceOf(IllegalArgumentException.class);
    var foreign =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                GENESIS, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base-commit-0",
            original.revisions(),
            original.affectedUnits());
    altered = new LinkedHashMap<>(first.object());
    altered.put("sourceBindingJson", foreign.canonicalJson());
    altered.put("sourceBindingDigest", foreign.digest());
    String wrongTarget = canonical(snapshotObject(selected, List.of(altered), null));
    assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(wrongTarget))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void declarationsRejectOwnerSchemaContentRevisionAndOrderSubstitution() throws Exception {
    var inventory = AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    String payload =
        TemplateConfigSourceValues.ownerInventoryPayload(Owner.AUTOMATION_SCRIPTING, inventory);
    var authored = binding(payload);
    var declaration =
        TemplateConfigSourceValues.mutations(authored).getFirst().ownerInventoryDeclaration();
    var selected = binding();
    for (String invalid :
        List.of(
            payload.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
            payload.replace("AUTOMATION_SCRIPTING", "ENTITY_MANAGEMENT"),
            payload.replace("\"inventory\":", "\"unknown\":"),
            payload.replace("\"SCRIPT_DEFINITIONS\":[]", "\"SCRIPT_DEFINITIONS\":[{}]"),
            payload.replace(
                "\"operation\":\"DECLARE_OWNER_SOURCE_INVENTORY\"", "\"operation\":\"UNKNOWN\""))) {
      assertThatThrownBy(() -> TemplateConfigSourceValues.mutations(binding(invalid)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> TemplateConfigSourceValues.mutations(binding(payload, payload)))
        .isInstanceOf(IllegalArgumentException.class);
    String duplicate =
        canonical(
            snapshotObject(
                selected, List.of(), List.of(declaration.object(), declaration.object())));
    assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(duplicate))
        .isInstanceOf(IllegalArgumentException.class);
    var changed = new LinkedHashMap<>(declaration.object());
    changed.put("inventoryJson", entityInventory());
    String substituted = canonical(snapshotObject(selected, List.of(), List.of(changed)));
    assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(substituted))
        .isInstanceOf(IllegalArgumentException.class);
    changed = new LinkedHashMap<>(declaration.object());
    changed.put("revisionOrder", "01");
    String wrongOrder = canonical(snapshotObject(selected, List.of(), List.of(changed)));
    assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(wrongOrder))
        .isInstanceOf(IllegalArgumentException.class);
    changed = new LinkedHashMap<>(declaration.object());
    changed.put("revisionId", GENESIS.toString());
    String wrongRevision = canonical(snapshotObject(selected, List.of(), List.of(changed)));
    assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(wrongRevision))
        .isInstanceOf(java.util.NoSuchElementException.class);
    for (String key :
        List.of(
            "owner",
            "inventoryJson",
            "sourceBindingJson",
            "sourceBindingDigest",
            "revisionOrder",
            "revisionId")) {
      var missing = new LinkedHashMap<>(declaration.object());
      missing.remove(key);
      String missingField = canonical(snapshotObject(selected, List.of(), List.of(missing)));
      assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(missingField))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var entity = EntityAuthoredSourceInventoryDeclaration.parse(entityInventory());
    var both =
        binding(
            payload,
            TemplateConfigSourceValues.ownerInventoryPayload(Owner.ENTITY_MANAGEMENT, entity));
    var declarations =
        TemplateConfigSourceValues.mutations(both).stream()
            .map(TemplateConfigSourceValues.Mutation::ownerInventoryDeclaration)
            .toList();
    String reversed =
        canonical(
            snapshotObject(
                selected,
                List.of(),
                List.of(declarations.get(1).object(), declarations.get(0).object())));
    assertThatThrownBy(() -> TemplateConfigSourceSnapshot.fromStored(reversed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static TemplateConfigSourceValues.Entry entry(
      DraftCommitBinding source, String id, String name, TemplateConfigSourceValues.Config config) {
    var revision = source.revisions().getFirst();
    return new TemplateConfigSourceValues.Entry(
        id, config, source, revision.revisionOrder(), revision.revisionId(), name);
  }

  private static Map<String, Object> snapshotObject(
      DraftCommitBinding selected,
      List<Map<String, Object>> entries,
      List<Map<String, Object>> declarations) {
    var value = new LinkedHashMap<String, Object>();
    value.put(
        "schema",
        declarations == null
            ? TemplateConfigSourceSnapshot.SCHEMA
            : TemplateConfigSourceSnapshot.SCHEMA_V2);
    value.put("bindingJson", selected.canonicalJson());
    value.put("bindingDigest", selected.digest());
    value.put("sourceEpoch", "1");
    value.put("inheritedCommitId", "");
    value.put("genesisReceiptId", GENESIS.toString());
    value.put("entries", entries);
    if (declarations != null) value.put("ownerSourceInventoryDeclarations", declarations);
    return value;
  }

  private static String canonical(Object value) throws Exception {
    return new String(
        Rfc8785CanonicalJson.canonicalizeUtf8(new ObjectMapper().writeValueAsString(value)),
        StandardCharsets.UTF_8);
  }

  private static String digest(String json) throws Exception {
    return "sha256:"
        + java.util.HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
  }

  private static String config() {
    return "{\"schemaVersion\":1,\"baseVersionId\":\""
        + VERSION
        + "\",\"world\":{\"regions\":[],\"rooms\":[]},\"entity\":{\"items\":[],\"npcs\":[]},"
        + "\"gameLogic\":{\"inputs\":[]},\"automation\":{\"scripts\":[],\"scriptPatch\":{\"presence\":\"ABSENT\"}},\"supportedSettings\":[]}";
  }

  private static String automationInventory() {
    return "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":"
        + "{\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],\"SCRIPT_PATCH_SOURCES\":[]}}";
  }

  private static String entityInventory() {
    return "{\"schema\":\"entity-authored-source-inventory/v1\",\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
        + "\"ACTOR_BODY_LAYOUT_ASSIGNMENTS\":[],\"ARCHETYPE_ASSIGNMENTS\":[],\"ARCHETYPE_CONSTRAINTS\":[],"
        + "\"ARCHETYPE_ROOTS\":[],\"BALANCE_CURVE_ATTACHMENTS\":[],\"BALANCE_CURVE_ROOTS\":[],"
        + "\"BODY_LAYOUT_MEMBERSHIPS\":[],\"BODY_LAYOUT_ROOTS\":[],\"CRAFTING_INGREDIENT_BINDINGS\":[],"
        + "\"CRAFTING_RECIPE_RESULT_BINDINGS\":[],\"CRAFTING_RECIPE_ROOTS\":[],\"EQUIPMENT_ATTACHMENT_RULES\":[],"
        + "\"EQUIPMENT_CAPABILITIES\":[],\"EQUIPMENT_COMPATIBILITY_RULES\":[],\"EQUIPMENT_OCCUPANCY_RULES\":[],"
        + "\"EQUIPMENT_SLOT_GROUPS\":[],\"EQUIPMENT_SLOT_ROOTS\":[],\"INBOUND_LOOT_BINDINGS\":[],"
        + "\"ITEM_TEMPLATE_ROOTS\":[],\"LOOT_ITEM_MAPPINGS\":[],\"LOOT_TABLE_ROOTS\":[],"
        + "\"NPC_TEMPLATE_ROOTS\":[],\"OTHER_ACTOR_TEMPLATE_ROOTS\":[]}}";
  }

  private static DraftCommitBinding binding(String... payloads) {
    if (payloads.length == 0) payloads = new String[] {"{\"revisionKind\":\"COMMAND_DEFINITION\"}"};
    var revisions = new ArrayList<DraftCommitBinding.RevisionPayload>();
    for (int i = 0; i < payloads.length; i++)
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(i),
              UUID.randomUUID(),
              Owner.GAME_DESIGN_CONTROL_PLANE,
              payloads[i]));
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            TENANT, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit-0",
        revisions,
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                TemplateConfigSourceValues.SCOPE,
                VERSION.toString(),
                TemplateConfigSourceValues.SCOPE,
                TemplateConfigSourceValues.SCOPE_ID,
                "0")));
  }
}
