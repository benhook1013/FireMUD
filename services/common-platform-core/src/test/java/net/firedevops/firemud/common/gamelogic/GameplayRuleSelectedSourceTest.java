package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.junit.jupiter.api.Test;

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

  @Test
  void distinctIntakeRoundTripsAndCannotDecodeAnOriginalDraftOrder() {
    var binding = binding("{}");
    var source = source(binding, List.of(), GameplayRuleManifest.explicitEmpty());
    var actor = UUID.randomUUID();
    var evidence =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            actor.toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1});
    var order =
        new GameLogicIntakeAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            actor,
            source,
            List.of(evidence));
    assertThat(
            GameLogicIntakeAuthorizationBinding.fromStored(order.canonicalBytes()).canonicalBytes())
        .isEqualTo(order.canonicalBytes());
    var original =
        new DraftAuthorizationFenceBinding(
                UUID.randomUUID(),
                binding.requestId(),
                binding.commitId(),
                UUID.randomUUID(),
                actor,
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.baseCommitId(),
                "0",
                binding.canonicalBytes(),
                binding.canonicalBytes(),
                binding.digest(),
                List.of(evidence))
            .withRequiredOwners();
    assertThatThrownBy(
            () -> GameLogicIntakeAuthorizationBinding.fromStored(original.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    var request = GameLogicIntakeAuthorizationReadEvidence.Request.create("test", order);
    var response = GameLogicIntakeAuthorizationReadGrpcCodec.toHeldResponse(request);
    assertThat(GameLogicIntakeAuthorizationReadGrpcCodec.fromResponse(request, response).request())
        .isEqualTo(request);
    assertThatThrownBy(
            () ->
                GameLogicIntakeAuthorizationReadGrpcCodec.fromResponse(
                    request, response.toBuilder().setHeld(false).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sourceReadRequiresExactCorrelationAndDigest() {
    var binding = binding("{}");
    var source = source(binding, List.of(), GameplayRuleManifest.explicitEmpty());
    var request = GameplayRuleSourceReadEvidence.Request.create("test", binding);
    var response =
        GameplayRuleSourceReadGrpcCodec.toResponse(
            new GameplayRuleSourceReadEvidence(request, source));
    assertThat(GameplayRuleSourceReadGrpcCodec.fromResponse(request, response).source())
        .isEqualTo(source);
    assertThatThrownBy(
            () ->
                GameplayRuleSourceReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setCompleteSourceSnapshotDigest("sha256:" + "0".repeat(64))
                        .build()))
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
