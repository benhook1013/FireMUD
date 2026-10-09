package net.firedevops.firemud.gamedesign.publication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.command.CommandEffectDeclarationConstraints;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Typed complete effective source for Game Design's authored command definitions. */
public final class CommandSource {
  public static final String SCOPE = "COMMAND_DEFINITION_SET";
  public static final String SCOPE_ID = "effective";
  public static final String SNAPSHOT_SCHEMA = "game-design-command-source-snapshot/v1";
  public static final String APPLICATION_SCHEMA = "game-design-command-source-application/v1";
  public static final String REALM_POLICY_REVISION_KIND = "REALM_ENTRY_POLICY";

  private static final String REVISION_KIND = "COMMAND_DEFINITION";
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0, 0);
  private static final ObjectMapper JSON = new ObjectMapper();

  private CommandSource() {}

  public enum OperationKind {
    UPSERT,
    DELETE
  }

  /** One exact authored command definition with its creating revision and commit provenance. */
  public record Definition(
      String commandId,
      String definitionJson,
      UUID sourceCommitId,
      UUID sourceRevisionId,
      String revisionOrder) {
    public Definition {
      requireText(commandId, "commandId");
      Objects.requireNonNull(definitionJson, "definitionJson");
      requireNonNil(sourceCommitId, "sourceCommitId");
      requireNonNil(sourceRevisionId, "sourceRevisionId");
      requireCounter(revisionOrder, "revisionOrder");
      validateCommandDefinition(definitionJson);
      if (!commandId.equals(commandIdFrom(definitionJson))) {
        throw new IllegalArgumentException("Command source key differs from the exact definition");
      }
    }

    public String stableKey() {
      return normalizeToken(commandId);
    }
  }

  /** One explicit command mutation. DELETE retains identity and source revision provenance. */
  public record Mutation(
      String revisionOrder,
      UUID revisionId,
      UUID commitId,
      OperationKind operation,
      String commandId,
      String definitionJson) {
    public Mutation {
      requireCounter(revisionOrder, "revisionOrder");
      requireNonNil(revisionId, "revisionId");
      requireNonNil(commitId, "commitId");
      Objects.requireNonNull(operation, "operation");
      requireText(commandId, "commandId");
      if ((operation == OperationKind.UPSERT) != (definitionJson != null)) {
        throw new IllegalArgumentException("Command mutation definition presence is invalid");
      }
      if (definitionJson != null) {
        validateCommandDefinition(definitionJson);
        if (!commandId.equals(commandIdFrom(definitionJson))) {
          throw new IllegalArgumentException("Upsert identity differs from its command definition");
        }
      }
    }

    public String stableKey() {
      return normalizeToken(commandId);
    }

    public Map<String, Object> canonicalObject() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("revisionOrder", revisionOrder);
      value.put("revisionId", revisionId.toString());
      value.put("commitId", commitId.toString());
      value.put("operation", operation.name());
      value.put("commandId", commandId);
      if (definitionJson != null) value.put("definitionJson", definitionJson);
      return value;
    }
  }

  /** Exact receipt supplied only by the owner path that inserted a genuinely new Draft Version. */
  public record NewDraftGenesisReceipt(
      DraftCommitBinding.TargetProof target, UUID receiptId, String creationTransactionId) {
    public NewDraftGenesisReceipt {
      Objects.requireNonNull(target, "target");
      requireNonNil(receiptId, "receiptId");
      if (creationTransactionId == null || !creationTransactionId.matches("[1-9][0-9]*")) {
        throw new IllegalArgumentException(
            "Exact Version insertion transaction identity is required");
      }
    }

    public String targetProofJson() {
      return canonical(targetObject(target));
    }

    public byte[] canonicalBytes() {
      return canonical(
              Map.of(
                  "schema",
                  "game-design-command-source-new-draft-genesis/v1",
                  "targetProof",
                  tree(targetProofJson()),
                  "receiptId",
                  receiptId.toString(),
                  "creationTransactionId",
                  creationTransactionId))
          .getBytes(StandardCharsets.UTF_8);
    }

    public String digest() {
      return sha256(canonicalBytes());
    }
  }

  /**
   * Converts the closed Draft owner payloads to command mutations. Realm policy payloads are a
   * separate Game Design owner scope and are deliberately left for their own source writer. Unknown
   * kinds deny rather than being guessed from omitted or legacy rows.
   */
  public static List<Mutation> mutations(DraftCommitBinding binding) {
    Objects.requireNonNull(binding, "binding");
    List<Mutation> mutations = new ArrayList<>();
    for (DraftCommitBinding.RevisionPayload revision : binding.revisions()) {
      if (revision.owner() != Owner.GAME_DESIGN_CONTROL_PLANE) continue;
      JsonNode payload = tree(revision.payload());
      if (!payload.isObject() || !payload.path("revisionKind").isTextual()) {
        throw new IllegalArgumentException("Closed Game Design owner revision kind is required");
      }
      String revisionKind = payload.path("revisionKind").asText();
      if (REALM_POLICY_REVISION_KIND.equals(revisionKind)
          || AssetSource.REVISION_KIND.equals(revisionKind)
          || GameplayRuleSource.REVISION_KIND.equals(revisionKind)
          || BrandingSource.REVISION_KIND.equals(revisionKind)
          || TemplateConfigSource.REVISION_KIND.equals(revisionKind)) continue;
      if (!REVISION_KIND.equals(revisionKind)) {
        throw new IllegalArgumentException("Unsupported Game Design command source revision kind");
      }
      if (!payload.path("schemaVersion").isInt()
          || payload.path("schemaVersion").asInt() != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported command source operation schema");
      }
      String operation = requiredText(payload, "operation");
      if ("UPSERT".equals(operation)) {
        fields(payload, "schemaVersion", "revisionKind", "operation", "definitionJson");
        String definitionJson = requiredText(payload, "definitionJson");
        mutations.add(
            new Mutation(
                revision.revisionOrder(),
                revision.revisionId(),
                binding.commitId(),
                OperationKind.UPSERT,
                commandIdFrom(definitionJson),
                definitionJson));
      } else if ("DELETE".equals(operation)) {
        fields(payload, "schemaVersion", "revisionKind", "operation", "commandId");
        mutations.add(
            new Mutation(
                revision.revisionOrder(),
                revision.revisionId(),
                binding.commitId(),
                OperationKind.DELETE,
                requiredText(payload, "commandId"),
                null));
      } else {
        throw new IllegalArgumentException("Unsupported command source operation");
      }
    }
    return List.copyOf(mutations);
  }

  public static boolean hasRealmPolicyRevision(DraftCommitBinding binding) {
    Objects.requireNonNull(binding, "binding");
    boolean found = false;
    for (DraftCommitBinding.RevisionPayload revision : binding.revisions()) {
      if (revision.owner() != Owner.GAME_DESIGN_CONTROL_PLANE) continue;
      JsonNode payload = tree(revision.payload());
      if (!payload.isObject() || !payload.path("revisionKind").isTextual()) {
        throw new IllegalArgumentException("Closed Game Design owner revision kind is required");
      }
      String revisionKind = payload.path("revisionKind").asText();
      if (REALM_POLICY_REVISION_KIND.equals(revisionKind)) {
        found = true;
      } else if (!REVISION_KIND.equals(revisionKind)
          && !AssetSource.REVISION_KIND.equals(revisionKind)
          && !GameplayRuleSource.REVISION_KIND.equals(revisionKind)
          && !BrandingSource.REVISION_KIND.equals(revisionKind)
          && !TemplateConfigSource.REVISION_KIND.equals(revisionKind)) {
        throw new IllegalArgumentException("Unsupported Game Design command source revision kind");
      }
    }
    return found;
  }

  /** Payload builder for a new immutable Draft binding; its result is part of binding.digest(). */
  public static String upsertPayload(String definitionJson) {
    validateCommandDefinition(definitionJson);
    return canonical(
        Map.of(
            "schemaVersion", SCHEMA_VERSION,
            "revisionKind", REVISION_KIND,
            "operation", "UPSERT",
            "definitionJson", definitionJson));
  }

  /**
   * Payload builder for an explicit digest-bound delete; absence is never interpreted as delete.
   */
  public static String deletePayload(String commandId) {
    requireText(commandId, "commandId");
    return canonical(
        Map.of(
            "schemaVersion", SCHEMA_VERSION,
            "revisionKind", REVISION_KIND,
            "operation", "DELETE",
            "commandId", commandId));
  }

  public static List<Definition> replay(List<Definition> inherited, List<Mutation> mutations) {
    Objects.requireNonNull(inherited, "inherited");
    Objects.requireNonNull(mutations, "mutations");
    Map<String, Definition> effective = new HashMap<>();
    for (Definition definition : inherited) {
      if (effective.putIfAbsent(definition.stableKey(), definition) != null) {
        throw new IllegalArgumentException("Inherited command source repeats a stable command key");
      }
    }
    String previousOrder = null;
    UUID commitId = null;
    java.util.HashSet<UUID> revisionIds = new java.util.HashSet<>();
    for (Mutation mutation : mutations) {
      if ((previousOrder != null
              && new java.math.BigInteger(mutation.revisionOrder())
                      .compareTo(new java.math.BigInteger(previousOrder))
                  <= 0)
          || (commitId != null && !commitId.equals(mutation.commitId()))
          || !revisionIds.add(mutation.revisionId())) {
        throw new IllegalArgumentException(
            "Command mutations must retain one canonical commit and increasing revision order");
      }
      previousOrder = mutation.revisionOrder();
      commitId = mutation.commitId();
      if (mutation.operation() == OperationKind.DELETE) {
        effective.remove(mutation.stableKey());
      } else {
        effective.put(
            mutation.stableKey(),
            new Definition(
                mutation.commandId(),
                mutation.definitionJson(),
                mutation.commitId(),
                mutation.revisionId(),
                mutation.revisionOrder()));
      }
    }
    List<Definition> result =
        effective.values().stream().sorted(Comparator.comparing(Definition::stableKey)).toList();
    validateDistinctCommandTokens(result);
    return result;
  }

  static String definitionsJson(List<Definition> definitions) {
    return canonical(
        definitions.stream()
            .sorted(Comparator.comparing(Definition::stableKey))
            .map(CommandSource::definitionObject)
            .toList());
  }

  static List<Definition> definitionsFromStored(String json) {
    JsonNode root = tree(json);
    if (!root.isArray()) throw new IllegalArgumentException("Complete command array is required");
    List<Definition> definitions = new ArrayList<>();
    for (JsonNode item : root) {
      fields(
          item,
          "commandId",
          "definitionJson",
          "sourceCommitId",
          "sourceRevisionId",
          "revisionOrder");
      definitions.add(
          new Definition(
              requiredText(item, "commandId"),
              requiredText(item, "definitionJson"),
              UUID.fromString(requiredText(item, "sourceCommitId")),
              UUID.fromString(requiredText(item, "sourceRevisionId")),
              requiredText(item, "revisionOrder")));
    }
    List<Definition> result =
        definitions.stream().sorted(Comparator.comparing(Definition::stableKey)).toList();
    if (!definitionsJson(result).equals(json)) {
      throw new IllegalArgumentException("Stored command source set is not canonical");
    }
    validateDistinctCommandTokens(result);
    return List.copyOf(result);
  }

  static String mutationsJson(List<Mutation> mutations) {
    return canonical(mutations.stream().map(Mutation::canonicalObject).toList());
  }

  static List<Mutation> mutationsFromStored(String json) {
    JsonNode root = tree(json);
    if (!root.isArray())
      throw new IllegalArgumentException("Complete command operation list is required");
    List<Mutation> result = new ArrayList<>();
    for (JsonNode item : root) {
      String operation = requiredText(item, "operation");
      if ("UPSERT".equals(operation)) {
        fields(
            item,
            "revisionOrder",
            "revisionId",
            "commitId",
            "operation",
            "commandId",
            "definitionJson");
        result.add(
            new Mutation(
                requiredText(item, "revisionOrder"),
                UUID.fromString(requiredText(item, "revisionId")),
                UUID.fromString(requiredText(item, "commitId")),
                OperationKind.UPSERT,
                requiredText(item, "commandId"),
                requiredText(item, "definitionJson")));
      } else if ("DELETE".equals(operation)) {
        fields(item, "revisionOrder", "revisionId", "commitId", "operation", "commandId");
        result.add(
            new Mutation(
                requiredText(item, "revisionOrder"),
                UUID.fromString(requiredText(item, "revisionId")),
                UUID.fromString(requiredText(item, "commitId")),
                OperationKind.DELETE,
                requiredText(item, "commandId"),
                null));
      } else {
        throw new IllegalArgumentException("Stored command operation kind is unsupported");
      }
    }
    String previousOrder = null;
    java.util.HashSet<UUID> revisionIds = new java.util.HashSet<>();
    for (Mutation mutation : result) {
      if ((previousOrder != null
              && new java.math.BigInteger(mutation.revisionOrder())
                      .compareTo(new java.math.BigInteger(previousOrder))
                  <= 0)
          || !revisionIds.add(mutation.revisionId())) {
        throw new IllegalArgumentException(
            "Stored command operations are not in exact revision order");
      }
      previousOrder = mutation.revisionOrder();
    }
    return List.copyOf(result);
  }

  static void requireCanonicalDefinitions(List<Definition> definitions) {
    List<Definition> sorted =
        definitions.stream().sorted(Comparator.comparing(Definition::stableKey)).toList();
    if (!definitions.equals(sorted)) {
      throw new IllegalArgumentException(
          "Command source definitions are not in canonical stable-key order");
    }
    Map<String, Definition> unique = new HashMap<>();
    for (Definition definition : definitions) {
      if (unique.putIfAbsent(definition.stableKey(), definition) != null) {
        throw new IllegalArgumentException("Command source repeats a stable command key");
      }
    }
    validateDistinctCommandTokens(definitions);
  }

  static void requireStoredFields(JsonNode node, String... fields) {
    CommandSource.fields(node, fields);
  }

  static String requiredStoredText(JsonNode node, String field) {
    return requiredText(node, field);
  }

  static String canonical(Object value) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
          StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalArgumentException("Canonical command source encoding failed", failure);
    }
  }

  static JsonNode tree(String json) {
    try {
      return JSON.readTree(Rfc8785CanonicalJson.canonicalizeUtf8(json));
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException("Unambiguous command source JSON required", failure);
    }
  }

  static byte[] canonicalBytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  static Map<String, Object> targetObject(DraftCommitBinding.TargetProof target) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("canonicalTenantId", target.canonicalTenantId().toString());
    result.put("canonicalVersionId", target.canonicalVersionId().toString());
    result.put("gameDesignVersionRowId", target.gameDesignVersionRowId());
    result.put("gameDesignVersionTenantKey", target.gameDesignVersionTenantKey());
    result.put("sourceGameRowId", target.sourceGameRowId());
    result.put("sourceGameTenantKey", target.sourceGameTenantKey());
    result.put("sourceProvenanceKind", target.sourceProvenanceKind());
    return result;
  }

  private static Map<String, Object> definitionObject(Definition definition) {
    return Map.of(
        "commandId", definition.commandId(),
        "definitionJson", definition.definitionJson(),
        "sourceCommitId", definition.sourceCommitId().toString(),
        "sourceRevisionId", definition.sourceRevisionId().toString(),
        "revisionOrder", definition.revisionOrder());
  }

  private static void validateDistinctCommandTokens(List<Definition> definitions) {
    Map<String, String> tokenOwners = new HashMap<>();
    Map<String, Boolean> commandIds = new HashMap<>();
    for (Definition definition : definitions) {
      String commandId = normalizeToken(definition.commandId());
      if (commandIds.putIfAbsent(commandId, Boolean.TRUE) != null) {
        throw new IllegalArgumentException("Duplicate command definition id " + commandId);
      }
      ensureSingleTokenOwner(tokenOwners, commandId, commandId);
      JsonNode aliases = tree(definition.definitionJson()).get("aliases");
      for (JsonNode alias : aliases) {
        String normalized = normalizeToken(alias.asText());
        ensureSingleTokenOwner(tokenOwners, normalized, commandId);
      }
    }
  }

  private static void ensureSingleTokenOwner(
      Map<String, String> owners, String token, String commandId) {
    String existing = owners.putIfAbsent(token, commandId);
    if (existing != null && !existing.equals(commandId)) {
      throw new IllegalArgumentException("Ambiguous published command token " + token);
    }
  }

  private static String commandIdFrom(String definitionJson) {
    validateCommandDefinition(definitionJson);
    return requiredText(tree(definitionJson), "commandId");
  }

  /** Mirrors the currently accepted revision and release schema while preserving the raw JSON. */
  private static void validateCommandDefinition(String json) {
    JsonNode definition = tree(json);
    if (!definition.isObject() || definition.path("schemaVersion").asInt() != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported command definition schema");
    }
    requiredText(definition, "commandId");
    requiredText(definition, "semanticOwner");
    requireEnum(definition, "executionDiscipline", "DURABLE_GAMEPLAY");
    requireEnum(definition, "stageRequirement", "NONE", "LOGIN", "GAMEPLAY");
    requireEnum(definition, "promptPolicy", "NEVER", "WHEN_LOGGED_IN", "WHEN_GAMEPLAY");
    requireEnum(definition, "actionCategory", "GAMEPLAY", "SOCIAL", "META", "ADMIN", "SYSTEM");
    JsonNode history = definition.get("historyRecordable");
    if (history == null || !history.isBoolean()) {
      throw new IllegalArgumentException("historyRecordable must be a boolean");
    }
    requireTextArray(definition, "aliases");
    requireEnumArray(
        definition,
        "actionTags",
        "MOVEMENT",
        "COMMUNICATION",
        "COMBAT",
        "INVENTORY",
        "WORLD_BROWSE",
        "SOCIAL_PRESENCE",
        "AUTHORING",
        "SESSION",
        "UI");
    JsonNode effects = definition.get("effects");
    if (effects == null || !effects.isArray()) {
      throw new IllegalArgumentException("effects must be an array");
    }
    for (JsonNode effect : effects) validateEffect(effect);
  }

  private static void validateEffect(JsonNode effect) {
    if (!effect.isObject()
        || !"APPLY_ACTION_STATE".equals(requiredText(effect, "effectKind"))
        || !effect.path("schemaVersion").isInt()
        || effect.path("schemaVersion").asInt() != 1
        || !"SELF".equals(requiredText(effect, "targeting"))
        || !"EFFECT_IDEMPOTENT".equals(requiredText(effect, "replayPolicy"))) {
      throw new IllegalArgumentException("Unsupported command effect declaration");
    }
    JsonNode payload = effect.get("payload");
    if (payload == null || !payload.isObject())
      throw new IllegalArgumentException("Effect payload must be an object");
    requireIdentifier(payload, "conditionKey");
    JsonNode duration = payload.get("durationSeconds");
    if (duration == null
        || !duration.isInt()
        || !CommandEffectDeclarationConstraints.isValidDurationSeconds(duration.asInt())) {
      throw new IllegalArgumentException("Command action-state duration is unsupported");
    }
    JsonNode effectPayload = payload.get("effectPayload");
    JsonNode modifiers = effectPayload == null ? null : effectPayload.get("modifiers");
    if (effectPayload == null
        || !effectPayload.isObject()
        || modifiers == null
        || !modifiers.isArray()) {
      throw new IllegalArgumentException("Command effect modifiers must be an array");
    }
    for (JsonNode modifier : modifiers) {
      if (!modifier.isObject())
        throw new IllegalArgumentException("Command effect modifier must be an object");
      String operation = requiredText(modifier, "operation");
      if (!CommandEffectDeclarationConstraints.SUPPORTED_MODIFIER_OPERATIONS.contains(operation)) {
        throw new IllegalArgumentException("Unsupported command effect modifier operation");
      }
      requireIdentifier(modifier, "target_key");
      JsonNode value = modifier.get("value");
      if (value == null || !value.isNumber())
        throw new IllegalArgumentException("Command effect modifier value must be numeric");
      optionalIdentifier(modifier, "scope_kind");
      optionalIdentifier(modifier, "scope_key");
      JsonNode priority = modifier.get("priority");
      if (priority != null && !priority.isInt())
        throw new IllegalArgumentException("Command effect priority must be an integer");
    }
  }

  private static void requireIdentifier(JsonNode source, String field) {
    String value = requiredText(source, field);
    if (!CommandEffectDeclarationConstraints.isIdentifier(value)) {
      throw new IllegalArgumentException("Command effect identifier is invalid: " + field);
    }
  }

  private static void optionalIdentifier(JsonNode source, String field) {
    if (source.get(field) != null) requireIdentifier(source, field);
  }

  private static void requireEnum(JsonNode source, String field, String... allowed) {
    String actual = requiredText(source, field);
    for (String value : allowed) if (value.equals(actual)) return;
    throw new IllegalArgumentException("Unsupported command definition field " + field);
  }

  private static void requireEnumArray(JsonNode source, String field, String... allowed) {
    requireTextArray(source, field);
    List<String> supported = List.of(allowed);
    for (JsonNode item : source.get(field)) {
      if (!supported.contains(item.asText())) {
        throw new IllegalArgumentException("Unsupported command definition value in " + field);
      }
    }
  }

  private static void requireTextArray(JsonNode source, String field) {
    JsonNode values = source.get(field);
    if (values == null || !values.isArray()) {
      throw new IllegalArgumentException("Command definition array is required: " + field);
    }
    for (JsonNode value : values) {
      if (!value.isTextual() || value.asText().isBlank()) {
        throw new IllegalArgumentException(
            "Command definition array has an invalid entry: " + field);
      }
    }
  }

  private static String requiredText(JsonNode source, String field) {
    JsonNode value = source.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("Command definition text is required: " + field);
    }
    return value.asText();
  }

  private static String normalizeToken(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Command token must not be blank");
    }
    return token.trim().toLowerCase(Locale.ROOT);
  }

  private static void fields(JsonNode node, String... names) {
    if (!node.isObject() || node.size() != names.length) {
      throw new IllegalArgumentException("Closed command source operation fields are required");
    }
    for (String name : names) {
      if (node.get(name) == null)
        throw new IllegalArgumentException("Missing command source field " + name);
    }
  }

  private static void requireCounter(String value, String name) {
    if (value == null || !value.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException(name + " must be a canonical nonnegative decimal");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
      throw new IllegalArgumentException(name + " must be nonblank valid UTF-8 text");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value))
      throw new IllegalArgumentException(name + " must be non-nil");
  }
}
