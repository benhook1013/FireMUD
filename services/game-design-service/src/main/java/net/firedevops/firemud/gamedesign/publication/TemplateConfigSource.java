package net.firedevops.firemud.gamedesign.publication;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import tools.jackson.databind.JsonNode;

/** Closed authored wiring. Syntax validation never qualifies an external owner's reference. */
public final class TemplateConfigSource {
  public static final String REVISION_KIND = "TEMPLATE_CONFIG";
  public static final String SCOPE = "TEMPLATE_CONFIG_SET";
  public static final String SCOPE_ID = "effective";

  private TemplateConfigSource() {}

  public enum OperationKind {
    CREATE,
    UPSERT,
    DELETE
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
      JsonNode root = CommandSource.tree(canonicalJson);
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
          if (!bindings.add(CommandSource.canonical(event)))
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
      canonicalJson = CommandSource.canonical(root);
    }

    public UUID baseVersionId() {
      return uuid(text(CommandSource.tree(canonicalJson), "baseVersionId"));
    }

    public List<GameplayInput> gameplayInputs() {
      List<GameplayInput> inputs = new ArrayList<>();
      for (JsonNode node : CommandSource.tree(canonicalJson).path("gameLogic").path("inputs"))
        inputs.add(
            new GameplayInput(
                Family.valueOf(text(node, "family")),
                text(node, "key"),
                uuid(text(node, "revisionId"))));
      return List.copyOf(inputs);
    }

    /** These missing authenticated owner boundaries deliberately prevent qualification. */
    public void requireAvailableOwnerReads() {
      var root = CommandSource.tree(canonicalJson);
      if (!root.path("world").path("regions").isEmpty()
          || !root.path("world").path("rooms").isEmpty())
        throw new IllegalStateException("TEMPLATE_CONFIG_WORLD_EXACT_OWNER_READ_UNAVAILABLE");
      if (!root.path("entity").path("items").isEmpty()
          || !root.path("entity").path("npcs").isEmpty())
        throw new IllegalStateException("TEMPLATE_CONFIG_ENTITY_EXACT_OWNER_READ_UNAVAILABLE");
      if (!root.path("automation").path("scripts").isEmpty()
          || "PRESENT".equals(text(root.path("automation").path("scriptPatch"), "presence")))
        throw new IllegalStateException("TEMPLATE_CONFIG_AUTOMATION_EXACT_OWNER_READ_UNAVAILABLE");
    }
  }

  public record Mutation(
      DraftCommitBinding binding,
      String revisionOrder,
      UUID revisionId,
      OperationKind operation,
      String templateId,
      String templateName,
      Config config) {
    public Mutation {
      Objects.requireNonNull(binding);
      Objects.requireNonNull(operation);
      if (operation == OperationKind.CREATE) {
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
      if (operation != OperationKind.DELETE) {
        Objects.requireNonNull(config);
        if (!binding.target().canonicalVersionId().equals(config.baseVersionId()))
          throw new IllegalArgumentException(
              "Template config base differs from its exact source version");
      } else if (config != null)
        throw new IllegalArgumentException("Template DELETE carries no config");
      var revision =
          binding.revisions().stream()
              .filter(r -> r.owner() == Owner.GAME_DESIGN_CONTROL_PLANE)
              .filter(
                  r -> r.revisionId().equals(revisionId) && r.revisionOrder().equals(revisionOrder))
              .findFirst()
              .orElseThrow();
      String expected =
          operation == OperationKind.CREATE
              ? createPayload(templateName, config)
              : operation == OperationKind.UPSERT
                  ? upsertPayload(templateId, config)
                  : deletePayload(templateId);
      if (!expected.equals(CommandSource.canonical(CommandSource.tree(revision.payload()))))
        throw new IllegalArgumentException(
            "Template config differs from its exact original revision");
    }

    public String payload() {
      return operation == OperationKind.CREATE
          ? createPayload(templateName, config)
          : operation == OperationKind.UPSERT
              ? upsertPayload(templateId, config)
              : deletePayload(templateId);
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

    Map<String, Object> object() {
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
    for (var revision : binding.revisions()) {
      if (revision.owner() != Owner.GAME_DESIGN_CONTROL_PLANE) continue;
      JsonNode root = CommandSource.tree(revision.payload());
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
      if (!root.path("schemaVersion").isInt() || root.path("schemaVersion").intValue() != 1)
        throw new IllegalArgumentException("Unsupported template source schemaVersion");
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
    return CommandSource.canonical(
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
    return CommandSource.canonical(
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
    return CommandSource.canonical(
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

  public static List<Entry> replay(
      List<Entry> inherited, DraftCommitBinding binding, Map<UUID, String> createdRows) {
    Map<String, Entry> entries = new java.util.HashMap<>();
    for (Entry entry : inherited) {
      if (!binding.target().equals(entry.sourceBinding().target())
          || entries.put(entry.templateId(), entry) != null)
        throw new IllegalArgumentException("Invalid inherited template config inventory");
    }
    for (Mutation mutation : mutations(binding)) {
      if (mutation.operation() == OperationKind.DELETE) {
        if (entries.remove(mutation.templateId()) == null)
          throw new IllegalArgumentException("Template DELETE requires an authored entry");
      } else {
        String id =
            mutation.operation() == OperationKind.CREATE
                ? Objects.requireNonNull(
                    createdRows.get(mutation.revisionId()),
                    "Actual generated template row required")
                : mutation.templateId();
        if (mutation.operation() == OperationKind.CREATE && entries.containsKey(id)
            || mutation.operation() == OperationKind.UPSERT && !entries.containsKey(id))
          throw new IllegalArgumentException(
              "Template source identity is not an existing qualified entry");
        entries.put(
            id,
            new Entry(
                id,
                mutation.config(),
                binding,
                mutation.revisionOrder(),
                mutation.revisionId(),
                mutation.templateName()));
      }
    }
    return entries.values().stream()
        .sorted(Comparator.comparing(e -> new BigInteger(e.templateId())))
        .toList();
  }

  public static boolean isScope(DraftCommitBinding.AffectedUnit unit) {
    return SCOPE.equals(unit.aggregateType())
        && SCOPE.equals(unit.scopeType())
        && SCOPE_ID.equals(unit.scopeId());
  }

  private static void requireTemplateId(String value) {
    if (value == null
        || !value.matches("[1-9][0-9]*")
        || new BigInteger(value).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
      throw new IllegalArgumentException("Exact Game Design template row identity required");
  }

  static UUID uuid(String value) {
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
    CommandSource.requireStoredFields(node, fields);
  }

  private static String text(JsonNode node, String key) {
    return CommandSource.requiredStoredText(node, key);
  }
}
