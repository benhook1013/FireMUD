package net.firedevops.firemud.entitymanagement.effect;

import java.math.BigDecimal;

public record EffectModifier(
    EffectOperation operation,
    String targetKey,
    BigDecimal value,
    EffectScope scope,
    EffectSource source,
    int priority) {
  public EffectModifier {
    if (operation == null) {
      throw new IllegalArgumentException("operation must be specified");
    }
    targetKey = targetKey == null ? "" : targetKey;
    value = value == null ? BigDecimal.ZERO : value;
    scope = scope == null ? EffectScope.wholeActor() : scope;
    source = source == null ? new EffectSource("", "", "") : source;
    if (requiresTarget(operation) && targetKey.isBlank()) {
      throw new IllegalArgumentException("targetKey must be specified for " + operation);
    }
  }

  public static EffectModifier add(String targetKey, long value, EffectSource source) {
    return new EffectModifier(
        EffectOperation.ADD,
        targetKey,
        BigDecimal.valueOf(value),
        EffectScope.wholeActor(),
        source,
        0);
  }

  public static EffectModifier multiply(String targetKey, String factor, EffectSource source) {
    return new EffectModifier(
        EffectOperation.MULTIPLY,
        targetKey,
        new BigDecimal(factor),
        EffectScope.wholeActor(),
        source,
        0);
  }

  public static EffectModifier clampMin(String targetKey, long value, EffectSource source) {
    return new EffectModifier(
        EffectOperation.CLAMP_MIN,
        targetKey,
        BigDecimal.valueOf(value),
        EffectScope.wholeActor(),
        source,
        0);
  }

  public static EffectModifier clampMax(String targetKey, long value, EffectSource source) {
    return new EffectModifier(
        EffectOperation.CLAMP_MAX,
        targetKey,
        BigDecimal.valueOf(value),
        EffectScope.wholeActor(),
        source,
        0);
  }

  public static EffectModifier grantCondition(String conditionKey, EffectSource source) {
    return new EffectModifier(
        EffectOperation.GRANT_CONDITION,
        conditionKey,
        BigDecimal.ONE,
        EffectScope.wholeActor(),
        source,
        0);
  }

  public static EffectModifier grantFlag(String flagKey, EffectSource source) {
    return new EffectModifier(
        EffectOperation.GRANT_FLAG, flagKey, BigDecimal.ONE, EffectScope.wholeActor(), source, 0);
  }

  private static boolean requiresTarget(EffectOperation operation) {
    return switch (operation) {
      case ADD, MULTIPLY, CLAMP_MIN, CLAMP_MAX, GRANT_FLAG, GRANT_CONDITION -> true;
    };
  }
}
