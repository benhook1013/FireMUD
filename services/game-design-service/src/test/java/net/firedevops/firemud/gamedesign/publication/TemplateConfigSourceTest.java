package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.BrandingSource;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import org.junit.jupiter.api.Test;

class TemplateConfigSourceTest {
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID VERSION = UUID.randomUUID();

  @Test
  void closedDocumentRequiresEveryExplicitCollectionAndRejectsRecursiveEvidence() {
    String valid = config();
    assertThat(new TemplateConfigSource.Config(valid).baseVersionId()).isEqualTo(VERSION);
    for (String invalid :
        List.of(
            valid.replace("\"supportedSettings\":[]", "\"unknown\":[]"),
            valid.replace("\"inputs\":[]", "\"inputs\":[],\"receipt\":{}"),
            valid.replace("\"inputs\":[]", "\"inputs\":[],\"bindingJson\":\"recursive\""),
            valid.replace("\"inputs\":[]", "\"inputs\":[],\"commitDigest\":\"recursive\""),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace(
                "\"supportedSettings\":[]",
                "\"supportedSettings\":[{\"defaultRuntimeFlagsJson\":\"{}\"}]"),
            valid.replace("\"scriptPatch\":{\"presence\":\"ABSENT\"}", "\"scriptPatch\":null"),
            valid + "{}"))
      assertThatThrownBy(() -> new TemplateConfigSource.Config(invalid))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canonicalOwnerUuidSyntaxCannotQualifyWorldEntityOrAutomationReferences() {
    String ownerId = UUID.randomUUID().toString();
    var world =
        new TemplateConfigSource.Config(
            config()
                .replace(
                    "\"regions\":[]", "\"regions\":[{\"regionTemplateId\":\"" + ownerId + "\"}]"));
    var entity =
        new TemplateConfigSource.Config(
            config()
                .replace("\"items\":[]", "\"items\":[{\"entityTemplateId\":\"" + ownerId + "\"}]"));
    var scripts =
        new TemplateConfigSource.Config(
            config()
                .replace(
                    "\"scripts\":[]",
                    "\"scripts\":[{\"scriptId\":\"onEnter\",\"eventBindings\":[]}]"));
    var patch =
        new TemplateConfigSource.Config(
            config()
                .replace(
                    "{\"presence\":\"ABSENT\"}",
                    "{\"presence\":\"PRESENT\",\"scriptPatchVersion\":\" raw-patch-1 \"}"));
    assertThatThrownBy(world::requireAvailableOwnerReads)
        .hasMessage("TEMPLATE_CONFIG_WORLD_EXACT_OWNER_READ_UNAVAILABLE");
    assertThatThrownBy(entity::requireAvailableOwnerReads)
        .hasMessage("TEMPLATE_CONFIG_ENTITY_EXACT_OWNER_READ_UNAVAILABLE");
    assertThatThrownBy(scripts::requireAvailableOwnerReads)
        .hasMessage("TEMPLATE_CONFIG_AUTOMATION_EXACT_OWNER_READ_UNAVAILABLE");
    assertThatThrownBy(patch::requireAvailableOwnerReads)
        .hasMessage("TEMPLATE_CONFIG_AUTOMATION_EXACT_OWNER_READ_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                new TemplateConfigSource.Config(
                    config().replace("\"items\":[]", "\"equipment\":[]")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TemplateConfigSource.Config(
                    config()
                        .replace("\"regions\":[]", "\"regions\":[{\"regionTemplateId\":\"7\"}]")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void actualGeneratedRowAndOriginalRevisionRemainSeparateFromAuthoredPreimage() {
    var create =
        binding(
            TemplateConfigSource.createPayload(
                "Starter", new TemplateConfigSource.Config(config())));
    var revision = create.revisions().getFirst().revisionId();
    assertThat(TemplateConfigSource.mutations(create).getFirst().templateId()).isNull();
    assertThatThrownBy(() -> TemplateConfigSource.replay(List.of(), create, Map.of()))
        .isInstanceOf(NullPointerException.class);
    var entries = TemplateConfigSource.replay(List.of(), create, Map.of(revision, "7"));
    var sibling = binding(CommandSource.deletePayload("absent"));
    var inherited = TemplateConfigSource.replay(entries, sibling, Map.of());
    assertThat(inherited.getFirst().sourceBinding()).isSameAs(create);
    var snapshot =
        new TemplateConfigSourceSnapshot(
            sibling, "1", create.commitId(), UUID.randomUUID(), inherited);
    assertThat(TemplateConfigSourceSnapshot.fromStored(snapshot.canonicalJson()))
        .isEqualTo(snapshot);
    assertThatThrownBy(
            () ->
                TemplateConfigSource.replay(
                    List.of(), binding(TemplateConfigSource.deletePayload("7")), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TemplateConfigSource.replay(
                    List.of(),
                    binding(
                        TemplateConfigSource.upsertPayload(
                            "7", new TemplateConfigSource.Config(config()))),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void siblingDispatchDoesNotInventUnknownSourceKindsOrChangeOriginalBinding() {
    var binding =
        binding(
            TemplateConfigSource.createPayload(
                "Starter", new TemplateConfigSource.Config(config())),
            CommandSource.deletePayload("absent"));
    assertThat(TemplateConfigSource.mutations(binding).getFirst().binding()).isSameAs(binding);
    assertThat(CommandSource.mutations(binding)).hasSize(1);
    assertThat(AssetSource.mutations(binding)).isEmpty();
    assertThat(BrandingSource.mutations(binding)).isEmpty();
    assertThat(GameplayRuleSource.mutations(binding)).isEmpty();
    assertThat(CommandSource.hasRealmPolicyRevision(binding)).isFalse();
    String wrongBase = config().replace(VERSION.toString(), UUID.randomUUID().toString());
    assertThatThrownBy(
            () ->
                TemplateConfigSource.mutations(
                    binding(
                        TemplateConfigSource.createPayload(
                            "Starter", new TemplateConfigSource.Config(wrongBase)))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void automationInventoryUsesItsExactVersionedOperationAndPreservesLegacySnapshotBytes() {
    var inventory = AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    String payload =
        TemplateConfigSource.ownerInventoryPayload(
            DraftCommitBinding.Owner.AUTOMATION_SCRIPTING, inventory);
    assertThat(CommandSource.tree(payload).path("schemaVersion").intValue()).isEqualTo(2);
    assertThat(CommandSource.tree(payload).path("inventory").isObject()).isTrue();
    assertThat(payload).doesNotContain("inventoryJson");
    var authored = binding(payload);
    var mutation = TemplateConfigSource.mutations(authored).getFirst();
    assertThat(mutation.operation())
        .isEqualTo(TemplateConfigSource.OperationKind.DECLARE_OWNER_SOURCE_INVENTORY);
    assertThat(mutation.templateId()).isNull();
    assertThat(mutation.templateName()).isNull();
    assertThat(mutation.config()).isNull();

    var declarations = TemplateConfigSource.replayOwnerInventoryDeclarations(List.of(), authored);
    var inherited =
        TemplateConfigSource.replayOwnerInventoryDeclarations(
            declarations, binding(CommandSource.deletePayload("absent")));
    assertThat(inherited).containsExactly(declarations.getFirst());
    assertThat(inherited.getFirst().sourceBinding()).isSameAs(authored);

    var oldBinding =
        binding(
            TemplateConfigSource.createPayload(
                "Starter", new TemplateConfigSource.Config(config())));
    var oldEntries =
        TemplateConfigSource.replay(
            List.of(), oldBinding, Map.of(oldBinding.revisions().getFirst().revisionId(), "7"));
    var oldSnapshot =
        new TemplateConfigSourceSnapshot(oldBinding, "1", null, UUID.randomUUID(), oldEntries);
    String legacySnapshotBytes =
        CommandSource.canonical(
            Map.of(
                "schema",
                TemplateConfigSourceSnapshot.SCHEMA,
                "bindingJson",
                oldBinding.canonicalJson(),
                "bindingDigest",
                oldBinding.digest(),
                "sourceEpoch",
                "1",
                "inheritedCommitId",
                "",
                "genesisReceiptId",
                oldSnapshot.genesisReceiptId().toString(),
                "entries",
                oldEntries.stream().map(TemplateConfigSource.Entry::object).toList()));
    assertThat(oldSnapshot.canonicalJson()).isEqualTo(legacySnapshotBytes);
    assertThat(oldSnapshot.canonicalJson())
        .contains("\"schema\":\"game-design-template-config-source-snapshot/v1\"")
        .doesNotContain("ownerSourceInventoryDeclarations");
    assertThat(TemplateConfigSourceSnapshot.fromStored(oldSnapshot.canonicalJson()))
        .isEqualTo(oldSnapshot);

    var v2Snapshot =
        new TemplateConfigSourceSnapshot(
            authored, "1", null, UUID.randomUUID(), List.of(), declarations);
    assertThat(v2Snapshot.canonicalJson())
        .contains("\"schema\":\"game-design-template-config-source-snapshot/v2\"")
        .contains("ownerSourceInventoryDeclarations")
        .contains(authored.requestId().toString())
        .contains(authored.commitId().toString());
    assertThat(TemplateConfigSourceSnapshot.fromStored(v2Snapshot.canonicalJson()))
        .isEqualTo(v2Snapshot);
  }

  @Test
  void ownerInventoryOperationRejectsMissingUnknownAndUnsupportedContent() {
    var inventory = AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    String valid =
        TemplateConfigSource.ownerInventoryPayload(
            DraftCommitBinding.Owner.AUTOMATION_SCRIPTING, inventory);
    String missingInventory =
        CommandSource.canonical(
            Map.of(
                "schemaVersion",
                2,
                "revisionKind",
                TemplateConfigSource.REVISION_KIND,
                "operation",
                "DECLARE_OWNER_SOURCE_INVENTORY",
                "owner",
                "AUTOMATION_SCRIPTING"));
    assertThatThrownBy(() -> TemplateConfigSource.mutations(binding(missingInventory)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TemplateConfigSource.mutations(
                    binding(valid.replace("AUTOMATION_SCRIPTING", "ENTITY_MANAGEMENT"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AutomationAuthoredSourceInventoryDeclaration.parse(
                    automationInventory()
                        .replace("\"SCRIPT_DEFINITIONS\":[]", "\"SCRIPT_DEFINITIONS\":[{}]")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            TemplateConfigSourceSnapshot.fromStored(
                    new TemplateConfigSourceSnapshot(
                            binding(CommandSource.deletePayload("absent")),
                            "0",
                            null,
                            UUID.randomUUID(),
                            List.of())
                        .canonicalJson())
                .ownerSourceInventoryDeclarations())
        .isEmpty();
  }

  @Test
  void entityInventoryUsesClosedTypedOperationAndRejectsOwnerContentSubstitution() {
    var entity = EntityAuthoredSourceInventoryDeclaration.parse(entityInventory());
    String payload =
        TemplateConfigSource.ownerInventoryPayload(
            DraftCommitBinding.Owner.ENTITY_MANAGEMENT, entity);
    assertThat(CommandSource.tree(payload).path("schemaVersion").intValue()).isEqualTo(3);
    assertThat(CommandSource.tree(payload).path("inventory").isObject()).isTrue();
    var authored = binding(payload);
    var mutation = TemplateConfigSource.mutations(authored).getFirst();
    assertThat(mutation.declaredOwner()).isEqualTo(DraftCommitBinding.Owner.ENTITY_MANAGEMENT);
    assertThat(mutation.inventory()).isNull();
    assertThat(mutation.entityInventory()).isEqualTo(entity);
    assertThat(mutation.templateId()).isNull();
    assertThat(mutation.config()).isNull();

    var automation = AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    String autoContentUnderEntity =
        typedInventoryPayload(3, "ENTITY_MANAGEMENT", automation.canonicalJson());
    String entityContentUnderAutomation =
        typedInventoryPayload(2, "AUTOMATION_SCRIPTING", entity.canonicalJson());
    for (String substituted : List.of(autoContentUnderEntity, entityContentUnderAutomation))
      assertThatThrownBy(() -> TemplateConfigSource.mutations(binding(substituted)))
          .isInstanceOf(IllegalArgumentException.class);
    String missingEntityInventory =
        CommandSource.canonical(
            Map.of(
                "schemaVersion",
                3,
                "revisionKind",
                TemplateConfigSource.REVISION_KIND,
                "operation",
                "DECLARE_OWNER_SOURCE_INVENTORY",
                "owner",
                "ENTITY_MANAGEMENT"));
    assertThatThrownBy(() -> TemplateConfigSource.mutations(binding(missingEntityInventory)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntityAuthoredSourceInventoryDeclaration.parse(
                    entityInventory()
                        .replace("\"ITEM_TEMPLATE_ROOTS\":[]", "\"ITEM_TEMPLATE_ROOTS\":[{}]")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntityAuthoredSourceInventoryDeclaration.parse(
                    entityInventory().replace("\"families\":{", "\"families\":{\"UNKNOWN\":[],")))
        .isInstanceOf(IllegalArgumentException.class);

    var declarations = TemplateConfigSource.replayOwnerInventoryDeclarations(List.of(), authored);
    var bothOwners =
        TemplateConfigSource.replayOwnerInventoryDeclarations(
            declarations,
            binding(
                TemplateConfigSource.ownerInventoryPayload(
                    DraftCommitBinding.Owner.AUTOMATION_SCRIPTING, automation)));
    assertThat(bothOwners)
        .extracting(TemplateConfigOwnerSourceInventoryDeclaration::owner)
        .containsExactly(
            DraftCommitBinding.Owner.AUTOMATION_SCRIPTING,
            DraftCommitBinding.Owner.ENTITY_MANAGEMENT);
    assertThat(bothOwners.get(1).entityInventory()).isEqualTo(entity);
    assertThat(bothOwners.get(1).sourceBinding()).isEqualTo(authored);
    var snapshot =
        new TemplateConfigSourceSnapshot(
            authored, "1", null, UUID.randomUUID(), List.of(), declarations);
    assertThat(TemplateConfigSourceSnapshot.fromStored(snapshot.canonicalJson()))
        .isEqualTo(snapshot);
    assertThat(snapshot.canonicalJson()).contains("ITEM_TEMPLATE_ROOTS");
  }

  @Test
  void sharedAndGdPayloadsMatchIndependentExistingEncodings() throws Exception {
    var gd = new TemplateConfigSource.Config(config());
    var shared =
        new net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues.Config(config());
    String expectedConfig = independentCanonical(CommandSource.tree(config()));
    assertThat(gd.canonicalJson()).isEqualTo(expectedConfig);
    assertThat(shared.canonicalJson()).isEqualTo(expectedConfig);
    for (String operation : List.of("CREATE", "UPSERT", "DELETE")) {
      Map<String, Object> expected = new java.util.LinkedHashMap<>();
      expected.put("schemaVersion", 1);
      expected.put("revisionKind", "TEMPLATE_CONFIG");
      expected.put("operation", operation);
      expected.put(
          operation.equals("CREATE") ? "templateName" : "templateId",
          operation.equals("CREATE") ? "Starter" : "7");
      if (!operation.equals("DELETE")) expected.put("configJson", expectedConfig);
      String expectedPayload = independentCanonical(expected);
      String gdPayload =
          switch (operation) {
            case "CREATE" -> TemplateConfigSource.createPayload("Starter", gd);
            case "UPSERT" -> TemplateConfigSource.upsertPayload("7", gd);
            default -> TemplateConfigSource.deletePayload("7");
          };
      String sharedPayload =
          switch (operation) {
            case "CREATE" ->
                net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues.createPayload(
                    "Starter", shared);
            case "UPSERT" ->
                net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues.upsertPayload(
                    "7", shared);
            default ->
                net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues.deletePayload(
                    "7");
          };
      assertThat(gdPayload).isEqualTo(expectedPayload);
      assertThat(sharedPayload).isEqualTo(expectedPayload);
    }
    var automation = AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    var entity = EntityAuthoredSourceInventoryDeclaration.parse(entityInventory());
    for (var owner :
        List.of(
            DraftCommitBinding.Owner.AUTOMATION_SCRIPTING,
            DraftCommitBinding.Owner.ENTITY_MANAGEMENT)) {
      boolean isAutomation = owner == DraftCommitBinding.Owner.AUTOMATION_SCRIPTING;
      String json = isAutomation ? automation.canonicalJson() : entity.canonicalJson();
      String expected =
          independentCanonical(
              Map.of(
                  "schemaVersion",
                  isAutomation ? 2 : 3,
                  "revisionKind",
                  "TEMPLATE_CONFIG",
                  "operation",
                  "DECLARE_OWNER_SOURCE_INVENTORY",
                  "owner",
                  owner.name(),
                  "inventory",
                  CommandSource.tree(json)));
      String gdPayload =
          isAutomation
              ? TemplateConfigSource.ownerInventoryPayload(owner, automation)
              : TemplateConfigSource.ownerInventoryPayload(owner, entity);
      String sharedPayload =
          isAutomation
              ? net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues
                  .ownerInventoryPayload(owner, automation)
              : net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues
                  .ownerInventoryPayload(owner, entity);
      assertThat(gdPayload).isEqualTo(expected);
      assertThat(sharedPayload).isEqualTo(expected);
    }
  }

  @Test
  void adaptersPreserveIndependentV1V2BytesDigestsAndOriginalInheritedProvenance()
      throws Exception {
    var config = new TemplateConfigSource.Config(config());
    var automation = AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    var entity = EntityAuthoredSourceInventoryDeclaration.parse(entityInventory());
    var original =
        binding(
            TemplateConfigSource.createPayload("Starter", config),
            TemplateConfigSource.ownerInventoryPayload(
                DraftCommitBinding.Owner.AUTOMATION_SCRIPTING, automation),
            TemplateConfigSource.ownerInventoryPayload(
                DraftCommitBinding.Owner.ENTITY_MANAGEMENT, entity));
    var entries =
        TemplateConfigSource.replay(
            List.of(), original, Map.of(original.revisions().getFirst().revisionId(), "7"));
    var declarations = TemplateConfigSource.replayOwnerInventoryDeclarations(List.of(), original);
    var selected = binding(CommandSource.deletePayload("unrelated"));
    for (boolean v2 : List.of(false, true)) {
      var retainedDeclarations =
          v2 ? declarations : List.<TemplateConfigOwnerSourceInventoryDeclaration>of();
      var snapshot =
          new TemplateConfigSourceSnapshot(
              selected, "1", original.commitId(), UUID.randomUUID(), entries, retainedDeclarations);
      var entry = entries.getFirst();
      Map<String, Object> entryObject =
          Map.of(
              "templateId",
              "7",
              "configJson",
              config.canonicalJson(),
              "sourceBindingJson",
              original.canonicalJson(),
              "sourceBindingDigest",
              original.digest(),
              "revisionOrder",
              entry.revisionOrder(),
              "revisionId",
              entry.revisionId().toString(),
              "createdName",
              "Starter");
      var declarationObjects = new ArrayList<Map<String, Object>>();
      for (var declaration : retainedDeclarations) {
        var object =
            Map.<String, Object>of(
                "owner",
                declaration.owner().name(),
                "inventoryJson",
                declaration.inventoryJson(),
                "sourceBindingJson",
                original.canonicalJson(),
                "sourceBindingDigest",
                original.digest(),
                "revisionOrder",
                declaration.revisionOrder(),
                "revisionId",
                declaration.revisionId().toString());
        declarationObjects.add(object);
        assertThat(declaration.object()).isEqualTo(object);
        assertThat(declaration.shared().object()).isEqualTo(object);
      }
      var expected = new java.util.LinkedHashMap<String, Object>();
      expected.put(
          "schema",
          v2
              ? "game-design-template-config-source-snapshot/v2"
              : "game-design-template-config-source-snapshot/v1");
      expected.put("bindingJson", selected.canonicalJson());
      expected.put("bindingDigest", selected.digest());
      expected.put("sourceEpoch", "1");
      expected.put("inheritedCommitId", original.commitId().toString());
      expected.put("genesisReceiptId", snapshot.genesisReceiptId().toString());
      expected.put("entries", List.of(entryObject));
      if (v2) expected.put("ownerSourceInventoryDeclarations", declarationObjects);
      String json = independentCanonical(expected);
      byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      String digest = independentDigest(bytes);
      var common =
          net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot.fromStored(json);
      assertThat(entry.object()).isEqualTo(entryObject);
      assertThat(snapshot.canonicalJson()).isEqualTo(json);
      assertThat(snapshot.canonicalBytes()).isEqualTo(bytes);
      assertThat(snapshot.digest()).isEqualTo(digest);
      assertThat(common.canonicalBytes()).isEqualTo(bytes);
      assertThat(common.digest()).isEqualTo(digest);
      assertThat(TemplateConfigSourceSnapshot.fromStored(json)).isEqualTo(snapshot);
      assertThat(TemplateConfigSourceSnapshot.fromShared(common)).isEqualTo(snapshot);
      assertThat(common.binding()).isEqualTo(selected);
      assertThat(common.entries().getFirst().sourceBinding()).isEqualTo(original);
      if (v2)
        assertThat(common.ownerSourceInventoryDeclarations())
            .extracting(
                net.firedevops.firemud.common.gamedesign
                        .TemplateConfigOwnerSourceInventoryDeclaration
                    ::sourceBinding)
            .containsExactly(original, original);
    }
  }

  @Test
  void applicationAndCaptureFramesRemainExactIndependentLengthPrefixedBytes() throws Exception {
    var authored =
        binding(
            TemplateConfigSource.createPayload(
                "Starter", new TemplateConfigSource.Config(config())));
    var entries =
        TemplateConfigSource.replay(
            List.of(), authored, Map.of(authored.revisions().getFirst().revisionId(), "7"));
    var snapshot =
        new TemplateConfigSourceSnapshot(authored, "1", null, UUID.randomUUID(), entries);
    byte[] applicationBytes =
        independentFrames(
            "game-design-template-config-source-application/v1"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "0".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            snapshot.shared().canonicalBytes());
    assertThat(
            new TemplateConfigSourceSnapshot.Application(authored, "0", snapshot).canonicalBytes())
        .isEqualTo(applicationBytes);

    // Stipulated upstream fixture tests encoding only, not owner participation or publication
    // proof.
    var operation = IsolatedPublicationOperationFixtures.fresh(authored.target());
    var selected = operation.account().input().selection().selectedCommit();
    var inherited =
        new TemplateConfigSourceSnapshot(
            selected, "1", authored.commitId(), snapshot.genesisReceiptId(), entries);
    var capture = new TemplateConfigSourceSnapshot.Capture(operation, inherited);
    byte[] captureBytes =
        independentFrames(
            "game-design-template-config-source-capture/v1"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            operation.canonicalBytes(),
            inherited.shared().canonicalBytes());
    assertThat(capture.canonicalBytes()).isEqualTo(captureBytes);
    assertThat(capture.digest()).isEqualTo(independentDigest(captureBytes));
    assertThat(inherited.entries().getFirst().sourceBinding()).isEqualTo(authored);
    assertThat(inherited.binding()).isEqualTo(selected);
  }

  private static String independentCanonical(Object value) throws Exception {
    return new String(
        net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
            new tools.jackson.databind.ObjectMapper().writeValueAsString(value)),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String independentDigest(byte[] bytes) throws Exception {
    return "sha256:"
        + java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static byte[] independentFrames(byte[]... fields) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var output = new java.io.DataOutputStream(bytes);
    for (byte[] field : fields) {
      output.writeInt(field.length);
      output.write(field);
    }
    return bytes.toByteArray();
  }

  private static String config() {
    return "{\"schemaVersion\":1,\"baseVersionId\":\""
        + VERSION
        + "\",\"world\":{\"regions\":[],\"rooms\":[]},\"entity\":{\"items\":[],\"npcs\":[]},\"gameLogic\":{\"inputs\":[]},\"automation\":{\"scripts\":[],\"scriptPatch\":{\"presence\":\"ABSENT\"}},\"supportedSettings\":[]}";
  }

  private static String automationInventory() {
    return "{\"schema\":\"automation-authored-source-inventory/v1\","
        + "\"families\":{\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
        + "\"SCRIPT_PATCH_SOURCES\":[]}}";
  }

  private static String entityInventory() {
    return "{\"schema\":\"entity-authored-source-inventory/v1\","
        + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
        + "\"ACTOR_BODY_LAYOUT_ASSIGNMENTS\":[],\"ARCHETYPE_ASSIGNMENTS\":[],"
        + "\"ARCHETYPE_CONSTRAINTS\":[],\"ARCHETYPE_ROOTS\":[],"
        + "\"BALANCE_CURVE_ATTACHMENTS\":[],\"BALANCE_CURVE_ROOTS\":[],"
        + "\"BODY_LAYOUT_MEMBERSHIPS\":[],\"BODY_LAYOUT_ROOTS\":[],"
        + "\"CRAFTING_INGREDIENT_BINDINGS\":[],\"CRAFTING_RECIPE_RESULT_BINDINGS\":[],"
        + "\"CRAFTING_RECIPE_ROOTS\":[],\"EQUIPMENT_ATTACHMENT_RULES\":[],"
        + "\"EQUIPMENT_CAPABILITIES\":[],\"EQUIPMENT_COMPATIBILITY_RULES\":[],"
        + "\"EQUIPMENT_OCCUPANCY_RULES\":[],\"EQUIPMENT_SLOT_GROUPS\":[],"
        + "\"EQUIPMENT_SLOT_ROOTS\":[],\"INBOUND_LOOT_BINDINGS\":[],"
        + "\"ITEM_TEMPLATE_ROOTS\":[],\"LOOT_ITEM_MAPPINGS\":[],"
        + "\"LOOT_TABLE_ROOTS\":[],\"NPC_TEMPLATE_ROOTS\":[],"
        + "\"OTHER_ACTOR_TEMPLATE_ROOTS\":[]}}";
  }

  private static String typedInventoryPayload(int schemaVersion, String owner, String inventory) {
    return CommandSource.canonical(
        Map.of(
            "schemaVersion",
            schemaVersion,
            "revisionKind",
            TemplateConfigSource.REVISION_KIND,
            "operation",
            "DECLARE_OWNER_SOURCE_INVENTORY",
            "owner",
            owner,
            "inventory",
            CommandSource.tree(inventory)));
  }

  private static DraftCommitBinding binding(String... payloads) {
    var revisions = new ArrayList<DraftCommitBinding.RevisionPayload>();
    for (int i = 0; i < payloads.length; i++)
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(i),
              UUID.randomUUID(),
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
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
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                TemplateConfigSource.SCOPE,
                VERSION.toString(),
                TemplateConfigSource.SCOPE,
                "effective",
                "0")));
  }
}
