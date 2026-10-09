package net.firedevops.firemud.gamedesign.publication;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;

/** Branding selected source only; no total inventory or template completeness claim. */
public record BrandingSourceSnapshot(
    DraftCommitBinding binding,
    String sourceEpoch,
    UUID inheritedCommitId,
    UUID genesisReceiptId,
    List<Item> items) {
  public BrandingSourceSnapshot {
    Objects.requireNonNull(binding);
    Objects.requireNonNull(genesisReceiptId);
    if (!sourceEpoch.matches("0|[1-9][0-9]*") || new UUID(0, 0).equals(genesisReceiptId))
      throw new IllegalArgumentException("Exact branding asset source epoch and genesis required");
    items = List.copyOf(items);
    new BrandingSource.Snapshot(binding, items.stream().map(Item::reference).toList());
    var ordered =
        items.stream()
            .sorted(
                java.util.Comparator.comparing(
                    item -> item.reference().usageKey(),
                    (String left, String right) ->
                        java.util.Arrays.compareUnsigned(
                            left.getBytes(StandardCharsets.UTF_8),
                            right.getBytes(StandardCharsets.UTF_8))))
            .toList();
    if (!items.equals(ordered))
      throw new IllegalArgumentException("Branding asset items must be in canonical key order");
  }

  public record Item(
      BrandingSource.Reference reference, String contentType, String contentDigest, long byteSize) {
    public Item {
      Objects.requireNonNull(reference);
      if (contentType == null
          || contentType.isBlank()
          || contentType.codePointCount(0, contentType.length()) > 100
          || contentDigest == null
          || !contentDigest.matches("sha256:[0-9a-f]{64}")
          || byteSize < 0)
        throw new IllegalArgumentException("Exact branding asset byte metadata required");
    }

    Map<String, Object> object() {
      var r = reference;
      return Map.ofEntries(
          Map.entry("family", r.family()),
          Map.entry("role", r.role().name()),
          Map.entry("usageKey", r.usageKey()),
          Map.entry("assetRowId", r.assetRowId()),
          Map.entry("requiredness", r.requiredness().name()),
          Map.entry("sourceBindingJson", r.sourceBinding().canonicalJson()),
          Map.entry("sourceBindingDigest", r.sourceBinding().digest()),
          Map.entry("revisionOrder", r.revisionOrder()),
          Map.entry("revisionId", r.revisionId().toString()),
          Map.entry("contentType", contentType),
          Map.entry("contentDigest", contentDigest),
          Map.entry("byteSize", Long.toString(byteSize)));
    }
  }

  public String canonicalJson() {
    return CommandSource.canonical(
        Map.of(
            "schema",
            "game-design-branding-asset-source-snapshot/v1",
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
            "roles",
            roleDeclarations(),
            "items",
            items.stream().map(Item::object).toList()));
  }

  /** Every role is explicitly declared against actual genesis and selected writer history. */
  public Map<String, String> roleDeclarations() {
    var result = new java.util.TreeMap<String, String>();
    for (var role : BrandingSource.Role.values())
      result.put(
          role.name(),
          items.stream().anyMatch(item -> item.reference().role() == role) ? "PRESENT" : "EMPTY");
    return Map.copyOf(result);
  }

  public byte[] canonicalBytes() {
    return canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  public String digest() {
    return CommandSource.sha256(canonicalBytes());
  }

  public static BrandingSourceSnapshot fromStored(String json) {
    var root = CommandSource.tree(json);
    CommandSource.requireStoredFields(
        root,
        "schema",
        "bindingJson",
        "bindingDigest",
        "sourceEpoch",
        "inheritedCommitId",
        "genesisReceiptId",
        "roles",
        "items");
    if (!"game-design-branding-asset-source-snapshot/v1"
            .equals(CommandSource.requiredStoredText(root, "schema"))
        || !root.path("items").isArray())
      throw new IllegalArgumentException("Unsupported branding asset snapshot");
    var binding =
        DraftCommitBinding.fromStored(
            CommandSource.requiredStoredText(root, "bindingJson"),
            CommandSource.requiredStoredText(root, "bindingDigest"));
    var items = new java.util.ArrayList<Item>();
    for (var node : root.path("items")) {
      CommandSource.requireStoredFields(
          node,
          "family",
          "role",
          "usageKey",
          "assetRowId",
          "requiredness",
          "sourceBindingJson",
          "sourceBindingDigest",
          "revisionOrder",
          "revisionId",
          "contentType",
          "contentDigest",
          "byteSize");
      if (!BrandingSource.FAMILY.equals(CommandSource.requiredStoredText(node, "family")))
        throw new IllegalArgumentException("Unsupported branding source family or role");
      var reference =
          new BrandingSource.Reference(
              CommandSource.requiredStoredText(node, "usageKey"),
              CommandSource.requiredStoredText(node, "assetRowId"),
              BrandingSource.Role.valueOf(CommandSource.requiredStoredText(node, "role")),
              BrandingSource.Requiredness.valueOf(
                  CommandSource.requiredStoredText(node, "requiredness")),
              DraftCommitBinding.fromStored(
                  CommandSource.requiredStoredText(node, "sourceBindingJson"),
                  CommandSource.requiredStoredText(node, "sourceBindingDigest")),
              CommandSource.requiredStoredText(node, "revisionOrder"),
              UUID.fromString(CommandSource.requiredStoredText(node, "revisionId")));
      items.add(
          new Item(
              reference,
              CommandSource.requiredStoredText(node, "contentType"),
              CommandSource.requiredStoredText(node, "contentDigest"),
              Long.parseLong(CommandSource.requiredStoredText(node, "byteSize"))));
    }
    if (!root.path("inheritedCommitId").isTextual())
      throw new IllegalArgumentException("Stored inherited commit must be text");
    String inherited = root.path("inheritedCommitId").textValue();
    var result =
        new BrandingSourceSnapshot(
            binding,
            CommandSource.requiredStoredText(root, "sourceEpoch"),
            inherited.isEmpty() ? null : UUID.fromString(inherited),
            UUID.fromString(CommandSource.requiredStoredText(root, "genesisReceiptId")),
            items);
    if (!result.canonicalJson().equals(json))
      throw new IllegalArgumentException("Noncanonical branding asset snapshot");
    return result;
  }

  public record Genesis(TargetProof target, UUID receiptId, String creationTransactionId) {
    public Genesis {
      Objects.requireNonNull(target);
      if (receiptId == null
          || new UUID(0, 0).equals(receiptId)
          || creationTransactionId == null
          || !creationTransactionId.matches("[1-9][0-9]*"))
        throw new IllegalArgumentException("Exact branding source creation evidence required");
    }

    public Map<String, String> roleDeclarations() {
      return BrandingSource.emptyRoleDeclarations();
    }
  }

  public record Capture(GameDesignPublicationOperation operation, BrandingSourceSnapshot snapshot) {
    public Capture {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(snapshot);
      if (!operation.account().input().selection().selectedCommit().equals(snapshot.binding()))
        throw new IllegalArgumentException("Branding asset capture differs from selected commit");
    }

    public byte[] canonicalBytes() {
      var out = new java.io.ByteArrayOutputStream();
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          out, "game-design-branding-asset-source-capture/v1");
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          out, operation.canonicalBytes());
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          out, snapshot.canonicalBytes());
      return out.toByteArray();
    }

    public String digest() {
      return CommandSource.sha256(canonicalBytes());
    }
  }
}
