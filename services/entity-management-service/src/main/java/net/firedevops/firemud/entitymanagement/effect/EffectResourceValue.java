package net.firedevops.firemud.entitymanagement.effect;

public record EffectResourceValue(
    String statKey,
    long currentValue,
    Long maxValue,
    Long baseValue,
    String primitiveKind,
    EffectSource source) {
  public EffectResourceValue {
    if (statKey == null || statKey.isBlank()) {
      throw new IllegalArgumentException("statKey must be specified");
    }
    primitiveKind = primitiveKind == null || primitiveKind.isBlank() ? "INTEGER" : primitiveKind;
    source = source == null ? new EffectSource("", "", "") : source;
  }
}
