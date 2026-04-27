package net.firedevops.firemud.entitymanagement.effect;

public record GrantedActorState(
    String stateKey, EffectOperation operation, EffectScope scope, EffectSource source) {
  public GrantedActorState {
    if (stateKey == null || stateKey.isBlank()) {
      throw new IllegalArgumentException("stateKey must be specified");
    }
    if (operation != EffectOperation.GRANT_FLAG && operation != EffectOperation.GRANT_CONDITION) {
      throw new IllegalArgumentException("operation must grant actor state");
    }
    scope = scope == null ? EffectScope.wholeActor() : scope;
    source = source == null ? new EffectSource("", "", "") : source;
  }
}
