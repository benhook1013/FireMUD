package net.firedevops.firemud.entitymanagement.effect;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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
      Map<?, ?> payload = objectMapper.readValue(effectPayloadJson, Map.class);
      Object rawModifiers = firstPresent(payload, "modifiers", "effects");
      if (!(rawModifiers instanceof List<?> modifiers)) {
        return List.of();
      }
      return modifiers.stream()
          .filter(Map.class::isInstance)
          .map(Map.class::cast)
          .map(modifier -> toModifier(modifier, source))
          .toList();
    } catch (Exception ex) {
      throw new IllegalArgumentException("effect_payload_json is not a valid effect payload", ex);
    }
  }

  private EffectModifier toModifier(Map<?, ?> modifier, EffectSource source) {
    EffectOperation operation = EffectOperation.valueOf(requiredString(modifier, "operation"));
    String targetKey = requiredString(modifier, "target_key", "targetKey", "stat_key", "state_key");
    BigDecimal value = numericValue(modifier.get("value"), operation);
    EffectScope scope =
        new EffectScope(
            optionalString(modifier, "scope_kind", "scopeKind"),
            optionalString(modifier, "scope_key", "scopeKey"));
    int priority = intValue(modifier.get("priority"));
    return new EffectModifier(operation, targetKey, value, scope, source, priority);
  }

  private Object firstPresent(Map<?, ?> payload, String... keys) {
    for (String key : keys) {
      if (payload.containsKey(key)) {
        return payload.get(key);
      }
    }
    return null;
  }

  private String requiredString(Map<?, ?> source, String... keys) {
    String value = optionalString(source, keys);
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("effect modifier is missing required field");
    }
    return value;
  }

  private String optionalString(Map<?, ?> source, String... keys) {
    Object value = firstPresent(source, keys);
    return value == null ? "" : value.toString();
  }

  private BigDecimal numericValue(Object rawValue, EffectOperation operation) {
    if (rawValue == null) {
      return operation == EffectOperation.MULTIPLY ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    return new BigDecimal(rawValue.toString());
  }

  private int intValue(Object rawValue) {
    if (rawValue == null) {
      return 0;
    }
    return Integer.parseInt(rawValue.toString());
  }
}
