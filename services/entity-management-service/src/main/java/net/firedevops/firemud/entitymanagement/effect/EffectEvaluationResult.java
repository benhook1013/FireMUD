package net.firedevops.firemud.entitymanagement.effect;

import java.util.List;

public record EffectEvaluationResult(
    List<EvaluatedResourceValue> resources, List<GrantedActorState> grantedStates) {
  public EffectEvaluationResult {
    resources = List.copyOf(resources);
    grantedStates = List.copyOf(grantedStates);
  }
}
