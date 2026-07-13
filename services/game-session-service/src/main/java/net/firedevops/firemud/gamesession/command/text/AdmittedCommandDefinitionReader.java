package net.firedevops.firemud.gamesession.command.text;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.common.command.CommandEffectDeclarationConstraints;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Resolves authored definitions only from the release bundle admitted for a live game instance. */
@Component
final class AdmittedCommandDefinitionReader {
  private static final Logger LOG = LoggerFactory.getLogger(AdmittedCommandDefinitionReader.class);
  private final GameInstanceRepository gameInstanceRepository;
  private final GameDesignClient gameDesignClient;
  private final ObjectMapper objectMapper;
  private final ConcurrentMap<BundleKey, List<TextCommandDefinition>> definitionsByBundle =
      new ConcurrentHashMap<>();

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
      LOG.warn(
          "Unable to resolve admitted command definitions tenantId={} gameInstanceId={}",
          context.tenantId(),
          context.gameInstanceId(),
          ex);
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
      BundleKey bundleKey =
          new BundleKey(tenantId, instance.getVersionId(), instance.getReleaseBundleId());
      return Optional.of(
          definitionsByBundle.computeIfAbsent(bundleKey, ignored -> loadDefinitions(bundleKey)));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Unable to read admitted command definitions tenantId={} versionId={} releaseBundleId={}",
          tenantId,
          instance.getVersionId(),
          instance.getReleaseBundleId(),
          ex);
      return Optional.empty();
    }
  }

  private List<TextCommandDefinition> loadDefinitions(BundleKey bundleKey) {
    GetPublishedReleaseBundleResponse response =
        gameDesignClient.getPublishedReleaseBundle(bundleKey.tenantId(), bundleKey.versionId());
    if (response.hasError()
        || !response.hasBundle()
        || response.getBundle().getId() != bundleKey.releaseBundleId()
        || response.getBundle().getVersionId() != bundleKey.versionId()) {
      throw new IllegalArgumentException(
          "published release bundle does not match the game instance");
    }
    List<TextCommandDefinition> definitions = new ArrayList<>();
    for (String json : response.getBundle().getCommandDefinitionsList()) {
      definitions.add(parseDefinition(json));
    }
    return List.copyOf(definitions);
  }

  private record BundleKey(long tenantId, long versionId, long releaseBundleId) {}

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
        || !CommandEffectDeclarationConstraints.isValidDurationSeconds(durationSeconds.asInt())) {
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
      if (!CommandEffectDeclarationConstraints.SUPPORTED_MODIFIER_OPERATIONS.contains(operation)) {
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
    if (!CommandEffectDeclarationConstraints.isIdentifier(value)) {
      throw new IllegalArgumentException(field + " must be an identifier");
    }
    return value;
  }

  private String optionalIdentifier(JsonNode definition, String field) {
    JsonNode value = definition.path(field);
    return value.isMissingNode() ? null : requiredIdentifier(definition, field);
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
