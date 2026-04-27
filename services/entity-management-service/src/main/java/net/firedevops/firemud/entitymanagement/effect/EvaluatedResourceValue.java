package net.firedevops.firemud.entitymanagement.effect;

import java.util.List;

public record EvaluatedResourceValue(
    String statKey,
    long currentValue,
    Long maxValue,
    Long baseValue,
    String primitiveKind,
    List<EffectSource> contributingSources) {
  public EvaluatedResourceValue {
    contributingSources = List.copyOf(contributingSources);
  }
}
