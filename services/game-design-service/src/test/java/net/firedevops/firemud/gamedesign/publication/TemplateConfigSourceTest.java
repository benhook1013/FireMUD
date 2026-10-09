package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
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

  private static String config() {
    return "{\"schemaVersion\":1,\"baseVersionId\":\""
        + VERSION
        + "\",\"world\":{\"regions\":[],\"rooms\":[]},\"entity\":{\"items\":[],\"npcs\":[]},\"gameLogic\":{\"inputs\":[]},\"automation\":{\"scripts\":[],\"scriptPatch\":{\"presence\":\"ABSENT\"}},\"supportedSettings\":[]}";
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
