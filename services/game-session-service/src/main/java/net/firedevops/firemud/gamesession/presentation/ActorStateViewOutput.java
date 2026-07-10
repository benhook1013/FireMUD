package net.firedevops.firemud.gamesession.presentation;

import java.util.List;

/** Player-visible projection of evaluated actor state without internal effect provenance. */
public record ActorStateViewOutput(List<Resource> resources, List<Condition> conditions)
    implements PlayerOutputPayload {
  public ActorStateViewOutput {
    resources = List.copyOf(resources == null ? List.of() : resources);
    conditions = List.copyOf(conditions == null ? List.of() : conditions);
  }

  public record Resource(String key, long currentValue, Long maxValue, Long baseValue) {}

  public record Condition(String key, int stackCount, String expiresAt) {}
}
