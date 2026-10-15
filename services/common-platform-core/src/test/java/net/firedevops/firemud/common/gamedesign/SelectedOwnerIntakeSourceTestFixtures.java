package net.firedevops.firemud.common.gamedesign;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;

/** Synthetic canonical typed source snapshots for selected-owner Common tests. */
public final class SelectedOwnerIntakeSourceTestFixtures {
  private static final UUID GENESIS = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final String GENESIS_DIGEST = "sha256:" + "a".repeat(64);

  private SelectedOwnerIntakeSourceTestFixtures() {}

  public static byte[] snapshot(String family, DraftCommitBinding selected) {
    return snapshot(family, selected, 0);
  }

  /** Canonical delete of a synthetic command for otherwise inert selected-source bindings. */
  public static String selectedRevisionPayload() {
    return CommandSource.deletePayload("synthetic-fixture-command");
  }

  /** Uses a valid, oversized command field when the transport test needs a large response body. */
  public static byte[] snapshot(
      String family, DraftCommitBinding selected, int largeCommandFieldSize) {
    return switch (family) {
      case "COMMAND" -> command(selected, largeCommandFieldSize).canonicalBytes();
      case "REALM_POLICY" -> new RealmPolicySnapshot(selected, "0", List.of()).canonicalBytes();
      case "ASSET" -> new AssetSnapshot(selected, "0", null, GENESIS, List.of()).canonicalBytes();
      case "BRANDING" ->
          new BrandingSourceSnapshot(selected, "0", null, GENESIS, List.of()).canonicalBytes();
      case "GAMEPLAY_RULE" -> gameplay(selected).canonicalBytes();
      case "TEMPLATE_CONFIG" ->
          new TemplateConfigSourceSnapshot(selected, "0", null, GENESIS, List.of())
              .canonicalBytes();
      default -> throw new IllegalArgumentException("Unsupported selected source fixture family");
    };
  }

  private static CommandSnapshot command(DraftCommitBinding selected, int largeCommandFieldSize) {
    if (largeCommandFieldSize < 0)
      throw new IllegalArgumentException("Command field size must be nonnegative");
    List<CommandSource.Definition> definitions =
        largeCommandFieldSize == 0
            ? List.of()
            : List.of(
                new CommandSource.Definition(
                    "synthetic-large-command",
                    CommandSource.canonical(
                        Map.ofEntries(
                            Map.entry("schemaVersion", 1),
                            Map.entry("commandId", "synthetic-large-command"),
                            Map.entry("semanticOwner", "x".repeat(largeCommandFieldSize)),
                            Map.entry("executionDiscipline", "DURABLE_GAMEPLAY"),
                            Map.entry("stageRequirement", "NONE"),
                            Map.entry("promptPolicy", "NEVER"),
                            Map.entry("actionCategory", "META"),
                            Map.entry("historyRecordable", false),
                            Map.entry("aliases", List.of()),
                            Map.entry("actionTags", List.of()),
                            Map.entry("effects", List.of()))),
                    selected.commitId(),
                    selected.revisions().getFirst().revisionId(),
                    selected.revisions().getFirst().revisionOrder()));
    return new CommandSnapshot(selected, "0", null, GENESIS_DIGEST, definitions);
  }

  private static GameplayRuleSelectedSource gameplay(DraftCommitBinding selected) {
    return new GameplayRuleSelectedSource(
        GameplayRuleManifest.canonical(
            Map.of(
                "schema", "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson", selected.canonicalJson(),
                "bindingDigest", selected.digest(),
                "sourceEpoch", "0",
                "inheritedCommitId", "",
                "genesisReceiptId", GENESIS.toString(),
                "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                "entries", List.of())));
  }
}
