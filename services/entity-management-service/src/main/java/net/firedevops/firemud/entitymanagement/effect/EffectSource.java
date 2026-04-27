package net.firedevops.firemud.entitymanagement.effect;

public record EffectSource(String sourceType, String sourceId, String actorId) {
  public EffectSource {
    sourceType = normalize(sourceType);
    sourceId = normalize(sourceId);
    actorId = normalize(actorId);
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }
}
