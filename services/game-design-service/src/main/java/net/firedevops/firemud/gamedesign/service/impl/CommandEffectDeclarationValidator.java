package net.firedevops.firemud.gamedesign.service.impl;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Validates the registered typed execution-effect schemas allowed in command definitions. */
final class CommandEffectDeclarationValidator {
  private static final Set<String> EFFECT_OPERATIONS =
      Set.of("ADD", "MULTIPLY", "CLAMP_MIN", "CLAMP_MAX", "GRANT_FLAG", "GRANT_CONDITION");

  private CommandEffectDeclarationValidator() {}

  static void validateAll(JsonNode effects) {
    if (!effects.isArray()) {
      throw invalid("effects must be an array");
    }
    for (JsonNode effect : effects) {
      validate(effect);
    }
  }

  private static void validate(JsonNode effect) {
    if (!effect.isObject()) {
      throw invalid("effect declarations must be objects");
    }
    if (!"APPLY_ACTION_STATE".equals(requiredText(effect, "effectKind"))) {
      throw invalid("uses an unsupported effectKind");
    }
    if (!effect.path("schemaVersion").isInt() || effect.path("schemaVersion").asInt() != 1) {
      throw invalid("uses an unsupported effect schemaVersion");
    }
    if (!"SELF".equals(requiredText(effect, "targeting"))) {
      throw invalid("APPLY_ACTION_STATE targeting must be SELF");
    }
    if (!"EFFECT_IDEMPOTENT".equals(requiredText(effect, "replayPolicy"))) {
      throw invalid("APPLY_ACTION_STATE replayPolicy must be EFFECT_IDEMPOTENT");
    }
    JsonNode payload = effect.path("payload");
    if (!payload.isObject()) {
      throw invalid("APPLY_ACTION_STATE payload must be an object");
    }
    requireIdentifier(payload, "conditionKey");
    JsonNode durationSeconds = payload.path("durationSeconds");
    if (!durationSeconds.isInt()
        || durationSeconds.asInt() <= 0
        || durationSeconds.asInt() > 3600) {
      throw invalid("APPLY_ACTION_STATE durationSeconds must be between 1 and 3600");
    }
    validateEffectPayload(payload.path("effectPayload"));
  }

  private static void validateEffectPayload(JsonNode payload) {
    if (!payload.isObject() || !payload.path("modifiers").isArray()) {
      throw invalid("APPLY_ACTION_STATE effectPayload.modifiers must be an array");
    }
    for (JsonNode modifier : payload.path("modifiers")) {
      if (!modifier.isObject()) {
        throw invalid("effect modifiers must be objects");
      }
      String operation = requiredText(modifier, "operation");
      if (!EFFECT_OPERATIONS.contains(operation)) {
        throw invalid("effect modifier uses an unsupported operation");
      }
      requireIdentifier(modifier, "target_key");
      if (!modifier.path("value").isNumber()) {
        throw invalid("effect modifier value must be numeric");
      }
      optionalIdentifier(modifier, "scope_kind");
      optionalIdentifier(modifier, "scope_key");
      if (!modifier.path("priority").isMissingNode() && !modifier.path("priority").isInt()) {
        throw invalid("effect modifier priority must be an integer");
      }
    }
  }

  private static String requiredText(JsonNode source, String field) {
    JsonNode value = source.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      throw invalid(field + " is required");
    }
    return value.asText();
  }

  private static void requireIdentifier(JsonNode source, String field) {
    String value = requiredText(source, field);
    if (!value.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
      throw invalid(field + " must be an identifier");
    }
  }

  private static void optionalIdentifier(JsonNode source, String field) {
    JsonNode value = source.path(field);
    if (!value.isMissingNode()) {
      requireIdentifier(source, field);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("INVALID_ARGUMENT: commandDefinition " + message);
  }
}
