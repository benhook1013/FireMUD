package net.firedevops.firemud.entitymanagement.effect;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.common.command.CommandEffectDeclarationConstraints;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class EffectPayloadParser {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring injects ObjectMapper; storing the shared mapper reference is safe")
  private final ObjectMapper objectMapper;

  public EffectPayloadParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public List<EffectModifier> parseModifiers(String effectPayloadJson, EffectSource source) {
    if (!StringUtils.hasText(effectPayloadJson)) {
      return List.of();
    }
    try {
      JsonNode payload = objectMapper.readTree(effectPayloadJson);
      if (!payload.isObject()) {
        throw new IllegalArgumentException("effect_payload_json must be an object");
      }
      JsonNode modifiers = payload.path("modifiers");
      if (modifiers.isMissingNode()) {
        if (!payload.path("effects").isMissingNode()) {
          throw new IllegalArgumentException("effect_payload_json must use modifiers, not effects");
        }
        return List.of();
      }
      if (!modifiers.isArray()) {
        throw new IllegalArgumentException("effect_payload_json modifiers must be an array");
      }
      List<EffectModifier> parsed = new ArrayList<>();
      for (JsonNode modifier : modifiers) {
        parsed.add(toModifier(modifier, source));
      }
      return List.copyOf(parsed);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("effect_payload_json is not a valid effect payload", ex);
    }
  }

  private EffectModifier toModifier(JsonNode modifier, EffectSource source) {
    if (!modifier.isObject()) {
      throw new IllegalArgumentException("effect modifier must be an object");
    }
    String operationName = requiredText(modifier, "operation");
    if (!CommandEffectDeclarationConstraints.SUPPORTED_MODIFIER_OPERATIONS.contains(
        operationName)) {
      throw new IllegalArgumentException("effect modifier operation is unsupported");
    }
    EffectOperation operation = EffectOperation.valueOf(operationName);
    String targetKey = requiredIdentifier(modifier, "target_key");
    JsonNode value = modifier.path("value");
    if (!value.isNumber()) {
      throw new IllegalArgumentException("effect modifier value must be numeric");
    }
    JsonNode priority = modifier.path("priority");
    if (!priority.isMissingNode() && !priority.isInt()) {
      throw new IllegalArgumentException("effect modifier priority must be an integer");
    }
    EffectScope scope =
        new EffectScope(
            optionalIdentifier(modifier, "scope_kind"), optionalIdentifier(modifier, "scope_key"));
    return new EffectModifier(
        operation,
        targetKey,
        value.decimalValue(),
        scope,
        source,
        priority.isMissingNode() ? 0 : priority.asInt());
  }

  private String requiredText(JsonNode modifier, String field) {
    JsonNode value = modifier.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("effect modifier " + field + " is required");
    }
    return value.asText();
  }

  private String requiredIdentifier(JsonNode modifier, String field) {
    String value = requiredText(modifier, field);
    if (!CommandEffectDeclarationConstraints.isIdentifier(value)) {
      throw new IllegalArgumentException("effect modifier " + field + " must be an identifier");
    }
    return value;
  }

  private String optionalIdentifier(JsonNode modifier, String field) {
    JsonNode value = modifier.path(field);
    return value.isMissingNode() ? null : requiredIdentifier(modifier, field);
  }
}
