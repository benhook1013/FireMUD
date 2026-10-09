package net.firedevops.firemud.gamedesign.publication;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;

/** Actual template wiring history only; no Game Logic receipt or total-release completeness. */
public record TemplateConfigSourceSnapshot(
    DraftCommitBinding binding,
    String sourceEpoch,
    UUID inheritedCommitId,
    UUID genesisReceiptId,
    List<TemplateConfigSource.Entry> entries,
    List<TemplateConfigOwnerSourceInventoryDeclaration> ownerSourceInventoryDeclarations) {
  public static final String SCHEMA = "game-design-template-config-source-snapshot/v1";
  public static final String SCHEMA_V2 = "game-design-template-config-source-snapshot/v2";

  public TemplateConfigSourceSnapshot(
      DraftCommitBinding binding,
      String sourceEpoch,
      UUID inheritedCommitId,
      UUID genesisReceiptId,
      List<TemplateConfigSource.Entry> entries) {
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
                entries.stream().map(TemplateConfigSource.Entry::object).toList()));
    if (!ownerSourceInventoryDeclarations.isEmpty())
      value.put(
          "ownerSourceInventoryDeclarations",
          ownerSourceInventoryDeclarations.stream()
              .map(TemplateConfigOwnerSourceInventoryDeclaration::object)
              .toList());
    return CommandSource.canonical(value);
  }

  public byte[] canonicalBytes() {
    return canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  public String digest() {
    return CommandSource.sha256(canonicalBytes());
  }

  public static TemplateConfigSourceSnapshot fromStored(String json) {
    var root = CommandSource.tree(json);
    String schema = CommandSource.requiredStoredText(root, "schema");
    if (SCHEMA.equals(schema)) {
      CommandSource.requireStoredFields(
          root,
          "schema",
          "bindingJson",
          "bindingDigest",
          "sourceEpoch",
          "inheritedCommitId",
          "genesisReceiptId",
          "entries");
    } else if (SCHEMA_V2.equals(schema)) {
      CommandSource.requireStoredFields(
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
            CommandSource.requiredStoredText(root, "bindingJson"),
            CommandSource.requiredStoredText(root, "bindingDigest"));
    List<TemplateConfigSource.Entry> entries = new ArrayList<>();
    for (var node : root.path("entries")) {
      CommandSource.requireStoredFields(
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
          new TemplateConfigSource.Entry(
              CommandSource.requiredStoredText(node, "templateId"),
              new TemplateConfigSource.Config(CommandSource.requiredStoredText(node, "configJson")),
              DraftCommitBinding.fromStored(
                  CommandSource.requiredStoredText(node, "sourceBindingJson"),
                  CommandSource.requiredStoredText(node, "sourceBindingDigest")),
              CommandSource.requiredStoredText(node, "revisionOrder"),
              TemplateConfigSource.uuid(CommandSource.requiredStoredText(node, "revisionId")),
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
            CommandSource.requiredStoredText(root, "sourceEpoch"),
            inherited.isEmpty() ? null : TemplateConfigSource.uuid(inherited),
            TemplateConfigSource.uuid(CommandSource.requiredStoredText(root, "genesisReceiptId")),
            entries,
            declarations);
    if ((SCHEMA.equals(schema) && !result.ownerSourceInventoryDeclarations().isEmpty())
        || (SCHEMA_V2.equals(schema) && result.ownerSourceInventoryDeclarations().isEmpty()))
      throw new IllegalArgumentException("Template config source snapshot schema mismatch");
    if (!json.equals(result.canonicalJson()))
      throw new IllegalArgumentException("Noncanonical template config snapshot");
    return result;
  }

  public record Genesis(TargetProof target, UUID receiptId, String creationTransactionId) {
    public Genesis {
      Objects.requireNonNull(target);
      DraftAuthorizationFenceBinding.requireUuid(receiptId);
      if (creationTransactionId == null || !creationTransactionId.matches("[1-9][0-9]*"))
        throw new IllegalArgumentException(
            "Actual fresh template source creation witness required");
    }
  }

  public record Application(
      DraftCommitBinding binding, String expectedEpoch, TemplateConfigSourceSnapshot snapshot) {
    public Application {
      Objects.requireNonNull(binding);
      if (expectedEpoch == null
          || !expectedEpoch.matches("0|[1-9][0-9]*")
          || !binding.equals(snapshot.binding())
          || TemplateConfigSource.mutations(binding).isEmpty()
          || !new BigInteger(expectedEpoch)
              .add(BigInteger.ONE)
              .toString()
              .equals(snapshot.sourceEpoch()))
        throw new IllegalArgumentException(
            "Exact template source application must advance its epoch");
    }

    public AppliedEpoch appliedEpoch() {
      return new AppliedEpoch(
          TemplateConfigSource.SCOPE,
          binding.target().canonicalVersionId().toString(),
          TemplateConfigSource.SCOPE,
          TemplateConfigSource.SCOPE_ID,
          expectedEpoch,
          snapshot.sourceEpoch());
    }

    public byte[] canonicalBytes() {
      var out = new java.io.ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(
          out, "game-design-template-config-source-application/v1");
      DraftAuthorizationFenceBinding.frame(out, expectedEpoch);
      DraftAuthorizationFenceBinding.frame(out, snapshot.canonicalBytes());
      return out.toByteArray();
    }
  }

  public record Capture(
      GameDesignPublicationOperation operation, TemplateConfigSourceSnapshot snapshot) {
    public Capture {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(snapshot);
      if (!operation.account().input().selection().selectedCommit().equals(snapshot.binding()))
        throw new IllegalArgumentException(
            "Template source differs from exact publication selection");
    }

    public byte[] canonicalBytes() {
      var out = new java.io.ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, "game-design-template-config-source-capture/v1");
      DraftAuthorizationFenceBinding.frame(out, operation.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, snapshot.canonicalBytes());
      return out.toByteArray();
    }

    public String digest() {
      return CommandSource.sha256(canonicalBytes());
    }
  }
}
