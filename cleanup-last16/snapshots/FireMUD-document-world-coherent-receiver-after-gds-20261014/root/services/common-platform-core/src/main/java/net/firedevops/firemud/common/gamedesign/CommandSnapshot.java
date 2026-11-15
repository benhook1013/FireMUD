package net.firedevops.firemud.common.gamedesign;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;

/** Complete effective command set captured at one actual synchronized Draft commit. */
public record CommandSnapshot(
    DraftCommitBinding binding,
    String sourceEpoch,
    UUID inheritedCommitId,
    String genesisReceiptDigest,
    List<CommandSource.Definition> definitions) {
  public CommandSnapshot {
    Objects.requireNonNull(binding, "binding");
    requireCounter(sourceEpoch, "sourceEpoch");
    if (inheritedCommitId != null && new UUID(0, 0).equals(inheritedCommitId)) {
      throw new IllegalArgumentException("inheritedCommitId must be non-nil when present");
    }
    if (genesisReceiptDigest == null || !genesisReceiptDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Exact command source genesis receipt digest required");
    }
    definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
    CommandSource.requireCanonicalDefinitions(definitions);
  }

  @Override
  public List<CommandSource.Definition> definitions() {
    return List.copyOf(definitions);
  }

  public String canonicalJson() {
    return CommandSource.canonical(
        Map.of(
            "schema",
            CommandSource.SNAPSHOT_SCHEMA,
            "bindingJson",
            binding.canonicalJson(),
            "bindingDigest",
            binding.digest(),
            "sourceEpoch",
            sourceEpoch,
            "inheritedCommitId",
            inheritedCommitId == null ? "" : inheritedCommitId.toString(),
            "genesisReceiptDigest",
            genesisReceiptDigest,
            "definitions",
            CommandSource.tree(CommandSource.definitionsJson(definitions))));
  }

  public byte[] canonicalBytes() {
    return canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  public String digest() {
    return CommandSource.sha256(canonicalBytes());
  }

  public static CommandSnapshot fromStored(String json) {
    var root = CommandSource.tree(json);
    CommandSource.requireStoredFields(
        root,
        "schema",
        "bindingJson",
        "bindingDigest",
        "sourceEpoch",
        "inheritedCommitId",
        "genesisReceiptDigest",
        "definitions");
    if (!CommandSource.SNAPSHOT_SCHEMA.equals(CommandSource.requiredStoredText(root, "schema"))) {
      throw new IllegalArgumentException("Unsupported command source snapshot schema");
    }
    var inheritedNode = root.get("inheritedCommitId");
    if (inheritedNode == null || !inheritedNode.isTextual()) {
      throw new IllegalArgumentException("Stored command source inheritedCommitId must be text");
    }
    String inherited = inheritedNode.textValue();
    UUID inheritedCommitId = inherited.isEmpty() ? null : UUID.fromString(inherited);
    if (inheritedCommitId != null && !inheritedCommitId.toString().equals(inherited)) {
      throw new IllegalArgumentException(
          "Stored command source inheritedCommitId must be a canonical UUID");
    }
    var result =
        new CommandSnapshot(
            DraftCommitBinding.fromStored(
                CommandSource.requiredStoredText(root, "bindingJson"),
                CommandSource.requiredStoredText(root, "bindingDigest")),
            CommandSource.requiredStoredText(root, "sourceEpoch"),
            inheritedCommitId,
            CommandSource.requiredStoredText(root, "genesisReceiptDigest"),
            CommandSource.definitionsFromStored(root.get("definitions").toString()));
    if (!result.canonicalJson().equals(json)) {
      throw new IllegalArgumentException(
          "Stored command source snapshot is not exact canonical JSON");
    }
    return result;
  }

  private static void requireCounter(String value, String name) {
    if (value == null || !value.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException(name + " must be a canonical nonnegative decimal");
    }
  }
}
