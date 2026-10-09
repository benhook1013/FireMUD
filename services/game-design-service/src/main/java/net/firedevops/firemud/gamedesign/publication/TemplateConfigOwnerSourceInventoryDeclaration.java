package net.firedevops.firemud.gamedesign.publication;

import java.util.Map;
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
    new net.firedevops.firemud.common.gamedesign.TemplateConfigOwnerSourceInventoryDeclaration(
        owner, inventory, entityInventory, sourceBinding, revisionOrder, revisionId);
  }

  Map<String, Object> object() {
    return shared().object();
  }

  static TemplateConfigOwnerSourceInventoryDeclaration fromStored(JsonNode node) {
    return fromShared(
        net.firedevops.firemud.common.gamedesign.TemplateConfigOwnerSourceInventoryDeclaration
            .fromStored(node));
  }

  String inventoryJson() {
    return shared().inventoryJson();
  }

  net.firedevops.firemud.common.gamedesign.TemplateConfigOwnerSourceInventoryDeclaration shared() {
    return new net.firedevops.firemud.common.gamedesign
        .TemplateConfigOwnerSourceInventoryDeclaration(
        owner, inventory, entityInventory, sourceBinding, revisionOrder, revisionId);
  }

  static TemplateConfigOwnerSourceInventoryDeclaration fromShared(
      net.firedevops.firemud.common.gamedesign.TemplateConfigOwnerSourceInventoryDeclaration
          value) {
    return new TemplateConfigOwnerSourceInventoryDeclaration(
        value.owner(),
        value.inventory(),
        value.entityInventory(),
        value.sourceBinding(),
        value.revisionOrder(),
        value.revisionId());
  }
}
