package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
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
