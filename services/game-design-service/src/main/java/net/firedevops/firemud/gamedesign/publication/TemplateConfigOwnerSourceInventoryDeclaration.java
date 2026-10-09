package net.firedevops.firemud.gamedesign.publication;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import tools.jackson.databind.JsonNode;

/** Automation's actual authored inventory content and its immutable original Draft provenance. */
public record TemplateConfigOwnerSourceInventoryDeclaration(
    Owner owner,
    AutomationAuthoredSourceInventoryDeclaration inventory,
    DraftCommitBinding sourceBinding,
    String revisionOrder,
    UUID revisionId) {
  public TemplateConfigOwnerSourceInventoryDeclaration {
    if (owner != Owner.AUTOMATION_SCRIPTING)
      throw new IllegalArgumentException("Only Automation source inventory is supported");
    Objects.requireNonNull(inventory);
    Objects.requireNonNull(sourceBinding);
    if (revisionOrder == null || !revisionOrder.matches("0|[1-9][0-9]*"))
      throw new IllegalArgumentException("Exact owner source inventory revision order required");
    TemplateConfigSource.uuid(Objects.requireNonNull(revisionId).toString());
    var revision =
        sourceBinding.revisions().stream()
            .filter(r -> r.owner() == Owner.GAME_DESIGN_CONTROL_PLANE)
            .filter(
                r -> r.revisionId().equals(revisionId) && r.revisionOrder().equals(revisionOrder))
            .findFirst()
            .orElseThrow();
    if (!TemplateConfigSource.ownerInventoryPayload(owner, inventory)
        .equals(CommandSource.canonical(CommandSource.tree(revision.payload()))))
      throw new IllegalArgumentException(
          "Owner inventory differs from its exact original source revision");
  }

  Map<String, Object> object() {
    return Map.of(
        "owner", owner.name(),
        "inventoryJson", inventory.canonicalJson(),
        "sourceBindingJson", sourceBinding.canonicalJson(),
        "sourceBindingDigest", sourceBinding.digest(),
        "revisionOrder", revisionOrder,
        "revisionId", revisionId.toString());
  }

  static TemplateConfigOwnerSourceInventoryDeclaration fromStored(JsonNode node) {
    CommandSource.requireStoredFields(
        node,
        "owner",
        "inventoryJson",
        "sourceBindingJson",
        "sourceBindingDigest",
        "revisionOrder",
        "revisionId");
    Owner owner = Owner.valueOf(CommandSource.requiredStoredText(node, "owner"));
    return new TemplateConfigOwnerSourceInventoryDeclaration(
        owner,
        AutomationAuthoredSourceInventoryDeclaration.parse(
            CommandSource.requiredStoredText(node, "inventoryJson")),
        DraftCommitBinding.fromStored(
            CommandSource.requiredStoredText(node, "sourceBindingJson"),
            CommandSource.requiredStoredText(node, "sourceBindingDigest")),
        CommandSource.requiredStoredText(node, "revisionOrder"),
        TemplateConfigSource.uuid(CommandSource.requiredStoredText(node, "revisionId")));
  }
}
