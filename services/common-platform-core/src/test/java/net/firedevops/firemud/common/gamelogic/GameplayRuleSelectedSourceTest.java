package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.junit.jupiter.api.Test;

// Parser proof derived from donor 1dcb173efe9670557e4db182ae5df10c04d95e79.
// Its distinct authorization and source-read transport tests remain in that donor.
class GameplayRuleSelectedSourceTest {
  @Test
  void retainsExactNonemptySourceAndRejectsSubstitutedOriginalRevision() {
    var definition = new GameplayRuleManifest.AdmissionTag("gameplay");
    var binding = binding(GameplayRuleSourceRevision.upsertPayload(definition));
    var entry = entry(binding, definition);
    var source = source(binding, List.of(entry), manifest(definition));
    assertThat(source.binding()).isEqualTo(binding);
    assertThat(source.manifest()).isEqualTo(manifest(definition));
    var changed = new java.util.LinkedHashMap<>(entry);
    changed.put("revisionId", UUID.randomUUID().toString());
    assertThatThrownBy(() -> source(binding, List.of(changed), manifest(definition)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> source(binding, List.of(), manifest(definition)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DraftCommitBinding binding(String payload) {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "genesis",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.randomUUID(),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                payload)),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                target.canonicalVersionId().toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static GameplayRuleManifest manifest(GameplayRuleManifest.Definition definition) {
    Map<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>> values =
        new EnumMap<>(GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values()) values.put(family, new ArrayList<>());
    values.get(definition.family()).add(definition);
    return new GameplayRuleManifest(values);
  }

  private static Map<String, Object> entry(
      DraftCommitBinding binding, GameplayRuleManifest.Definition definition) {
    return Map.of(
        "family",
        definition.family().name(),
        "definitionJson",
        GameplayRuleManifest.canonical(definition),
        "sourceBindingJson",
        binding.canonicalJson(),
        "sourceBindingDigest",
        binding.digest(),
        "revisionOrder",
        "0",
        "revisionId",
        binding.revisions().getFirst().revisionId().toString());
  }

  private static GameplayRuleSelectedSource source(
      DraftCommitBinding binding,
      List<Map<String, Object>> entries,
      GameplayRuleManifest manifest) {
    return new GameplayRuleSelectedSource(
        GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson",
                binding.canonicalJson(),
                "bindingDigest",
                binding.digest(),
                "sourceEpoch",
                "1",
                "inheritedCommitId",
                "",
                "genesisReceiptId",
                UUID.randomUUID().toString(),
                "manifestJson",
                manifest.canonicalJson(),
                "entries",
                entries)));
  }
}
