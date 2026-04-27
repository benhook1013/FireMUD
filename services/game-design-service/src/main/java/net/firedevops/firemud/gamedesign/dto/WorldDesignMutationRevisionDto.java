package net.firedevops.firemud.gamedesign.dto;

import java.util.List;

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
    WorldEntitySpawnBindingMutationDto worldEntitySpawnBinding,
    WorldGenerationSubtreeMutationDto worldGenerationSubtree) {
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

  public static final class WorldGenerationSubtreeMutationDto {
    private final List<GenerationRuleMutationDto> generationRules;
    private final List<GeneratedRoomMutationDto> rooms;
    private final List<GeneratedRoomExitMutationDto> roomExits;
    private final List<GeneratedWorldEntitySpawnBindingMutationDto> worldEntitySpawnBindings;

    public WorldGenerationSubtreeMutationDto(
        List<GenerationRuleMutationDto> generationRules,
        List<GeneratedRoomMutationDto> rooms,
        List<GeneratedRoomExitMutationDto> roomExits,
        List<GeneratedWorldEntitySpawnBindingMutationDto> worldEntitySpawnBindings) {
      this.generationRules = immutableList(generationRules);
      this.rooms = immutableList(rooms);
      this.roomExits = immutableList(roomExits);
      this.worldEntitySpawnBindings = immutableList(worldEntitySpawnBindings);
    }

    public List<GenerationRuleMutationDto> generationRules() {
      return List.copyOf(generationRules);
    }

    public List<GeneratedRoomMutationDto> rooms() {
      return List.copyOf(rooms);
    }

    public List<GeneratedRoomExitMutationDto> roomExits() {
      return List.copyOf(roomExits);
    }

    public List<GeneratedWorldEntitySpawnBindingMutationDto> worldEntitySpawnBindings() {
      return List.copyOf(worldEntitySpawnBindings);
    }
  }

  public record GeneratedRoomMutationDto(
      String clientRef,
      String name,
      String description,
      String zoneId,
      String nameLocalizedVariantsJson,
      String descriptionLocalizedVariantsJson) {}

  public record GeneratedRoomExitMutationDto(
      String fromRoomRef, String toRoomRef, String direction, Integer cost) {}

  public record GeneratedWorldEntitySpawnBindingMutationDto(
      String roomRef,
      String entityTemplateType,
      String entityTemplateId,
      Integer spawnCount,
      Integer respawnDelaySeconds) {}

  private static <T> List<T> immutableList(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
