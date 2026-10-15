package net.firedevops.firemud.common.gamedesign;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;

/** Actual template wiring history only; no Game Logic receipt or total-release completeness. */
public record TemplateConfigSourceSnapshot(
    DraftCommitBinding binding,
    String sourceEpoch,
    UUID inheritedCommitId,
    UUID genesisReceiptId,
    List<TemplateConfigSourceValues.Entry> entries,
    List<TemplateConfigOwnerSourceInventoryDeclaration> ownerSourceInventoryDeclarations) {
  public static final String SCHEMA = "game-design-template-config-source-snapshot/v1";
  public static final String SCHEMA_V2 = "game-design-template-config-source-snapshot/v2";

  public TemplateConfigSourceSnapshot(
      DraftCommitBinding binding,
      String sourceEpoch,
      UUID inheritedCommitId,
      UUID genesisReceiptId,
      List<TemplateConfigSourceValues.Entry> entries) {
    this(binding, sourceEpoch, inheritedCommitId, genesisReceiptId, entries, List.of());
  }

  public TemplateConfigSourceSnapshot {
    Objects.requireNonNull(binding);
    if (sourceEpoch == null || !sourceEpoch.matches("0|[1-9][0-9]*"))
      throw new IllegalArgumentException("Exact template source epoch required");
    DraftAuthorizationFenceBinding.requireUuid(genesisReceiptId);
    if (inheritedCommitId != null) DraftAuthorizationFenceBinding.requireUuid(inheritedCommitId);
    entries = List.copyOf(entries);
    var keys = new java.util.HashSet<String>();
    for (var entry : entries)
      if (!entry.sourceBinding().target().equals(binding.target()) || !keys.add(entry.templateId()))
        throw new IllegalArgumentException("Template source target or identity conflict");
    if (!entries.equals(
        entries.stream()
            .sorted(java.util.Comparator.comparing(e -> new BigInteger(e.templateId())))
            .toList())) throw new IllegalArgumentException("Canonical template row order required");
    ownerSourceInventoryDeclarations = List.copyOf(ownerSourceInventoryDeclarations);
    var owners =
        new java.util.HashSet<net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner>();
    for (var declaration : ownerSourceInventoryDeclarations) {
      if (!declaration.sourceBinding().target().equals(binding.target())
          || !owners.add(declaration.owner()))
        throw new IllegalArgumentException("Template owner inventory target or identity conflict");
    }
    if (!ownerSourceInventoryDeclarations.equals(
        ownerSourceInventoryDeclarations.stream()
            .sorted(java.util.Comparator.comparing(value -> value.owner().name()))
            .toList()))
      throw new IllegalArgumentException("Canonical owner inventory order required");
  }

  public String canonicalJson() {
    Map<String, Object> value =
        new java.util.LinkedHashMap<>(
            Map.of(
                "schema",
                ownerSourceInventoryDeclarations.isEmpty() ? SCHEMA : SCHEMA_V2,
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
                "entries",
                entries.stream().map(TemplateConfigSourceValues.Entry::object).toList()));
    if (!ownerSourceInventoryDeclarations.isEmpty())
      value.put(
          "ownerSourceInventoryDeclarations",
          ownerSourceInventoryDeclarations.stream()
              .map(TemplateConfigOwnerSourceInventoryDeclaration::object)
              .toList());
    return TemplateConfigSourceJson.canonical(value);
  }

  public byte[] canonicalBytes() {
    return canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  public String digest() {
    return TemplateConfigSourceJson.sha256(canonicalBytes());
  }

  public static TemplateConfigSourceSnapshot fromStored(String json) {
    var root = TemplateConfigSourceJson.tree(json);
    String schema = TemplateConfigSourceJson.requiredStoredText(root, "schema");
    if (SCHEMA.equals(schema)) {
      TemplateConfigSourceJson.requireStoredFields(
          root,
          "schema",
          "bindingJson",
          "bindingDigest",
          "sourceEpoch",
          "inheritedCommitId",
          "genesisReceiptId",
          "entries");
    } else if (SCHEMA_V2.equals(schema)) {
      TemplateConfigSourceJson.requireStoredFields(
          root,
          "schema",
          "bindingJson",
          "bindingDigest",
          "sourceEpoch",
          "inheritedCommitId",
          "genesisReceiptId",
          "entries",
          "ownerSourceInventoryDeclarations");
    } else {
      throw new IllegalArgumentException("Unsupported template config source snapshot");
    }
    if (!root.path("entries").isArray()
        || (SCHEMA_V2.equals(schema) && !root.path("ownerSourceInventoryDeclarations").isArray()))
      throw new IllegalArgumentException(
          "Template config source snapshot collections are required");
    var binding =
        DraftCommitBinding.fromStored(
            TemplateConfigSourceJson.requiredStoredText(root, "bindingJson"),
            TemplateConfigSourceJson.requiredStoredText(root, "bindingDigest"));
    List<TemplateConfigSourceValues.Entry> entries = new ArrayList<>();
    for (var node : root.path("entries")) {
      TemplateConfigSourceJson.requireStoredFields(
          node,
          "templateId",
          "configJson",
          "sourceBindingJson",
          "sourceBindingDigest",
          "revisionOrder",
          "revisionId",
          "createdName");
      if (!node.path("createdName").isTextual())
        throw new IllegalArgumentException("Explicit source operation identity required");
      String name = node.path("createdName").textValue();
      entries.add(
          new TemplateConfigSourceValues.Entry(
              TemplateConfigSourceJson.requiredStoredText(node, "templateId"),
              new TemplateConfigSourceValues.Config(
                  TemplateConfigSourceJson.requiredStoredText(node, "configJson")),
              DraftCommitBinding.fromStored(
                  TemplateConfigSourceJson.requiredStoredText(node, "sourceBindingJson"),
                  TemplateConfigSourceJson.requiredStoredText(node, "sourceBindingDigest")),
              TemplateConfigSourceJson.requiredStoredText(node, "revisionOrder"),
              TemplateConfigSourceValues.uuid(
                  TemplateConfigSourceJson.requiredStoredText(node, "revisionId")),
              name.isEmpty() ? null : name));
    }
    List<TemplateConfigOwnerSourceInventoryDeclaration> declarations = new ArrayList<>();
    if (SCHEMA_V2.equals(schema))
      for (var node : root.path("ownerSourceInventoryDeclarations"))
        declarations.add(TemplateConfigOwnerSourceInventoryDeclaration.fromStored(node));
    if (!root.path("inheritedCommitId").isTextual())
      throw new IllegalArgumentException("Explicit predecessor required");
    String inherited = root.path("inheritedCommitId").textValue();
    var result =
        new TemplateConfigSourceSnapshot(
            binding,
            TemplateConfigSourceJson.requiredStoredText(root, "sourceEpoch"),
            inherited.isEmpty() ? null : TemplateConfigSourceValues.uuid(inherited),
            TemplateConfigSourceValues.uuid(
                TemplateConfigSourceJson.requiredStoredText(root, "genesisReceiptId")),
            entries,
            declarations);
    if ((SCHEMA.equals(schema) && !result.ownerSourceInventoryDeclarations().isEmpty())
        || (SCHEMA_V2.equals(schema) && result.ownerSourceInventoryDeclarations().isEmpty()))
      throw new IllegalArgumentException("Template config source snapshot schema mismatch");
    if (!json.equals(result.canonicalJson()))
      throw new IllegalArgumentException("Noncanonical template config snapshot");
    return result;
  }
}
