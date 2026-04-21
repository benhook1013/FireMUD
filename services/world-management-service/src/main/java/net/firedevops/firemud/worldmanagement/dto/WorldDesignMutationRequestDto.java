package net.firedevops.firemud.worldmanagement.dto;

public record WorldDesignMutationRequestDto(
    long tenantId,
    long versionId,
    String commitId,
    String revisionId,
    String operationType,
    String aggregateType,
    String aggregateId,
    long expectedDraftRevisionEpoch,
    String scopeType,
    String scopeId,
    long expectedDraftScopeRevisionEpoch,
    RegionMutationDto region,
    ZoneMutationDto zone,
    RoomMutationDto room,
    RoomExitMutationDto roomExit,
    GenerationRuleMutationDto generationRule,
    WorldEntitySpawnBindingMutationDto worldEntitySpawnBinding) {
  public record RegionMutationDto(
      String name,
      String weather,
      int shardId,
      long generationSeed,
      String generatorType,
      String generatorParams,
      double spacingMultiplier) {}

  public record ZoneMutationDto(String name, String regionId) {}

  public record RoomMutationDto(
      String name,
      String description,
      String zoneId,
      String nameLocalizedVariantsJson,
      String descriptionLocalizedVariantsJson) {}

  public record RoomExitMutationDto(
      String fromRoomId, String toRoomId, String direction, int cost) {}

  public record GenerationRuleMutationDto(String name, String value) {}

  public record WorldEntitySpawnBindingMutationDto(
      String roomId,
      String entityTemplateType,
      String entityTemplateId,
      int spawnCount,
      int respawnDelaySeconds) {}
}
