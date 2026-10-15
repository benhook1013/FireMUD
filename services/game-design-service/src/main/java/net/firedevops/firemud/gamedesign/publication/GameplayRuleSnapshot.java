package net.firedevops.firemud.gamedesign.publication;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;

/** Complete authored source and immutable original revisions, not a GL retained-source receipt. */
public record GameplayRuleSnapshot(
    DraftCommitBinding binding,
    String sourceEpoch,
    UUID inheritedCommitId,
    UUID genesisReceiptId,
    List<GameplayRuleSource.Entry> entries) {
  public static final String SCHEMA = "game-design-gameplay-rule-source-snapshot/v1";

  public GameplayRuleSnapshot {
    Objects.requireNonNull(binding);
    counter(sourceEpoch);
    DraftAuthorizationFenceBinding.requireUuid(genesisReceiptId);
    if (inheritedCommitId != null) DraftAuthorizationFenceBinding.requireUuid(inheritedCommitId);
    entries = List.copyOf(entries);
    for (var entry : entries)
      if (!entry.sourceBinding().target().equals(binding.target()))
        throw new IllegalArgumentException("Rule source belongs to another canonical target");
    GameplayRuleSource.manifest(entries);
  }

  public GameplayRuleManifest manifest() {
    return GameplayRuleSource.manifest(entries);
  }

  public String canonicalJson() {
    return GameplayRuleManifest.canonical(
        Map.of(
            "schema",
            SCHEMA,
            "bindingJson",
            binding.canonicalJson(),
            "bindingDigest",
            binding.digest(),
            "sourceEpoch",
            sourceEpoch,
            "inheritedCommitId",
            inheritedCommitId == null ? "" : inheritedCommitId.toString(),
            "genesisReceiptId",
            genesisReceiptId.toString(),
            "manifestJson",
            manifest().canonicalJson(),
            "entries",
            entries.stream().map(GameplayRuleSource.Entry::object).toList()));
  }

  public byte[] canonicalBytes() {
    return canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  public String digest() {
    return GameplayRuleManifest.sha256(canonicalBytes());
  }

  public static GameplayRuleSnapshot fromStored(String json) {
    var root = GameplayRuleManifest.tree(json);
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
    if (!SCHEMA.equals(GameplayRuleSource.text(root, "schema")) || !root.path("entries").isArray())
      throw new IllegalArgumentException("Unsupported rule snapshot");
    var binding =
        DraftCommitBinding.fromStored(
            GameplayRuleSource.text(root, "bindingJson"),
            GameplayRuleSource.text(root, "bindingDigest"));
    List<GameplayRuleSource.Entry> entries = new ArrayList<>();
    for (var node : root.path("entries")) {
      GameplayRuleManifest.fields(
          node,
          Set.of(
              "family",
              "definitionJson",
              "sourceBindingJson",
              "sourceBindingDigest",
              "revisionOrder",
              "revisionId"));
      Family family = Family.valueOf(GameplayRuleSource.text(node, "family"));
      entries.add(
          new GameplayRuleSource.Entry(
              family,
              GameplayRuleManifest.definition(
                  family, GameplayRuleSource.text(node, "definitionJson")),
              DraftCommitBinding.fromStored(
                  GameplayRuleSource.text(node, "sourceBindingJson"),
                  GameplayRuleSource.text(node, "sourceBindingDigest")),
              GameplayRuleSource.text(node, "revisionOrder"),
              UUID.fromString(GameplayRuleSource.text(node, "revisionId"))));
    }
    if (!root.path("inheritedCommitId").isTextual())
      throw new IllegalArgumentException("Invalid predecessor");
    String inherited = root.path("inheritedCommitId").textValue();
    var result =
        new GameplayRuleSnapshot(
            binding,
            GameplayRuleSource.text(root, "sourceEpoch"),
            inherited.isEmpty() ? null : UUID.fromString(inherited),
            UUID.fromString(GameplayRuleSource.text(root, "genesisReceiptId")),
            entries);
    if (!result
            .manifest()
            .equals(GameplayRuleManifest.fromStored(GameplayRuleSource.text(root, "manifestJson")))
        || !result.canonicalJson().equals(json))
      throw new IllegalArgumentException("Changed rule snapshot bytes");
    return result;
  }

  public record Genesis(
      TargetProof target,
      UUID receiptId,
      String creationTransactionId,
      GameplayRuleManifest inventory) {
    public Genesis {
      Objects.requireNonNull(target);
      DraftAuthorizationFenceBinding.requireUuid(receiptId);
      if (creationTransactionId == null
          || !creationTransactionId.matches("[1-9][0-9]*")
          || !GameplayRuleManifest.explicitEmpty().equals(inventory))
        throw new IllegalArgumentException(
            "Actual fresh creation requires complete empty inventory");
    }
  }

  public record Application(
      DraftCommitBinding binding, String expectedEpoch, GameplayRuleSnapshot snapshot) {
    public Application {
      counter(expectedEpoch);
      Objects.requireNonNull(snapshot);
      if (!binding.equals(snapshot.binding())
          || GameplayRuleSource.mutations(binding).isEmpty()
          || !new java.math.BigInteger(expectedEpoch)
              .add(java.math.BigInteger.ONE)
              .toString()
              .equals(snapshot.sourceEpoch()))
        throw new IllegalArgumentException(
            "Rule application must advance its original bound epoch");
    }

    public AppliedEpoch appliedEpoch() {
      return new AppliedEpoch(
          GameplayRuleSource.SCOPE,
          binding.target().canonicalVersionId().toString(),
          GameplayRuleSource.SCOPE,
          GameplayRuleSource.SCOPE_ID,
          expectedEpoch,
          snapshot.sourceEpoch());
    }

    public byte[] canonicalBytes() {
      var out = new java.io.ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, "game-design-gameplay-rule-source-application/v1");
      DraftAuthorizationFenceBinding.frame(out, expectedEpoch);
      DraftAuthorizationFenceBinding.frame(out, snapshot.canonicalBytes());
      return out.toByteArray();
    }
  }

  private static void counter(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]*"))
      throw new IllegalArgumentException("Invalid source epoch");
  }
}
