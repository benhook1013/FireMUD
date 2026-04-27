package net.firedevops.firemud.entitymanagement.effect;

import java.util.List;

public record EffectEvaluationInput(
    List<EffectResourceValue> baseResources, List<EffectModifier> modifiers) {
  public EffectEvaluationInput {
    baseResources = List.copyOf(baseResources);
    modifiers = List.copyOf(modifiers);
  }
}
