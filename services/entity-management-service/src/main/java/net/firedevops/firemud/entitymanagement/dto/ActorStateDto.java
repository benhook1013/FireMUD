package net.firedevops.firemud.entitymanagement.dto;

import java.util.List;

public record ActorStateDto(
    long tenantId,
    String gameInstanceId,
    long characterId,
    List<ActorResourceStateDto> resources,
    List<ActorConditionStateDto> activeConditions) {
  public ActorStateDto {
    resources = List.copyOf(resources);
    activeConditions = List.copyOf(activeConditions);
  }
}
