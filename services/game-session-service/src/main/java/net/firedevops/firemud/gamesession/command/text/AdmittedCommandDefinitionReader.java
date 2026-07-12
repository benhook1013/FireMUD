package net.firedevops.firemud.gamesession.command.text;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Resolves authored definitions only from the release bundle admitted for a live game instance. */
@Component
final class AdmittedCommandDefinitionReader {
  private static final Set<String> EFFECT_OPERATIONS =
      Set.of("ADD", "MULTIPLY", "CLAMP_MIN", "CLAMP_MAX", "GRANT_FLAG", "GRANT_CONDITION");
  private static final String IDENTIFIER_PATTERN = "[A-Za-z][A-Za-z0-9_]{0,63}";
  private final GameInstanceRepository gameInstanceRepository;
  private final GameDesignClient gameDesignClient;
  private final ObjectMapper objectMapper;

  AdmittedCommandDefinitionReader(
      GameInstanceRepository gameInstanceRepository,
      GameDesignClient gameDesignClient,
      ObjectMapper objectMapper) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameDesignClient = gameDesignClient;
    this.objectMapper = objectMapper;
  }

  Optional<List<TextCommandDefinition>> definitionsFor(SessionContext context) {
    if (context == null || context.tenantId() <= 0L || context.gameInstanceId() <= 0L) {
      return Optional.empty();
    }
    try {
      return gameInstanceRepository
          .findById(context.gameInstanceId())
          .filter(instance -> matchesContext(instance, context))
          .flatMap(instance -> readDefinitions(context.tenantId(), instance));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private boolean matchesContext(GameInstance instance, SessionContext context) {
    return instance.getTenantId() != null
        && instance.getTenantId() == context.tenantId()
        && instance.getVersionId() != null
        && instance.getVersionId() > 0L
        && instance.getReleaseBundleId() != null
        && instance.getReleaseBundleId() > 0L;
  }

  private Optional<List<TextCommandDefinition>> readDefinitions(
      long tenantId, GameInstance instance) {
    try {
      GetPublishedReleaseBundleResponse response =
          gameDesignClient.getPublishedReleaseBundle(tenantId, instance.getVersionId());
      if (response.hasError()
          || !response.hasBundle()
          || response.getBundle().getId() != instance.getReleaseBundleId()
          || response.getBundle().getVersionId() != instance.getVersionId()) {
        return Optional.empty();
      }
      List<TextCommandDefinition> definitions = new ArrayList<>();
      for (String json : response.getBundle().getCommandDefinitionsList()) {
        definitions.add(parseDefinition(json));
      }
      return Optional.of(List.copyOf(definitions));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private TextCommandDefinition parseDefinition(String json) {
    JsonNode definition = objectMapper.readTree(json);
    if (!definition.isObject()
        || definition.path("schemaVersion").asInt() != 1
        || !"DURABLE_GAMEPLAY".equals(definition.path("executionDiscipline").asText())
        || !definition.path("effects").isArray()) {
      throw new IllegalArgumentException("unsupported published command definition");
    }
    String commandId = requiredText(definition, "commandId");
    requiredText(definition, "semanticOwner");
    List<String> aliases = textArray(definition, "aliases");
    List<TextCommandActionTag> actionTags =
        textArray(definition, "actionTags").stream().map(TextCommandActionTag::valueOf).toList();
    List<TextCommandEffectDeclaration> effects = parseEffects(definition.path("effects"));
    return new TextCommandDefinition(
        commandId,
        TextCommandType.AUTHORED,
        aliases,
        TextCommandDispatchGroup.AUTHORED,
        TextCommandStageRequirement.valueOf(requiredText(definition, "stageRequirement")),
        TextCommandPromptPolicy.valueOf(requiredText(definition, "promptPolicy")),
        TextCommandActionCategory.valueOf(requiredText(definition, "actionCategory")),
        actionTags,
        TextCommandSource.GAME_AUTHORED,
        "NONE",
        null,
        0L,
        null,
        0L,
        null,
        effects);
  }

  private List<TextCommandEffectDeclaration> parseEffects(JsonNode effects) {
    List<TextCommandEffectDeclaration> declarations = new ArrayList<>();
    for (JsonNode effect : effects) {
      declarations.add(parseEffect(effect));
    }
    return List.copyOf(declarations);
  }

  private TextCommandEffectDeclaration parseEffect(JsonNode effect) {
    if (!effect.isObject()
        || !"APPLY_ACTION_STATE".equals(requiredText(effect, "effectKind"))
        || !effect.path("schemaVersion").isInt()
        || effect.path("schemaVersion").asInt() != 1
        || !"SELF".equals(requiredText(effect, "targeting"))
        || !"EFFECT_IDEMPOTENT".equals(requiredText(effect, "replayPolicy"))) {
      throw new IllegalArgumentException("unsupported published command effect declaration");
    }
    JsonNode payload = effect.path("payload");
    if (!payload.isObject()) {
      throw new IllegalArgumentException("command effect payload must be an object");
    }
    String conditionKey = requiredIdentifier(payload, "conditionKey");
    JsonNode durationSeconds = payload.path("durationSeconds");
    if (!durationSeconds.isInt()
        || durationSeconds.asInt() <= 0
        || durationSeconds.asInt() > 3600) {
      throw new IllegalArgumentException("command effect durationSeconds is invalid");
    }
    return new TextCommandEffectDeclaration.ApplyActionState(
        conditionKey,
        Duration.ofSeconds(durationSeconds.asInt()),
        parseModifiers(payload.path("effectPayload")));
  }

  private List<TextCommandEffectDeclaration.Modifier> parseModifiers(JsonNode effectPayload) {
    JsonNode modifiers = effectPayload.path("modifiers");
    if (!effectPayload.isObject() || !modifiers.isArray()) {
      throw new IllegalArgumentException("command effect modifiers must be an array");
    }
    List<TextCommandEffectDeclaration.Modifier> parsed = new ArrayList<>();
    for (JsonNode modifier : modifiers) {
      if (!modifier.isObject()) {
        throw new IllegalArgumentException("command effect modifier must be an object");
      }
      String operation = requiredText(modifier, "operation");
      if (!EFFECT_OPERATIONS.contains(operation)) {
        throw new IllegalArgumentException("command effect modifier operation is unsupported");
      }
      String targetKey = requiredIdentifier(modifier, "target_key");
      JsonNode value = modifier.path("value");
      if (!value.isNumber()) {
        throw new IllegalArgumentException("command effect modifier value must be numeric");
      }
      JsonNode priority = modifier.path("priority");
      if (!priority.isMissingNode() && !priority.isInt()) {
        throw new IllegalArgumentException("command effect modifier priority must be an integer");
      }
      parsed.add(
          new TextCommandEffectDeclaration.Modifier(
              operation,
              targetKey,
              value.decimalValue(),
              optionalIdentifier(modifier, "scope_kind"),
              optionalIdentifier(modifier, "scope_key"),
              priority.isMissingNode() ? 0 : priority.asInt()));
    }
    return List.copyOf(parsed);
  }

  private String requiredText(JsonNode definition, String field) {
    JsonNode node = definition.path(field);
    if (!node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return node.asText();
  }

  private String requiredIdentifier(JsonNode definition, String field) {
    String value = requiredText(definition, field);
    if (!value.matches(IDENTIFIER_PATTERN)) {
      throw new IllegalArgumentException(field + " must be an identifier");
    }
    return value;
  }

  private String optionalIdentifier(JsonNode definition, String field) {
    JsonNode value = definition.path(field);
    return value.isMissingNode() ? "" : requiredIdentifier(definition, field);
  }

  private List<String> textArray(JsonNode definition, String field) {
    if (!definition.path(field).isArray()) {
      throw new IllegalArgumentException(field + " must be an array");
    }
    List<String> values = new ArrayList<>();
    for (JsonNode value : definition.path(field)) {
      if (!value.isTextual() || value.asText().isBlank()) {
        throw new IllegalArgumentException(field + " entries must be nonblank strings");
      }
      values.add(value.asText());
    }
    return List.copyOf(values);
  }
}
