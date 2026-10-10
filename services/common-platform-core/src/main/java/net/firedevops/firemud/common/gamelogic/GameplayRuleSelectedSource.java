package net.firedevops.firemud.common.gamelogic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;

/** Closed GD owner export. Structure and digest are not authentication of its producer. */
public record GameplayRuleSelectedSource(String snapshotJson) {
  public GameplayRuleSelectedSource {
    if (snapshotJson == null || snapshotJson.getBytes(StandardCharsets.UTF_8).length > 4194304)
      throw new IllegalArgumentException("Bounded complete GD source required");
    var root = GameplayRuleManifest.tree(snapshotJson);
    GameplayRuleManifest.fields(
        root,
        Set.of(
            "schema",
            "bindingJson",
            "bindingDigest",
            "sourceEpoch",
            "inheritedCommitId",
            "genesisReceiptId",
            "manifestJson",
            "entries"));
    if (!"game-design-gameplay-rule-source-snapshot/v1".equals(text(root, "schema"))
        || !root.path("entries").isArray()
        || !text(root, "sourceEpoch").matches("0|[1-9][0-9]*"))
      throw new IllegalArgumentException("Complete supported GD rule snapshot required");
    var binding =
        DraftCommitBinding.fromStored(text(root, "bindingJson"), text(root, "bindingDigest"));
    DraftAuthorizationFenceBinding.canonicalUuid(text(root, "genesisReceiptId"));
    String inherited = text(root, "inheritedCommitId");
    if (!inherited.isEmpty()) DraftAuthorizationFenceBinding.canonicalUuid(inherited);
    var manifest = GameplayRuleManifest.fromStored(text(root, "manifestJson"));
    Map<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>> inventory =
        new EnumMap<>(GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values())
      inventory.put(family, new ArrayList<>());
    for (var entry : root.path("entries")) {
      GameplayRuleManifest.fields(
          entry,
          Set.of(
              "family",
              "definitionJson",
              "sourceBindingJson",
              "sourceBindingDigest",
              "revisionOrder",
              "revisionId"));
      var family = GameplayRuleManifest.Family.valueOf(text(entry, "family"));
      var origin =
          DraftCommitBinding.fromStored(
              text(entry, "sourceBindingJson"), text(entry, "sourceBindingDigest"));
      if (!origin.target().equals(binding.target())
          || !text(entry, "revisionOrder").matches("0|[1-9][0-9]*"))
        throw new IllegalArgumentException("Selected rule provenance differs");
      String revisionId = text(entry, "revisionId");
      DraftAuthorizationFenceBinding.canonicalUuid(revisionId);
      var definition = GameplayRuleManifest.definition(family, text(entry, "definitionJson"));
      String expectedPayload = GameplayRuleSourceRevision.upsertPayload(definition);
      boolean originalRevision =
          origin.revisions().stream()
              .anyMatch(
                  revision ->
                      revision.owner() == DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE
                          && revision.revisionId().toString().equals(revisionId)
                          && revision.revisionOrder().equals(text(entry, "revisionOrder"))
                          && expectedPayload.equals(
                              GameplayRuleManifest.canonical(
                                  GameplayRuleManifest.tree(revision.payload()))));
      if (!originalRevision)
        throw new IllegalArgumentException("Rule entry lacks exact original UPSERT revision");
      inventory.get(family).add(definition);
    }
    if (!manifest.equals(new GameplayRuleManifest(inventory))
        || !snapshotJson.equals(GameplayRuleManifest.canonical(root)))
      throw new IllegalArgumentException("Selected manifest differs from complete snapshot");
  }

  public DraftCommitBinding binding() {
    var root = GameplayRuleManifest.tree(snapshotJson);
    return DraftCommitBinding.fromStored(text(root, "bindingJson"), text(root, "bindingDigest"));
  }

  public GameplayRuleManifest manifest() {
    return GameplayRuleManifest.fromStored(
        text(GameplayRuleManifest.tree(snapshotJson), "manifestJson"));
  }

  public byte[] canonicalBytes() {
    return snapshotJson.getBytes(StandardCharsets.UTF_8);
  }

  public String digest() {
    return GameplayRuleManifest.sha256(canonicalBytes());
  }

  private static String text(tools.jackson.databind.JsonNode node, String name) {
    if (!node.path(name).isTextual())
      throw new IllegalArgumentException("Text source field required");
    return node.path(name).textValue();
  }
}
