package net.firedevops.firemud.common.gamedesign;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import tools.jackson.databind.JsonNode;

/** Closed authored wiring. Syntax validation never qualifies an external owner's reference. */
public final class TemplateConfigSourceValues {
  public static final String REVISION_KIND = "TEMPLATE_CONFIG";
  public static final String SCOPE = "TEMPLATE_CONFIG_SET";
  public static final String SCOPE_ID = "effective";

  private TemplateConfigSourceValues() {}

  public enum OperationKind {
    CREATE,
    UPSERT,
    DELETE,
    DECLARE_OWNER_SOURCE_INVENTORY
  }

  public record GameplayInput(Family family, String key, UUID revisionId) {
    public GameplayInput {
      Objects.requireNonNull(family);
      GameplayRuleManifest.identifier(key);
      uuid(revisionId.toString());
    }
  }

  /** Canonical config remains the actual authored document, without derived commit evidence. */
  public record Config(String canonicalJson) {
    public Config {
      JsonNode root = TemplateConfigSourceJson.tree(canonicalJson);
      fields(
          root,
          "schemaVersion",
          "baseVersionId",
          "world",
          "entity",
          "gameLogic",
          "automation",
          "supportedSettings");
      if (!root.path("schemaVersion").isInt() || root.path("schemaVersion").intValue() != 1)
        throw new IllegalArgumentException("Unsupported template config schemaVersion");
      uuid(text(root, "baseVersionId"));
      fields(root.path("world"), "regions", "rooms");
      ownerIds(root.path("world"), "regions", "regionTemplateId");
      ownerIds(root.path("world"), "rooms", "roomTemplateId");
      fields(root.path("entity"), "items", "npcs");
      ownerIds(root.path("entity"), "items", "entityTemplateId");
      ownerIds(root.path("entity"), "npcs", "entityTemplateId");
      fields(root.path("gameLogic"), "inputs");
      Set<String> rules = new HashSet<>();
      for (JsonNode node : array(root.path("gameLogic"), "inputs")) {
        fields(node, "family", "key", "revisionId");
        Family family = Family.valueOf(text(node, "family"));
        String key = text(node, "key");
        GameplayRuleManifest.identifier(key);
        uuid(text(node, "revisionId"));
        if (!rules.add(family.name() + ":" + key))
          throw new IllegalArgumentException("Duplicate template gameplay input");
      }
      fields(root.path("automation"), "scripts", "scriptPatch");
      Set<String> scripts = new HashSet<>();
      for (JsonNode node : array(root.path("automation"), "scripts")) {
        fields(node, "scriptId", "eventBindings");
        if (!scripts.add(text(node, "scriptId")))
          throw new IllegalArgumentException("Duplicate template script name");
        Set<String> bindings = new HashSet<>();
        for (JsonNode event : array(node, "eventBindings")) {
          fields(
              event,
              "eventType",
              "eventSchemaVersion",
              "bindingId",
              "targetScopeType",
              "targetScopeId");
          text(event, "eventType");
          text(event, "eventSchemaVersion");
          for (String key : List.of("bindingId", "targetScopeType", "targetScopeId"))
            if (!event.path(key).isTextual())
              throw new IllegalArgumentException("Exact Automation binding key required");
          if (!bindings.add(TemplateConfigSourceJson.canonical(event)))
            throw new IllegalArgumentException("Duplicate template event binding");
        }
      }
      JsonNode patch = root.path("automation").path("scriptPatch");
      String presence = text(patch, "presence");
      if ("ABSENT".equals(presence)) fields(patch, "presence");
      else if ("PRESENT".equals(presence)) {
        fields(patch, "presence", "scriptPatchVersion");
        text(patch, "scriptPatchVersion");
      } else throw new IllegalArgumentException("Explicit template script-patch presence required");
      if (!array(root, "supportedSettings").isEmpty())
        throw new IllegalArgumentException(
            "No supported non-authoritative settings are registered");
      canonicalJson = TemplateConfigSourceJson.canonical(root);
    }

    public UUID baseVersionId() {
      return uuid(text(TemplateConfigSourceJson.tree(canonicalJson), "baseVersionId"));
    }

    public List<GameplayInput> gameplayInputs() {
      List<GameplayInput> inputs = new ArrayList<>();
      for (JsonNode node :
          TemplateConfigSourceJson.tree(canonicalJson).path("gameLogic").path("inputs"))
        inputs.add(
            new GameplayInput(
                Family.valueOf(text(node, "family")),
                text(node, "key"),
                uuid(text(node, "revisionId"))));
      return List.copyOf(inputs);
    }

    /** Data inspection only; these predicates do not qualify owner source. */
    public boolean hasWorldReferences() {
      var root = TemplateConfigSourceJson.tree(canonicalJson).path("world");
      return !root.path("regions").isEmpty() || !root.path("rooms").isEmpty();
    }

    public boolean hasEntityReferences() {
      var root = TemplateConfigSourceJson.tree(canonicalJson).path("entity");
      return !root.path("items").isEmpty() || !root.path("npcs").isEmpty();
    }

    public boolean hasAutomationReferences() {
      var root = TemplateConfigSourceJson.tree(canonicalJson).path("automation");
      return !root.path("scripts").isEmpty()
          || "PRESENT".equals(text(root.path("scriptPatch"), "presence"));
    }
  }

  public record Mutation(
      DraftCommitBinding binding,
      String revisionOrder,
      UUID revisionId,
      OperationKind operation,
      String templateId,
      String templateName,
      Config config,
      Owner declaredOwner,
      AutomationAuthoredSourceInventoryDeclaration inventory,
      EntityAuthoredSourceInventoryDeclaration entityInventory) {
    public Mutation(
        DraftCommitBinding binding,
        String revisionOrder,
        UUID revisionId,
        OperationKind operation,
        String templateId,
        String templateName,
        Config config) {
      this(
          binding,
          revisionOrder,
          revisionId,
          operation,
          templateId,
          templateName,
          config,
          null,
          null,
          null);
    }

    public Mutation {
      Objects.requireNonNull(binding);
      Objects.requireNonNull(operation);
      if (operation == OperationKind.DECLARE_OWNER_SOURCE_INVENTORY) {
        if (templateId != null
            || templateName != null
            || config != null
            || declaredOwner == null
            || !Set.of(Owner.AUTOMATION_SCRIPTING, Owner.ENTITY_MANAGEMENT).contains(declaredOwner)
            || (declaredOwner == Owner.AUTOMATION_SCRIPTING
                ? inventory == null || entityInventory != null
                : entityInventory == null || inventory != null))
          throw new IllegalArgumentException(
              "Owner inventory declaration carries only its supported owner's authored content");
        new TemplateConfigOwnerSourceInventoryDeclaration(
            declaredOwner, inventory, entityInventory, binding, revisionOrder, revisionId);
      } else if (declaredOwner != null || inventory != null || entityInventory != null) {
        throw new IllegalArgumentException("Template row mutations carry no owner inventory");
      } else if (operation == OperationKind.CREATE) {
        if (templateId != null
            || templateName == null
            || templateName.isBlank()
            || templateName.length() > 100)
          throw new IllegalArgumentException(
              "CREATE requires a display name and no caller template id");
      } else {
        requireTemplateId(templateId);
        if (templateName != null)
          throw new IllegalArgumentException("Only CREATE carries a display name");
      }
      if (operation != OperationKind.DELETE
          && operation != OperationKind.DECLARE_OWNER_SOURCE_INVENTORY) {
        Objects.requireNonNull(config);
        if (!binding.target().canonicalVersionId().equals(config.baseVersionId()))
          throw new IllegalArgumentException(
              "Template config base differs from its exact source version");
      } else if (operation == OperationKind.DELETE && config != null)
        throw new IllegalArgumentException("Template DELETE carries no config");
      var revision =
          binding.revisions().stream()
              .filter(r -> r.owner() == Owner.GAME_DESIGN_CONTROL_PLANE)
              .filter(
                  r -> r.revisionId().equals(revisionId) && r.revisionOrder().equals(revisionOrder))
              .findFirst()
              .orElseThrow();
      String expected =
          payloadFor(
              operation,
              templateId,
              templateName,
              config,
              declaredOwner,
              inventory,
              entityInventory);
      if (!expected.equals(
          TemplateConfigSourceJson.canonical(TemplateConfigSourceJson.tree(revision.payload()))))
        throw new IllegalArgumentException(
            "Template config differs from its exact original revision");
    }

    public String payload() {
      return payloadFor(
          operation, templateId, templateName, config, declaredOwner, inventory, entityInventory);
    }

    public boolean changesTemplateRow() {
      return operation != OperationKind.DECLARE_OWNER_SOURCE_INVENTORY;
    }

    public TemplateConfigOwnerSourceInventoryDeclaration ownerInventoryDeclaration() {
      return operation == OperationKind.DECLARE_OWNER_SOURCE_INVENTORY
          ? new TemplateConfigOwnerSourceInventoryDeclaration(
              declaredOwner, inventory, entityInventory, binding, revisionOrder, revisionId)
          : null;
    }

    public String inventoryJson() {
      if (inventory != null) return inventory.canonicalJson();
      if (entityInventory != null) return entityInventory.canonicalJson();
      throw new IllegalStateException("Mutation has no owner inventory content");
    }
  }

  public record Entry(
      String templateId,
      Config config,
      DraftCommitBinding sourceBinding,
      String revisionOrder,
      UUID revisionId,
      String createdName) {
    public Entry {
      requireTemplateId(templateId);
      new Mutation(
          sourceBinding,
          revisionOrder,
          revisionId,
          createdName == null ? OperationKind.UPSERT : OperationKind.CREATE,
          createdName == null ? templateId : null,
          createdName,
          config);
    }

    public Map<String, Object> object() {
      return Map.of(
          "templateId",
          templateId,
          "configJson",
          config.canonicalJson(),
          "sourceBindingJson",
          sourceBinding.canonicalJson(),
          "sourceBindingDigest",
          sourceBinding.digest(),
          "revisionOrder",
          revisionOrder,
          "revisionId",
          revisionId.toString(),
          "createdName",
          createdName == null ? "" : createdName);
    }
  }

  public static List<Mutation> mutations(DraftCommitBinding binding) {
    List<Mutation> result = new ArrayList<>();
    Set<Owner> declaredOwners = new HashSet<>();
    for (var revision : binding.revisions()) {
      if (revision.owner() != Owner.GAME_DESIGN_CONTROL_PLANE) continue;
      JsonNode root = TemplateConfigSourceJson.tree(revision.payload());
      String kind = text(root, "revisionKind");
      if (Set.of(
              "COMMAND_DEFINITION",
              "REALM_ENTRY_POLICY",
              "ASSET_REFERENCE",
              "GAMEPLAY_RULE",
              "BRANDING_ASSET_REFERENCE")
          .contains(kind)) continue;
      if (!REVISION_KIND.equals(kind))
        throw new IllegalArgumentException("Unknown Game Design revision kind");
      OperationKind operation = OperationKind.valueOf(text(root, "operation"));
      int schemaVersion =
          root.path("schemaVersion").isInt() ? root.path("schemaVersion").intValue() : -1;
      if (operation == OperationKind.DECLARE_OWNER_SOURCE_INVENTORY) {
        fields(root, "schemaVersion", "revisionKind", "operation", "owner", "inventory");
        Owner owner = Owner.valueOf(text(root, "owner"));
        if (!Set.of(Owner.AUTOMATION_SCRIPTING, Owner.ENTITY_MANAGEMENT).contains(owner)
            || !declaredOwners.add(owner))
          throw new IllegalArgumentException("Only one declaration per supported owner is allowed");
        if (owner == Owner.AUTOMATION_SCRIPTING) {
          if (schemaVersion != 2)
            throw new IllegalArgumentException(
                "Unsupported Automation owner inventory source schemaVersion");
          result.add(
              new Mutation(
                  binding,
                  revision.revisionOrder(),
                  revision.revisionId(),
                  operation,
                  null,
                  null,
                  null,
                  owner,
                  AutomationAuthoredSourceInventoryDeclaration.parse(
                      TemplateConfigSourceJson.canonical(root.path("inventory"))),
                  null));
        } else {
          if (schemaVersion != 3)
            throw new IllegalArgumentException(
                "Unsupported Entity owner inventory source schemaVersion");
          result.add(
              new Mutation(
                  binding,
                  revision.revisionOrder(),
                  revision.revisionId(),
                  operation,
                  null,
                  null,
                  null,
                  owner,
                  null,
                  EntityAuthoredSourceInventoryDeclaration.parse(
                      TemplateConfigSourceJson.canonical(root.path("inventory")))));
        }
        continue;
      }
      if (schemaVersion != 1)
        throw new IllegalArgumentException("Unsupported template source schemaVersion");
      fields(
          root,
          operation == OperationKind.CREATE
              ? new String[] {
                "schemaVersion", "revisionKind", "operation", "templateName", "configJson"
              }
              : operation == OperationKind.UPSERT
                  ? new String[] {
                    "schemaVersion", "revisionKind", "operation", "templateId", "configJson"
                  }
                  : new String[] {"schemaVersion", "revisionKind", "operation", "templateId"});
      result.add(
          new Mutation(
              binding,
              revision.revisionOrder(),
              revision.revisionId(),
              operation,
              operation == OperationKind.CREATE ? null : text(root, "templateId"),
              operation == OperationKind.CREATE ? text(root, "templateName") : null,
              operation != OperationKind.DELETE ? new Config(text(root, "configJson")) : null));
    }
    return List.copyOf(result);
  }

  public static String upsertPayload(String templateId, Config config) {
    requireTemplateId(templateId);
    return TemplateConfigSourceJson.canonical(
        Map.of(
            "schemaVersion",
            1,
            "revisionKind",
            REVISION_KIND,
            "operation",
            "UPSERT",
            "templateId",
            templateId,
            "configJson",
            config.canonicalJson()));
  }

  public static String createPayload(String templateName, Config config) {
    if (templateName == null || templateName.isBlank() || templateName.length() > 100)
      throw new IllegalArgumentException("Template display name required");
    return TemplateConfigSourceJson.canonical(
        Map.of(
            "schemaVersion",
            1,
            "revisionKind",
            REVISION_KIND,
            "operation",
            "CREATE",
            "templateName",
            templateName,
            "configJson",
            config.canonicalJson()));
  }

  public static String deletePayload(String templateId) {
    requireTemplateId(templateId);
    return TemplateConfigSourceJson.canonical(
        Map.of(
            "schemaVersion",
            1,
            "revisionKind",
            REVISION_KIND,
            "operation",
            "DELETE",
            "templateId",
            templateId));
  }

  public static String ownerInventoryPayload(
      Owner owner, AutomationAuthoredSourceInventoryDeclaration inventory) {
    if (owner != Owner.AUTOMATION_SCRIPTING)
      throw new IllegalArgumentException("Only Automation source inventory is supported");
    Objects.requireNonNull(inventory);
    return TemplateConfigSourceJson.canonical(
        Map.of(
            "schemaVersion", 2,
            "revisionKind", REVISION_KIND,
            "operation", OperationKind.DECLARE_OWNER_SOURCE_INVENTORY.name(),
            "owner", owner.name(),
            "inventory", TemplateConfigSourceJson.tree(inventory.canonicalJson())));
  }

  public static String ownerInventoryPayload(
      Owner owner, EntityAuthoredSourceInventoryDeclaration inventory) {
    if (owner != Owner.ENTITY_MANAGEMENT)
      throw new IllegalArgumentException("Only Entity source inventory is supported");
    Objects.requireNonNull(inventory);
    return TemplateConfigSourceJson.canonical(
        Map.of(
            "schemaVersion", 3,
            "revisionKind", REVISION_KIND,
            "operation", OperationKind.DECLARE_OWNER_SOURCE_INVENTORY.name(),
            "owner", owner.name(),
            "inventory", TemplateConfigSourceJson.tree(inventory.canonicalJson())));
  }

  private static String payloadFor(
      OperationKind operation,
      String templateId,
      String templateName,
      Config config,
      Owner declaredOwner,
      AutomationAuthoredSourceInventoryDeclaration inventory,
      EntityAuthoredSourceInventoryDeclaration entityInventory) {
    return switch (operation) {
      case CREATE -> createPayload(templateName, config);
      case UPSERT -> upsertPayload(templateId, config);
      case DELETE -> deletePayload(templateId);
      case DECLARE_OWNER_SOURCE_INVENTORY ->
          inventory != null
              ? ownerInventoryPayload(declaredOwner, inventory)
              : ownerInventoryPayload(declaredOwner, entityInventory);
    };
  }

  private static void requireTemplateId(String value) {
    if (value == null
        || !value.matches("[1-9][0-9]*")
        || new BigInteger(value).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
      throw new IllegalArgumentException("Exact Game Design template row identity required");
  }

  public static UUID uuid(String value) {
    UUID result = UUID.fromString(value);
    if (!result.toString().equals(value) || new UUID(0, 0).equals(result))
      throw new IllegalArgumentException("Canonical nonzero owner UUID required");
    return result;
  }

  private static void ownerIds(JsonNode root, String collection, String key) {
    Set<UUID> ids = new HashSet<>();
    for (JsonNode node : array(root, collection)) {
      fields(node, key);
      if (!ids.add(uuid(text(node, key))))
        throw new IllegalArgumentException("Duplicate owner template reference");
    }
  }

  private static JsonNode array(JsonNode root, String key) {
    if (!root.path(key).isArray())
      throw new IllegalArgumentException("Explicit template config collection required: " + key);
    return root.path(key);
  }

  private static void fields(JsonNode node, String... fields) {
    TemplateConfigSourceJson.requireStoredFields(node, fields);
  }

  private static String text(JsonNode node, String key) {
    return TemplateConfigSourceJson.requiredStoredText(node, key);
  }
}
