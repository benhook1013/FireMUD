package net.firedevops.firemud.gamedesign.dto;

public record WorldDesignMutationRevisionDto(
    String logicalRevisionId,
    String commitId,
    String operation,
    String aggregateType,
    String aggregateId,
    Long expectedDraftRevisionEpoch,
    String scopeType,
    String scopeId,
    Long expectedDraftScopeRevisionEpoch,
    String scopeMutationPolicy,
    RegionMutationDto region,
    ZoneMutationDto zone,
    RoomMutationDto room,
    RoomExitMutationDto roomExit,
    GenerationRuleMutationDto generationRule,
    WorldEntitySpawnBindingMutationDto worldEntitySpawnBinding) {
  public record RegionMutationDto(
      String name,
      String weather,
      Integer shardId,
      Long generationSeed,
      String generatorType,
      String generatorParams,
      Double spacingMultiplier) {}

  public record ZoneMutationDto(String name, String regionId) {}

  public record RoomMutationDto(
      String name,
      String description,
      String zoneId,
      String nameLocalizedVariantsJson,
      String descriptionLocalizedVariantsJson) {}

  public record RoomExitMutationDto(
      String fromRoomId, String toRoomId, String direction, Integer cost) {}

  public record GenerationRuleMutationDto(String name, String value) {}

  public record WorldEntitySpawnBindingMutationDto(
      String roomId,
      String entityTemplateType,
      String entityTemplateId,
      Integer spawnCount,
      Integer respawnDelaySeconds) {}
}
