package net.firedevops.firemud.gamesession.command.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    return gameInstanceRepository
        .findById(context.gameInstanceId())
        .filter(instance -> matchesContext(instance, context))
        .flatMap(instance -> readDefinitions(context.tenantId(), instance));
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
    GetPublishedReleaseBundleResponse response =
        gameDesignClient.getPublishedReleaseBundle(tenantId, instance.getVersionId());
    if (response.hasError()
        || !response.hasBundle()
        || response.getBundle().getId() != instance.getReleaseBundleId()
        || response.getBundle().getVersionId() != instance.getVersionId()) {
      return Optional.empty();
    }
    try {
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
        || !definition.path("effects").isArray()
        || !definition.path("effects").isEmpty()) {
      throw new IllegalArgumentException("unsupported published command definition");
    }
    String commandId = requiredText(definition, "commandId");
    requiredText(definition, "semanticOwner");
    List<String> aliases = textArray(definition, "aliases");
    List<TextCommandActionTag> actionTags =
        textArray(definition, "actionTags").stream().map(TextCommandActionTag::valueOf).toList();
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
        null);
  }

  private String requiredText(JsonNode definition, String field) {
    String value = definition.path(field).asText();
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
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
