package net.firedevops.firemud.common.gamedesign;

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
    TemplateConfigSourceValues.uuid(Objects.requireNonNull(revisionId).toString());
    var revision =
        sourceBinding.revisions().stream()
            .filter(r -> r.owner() == Owner.GAME_DESIGN_CONTROL_PLANE)
            .filter(
                r -> r.revisionId().equals(revisionId) && r.revisionOrder().equals(revisionOrder))
            .findFirst()
            .orElseThrow();
    String expectedPayload =
        owner == Owner.AUTOMATION_SCRIPTING
            ? TemplateConfigSourceValues.ownerInventoryPayload(owner, inventory)
            : TemplateConfigSourceValues.ownerInventoryPayload(owner, entityInventory);
    if (!expectedPayload.equals(
        TemplateConfigSourceJson.canonical(TemplateConfigSourceJson.tree(revision.payload()))))
      throw new IllegalArgumentException(
          "Owner inventory differs from its exact original source revision");
  }

  public Map<String, Object> object() {
    return Map.of(
        "owner", owner.name(),
        "inventoryJson", inventoryJson(),
        "sourceBindingJson", sourceBinding.canonicalJson(),
        "sourceBindingDigest", sourceBinding.digest(),
        "revisionOrder", revisionOrder,
        "revisionId", revisionId.toString());
  }

  public static TemplateConfigOwnerSourceInventoryDeclaration fromStored(JsonNode node) {
    TemplateConfigSourceJson.requireStoredFields(
        node,
        "owner",
        "inventoryJson",
        "sourceBindingJson",
        "sourceBindingDigest",
        "revisionOrder",
        "revisionId");
    Owner owner = Owner.valueOf(TemplateConfigSourceJson.requiredStoredText(node, "owner"));
    String inventoryJson = TemplateConfigSourceJson.requiredStoredText(node, "inventoryJson");
    var binding =
        DraftCommitBinding.fromStored(
            TemplateConfigSourceJson.requiredStoredText(node, "sourceBindingJson"),
            TemplateConfigSourceJson.requiredStoredText(node, "sourceBindingDigest"));
    String revisionOrder = TemplateConfigSourceJson.requiredStoredText(node, "revisionOrder");
    UUID revisionId =
        TemplateConfigSourceValues.uuid(
            TemplateConfigSourceJson.requiredStoredText(node, "revisionId"));
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

  public String inventoryJson() {
    return owner == Owner.AUTOMATION_SCRIPTING
        ? inventory.canonicalJson()
        : entityInventory.canonicalJson();
  }
}
