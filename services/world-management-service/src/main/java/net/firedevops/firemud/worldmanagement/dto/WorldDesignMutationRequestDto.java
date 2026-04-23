package net.firedevops.firemud.worldmanagement.dto;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

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
    String scopeMutationPolicy,
    RegionMutationDto region,
    ZoneMutationDto zone,
    RoomMutationDto room,
    RoomExitMutationDto roomExit,
    GenerationRuleMutationDto generationRule,
    WorldEntitySpawnBindingMutationDto worldEntitySpawnBinding,
    WorldGenerationSubtreeMutationDto worldGenerationSubtree) {
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

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Mutation DTO lists are request payload snapshots")
  public record WorldGenerationSubtreeMutationDto(
      List<GenerationRuleMutationDto> generationRules,
      List<GeneratedRoomMutationDto> rooms,
      List<GeneratedRoomExitMutationDto> roomExits,
      List<GeneratedWorldEntitySpawnBindingMutationDto> worldEntitySpawnBindings) {}

  public record GeneratedRoomMutationDto(
      String clientRef,
      String name,
      String description,
      String zoneId,
      String nameLocalizedVariantsJson,
      String descriptionLocalizedVariantsJson) {}

  public record GeneratedRoomExitMutationDto(
      String fromRoomRef, String toRoomRef, String direction, int cost) {}

  public record GeneratedWorldEntitySpawnBindingMutationDto(
      String roomRef,
      String entityTemplateType,
      String entityTemplateId,
      int spawnCount,
      int respawnDelaySeconds) {}
}
