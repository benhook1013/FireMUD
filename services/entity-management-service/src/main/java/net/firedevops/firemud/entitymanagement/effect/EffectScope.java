package net.firedevops.firemud.entitymanagement.effect;

public record EffectScope(String scopeKind, String scopeKey) {
  public static final String WHOLE_ACTOR = "WHOLE_ACTOR";

  public EffectScope {
    scopeKind = normalize(scopeKind);
    scopeKey = normalize(scopeKey);
  }

  public static EffectScope wholeActor() {
    return new EffectScope(WHOLE_ACTOR, "");
  }

  public static EffectScope of(String scopeKind, String scopeKey) {
    return new EffectScope(scopeKind, scopeKey);
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }
}
