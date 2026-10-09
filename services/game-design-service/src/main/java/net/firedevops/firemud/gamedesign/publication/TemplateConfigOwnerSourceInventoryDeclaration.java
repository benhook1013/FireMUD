package net.firedevops.firemud.gamedesign.publication;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration;
import tools.jackson.databind.JsonNode;

/** Typed owner-authored inventory content and its immutable original Draft provenance. */
public record TemplateConfigOwnerSourceInventoryDeclaration(
    Owner owner,
    AutomationAuthoredSourceInventoryDeclaration inventory,
    EntityAuthoredSourceInventoryDeclaration entityInventory,
    DraftCommitBinding sourceBinding,
    String revisionOrder,
    UUID revisionId) {
  public TemplateConfigOwnerSourceInventoryDeclaration(
      Owner owner,
      AutomationAuthoredSourceInventoryDeclaration inventory,
      DraftCommitBinding sourceBinding,
      String revisionOrder,
      UUID revisionId) {
    this(owner, inventory, null, sourceBinding, revisionOrder, revisionId);
  }

  public TemplateConfigOwnerSourceInventoryDeclaration(
      Owner owner,
      EntityAuthoredSourceInventoryDeclaration inventory,
      DraftCommitBinding sourceBinding,
      String revisionOrder,
      UUID revisionId) {
    this(owner, null, inventory, sourceBinding, revisionOrder, revisionId);
  }

  public TemplateConfigOwnerSourceInventoryDeclaration {
    if (owner == Owner.AUTOMATION_SCRIPTING) {
      if (inventory == null || entityInventory != null)
        throw new IllegalArgumentException("Automation owner requires its typed inventory");
    } else if (owner == Owner.ENTITY_MANAGEMENT) {
      if (entityInventory == null || inventory != null)
        throw new IllegalArgumentException("Entity owner requires its typed inventory");
    } else {
      throw new IllegalArgumentException("Unsupported owner source inventory content");
    }
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
    String expectedPayload =
        owner == Owner.AUTOMATION_SCRIPTING
            ? TemplateConfigSource.ownerInventoryPayload(owner, inventory)
            : TemplateConfigSource.ownerInventoryPayload(owner, entityInventory);
    if (!expectedPayload.equals(CommandSource.canonical(CommandSource.tree(revision.payload()))))
      throw new IllegalArgumentException(
          "Owner inventory differs from its exact original source revision");
  }

  Map<String, Object> object() {
    return Map.of(
        "owner", owner.name(),
        "inventoryJson", inventoryJson(),
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
    String inventoryJson = CommandSource.requiredStoredText(node, "inventoryJson");
    var binding =
        DraftCommitBinding.fromStored(
            CommandSource.requiredStoredText(node, "sourceBindingJson"),
            CommandSource.requiredStoredText(node, "sourceBindingDigest"));
    String revisionOrder = CommandSource.requiredStoredText(node, "revisionOrder");
    UUID revisionId =
        TemplateConfigSource.uuid(CommandSource.requiredStoredText(node, "revisionId"));
    return switch (owner) {
      case AUTOMATION_SCRIPTING ->
          new TemplateConfigOwnerSourceInventoryDeclaration(
              owner,
              AutomationAuthoredSourceInventoryDeclaration.parse(inventoryJson),
              binding,
              revisionOrder,
              revisionId);
      case ENTITY_MANAGEMENT ->
          new TemplateConfigOwnerSourceInventoryDeclaration(
              owner,
              EntityAuthoredSourceInventoryDeclaration.parse(inventoryJson),
              binding,
              revisionOrder,
              revisionId);
      default -> throw new IllegalArgumentException("Unsupported owner source inventory content");
    };
  }

  String inventoryJson() {
    return owner == Owner.AUTOMATION_SCRIPTING
        ? inventory.canonicalJson()
        : entityInventory.canonicalJson();
  }
}
